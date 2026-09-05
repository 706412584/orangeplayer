package com.orange.playerlibrary.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.orange.playerlibrary.PlayerConstants;
import com.orange.playerlibrary.utils.UrlProtocolClassifier.UrlInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 内核协议能力矩阵与 URL 分类器表驱动测试（P4）。
 */
public class EngineCapabilityMatrixTest {

    private static UrlInfo info(String url) {
        return UrlProtocolClassifier.parse(url);
    }

    // ===== UrlProtocolClassifier =====

    @Test
    public void 分类器_m3u8误伤查询串已修复() {
        // 查询串里的 .m3u8 不再误判（历史缺陷 M1）
        UrlInfo mp4WithQuery = info("https://example.com/video.mp4?refer=http://x/a.m3u8");
        assertEquals("mp4", mp4WithQuery.lastSegmentExt);
        assertFalse(mp4WithQuery.isHlsLike);

        UrlInfo realHls = info("https://example.com/master.m3u8?token=abc");
        assertEquals("m3u8", realHls.lastSegmentExt);
        assertTrue(realHls.isHlsLike);
    }

    @Test
    public void 分类器_回环代理识别() {
        assertTrue(info("http://127.0.0.1:10591/cleaned.m3u8").isLoopbackProxy);
        assertTrue(info("http://localhost:8080/proxy").isLoopbackProxy);
        assertFalse(info("https://cdn.example.com/video.m3u8").isLoopbackProxy);
    }

    @Test
    public void 分类器_本地与scheme() {
        UrlInfo local = info("/sdcard/movie.mp4");
        assertTrue(local.isLocalFile);
        assertEquals(null, local.scheme);

        UrlInfo file = info("file:///sdcard/movie.mkv");
        assertTrue(file.isLocalFile);
        assertEquals("file", file.scheme);

        assertEquals("content", info("content://media/external/video/1").scheme);
        assertEquals("udp", info("udp://239.1.1.1:5000").scheme);
    }

    @Test
    public void 分类器_大小写与空值() {
        assertEquals("m3u8", info("HTTPS://Example.COM/Video.M3U8").lastSegmentExt);
        assertEquals("", info("").lastSegmentExt);
        assertEquals("", info(null).lastSegmentExt);
    }

    // ===== 矩阵 supports（修正项回归） =====

    @Test
    public void udp与tcp仅IJK支持() {
        // 历史缺陷：旧代码推荐 EXO 播 udp/tcp
        for (String url : new String[]{"udp://239.1.1.1:5000", "tcp://192.168.1.1:8554"}) {
            UrlInfo i = info(url);
            assertFalse("EXO should not support " + url,
                    EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_EXO, i));
            assertTrue(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_IJK, i));
            assertFalse(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_ALI, i));
        }
    }

    @Test
    public void webrtc无引擎支持() {
        // 历史缺陷：旧代码推荐 EXO / 声称 ALI 支持
        UrlInfo i = info("webrtc://example.com/live");
        assertFalse(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_EXO, i));
        assertFalse(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_IJK, i));
        assertFalse(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_ALI, i));
        assertFalse(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_DEFAULT, i));
        assertTrue(EngineCapabilityMatrix.candidates(i).isEmpty());
    }

    @Test
    public void rtmp阿里云首推IJK备选() {
        UrlInfo i = info("rtmp://live.example.com/app/stream");
        List<String> c = EngineCapabilityMatrix.candidates(i);
        assertEquals(PlayerConstants.ENGINE_ALI, c.get(0));
        assertEquals(PlayerConstants.ENGINE_IJK, c.get(1));
        assertFalse(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_EXO, i));
    }

    @Test
    public void rtmp变体无引擎支持() {
        for (String url : new String[]{"rtmpt://x.com/a", "rtmpe://x.com/a"}) {
            assertTrue(EngineCapabilityMatrix.candidates(info(url)).isEmpty());
        }
    }

    @Test
    public void dash仅EXO() {
        // 历史缺陷：旧矩阵声称 ALI 支持 .mpd
        UrlInfo i = info("https://example.com/stream.mpd");
        assertEquals(Arrays.asList(PlayerConstants.ENGINE_EXO),
                EngineCapabilityMatrix.candidates(i));
        assertFalse(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_ALI, i));
    }

    @Test
    public void http点播候选链() {
        List<String> c = EngineCapabilityMatrix.candidates(info("https://example.com/v.mp4"));
        assertEquals(PlayerConstants.ENGINE_EXO, c.get(0));
        assertTrue(c.contains(PlayerConstants.ENGINE_IJK));
        assertTrue(c.contains(PlayerConstants.ENGINE_ALI));
    }

    @Test
    public void hls候选链与flv() {
        List<String> hls = EngineCapabilityMatrix.candidates(info("https://x.com/a.m3u8"));
        // 问题1修正：FFmpeg 可跳过坏 TS 段，自动回退应先到 IJK 再 ALI
        assertEquals(PlayerConstants.ENGINE_EXO, hls.get(0));
        assertEquals(PlayerConstants.ENGINE_IJK, hls.get(1));
        assertEquals(PlayerConstants.ENGINE_ALI, hls.get(2));

        List<String> flv = EngineCapabilityMatrix.candidates(info("https://x.com/live.flv"));
        assertEquals(PlayerConstants.ENGINE_ALI, flv.get(0));
        assertFalse(flv.contains(PlayerConstants.ENGINE_EXO));
    }

    @Test
    public void content与本地文件() {
        UrlInfo content = info("content://media/external/video/42");
        assertTrue(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_EXO, content));
        assertTrue(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_IJK, content));
        assertFalse(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_ALI, content));

        UrlInfo local = info("/sdcard/movie.mkv");
        assertTrue(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_EXO, local));
        assertTrue(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_IJK, local));
        assertFalse(EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_ALI, local));
    }

    @Test
    public void null与默认值安全() {
        assertFalse(EngineCapabilityMatrix.supports(null, info("http://x")));
        assertFalse(EngineCapabilityMatrix.supports("bad-engine", info("http://x")));
        assertTrue(EngineCapabilityMatrix.candidates(null).isEmpty());
    }

    // ===== PlayerEngineSelector 兼容封装 =====

    @Test
    public void selector兼容API() {
        // rtsp → EXO（历史行为保留）
        assertEquals(PlayerConstants.ENGINE_EXO,
                PlayerEngineSelector.selectEngine("rtsp://x.com/stream"));
        // rtmp → ALI（历史行为保留）
        assertEquals(PlayerConstants.ENGINE_ALI,
                PlayerEngineSelector.selectEngine("rtmp://x.com/a"));
        // udp → IJK（修正后行为，历史是 EXO）
        assertEquals(PlayerConstants.ENGINE_IJK,
                PlayerEngineSelector.selectEngine("udp://239.1.1.1:5000"));
        // webrtc → 无候选回退默认（历史是 EXO 假推荐）
        assertEquals(PlayerConstants.ENGINE_EXO,
                PlayerEngineSelector.selectEngine("webrtc://x.com/live"));
        // 查询串含 m3u8 的 mp4 → EXO（历史误判为 ALI）
        assertEquals(PlayerConstants.ENGINE_EXO,
                PlayerEngineSelector.selectEngine("https://x.com/v.mp4?ref=a.m3u8"));

        assertTrue(PlayerEngineSelector.isEngineSupported(
                "rtsp://x.com/s", PlayerConstants.ENGINE_EXO));
        assertFalse(PlayerEngineSelector.isEngineSupported(
                "udp://239.1.1.1:5000", PlayerConstants.ENGINE_EXO));
    }
}
