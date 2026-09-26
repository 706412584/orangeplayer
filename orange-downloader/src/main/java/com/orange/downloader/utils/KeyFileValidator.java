package com.orange.downloader.utils;

import java.io.File;

/**
 * AES-128 密钥文件校验（纯函数）。
 *
 * <p>为什么需要：密钥文件是**下载产物的一部分**，而「文件存在」不等于「文件可用」。
 * 早期实现直接写最终路径，网络中断会留下半截文件；而续传/重下的判据是
 * {@code keyFile.exists()} → 半截文件被永久当成「已下载」，任务报 SUCCESS，
 * 但 ffmpeg 用错 key 解出垃圾 —— 表现为「分片齐全但解不开」，且没有任何报错。
 *
 * <p>AES-128 的密钥固定 16 字节。长度不符说明拿到的不是密钥
 * （403 页面、占位内容、被截断），必须当失败处理。
 */
public final class KeyFileValidator {

    /** AES-128 密钥长度（字节） */
    public static final long AES_128_KEY_SIZE = 16L;

    private KeyFileValidator() {
    }

    /**
     * 密钥文件是否可用：存在且长度正确。
     *
     * <p>长度不符的文件会被**删除**——否则它会一直被 {@code exists()} 判据
     * 当成有效产物，永远不再重下。
     *
     * @return true 表示可用；false 表示不存在或长度异常（已删除）
     */
    public static boolean isUsable(File keyFile) {
        if (keyFile == null || !keyFile.exists()) {
            return false;
        }
        if (keyFile.length() == AES_128_KEY_SIZE) {
            return true;
        }
        keyFile.delete();
        return false;
    }

    /**
     * 下载到的字节数是否为合法的 AES-128 密钥长度。
     */
    public static boolean isExpectedKeyLength(long bytes) {
        return bytes == AES_128_KEY_SIZE;
    }
}
