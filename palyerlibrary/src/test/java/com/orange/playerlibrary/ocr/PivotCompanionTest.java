package com.orange.playerlibrary.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * MlKitTranslationEngine 模型目录名 → 语言码 的解析单测。
 *
 * 背景（真机缺陷）：MLKit 目录名是两个语言码**字典序排序**后拼接（见 AAR 内
 * {@code zzad.zzc}：{@code Arrays.sort} 后 {@code "%s_%s"}），旧实现只认
 * {@code en_} 前缀，于是码序在 "en" 之前的语种（德语 de、阿拉伯语 ar 等）
 * 目录名形如 {@code de_en} / {@code ar_en}，被误判为「未装」。
 */
public class PivotCompanionTest {

    @Test
    public void englishFirstOrderIsRecognized() {
        // de/fr/ja/ko/zh 等码序在 en 之后，英语排前面
        assertEquals("zh", MlKitTranslationEngine.pivotCompanion("en_zh"));
        assertEquals("ja", MlKitTranslationEngine.pivotCompanion("en_ja"));
        assertEquals("ko", MlKitTranslationEngine.pivotCompanion("en_ko"));
        assertEquals("fr", MlKitTranslationEngine.pivotCompanion("en_fr"));
        assertEquals("es", MlKitTranslationEngine.pivotCompanion("en_es"));
        assertEquals("th", MlKitTranslationEngine.pivotCompanion("en_th"));
        assertEquals("vi", MlKitTranslationEngine.pivotCompanion("en_vi"));
    }

    /** 缺陷所在：码序在 en 之前的语种，目录名英语在后 */
    @Test
    public void englishSecondOrderIsRecognized() {
        assertEquals("de", MlKitTranslationEngine.pivotCompanion("de_en"));
        assertEquals("ar", MlKitTranslationEngine.pivotCompanion("ar_en"));
    }

    @Test
    public void threeLetterCodesAreSupported() {
        assertEquals("fil", MlKitTranslationEngine.pivotCompanion("en_fil"));
        assertEquals("fil", MlKitTranslationEngine.pivotCompanion("fil_en"));
    }

    @Test
    public void nonEnglishPairsAreRejected() {
        // 不含英语的目录不构成可用模型（翻译恒经英语中转）
        assertNull(MlKitTranslationEngine.pivotCompanion("zh_ja"));
        assertNull(MlKitTranslationEngine.pivotCompanion("de_fr"));
    }

    @Test
    public void intermediateDirsAndMalformedNamesAreRejected() {
        // temp 等中间目录
        assertNull(MlKitTranslationEngine.pivotCompanion("temp"));
        assertNull(MlKitTranslationEngine.pivotCompanion("en"));
        assertNull(MlKitTranslationEngine.pivotCompanion("_en"));
        assertNull(MlKitTranslationEngine.pivotCompanion("en_"));
        assertNull(MlKitTranslationEngine.pivotCompanion("en_zh_extra"));
        assertNull(MlKitTranslationEngine.pivotCompanion("EN_ZH"));
        assertNull(MlKitTranslationEngine.pivotCompanion("en_z"));
        assertNull(MlKitTranslationEngine.pivotCompanion("en_zh4"));
        assertNull(MlKitTranslationEngine.pivotCompanion(null));
    }
}
