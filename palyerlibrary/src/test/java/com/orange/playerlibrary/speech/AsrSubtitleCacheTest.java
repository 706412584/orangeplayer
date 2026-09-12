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

/**
 * ASR 识别结果缓存的 srt 读写。
 *
 * 缓存读错比识别慢更致命：解析出的时间轴/文本一旦错位，重看时会显示错误字幕，
 * 且因为「命中缓存」不会再识别一遍，错误会一直保留。故对写入-解析往返做单测。
 */
public class AsrSubtitleCacheTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writeThenParseRoundTrip() throws Exception {
        List<SubtitleEntry> entries = new ArrayList<>();
        entries.add(new SubtitleEntry(0, 4200, "第一句"));
        entries.add(new SubtitleEntry(5000, 9100, "second line"));
        entries.add(new SubtitleEntry(3702000, 3705999, "一小时后"));

        File f = new File(tmp.getRoot(), "part.srt");
        AsrSubtitleCache.writeSrt(f, entries);
        List<SubtitleEntry> back = AsrSubtitleCache.parseSrt(f);

        assertEquals(entries.size(), back.size());
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(entries.get(i).getStartTime(), back.get(i).getStartTime());
            assertEquals(entries.get(i).getEndTime(), back.get(i).getEndTime());
            assertEquals(entries.get(i).getText(), back.get(i).getText());
        }
    }

    /** 文本里的换行必须被压平：否则解析时会被当成时间行/新条目 */
    @Test
    public void multilineTextIsFlattened() throws Exception {
        List<SubtitleEntry> entries = new ArrayList<>();
        entries.add(new SubtitleEntry(1000, 2000, "上句\n下句\r\n第三行"));

        File f = new File(tmp.getRoot(), "flat.srt");
        AsrSubtitleCache.writeSrt(f, entries);
        List<SubtitleEntry> back = AsrSubtitleCache.parseSrt(f);

        assertEquals(1, back.size());
        assertEquals("上句 下句 第三行", back.get(0).getText());
    }

    @Test
    public void textContainingArrowIsNotMistakenForTimeLine() throws Exception {
        List<SubtitleEntry> entries = new ArrayList<>();
        entries.add(new SubtitleEntry(1000, 2000, "A --> B"));

        File f = new File(tmp.getRoot(), "arrow.srt");
        AsrSubtitleCache.writeSrt(f, entries);
        List<SubtitleEntry> back = AsrSubtitleCache.parseSrt(f);

        assertEquals(1, back.size());
        assertEquals("A --> B", back.get(0).getText());
        assertEquals(1000, back.get(0).getStartTime());
    }

    @Test
    public void emptyTextEntrySurvivesRoundTrip() throws Exception {
        List<SubtitleEntry> entries = new ArrayList<>();
        entries.add(new SubtitleEntry(0, 1000, ""));
        entries.add(new SubtitleEntry(2000, 3000, "有内容"));

        File f = new File(tmp.getRoot(), "empty.srt");
        AsrSubtitleCache.writeSrt(f, entries);

        // 空文本条目在解析时按「无内容」丢弃，但不得因此吞掉后续条目
        List<SubtitleEntry> back = AsrSubtitleCache.parseSrt(f);
        assertEquals(1, back.size());
        assertEquals("有内容", back.get(0).getText());
        assertEquals(2000, back.get(0).getStartTime());
    }

    /** 外部 srt：CRLF 换行、多余空行、序号缺失都不能解析错位 */
    @Test
    public void parsesForeignSrtFormat() throws Exception {
        String content = "1\r\n"
                + "00:00:01,000 --> 00:00:03,500\r\n"
                + "Hello\r\n"
                + "\r\n"
                + "\r\n"
                + "2\r\n"
                + "00:00:04,000 --> 00:00:08,000\r\n"
                + "世界\r\n"
                + "多行第二行\r\n"
                + "\r\n";
        File f = new File(tmp.getRoot(), "foreign.srt");
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));

        List<SubtitleEntry> back = AsrSubtitleCache.parseSrt(f);
        assertEquals(2, back.size());
        assertEquals(1000, back.get(0).getStartTime());
        assertEquals("Hello", back.get(0).getText());
        assertEquals(4000, back.get(1).getStartTime());
        assertEquals("世界 多行第二行", back.get(1).getText());
    }

    @Test
    public void malformedTimeLineDropsEntryInsteadOfGuessing() throws Exception {
        String content = "1\n"
                + "not a time --> nope\n"
                + "坏条目\n\n"
                + "2\n"
                + "00:00:04,000 --> 00:00:08,000\n"
                + "好条目\n\n";
        File f = new File(tmp.getRoot(), "bad.srt");
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));

        List<SubtitleEntry> back = AsrSubtitleCache.parseSrt(f);
        assertEquals(1, back.size());
        assertEquals("好条目", back.get(0).getText());
    }

    @Test
    public void parseEmptyFileReturnsEmptyList() throws Exception {
        File f = new File(tmp.getRoot(), "none.srt");
        AsrSubtitleCache.writeSrt(f, new ArrayList<>());
        assertTrue(f.exists());
        assertTrue(AsrSubtitleCache.parseSrt(f).isEmpty());
    }
}
