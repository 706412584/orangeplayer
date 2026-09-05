package com.orange.playerlibrary.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.orange.playerlibrary.utils.BadSegmentSkipper.Segment;
import com.orange.playerlibrary.utils.BadSegmentSkipper.SkipResult;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 坏段跳段自愈纯逻辑测试（问题1：Exo 坏 TS 不跳过）。
 */
public class BadSegmentSkipperTest {

    // localhost 恒可解析（hosts 文件），不依赖外网；BAD_HOST 为真实 NXDOMAIN 域名
    private static final String OK_HOST = "localhost";
    private static final String BAD_HOST = "v.lzcdn1.com"; // 真实 NXDOMAIN 域名
    private static final String M3U8_URL = "http://by.example.com/f/123.m3u8";

    /** 复刻用户坏源结构：前6段好域名、第7-12段坏域名、之后好域名 */
    private static String buildPlaylist() {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        double[] durations = {4.0, 4.6, 3.36, 4.0, 4.56, 3.84,
                4.28, 3.36, 5.64, 4.0, 2.32, 4.32};
        for (int i = 0; i < durations.length; i++) {
            String host = (i >= 6 && i <= 11) ? BAD_HOST : OK_HOST;
            sb.append("#EXTINF:").append(String.format("%.3f", durations[i])).append(",\n");
            sb.append("https://").append(host).append("/hls/seg").append(i).append(".ts\n");
        }
        for (int i = 12; i < 20; i++) {
            sb.append("#EXTINF:4.000,\n");
            sb.append("https://").append(OK_HOST).append("/hls/seg").append(i).append(".ts\n");
        }
        return sb.toString();
    }

    @Test
    public void 解析_段累计时间正确() {
        List<Segment> segments = BadSegmentSkipper.parseSegments(buildPlaylist(), M3U8_URL);
        assertEquals(20, segments.size());
        // 前6段累计 24.36s = 24360ms，第7段(索引6)起始
        assertEquals(0, segments.get(0).startTimeMs);
        assertEquals(24360, segments.get(6).startTimeMs);
        assertEquals(4000, segments.get(0).durationMs);
        assertEquals("https://" + BAD_HOST + "/hls/seg6.ts", segments.get(6).url);
        assertEquals(BAD_HOST, segments.get(6).host);
        assertEquals(OK_HOST, segments.get(0).host);
    }

    @Test
    public void 解析_相对路径转绝对() {
        String content = "#EXTM3U\n#EXTINF:2.0,\nseg0.ts\n";
        List<Segment> segments = BadSegmentSkipper.parseSegments(content, "http://a.com/path/index.m3u8");
        assertEquals(1, segments.size());
        assertEquals("http://a.com/path/seg0.ts", segments.get(0).url);
        assertEquals("a.com", segments.get(0).host);
    }

    @Test
    public void 解析_空与master返回空() {
        assertTrue(BadSegmentSkipper.parseSegments(null, M3U8_URL).isEmpty());
        assertTrue(BadSegmentSkipper.parseSegments("", M3U8_URL).isEmpty());
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\n720p.m3u8\n";
        // master playlist 的变体行无前置 EXTINF，解析为 0 时段——返回 1 段但时长 0；
        // 调用方按时间定位时不会命中跳段（fromPosition 0 段起始也是 0）
        List<Segment> segments = BadSegmentSkipper.parseSegments(master, "http://a.com/index.m3u8");
        assertEquals(1, segments.size());
        assertEquals(0, segments.get(0).durationMs);
    }

    @Test
    public void 跳段_坏域名段被跳过_定位健康段起点() {
        List<Segment> segments = BadSegmentSkipper.parseSegments(buildPlaylist(), M3U8_URL);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 出错位置 25s（第7段刚起播即 DNS 失败）
            SkipResult result = BadSegmentSkipper.findNextHealthySegment(segments, 25000, executor);
            assertTrue(result.success);
            // 第13段（索引12）起始 = 前12段累计 24.36+23.92=48.28s
            assertEquals(48280, result.seekPositionMs);
            assertEquals(5, result.skippedSegments);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void 跳段_全健康时不跳() {
        String content = "#EXTM3U\n";
        for (int i = 0; i < 5; i++) {
            content += "#EXTINF:4.0,\nhttps://" + OK_HOST + "/s" + i + ".ts\n";
        }
        List<Segment> segments = BadSegmentSkipper.parseSegments(content, M3U8_URL);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            SkipResult result = BadSegmentSkipper.findNextHealthySegment(segments, 5000, executor);
            assertFalse(result.success); // 段都健康 → 不是段级问题 → 不跳
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void 跳段_出错位置在段中间() {
        List<Segment> segments = BadSegmentSkipper.parseSegments(buildPlaylist(), M3U8_URL);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 出错位置 35s（第9段中间），从该段起找健康段
            SkipResult result = BadSegmentSkipper.findNextHealthySegment(segments, 35000, executor);
            assertTrue(result.success);
            assertEquals(48280, result.seekPositionMs);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void 工具_提取host与baseUrl() {
        assertEquals("a.com", BadSegmentSkipper.extractHost("https://a.com:8080/x/y.ts"));
        assertNull(BadSegmentSkipper.extractHost("not-a-url"));
        assertEquals("http://a.com/path/",
                BadSegmentSkipper.extractBaseUrlPath("http://a.com/path/index.m3u8"));
    }

    @Test
    public void 工具_绝对路径段转绝对URL() {
        assertEquals("https://cdn.com/abs.ts",
                BadSegmentSkipper.toAbsoluteUrl("https://cdn.com/abs.ts", "http://x.com/"));
        assertEquals("http://a.com/root.ts",
                BadSegmentSkipper.toAbsoluteUrl("/root.ts", "http://a.com/path/index.m3u8"));
        assertEquals("http://a.com/path/rel.ts",
                BadSegmentSkipper.toAbsoluteUrl("rel.ts", "http://a.com/path/"));
    }

    @Test
    public void 工具_EXTINF解析容错() {
        assertEquals(4.28, BadSegmentSkipper.parseExtinfDuration("#EXTINF:4.280,"), 0.001);
        assertEquals(4.0, BadSegmentSkipper.parseExtinfDuration("#EXTINF:4.000"), 0.001);
        assertEquals(0.0, BadSegmentSkipper.parseExtinfDuration("#EXTINF:abc,"), 0.001);
    }

    @Test
    public void 真实场景_NXDOMAIN域名判定为坏() {
        // 真机复现的核心：v.lzcdn1.com 全球 NXDOMAIN
        assertFalse(BadSegmentSkipper.isHostHealthy("v.lzcdn1.com"));
        // localhost 恒可解析（不依赖外网的断言）
        assertTrue(BadSegmentSkipper.isHostHealthy("localhost"));
        assertNotNull(BadSegmentSkipper.isHostHealthy("localhost"));
    }
}
