package com.orange.playerlibrary.track;

import com.shuyu.gsyvideoplayer.player.IjkPlayerManager;

import java.util.ArrayList;
import java.util.List;

import tv.danmaku.ijk.media.player.misc.ITrackInfo;

/**
 * IJK 引擎的轨道控制适配器。
 * 直接转发 IjkPlayerManager 已有的 getTrackInfo/selectTrack/deselectTrack。
 */
public class IjkTrackController implements TrackController {

    private final IjkPlayerManager manager;

    public IjkTrackController(IjkPlayerManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean isTrackSelectionSupported() {
        return true;
    }

    @Override
    public List<TrackInfo> getAvailableTracks() {
        if (manager == null || manager.getMediaPlayer() == null) {
            return new ArrayList<>();
        }
        ITrackInfo[] trackInfos = manager.getTrackInfo();
        List<TrackInfo> result = new ArrayList<>();
        if (trackInfos == null) {
            return result;
        }
        for (int i = 0; i < trackInfos.length; i++) {
            ITrackInfo info = trackInfos[i];
            if (info == null) {
                continue;
            }
            String label = null;
            try {
                label = info.getInfoInline();
            } catch (Throwable ignored) {
            }
            boolean selected = false;
            try {
                selected = manager.getSelectedTrack(info.getTrackType()) == i;
            } catch (Throwable ignored) {
            }
            result.add(new TrackInfo(i, ijkTypeToType(info.getTrackType()),
                    info.getLanguage(), label, selected));
        }
        return result;
    }

    @Override
    public TrackResult selectTrack(int trackId) {
        if (manager == null || manager.getMediaPlayer() == null) {
            return TrackResult.NOT_READY;
        }
        ITrackInfo[] trackInfos = manager.getTrackInfo();
        if (trackInfos == null || trackId < 0 || trackId >= trackInfos.length) {
            return TrackResult.INVALID_ID;
        }
        try {
            manager.selectTrack(trackId);
            return TrackResult.OK;
        } catch (Throwable t) {
            return TrackResult.ENGINE_ERROR;
        }
    }

    @Override
    public TrackResult deselectTrack(int trackId) {
        if (manager == null || manager.getMediaPlayer() == null) {
            return TrackResult.NOT_READY;
        }
        ITrackInfo[] trackInfos = manager.getTrackInfo();
        if (trackInfos == null || trackId < 0 || trackId >= trackInfos.length) {
            return TrackResult.INVALID_ID;
        }
        try {
            manager.deselectTrack(trackId);
            return TrackResult.OK;
        } catch (Throwable t) {
            return TrackResult.ENGINE_ERROR;
        }
    }

    private static int ijkTypeToType(int ijkTrackType) {
        switch (ijkTrackType) {
            case ITrackInfo.MEDIA_TRACK_TYPE_VIDEO: return TrackInfo.TYPE_VIDEO;
            case ITrackInfo.MEDIA_TRACK_TYPE_AUDIO: return TrackInfo.TYPE_AUDIO;
            case ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE:
            case ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT: return TrackInfo.TYPE_TEXT;
            default: return TrackInfo.TYPE_UNKNOWN;
        }
    }
}
