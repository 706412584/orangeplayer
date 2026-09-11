package com.orange.playerlibrary.ai;

import android.content.Context;
import android.util.Log;

import com.orange.playerlibrary.ocr.MlKitTranslationEngine;
import com.orange.playerlibrary.ocr.TranslationEngine;
import com.orange.playerlibrary.subtitle.SubtitleEntry;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 渐进翻译调度器：ASR 边识别边翻译。
 *
 * 复用既有能力（不重复造轮子）：
 *  - AI 批量翻译：{@link AiTranslationEngine}（BatchPlanner 分批 + TranslationCacheStore
 *    落盘缓存 + Glossary 术语表），缓存 key 与手动「AI 翻译」一致，两者互相复用译文，
 *    重看不重复计费；每次提交传全量行列表，旧行命中缓存、只翻译新增块
 *  - 本地兜底：{@link MlKitTranslationEngine}（MLKit 离线模型），未配置 API Key 或
 *    AI 调用失败时使用；源语言取 ASR 检测到的语种（auto 模式下由 SenseVoice 回报），
 *    目标语言从 AI 设置的目标语言映射为 MLKit 语言码
 *
 * 下标对齐：字幕按块顺序追加，本类按同样顺序累计下标；每批译文通过
 * {@code onTranslated(startIdx, ...)} 回写，由调用方写入对应区间。
 */
public class ProgressiveTranslator {

    private static final String TAG = "ProgressiveTranslator";

    /** 本地兜底单条翻译超时 */
    private static final long LOCAL_TIMEOUT_SEC = 20;

    public interface Callback {
        /**
         * 某批翻译完成（主线程）。
         *
         * @param startIdx   该批首条在原字幕列表中的下标
         * @param translated 与提交顺序一致的译文（失败位置为 null）
         */
        void onTranslated(int startIdx, String[] translated);

        /** 状态提示（主线程，可空） */
        default void onStatus(String message) {
        }
    }

    private final Context mAppContext;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    /** 已提交的全部行（idx 与字幕列表下标一致；传全量给引擎以命中缓存） */
    private final List<SubtitleLine> mAllLines = new ArrayList<>();
    private final AtomicInteger mSubmittedCount = new AtomicInteger(0);

    /** 世代号：reset 后作废在途任务 */
    private volatile int mGeneration;

    private volatile String mVideoUrl;
    private volatile String mTargetLang;
    /** 源语言：ASR 为 auto 时由检测结果动态更新 */
    private volatile String mSourceLang;
    /** AI 配置；null 表示未配置 → 直接走本地兜底 */
    private volatile TranslatorSettings mAiSettings;
    private volatile Callback mCallback;

    private File mCacheRoot;
    private String mCacheKey;

    /** 本地兜底引擎（懒创建） */
    private volatile MlKitTranslationEngine mLocalEngine;
    private volatile String mLocalEngineKey;
    private final AtomicInteger mLocalFailStreak = new AtomicInteger(0);

    public ProgressiveTranslator(Context context) {
        mAppContext = context.getApplicationContext();
    }

    /**
     * 配置一个会话（换视频/重新识别时调用）。
     *
     * @param videoUrl     视频标识（用于缓存 key，与其他视频区分）
     * @param sourceLang   ASR 源语言；传 "auto" 或 null 表示由检测结果决定
     * @param targetLang   目标语言（AI 设置中的值，如「简体中文」）
     * @param aiSettings   AI 接入配置；null 表示未配置 → 本地兜底
     */
    public void configure(String videoUrl, String sourceLang, String targetLang,
                          TranslatorSettings aiSettings, Callback callback) {
        reset();
        mVideoUrl = videoUrl;
        mSourceLang = normalizeSourceLang(sourceLang);
        mTargetLang = targetLang;
        mAiSettings = aiSettings;
        mCallback = callback;
        mCacheRoot = new File(mAppContext.getCacheDir(), "ai_translation");
        // 与手动「AI 翻译」保持一致的 key：两条路径互相复用译文缓存
        mCacheKey = "sub=|url=" + (videoUrl == null ? "" : videoUrl)
                + "|to=" + (targetLang == null ? "" : targetLang);
        Log.d(TAG, "configure: url=" + videoUrl + " src=" + mSourceLang
                + " tgt=" + targetLang + " ai=" + (aiSettings != null));
    }

    /** ASR 检测到语种（auto 模式下替换初始值；已有译文不受影响） */
    public void onLanguageDetected(String lang) {
        String normalized = normalizeSourceLang(lang);
        if (normalized != null && !normalized.equals(mSourceLang)) {
            Log.d(TAG, "源语言更新: " + mSourceLang + " -> " + normalized);
            mSourceLang = normalized;
        }
    }

    /**
     * 提交一批新字幕（与字幕追加同序）。
     *
     * @return 该批首条在字幕列表中的下标；未启用/已释放返回 -1
     */
    public int submit(List<SubtitleEntry> entries) {
        if (entries == null || entries.isEmpty() || mTargetLang == null
                || mTargetLang.trim().isEmpty()) {
            return -1;
        }
        final int startIdx = mSubmittedCount.getAndAdd(entries.size());
        synchronized (mAllLines) {
            for (SubtitleEntry e : entries) {
                mAllLines.add(new SubtitleLine(mAllLines.size(),
                        e.getText() == null ? "" : e.getText()));
            }
        }
        final int generation = mGeneration;
        mExecutor.execute(() -> {
            try {
                runBatch(generation, startIdx, entries.size());
            } catch (Throwable t) {
                Log.w(TAG, "批次翻译失败 @" + startIdx, t);
            }
        });
        return startIdx;
    }

    private void runBatch(int generation, int startIdx, int count) {
        if (generation != mGeneration) {
            return;   // 已换视频/重置
        }
        TranslatorSettings settings = mAiSettings;
        String[] translated;
        if (settings != null) {
            translated = translateWithAi(generation, startIdx, count, settings);
            if (translated == null && generation == mGeneration) {
                // AI 不可用（网络/Key/额度）→ 本地兜底
                notifyStatus("AI 翻译不可用，改用本地翻译");
                translated = translateLocal(generation, startIdx, count);
            }
        } else {
            translated = translateLocal(generation, startIdx, count);
        }
        if (translated == null || generation != mGeneration) {
            return;
        }
        final String[] result = translated;
        Callback cb = mCallback;
        if (cb != null) {
            cb.onTranslated(startIdx, result);
        }
    }

    /** AI 批量翻译（全量传入 → 旧行命中落盘缓存，只翻新增块） */
    private String[] translateWithAi(int generation, int startIdx, int count,
                                     TranslatorSettings settings) {
        List<SubtitleLine> snapshot;
        synchronized (mAllLines) {
            snapshot = new ArrayList<>(mAllLines);
        }
        try {
            AiTranslationEngine engine = new AiTranslationEngine(
                    new OpenAiCompatibleProvider());
            AiTranslationEngine.Result result = engine.translateAll(
                    mCacheRoot, mCacheKey, snapshot, settings, null);
            if (generation != mGeneration) {
                return null;
            }
            String[] out = new String[count];
            int ok = 0;
            for (int i = 0; i < count; i++) {
                int idx = startIdx + i;
                if (idx < result.lines.size()) {
                    SubtitleLine line = result.lines.get(idx);
                    if (line.hasTranslation()) {
                        out[i] = line.getTranslated();
                        ok++;
                    }
                }
            }
            Log.d(TAG, "AI 批次完成 @" + startIdx + ": " + ok + "/" + count);
            // 全部失败视为 AI 不可用（Key/网络），交由本地兜底
            return ok == 0 && count > 0 ? null : out;
        } catch (Throwable t) {
            Log.w(TAG, "AI 翻译异常，转本地兜底", t);
            return null;
        }
    }

    /** 本地兜底：MLKit 逐条翻译（复用同一份落盘缓存） */
    private String[] translateLocal(int generation, int startIdx, int count) {
        if (!ensureLocalEngine()) {
            return null;
        }
        String[] cache = null;
        try {
            TranslationCacheStore store = new TranslationCacheStore(mCacheRoot, mCacheKey);
            cache = store.load();
        } catch (Throwable ignored) {
        }
        String[] out = new String[count];
        int ok = 0;
        int translatedNow = 0;
        for (int i = 0; i < count; i++) {
            if (generation != mGeneration) {
                return null;
            }
            int idx = startIdx + i;
            // 命中既有缓存（可能来自手动 AI 翻译/上次播放）
            if (cache != null && idx < cache.length && cache[idx] != null) {
                out[i] = cache[idx];
                ok++;
                continue;
            }
            List<SubtitleLine> src;
            synchronized (mAllLines) {
                if (idx >= mAllLines.size()) {
                    break;
                }
                src = new ArrayList<>();
                src.add(mAllLines.get(idx));
            }
            String text = src.get(0).getText();
            if (text.trim().isEmpty()) {
                continue;
            }
            String t = translateOneLocal(text.trim());
            if (t != null && !t.trim().isEmpty()) {
                out[i] = t.trim();
                ok++;
                translatedNow++;
                mLocalFailStreak.set(0);
            } else if (mLocalFailStreak.incrementAndGet() >= 3) {
                Log.w(TAG, "本地翻译连续失败，放弃本批");
                notifyStatus("本地翻译不可用（模型未就绪？）");
                break;
            }
        }
        if (translatedNow > 0) {
            saveLocalCache(out, startIdx);
        }
        Log.d(TAG, "本地批次完成 @" + startIdx + ": " + ok + "/" + count);
        return ok == 0 && count > 0 ? null : out;
    }

    /** 把本批译文并入缓存文件（idx 对齐） */
    private void saveLocalCache(String[] batch, int startIdx) {
        try {
            TranslationCacheStore store = new TranslationCacheStore(mCacheRoot, mCacheKey);
            String[] merged = store.load();
            int need = startIdx + batch.length;
            if (merged == null || merged.length < need) {
                String[] grown = new String[need];
                if (merged != null) {
                    System.arraycopy(merged, 0, grown, 0, merged.length);
                }
                merged = grown;
            }
            for (int i = 0; i < batch.length; i++) {
                if (batch[i] != null) {
                    merged[startIdx + i] = batch[i];
                }
            }
            store.save(merged, null);
        } catch (Throwable t) {
            Log.w(TAG, "本地译文缓存写入失败（不影响显示）", t);
        }
    }

    /** 懒创建/重建本地引擎（源语言或目标语言变化时重建） */
    private boolean ensureLocalEngine() {
        String src = mSourceLang;
        String tgtCode = mapToMlKitCode(mTargetLang);
        if (src == null || tgtCode == null) {
            Log.d(TAG, "本地兜底跳过：源语言(" + src + ")或目标语言(" + mTargetLang + ")无法映射");
            return false;
        }
        String key = src + "->" + tgtCode;
        MlKitTranslationEngine engine = mLocalEngine;
        if (engine != null && key.equals(mLocalEngineKey) && engine.isInitialized()) {
            return true;
        }
        if (!com.orange.playerlibrary.ocr.OcrAvailabilityChecker.isMlKitTranslateAvailable()) {
            Log.d(TAG, "本地兜底不可用：MLKit 未接入");
            return false;
        }
        if (engine != null) {
            try {
                engine.release();
            } catch (Throwable ignored) {
            }
        }
        engine = new MlKitTranslationEngine();
        engine.init(mAppContext, src, tgtCode);
        mLocalEngine = engine;
        mLocalEngineKey = key;
        // 模型未下载：触发后台下载，本批跳过（下批可用）
        if (!engine.isModelDownloaded()) {
            Log.d(TAG, "本地翻译模型未下载，触发下载");
            notifyStatus("正在下载本地翻译模型…");
            engine.downloadModel(new TranslationEngine.ModelDownloadCallback() {
                @Override
                public void onProgress(int progress) {
                }

                @Override
                public void onSuccess() {
                    Log.d(TAG, "本地翻译模型下载完成");
                    notifyStatus("本地翻译模型已就绪");
                }

                @Override
                public void onError(String error) {
                    Log.w(TAG, "本地翻译模型下载失败: " + error);
                }
            });
            return false;
        }
        return true;
    }

    /** 单条本地翻译（回调转同步） */
    private String translateOneLocal(String text) {
        final MlKitTranslationEngine engine = mLocalEngine;
        if (engine == null) {
            return null;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();
        engine.translate(text, new TranslationEngine.TranslationCallback() {
            @Override
            public void onSuccess(String translatedText) {
                result.set(translatedText);
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(LOCAL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result.get();
    }

    /** 目标/源语言显示名 → MLKit 语言码 */
    static String mapToMlKitCode(String lang) {
        if (lang == null) {
            return null;
        }
        String s = lang.trim().toLowerCase();
        switch (s) {
            case "zh":
            case "chinese":
            case "chi_sim":
            case "chi_tra":
            case "简体中文":
            case "繁体中文":
            case "中文":
                return "zh";
            case "en":
            case "english":
            case "eng":
            case "英语":
            case "英文":
                return "en";
            case "ja":
            case "japanese":
            case "jpn":
            case "日语":
            case "日文":
                return "ja";
            case "ko":
            case "korean":
            case "kor":
            case "韩语":
            case "韩文":
                return "ko";
            case "fr":
            case "french":
            case "fra":
            case "法语":
                return "fr";
            case "de":
            case "german":
            case "deu":
            case "德语":
                return "de";
            case "es":
            case "spanish":
            case "spa":
            case "西班牙语":
                return "es";
            case "ru":
            case "russian":
            case "rus":
            case "俄语":
                return "ru";
            case "ar":
            case "arabic":
            case "ara":
            case "阿拉伯语":
                return "ar";
            case "th":
            case "thai":
            case "tha":
            case "泰语":
                return "th";
            case "vi":
            case "vietnamese":
            case "vie":
            case "越南语":
                return "vi";
            default:
                return null;
        }
    }

    /** "auto"/空 → null（等检测结果）；其余小写化 */
    private static String normalizeSourceLang(String lang) {
        if (lang == null) {
            return null;
        }
        String s = lang.trim().toLowerCase();
        if (s.isEmpty() || "auto".equals(s)) {
            return null;
        }
        return s;
    }

    /** 重置（换视频/停止识别）：作废在途任务与已累积下标 */
    public void reset() {
        mGeneration++;
        mSubmittedCount.set(0);
        synchronized (mAllLines) {
            mAllLines.clear();
        }
        mLocalFailStreak.set(0);
        mCallback = null;
    }

    public void release() {
        reset();
        MlKitTranslationEngine engine = mLocalEngine;
        mLocalEngine = null;
        mLocalEngineKey = null;
        if (engine != null) {
            try {
                engine.release();
            } catch (Throwable ignored) {
            }
        }
        mExecutor.shutdown();
    }

    private void notifyStatus(String message) {
        Callback cb = mCallback;
        if (cb != null) {
            cb.onStatus(message);
        }
    }
}
