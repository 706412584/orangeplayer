package com.orange.playerlibrary.ui;

/**
 * 播放器 UI 阶段。
 *
 * <p>控制栏 UI 据此决定显示哪一层浮层（加载 / 错误 / 起播 / 播完）。
 * 与具体播放器内核的 state 常量解耦，UI 层不需要认识 GSY/ijk/Exo 的数值。</p>
 */
public final class IOrangePlaybackUiState {

    private IOrangePlaybackUiState() {
    }

    /** 空闲/普通，无浮层。 */
    public static final int STAGE_NONE = 0;
    /** 起播前：等待用户点「开始播放」。 */
    public static final int STAGE_PREPARE = 1;
    /** 加载/缓冲中：显示进度圈。 */
    public static final int STAGE_LOADING = 2;
    /** 播放中。 */
    public static final int STAGE_PLAYING = 3;
    /** 暂停。 */
    public static final int STAGE_PAUSED = 4;
    /** 播放出错：显示错误文案与重试。 */
    public static final int STAGE_ERROR = 5;
    /** 播放完成：显示重播。 */
    public static final int STAGE_COMPLETE = 6;
}
