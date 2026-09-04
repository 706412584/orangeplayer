package com.orange.playerlibrary.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * NoopTrackController 与 TrackInfo/TrackResult 单元测试。
 */
public class NoopTrackControllerTest {

    @Test
    public void 不支持轨道选择() {
        NoopTrackController controller = new NoopTrackController();
        assertFalse(controller.isTrackSelectionSupported());
    }

    @Test
    public void 枚举返回空列表() {
        List<TrackInfo> tracks = new NoopTrackController().getAvailableTracks();
        assertTrue(tracks.isEmpty());
    }

    @Test
    public void 选择与取消均返回引擎不支持() {
        NoopTrackController controller = new NoopTrackController();
        assertEquals(TrackResult.UNSUPPORTED_ENGINE, controller.selectTrack(0));
        assertEquals(TrackResult.UNSUPPORTED_ENGINE, controller.deselectTrack(0));
    }

    @Test
    public void TrackInfo字段与类型转换() {
        TrackInfo info = new TrackInfo(3, TrackInfo.TYPE_AUDIO, "zh", "中文音轨", true);
        assertEquals(3, info.getId());
        assertEquals(TrackInfo.TYPE_AUDIO, info.getType());
        assertEquals("zh", info.getLanguage());
        assertEquals("中文音轨", info.getLabel());
        assertTrue(info.isSelected());
        assertEquals("audio", TrackInfo.typeToString(TrackInfo.TYPE_AUDIO));
        assertEquals("video", TrackInfo.typeToString(TrackInfo.TYPE_VIDEO));
        assertEquals("text", TrackInfo.typeToString(TrackInfo.TYPE_TEXT));
        assertEquals("unknown", TrackInfo.typeToString(TrackInfo.TYPE_UNKNOWN));
    }
}
