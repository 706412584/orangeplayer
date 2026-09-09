package com.orange.playerlibrary.speech;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 音轨抽取器：MediaExtractor + MediaCodec 把视频音轨解码为
 * 16k/单声道/16bit PCM 裸流（ASR 输入）。
 *
 * 为什么不用 orange-ffmpeg：其 native 是 remux-only（流拷贝不转码），
 * 无法完成 aac→pcm 解码与重采样。系统 MediaCodec 解码 mp4/aac 是播放器
 * 看家能力，零新依赖；重采样（任意源采样率→16k）用 AudioResampler 兜底。
 */
public class AudioTrackExtractor {

    private static final String TAG = "AudioTrackExtractor";

    public interface ExtractListener {
        /** 阶段进度（0-100，按读取时长估算） */
        void onProgress(int percent);

        /** 是否取消 */
        boolean isCancelled();
    }

    /** 目标采样率（SenseVoice/VAD 硬性要求） */
    public static final int TARGET_SAMPLE_RATE = 16000;

    /**
     * 解码视频音轨为裸 PCM（16k/单声道/s16le）。
     *
     * @param videoFile  视频文件
     * @param pcmFile    输出裸 PCM 文件（无头）
     * @param durationUs 视频总时长（微秒），用于进度；<=0 时按 MediaExtractor 读取
     * @param listener   进度/取消回调（可 null）
     * @return 实际输出采样率（若源已是 16k 且单声道则无需重采样；否则调用方应重采样）
     * @throws IOException 无音轨/解码失败等
     */
    public static int extractPcm(File videoFile, File pcmFile, long durationUs,
                                 ExtractListener listener) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(videoFile.getAbsolutePath());
            int trackIndex = selectAudioTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("视频无音轨");
            }
            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new IOException("音轨 MIME 未知");
            }
            int srcSampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : TARGET_SAMPLE_RATE;
            int srcChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            Log.d(TAG, "音轨: mime=" + mime + " rate=" + srcSampleRate
                    + " ch=" + srcChannels + " duration=" + format.getLong(MediaFormat.KEY_DURATION) + "us");

            // 若已是 16k 单声道可跳过重采样；否则输出源格式，由调用方重采样
            boolean needResample = srcSampleRate != TARGET_SAMPLE_RATE || srcChannels != 1;

            MediaCodec decoder = MediaCodec.createDecoderByType(mime);
            try {
                decoder.configure(format, null, null, 0);
                decoder.start();
                try (FileOutputStream out = new FileOutputStream(pcmFile)) {
                    decodeLoop(extractor, decoder, out, durationUs, listener);
                } finally {
                    try {
                        decoder.stop();
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                decoder.release();
            }
            return needResample ? srcSampleRate : TARGET_SAMPLE_RATE;
        } finally {
            extractor.release();
        }
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private static void decodeLoop(MediaExtractor extractor, MediaCodec decoder,
                                   FileOutputStream out, long durationUs,
                                   ExtractListener listener) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        int outChannels = 1;    // 解码输出声道（INFO_OUTPUT_FORMAT_CHANGED 更新）
        int outRate = 16000;
        long reported = 0;

        while (!outputDone) {
            if (listener != null && listener.isCancelled()) {
                throw new IOException("取消");
            }

            // 喂输入
            if (!inputDone) {
                int inIdx = decoder.dequeueInputBuffer(10_000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        int sampleSize = extractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
            }

            // 取输出
            int outIdx = decoder.dequeueOutputBuffer(info, 10_000);
            if (outIdx >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
                if (info.size > 0) {
                    ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                    if (outBuf != null) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        // 直接混单声道写流（解码输出为 PCM s16 交错）
                        writeMonoPcm(outBuf, outChannels, out);
                    }
                }
                decoder.releaseOutputBuffer(outIdx, false);

                // 进度（按解码时间戳 / 总时长）
                if (listener != null && durationUs > 0 && info.presentationTimeUs > 0) {
                    int pct = (int) (info.presentationTimeUs * 100L / durationUs);
                    if (pct > reported) {
                        reported = pct;
                        listener.onProgress(Math.min(pct, 99));
                    }
                }
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = decoder.getOutputFormat();
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
                Log.d(TAG, "解码输出格式: " + outRate + "Hz " + outChannels + "ch");
            }
        }
        out.flush();
    }

    /** 交错 s16 PCM 混单声道写流（逐样本均值） */
    private static void writeMonoPcm(ByteBuffer src, int channels, FileOutputStream out)
            throws IOException {
        int frames = src.remaining() / 2 / Math.max(1, channels);
        byte[] mono = new byte[frames * 2];
        src.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            long sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += src.getShort();
            }
            short v = (short) (sum / channels);
            mono[i * 2] = (byte) (v & 0xFF);
            mono[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        out.write(mono);
    }
}
