package com.orange.player.sherpa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * SenseVoice 语种字段清洗单测。
 *
 * 回归背景：SenseVoice 的 lang 形如 {@code <|zh|>}——语种在标记**内部**。
 * 早期实现沿用了文本清洗（删除 {@code <|...|>} 标记），把语种连内容一起删掉，
 * 结果恒为 null，本地翻译兜底因拿不到源语言而完全不工作。
 */
public class SherpaLangSanitizeTest {

    @Test
    public void extractsLanguageFromTag() {
        assertEquals("zh", SherpaBatchAsrEngine.sanitizeLang("<|zh|>"));
        assertEquals("en", SherpaBatchAsrEngine.sanitizeLang("<|en|>"));
        assertEquals("ja", SherpaBatchAsrEngine.sanitizeLang("<|ja|>"));
        assertEquals("ko", SherpaBatchAsrEngine.sanitizeLang("<|ko|>"));
        assertEquals("yue", SherpaBatchAsrEngine.sanitizeLang("<|yue|>"));
    }

    @Test
    public void handlesPlainCodes() {
        assertEquals("zh", SherpaBatchAsrEngine.sanitizeLang("zh"));
        assertEquals("en", SherpaBatchAsrEngine.sanitizeLang("EN"));
        assertEquals("zh", SherpaBatchAsrEngine.sanitizeLang("  zh  "));
    }

    @Test
    public void takesPrimarySubtag() {
        assertEquals("en", SherpaBatchAsrEngine.sanitizeLang("en-US"));
        assertEquals("zh", SherpaBatchAsrEngine.sanitizeLang("zh_CN"));
        assertEquals("en", SherpaBatchAsrEngine.sanitizeLang("<|en-US|>"));
    }

    @Test
    public void nullAndEmptyReturnNull() {
        assertNull(SherpaBatchAsrEngine.sanitizeLang(null));
        assertNull(SherpaBatchAsrEngine.sanitizeLang(""));
        assertNull(SherpaBatchAsrEngine.sanitizeLang("   "));
        assertNull(SherpaBatchAsrEngine.sanitizeLang("<|  |>"));
        assertNull(SherpaBatchAsrEngine.sanitizeLang("123"));
    }

    @Test
    public void textCleanStillRemovesTags() {
        // 文本清洗与语种提取方向相反：这里是删除标记
        assertEquals("你好世界", SherpaBatchAsrEngine.clean("<|zh|>你好<|NEUTRAL|>世界"));
        assertEquals("hello", SherpaBatchAsrEngine.clean("<|en|>hello"));
    }
}
