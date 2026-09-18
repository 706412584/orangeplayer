package com.orange.playerlibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * M3U8AdRemovalState 单元测试。
 */
public class M3U8AdRemovalStateTest {

    private M3U8AdRemovalState state;

    @Before
    public void setUp() {
        state = new M3U8AdRemovalState();
    }

    @Test
    public void 初始状态_所有标记为空或false() {
        assertNull(state.getOriginalUrl());
        assertNull(state.getOriginalHeaders());
        assertEquals("", state.getOriginalTitle());
        assertTrue(state.isOriginalCacheWithPlay());
        assertFalse(state.isPlayingAdRemoved());
        assertFalse(state.hasRetriedOriginalUrl());
        assertFalse(state.isPendingAdRemoval());
        assertFalse(state.isPendingDiscontinuityCheck());
        assertNull(state.getDiscontinuityCheckedUrl());
        assertFalse(state.isBypassOnce());
        assertFalse(state.isSkipEngineRestore());
        assertNull(state.getUserPreferredEngine());
        assertEquals(0, state.getRequestToken());
    }

    @Test
    public void clear_重置所有去广告状态() {
        state.setOriginalUrl("http://example.com/video.m3u8");
        state.setOriginalTitle("标题");
        state.setOriginalCacheWithPlay(false);
        state.setPlayingAdRemoved(true);
        state.setHasRetriedOriginalUrl(true);
        state.setPendingAdRemoval(true);
        state.setBypassOnce(true);

        state.clear();

        assertNull(state.getOriginalUrl());
        assertEquals("", state.getOriginalTitle());
        assertTrue(state.isOriginalCacheWithPlay());
        assertFalse(state.isPlayingAdRemoved());
        assertFalse(state.hasRetriedOriginalUrl());
        assertFalse(state.isPendingAdRemoval());
        assertFalse(state.isPendingDiscontinuityCheck());
        assertNull(state.getDiscontinuityCheckedUrl());
        assertFalse(state.isBypassOnce());
    }

    /**
     * 回归：clear() 不得清掉 mSourceUrl。
     *
     * clear() 在每次 setUp 开头都会执行。去广告绑定与引擎切换等内部路径会用
     * 回环代理地址/本地路径重新 setUp，此时 clear() 先清空、而 rememberSourceUrl
     * 又因地址是回环而拒绝写回 → 源地址永久丢失 → 选集索引反查恒为 -1，
     * 表现为「点下一集提示已经是最后一集了」。
     */
    @Test
    public void clear_不清除sourceUrl_否则选集索引反查会失效() {
        String source = "https://v.example.com/20240815/3725/index.m3u8";
        state.setSourceUrl(source);

        state.clear();

        assertEquals("clear() 必须保留调用方原始播放源", source, state.getSourceUrl());
    }

    @Test
    public void cancelPendingRequests_不清除sourceUrl() {
        String source = "https://v.example.com/a/index.m3u8";
        state.setSourceUrl(source);

        state.cancelPendingRequests();

        assertEquals(source, state.getSourceUrl());
    }

    @Test
    public void sourceUrl_可被后续setUp覆盖() {
        state.setSourceUrl("https://a.com/1.m3u8");
        state.setSourceUrl("https://a.com/2.m3u8");
        assertEquals("https://a.com/2.m3u8", state.getSourceUrl());
    }

    @Test
    public void clear_递增请求token_用于作废过期回调() {
        int tokenBefore = state.getRequestToken();
        state.clear();
        assertEquals(tokenBefore + 1, state.getRequestToken());
    }

    @Test
    public void cancelPendingRequests_作废回调但保留原始源() {
        state.setOriginalUrl("https://example.com/live.m3u8");
        state.setPendingAdRemoval(true);
        state.setPendingDiscontinuityCheck(true);
        int tokenBefore = state.getRequestToken();

        state.cancelPendingRequests();

        assertEquals(tokenBefore + 1, state.getRequestToken());
        assertFalse(state.isPendingAdRemoval());
        assertFalse(state.isPendingDiscontinuityCheck());
        assertEquals("https://example.com/live.m3u8", state.getOriginalUrl());
    }

    @Test
    public void nextToken_递增并返回新token() {
        assertEquals(1, state.nextToken());
        assertEquals(2, state.nextToken());
        assertEquals(2, state.getRequestToken());
    }

    @Test
    public void clear_不重置内核恢复相关字段() {
        // clear 只负责去广告链路状态，内核偏好由调用方按需管理
        state.setUserPreferredEngine(PlayerConstants.ENGINE_IJK);
        state.setSkipEngineRestore(true);

        state.clear();

        assertEquals(PlayerConstants.ENGINE_IJK, state.getUserPreferredEngine());
        assertTrue(state.isSkipEngineRestore());
    }

    @Test
    public void discontinuity检查状态可保存并清除() {
        state.setPendingDiscontinuityCheck(true);
        state.setDiscontinuityCheckedUrl("https://example.com/live.m3u8");
        assertTrue(state.isPendingDiscontinuityCheck());
        assertEquals("https://example.com/live.m3u8", state.getDiscontinuityCheckedUrl());

        state.clear();
        assertFalse(state.isPendingDiscontinuityCheck());
        assertNull(state.getDiscontinuityCheckedUrl());
    }

    @Test
    public void setOriginalTitle_null转为空串() {
        state.setOriginalTitle(null);
        assertEquals("", state.getOriginalTitle());
    }

    @Test
    public void originalHeaders_可保存与读取() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "http://example.com");
        state.setOriginalHeaders(headers);
        assertEquals(headers, state.getOriginalHeaders());
    }
}
