package com.orange.playerlibrary.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ResponseParserTest {

    private final ResponseParser parser = new ResponseParser();

    @Test
    public void parsesBasicLines() {
        ResponseParser.ParseResult r = parser.parse("0|你好\n1|世界\n");
        assertEquals(2, r.translations.size());
        assertEquals(0, r.translations.get(0).idx);
        assertEquals("你好", r.translations.get(0).text);
        assertEquals(1, r.translations.get(1).idx);
        assertEquals("世界", r.translations.get(1).text);
    }

    @Test
    public void toleratesChineseColonAndDotSeparators() {
        ResponseParser.ParseResult r = parser.parse("3：收到\n4. 好的\n5、行吧\n6 嗯\n");
        assertEquals(4, r.translations.size());
        assertEquals("收到", r.translations.get(0).text);
        assertEquals("好的", r.translations.get(1).text);
        assertEquals("行吧", r.translations.get(2).text);
        assertEquals("嗯", r.translations.get(3).text);
    }

    @Test
    public void extractsGlossaryLines() {
        ResponseParser.ParseResult r = parser.parse(
                "## Naruto -> 鸣人\n0|我是鸣人\n## Sakura -> 小樱\n1|小樱来了\n");
        assertEquals(2, r.translations.size());
        assertEquals(2, r.glossary.size());
        assertEquals("鸣人", r.glossary.get("Naruto"));
        assertEquals("小樱", r.glossary.get("Sakura"));
    }

    @Test
    public void stripsMarkdownCodeFence() {
        ResponseParser.ParseResult r = parser.parse("```\n0|译文\n1|第二句\n```\n");
        assertEquals(2, r.translations.size());
        assertEquals("译文", r.translations.get(0).text);
    }

    @Test
    public void skipsUselessLinesButCountsThem() {
        ResponseParser.ParseResult r = parser.parse(
                "好的，我来翻译：\n0|第一句\n（以上是翻译结果）\n");
        assertEquals(1, r.translations.size());
        assertEquals(2, r.skippedLines);
    }

    @Test
    public void handlesEmptyAndNull() {
        ResponseParser.ParseResult r1 = parser.parse("");
        assertTrue(r1.translations.isEmpty());
        ResponseParser.ParseResult r2 = parser.parse(null);
        assertTrue(r2.translations.isEmpty());
    }

    @Test
    public void keepsTranslationTextWithSpacesAndPunctuation() {
        ResponseParser.ParseResult r = parser.parse("0|I'm here, buddy!\n");
        assertEquals("I'm here, buddy!", r.translations.get(0).text);
    }
}
