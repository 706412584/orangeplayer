package com.orange.playerlibrary.track;

import java.util.List;

/**
 * 统一轨道控制接口（引擎无关）
 * <p>
 * 能力因引擎而异：Exo/IJK 支持枚举与选择，系统/阿里云返回
 * {@link TrackResult#UNSUPPORTED_ENGINE}。请在 onPrepared 之后再枚举。
 */
public interface TrackController {

    /** 当前引擎是否支持轨道选择 */
    boolean isTrackSelectionSupported();

    /** 枚举当前媒体的全部轨道；无轨道或未就绪时返回空列表 */
    List<TrackInfo> getAvailableTracks();

    /** 选择指定轨道 */
    TrackResult selectTrack(int trackId);

    /** 取消选择指定轨道（如关闭字幕轨） */
    TrackResult deselectTrack(int trackId);
}
