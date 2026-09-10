package com.orange.playerlibrary.ocr;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * LanguagePackManager 下载内容校验单测。
 *
 * 背景：已停止服务的 GitHub 加速站对任意路径返回 HTTP 200 + HTML 落地页
 * （约 1.8KB），只检查状态码会把错误页存成 .traineddata，用户看到
 * 「下载完成」但 OCR 直接失败。故按体积与文件头识别。
 */
public class LanguagePackValidationTest {

    /** 真实的 .traineddata 文件头（Tesseract 模型为二进制，非文本） */
    private static final byte[] REAL_HEAD = {0x18, 0x00, 0x00, 0x00, (byte) 0xc4, 0x00, 0x00, 0x00};

    /** HTML 落地页开头 */
    private static final byte[] HTML_HEAD = "<!DOCTYPE html>\n<html lang=\"\">".getBytes();

    /** JSON 错误正文开头 */
    private static final byte[] JSON_HEAD = "{\"error\":\"not found\"}".getBytes();

    @Test
    public void acceptsRealFileAtExpectedSize() {
        assertNull(LanguagePackManager.validateDownloaded(
                2_500_000, 2_500_000, REAL_HEAD));
    }

    @Test
    public void acceptsJsdelivrCompressedSize() {
        // jsDelivr 的版本经过压缩（1700263 vs 声明 2500000），约 68%，
        // 校验需容忍此类差异
        assertNull(LanguagePackManager.validateDownloaded(
                1_700_263, 2_500_000, REAL_HEAD));
    }

    @Test
    public void rejectsGhproxyHtmlPage() {
        // 真机实测：ghproxy.com 返回 200 + 1797 字节 HTML
        assertNotNull("HTML 错误页必须拒绝",
                LanguagePackManager.validateDownloaded(1797, 2_500_000, HTML_HEAD));
    }

    @Test
    public void rejectsHtmlEvenWhenSizeLooksPlausible() {
        assertNotNull("文件头是 HTML 即拒绝，不看体积",
                LanguagePackManager.validateDownloaded(2_500_000, 2_500_000, HTML_HEAD));
    }

    @Test
    public void rejectsJsonErrorBody() {
        assertNotNull(LanguagePackManager.validateDownloaded(
                2_500_000, 2_500_000, JSON_HEAD));
    }

    @Test
    public void rejectsTruncatedDownload() {
        assertNotNull("截断文件应拒绝",
                LanguagePackManager.validateDownloaded(120_000, 2_500_000, REAL_HEAD));
    }

    @Test
    public void rejectsTooSmallFileWithoutExpectedSize() {
        // 未知预估体积时仍有绝对下限兜底
        assertNotNull(LanguagePackManager.validateDownloaded(1024, 0, REAL_HEAD));
    }

    @Test
    public void acceptsWhenExpectedSizeUnknownAndSizeReasonable() {
        assertNull(LanguagePackManager.validateDownloaded(800_000, 0, REAL_HEAD));
    }

    @Test
    public void nullHeadStillChecksSize() {
        assertNull(LanguagePackManager.validateDownloaded(2_500_000, 2_500_000, null));
        assertNotNull(LanguagePackManager.validateDownloaded(100, 2_500_000, null));
    }
}
