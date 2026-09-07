package com.orange.playerlibrary;

/**
 * 播放器常量定义
 * 保持与原 DKPlayer 一致的状态常量定义
 */
public class PlayerConstants {
    
    // ===== 播放状态常量 =====
    /** 播放错误 */
    public static final int STATE_ERROR = -1;
    /** 空闲状态 */
    public static final int STATE_IDLE = 0;
    /** 准备中 */
    public static final int STATE_PREPARING = 1;
    /** 准备完成 */
    public static final int STATE_PREPARED = 2;
    /** 播放中 */
    public static final int STATE_PLAYING = 3;
    /** 已暂停 */
    public static final int STATE_PAUSED = 4;
    /** 播放完成 */
    public static final int STATE_PLAYBACK_COMPLETED = 5;
    /** 缓冲中 */
    public static final int STATE_BUFFERING = 6;
    /** 缓冲完成 */
    public static final int STATE_BUFFERED = 7;
    
    // ===== 播放器状态常量 =====
    /** 普通模式 */
    public static final int PLAYER_NORMAL = 10;
    /** 全屏模式 */
    public static final int PLAYER_FULL_SCREEN = 11;
    /** 小窗模式 */
    public static final int PLAYER_TINY_SCREEN = 12;
    
    // ===== 自定义状态常量 =====
    /** 开始嗅探 */
    public static final int STATE_STARTSNIFFING = 13;
    /** 结束嗅探 */
    public static final int STATE_ENDSNIFFING = 14;
    /** 开始 M3U8 去广告处理 */
    public static final int STATE_M3U8_AD_REMOVAL = 15;
    /** 结束 M3U8 去广告处理 */
    public static final int STATE_M3U8_AD_REMOVAL_END = 16;
    
    // ===== 播放内核常量 =====
    /** 播放器引擎键名 */
    public static final String KEY_PLAYER_ENGINE = "player_engine";
    /** IJK 播放器 */
    public static final String ENGINE_IJK = "ijk";
    /** ExoPlayer */
    public static final String ENGINE_EXO = "exo";
    /** 阿里云播放器 */
    public static final String ENGINE_ALI = "ali";
    /** Android 原生 MediaPlayer */
    public static final String ENGINE_DEFAULT = "default";
    /** mpv 内核（可选独立工件 orangeplayer-mpv，仅用户显式选择，永不自动回退到达） */
    public static final String ENGINE_MPV = "mpv";
    
    // ===== 屏幕方向常量 =====
    /** 竖屏 */
    public static final int SCREEN_ORIENTATION_PORTRAIT = 0;
    /** 横屏 */
    public static final int SCREEN_ORIENTATION_LANDSCAPE = 1;
    /** 反向横屏 */
    public static final int SCREEN_ORIENTATION_REVERSE_LANDSCAPE = 2;
    
    // ===== 渲染类型常量 =====
    /** TextureView 渲染 */
    public static final int RENDER_TEXTURE_VIEW = 0;
    /** SurfaceView 渲染 */
    public static final int RENDER_SURFACE_VIEW = 1;
}
