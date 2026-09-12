package com.orange.playerlibrary.speech;

import android.content.Context;
import android.util.Log;

import com.orange.playerlibrary.subtitle.SubtitleEntry;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 渐进 ASR 会话（边看边播）：按播放进度分块抽取音轨 → 逐块识别 → 增量注入字幕。
 *
 * 复用现有能力，不重复造轮子：
 *  - {@link AudioTrackExtractor} 区间抽取（MediaExtractor.seekTo + endUs 截断）
 *  - {@link AudioResampler} 重采样、{@link AsrSubtitleGenerator#wrapRiffWavHeader} 包 wav 头
 *  - {@link BatchAsrEngine}：init 一次长持，逐块 transcribeFile（引擎 VAD 每块 reset，可复用）
 *  - 引擎时间戳相对块起点 → 加块偏移得绝对时间轴
 *  - {@link com.orange.playerlibrary.subtitle.SubtitleManager#appendSubtitles} 增量注入
 *
 * 仅支持可随机 seek 的本地文件/已缓存 mp4；HLS 走完整下载兜底。
 */
public class ProgressiveAsrSession {

    private static final String TAG = "ProgressiveAsr";

    /** 识别块粒度（与播放进度对齐的分段） */
    private static final long BLOCK_MS = 30_000;
    /** 领先播放头多少开始识别（提前出字幕） */
    private static final long LEAD_MS = 15_000;

    public interface Callback {
        /**
         * 某块识别完成（主线程）。
         *
         * @param blockStartMs 块起始毫秒——写增量缓存要据此标记已识别段；
         *                     即使 entries 为空（该段无语音）块也已消费完毕
         * @param entries      该块的绝对时间轴字幕
         */
        void onBlockReady(long blockStartMs, List<SubtitleEntry> entries);

        /** 进度：已覆盖到 ms / 总时长 ms（主线程） */
        void onProgress(long recognizedUntilMs, long durationMs);

        /** 失败（主线程）；会话随即结束 */
        void onError(int code, String message);

        /**
         * 已识别到视频末尾（主线程）；会话随即结束。
         * 供调用方把增量缓存提升为整片缓存（下次重看直接命中，不再识别）。
         */
        default void onCompleted() {
        }

        /**
         * 引擎检测到的语种（如 "zh"/"en"，auto 模式下有效；识别不到则不回调）。
         * 供本地翻译兜底确定源语言；默认空实现，未使用的调用方无需改动。
         */
        default void onLanguageDetected(String lang) {
        }
    }

    private final Context mContext;
    private final BlockAudioSource mSource;
    private final String mLanguage;
    private final Callback mCallback;

    private final BatchAsrEngine mEngine;
    private final Set<Long> mDoneBlocks = new HashSet<>();
    private final File mWorkDir;

    /** 抽音频失败的块数：>0 时不能宣称整片识别完成（见 runLoop 收尾） */
    private final java.util.concurrent.atomic.AtomicInteger mFailedBlocks =
            new java.util.concurrent.atomic.AtomicInteger();

    private volatile boolean mStopped;
    private volatile long mPlaybackMs;
    private volatile long mRecognizedUntilMs;
    private long mDurationMs;
    private int mTotalSegments;
    private Thread mThread;

    private final BatchAsrEngine.CancelToken mCancelToken = () -> mStopped;

    public ProgressiveAsrSession(Context context, BlockAudioSource source, String language,
                                 long durationMs, Callback callback) {
        mContext = context.getApplicationContext();
        mSource = source;
        mLanguage = (language == null || language.isEmpty()) ? "auto" : language;
        mDurationMs = durationMs;
        mCallback = callback;
        mEngine = SherpaAvailabilityChecker.createEngine();
        mWorkDir = new File(mContext.getCacheDir(), "asr_work");
    }

    /** 引擎与模型均就绪时可用 */
    public static boolean isAvailable(Context context) {
        return SherpaAvailabilityChecker.isSherpaAvailable()
                && AsrSubtitleGenerator.isModelReady(context);
    }

    /**
     * 预填「已识别过的块」（来自上一次会话的增量缓存）。
     * 只在 {@link #start()} 之前有效；预填后本轮只识别缺失的块。
     */
    public void seedDoneBlocks(Set<Long> doneBlocks) {
        if (doneBlocks == null || doneBlocks.isEmpty()) {
            return;
        }
        mDoneBlocks.addAll(doneBlocks);
        // 进度起点对齐到已识别段的末尾（与其他位置写入的语义一致：
        // mRecognizedUntilMs 是「已覆盖到」的开区间上界）
        long max = 0;
        for (Long b : doneBlocks) {
            if (b != null && b + BLOCK_MS > max) {
                max = b + BLOCK_MS;
            }
        }
        mRecognizedUntilMs = mDurationMs > 0 ? Math.min(max, mDurationMs) : max;
        Log.d(TAG, "预填已识别块 " + doneBlocks.size() + " 个，起点 " + mRecognizedUntilMs + "ms");
    }

    /** 开始会话（幂等；不可用时回调 onError 并结束） */
    public void start() {
        if (mThread != null) {
            return;
        }
        if (mEngine == null) {
            postError(-2, "sherpa ASR 引擎不可用（模块未链接？）");
            return;
        }
        if (!AsrSubtitleGenerator.isModelReady(mContext)) {
            postError(-3, "ASR 模型未下载（需 model.int8.onnx 等，约 240MB）");
            return;
        }
        if (!mWorkDir.exists() && !mWorkDir.mkdirs()) {
            postError(-1, "无法创建工作目录");
            return;
        }
        mThread = new Thread(this::runLoop, "progressive-asr");
        mThread.start();
    }

    /** 播放位置更新（UI 每秒调用） */
    public void updatePlaybackPosition(long positionMs) {
        mPlaybackMs = positionMs;
    }

    /** 结束会话（幂等） */
    public void stop() {
        mStopped = true;
        Thread t = mThread;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean isRunning() {
        return mThread != null && mThread.isAlive();
    }

    public long getRecognizedUntilMs() {
        return mRecognizedUntilMs;
    }

    public int getTotalSegments() {
        return mTotalSegments;
    }

    /** [0, duration) 内每个块都已识别（时长未知时为 false） */
    public boolean isFullyRecognized() {
        return mDurationMs > 0 && isAllBlocksDone();
    }

    /**
     * 驱动循环：优先保证「当前所在块」已识别，其次识别「领先块」。
     * 单线程串行，避免与播放争 CPU（SenseVoice numThreads 已是省电配置）。
     */
    private void runLoop() {
        try {
            if (!mEngine.init(AsrSubtitleGenerator.getModelDir(mContext).getAbsolutePath(), mLanguage)) {
                postError(-6, "ASR 引擎初始化失败");
                return;
            }
            if (mDurationMs <= 0) {
                mDurationMs = mSource.getDurationMs();   // HLS 首次解析清单后可得
            }
            Log.d(TAG, "渐进识别开始: duration=" + mDurationMs + "ms");
            while (!mStopped) {
                long pos = mPlaybackMs;
                long cur = (pos / BLOCK_MS) * BLOCK_MS;
                long wanted = ((pos + LEAD_MS) / BLOCK_MS) * BLOCK_MS;

                long target = -1;
                if (!mDoneBlocks.contains(cur)) {
                    target = cur;
                } else if (!mDoneBlocks.contains(wanted)) {
                    target = wanted;
                }

                if (target < 0 || (mDurationMs > 0 && target >= mDurationMs)) {
                    // 无待识别块：若已覆盖到视频末尾，说明整片识别完毕
                    if (mDurationMs > 0 && isAllBlocksDone()) {
                        int failed = mFailedBlocks.get();
                        if (failed == 0) {
                            Log.d(TAG, "渐进识别已覆盖全片 " + mDurationMs + "ms");
                            postCompleted();
                        } else {
                            // 有块抽不出音频（网络源取数失败等）：不能宣称整片识别完，
                            // 否则调用方会把缺内容的 part.srt 提升为整片缓存，
                            // 之后每次重看都用这份残缺字幕
                            Log.w(TAG, "整片识别结束，但有 " + failed + " 个块失败，不提升为整片缓存");
                        }
                        return;
                    }
                    sleepQuiet(200);
                    continue;
                }
                recognizeBlock(target);
            }
        } catch (Throwable t) {
            Log.e(TAG, "渐进识别异常", t);
            postError(-99, "渐进识别异常: " + t.getMessage());
        } finally {
            try {
                mEngine.release();
            } catch (Exception ignored) {
            }
            try {
                mSource.release();
            } catch (Exception ignored) {
            }
            Log.d(TAG, "渐进识别结束，累计 " + mTotalSegments + " 条");
        }
    }

    /**
     * [0, duration) 内的每个块是否都已处理过。
     * 只对整个块判定：末块边界落在 duration 之后时也要求该块完成，
     * 与 runLoop 中 {@code target >= mDurationMs} 的跳过规则一致。
     */
    private boolean isAllBlocksDone() {
        for (long b = 0; b < mDurationMs; b += BLOCK_MS) {
            if (!mDoneBlocks.contains(b)) {
                return false;
            }
        }
        return true;
    }

    /** 识别单个时间块；失败不重试（避免卡死循环） */
    private void recognizeBlock(long blockStartMs) {
        mDoneBlocks.add(blockStartMs);
        if (mStopped) {
            return;
        }
        final long blockEndMs = blockStartMs + BLOCK_MS;
        final String tag = System.currentTimeMillis() + "_" + blockStartMs;
        final File wav = new File(mWorkDir, tag + ".wav");
        try {
            long t0 = android.os.SystemClock.elapsedRealtime();
            // 音频来源取块（本地 seek 解码 / HLS 分片读缓存）；返回实际起始偏移
            final long actualStartMs = mSource.extractBlockWav(blockStartMs, blockEndMs, wav);
            if (mStopped) {
                return;
            }
            if (actualStartMs < 0) {
                mFailedBlocks.incrementAndGet();
                Log.w(TAG, "块音频抽取失败 @" + blockStartMs + "ms");
                return;
            }
            if (!wav.exists() || wav.length() == 0) {
                return;   // 该段无音频（静音/超出媒体末尾），不算失败
            }

            final List<SubtitleEntry> entries = new ArrayList<>();
            mEngine.transcribeFile(wav.getAbsolutePath(),
                    new BatchAsrEngine.BatchAsrCallback() {
                        @Override
                        public void onReady() {
                        }

                        @Override
                        public void onSegment(String text, long startMs, long endMs) {
                            // 引擎时间戳相对块起点 → 加实际偏移得绝对时间轴
                            entries.add(new SubtitleEntry(actualStartMs + startMs,
                                    actualStartMs + endMs, text));
                        }

                        @Override
                        public void onSegmentWithLang(String text, long startMs, long endMs,
                                                      String lang) {
                            onSegment(text, startMs, endMs);
                            // 语种只在变化时上报（本地翻译兜底据此确定源语言）
                            if (lang != null && !lang.isEmpty() && !lang.equals(mDetectedLang)) {
                                mDetectedLang = lang;
                                postLanguageDetected(lang);
                            }
                        }

                        @Override
                        public void onProgress(int percent, String stage) {
                        }

                        @Override
                        public void onCompleted(int segmentCount) {
                        }

                        @Override
                        public void onError(int errorCode, String errorMessage) {
                            Log.w(TAG, "块识别回调错误 @" + blockStartMs + ": " + errorMessage);
                        }
                    }, mCancelToken);

            if (mStopped) {
                return;
            }
            if (!entries.isEmpty()) {
                mTotalSegments += entries.size();
            }
            // 无语音的块也要上报：它确实识别完了，增量缓存据此记「该段无需再识别」。
            // 上面的早退分支（抽取失败/已停止）不走到这里，避免把失败当作已完成。
            postBlockReady(blockStartMs, entries);
            mRecognizedUntilMs = mDurationMs > 0
                    ? Math.min(blockEndMs, mDurationMs) : blockEndMs;
            postProgress(mRecognizedUntilMs, mDurationMs);
            Log.d(TAG, "块完成 @" + blockStartMs + "ms: " + entries.size() + " 条, 耗时 "
                    + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
        } catch (Throwable t) {
            mFailedBlocks.incrementAndGet();
            Log.w(TAG, "块识别失败 @" + blockStartMs + "ms", t);
        } finally {
            deleteQuiet(wav);
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void deleteQuiet(File f) {
        if (f != null && f.exists()) {
            f.delete();
        }
    }

    private void postBlockReady(final long blockStartMs, final List<SubtitleEntry> entries) {
        if (mCallback != null) {
            mCallback.onBlockReady(blockStartMs, entries);
        }
    }

    private void postCompleted() {
        if (mCallback != null) {
            mCallback.onCompleted();
        }
    }

    private void postProgress(final long recognizedUntilMs, final long durationMs) {
        if (mCallback != null) {
            mCallback.onProgress(recognizedUntilMs, durationMs);
        }
    }

    /** 引擎检测到的语种（回调线程写，仅用于去重上报） */
    private volatile String mDetectedLang;

    private void postLanguageDetected(String lang) {
        if (mCallback != null) {
            mCallback.onLanguageDetected(lang);
        }
    }

    private void postError(final int code, final String message) {
        Log.e(TAG, "错误 " + code + ": " + message);
        if (mCallback != null) {
            mCallback.onError(code, message);
        }
    }
}
