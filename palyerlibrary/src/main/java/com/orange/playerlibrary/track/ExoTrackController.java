package com.orange.playerlibrary.track;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;

import java.util.ArrayList;
import java.util.List;

import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

/**
 * Exo 引擎的轨道控制适配器。
 * <p>
 * 经 IjkExo2MediaPlayer 的 getTrackInfo()（Media3 Tracks 映射）枚举，
 * 经 TrackSelector 的 TrackSelectionOverride 选择。
 * <p>
 * 本类引用 media3 类型，运行时仅当 classpath 含 gsyVideoPlayer-exo_player2
 * （palyerlibrary 为 compileOnly）时可达；分派方须以引擎 instanceof 守卫。
 */
public class ExoTrackController implements TrackController {

    private final IjkExo2MediaPlayer player;

    public ExoTrackController(IjkExo2MediaPlayer player) {
        this.player = player;
    }

    @Override
    public boolean isTrackSelectionSupported() {
        return true;
    }

    @Override
    public List<TrackInfo> getAvailableTracks() {
        List<TrackInfo> result = new ArrayList<>();
        if (player == null) {
            return result;
        }
        Tracks tracks = player.getCurrentTracks();
        if (tracks == null) {
            return result;
        }
        int id = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            int trackCount = group.getMediaTrackGroup().length;
            for (int i = 0; i < trackCount; i++) {
                Format format = group.getTrackFormat(i);
                int type = mediaTypeToType(format.sampleMimeType);
                if (type == TrackInfo.TYPE_UNKNOWN) {
                    continue;
                }
                boolean selected = group.isTrackSelected(i);
                String label = format.label != null ? format.label
                        : (format.language != null ? format.language : null);
                result.add(new TrackInfo(id, type, format.language, label, selected));
                id++;
            }
        }
        return result;
    }

    @Override
    public TrackResult selectTrack(int trackId) {
        int[] groupAndIndex = locateTrack(trackId);
        if (groupAndIndex == null) {
            return TrackResult.INVALID_ID;
        }
        return applyOverride(groupAndIndex[0], groupAndIndex[1]);
    }

    @Override
    public TrackResult deselectTrack(int trackId) {
        int[] groupAndIndex = locateTrack(trackId);
        if (groupAndIndex == null) {
            return TrackResult.INVALID_ID;
        }
        // 取消选择 = 按该轨道所属 renderer 类型清除 override，回到默认选择策略
        MappingTrackSelector selector = player.getTrackSelector();
        if (selector == null) {
            return TrackResult.NOT_READY;
        }
        Tracks tracks = player.getCurrentTracks();
        if (tracks == null) {
            return TrackResult.NOT_READY;
        }
        try {
            Tracks.Group group = tracks.getGroups().get(groupAndIndex[0]);
            Format format = group.getTrackFormat(groupAndIndex[1]);
            int rendererType = mediaTypeToRendererType(format.sampleMimeType);
            TrackSelectionParameters parameters = selector.getParameters()
                    .buildUpon()
                    .clearOverridesOfType(rendererType)
                    .build();
            selector.setParameters(parameters);
            return TrackResult.OK;
        } catch (Throwable t) {
            return TrackResult.ENGINE_ERROR;
        }
    }

    /** 定位 trackId 对应的 (groupIndex, trackIndexInGroup) */
    private int[] locateTrack(int trackId) {
        if (player == null) {
            return null;
        }
        Tracks tracks = player.getCurrentTracks();
        if (tracks == null) {
            return null;
        }
        int id = 0;
        int groupIndex = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            int trackCount = group.getMediaTrackGroup().length;
            for (int i = 0; i < trackCount; i++) {
                Format format = group.getTrackFormat(i);
                if (mediaTypeToType(format.sampleMimeType) == TrackInfo.TYPE_UNKNOWN) {
                    continue;
                }
                if (id == trackId) {
                    return new int[]{groupIndex, i};
                }
                id++;
            }
            groupIndex++;
        }
        return null;
    }

    private TrackResult applyOverride(int groupIndex, int trackIndexInGroup) {
        MappingTrackSelector selector = player.getTrackSelector();
        if (selector == null) {
            return TrackResult.NOT_READY;
        }
        Tracks tracks = player.getCurrentTracks();
        if (tracks == null) {
            return TrackResult.NOT_READY;
        }
        try {
            Tracks.Group group = tracks.getGroups().get(groupIndex);
            TrackSelectionOverride override = new TrackSelectionOverride(
                    group.getMediaTrackGroup(), trackIndexInGroup);
            TrackSelectionParameters parameters = selector.getParameters()
                    .buildUpon()
                    .addOverride(override)
                    .build();
            selector.setParameters(parameters);
            return TrackResult.OK;
        } catch (Throwable t) {
            return TrackResult.ENGINE_ERROR;
        }
    }

    private static int mediaTypeToRendererType(String sampleMimeType) {
        if (sampleMimeType == null) {
            return C.TRACK_TYPE_NONE;
        }
        if (sampleMimeType.startsWith("video/")) {
            return C.TRACK_TYPE_VIDEO;
        }
        if (sampleMimeType.startsWith("audio/")) {
            return C.TRACK_TYPE_AUDIO;
        }
        if (sampleMimeType.startsWith("text/")
                || sampleMimeType.equals(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
                || sampleMimeType.equals(androidx.media3.common.MimeTypes.APPLICATION_TTML)) {
            return C.TRACK_TYPE_TEXT;
        }
        return C.TRACK_TYPE_NONE;
    }

    private static int mediaTypeToType(String sampleMimeType) {
        if (sampleMimeType == null) {
            return TrackInfo.TYPE_UNKNOWN;
        }
        if (sampleMimeType.startsWith("video/")) {
            return TrackInfo.TYPE_VIDEO;
        }
        if (sampleMimeType.startsWith("audio/")) {
            return TrackInfo.TYPE_AUDIO;
        }
        if (sampleMimeType.startsWith("text/")
                || sampleMimeType.equals(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
                || sampleMimeType.equals(androidx.media3.common.MimeTypes.APPLICATION_TTML)) {
            return TrackInfo.TYPE_TEXT;
        }
        return TrackInfo.TYPE_UNKNOWN;
    }

    /** IJK 类型常量兼容转换（供与 IjkTrackController 的 TrackInfo 对齐） */
    static int ijkCompatType(int ijkTrackType) {
        switch (ijkTrackType) {
            case ITrackInfo.MEDIA_TRACK_TYPE_VIDEO: return TrackInfo.TYPE_VIDEO;
            case ITrackInfo.MEDIA_TRACK_TYPE_AUDIO: return TrackInfo.TYPE_AUDIO;
            case ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE: return TrackInfo.TYPE_TEXT;
            default: return TrackInfo.TYPE_UNKNOWN;
        }
    }
}
