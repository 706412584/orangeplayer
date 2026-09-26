package com.orange.downloader.utils;

import java.io.File;
import java.io.FileInputStream;

/**
 * TS 明文探测。
 *
 * <p>背景：有站点在 playlist 里声明 {@code #EXT-X-KEY:METHOD=AES-128}，实际分片却是
 * 明文（17c.tw 实测）。此时若照声明保留 EXT-X-KEY，ffmpeg 会把明文当密文解，
 * 得到垃圾数据，合并必然失败，而报错（"Error when loading first segment"）
 * 完全指不出真实原因。所以判定必须基于**实际字节**，而不是 playlist 的声明。
 */
public final class TsProbe {

    /** MPEG-TS 定长包大小 */
    public static final int TS_PACKET_SIZE = 188;

    /** TS 包同步字节 */
    public static final byte TS_SYNC_BYTE = 0x47;

    /** 默认抽查的包数 */
    private static final int DEFAULT_PACKETS_TO_CHECK = 20;

    /** 至少要读到这么多包才敢下结论 */
    private static final int MIN_PACKETS = 4;

    private TsProbe() {
    }

    /**
     * 判断文件是否为明文 TS。
     *
     * @return true 表示明文；文件不存在 / 太短 / 读取失败一律返回 false（保守：
     *         宁可按加密处理，也不要把加密流误判成明文）
     */
    public static boolean isPlainTsFile(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            return isPlainTsStream(in, DEFAULT_PACKETS_TO_CHECK);
        } catch (Exception e) {
            return false;
        } finally {
            VideoDownloadUtils.close(in);
        }
    }

    /**
     * 从流里顺序读若干 188 字节包，统计包首命中同步字节的比例。
     *
     * <p>明文 TS 应接近 100% 命中；密文的 0x47 只是随机出现，概率约 1/256，
     * 连续 20 个包几乎不可能过半命中。
     */
    public static boolean isPlainTsStream(java.io.InputStream in, int packetsToCheck)
            throws java.io.IOException {
        if (in == null) {
            return false;
        }
        byte[] buf = new byte[TS_PACKET_SIZE];
        int hits = 0;
        int checked = 0;
        while (checked < packetsToCheck) {
            int read = 0;
            while (read < TS_PACKET_SIZE) {
                int n = in.read(buf, read, TS_PACKET_SIZE - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            if (read < TS_PACKET_SIZE) {
                break;
            }
            checked++;
            if (buf[0] == TS_SYNC_BYTE) {
                hits++;
            }
        }
        return checked >= MIN_PACKETS && hits * 2 >= checked;
    }

    /**
     * 字节数组版本（便于测试与内存数据判定）。
     */
    public static boolean isPlainTsBytes(byte[] data, int packetsToCheck) {
        if (data == null) {
            return false;
        }
        int hits = 0;
        int checked = 0;
        for (int i = 0; i < packetsToCheck; i++) {
            int off = i * TS_PACKET_SIZE;
            if (off + TS_PACKET_SIZE > data.length) {
                break;
            }
            checked++;
            if (data[off] == TS_SYNC_BYTE) {
                hits++;
            }
        }
        return checked >= MIN_PACKETS && hits * 2 >= checked;
    }
}
