package com.orange.playerlibrary.speech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * AsrModelDownloader 下载完整性校验单测。
 *
 * 回归背景（真机实测）：.part 恰好等于目标体积时，旧实现只处理「严格大于」，
 * 于是发 Range: bytes=&lt;total&gt;- 收到 416，被当作普通 HTTP 错误换源，
 * 两个源都 416 → 每次重试必然复现、无恢复入口，用户被永久卡死。
 * 现改为「>= 目标体积时先验哈希，通过则落位、否则丢弃重下」。
 */
public class AsrModelDownloaderTest {

    @Rule
    public TemporaryFolder mTemp = new TemporaryFolder();

    private static final String CONTENT = "orangeplayer-test-payload";

    @Test
    public void verifyHashPassesForMatchingContent() throws Exception {
        File f = writeFile("ok.bin", CONTENT);
        assertTrue(AsrModelDownloader.verifyHash(f, sha256Hex(CONTENT)));
    }

    @Test
    public void verifyHashFailsForDifferentContent() throws Exception {
        // 真机场景：.part 由 /dev/zero 填充到恰好目标体积，长度对但内容错
        File f = writeFile("zeros.bin", "not-the-real-bytes");
        assertFalse(AsrModelDownloader.verifyHash(f, sha256Hex(CONTENT)));
    }

    @Test
    public void verifyHashFailsForSameLengthDifferentContent() throws Exception {
        // 长度完全相同的两组不同字节（正是等长坏 .part 的形态）
        File a = writeFile("a.bin", "AAAAAAAA");
        File b = writeFile("b.bin", "BBBBBBBB");
        assertEquals(a.length(), b.length());
        assertTrue(AsrModelDownloader.verifyHash(a, sha256Hex("AAAAAAAA")));
        assertFalse(AsrModelDownloader.verifyHash(a, sha256Hex("BBBBBBBB")));
    }

    @Test
    public void verifyHashSkippedWhenExpectedIsNull() throws Exception {
        File f = writeFile("any.bin", CONTENT);
        assertTrue("未提供期望哈希时不拦截", AsrModelDownloader.verifyHash(f, null));
        assertTrue(AsrModelDownloader.verifyHash(f, ""));
    }

    @Test
    public void verifyHashFailsForMissingFile() throws Exception {
        File missing = new File(mTemp.getRoot(), "nope.bin");
        assertFalse(AsrModelDownloader.verifyHash(missing, sha256Hex(CONTENT)));
    }

    @Test
    public void hashComparisonIsCaseInsensitive() throws Exception {
        File f = writeFile("case.bin", CONTENT);
        assertTrue(AsrModelDownloader.verifyHash(f, sha256Hex(CONTENT).toUpperCase()));
    }

    @Test
    public void manifestExposesExpectedSizesForCompletenessCheck() {
        // isModelReady 依赖这张表做体积校验（此前只查存在性，截断文件会被判「就绪」）
        assertEquals(239_233_841L, AsrModelDownloader.expectedSizeOf("model.int8.onnx"));
        assertEquals(315_894L, AsrModelDownloader.expectedSizeOf("tokens.txt"));
        assertEquals(643_854L, AsrModelDownloader.expectedSizeOf("silero_vad.onnx"));
        assertEquals(0L, AsrModelDownloader.expectedSizeOf("unknown.file"));
        assertEquals(AsrModelDownloader.getTotalBytes(),
                239_233_841L + 315_894L + 643_854L);
    }

    private File writeFile(String name, String content) throws Exception {
        File f = new File(mTemp.getRoot(), name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    private static String sha256Hex(String s) throws Exception {
        java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = d.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
