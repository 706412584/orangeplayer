package com.orange.playerlibrary.ai;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 批翻译核心编排器。
 *
 * 设计要点：
 * 1. 分批（BatchPlanner 字符预算）→ 每批一次 LLM 调用，网络往返从"逐句×N"降为"N/批"；
 * 2. 术语表（Glossary）批间累积并随每批 system 消息下发，保证专名全片一致；
 * 3. 上文衔接：非首批在 system 中携带上一批译文末尾若干句，避免风格/代词断裂；
 * 4. 断点续传：每批成功后立即写盘（TranslationCacheStore），重跑时跳过已译 idx；
 * 5. 失败隔离：瞬时错误退避重试（指数），耗尽后跳过该批继续，错误汇总在结果中，
 *    调用方可再次调用 translateAll 续传（未译 idx 自动重试）。
 *
 * 引擎为同步核心，线程模型由调用方决定（建议单后台线程）。
 */
public class AiTranslationEngine {

    public interface ProgressListener {
        /** 每成功翻译一批（含缓存命中批次计入）后回调 */
        void onProgress(int translatedCount, int totalCount);
    }

    public static class Result {
        /** 与输入同序的翻译结果（原文 text 保留，translated 成功时填充） */
        public final List<SubtitleLine> lines;
        /** 本次调用翻译成功的行数（不含命中缓存，累计口径走 onProgress） */
        public final int translatedThisRun;
        /** 命中缓存的行数 */
        public final int cachedHits;
        /** 未译行（translated 仍为 null）—— 重试对象 */
        public final int untranslated;
        /** 失败批信息（批内 idx 区间 + 原因） */
        public final List<String> errors;

        Result(List<SubtitleLine> lines, int translatedThisRun, int cachedHits,
               int untranslated, List<String> errors) {
            this.lines = lines;
            this.translatedThisRun = translatedThisRun;
            this.cachedHits = cachedHits;
            this.untranslated = untranslated;
            this.errors = errors;
        }
    }

    private static final String SYSTEM_TEMPLATE =
            "你是专业字幕译者。把用户给出的字幕行翻译成%s。" +
            "要求：口语自然、保留语气与标点风格；专名术语必须与下表一致；不得合并或拆分行；" +
            "每行只输出一行译文，格式为 序号|译文，不要输出序号外的解释。%s";

    private final AiProvider provider;
    private final ResponseParser parser;

    public AiTranslationEngine(AiProvider provider) {
        this(provider, new ResponseParser());
    }

    AiTranslationEngine(AiProvider provider, ResponseParser parser) {
        this.provider = provider;
        this.parser = parser;
    }

    /**
     * 翻译全部字幕行。
     *
     * @param cacheDir   缓存目录（每批落盘；传 null 关闭缓存）
     * @param cacheKey   缓存标识，建议 videoUri+源语言+目标语言+model（内部 hash）
     * @param lines      按 idx 升序的字幕行（idx 必须连续 0..n-1）
     * @param settings   接入配置
     * @param listener   进度回调（可 null）
     */
    public Result translateAll(File cacheDir, String cacheKey, List<SubtitleLine> lines,
                               TranslatorSettings settings, ProgressListener listener) {
        TranslationCacheStore store = cacheDir == null ? null
                : new TranslationCacheStore(cacheDir, cacheKey);
        String[] cached = store == null ? null : store.load();
        Glossary glossary = store == null ? new Glossary() : store.loadGlossary();

        // 与输入对齐的译文槽位；命中缓存的直接填入
        String[] out = new String[lines.size()];
        int cachedHits = 0;
        for (SubtitleLine line : lines) {
            int i = line.getIdx();
            if (i >= 0 && i < lines.size() && cached != null
                    && i < cached.length && cached[i] != null) {
                out[i] = cached[i];
                cachedHits++;
            }
        }

        BatchPlanner planner = new BatchPlanner();
        List<BatchPlanner.Batch> batches = planner.plan(lines,
                settings.getBatchCharBudget(), settings.getBatchMaxLines());

        int total = countNonBlank(lines);
        int translated = cachedHits;
        List<String> errors = new ArrayList<>();
        List<String> contextTail = new ArrayList<>();   // 上批译文末尾，用于衔接

        for (int bi = 0; bi < batches.size(); bi++) {
            BatchPlanner.Batch batch = batches.get(bi);
            // 过滤本批中已译（缓存命中）的行
            List<SubtitleLine> todo = new ArrayList<>();
            for (SubtitleLine l : batch.lines) {
                if (l.getIdx() >= 0 && l.getIdx() < out.length && out[l.getIdx()] == null) {
                    todo.add(l);
                }
            }
            if (todo.isEmpty()) {
                continue;
            }

            AiException lastError = null;
            boolean done = false;
            int attempts = 0;
            while (attempts <= settings.getMaxRetries() && !done) {
                attempts++;
                try {
                    String reply = provider.chat(buildMessages(todo, glossary,
                            contextTail, settings), settings);
                    ResponseParser.ParseResult parsed = parser.parse(reply);

                    // 只接受属于本批的 idx（防御模型乱序/串批）
                    for (ResponseParser.ParseResult.Translation t : parsed.translations) {
                        if (t.idx >= 0 && t.idx < lines.size() && out[t.idx] == null) {
                            out[t.idx] = t.text;
                        }
                    }
                    // 术语增量合并
                    for (java.util.Map.Entry<String, String> e
                            : parsed.glossary.getEntries().entrySet()) {
                        glossary.put(e.getKey(), e.getValue());
                    }
                    // 上文尾巴：取本批成功译文末尾 contextSentences 句
                    contextTail = collectTail(todo, out, settings.getContextSentences());
                    if (store != null) {
                        try {
                            store.save(out, glossary);
                        } catch (java.io.IOException e) {
                            // 缓存写失败不阻断翻译（下次重译即可）
                        }
                    }
                    done = true;
                } catch (AiException e) {
                    lastError = e;
                    if (!e.isRetryable() || attempts > settings.getMaxRetries()) {
                        break;
                    }
                    sleepBackoff(attempts);
                }
            }

            if (done) {
                int newly = countFilled(todo, out);
                translated += newly;
                if (listener != null) {
                    listener.onProgress(translated, total);
                }
            } else {
                String msg = "第" + (bi + 1) + "/" + batches.size() + "批失败"
                        + (lastError != null ? ": " + lastError.getMessage() : "")
                        + "（行 " + describeRange(todo) + "，可重试）";
                errors.add(msg);
                if (listener != null) {
                    listener.onProgress(translated, total);
                }
            }
        }

        for (SubtitleLine line : lines) {
            int i = line.getIdx();
            if (i >= 0 && i < out.length && out[i] != null) {
                line.setTranslated(out[i]);
            }
        }
        int untranslated = countNonBlank(lines) - countFilledAll(out, lines);
        return new Result(lines, translated - cachedHits, cachedHits, untranslated, errors);
    }

    // ===== 消息构造 =====

    List<ChatMessage> buildMessages(List<SubtitleLine> batch, Glossary glossary,
                                    List<String> contextTail, TranslatorSettings settings) {
        StringBuilder sys = new StringBuilder();
        sys.append(String.format(SYSTEM_TEMPLATE, settings.getTargetLanguage(), ""));

        if (!glossary.isEmpty()) {
            sys.append("\n已知术语对照（务必遵守）：\n").append(glossary.toText());
        }
        if (!contextTail.isEmpty()) {
            sys.append("\n上一段已译内容（衔接风格，不要重复翻译它们）：\n");
            for (String t : contextTail) {
                sys.append("- ").append(t).append('\n');
            }
        }

        StringBuilder user = new StringBuilder();
        for (SubtitleLine l : batch) {
            user.append(l.getIdx()).append('|').append(l.getText()).append('\n');
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage(ChatMessage.ROLE_SYSTEM, sys.toString()));
        messages.add(new ChatMessage(ChatMessage.ROLE_USER, user.toString()));
        return messages;
    }

    // ===== 工具 =====

    private List<String> collectTail(List<SubtitleLine> batch, String[] out, int n) {
        List<String> tail = new ArrayList<>();
        for (int i = batch.size() - 1; i >= 0 && tail.size() < n; i--) {
            SubtitleLine l = batch.get(i);
            int idx = l.getIdx();
            if (idx >= 0 && idx < out.length && out[idx] != null && !out[idx].trim().isEmpty()) {
                tail.add(0, out[idx]);
            }
        }
        return tail;
    }

    private int countFilled(List<SubtitleLine> batch, String[] out) {
        int c = 0;
        for (SubtitleLine l : batch) {
            int idx = l.getIdx();
            if (idx >= 0 && idx < out.length && out[idx] != null) {
                c++;
            }
        }
        return c;
    }

    private int countFilledAll(String[] out, List<SubtitleLine> lines) {
        int c = 0;
        for (SubtitleLine l : lines) {
            int idx = l.getIdx();
            if (idx >= 0 && idx < out.length && out[idx] != null) {
                c++;
            }
        }
        return c;
    }

    private int countNonBlank(List<SubtitleLine> lines) {
        int c = 0;
        for (SubtitleLine l : lines) {
            if (l.getText() != null && !l.getText().trim().isEmpty()) {
                c++;
            }
        }
        return c;
    }

    private String describeRange(List<SubtitleLine> batch) {
        if (batch.isEmpty()) {
            return "";
        }
        int first = batch.get(0).getIdx();
        int last = batch.get(batch.size() - 1).getIdx();
        return first == last ? "#" + first : "#" + first + "~#" + last;
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(1000L * (1L << (attempt - 1)));   // 1s, 2s, 4s
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
