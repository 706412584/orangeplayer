package com.orange.downloader.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * TS 明文探测单元测试。
 *
 * <p>回归背景（2026-09-19，17c.tw 实测）：站点在 playlist 里声明
 * {@code #EXT-X-KEY:METHOD=AES-128}，但分片本身是明文 TS。
 * 照声明保留 EXT-X-KEY 会让 ffmpeg 把明文当密文解，合并必然失败。
 * 所以判定必须基于实际字节。
 */
public class TsProbeTest {

    private static final int PACKET = TsProbe.TS_PACKET_SIZE;

    /** 构造 n 个包的明文 TS：每包首字节 0x47，其余填充 */
    private static byte[] plainTs(int packets) {
        byte[] d = new byte[packets * PACKET];
        for (int i = 0; i < packets; i++) {
            d[i * PACKET] = 0x47;
            for (int j = 1; j < PACKET; j++) {
                d[i * PACKET + j] = (byte) (i + j);
            }
        }
        return d;
    }

    /** 构造加密样式的数据：0x47 只是随机出现，188 对齐处不应命中 */
    private static byte[] encryptedLike(int packets, long seed) {
        byte[] d = new byte[packets * PACKET];
        new Random(seed).nextBytes(d);
        // 确保 188 对齐处不是 0x47，模拟密文
        for (int i = 0; i < packets; i++) {
            if (d[i * PACKET] == 0x47) {
                d[i * PACKET] = 0x21;
            }
        }
        return d;
    }

    @Test
    public void 明文TS_应判定为明文() {
        assertTrue(TsProbe.isPlainTsBytes(plainTs(20), 20));
    }

    @Test
    public void 密文样式数据_不应判定为明文() {
        for (long seed = 1; seed <= 20; seed++) {
            assertFalse("seed=" + seed, TsProbe.isPlainTsBytes(encryptedLike(20, seed), 20));
        }
    }

    @Test
    public void 数据过短_保守判定为非明文() {
        // 只有 2 个包，不足以判断，应保守返回 false
        assertFalse(TsProbe.isPlainTsBytes(plainTs(2), 20));
    }

    @Test
    public void 空数据与null_不应判定为明文() {
        assertFalse(TsProbe.isPlainTsBytes(new byte[0], 20));
        assertFalse(TsProbe.isPlainTsBytes(null, 20));
    }

    @Test
    public void 允许少量包首错位_仍判明文() {
        // 现实里偶发坏包，不应因一两个包就翻案（命中率过半即可）
        byte[] d = plainTs(20);
        d[0] = 0x00;
        d[PACKET] = 0x00;
        assertTrue(TsProbe.isPlainTsBytes(d, 20));
    }

    @Test
    public void 明文占比不足一半_判为非明文() {
        byte[] d = plainTs(20);
        // 破坏 11/20 个包的同步字节 → 命中 9/20，低于半数
        for (int i = 0; i < 11; i++) {
            d[i * PACKET] = 0x00;
        }
        assertFalse(TsProbe.isPlainTsBytes(d, 20));
    }

    @Test
    public void 不存在的文件_返回false() {
        assertFalse(TsProbe.isPlainTsFile(new java.io.File("/definitely/not/here.ts")));
        assertFalse(TsProbe.isPlainTsFile(null));
    }
}
