package com.orange.player.sherpa;

import android.util.Log;

import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import com.orange.playerlibrary.speech.AsrSegmentSplitter;
import com.orange.playerlibrary.speech.BatchAsrEngine;
import com.orange.playerlibrary.speech.SpeakerTimeline;
import com.orange.playerlibrary.subtitle.SubtitleEntry;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sherpa-onnx 批量 ASR 引擎实现（SenseVoice + 可选离线说话人分离）。
 *
 * 流程：整段 wav → silero-vad 切段 → 逐段 OfflineRecognizer.decode
 * → VAD 段边界即字幕时间轴。
 *
 * 若 {@code modelDir/diarization/} 下存在说话人分离模型，则额外先跑一遍
 * pyannote 分段 + 声纹 embedding + 聚类，得到「时间区间 → 说话人」，
 * 再按最大重叠把说话人挂到每个字幕段上。
 *
 * SenseVoice 输出可能含 <|NEUTRAL|> <|laughter|> 等事件标记，入字幕前清洗。
 */
public class SherpaBatchAsrEngine implements BatchAsrEngine {

    private static final String TAG = "SherpaBatchAsr";

    // SenseVoice 事件/情感标记：<|xxx|> 全部剥除
    private static final Pattern EVENT_TAG = Pattern.compile("<\\|[^|]*\\|>");

    // 语种标记：<|zh|> → 提取内部语种（与文本清洗相反，这里是取值）
    private static final Pattern LANG_TAG = Pattern.compile("<\\|([^|]*)\\|>");

    /** 说话人分离模型子目录与文件名（缺失即降级为不含说话人） */
    private static final String DIAR_DIR = "diarization";
    private static final String DIAR_SEGMENTATION = "segmentation.onnx";
    private static final String DIAR_EMBEDDING = "embedding.onnx";

    private final Object mLock = new Object();
    private OfflineRecognizer mRecognizer;
    private Vad mVad;
    /** 可空：仅当 modelDir/diarization 下模型齐全时非空 */
    private OfflineSpeakerDiarization mDiarizer;
    /** 是否启用说话人分离（须在 init 前设置；分块识别场景应关闭） */
    private volatile boolean mDiarizationEnabled = true;
    private String mModelDir;

    @Override
    public void setDiarizationEnabled(boolean enabled) {
        // init 之前调用才有效：init 会据此决定是否加载分离模型
        mDiarizationEnabled = enabled;
    }

    // ===== 生命周期 =====

    @Override
    public boolean init(String modelDir, String language) {
        synchronized (mLock) {
            releaseInternal();
            mModelDir = modelDir;
            try {
                File dir = new File(modelDir);
                File modelFile = new File(dir, "model.int8.onnx");
                File tokensFile = new File(dir, "tokens.txt");
                File vadFile = new File(dir, "silero_vad.onnx");
                if (!modelFile.exists() || !tokensFile.exists() || !vadFile.exists()) {
                    Log.e(TAG, "模型文件缺失: " + modelDir
                            + " (需 model.int8.onnx/tokens.txt/silero_vad.onnx)");
                    return false;
                }

                // VAD（silero）：静音 0.3s 断句，单段上限 5s
                SileroVadModelConfig silero = new SileroVadModelConfig();
                silero.setModel(vadFile.getAbsolutePath());
                silero.setThreshold(0.5f);
                silero.setMinSilenceDuration(0.3f);   // 断句
                silero.setMinSpeechDuration(0.25f);
                silero.setWindowSize(512);
                silero.setMaxSpeechDuration(5.0f);
                VadModelConfig vadConfig = new VadModelConfig();
                vadConfig.setSileroVadModelConfig(silero);
                vadConfig.setSampleRate(16000);
                vadConfig.setNumThreads(1);
                vadConfig.setProvider("cpu");
                mVad = new Vad(null, vadConfig);

                // 识别器（SenseVoice）——无参构造 + setter（避免长构造参数序错位）
                OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig();
                senseVoice.setModel(modelFile.getAbsolutePath());
                senseVoice.setLanguage(language == null ? "auto" : language);
                OfflineModelConfig modelConfig = new OfflineModelConfig();
                modelConfig.setSenseVoice(senseVoice);
                modelConfig.setTokens(tokensFile.getAbsolutePath());
                // 不设 modelType——senseVoice 非空时 SDK 自动推断（官方示例同）
                modelConfig.setNumThreads(1);
                modelConfig.setProvider("cpu");
                FeatureConfig featureConfig = new FeatureConfig(16000, 80, 0f);  // 16k/80维fbank/无抖动
                OfflineRecognizerConfig recognizerConfig = new OfflineRecognizerConfig();
                recognizerConfig.setFeatConfig(featureConfig);
                recognizerConfig.setModelConfig(modelConfig);
                recognizerConfig.setDecodingMethod("greedy_search");
                mRecognizer = new OfflineRecognizer(null, recognizerConfig);

                // 说话人分离（可选）：模型不齐时保持 null，识别照常但不带说话人
                mDiarizer = createDiarizer(dir);

                Log.d(TAG, "init 成功 modelDir=" + modelDir + " lang=" + language
                        + " diarization=" + (mDiarizer != null));
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "init 失败", t);
                releaseInternal();
                return false;
            }
        }
    }

    @Override
    public boolean isInitialized() {
        synchronized (mLock) {
            return mRecognizer != null;
        }
    }

    @Override
    public void release() {
        synchronized (mLock) {
            releaseInternal();
        }
    }

    private void releaseInternal() {
        if (mRecognizer != null) {
            try {
                mRecognizer.release();
            } catch (Throwable ignored) {
            }
            mRecognizer = null;
        }
        if (mVad != null) {
            try {
                mVad.release();
            } catch (Throwable ignored) {
            }
            mVad = null;
        }
        if (mDiarizer != null) {
            try {
                mDiarizer.release();
            } catch (Throwable ignored) {
            }
            mDiarizer = null;
        }
    }

    /**
     * 构建说话人分离器。模型不齐或加载失败时返回 null——**不得**让 init 失败：
     * 说话人是增强项，缺失时应静默降级为原有的纯识别行为。
     */
    private OfflineSpeakerDiarization createDiarizer(File modelDir) {
        try {
            if (!mDiarizationEnabled) {
                Log.d(TAG, "说话人分离已被调用方关闭（分块识别场景），跳过加载");
                return null;
            }
            File seg = new File(new File(modelDir, DIAR_DIR), DIAR_SEGMENTATION);
            File emb = new File(new File(modelDir, DIAR_DIR), DIAR_EMBEDDING);
            if (!seg.exists() || !emb.exists()) {
                Log.d(TAG, "未启用说话人分离（缺 " + DIAR_DIR + "/{" + DIAR_SEGMENTATION
                        + "," + DIAR_EMBEDDING + "}）");
                return null;
            }

            // 以下构造器参数均为显式全参：Kotlin 的默认值只在 Kotlin 侧可见，
            // Java 调不到带 DefaultConstructorMarker 的合成构造器，省略即编译不过。
            // 取值同 sherpa-onnx 上游默认（numThreads=1 / debug=false / provider="cpu"）。

            // pyannote 分段：windowShiftRatio 0.5 为 sherpa 上游默认
            OfflineSpeakerSegmentationPyannoteModelConfig pyannote =
                    new OfflineSpeakerSegmentationPyannoteModelConfig(seg.getAbsolutePath(), 0.5f);
            OfflineSpeakerSegmentationModelConfig segmentation =
                    new OfflineSpeakerSegmentationModelConfig(pyannote, 1, false, "cpu");

            // 声纹 embedding（3dspeaker eres2net 等）
            SpeakerEmbeddingExtractorConfig embedding =
                    new SpeakerEmbeddingExtractorConfig(emb.getAbsolutePath(), 1, false, "cpu");

            // numClusters=0 → 用 threshold 自动判定人数（sherpa 约定）
            FastClusteringConfig clustering = new FastClusteringConfig(0, 0.5f);

            // minDurationOn/minDurationOff 取上游默认 0.3 / 0.5
            OfflineSpeakerDiarizationConfig config = new OfflineSpeakerDiarizationConfig(
                    segmentation, embedding, clustering, 0.3f, 0.5f);
            OfflineSpeakerDiarization diarizer = new OfflineSpeakerDiarization(null, config);
            Log.d(TAG, "说话人分离已启用: " + seg.getName() + " + " + emb.getName()
                    + " (sampleRate=" + diarizer.sampleRate() + ")");
            return diarizer;
        } catch (Throwable t) {
            Log.w(TAG, "说话人分离初始化失败，降级为不含说话人", t);
            return null;
        }
    }

    // ===== 识别 =====

    @Override
    public void transcribeFile(String wavPath, BatchAsrCallback callback, CancelToken cancelToken) {
        if (callback == null) {
            return;
        }
        if (!isInitialized()) {
            callback.onError(-1, "引擎未初始化");
            return;
        }
        float[] samples;
        try {
            samples = readWav16kMono(wavPath);
        } catch (IOException e) {
            Log.e(TAG, "读取 wav 失败: " + wavPath, e);
            callback.onError(-2, "读取音频失败: " + e.getMessage());
            return;
        }
        if (samples.length == 0) {
            callback.onError(-3, "音频为空");
            return;
        }
        if (cancelled(cancelToken)) {
            callback.onCompleted(0);
            return;
        }

        callback.onReady();
        transcribeSamples(samples, callback, cancelToken);
    }

    /**
     * 核心：VAD 分段 + 逐段识别。VAD 预扫一次性完成（v1），
     * 段集就绪后按段解码回报进度；每段间检查取消。
     */
    private void transcribeSamples(float[] samples, BatchAsrCallback callback, CancelToken cancelToken) {
        OfflineRecognizer recognizer;
        Vad vad;
        OfflineSpeakerDiarization diarizer;
        synchronized (mLock) {
            recognizer = mRecognizer;
            vad = mVad;
            diarizer = mDiarizer;
        }
        if (recognizer == null || vad == null) {
            callback.onError(-1, "引擎未初始化");
            return;
        }

        try {
            // 阶段 1：VAD 切段（不可中断，快速；进度按已喂比例估算）
            vad.reset();
            java.util.List<SpeechSegment> segments = new java.util.ArrayList<>();
            final int chunkSize = 512;   // windowSize
            int totalChunks = Math.max(1, samples.length / chunkSize);
            int feedChunks = 0;
            // 按 0.1s 一批喂入（1600 样本），期间可查取消
            int batch = 1600;
            for (int i = 0; i < samples.length; i += batch) {
                int len = Math.min(batch, samples.length - i);
                float[] chunk = new float[len];
                System.arraycopy(samples, i, chunk, 0, len);
                vad.acceptWaveform(chunk);
                feedChunks += len / chunkSize;
                if (cancelled(cancelToken)) {
                    callback.onCompleted(0);
                    return;
                }
                int pct = (int) (feedChunks * 100L / totalChunks);
                callback.onProgress(Math.min(pct, 99), "语音分段");
            }
            // 收尾取段：front() 取头段 + pop() 弹出（Java API：pop 返回 void）
            while (!vad.empty()) {
                SpeechSegment seg = vad.front();
                if (seg != null && seg.getSamples() != null) {
                    segments.add(seg);
                }
                vad.pop();
            }
            Log.d(TAG, "VAD 切段完成: " + segments.size() + " 段 (输入样本=" + samples.length + ")");

            if (segments.isEmpty()) {
                callback.onCompleted(0);
                return;
            }

            // 阶段 2：说话人分离（可选，整段一次性跑）
            SpeakerTimeline timeline = diarizer == null
                    ? SpeakerTimeline.EMPTY
                    : buildSpeakerTimeline(diarizer, samples, callback, cancelToken);

            // 阶段 3：逐段解码
            int done = 0;
            for (SpeechSegment segment : segments) {
                if (cancelled(cancelToken)) {
                    callback.onCompleted(done);
                    return;
                }
                OfflineStream stream = recognizer.createStream();
                stream.acceptWaveform(segment.getSamples(), 16000);
                recognizer.decode(stream);
                OfflineRecognizerResult result = recognizer.getResult(stream);
                String text = clean(result.getText());
                long startMs = Math.round(segment.getStart() / 16.0);  // samples@16k → ms
                long endMs = startMs + Math.round(segment.getSamples().length / 16.0);
                if (!text.isEmpty()) {
                    // SenseVoice 无词级时间戳，VAD 段内最长可达 5s：整段作为一条字幕
                    // 会「好几秒一大段」。按标点/字数切分并用字数比例插值段内时间，
                    // 段首尾仍与 VAD 边界严格对齐（不累积漂移）。
                    for (SubtitleEntry entry : AsrSegmentSplitter.split(text, startMs, endMs)) {
                        callback.onSegmentWithSpeaker(entry.getText(), entry.getStartTime(),
                                entry.getEndTime(), sanitizeLang(result.getLang()),
                                timeline.speakerAt(entry.getStartTime(), entry.getEndTime()));
                    }
                }
                stream.release();
                done++;
                callback.onProgress(done * 100 / segments.size(), "语音识别");
            }
            callback.onCompleted(done);
        } catch (Throwable t) {
            Log.e(TAG, "识别异常", t);
            callback.onError(-4, "识别异常: " + t.getMessage());
        }
    }

    /**
     * 跑一遍说话人分离并把区间转成 {@link SpeakerTimeline}。
     *
     * <p>分离失败**不**让整次识别失败：说话人是增强项，出错时返回空时间线，
     * 结果退化为「不带说话人标签的识别」——与模型缺失时的降级路径一致。
     */
    private SpeakerTimeline buildSpeakerTimeline(OfflineSpeakerDiarization diarizer,
                                                 float[] samples,
                                                 BatchAsrCallback callback,
                                                 CancelToken cancelToken) {
        try {
            int rate = diarizer.sampleRate();
            if (rate != 16000) {
                // 传入的是 16k 采样；采样率不符时时间轴会整体缩放错位，宁可不用
                Log.w(TAG, "说话人分离采样率不符: " + rate + "，跳过说话人标注");
                return SpeakerTimeline.EMPTY;
            }
            callback.onProgress(0, "说话人分离");
            // 用带回调的版本：分离在 262s 素材上实测耗时 111s，只报首尾两帧
            // 会让进度环长时间不动（真机症状：卡在 86%/40% 看着像死了）。
            // 回调粒度是「已算完的 embedding 分块 / 总块数」，即真实的分离进度。
            OfflineSpeakerDiarizationSegment[] segs = diarizer.processWithCallback(
                    samples, new DiarProgressCallback(callback), 0L);
            if (segs == null || segs.length == 0) {
                Log.d(TAG, "说话人分离无输出");
                return SpeakerTimeline.EMPTY;
            }
            List<SpeakerTimeline.Span> spans = new ArrayList<>(segs.length);
            for (OfflineSpeakerDiarizationSegment s : segs) {
                if (s == null) {
                    continue;
                }
                spans.add(new SpeakerTimeline.Span(
                        Math.round(s.getStart() * 1000f),
                        Math.round(s.getEnd() * 1000f),
                        s.getSpeaker()));
            }
            // compact：把 sherpa 的任意编号压成 0..n-1（真机首个说话人是 1，
            // 直接用会让单人字幕显示成 S2）
            SpeakerTimeline timeline = SpeakerTimeline.of(spans).compact();
            // 说话人编号不保证从 0 起也不保证连续（真机实测首个说话人为 1），
            // 故按去重计数，不能用 max+1——那会把单个说话人报成 2 个。
            java.util.Set<Integer> distinct = new java.util.HashSet<>();
            for (SpeakerTimeline.Span sp : spans) {
                distinct.add(sp.getSpeaker());
            }
            Log.d(TAG, "说话人分离完成: " + timeline.size() + " 区间, 说话人数="
                    + distinct.size() + " " + distinct);
            // 逐区间明细：排查「说话人串了 / 被切碎」时唯一的一手依据，量小常开
            for (SpeakerTimeline.Span sp : spans) {
                Log.d(TAG, "  S" + sp.getSpeaker() + " [" + sp.getStartMs()
                        + "-" + sp.getEndMs() + "ms]");
            }
            return timeline;
        } catch (Throwable t) {
            Log.w(TAG, "说话人分离失败，本次结果不含说话人", t);
            return SpeakerTimeline.EMPTY;
        }
    }

    /**
     * 说话人分离的进度回调。
     *
     * <p>native 侧（libsherpa-onnx-jni.so）用 {@code GetMethodID} 按**字面签名**
     * {@code (IIJ)Ljava/lang/Integer;} 查找 {@code invoke}——与 Kotlin 的
     * {@code Function3<Integer,Integer,Long,Integer>} 擦除后同形。Java 泛型方法
     * 擦除得到的是装箱签名 {@code (Ljava/lang/Integer;Ljava/lang/Integer;Ljava/lang/Long;)}
     * 且**不会**合成原始类型桥方法（只有 Kotlin 编译器会），所以这里必须手写
     * {@code invoke(int,int,long)}，否则 native 找不到方法：不崩，回调静默失效。
     *
     * <p>返回值：上游 pyannote 实现忽略它（既不判空也不解箱），但返回 0 而非 null
     * ——Kotlin 的 {@code Function3} 返回值类型是非空 {@code Integer}，返回 null
     * 会违背该契约；万一将来上游改为解箱返回值，null 会直接 NPE 崩在 native 栈上。
     * 代价为零，不留这个隐患。
     *
     * <p>性能：native 每算完一个 embedding 块回调一次（数百毫秒级），
     * 无锁、无分配，不构成瓶颈。
     */
    static final class DiarProgressCallback
            implements kotlin.jvm.functions.Function3<Integer, Integer, Long, Integer> {

        /** 非空返回值（见类注释）：0 = 继续，与上游「忽略返回值」的语义一致。 */
        private static final Integer CONTINUE = 0;

        private final BatchAsrCallback callback;

        DiarProgressCallback(BatchAsrCallback callback) {
            this.callback = callback;
        }

        /** JNI 实际查找的那个重载（原始类型描述符）。 */
        public Integer invoke(int processed, int total, long arg) {
            report(processed, total);
            return CONTINUE;
        }

        /** 泛型契约实现：Kotlin/Java 侧若以泛型方式调用会走到这里。 */
        @Override
        public Integer invoke(Integer processed, Integer total, Long arg) {
            report(processed == null ? 0 : processed, total == null ? 0 : total);
            return CONTINUE;
        }

        private void report(int processed, int total) {
            if (callback == null || total <= 0) {
                return;
            }
            int pct = (int) (processed * 100L / total);
            callback.onProgress(Math.max(0, Math.min(pct, 100)), "说话人分离");
        }
    }

    /** SenseVoice 事件标记清洗：<|NEUTRAL|> <|laughter|> <|Speech|> 等 */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return EVENT_TAG.matcher(raw).replaceAll("").trim();
    }

    /**
     * 语种字段清洗：SenseVoice 的 lang 形如 {@code <|zh|>}——语种在标记**内部**，
     * 需提取而非删除（{@link #clean} 是删标记，用于文本）。兼容 "en-US"/"EN" 等写法，
     * 取主语言子标签；无法识别时返回 null。
     */
    static String sanitizeLang(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        Matcher m = LANG_TAG.matcher(s);
        if (m.find()) {
            s = m.group(1);
        }
        s = s.trim().toLowerCase();
        // 取主语言子标签（en-US / zh_CN → en / zh）
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '_' || c == ' ') {
                cut = i;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cut; i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 读取 16k 单声道 pcm_s16le wav → float[-1,1] */
    private float[] readWav16kMono(String path) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path, "r");
        try {
            // 解析 RIFF 头找 data 块
            byte[] header = new byte[12];
            raf.readFully(header);
            // RIFF/WAVE 校验
            if (!"RIFF".equals(new String(header, 0, 4, "US-ASCII"))
                    || !"WAVE".equals(new String(header, 8, 4, "US-ASCII"))) {
                throw new IOException("非 WAV 文件: " + path);
            }
            long dataPos = -1;
            long dataLen = -1;
            while (raf.getFilePointer() < raf.length()) {
                byte[] chunkHeader = new byte[8];
                if (raf.read(chunkHeader) < 8) {
                    break;
                }
                String id = new String(chunkHeader, 0, 4, "US-ASCII");
                long size = ByteBuffer.wrap(chunkHeader, 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
                if ("data".equals(id)) {
                    dataPos = raf.getFilePointer();
                    dataLen = size;
                    break;
                }
                raf.skipBytes((int) Math.min(size, raf.length() - raf.getFilePointer()));
                if ((size & 1) == 1 && raf.getFilePointer() < raf.length()) {
                    raf.skipBytes(1);  // pad byte
                }
            }
            if (dataPos < 0) {
                throw new IOException("WAV 无 data 块: " + path);
            }
            raf.seek(dataPos);
            long sampleCount = dataLen / 2;
            if (sampleCount > Integer.MAX_VALUE - 8) {
                throw new IOException("音频过大");
            }
            byte[] raw = new byte[(int) dataLen];
            raf.readFully(raw);
            float[] out = new float[(int) sampleCount];
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < sampleCount; i++) {
                out[i] = bb.getShort() / 32768f;
            }
            return out;
        } finally {
            raf.close();
        }
    }

    private boolean cancelled(CancelToken token) {
        return token != null && token.isCancelled();
    }
}
