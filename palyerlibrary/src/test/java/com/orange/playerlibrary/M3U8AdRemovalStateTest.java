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
        assertFalse(state.isBypassOnce());
    }

    @Test
    public void clear_递增请求token_用于作废过期回调() {
        int tokenBefore = state.getRequestToken();
        state.clear();
        assertEquals(tokenBefore + 1, state.getRequestToken());
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
