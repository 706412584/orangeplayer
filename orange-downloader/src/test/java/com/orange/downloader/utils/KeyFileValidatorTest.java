package com.orange.downloader.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * AES-128 密钥文件校验单元测试。
 *
 * <p>回归背景：早期实现直接写最终路径，网络中断留下半截文件；而续传判据是
 * {@code keyFile.exists()} → 半截文件被永久当成「已下载」，任务报 SUCCESS 但
 * 产物用错 key 解出垃圾，且无任何报错。本类把「存在 ≠ 可用」这条钉死。
 */
public class KeyFileValidatorTest {

    private File mDir;

    @Before
    public void setUp() throws Exception {
        mDir = Files.createTempDirectory("key-val-test").toFile();
    }

    private File writeKey(String name, int bytes) throws Exception {
        File f = new File(mDir, name);
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(new byte[bytes]);
        } finally {
            out.close();
        }
        return f;
    }

    @Test
    public void 长度16字节的key_可用() throws Exception {
        File f = writeKey("good.key", 16);
        assertTrue(KeyFileValidator.isUsable(f));
        assertTrue("可用文件不应被删除", f.exists());
    }

    /** 回归：半截文件必须判为不可用，且被删除（否则会被 exists() 永久复用）。 */
    @Test
    public void 半截key_不可用且被删除() throws Exception {
        File f = writeKey("partial.key", 7);
        assertFalse("半截 key 不可用", KeyFileValidator.isUsable(f));
        assertFalse("半截 key 必须被删除，否则永远不会重下", f.exists());
    }

    @Test
    public void 空文件_不可用且被删除() throws Exception {
        File f = writeKey("empty.key", 0);
        assertFalse(KeyFileValidator.isUsable(f));
        assertFalse(f.exists());
    }

    /** 长度超出（如把 403 页面存了下来）同样不可用。 */
    @Test
    public void 超长文件_不可用且被删除() throws Exception {
        File f = writeKey("toolong.key", 512);
        assertFalse(KeyFileValidator.isUsable(f));
        assertFalse(f.exists());
    }

    @Test
    public void 不存在的文件_不可用() {
        File f = new File(mDir, "nope.key");
        assertFalse(KeyFileValidator.isUsable(f));
    }

    @Test
    public void null_不抛异常() {
        assertFalse(KeyFileValidator.isUsable(null));
    }

    @Test
    public void 长度判定() {
        assertTrue(KeyFileValidator.isExpectedKeyLength(16));
        assertFalse(KeyFileValidator.isExpectedKeyLength(0));
        assertFalse(KeyFileValidator.isExpectedKeyLength(15));
        assertFalse(KeyFileValidator.isExpectedKeyLength(17));
        assertFalse(KeyFileValidator.isExpectedKeyLength(-1));
        assertFalse(KeyFileValidator.isExpectedKeyLength(1024));
    }

    @Test
    public void 常量值为16() {
        assertEquals(16L, KeyFileValidator.AES_128_KEY_SIZE);
    }

    /** 删除后再判仍为不可用（幂等，重复调用不抛异常）。 */
    @Test
    public void 重复判定幂等() throws Exception {
        File f = writeKey("dup.key", 3);
        assertFalse(KeyFileValidator.isUsable(f));
        assertFalse(KeyFileValidator.isUsable(f));
        assertFalse(KeyFileValidator.isUsable(f));
    }
}
