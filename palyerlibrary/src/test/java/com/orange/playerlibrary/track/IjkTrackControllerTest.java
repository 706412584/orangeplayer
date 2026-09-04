package com.orange.playerlibrary.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shuyu.gsyvideoplayer.player.IjkPlayerManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo;

/**
 * IjkTrackController 单元测试（mock IjkPlayerManager 与 IjkTrackInfo）。
 */
@RunWith(MockitoJUnitRunner.class)
public class IjkTrackControllerTest {

    @Mock
    private IjkPlayerManager mockManager;

    @Mock
    private IjkMediaPlayer mockIjkPlayer;

    @Mock
    private IjkTrackInfo mockAudioTrack;

    @Mock
    private IjkTrackInfo mockTextTrack;

    private IjkTrackController controller;

    @Before
    public void setUp() {
        when(mockManager.getMediaPlayer()).thenReturn(mockIjkPlayer);
        when(mockAudioTrack.getTrackType()).thenReturn(tv.danmaku.ijk.media.player.misc.ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        when(mockAudioTrack.getLanguage()).thenReturn("chi");
        when(mockTextTrack.getTrackType()).thenReturn(tv.danmaku.ijk.media.player.misc.ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE);
        when(mockTextTrack.getLanguage()).thenReturn("eng");
        controller = new IjkTrackController(mockManager);
    }

    @Test
    public void 支持轨道选择() {
        assertTrue(controller.isTrackSelectionSupported());
    }

    @Test
    public void 播放器为null时枚举返回空列表() {
        when(mockManager.getMediaPlayer()).thenReturn(null);
        List<TrackInfo> tracks = controller.getAvailableTracks();
        assertTrue(tracks.isEmpty());
    }

    @Test
    public void 未就绪时选择返回NOT_READY() {
        when(mockManager.getMediaPlayer()).thenReturn(null);
        assertEquals(TrackResult.NOT_READY, controller.selectTrack(0));
    }

    @Test
    public void 无轨道信息时返回INVALID_ID() {
        when(mockManager.getTrackInfo()).thenReturn(null);
        assertEquals(TrackResult.INVALID_ID, controller.selectTrack(0));
        assertEquals(TrackResult.INVALID_ID, controller.selectTrack(-1));
    }

    @Test
    public void 有效id选择成功并转发到manager() {
        when(mockManager.getTrackInfo()).thenReturn(new IjkTrackInfo[]{mockAudioTrack, mockTextTrack});
        assertEquals(TrackResult.OK, controller.selectTrack(1));
        verify(mockManager).selectTrack(1);
    }

    @Test
    public void 引擎抛异常时返回ENGINE_ERROR() {
        when(mockManager.getTrackInfo()).thenReturn(new IjkTrackInfo[]{mockAudioTrack});
        org.mockito.Mockito.doThrow(new RuntimeException("engine down"))
                .when(mockManager).selectTrack(anyInt());
        assertEquals(TrackResult.ENGINE_ERROR, controller.selectTrack(0));
    }

    @Test
    public void 取消选择转发deselectTrack() {
        when(mockManager.getTrackInfo()).thenReturn(new IjkTrackInfo[]{mockAudioTrack});
        assertEquals(TrackResult.OK, controller.deselectTrack(0));
        verify(mockManager).deselectTrack(0);
    }

    @Test
    public void null轨道条目被跳过() {
        IjkTrackInfo nullTrack = null;
        when(mockManager.getTrackInfo()).thenReturn(new IjkTrackInfo[]{nullTrack, mockAudioTrack});
        List<TrackInfo> tracks = controller.getAvailableTracks();
        assertEquals(1, tracks.size());
        assertEquals(TrackInfo.TYPE_AUDIO, tracks.get(0).getType());
        assertEquals("chi", tracks.get(0).getLanguage());
    }

    @Test
    public void 未就绪时不触碰引擎() {
        when(mockManager.getMediaPlayer()).thenReturn(null);
        controller.deselectTrack(0);
        verify(mockManager, never()).deselectTrack(anyInt());
    }

    @Test
    public void ijk类型正确映射到统一类型() {
        when(mockManager.getTrackInfo()).thenReturn(new IjkTrackInfo[]{mockAudioTrack, mockTextTrack});
        List<TrackInfo> tracks = controller.getAvailableTracks();
        assertEquals(TrackInfo.TYPE_AUDIO, tracks.get(0).getType());
        assertEquals(TrackInfo.TYPE_TEXT, tracks.get(1).getType());
    }
}
