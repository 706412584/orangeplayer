package com.orange.playerlibrary.ocr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * MlKitTranslationEngine 语言码 → MLKit 常量名映射单测。
 *
 * 背景（真机缺陷）：原实现用「语言码转大写」猜 TranslateLanguage 的字段名，
 * 只对 ZH/EN/JA/KO 巧合成立；MLKit 里其余语种只有全称常量（FRENCH 而非 FR），
 * 于是选法语等目标语言时预装与本地兜底全部静默失败。
 */
public class MlKitFieldNameTest {

    @Test
    public void shortCodesMapToFullConstantNames() {
        assertEquals("CHINESE", MlKitTranslationEngine.mlKitFieldName("zh"));
        assertEquals("ENGLISH", MlKitTranslationEngine.mlKitFieldName("en"));
        assertEquals("JAPANESE", MlKitTranslationEngine.mlKitFieldName("ja"));
        assertEquals("KOREAN", MlKitTranslationEngine.mlKitFieldName("ko"));
        // 以下 7 个是缺陷所在：不能是 FR / DE / ES / RU / AR / TH / VI
        assertEquals("FRENCH", MlKitTranslationEngine.mlKitFieldName("fr"));
        assertEquals("GERMAN", MlKitTranslationEngine.mlKitFieldName("de"));
        assertEquals("SPANISH", MlKitTranslationEngine.mlKitFieldName("es"));
        assertEquals("RUSSIAN", MlKitTranslationEngine.mlKitFieldName("ru"));
        assertEquals("ARABIC", MlKitTranslationEngine.mlKitFieldName("ar"));
        assertEquals("THAI", MlKitTranslationEngine.mlKitFieldName("th"));
        assertEquals("VIETNAMESE", MlKitTranslationEngine.mlKitFieldName("vi"));
    }

    /**
     * 覆盖范围须与 ProgressiveTranslator.mapToMlKitCode 一致，否则设置界面选得到的语言会预装失败。
     * MLKit 的语言常量都是全称（CHINESE / FRENCH…），故映射结果必定长于两字母语言码——
     * 「转大写」的旧实现会返回等长的 FR，被此断言拦下。
     */
    @Test
    public void everyMappedLanguageHasFullConstantName() {
        for (String name : com.orange.playerlibrary.ai.ProgressiveTranslator.TARGET_LANGUAGES) {
            String code = com.orange.playerlibrary.ai.ProgressiveTranslator.mapToMlKitCode(name);
            String field = MlKitTranslationEngine.mlKitFieldName(code);
            assertEquals("语言 " + name + " 的常量名不像 MLKit 全称常量",
                    true, field.length() > code.length());
        }
    }
}
