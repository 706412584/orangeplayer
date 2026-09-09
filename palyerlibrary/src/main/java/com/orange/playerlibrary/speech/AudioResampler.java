package com.orange.playerlibrary.speech;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * PCM 重采样器：把解码输出的 PCM（任意采样率/声道）转为 16k/单声道/s16le
 * （线性插值）。仅处理 s16le 交错输入（MediaCodec PCM 默认）。
 *
 * 实现：整段读入内存数组随机插值（正确性优先）；内存上限按
 * 输入时长：30min@48k 立体声 ≈ 172MB short≈344MB 字节——超限抛错。
 * 语音 ASR 场景线性插值足够。
 */
public class AudioResampler {

    /** 输入字节数上限（≈1.5GB 输入/45min@48k stereo）防止 OOM */
    private static final long MAX_INPUT_BYTES = 1_500_000_000L;

    public static void resampleTo16kMono(File inFile, int inRate, int inChannels,
                                         File outFile) throws IOException {
        if (inRate == 16000 && inChannels == 1) {
            Files.copy(inFile.toPath(), outFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        if (inFile.length() > MAX_INPUT_BYTES) {
            throw new IOException("音频过大（>1.5GB 输入）无法重采样");
        }

        // 读入全部并混单声道
        int totalFrames;
        short[] mono;
        try (InputStream in = new FileInputStream(inFile)) {
            byte[] all = in.readAllBytes();
            totalFrames = all.length / (2 * inChannels);
            mono = new short[totalFrames];
            for (int i = 0; i < totalFrames; i++) {
                long sum = 0;
                for (int c = 0; c < inChannels; c++) {
                    int idx = (i * inChannels + c) * 2;
                    sum += (short) ((all[idx] & 0xFF) | (all[idx + 1] << 8));
                }
                mono[i] = (short) (sum / inChannels);
            }
        }

        double ratio = (double) inRate / 16000.0;   // 输入帧/输出样本
        long outCount = (long) Math.ceil(totalFrames / ratio);
        try (OutputStream out = new FileOutputStream(outFile)) {
            for (long n = 0; n < outCount; n++) {
                double pos = n * ratio;
                long i0 = (long) Math.floor(pos);
                long i1 = Math.min(i0 + 1, totalFrames - 1);
                double frac = pos - i0;
                int a = mono[(int) i0];
                int b = mono[(int) i1];
                int v = (int) (a * (1 - frac) + b * frac);
                if (v > 32767) {
                    v = 32767;
                }
                if (v < -32768) {
                    v = -32768;
                }
                out.write(v & 0xFF);
                out.write((v >> 8) & 0xFF);
            }
            out.flush();
        }
    }
}
