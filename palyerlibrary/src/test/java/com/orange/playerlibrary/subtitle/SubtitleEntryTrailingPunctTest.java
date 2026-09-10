package com.orange.playerlibrary.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * SubtitleEntry.stripTrailingPunctuation 单测：字幕显示去尾部标点的契约。
 */
public class SubtitleEntryTrailingPunctTest {

    @Test
    public void removesChineseSentenceEndPunctuation() {
        assertEquals("今天天气很好", SubtitleEntry.stripTrailingPunctuation("今天天气很好。"));
        assertEquals("真的吗", SubtitleEntry.stripTrailingPunctuation("真的吗？"));
        assertEquals("太好了", SubtitleEntry.stripTrailingPunctuation("太好了！"));
    }

    @Test
    public void removesCommaAtEnd() {
        assertEquals("服务好", SubtitleEntry.stripTrailingPunctuation("服务好，"));
        assertEquals("第一项", SubtitleEntry.stripTrailingPunctuation("第一项、"));
    }

    @Test
    public void removesConsecutiveTrailingPunctuation() {
        assertEquals("好的", SubtitleEntry.stripTrailingPunctuation("好的，。！"));
    }

    @Test
    public void keepsInnerPunctuation() {
        assertEquals("服务好，功能强大", SubtitleEntry.stripTrailingPunctuation("服务好，功能强大。"));
        assertEquals("首先，云视频", SubtitleEntry.stripTrailingPunctuation("首先，云视频，"));
    }

    @Test
    public void removesEnglishPunctuation() {
        assertEquals("hello world", SubtitleEntry.stripTrailingPunctuation("hello world."));
        assertEquals("really", SubtitleEntry.stripTrailingPunctuation("really?!"));
    }

    @Test
    public void trimsWhitespaceLeftAfterPunctuation() {
        assertEquals("结束", SubtitleEntry.stripTrailingPunctuation("结束 。 "));
    }

    @Test
    public void punctuationOnlyTextBecomesEmpty() {
        assertEquals("", SubtitleEntry.stripTrailingPunctuation("。"));
        assertEquals("", SubtitleEntry.stripTrailingPunctuation("，。！"));
    }

    @Test
    public void textWithoutPunctuationUnchanged() {
        assertEquals("没有标点", SubtitleEntry.stripTrailingPunctuation("没有标点"));
    }

    @Test
    public void nullStaysNull() {
        assertNull(SubtitleEntry.stripTrailingPunctuation(null));
    }
}
