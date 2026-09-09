package com.orange.playerlibrary.speech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.orange.playerlibrary.subtitle.SubtitleEntry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class AsrSubtitleGeneratorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void formatSrtTime() {
        assertEquals("00:00:00,000", AsrSubtitleGenerator.formatSrtTime(0));
        assertEquals("00:00:01,250", AsrSubtitleGenerator.formatSrtTime(1250));
        assertEquals("00:01:02,003", AsrSubtitleGenerator.formatSrtTime(62003));
        assertEquals("01:00:00,000", AsrSubtitleGenerator.formatSrtTime(3600000));
    }

    @Test
    public void writeSrtContent() throws Exception {
        List<SubtitleEntry> entries = new ArrayList<>();
        entries.add(new SubtitleEntry(1000, 3500, "Hello world"));
        entries.add(new SubtitleEntry(4000, 8000, "第二句台词"));
        File f = new File(tmp.getRoot(), "out.srt");
        AsrSubtitleGenerator.writeSrt(f, entries);

        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        String expected = "1\n"
                + "00:00:01,000 --> 00:00:03,500\n"
                + "Hello world\n\n"
                + "2\n"
                + "00:00:04,000 --> 00:00:08,000\n"
                + "第二句台词\n\n";
        assertEquals(expected, content);
    }

    @Test
    public void emptyEntriesWritesEmptyFile() throws Exception {
        File f = new File(tmp.getRoot(), "empty.srt");
        AsrSubtitleGenerator.writeSrt(f, new ArrayList<>());
        assertEquals("", new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        assertTrue(f.exists());
    }

    @Test
    public void wrapRiffWavHeaderCreatesValidWav() throws Exception {
        // 写 32000 字节裸 PCM（1 秒 16k 单声道 s16le）
        java.io.File pcm = new java.io.File(tmp.getRoot(), "raw.pcm");
        byte[] data = new byte[32000];
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(pcm)) {
            out.write(data);
        }
        AsrSubtitleGenerator.wrapRiffWavHeader(pcm);

        byte[] wav = Files.readAllBytes(pcm.toPath());
        assertEquals(32044, wav.length);   // 44 头 + 32000 数据
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("fmt ", new String(wav, 12, 4, StandardCharsets.US_ASCII));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));
        // fmt: PCM=1, mono=1, 16000Hz, 16bit
        assertEquals(1, wav[20] & 0xFF);
        assertEquals(1, wav[22] & 0xFF);
        assertEquals(0x80, wav[24] & 0xFF);  // 16000 = 0x3E80 LE → 低字节 0x80
        assertEquals(0x3E, wav[25] & 0xFF);
        assertEquals(16, wav[34] & 0xFF);
        // data 大小 = 32000 = 0x7D00
        assertEquals(0x00, wav[40] & 0xFF);
        assertEquals(0x7D, wav[41] & 0xFF);
    }
}
