package com.orange.playerlibrary.speech;

import java.io.File;

/**
 * 渐进 ASR 的块音频来源：给定时间区间产出一段 16k/单声道 wav。
 *
 * 两种实现：
 *  - {@link LocalFileBlockSource}：本地/已缓存 mp4，MediaExtractor seek 截区间解码
 *  - {@link HlsCachedBlockSource}：HLS，按区间取分片，优先读播放器 media3 缓存
 */
public interface BlockAudioSource {

    /**
     * 产出 [startMs, endMs) 的 16k 单声道 wav。
     *
     * @return 该 wav 在整条视频时间轴上的实际起点（ms，分片/关键帧对齐后通常 <= startMs）；
     *         失败返回 -1
     */
    long extractBlockWav(long startMs, long endMs, File outWav) throws Exception;

    /** 视频总时长（ms）；未知返回 0 */
    long getDurationMs();

    /** 释放资源（会话结束时调用） */
    void release();
}
