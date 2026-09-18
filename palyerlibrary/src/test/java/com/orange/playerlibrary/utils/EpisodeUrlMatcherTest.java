package com.orange.playerlibrary.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 选集地址匹配器单测。
 *
 * 覆盖真机复现的回归场景：M3U8 去广告把实际播放地址换成回环代理地址后，
 * 选集列表按「当前播放地址」反查下标失败，点下一集误报「已经是最后一集了」。
 */
public class EpisodeUrlMatcherTest {

    private static final String ORIGIN =
            "https://v.cdnlz22.com/20240815/3725_2916c479/index.m3u8";
    private static final String PROXIED =
            "http://127.0.0.1:41167/cleaned/cf9e46088258afe374af6199a8b3fdf8.m3u8";

    // ---------- 核心回归：去广告改写地址后仍能匹配 ----------

    @Test
    public void 去广告改写地址后_用原始地址仍能匹配() {
        // 列表里存的是原始地址，实际在播的是回环代理地址
        assertTrue(EpisodeUrlMatcher.matches(ORIGIN, ORIGIN, PROXIED));
    }

    @Test
    public void 仅靠当前播放地址会失配_证明原实现确实失败() {
        // 复刻旧逻辑：只用 playingUrl 比对。这条断言把回归钉住，
        // 若将来有人把 matches 退化成只比 playingUrl，本测试会红。
        assertFalse(PROXIED.equals(ORIGIN));
        // 而 matches 通过 sourceUrl 兜住了这种情况
        assertTrue(EpisodeUrlMatcher.matches(ORIGIN, ORIGIN, PROXIED));
    }

    // ---------- 已下载视频被改写成 file:// ----------

    @Test
    public void 命中已下载视频被改写成file路径_仍能匹配() {
        String origin = "https://example.com/vod/ep01.mp4";
        String local = "file:///storage/emulated/0/Download/ep01.mp4";
        assertTrue(EpisodeUrlMatcher.matches(origin, origin, local));
    }

    // ---------- 未改写时保持原有行为 ----------

    @Test
    public void 地址未被改写时_正常匹配() {
        assertTrue(EpisodeUrlMatcher.matches(ORIGIN, ORIGIN, ORIGIN));
    }

    @Test
    public void 列表存的是改写后地址时_靠当前地址匹配() {
        assertTrue(EpisodeUrlMatcher.matches(PROXIED, null, PROXIED));
    }

    @Test
    public void sourceUrl为null时_不影响当前地址匹配() {
        assertTrue(EpisodeUrlMatcher.matches(ORIGIN, null, ORIGIN));
    }

    // ---------- 查询串回退 ----------

    @Test
    public void 源站改写查询串_去query后匹配() {
        String item = "http://39.134.66.108/live/index.m3u8";
        String playing = "http://39.134.66.108/live/index.m3u8?icpid=88888888&RTS=1750674609";
        assertTrue(EpisodeUrlMatcher.matches(item, null, playing));
    }

    @Test
    public void 去query匹配同样适用于原始地址() {
        String item = "http://a.com/x.m3u8?t=1";
        String source = "http://a.com/x.m3u8?t=2";
        assertTrue(EpisodeUrlMatcher.matches(item, source, "http://127.0.0.1:1/cleaned/h.m3u8"));
    }

    // ---------- 不能串集 ----------

    @Test
    public void 不同集不得匹配_前缀相同也不行() {
        // 1.mp4 是 10.mp4 的前缀，包含匹配会串集
        assertFalse(EpisodeUrlMatcher.matches("https://a.com/1.mp4", "https://a.com/10.mp4", null));
        assertFalse(EpisodeUrlMatcher.matches("https://a.com/10.mp4", "https://a.com/1.mp4", null));
    }

    @Test
    public void 不同集不得匹配_不同host() {
        assertFalse(EpisodeUrlMatcher.matches(
                "https://v.cdnlz22.com/share/aaa", "https://v.lzcdn26.com/share/aaa", null));
    }

    @Test
    public void 不同集不得匹配_同一host不同路径() {
        assertFalse(EpisodeUrlMatcher.matches(
                "https://v.cdnlz22.com/share/94ef7214c4a90790186e255304f8fd1f",
                "https://v.cdnlz22.com/share/9d752cb08ef466fc480fba981cfa44a1",
                null));
    }

    @Test
    public void 两条线路的同名集不得互相匹配() {
        // item.json 里 part0 与 part1 的「第01集」地址完全不同，不能串
        String liangzi = "https://v.cdnlz22.com/share/94ef7214c4a90790186e255304f8fd1f";
        String lzm3u8 = "https://v.cdnlz22.com/20240815/3725_2916c479/index.m3u8";
        assertFalse(EpisodeUrlMatcher.matches(liangzi, lzm3u8, lzm3u8));
    }

    // ---------- 边界 ----------

    @Test
    public void 空入参一律不匹配() {
        assertFalse(EpisodeUrlMatcher.matches(null, ORIGIN, PROXIED));
        assertFalse(EpisodeUrlMatcher.matches("", ORIGIN, PROXIED));
        assertFalse(EpisodeUrlMatcher.matches(ORIGIN, null, null));
    }

    @Test
    public void stripQuery_行为() {
        assertNull(EpisodeUrlMatcher.stripQuery(null));
        assertNull(EpisodeUrlMatcher.stripQuery(""));
        assertEquals("http://a.com/x", EpisodeUrlMatcher.stripQuery("http://a.com/x?a=1&b=2"));
        assertEquals("http://a.com/x", EpisodeUrlMatcher.stripQuery("http://a.com/x"));
        assertEquals("", EpisodeUrlMatcher.stripQuery("?onlyquery"));
    }
}
