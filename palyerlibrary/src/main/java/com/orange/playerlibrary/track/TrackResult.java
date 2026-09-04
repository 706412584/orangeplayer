package com.orange.playerlibrary.track;

/**
 * 轨道选择操作结果
 */
public enum TrackResult {
    /** 成功 */
    OK,
    /** 当前引擎不支持轨道选择（系统/阿里云） */
    UNSUPPORTED_ENGINE,
    /** 播放器未就绪（需在 onPrepared 后调用） */
    NOT_READY,
    /** 无效的轨道 id */
    INVALID_ID,
    /** 引擎内部错误 */
    ENGINE_ERROR
}
