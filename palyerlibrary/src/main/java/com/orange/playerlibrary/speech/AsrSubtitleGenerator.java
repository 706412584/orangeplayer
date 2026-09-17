package com.orange.playerlibrary.speech;

import android.content.Context;
import android.util.Log;

import com.orange.ffmpeg.FFmpegKit;
import com.orange.playerlibrary.subtitle.SubtitleEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ASR 字幕生成协调器：视频文件 → 抽音频(16k wav) → sherpa 批量识别
 * → 字幕段 → 临时 .srt 文件（供 SubtitleManager.loadSubtitle 加载显示）。
 *
 * 依赖 sherpa 引擎经 SherpaAvailabilityChecker 反射获取——宿主未链
 * orangeplayer-sherpa 模块时可用性检查为 false，功能自然隐藏。
 */
public class AsrSubtitleGenerator {

    private static final String TAG = "AsrSubtitleGenerator";

    public interface GenerateCallback {
        /**
         * 阶段进度。percent 是**整条管线**的全局进度（已按 {@link #asrStageBand}
         * 把各阶段映射到互不重叠的区间），可直接当 0-100 用。
         * stage 取值：{@code "抽取音频"}（5-30）、{@code "加载模型"}（30）、
         * {@code "语音分段"}（30-40）、{@code "说话人分离"}（40-75）、
         * {@code "语音识别"}（75-90）、{@code "生成字幕"}（95）、{@code "完成"}（100）。
         */
        void onProgress(int percent, String stage);

        /** 完成：srt 文件路径 + 字幕条数（主线程） */
        void onSuccess(File srtFile, int subtitleCount);

        /** 失败（主线程） */
        void onError(int code, String message);
    }

    /** 模型目录名（app 私有 filesDir 下） */
    public static final String MODEL_DIR_NAME = "asr_model";

    private final Context mContext;

    public AsrSubtitleGenerator(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * 模型是否就位（model.int8.onnx/tokens.txt/silero_vad.onnx）。
     * 除存在性外校验体积与清单一致：截断/损坏的文件此前会被判定「就绪」，
     * 直到 ONNX 加载才报错（难排查），且下载器不会重下。
     */
    public static boolean isModelReady(Context context) {
        File dir = getModelDir(context);
        return isModelFileComplete(new File(dir, "model.int8.onnx"))
                && isModelFileComplete(new File(dir, "tokens.txt"))
                && isModelFileComplete(new File(dir, "silero_vad.onnx"));
    }

    /** 文件存在且体积符合清单预期（清单无记录时只要求非空） */
    private static boolean isModelFileComplete(File file) {
        if (!file.exists() || file.length() == 0) {
            return false;
        }
        long expected = AsrModelDownloader.expectedSizeOf(file.getName());
        return expected <= 0 || file.length() == expected;
    }

    public static File getModelDir(Context context) {
        return new File(context.getExternalFilesDir(null), MODEL_DIR_NAME);
    }

    /** asr_work 孤儿临时文件的存活阈值（超过视为异常中断残留） */
    private static final long ORPHAN_FILE_AGE_MS = 24L * 60 * 60 * 1000;

    /**
     * 清扫 asr_work 下的孤儿临时文件。正常流程的 pcm/wav 用完即删，
     * 但崩溃/杀进程时 finally 清理不会执行，会残留大体积 _raw.pcm/
     * _16k.pcm/_audio.wav（真机实测一次可残留 70MB+）。
     * 判定标准：修改时间超过 {@link #ORPHAN_FILE_AGE_MS} 且正在生成中的
     * 任务不可能仍在写它（单次生成最长约几小时）。srt 产物不受影响
     * （同样按时间清理，播放器加载后已写入正式目录/内存）。
     * 启动时调用一次即可，幂等且不阻塞（文件数通常为个位数）。
     */
    public static void cleanupOrphanWorkFiles(Context context) {
        File workDir = new File(context.getCacheDir(), "asr_work");
        File[] files = workDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        int deleted = 0;
        for (File f : files) {
            if (now - f.lastModified() > ORPHAN_FILE_AGE_MS && f.delete()) {
                deleted++;
            }
        }
        if (deleted > 0) {
            Log.d(TAG, "清扫 asr_work 孤儿临时文件: " + deleted + " 个");
        }
    }

    /**
     * 生成字幕。阻塞调用（后台线程），全程可取消。
     *
     * @param videoFile   视频文件（本地或已缓存）
     * @param language    语种 auto/zh/en/ja/ko
     * @param callback    回调（UI 线程）
     * @param cancelToken 取消令牌
     */
    public void generate(final File videoFile, final String language,
                         final GenerateCallback callback, final BatchAsrEngine.CancelToken cancelToken) {
        final File workDir = new File(mContext.getCacheDir(), "asr_work");
        if (!workDir.exists() && !workDir.mkdirs()) {
            postError(callback, -1, "无法创建工作目录");
            return;
        }
        final File wavFile = new File(workDir, System.currentTimeMillis() + "_audio.wav");
        final File srtFile = new File(workDir, System.currentTimeMillis() + "_subtitle.srt");

        final BatchAsrEngine engine = SherpaAvailabilityChecker.createEngine();
        if (engine == null) {
            postError(callback, -2, "sherpa ASR 引擎不可用（模块未链接？）");
            return;
        }
        if (!isModelReady(mContext)) {
            postError(callback, -3, "ASR 模型未下载（需 model.int8.onnx/tokens.txt/silero_vad.onnx）");
            return;
        }

        new Thread(() -> {
            try {
                // 阶段 1：抽音频（MediaExtractor+MediaCodec 解码 → 16k mono PCM）
                if (cancelled(cancelToken)) {
                    cleanup(wavFile, srtFile);
                    postSuccess(callback, null, 0);
                    return;
                }
                callbackSafe(callback, 1, "抽取音频");
                // 抽音频占 5-30%：解码进度（按 presentationTimeUs/时长）线性映射进来。
                // 余下 30-40% 给 VAD、40-75% 给说话人分离、75-90% 给逐段识别，
                // 见 asrStageBand——各阶段区间互不重叠，单调守卫才不会互相压死。
                final File rawPcm = new File(workDir, System.currentTimeMillis() + "_raw.pcm");
                final File resampled = new File(workDir, System.currentTimeMillis() + "_16k.pcm");
                try {
                    int srcRate = AudioTrackExtractor.extractPcm(videoFile, rawPcm,
                            resolveDurationUs(videoFile),
                            new AudioTrackExtractor.ExtractListener() {
                                @Override
                                public void onProgress(int percent) {
                                    callbackSafe(callback, 5 + percent * 25 / 100, "抽取音频");
                                }

                                @Override
                                public boolean isCancelled() {
                                    return cancelled(cancelToken);
                                }
                            });
                    if (!rawPcm.exists() || rawPcm.length() == 0) {
                        postError(callback, -5, "音频抽取结果为空");
                        return;
                    }
                    // 重采样到 16k 单声道（源已是则直拷）
                    AudioResampler.resampleTo16kMono(rawPcm, srcRate, 1, resampled);
                    rawPcm.delete();
                    // 包 RIFF 头成标准 wav
                    java.nio.file.Files.copy(resampled.toPath(), wavFile.toPath());
                    resampled.delete();
                    wrapRiffWavHeader(wavFile);
                } catch (IOException e) {
                    Log.e(TAG, "音频抽取失败", e);
                    postError(callback, -4, "音频抽取失败: " + e.getMessage());
                    return;
                }

                // 阶段 2：初始化引擎 + 识别
                callbackSafe(callback, 30, "加载模型");
                final boolean initOk = engine.init(getModelDir(mContext).getAbsolutePath(), language);
                if (!initOk) {
                    postError(callback, -6, "ASR 引擎初始化失败");
                    return;
                }

                final List<SubtitleEntry> entries = new ArrayList<>();
                final int[] lastPercent = {30};
                engine.transcribeFile(wavFile.getAbsolutePath(),
                        new BatchAsrEngine.BatchAsrCallback() {
                            @Override
                            public void onReady() {
                            }

                            @Override
                            public void onSegment(String text, long startMs, long endMs) {
                                entries.add(new SubtitleEntry(startMs, endMs, text));
                            }

                            @Override
                            public void onSegmentWithSpeaker(String text, long startMs, long endMs,
                                                             String lang, int speaker) {
                                // 说话人存独立字段，不拼进 text——text 是翻译的输入
                                SubtitleEntry entry = new SubtitleEntry(startMs, endMs, text);
                                entry.setSpeaker(speaker);
                                entries.add(entry);
                            }

                            @Override
                            public void onProgress(int percent, String stage) {
                                // 引擎的 percent 是**阶段内**进度（每阶段都从 0 重来），
                                // 且阶段名此前被硬编码成"语音识别"丢弃。两者叠加的真机症状：
                                // VAD 跑到 95% → 映射成 86%，此后「说话人分离」上报的 0%
                                // 与逐段识别的 3%/7%… 全部低于 86 被单调守卫吞掉，悬浮环
                                // 从分离开始一路冻结到识别收尾（实测 2 分半不动）。
                                // 解法：按阶段划分互不重叠的区间，阶段内再映射——单调守卫
                                // 才能正确表达「整体在前进」而不是被前一阶段的高值压死。
                                int[] band = asrStageBand(stage);
                                int pct = band[0] + percent * (band[1] - band[0]) / 100;
                                if (pct > lastPercent[0]) {
                                    lastPercent[0] = pct;
                                    callbackSafe(callback, pct,
                                            stage == null || stage.isEmpty() ? "语音识别" : stage);
                                }
                            }

                            @Override
                            public void onCompleted(int segmentCount) {
                            }

                            @Override
                            public void onError(int errorCode, String errorMessage) {
                            }
                        }, cancelToken);

                engine.release();

                if (cancelled(cancelToken)) {
                    cleanup(wavFile, srtFile);
                    postSuccess(callback, null, 0);
                    return;
                }
                if (entries.isEmpty()) {
                    postError(callback, -7, "未识别到语音内容");
                    return;
                }

                // 阶段 3：写 srt
                callbackSafe(callback, 95, "生成字幕");
                writeSrt(srtFile, entries);
                callbackSafe(callback, 100, "完成");
                postSuccess(callback, srtFile, entries.size());
            } catch (Throwable t) {
                Log.e(TAG, "生成字幕异常", t);
                postError(callback, -99, "生成字幕异常: " + t.getMessage());
            } finally {
                // wav 用完即删（可能上百 MB）
                if (wavFile.exists()) {
                    wavFile.delete();
                }
            }
        }, "asr-generate").start();
    }

    /**
     * 给裸 PCM（16k/单声道/s16le）文件前置 44 字节 RIFF/WAVE 头。
     * ffmpeg 输出裸流时无容器头，ASR 引擎按标准 wav 解析。
     */
    static void wrapRiffWavHeader(File pcmFile) throws IOException {
        long dataLen = pcmFile.length();
        if (dataLen > 0xFFFFFFFFL - 44) {
            throw new IOException("PCM 过大无法包装");
        }
        byte[] hdr = new byte[44];
        writeAscii(hdr, 0, "RIFF");
        writeIntLE(hdr, 4, (int) (36 + dataLen));
        writeAscii(hdr, 8, "WAVE");
        writeAscii(hdr, 12, "fmt ");
        writeIntLE(hdr, 16, 16);          // fmt chunk size
        writeShortLE(hdr, 20, 1);         // PCM
        writeShortLE(hdr, 22, 1);         // 单声道
        writeIntLE(hdr, 24, 16000);       // sample rate
        writeIntLE(hdr, 28, 32000);       // byte rate = 16000 * 2
        writeShortLE(hdr, 32, 2);         // block align
        writeShortLE(hdr, 34, 16);        // bits per sample
        writeAscii(hdr, 36, "data");
        writeIntLE(hdr, 40, (int) dataLen);

        byte[] body = java.nio.file.Files.readAllBytes(pcmFile.toPath());
        try (FileOutputStream out = new FileOutputStream(pcmFile)) {
            out.write(hdr);
            out.write(body);
        }
    }

    private static void writeAscii(byte[] buf, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            buf[off + i] = (byte) s.charAt(i);
        }
    }

    private static void writeIntLE(byte[] buf, int off, int v) {
        buf[off] = (byte) (v & 0xFF);
        buf[off + 1] = (byte) ((v >> 8) & 0xFF);
        buf[off + 2] = (byte) ((v >> 16) & 0xFF);
        buf[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void writeShortLE(byte[] buf, int off, int v) {
        buf[off] = (byte) (v & 0xFF);
        buf[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    /**
     * 写 SRT 文件（字幕时间轴为 VAD 段边界）。
     * 有说话人归属时正文前加 {@code [S1] }，回读端会剥回 speaker 字段
     * （见 {@link AsrSubtitleCache#parseSrt} / SubtitleManager.parseSrt）。
     */
    static void writeSrt(File file, List<SubtitleEntry> entries) throws IOException {
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            for (int i = 0; i < entries.size(); i++) {
                SubtitleEntry e = entries.get(i);
                w.write((i + 1) + "\n");
                w.write(formatSrtTime(e.getStartTime()) + " --> " + formatSrtTime(e.getEndTime()) + "\n");
                String label = e.speakerLabel();
                w.write((label == null ? "" : "[" + label + "] ") + e.getText() + "\n\n");
            }
        }
    }

    /** 毫秒 → SRT 时间戳 HH:MM:SS,mmm */
    static String formatSrtTime(long ms) {
        long h = ms / 3600000;
        long m = (ms % 3600000) / 60000;
        long s = (ms % 60000) / 1000;
        long milli = ms % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli);
    }

    /**
     * 取视频时长（微秒），仅用于抽音频阶段的进度换算。
     *
     * <p>此前这条路径传死 0，而 {@code AudioTrackExtractor.decodeLoop} 的进度
     * 判据是 {@code durationUs > 0}——于是整个抽音频阶段（实测 26 秒）一次
     * 进度回调都不发，悬浮环停在 1% 一动不动，用户以为卡死了。
     *
     * <p>取不到时返回 0：退回「无进度」行为，不影响功能。
     */
    private static long resolveDurationUs(File videoFile) {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        try {
            ex.setDataSource(videoFile.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")
                        && f.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    return f.getLong(android.media.MediaFormat.KEY_DURATION);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取视频时长失败（抽音频阶段将无进度）: " + t.getMessage());
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    /**
     * 识别阶段 → 进度区间 [起, 止]（含起、不含止）。
     *
     * <p>存在的理由：引擎上报的 percent 是**阶段内**进度，每个阶段都从 0 重来。
     * 消费端若直接把它当全局进度再套单调守卫，前一个阶段的高值会把后一阶段
     * 全部压死——真机症状是「VAD 到 86% 后，说话人分离与逐段识别的进度全被
     * 吞掉，悬浮环冻结 2 分半」。给每个阶段划一段互不重叠的区间，阶段内再线性
     * 映射，单调守卫才能正确表达「整体在前进」。
     *
     * <p>区间划分按实测耗时比例给权重（262s 视频：抽音频 26s / VAD 3s /
     * 说话人分离 111s / 逐段识别 38s），分离最慢故区间最宽——它不是可选项，
     * 模型齐全时每次识别都要跑。
     */
    static int[] asrStageBand(String stage) {
        if (stage == null) {
            return ASR_BAND_UNKNOWN;
        }
        switch (stage) {
            case "语音分段":
                return ASR_BAND_VAD;
            case "说话人分离":
                return ASR_BAND_DIAR;
            case "语音识别":
                return ASR_BAND_DECODE;
            default:
                // 引擎新增阶段而此处未跟随时走到这里：宁可少报也不能与前一个
                // 阶段的区间重叠——重叠会让单调守卫把后续阶段的进度整段吞掉，
                // 正是本次要修的病根
                return ASR_BAND_UNKNOWN;
        }
    }

    /** 各阶段进度区间（含起、不含止）：抽音频 5-30 / VAD 30-40 / 分离 40-75 / 逐段 75-90 */
    private static final int[] ASR_BAND_VAD = {30, 40};
    private static final int[] ASR_BAND_DIAR = {40, 75};
    private static final int[] ASR_BAND_DECODE = {75, 90};
    /** 未识别阶段：与「逐段识别」同区间（最保守：只影响末段，不会吞掉它） */
    private static final int[] ASR_BAND_UNKNOWN = {75, 90};

    private boolean cancelled(BatchAsrEngine.CancelToken token) {
        return token != null && token.isCancelled();
    }

    private void cleanup(File... files) {
        for (File f : files) {
            if (f != null && f.exists()) {
                f.delete();
            }
        }
    }

    private void callbackSafe(GenerateCallback callback, int percent, String stage) {
        if (callback != null) {
            callback.onProgress(percent, stage);
        }
    }

    private void postError(GenerateCallback callback, int code, String msg) {
        Log.e(TAG, "错误 " + code + ": " + msg);
        if (callback != null) {
            callback.onError(code, msg);
        }
    }

    private void postSuccess(GenerateCallback callback, File srt, int count) {
        if (callback != null) {
            callback.onSuccess(srt, count);
        }
    }
}
