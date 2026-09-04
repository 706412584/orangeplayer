package com.orange.playerlibrary.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * AssParser 纯 Java 解析器单元测试。
 */
public class AssParserTest {

    private static final String VALID_ASS =
            "[Script Info]\n"
            + "Title: Test\n"
            + "ScriptType: v4.00+\n"
            + "\n"
            + "[V4+ Styles]\n"
            + "Format: Name, Fontname, Fontsize, PrimaryColour\n"
            + "Style: Default,Arial,20,&H00FFFFFF\n"
            + "\n"
            + "[Events]\n"
            + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
            + "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\pos(960,50)}Hello {\\i1}world{\\i0}!\n"
            + "Dialogue: 0,0:00:04.50,0:00:06.00,Default,,0,0,0,,Second line\\Nwith newline\n"
            + "Dialogue: 0,0:00:07.00,0:00:06.00,Default,,0,0,0,,Invalid reversed time\n"
            + "Comment: 0,0:00:08.00,0:00:09.00,Default,,0,0,0,,This is a comment\n";

    @Test
    public void 检测ASS内容() {
        assertTrue(AssParser.isAssContent(VALID_ASS));
        assertTrue(AssParser.isAssContent("[Script Info]\nanything"));
        assertFalse(AssParser.isAssContent("1\n00:00:01,000 --> 00:00:02,000\ntext"));
        assertFalse(AssParser.isAssContent(null));
        assertFalse(AssParser.isAssContent(""));
    }

    @Test
    public void 解析Dialogue行并剥离标签() {
        List<SubtitleEntry> entries = AssParser.parse(VALID_ASS);
        // 3 条 Dialogue 中 1 条时间倒置被丢弃，Comment 行不计
        assertEquals(2, entries.size());

        SubtitleEntry first = entries.get(0);
        assertEquals(1000L, first.getStartTime());
        assertEquals(3000L, first.getEndTime());
        assertEquals("Hello world!", first.getText());
    }

    @Test
    public void 换行标签转换为换行符() {
        List<SubtitleEntry> entries = AssParser.parse(VALID_ASS);
        assertEquals("Second line\nwith newline", entries.get(1).getText());
        assertEquals(4500L, entries.get(1).getStartTime());
        assertEquals(6000L, entries.get(1).getEndTime());
    }

    @Test
    public void 时间倒置的Dialogue被丢弃() {
        List<SubtitleEntry> entries = AssParser.parse(VALID_ASS);
        for (SubtitleEntry entry : entries) {
            assertTrue("end must be after start",
                    entry.getEndTime() > entry.getStartTime());
        }
    }

    @Test
    public void Comment行不被解析() {
        List<SubtitleEntry> entries = AssParser.parse(VALID_ASS);
        for (SubtitleEntry entry : entries) {
            assertFalse(entry.getText().contains("comment"));
        }
    }

    @Test
    public void 空内容与null返回空列表() {
        assertTrue(AssParser.parse(null).isEmpty());
        assertTrue(AssParser.parse("").isEmpty());
        assertTrue(AssParser.parse("[Script Info]\nno events").isEmpty());
    }

    @Test
    public void 恶意输入不崩溃() {
        assertTrue(AssParser.parse("Dialogue:").isEmpty());
        assertTrue(AssParser.parse("Dialogue: garbage").isEmpty());
        assertTrue(AssParser.parse("Dialogue: 0,bad,bad,Default,,0,0,0,,text").isEmpty());
        // 文本含逗号：splitCsv 保留最后一份完整
        List<SubtitleEntry> comma = AssParser.parse(
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,text, with, commas");
        assertEquals(1, comma.size());
        assertEquals("text, with, commas", comma.get(0).getText());
    }

    @Test
    public void 文本为纯标签的Dialogue被丢弃() {
        List<SubtitleEntry> entries = AssParser.parse(
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\pos(1,1)}");
        assertTrue(entries.isEmpty());
    }

    @Test
    public void 毫秒精度时间解析() {
        assertEquals(3661000L, AssParser.parseAssTime("1:01:01.00"));
        assertEquals(59590L, AssParser.parseAssTime("0:00:59.59"));
        assertEquals(-1L, AssParser.parseAssTime("invalid"));
        assertEquals(-1L, AssParser.parseAssTime(null));
    }

    @Test
    public void CRLF换行兼容() {
        List<SubtitleEntry> entries = AssParser.parse(
                "[Events]\r\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,crlf text\r\n");
        assertEquals(1, entries.size());
        assertEquals("crlf text", entries.get(0).getText());
    }

    @Test
    public void Styles段识别() {
        assertFalse(AssParser.parseStyles(VALID_ASS).isEmpty());
        assertTrue(AssParser.parseStyles(null).isEmpty());
    }
}
