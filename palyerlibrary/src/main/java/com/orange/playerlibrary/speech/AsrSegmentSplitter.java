package com.orange.playerlibrary.speech;

import com.orange.playerlibrary.subtitle.SubtitleEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * ASR 长段字幕切分器（纯逻辑，可单测）。
 *
 * 背景：SenseVoice 是**非流式、无词级时间戳**的模型——sherpa 只按 silero VAD
 * 给出的语音段边界回报时间轴。真机实测连续旁白（无 0.3s 以上静音）的 VAD 段
 * 可达 **20s 以上**，整段作为一条字幕就是「一条字幕横跨十几秒」。
 *
 * 做法：段边界（VAD）是真实时间，段内时间按**字数比例插值**切分，
 * 切分点优先落在标点上（SenseVoice 自带标点），避免把词组劈开；
 * 分配后做**上限钳制 + 溢出再分配**，保证单条不超过 {@link #MAX_ENTRY_MS}。
 * 段首尾与 VAD 严格对齐，不累积漂移。代价是段内时间轴为近似值——
 * 这是无词级时间戳模型的固有限制。
 */
public final class AsrSegmentSplitter {

    /** 单条字幕最长时长：硬上限，分配后钳制保证（用户观感「好几秒一大段」的来源） */
    static final long MAX_ENTRY_MS = 4000;

    /** 分配目标时长：据此估算切分段数，给字数不均留余量（须明显小于上限） */
    static final long ALLOC_TARGET_MS = 3000;

    /** 单条字幕最短时长：低于则并入相邻条，避免一闪而过 */
    static final long MIN_ENTRY_MS = 900;

    /** 单条字幕软上限字数：即使时长短，字太多也切（中文按字符计） */
    static final int SOFT_MAX_CHARS = 16;

    /** 切分点搜索窗口（字符）：在理想位置附近找标点，找不到就硬切 */
    private static final int SEARCH_RADIUS = 8;

    /** 单段最少字数：保证标点切分不产生极短段 */
    private static final int MIN_CHUNK_CHARS = 4;

    /** 句末标点：优先在此后切分 */
    private static final String STRONG_PUNCT = "。！？!?…";

    /** 子句标点：其次 */
    private static final String WEAK_PUNCT = "，、；：,;:";

    private AsrSegmentSplitter() {
    }

    /**
     * 把一段识别结果切成若干条字幕。
     * 时间轴：首条 start = startMs，末条 end = endMs，中间按字数比例分配，
     * 相邻条目时间严格连续不重叠。
     *
     * @param text    VAD 段识别文本（已清洗事件标记）
     * @param startMs 段起点（绝对时间，VAD 边界）
     * @param endMs   段终点（绝对时间）
     * @return 字幕条目；文本为空时返回空列表
     */
    public static List<SubtitleEntry> split(String text, long startMs, long endMs) {
        List<SubtitleEntry> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return out;
        }
        long duration = endMs - startMs;
        if (duration <= 0) {
            // 时间信息不可用：不猜，整段给一个零长区间由调用方处理
            out.add(new SubtitleEntry(startMs, endMs, t));
            return out;
        }

        int n = chunkCount(t.length(), duration);
        if (n <= 1) {
            out.add(new SubtitleEntry(startMs, endMs, t));
            return out;
        }

        // 字数分布不均时，按比例分配可能让某条顶到上限而被迫截断（时间不守恒），
        // 迭代增加段数直到不再截断；上限保护避免病态输入死循环。
        List<SubtitleEntry> candidate = null;
        for (int attempt = 0; attempt < 8 && n <= MAX_CHUNKS; attempt++, n++) {
            candidate = new ArrayList<>();
            boolean truncated = allocateTime(splitText(t, n), startMs, endMs, candidate);
            if (!truncated && !hasOverflow(candidate)) {
                out.addAll(candidate);
                return out;
            }
        }
        // 兜底：段数已给足仍截断（极短时长 + 极多字），取最后一次结果
        if (candidate != null) {
            out.addAll(candidate);
        }
        return out;
    }

    /** 段数上限：防止极端输入下无限加段 */
    private static final int MAX_CHUNKS = 64;

    /** 是否存在超过上限的条目（短于下限的容忍，合并逻辑已尽力） */
    private static boolean hasOverflow(List<SubtitleEntry> entries) {
        for (SubtitleEntry e : entries) {
            if (e.getDuration() > MAX_ENTRY_MS) {
                return true;
            }
        }
        return false;
    }

    /** 需要切成几段：按目标时长估算（留余量），字数过多再加码 */
    private static int chunkCount(int charCount, long durationMs) {
        int byDuration = (int) ((durationMs + ALLOC_TARGET_MS - 1) / ALLOC_TARGET_MS);
        int byChars = (charCount + SOFT_MAX_CHARS - 1) / SOFT_MAX_CHARS;
        return Math.max(1, Math.max(byDuration, byChars));
    }

    /** 按标点优先切成 n 段文本；保留原始字符（含空格），保证拼接可还原原文 */
    private static List<String> splitText(String text, int n) {
        int len = text.length();
        List<String> chunks = new ArrayList<>(n);
        int from = 0;
        for (int i = 1; i < n; i++) {
            int ideal = (int) ((long) len * i / n);
            if (ideal <= from) {
                continue;   // 前一次已越界，跳过（段数退化时兜底）
            }
            int cut = findCutPoint(text, from, ideal, len);
            if (cut <= from) {
                continue;
            }
            chunks.add(text.substring(from, cut));
            from = cut;
        }
        if (from < len) {
            chunks.add(text.substring(from));
        }
        chunks.removeIf(String::isEmpty);
        return chunks;
    }

    /**
     * 在理想切分点附近找标点位置，返回切分下标（标点之后）。
     * 取**最接近理想位置**的标点（而非最靠后），并要求两侧都不少于
     * {@link #MIN_CHUNK_CHARS} 字——否则会切出极短尾段，被后续合并逻辑
     * 回吞成超长条目（真机实测出现过 4.9s 条目）。
     */
    private static int findCutPoint(String text, int from, int ideal, int len) {
        int strong = searchPunct(text, from, ideal, len, STRONG_PUNCT);
        if (strong > 0) {
            return strong;
        }
        int weak = searchPunct(text, from, ideal, len, WEAK_PUNCT);
        if (weak > 0) {
            return weak;
        }
        return Math.min(Math.max(ideal, from + 1), len);
    }

    /** 找 [ideal-RADIUS, ideal+RADIUS] 内最接近 ideal 的标点，返回其后一位；无则 -1 */
    private static int searchPunct(String text, int from, int ideal, int len, String puncts) {
        int lo = Math.max(from + MIN_CHUNK_CHARS, ideal - SEARCH_RADIUS);
        int hi = Math.min(len - MIN_CHUNK_CHARS, ideal + SEARCH_RADIUS);
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = lo; i <= hi; i++) {
            if (puncts.indexOf(text.charAt(i)) < 0) {
                continue;
            }
            int distance = Math.abs(i + 1 - ideal);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i + 1;   // 标点之后切分，标点留在前一条
            }
        }
        return best;
    }

    /**
     * 按字数比例把 [startMs, endMs] 分配给各段。
     *
     * 用**滚动分配**而非各自独立按比例算：每条按「剩余时长 × 本条字数 / 剩余字数」
     * 取时长并截断到上限，截断掉的余量自动滚给后续条目，末条吸收全部剩余。
     *
     * @return 是否发生过截断（时间不守恒，调用方应增加段数重试）
     */
    private static boolean allocateTime(List<String> chunks, long startMs, long endMs,
                                        List<SubtitleEntry> out) {
        int n = chunks.size();
        int totalChars = 0;
        for (String c : chunks) {
            totalChars += c.length();
        }
        if (totalChars <= 0) {
            out.add(new SubtitleEntry(startMs, endMs, String.join("", chunks)));
            return false;
        }

        boolean truncated = false;
        long cursor = startMs;
        int remainingChars = totalChars;
        for (int i = 0; i < n; i++) {
            int chars = chunks.get(i).length();
            long remainingMs = endMs - cursor;
            long want = (i == n - 1)
                    ? remainingMs                                   // 末条吸收剩余
                    : remainingMs * chars / Math.max(1, remainingChars);
            // 给后续条目至少留 1ms/条，保证时间严格递增
            long maxForThis = Math.min(MAX_ENTRY_MS, remainingMs - (n - 1 - i));
            if (want > maxForThis) {
                want = maxForThis;
                truncated = true;
            }
            if (want < 1) {
                want = 1;
            }
            long end = cursor + want;
            if (end > endMs) {
                end = endMs;
            }
            out.add(new SubtitleEntry(cursor, end, chunks.get(i)));
            cursor = end;
            remainingChars -= chars;
        }
        mergeTooShort(out);
        return truncated;
    }

    /**
     * 合并过短条目（< MIN_ENTRY_MS）到相邻条：避免切分后出现一闪而过的字幕。
     * 首条过短则并入次条（时间前移），其余并入前条（时间后延）。
     *
     * 但合并不允许把条目撑过 {@link #MAX_ENTRY_MS}——「一条字幕横跨好几秒」
     * 是首要问题，宁可留一条略短的，也不回吞成超长条。
     */
    private static void mergeTooShort(List<SubtitleEntry> entries) {
        for (int i = entries.size() - 1; i >= 0 && entries.size() > 1; i--) {
            SubtitleEntry e = entries.get(i);
            if (e.getDuration() >= MIN_ENTRY_MS) {
                continue;
            }
            if (i == 0) {
                SubtitleEntry next = entries.get(1);
                long merged = next.getEndTime() - e.getStartTime();
                if (merged > MAX_ENTRY_MS) {
                    continue;
                }
                next.setStartTime(e.getStartTime());
                next.setText(e.getText() + next.getText());
                entries.remove(0);
            } else {
                SubtitleEntry prev = entries.get(i - 1);
                long merged = e.getEndTime() - prev.getStartTime();
                if (merged > MAX_ENTRY_MS) {
                    continue;
                }
                prev.setEndTime(e.getEndTime());
                prev.setText(prev.getText() + e.getText());
                entries.remove(i);
            }
        }
    }
}
