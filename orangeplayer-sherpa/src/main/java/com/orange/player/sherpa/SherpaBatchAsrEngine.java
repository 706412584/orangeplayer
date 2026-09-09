package com.orange.player.sherpa;

import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import com.orange.playerlibrary.speech.BatchAsrEngine;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.regex.Pattern;

/**
 * sherpa-onnx 批量 ASR 引擎实现（SenseVoice）。
 *
 * 流程：整段 wav → silero-vad 切段 → 逐段 OfflineRecognizer.decode
 * → VAD 段边界即字幕时间轴。
 *
 * SenseVoice 输出可能含 <|NEUTRAL|> <|laughter|> 等事件标记，入字幕前清洗。
 */
public class SherpaBatchAsrEngine implements BatchAsrEngine {

    private static final String TAG = "SherpaBatchAsr";

    // SenseVoice 事件/情感标记：<|xxx|> 全部剥除
    private static final Pattern EVENT_TAG = Pattern.compile("<\\|[^|]*\\|>");

    private final Object mLock = new Object();
    private OfflineRecognizer mRecognizer;
    private Vad mVad;
    private String mModelDir;

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
                Log.d(TAG, "init 成功 modelDir=" + modelDir + " lang=" + language);
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
        synchronized (mLock) {
            recognizer = mRecognizer;
            vad = mVad;
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

            // 阶段 2：逐段解码
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
                    callback.onSegment(text, startMs, endMs);
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

    /** SenseVoice 事件标记清洗：<|NEUTRAL|> <|laughter|> <|Speech|> 等 */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return EVENT_TAG.matcher(raw).replaceAll("").trim();
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
