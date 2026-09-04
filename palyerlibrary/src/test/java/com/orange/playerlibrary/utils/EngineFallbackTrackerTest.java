package com.orange.playerlibrary.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orange.playerlibrary.PlayerConstants;
import com.orange.playerlibrary.utils.EngineFallbackTracker.AvailabilityProbe;
import com.orange.playerlibrary.utils.EngineFallbackTracker.State;
import com.orange.playerlibrary.utils.UrlProtocolClassifier.UrlInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * 引擎自动回退状态机测试（P4）：K=1 有界、失败集去重、用户优先。
 */
@RunWith(MockitoJUnitRunner.class)
public class EngineFallbackTrackerTest {

    private EngineFallbackTracker tracker;
    private AvailabilityProbe allAvailable;

    @Before
    public void setUp() {
        tracker = new EngineFallbackTracker();
        allAvailable = mock(AvailabilityProbe.class);
        when(allAvailable.isEngineAvailable(anyString())).thenReturn(true);
    }

    private static UrlInfo info(String url) {
        return UrlProtocolClassifier.parse(url);
    }

    @Test
    public void 偏好引擎支持协议时直接ACTIVE() {
        String chosen = tracker.onUrlSet("https://x.com/v.mp4",
                PlayerConstants.ENGINE_EXO, info("https://x.com/v.mp4"), allAvailable);
        assertEquals(PlayerConstants.ENGINE_EXO, chosen);
        assertEquals(State.ACTIVE, tracker.getState());
        assertEquals(PlayerConstants.ENGINE_EXO, tracker.getPreferredEngine());
    }

    @Test
    public void 偏好引擎不支持协议时回退到候选链() {
        // 用户偏好 ALI 播 udp → ALI 不支持，回退 IJK
        String url = "udp://239.1.1.1:5000";
        String chosen = tracker.onUrlSet(url, PlayerConstants.ENGINE_ALI, info(url), allAvailable);
        assertEquals(PlayerConstants.ENGINE_IJK, chosen);
        assertEquals(State.FALLBACK, tracker.getState());
        // 偏好不被自动覆盖
        assertEquals(PlayerConstants.ENGINE_ALI, tracker.getPreferredEngine());
    }

    @Test
    public void 引擎不可用时跳过该候选() {
        AvailabilityProbe noIjk = mock(AvailabilityProbe.class);
        when(noIjk.isEngineAvailable(anyString())).thenReturn(true);
        when(noIjk.isEngineAvailable(PlayerConstants.ENGINE_IJK)).thenReturn(false);
        String url = "udp://239.1.1.1:5000";
        String chosen = tracker.onUrlSet(url, PlayerConstants.ENGINE_ALI, info(url), noIjk);
        // IJK 不可用且 udp 无其他候选 → 保留偏好由上层显式报错
        assertEquals(PlayerConstants.ENGINE_ALI, chosen);
        assertEquals(State.ACTIVE, tracker.getState());
    }

    @Test
    public void 播放失败自动换核一次后TERMINAL() {
        String url = "https://x.com/v.mp4";
        UrlInfo i = info(url);
        tracker.onUrlSet(url, PlayerConstants.ENGINE_EXO, i, allAvailable);

        // 第一次失败：自动切到 IJK（预算 K=1）
        String next = tracker.onEngineFailure(PlayerConstants.ENGINE_EXO, i, allAvailable);
        assertEquals(PlayerConstants.ENGINE_IJK, next);
        assertEquals(State.FALLBACK, tracker.getState());
        assertTrue(tracker.hasFailed(PlayerConstants.ENGINE_EXO));

        // 第二次失败：预算耗尽 → TERMINAL，不再自动重试
        String next2 = tracker.onEngineFailure(PlayerConstants.ENGINE_IJK, i, allAvailable);
        assertNull(next2);
        assertEquals(State.TERMINAL_ERROR, tracker.getState());
    }

    @Test
    public void 失败引擎不会被重复选中() {
        String url = "https://x.com/v.mp4";
        UrlInfo i = info(url);
        tracker.onUrlSet(url, PlayerConstants.ENGINE_EXO, i, allAvailable);
        tracker.onEngineFailure(PlayerConstants.ENGINE_EXO, i, allAvailable); // → IJK
        tracker.onEngineFailure(PlayerConstants.ENGINE_IJK, i, allAvailable); // → TERMINAL

        // 新 URL：EXO/IJK 已在会话失败集，ALI 成为直接选择
        String url2 = "https://y.com/w.mp4";
        String chosen = tracker.onUrlSet(url2, PlayerConstants.ENGINE_EXO, info(url2), allAvailable);
        assertEquals(PlayerConstants.ENGINE_ALI, chosen);
    }

    @Test
    public void PTS跳变一次性切EXO并计入预算() {
        String url = "https://x.com/live.m3u8";
        UrlInfo i = info(url);
        tracker.onUrlSet(url, PlayerConstants.ENGINE_IJK, i, allAvailable);

        String exo = tracker.onPtsJumpDetected(i, allAvailable);
        assertEquals(PlayerConstants.ENGINE_EXO, exo);

        // 预算已耗尽：再失败不自动换核
        assertNull(tracker.onEngineFailure(PlayerConstants.ENGINE_EXO, i, allAvailable));
        assertEquals(State.TERMINAL_ERROR, tracker.getState());
    }

    @Test
    public void 已在EXO时PTS跳变不动作() {
        String url = "https://x.com/live.m3u8";
        UrlInfo i = info(url);
        tracker.onUrlSet(url, PlayerConstants.ENGINE_EXO, i, allAvailable);
        assertNull(tracker.onPtsJumpDetected(i, allAvailable));
        assertEquals(State.ACTIVE, tracker.getState());
    }

    @Test
    public void PTS跳变对EXO不支持的协议不生效() {
        String url = "udp://239.1.1.1:5000";
        UrlInfo i = info(url);
        tracker.onUrlSet(url, PlayerConstants.ENGINE_IJK, i, allAvailable);
        assertNull(tracker.onPtsJumpDetected(i, allAvailable)); // EXO 不支持 udp
        assertEquals(State.ACTIVE, tracker.getState());
    }

    @Test
    public void 用户选择清空自动态并成为新偏好() {
        String url = "https://x.com/v.mp4";
        UrlInfo i = info(url);
        tracker.onUrlSet(url, PlayerConstants.ENGINE_EXO, i, allAvailable);
        tracker.onEngineFailure(PlayerConstants.ENGINE_EXO, i, allAvailable);
        assertTrue(tracker.hasFailed(PlayerConstants.ENGINE_EXO));

        tracker.onUserSelect(PlayerConstants.ENGINE_EXO);
        assertEquals(PlayerConstants.ENGINE_EXO, tracker.getPreferredEngine());
        assertEquals(State.ACTIVE, tracker.getState());
        assertFalse(tracker.hasFailed(PlayerConstants.ENGINE_EXO)); // 失败集被清
    }

    @Test
    public void 新会话全部复位() {
        tracker.onUrlSet("https://x.com/v.mp4", PlayerConstants.ENGINE_EXO,
                info("https://x.com/v.mp4"), allAvailable);
        tracker.onEngineFailure(PlayerConstants.ENGINE_EXO,
                info("https://x.com/v.mp4"), allAvailable);

        tracker.onNewSession();
        assertEquals(State.IDLE, tracker.getState());
        assertNull(tracker.getPreferredEngine());
        assertNull(tracker.getCurrentEngine());
        assertFalse(tracker.hasFailed(PlayerConstants.ENGINE_EXO));
    }
}
