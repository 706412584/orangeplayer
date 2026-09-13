package com.orange.playerlibrary.subtitle;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * AI 批翻译会**覆盖式**回写译文（SubtitleManager.applyAiTranslationFrom 直接
 * setText），因此条目必须自己留一份原文，否则切换翻译语言时无源可译——真机
 * 表现为「换语言后已播放字幕不翻译，要重启或重播」。
 */
public class SubtitleEntryOriginalTextTest {

    @Test
    public void originalTextSurvivesTranslationOverwrite() {
        SubtitleEntry e = new SubtitleEntry(0, 1000, "你好世界");
        assertEquals("你好世界", e.getOriginalText());

        // 模拟 AI 批翻译回写：译文覆盖 text
        e.setText("Hello world");

        assertEquals("Hello world", e.getText());
        assertEquals("原文必须保留，否则换语言无法重译", "你好世界", e.getOriginalText());
    }

    @Test
    public void originalTextIsNotNullForNullInput() {
        // 解析器允许空文本条目，此时 originalText 也应为 null 而不是抛异常
        SubtitleEntry e = new SubtitleEntry(0, 1000, null);
        assertEquals(null, e.getOriginalText());
        e.setText("translated");
        assertEquals(null, e.getOriginalText());
    }

    @Test
    public void repeatedTranslationKeepsFirstOriginal() {
        // 多次换语言重译：原文始终是最初那条，不会被上一次的译文污染
        SubtitleEntry e = new SubtitleEntry(0, 1000, "原始");
        e.setText("first");
        e.setText("second");
        assertEquals("原始", e.getOriginalText());
    }
}
