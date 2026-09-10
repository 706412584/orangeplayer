package com.orange.playerlibrary.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * HlsPlaylistRewriter 单测：重点覆盖相对 URI 解析与 tag 内 URI 改写
 * （这是 HLS 走本地代理最容易出错的地方）。
 */
public class HlsPlaylistRewriterTest {

    /** 记录映射输入，便于断言解析出的绝对 URL */
    private static final class Recorder implements HlsPlaylistRewriter.UrlMapper {
        final java.util.List<String> seen = new java.util.ArrayList<>();

        @Override
        public String map(String absoluteUrl) {
            seen.add(absoluteUrl);
            return "PROXY(" + absoluteUrl + ")";
        }
    }

    @Test
    public void relativeSegmentResolvedAgainstPlaylistUrl() {
        Recorder seg = new Recorder();
        Recorder pl = new Recorder();
        String content = "#EXTM3U\n#EXTINF:4.0,\nseg1.ts\n#EXTINF:4.0,\nsub/seg2.ts\n";
        String out = HlsPlaylistRewriter.rewrite(content,
                "https://cdn.test/hls/index.m3u8", false, pl, seg);

        assertEquals("https://cdn.test/hls/seg1.ts", seg.seen.get(0));
        assertEquals("https://cdn.test/hls/sub/seg2.ts", seg.seen.get(1));
        assertTrue(out.contains("PROXY(https://cdn.test/hls/seg1.ts)"));
    }

    @Test
    public void absolutePathResolvedAgainstHost() {
        Recorder seg = new Recorder();
        String out = HlsPlaylistRewriter.rewrite(
                "#EXTM3U\n#EXTINF:4.0,\n/other/seg.ts\n",
                "https://cdn.test/a/b/index.m3u8", false, new Recorder(), seg);
        assertEquals("https://cdn.test/other/seg.ts", seg.seen.get(0));
    }

    @Test
    public void protocolRelativeResolvedToHttps() {
        Recorder seg = new Recorder();
        HlsPlaylistRewriter.rewrite(
                "#EXTM3U\n#EXTINF:4.0,\n//cdn2.test/seg.ts\n",
                "https://cdn.test/index.m3u8", false, new Recorder(), seg);
        assertEquals("https://cdn2.test/seg.ts", seg.seen.get(0));
    }

    @Test
    public void queryStringPreserved() {
        Recorder seg = new Recorder();
        HlsPlaylistRewriter.rewrite(
                "#EXTM3U\n#EXTINF:4.0,\nseg.ts?token=abc&x=1\n",
                "https://cdn.test/index.m3u8?auth=1", false, new Recorder(), seg);
        assertEquals("https://cdn.test/seg.ts?token=abc&x=1", seg.seen.get(0));
    }

    @Test
    public void absoluteSegmentLeftAsIs() {
        Recorder seg = new Recorder();
        HlsPlaylistRewriter.rewrite(
                "#EXTM3U\n#EXTINF:4.0,\nhttps://other.test/seg.ts\n",
                "https://cdn.test/index.m3u8", false, new Recorder(), seg);
        assertEquals("https://other.test/seg.ts", seg.seen.get(0));
    }

    @Test
    public void masterVariantsGoToPlaylistMapper() {
        Recorder seg = new Recorder();
        Recorder pl = new Recorder();
        String content = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n"
                + "v360/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2000000\n"
                + "https://cdn2.test/v720.m3u8\n";
        assertTrue(HlsPlaylistRewriter.isMasterPlaylist(content));

        HlsPlaylistRewriter.rewrite(content, "https://cdn.test/hls/master.m3u8", true, pl, seg);

        assertEquals(java.util.Arrays.asList(
                "https://cdn.test/hls/v360/index.m3u8",
                "https://cdn2.test/v720.m3u8"), pl.seen);
        assertTrue("master 不应触碰分片 mapper", seg.seen.isEmpty());
    }

    @Test
    public void keyAndMapUriRewrittenToSegmentMapper() {
        Recorder seg = new Recorder();
        String content = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n"
                + "#EXTINF:4.0,\nseg.m4s\n";
        String out = HlsPlaylistRewriter.rewrite(content,
                "https://cdn.test/hls/index.m3u8", false, new Recorder(), seg);

        assertEquals("https://cdn.test/hls/key.bin", seg.seen.get(0));
        assertEquals("https://cdn.test/hls/init.mp4", seg.seen.get(1));
        assertEquals("https://cdn.test/hls/seg.m4s", seg.seen.get(2));
        assertTrue(out.contains("URI=\"PROXY(https://cdn.test/hls/key.bin)\""));
        // 非 URI 属性必须原样保留
        assertTrue(out.contains("METHOD=AES-128"));
        assertTrue(out.contains("IV=0x1"));
    }

    @Test
    public void mediaAndIframeUriRewrittenToPlaylistMapper() {
        Recorder seg = new Recorder();
        Recorder pl = new Recorder();
        String content = "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"audio/index.m3u8\"\n"
                + "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100000,URI=\"iframe.m3u8\"\n";
        HlsPlaylistRewriter.rewrite(content, "https://cdn.test/hls/master.m3u8", true, pl, seg);

        assertEquals(java.util.Arrays.asList(
                "https://cdn.test/hls/audio/index.m3u8",
                "https://cdn.test/hls/iframe.m3u8"), pl.seen);
    }

    @Test
    public void unknownTagsAndEndListPreserved() {
        Recorder seg = new Recorder();
        String content = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n"
                + "#EXT-X-DISCONTINUITY\n#EXTINF:4.0,\nseg.ts\n#EXT-X-ENDLIST\n";
        String out = HlsPlaylistRewriter.rewrite(content,
                "https://cdn.test/index.m3u8", false, new Recorder(), seg);

        assertTrue(out.contains("#EXT-X-VERSION:3"));
        assertTrue(out.contains("#EXT-X-TARGETDURATION:4"));
        assertTrue(out.contains("#EXT-X-DISCONTINUITY"));
        assertTrue(out.contains("#EXT-X-ENDLIST"));
        assertTrue(out.startsWith("#EXTM3U\n"));
    }

    @Test
    public void livePlaylistHasNoEndListStillRewritten() {
        Recorder seg = new Recorder();
        String out = HlsPlaylistRewriter.rewrite("#EXTM3U\n#EXTINF:4.0,\nlive1.ts\n",
                "https://cdn.test/live/index.m3u8", false, new Recorder(), seg);
        assertEquals(1, seg.seen.size());
        assertTrue(out.contains("PROXY(https://cdn.test/live/live1.ts)"));
        assertFalse(HlsPlaylistRewriter.isMasterPlaylist(out));
    }

    @Test
    public void emptyContentReturnsNull() {
        assertNull(HlsPlaylistRewriter.rewrite("", "https://a/b.m3u8", false,
                new Recorder(), new Recorder()));
        assertNull(HlsPlaylistRewriter.rewrite(null, "https://a/b.m3u8", false,
                new Recorder(), new Recorder()));
    }

    @Test
    public void crlfLineEndingsHandled() {
        Recorder seg = new Recorder();
        HlsPlaylistRewriter.rewrite("#EXTM3U\r\n#EXTINF:4.0,\r\nseg.ts\r\n",
                "https://cdn.test/index.m3u8", false, new Recorder(), seg);
        assertEquals("https://cdn.test/seg.ts", seg.seen.get(0));
    }

    @Test
    public void invalidUriFallsBackToPathConcat() {
        // 含空格等 URI 非法字符时应兜底而非抛异常
        String abs = HlsPlaylistRewriter.toAbsoluteUrl(
                "https://cdn.test/a/b/index.m3u8", "se g 1.ts");
        assertEquals("https://cdn.test/a/b/se g 1.ts", abs);
    }

    @Test
    public void baseUrlWithoutTrailingSlashResolvesSibling() {
        assertEquals("https://cdn.test/a/index.m3u8",
                HlsPlaylistRewriter.toAbsoluteUrl("https://cdn.test/a/master.m3u8", "index.m3u8"));
    }
}
