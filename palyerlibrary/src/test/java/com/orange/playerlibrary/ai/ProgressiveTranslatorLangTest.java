package com.orange.playerlibrary.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

import android.content.Context;

import org.junit.Test;

/**
 * ProgressiveTranslator 语言码映射单测。
 *
 * 目标语言来自 AI 设置（用户填的是「简体中文」这类显示名），
 * MLKit 本地兜底需要 ISO 语言码，映射错误会导致本地翻译直接跳过。
 */
public class ProgressiveTranslatorLangTest {

    @Test
    public void mapsChineseDisplayNames() {
        assertEquals("zh", ProgressiveTranslator.mapToMlKitCode("简体中文"));
        assertEquals("zh", ProgressiveTranslator.mapToMlKitCode("繁体中文"));
        assertEquals("zh", ProgressiveTranslator.mapToMlKitCode("中文"));
    }

    @Test
    public void mapsEnglishAndOthers() {
        assertEquals("en", ProgressiveTranslator.mapToMlKitCode("英语"));
        assertEquals("en", ProgressiveTranslator.mapToMlKitCode("英文"));
        assertEquals("ja", ProgressiveTranslator.mapToMlKitCode("日语"));
        assertEquals("ko", ProgressiveTranslator.mapToMlKitCode("韩语"));
        assertEquals("fr", ProgressiveTranslator.mapToMlKitCode("法语"));
        assertEquals("de", ProgressiveTranslator.mapToMlKitCode("德语"));
        assertEquals("es", ProgressiveTranslator.mapToMlKitCode("西班牙语"));
        assertEquals("ru", ProgressiveTranslator.mapToMlKitCode("俄语"));
        assertEquals("ar", ProgressiveTranslator.mapToMlKitCode("阿拉伯语"));
        assertEquals("th", ProgressiveTranslator.mapToMlKitCode("泰语"));
        assertEquals("vi", ProgressiveTranslator.mapToMlKitCode("越南语"));
    }

    @Test
    public void mapsIsoAndTesseractCodes() {
        assertEquals("zh", ProgressiveTranslator.mapToMlKitCode("zh"));
        assertEquals("zh", ProgressiveTranslator.mapToMlKitCode("chi_sim"));
        assertEquals("en", ProgressiveTranslator.mapToMlKitCode("eng"));
        assertEquals("ja", ProgressiveTranslator.mapToMlKitCode("jpn"));
        assertEquals("ko", ProgressiveTranslator.mapToMlKitCode("kor"));
    }

    @Test
    public void caseAndWhitespaceInsensitive() {
        assertEquals("en", ProgressiveTranslator.mapToMlKitCode("ENGLISH"));
        assertEquals("zh", ProgressiveTranslator.mapToMlKitCode("  中文  "));
    }

    @Test
    public void mapsCantoneseAsChinese() {
        // SenseVoice 检测到粤语（yue）时转写已是书面中文，
        // 按 zh 走 MLKit，否则本地兜底对粤语视频静默跳过
        assertEquals("zh", ProgressiveTranslator.mapToMlKitCode("yue"));
        assertEquals("zh", ProgressiveTranslator.mapToMlKitCode("粤语"));
    }

    @Test
    public void unknownReturnsNull() {
        assertNull(ProgressiveTranslator.mapToMlKitCode(null));
        assertNull(ProgressiveTranslator.mapToMlKitCode(""));
        assertNull(ProgressiveTranslator.mapToMlKitCode("克林贡语"));
        assertNull(ProgressiveTranslator.mapToMlKitCode("xx"));
    }

    /**
     * retarget 的前置校验：目标语言为空 / 会话未配置（mCallback==null）时必须
     * 直接返回 false 而不是改状态——否则会把在途会话的目标语言改成空值，
     * 后续批次全部翻译失败。
     */
    @Test
    public void retargetRejectsBlankLanguageAndUnconfiguredSession() {
        Context context = mock(Context.class);
        ProgressiveTranslator translator = new ProgressiveTranslator(context);
        assertFalse(translator.retarget("http://v/1.mp4", null, null));
        assertFalse(translator.retarget("http://v/1.mp4", "", null));
        assertFalse(translator.retarget("http://v/1.mp4", "   ", null));
        // 未 configure（无 callback）：合法语言也不该触发
        assertFalse(translator.retarget("http://v/1.mp4", "英语", null));
    }
}
