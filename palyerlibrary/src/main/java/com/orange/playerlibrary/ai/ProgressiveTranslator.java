package com.orange.playerlibrary.ai;

import android.content.Context;
import android.util.Log;

import com.orange.playerlibrary.ocr.MlKitTranslationEngine;
import com.orange.playerlibrary.ocr.TranslationEngine;
import com.orange.playerlibrary.subtitle.SubtitleEntry;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
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

    /** 世代号：reset 后作废在途任务 */
    private volatile int mGeneration;

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
     * 提交一批新字幕。
     *
     * @param startIdx 本批首条在字幕列表中的**实际下标**（由
     *                 {@code SubtitleManager.AppendCallback} 回传）。
     *                 不能用内部计数器推断：列表可能被整表替换（加载外挂字幕）
     *                 或清空，推断出的下标一旦与列表实际状态不符，按该下标回写的
     *                 译文就会覆盖到别的条目上。
     * @return 是否接受（未启用/已释放返回 false）
     */
    public boolean submit(int startIdx, List<SubtitleEntry> entries) {
        if (entries == null || entries.isEmpty() || startIdx < 0
                || mTargetLang == null || mTargetLang.trim().isEmpty()
                || mCallback == null) {
            return false;
        }
        // 按真实下标放置行（扩容 + 覆盖）：与列表同构，translateAll 才能正确命中缓存
        synchronized (mAllLines) {
            while (mAllLines.size() < startIdx) {
                mAllLines.add(new SubtitleLine(mAllLines.size(), ""));
            }
            for (int i = 0; i < entries.size(); i++) {
                SubtitleLine line = new SubtitleLine(startIdx + i,
                        entries.get(i).getText() == null ? "" : entries.get(i).getText());
                int idx = startIdx + i;
                if (idx < mAllLines.size()) {
                    mAllLines.set(idx, line);
                } else {
                    mAllLines.add(line);
                }
            }
        }
        // 背压：只累积待处理区间，不逐批投递网络任务。
        // 单线程 executor 是无界队列，若每批都投递一个含网络调用的任务，
        // AI 超时型故障（连得上但不通，单批最坏约 127s）会让队列只增不减、
        // 本地兜底永远排不上、整场视频没有译文。
        synchronized (mPendingLock) {
            if (mPendingFrom < 0) {
                mPendingFrom = startIdx;
            }
            mPendingTo = Math.max(mPendingTo, startIdx + entries.size());
        }
        final int generation = mGeneration;
        try {
            mExecutor.execute(() -> {
                int from;
                int to;
                synchronized (mPendingLock) {
                    if (mPendingFrom < 0) {
                        return;   // 已被前一个任务取走（说明有任务在跑，本任务无需做事）
                    }
                    from = mPendingFrom;
                    to = mPendingTo;
                    mPendingFrom = -1;
                    mPendingTo = -1;
                }
                try {
                    runBatch(generation, from, to - from);
                } catch (Throwable t) {
                    Log.w(TAG, "批次翻译失败 @" + from, t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // release() 已 shutdown：会话作废，本批无需再处理
        }
        return true;
    }

    /** 待翻译区间（startIdx, endIdx）；-1 表示无待处理 */
    private final Object mPendingLock = new Object();
    private int mPendingFrom = -1;
    private int mPendingTo = -1;

    private void runBatch(int generation, int startIdx, int count) {
        if (generation != mGeneration || count <= 0) {
            return;   // 已换视频/重置
        }
        int from = startIdx;
        int n = count;
        // 区间起点可能早于已译部分（背压合并了多批）：裁掉已处理的
        synchronized (mAllLines) {
            while (n > 0 && from < mAllLines.size()
                    && mAllLines.get(from).hasTranslation()) {
                from++;
                n--;
            }
        }
        if (n <= 0) {
            return;
        }
        TranslatorSettings settings = mAiLockedToLocal ? null : mAiSettings;
        String[] translated;
        if (settings != null) {
            translated = translateWithAi(generation, from, n, settings);
            if (translated == null && generation == mGeneration) {
                // AI 不可用（网络/Key/额度）→ 本地兜底
                notifyStatus("AI 翻译不可用，改用本地翻译");
                translated = translateLocal(generation, from, n);
            }
        } else {
            translated = translateLocal(generation, from, n);
        }
        if (translated == null || generation != mGeneration) {
            return;
        }
        final String[] result = translated;
        final int resultFrom = from;
        Callback cb = mCallback;
        if (cb != null) {
            cb.onTranslated(resultFrom, result);
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
            if (ok == 0) {
                // 全部失败：AI 不可用（Key/网络），交由本地兜底。
                // 连续多批失败则锁存为本地模式——超时型故障下每批要重试到
                // 超时才降级（单批最坏约 127s），不锁存会让整场视频都在等 AI。
                if (mAiFailStreak.incrementAndGet() >= AI_FAIL_STREAK_TO_LOCAL) {
                    mAiLockedToLocal = true;
                    Log.w(TAG, "AI 连续 " + mAiFailStreak.get() + " 批失败，本会话改用本地翻译");
                    notifyStatus("AI 翻译连续失败，本会话改用本地翻译");
                }
                return null;
            }
            mAiFailStreak.set(0);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "AI 翻译异常，转本地兜底", t);
            mAiFailStreak.incrementAndGet();
            return null;
        }
    }

    /** AI 连续失败批次数：达到阈值后本会话锁定本地翻译 */
    private static final int AI_FAIL_STREAK_TO_LOCAL = 2;

    private final AtomicInteger mAiFailStreak = new AtomicInteger(0);
    private volatile boolean mAiLockedToLocal;

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
        // 失败连击按批重置（M3）：跨批累积会让一次网络抖动后的第 3 条
        // 直接放弃整批及后续所有批，与单条翻译故障无关
        mLocalFailStreak.set(0);
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

    /** 把本批译文并入缓存文件（idx 对齐）；glossary 原样保留（M1：null 会抹掉既有 G: 行） */
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
            store.save(merged, store.loadGlossary());
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
            // 引擎初始化过但「模型未下载」状态可能已过期（后台下载成功后
            // 标志才翻转；此前下载失败/超时的批次会一直被挡在这里）——
            // 重新查询一次，成功则本批立即可译（M2 修复：下载失败可重试）
            if (!engine.isModelDownloaded()) {
                engine.downloadModel(null);
            }
            return engine.isModelDownloaded();
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
        // 模型未下载：触发后台下载（失败时下批经上方 isModelDownloaded 重查再触发），
        // 本批跳过
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
            // 粤语：MLKit 无粤语模型，但 SenseVoice 把粤语转写为书面中文，
            // 按中文模型翻译；否则源语言=yue 的视频本地兜底静默跳过
            case "yue":
            case "cantonese":
            case "粤语":
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
        synchronized (mAllLines) {
            mAllLines.clear();
        }
        // 背压区间一并作废：mPendingFrom 只在 <0 时赋值，不清会让新会话
        // 的首个区间起点被上一会话残留值污染（表现为重复翻译/下标错位）
        synchronized (mPendingLock) {
            mPendingFrom = -1;
            mPendingTo = -1;
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
