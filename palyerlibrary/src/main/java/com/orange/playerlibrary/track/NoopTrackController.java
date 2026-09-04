package com.orange.playerlibrary.track;

import java.util.Collections;
import java.util.List;

/**
 * 不支持轨道选择引擎（系统播放器/阿里云）的空实现。
 * 显式返回 UNSUPPORTED_ENGINE，禁止假成功。
 */
public class NoopTrackController implements TrackController {

    @Override
    public boolean isTrackSelectionSupported() {
        return false;
    }

    @Override
    public List<TrackInfo> getAvailableTracks() {
        return Collections.emptyList();
    }

    @Override
    public TrackResult selectTrack(int trackId) {
        return TrackResult.UNSUPPORTED_ENGINE;
    }

    @Override
    public TrackResult deselectTrack(int trackId) {
        return TrackResult.UNSUPPORTED_ENGINE;
    }
}
