package com.orange.playerlibrary.speech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

public class AudioResamplerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 生成指定采样率的正弦/常量 PCM */
    private File writePcm(int sampleRate, int channels, double freq, double seconds) throws Exception {
        File f = new File(tmp.getRoot(), "in_" + sampleRate + "_" + channels + ".pcm");
        int frames = (int) (sampleRate * seconds);
        byte[] data = new byte[frames * channels * 2];
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            short v = (short) (Math.sin(2 * Math.PI * freq * i / sampleRate) * 8000);
            for (int c = 0; c < channels; c++) {
                bb.putShort(v);
            }
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        return f;
    }

    @Test
    public void passthroughWhenAlready16kMono() throws Exception {
        File in = writePcm(16000, 1, 440, 0.5);
        File out = new File(tmp.getRoot(), "out.pcm");
        AudioResampler.resampleTo16kMono(in, 16000, 1, out);
        assertEquals(in.length(), out.length());   // 直拷同大小
    }

    @Test
    public void resamples44kTo16kCorrectLength() throws Exception {
        File in = writePcm(44100, 1, 440, 0.5);
        File out = new File(tmp.getRoot(), "out.pcm");
        AudioResampler.resampleTo16kMono(in, 44100, 1, out);
        long expectedFrames = (long) Math.ceil(44100 * 0.5 / (44100.0 / 16000.0));
        assertEquals(expectedFrames * 2, out.length());   // s16
    }

    @Test
    public void stereoDownmixToMono() throws Exception {
        // 立体声：左 -8000，右 +8000 → 平均应为 0（或接近）
        File in = new File(tmp.getRoot(), "stereo.pcm");
        int frames = 1600;
        byte[] data = new byte[frames * 2 * 2];
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            bb.putShort((short) -8000);
            bb.putShort((short) 8000);
        }
        try (FileOutputStream out = new FileOutputStream(in)) {
            out.write(data);
        }
        File out = new File(tmp.getRoot(), "mono.pcm");
        AudioResampler.resampleTo16kMono(in, 16000, 2, out);
        byte[] result = Files.readAllBytes(out.toPath());
        ByteBuffer rb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        short first = rb.getShort();
        assertTrue("混单声道应趋近 0, got " + first, Math.abs(first) < 300);
    }
}
