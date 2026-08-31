package com.orange.playerlibrary.torrent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * TorrentSupport URL 识别单元测试（纯 Java 逻辑，无 Android 依赖路径）。
 */
public class TorrentSupportTest {

    // --- isTorrentUrl ---

    @Test
    public void 磁力链接_识别为种子URL() {
        assertTrue(TorrentSupport.isTorrentUrl("magnet:?xt=urn:btih:abcdef123456"));
    }

    @Test
    public void 大写MAGNET_识别为种子URL() {
        assertTrue(TorrentSupport.isTorrentUrl("MAGNET:?xt=urn:btih:abcdef123456"));
    }

    @Test
    public void torrent协议前缀_识别为种子URL() {
        assertTrue(TorrentSupport.isTorrentUrl("torrent:http://example.com/file"));
    }

    @Test
    public void torrent文件后缀_识别为种子URL() {
        assertTrue(TorrentSupport.isTorrentUrl("/sdcard/Download/movie.torrent"));
    }

    @Test
    public void torrent后缀带查询参数_识别为种子URL() {
        assertTrue(TorrentSupport.isTorrentUrl("http://example.com/movie.torrent?track=abc"));
    }

    @Test
    public void 普通httpURL_不是种子URL() {
        assertFalse(TorrentSupport.isTorrentUrl("http://example.com/video.mp4"));
    }

    @Test
    public void m3u8URL_不是种子URL() {
        assertFalse(TorrentSupport.isTorrentUrl("http://example.com/video.m3u8"));
    }

    @Test
    public void nullURL_不是种子URL() {
        assertFalse(TorrentSupport.isTorrentUrl(null));
    }

    @Test
    public void 空字符串_不是种子URL() {
        assertFalse(TorrentSupport.isTorrentUrl(""));
    }

    // --- extractMagnetUrl ---

    @Test
    public void 纯磁力链接_原样返回() {
        String magnet = "magnet:?xt=urn:btih:abcdef123456";
        assertEquals(magnet, TorrentSupport.extractMagnetUrl(magnet));
    }

    @Test
    public void 带前缀污染的URL_提取磁力部分() {
        String polluted = "剪贴板垃圾内容 magnet:?xt=urn:btih:abcdef123456";
        assertEquals("magnet:?xt=urn:btih:abcdef123456",
                TorrentSupport.extractMagnetUrl(polluted));
    }

    @Test
    public void 非磁力URL_原样返回() {
        assertEquals("http://example.com/video.mp4",
                TorrentSupport.extractMagnetUrl("http://example.com/video.mp4"));
    }

    @Test
    public void nullURL_返回null() {
        assertNull(TorrentSupport.extractMagnetUrl(null));
    }
}
