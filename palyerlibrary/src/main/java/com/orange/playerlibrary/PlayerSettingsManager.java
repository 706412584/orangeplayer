package com.orange.playerlibrary;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 播放器设置管理器
 * 负责保存和读取播放器的各种设置
 */
public class PlayerSettingsManager {
    
    private static final String PREFERENCES_NAME = "orange_player_settings";
    private static final String KEY_PLAYER_ENGINE = "player_engine";
    private static final String KEY_LONG_PRESS_SPEED = "long_press_speed";
    private static final String KEY_PLAY_MODE = "play_mode";
    private static final String KEY_VIDEO_SCALE = "video_scale";
    private static final String KEY_SKIP_OPENING = "skip_opening";
    private static final String KEY_SKIP_ENDING = "skip_ending";
    private static final String KEY_BOTTOM_PROGRESS = "bottom_progress";
    private static final String KEY_DANMAKU_ENABLED = "danmaku_enabled";
    private static final String KEY_DANMAKU_TEXT_SIZE = "danmaku_text_size";
    private static final String KEY_DANMAKU_SPEED = "danmaku_speed";
    private static final String KEY_DANMAKU_ALPHA = "danmaku_alpha";
    
    // 自动选择播放器内核设置
    private static final String KEY_AUTO_SELECT_ENGINE = "auto_select_engine";
    
    // 智能全屏设置
    private static final String KEY_SMART_FULLSCREEN = "smart_fullscreen";
    
    // 嗅探自动播放设置
    private static final String KEY_SNIFFING_AUTO_PLAY = "sniffing_auto_play";
    
    // 解码方式设置
    private static final String KEY_DECODE_MODE = "decode_mode";
    public static final String DECODE_HARDWARE = "hardware";  // 硬件解码
    public static final String DECODE_SOFTWARE = "software";  // 软件解码
    
    // 字幕设置
    private static final String KEY_SUBTITLE_SIZE = "subtitle_size";
    private static final String KEY_SUBTITLE_ENABLED = "subtitle_enabled";
    private static final String KEY_SUBTITLE_URL_PREFIX = "subtitle_url_";      // 按视频URL存储
    private static final String KEY_SUBTITLE_LOCAL_PREFIX = "subtitle_local_";  // 按视频URL存储本地字幕Uri
    private static final String KEY_SUBTITLE_MIME_PREFIX = "subtitle_mime_";    // 按视频URL存储字幕 MIME
    private static final String KEY_SUBTITLE_DELAY_PREFIX = "subtitle_delay_";  // 按视频URL存储字幕延迟（毫秒）
    
    // 自动旋转设置
    private static final String KEY_AUTO_ROTATE = "auto_rotate";

    // TV 自动帧率匹配设置
    private static final String KEY_AUTO_FRAME_RATE_MATCHING = "auto_frame_rate_matching";

    private static final String KEY_DOWNLOAD_ENABLED = "download_enabled";
    
    // 记忆播放设置
    private static final String KEY_MEMORY_PLAY_ENABLED = "memory_play_enabled";
    
    // ===== 实例 =====
    private static volatile PlayerSettingsManager sInstance;
    private SharedPreferences mPreferences;
    
    // 播放器引擎变更监听器
    private EngineChangeListener mEngineChangeListener;
    
    /**
     * 播放器引擎变更监听器接口
     */
    public interface EngineChangeListener {
        void onEngineChanged(String newEngine);
    }
    
    /**
     * 设置播放器引擎变更监听器
     */
    public void setEngineChangeListener(EngineChangeListener listener) {
        mEngineChangeListener = listener;
    }
    
    private PlayerSettingsManager(Context context) {
        mPreferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
    
    public static PlayerSettingsManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (PlayerSettingsManager.class) {
                if (sInstance == null) {
                    sInstance = new PlayerSettingsManager(context);
                }
            }
        }
        return sInstance;
    }
    
    // ===== 播放器引擎设置 =====
    
    public void setPlayerEngine(String engine) {
        String oldEngine = mPreferences.getString(KEY_PLAYER_ENGINE, null);
        
        // 保存设置
        mPreferences.edit().putString(KEY_PLAYER_ENGINE, engine).apply();
        
        // 通知监听器（如果引擎确实发生了变化）
        if (mEngineChangeListener != null && (oldEngine == null || !oldEngine.equals(engine))) {
            mEngineChangeListener.onEngineChanged(engine);
        }
    }
    
    /**
     * 获取播放器引擎设置
     * 
     * 优先级：
     * 1. 用户手动设置的内核
     * 2. 智能检测可用内核（ExoPlayer > 系统播放器）
     * 
     * 注意：不再默认使用 IJK，因为很多项目可能不包含 IJK 依赖
     */
    public String getPlayerEngine() {
        String engine = mPreferences.getString(KEY_PLAYER_ENGINE, null);
        
        // 用户已手动设置
        if (engine != null && !engine.isEmpty()) {
            return engine;
        }
        
        // 智能选择默认内核
        return getDefaultEngine();
    }
    
    /**
     * 智能选择默认播放器内核
     * 优先级：ExoPlayer > 系统播放器
     */
    private String getDefaultEngine() {
        // 检测 ExoPlayer 是否可用
        if (isExoPlayerAvailable()) {
            android.util.Log.d("PlayerSettingsManager", "默认内核: ExoPlayer");
            return PlayerConstants.ENGINE_EXO;
        }
        
        // 回退到系统播放器（始终可用）
        android.util.Log.d("PlayerSettingsManager", "默认内核: 系统播放器");
        return PlayerConstants.ENGINE_DEFAULT;
    }
    
    /**
     * 检测 ExoPlayer 是否可用
     */
    private boolean isExoPlayerAvailable() {
        try {
            // 检测 GSY ExoPlayer 或 Media3 ExoPlayer
            Class.forName("tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer");
            return true;
        } catch (ClassNotFoundException e) {
            // 尝试检测 Media3
            try {
                Class.forName("androidx.media3.exoplayer.ExoPlayer");
                return true;
            } catch (ClassNotFoundException ex) {
                return false;
            }
        }
    }
    
    /**
     * 检查用户是否手动设置过内核
     */
    public boolean hasUserSetEngine() {
        String engine = mPreferences.getString(KEY_PLAYER_ENGINE, null);
        return engine != null && !engine.isEmpty();
    }
    
    // ===== 长按倍速设置 =====
    
    public void setLongPressSpeed(float speed) {
        mPreferences.edit().putFloat(KEY_LONG_PRESS_SPEED, speed).apply();
    }
    
    public float getLongPressSpeed() {
        // 默认 3.0x（长按倍速最高 3.0x）
        return mPreferences.getFloat(KEY_LONG_PRESS_SPEED, 3.0f);
    }
    
    // ===== 播放模式设置 =====
    
    public void setPlayMode(String mode) {
        mPreferences.edit().putString(KEY_PLAY_MODE, mode).apply();
    }
    
    public String getPlayMode() {
        return mPreferences.getString(KEY_PLAY_MODE, "sequential");
    }
    
    // ===== 画面比例设置 =====
    
    public void setVideoScale(String scale) {
        mPreferences.edit().putString(KEY_VIDEO_SCALE, scale).apply();
    }
    
    public String getVideoScale() {
        return mPreferences.getString(KEY_VIDEO_SCALE, "默认");
    }
    
    // ===== 会话内画面比例（不持久化，切换剧集时重置）=====
    
    private String mSessionVideoScale = null;
    
    public void setSessionVideoScale(String scale) {
        mSessionVideoScale = scale;
    }
    
    public String getSessionVideoScale() {
        return mSessionVideoScale;
    }
    
    public void clearSessionVideoScale() {
        mSessionVideoScale = null;
    }
    
    // ===== 跳过片头片尾设置 =====
    
    public void setSkipOpening(int milliseconds) {
        mPreferences.edit().putInt(KEY_SKIP_OPENING, milliseconds).apply();
    }
    
    public int getSkipOpening() {
        return mPreferences.getInt(KEY_SKIP_OPENING, 0);
    }
    
    public void setSkipEnding(int milliseconds) {
        mPreferences.edit().putInt(KEY_SKIP_ENDING, milliseconds).apply();
    }
    
    public int getSkipEnding() {
        return mPreferences.getInt(KEY_SKIP_ENDING, 0);
    }
    
    // ===== 底部进度条设置 =====
    
    public void setBottomProgressEnabled(boolean enabled) {
        mPreferences.edit().putBoolean(KEY_BOTTOM_PROGRESS, enabled).apply();
    }
    
    public boolean isBottomProgressEnabled() {
        return mPreferences.getBoolean(KEY_BOTTOM_PROGRESS, true);
    }
    
    // ===== 弹幕设置 =====
    
    public void setDanmakuEnabled(boolean enabled) {
        mPreferences.edit().putBoolean(KEY_DANMAKU_ENABLED, enabled).apply();
    }
    
    public boolean isDanmakuEnabled() {
        return mPreferences.getBoolean(KEY_DANMAKU_ENABLED, true);
    }
    
    public void setDanmakuTextSize(float size) {
        mPreferences.edit().putFloat(KEY_DANMAKU_TEXT_SIZE, size).apply();
    }
    
    public float getDanmakuTextSize() {
        return mPreferences.getFloat(KEY_DANMAKU_TEXT_SIZE, 16.0f);
    }
    
    public void setDanmakuSpeed(float speed) {
        mPreferences.edit().putFloat(KEY_DANMAKU_SPEED, speed).apply();
    }
    
    public float getDanmakuSpeed() {
        return mPreferences.getFloat(KEY_DANMAKU_SPEED, 1.5f);
    }
    
    public void setDanmakuAlpha(float alpha) {
        mPreferences.edit().putFloat(KEY_DANMAKU_ALPHA, alpha).apply();
    }
    
    public float getDanmakuAlpha() {
        return mPreferences.getFloat(KEY_DANMAKU_ALPHA, 1.0f);
    }
    
    // ===== 解码方式设置 =====
    
    public void setDecodeMode(String mode) {
        mPreferences.edit().putString(KEY_DECODE_MODE, mode).apply();
    }
    
    public String getDecodeMode() {
        return mPreferences.getString(KEY_DECODE_MODE, DECODE_HARDWARE); // 默认硬件解码
    }
    
    public boolean isHardwareDecode() {
        return DECODE_HARDWARE.equals(getDecodeMode());
    }
    
    // ===== 字幕设置 =====
    
    public void setSubtitleSize(float size) {
        mPreferences.edit().putFloat(KEY_SUBTITLE_SIZE, size).apply();
    }
    
    public float getSubtitleSize() {
        return mPreferences.getFloat(KEY_SUBTITLE_SIZE, 18.0f); // 默认18sp
    }
    
    public void setSubtitleEnabled(boolean enabled) {
        mPreferences.edit().putBoolean(KEY_SUBTITLE_ENABLED, enabled).apply();
    }
    
    public boolean isSubtitleEnabled() {
        return mPreferences.getBoolean(KEY_SUBTITLE_ENABLED, false);
    }
    
    /**
     * 保存视频对应的字幕URL
     * @param videoUrl 视频URL
     * @param subtitleUrl 字幕URL
     */
    public void setSubtitleUrlForVideo(String videoUrl, String subtitleUrl) {
        String key = KEY_SUBTITLE_URL_PREFIX + hashVideoUrl(videoUrl);
        mPreferences.edit().putString(key, subtitleUrl).apply();
    }
    
    /**
     * 获取视频对应的字幕URL
     */
    public String getSubtitleUrlForVideo(String videoUrl) {
        String key = KEY_SUBTITLE_URL_PREFIX + hashVideoUrl(videoUrl);
        return mPreferences.getString(key, null);
    }
    
    /**
     * 保存视频对应的本地字幕Uri
     * @param videoUrl 视频URL
     * @param subtitleUri 本地字幕Uri字符串
     */
    public void setSubtitleLocalForVideo(String videoUrl, String subtitleUri) {
        String key = KEY_SUBTITLE_LOCAL_PREFIX + hashVideoUrl(videoUrl);
        mPreferences.edit().putString(key, subtitleUri).apply();
    }
    
    /**
     * 获取视频对应的本地字幕Uri
     */
    public String getSubtitleLocalForVideo(String videoUrl) {
        String key = KEY_SUBTITLE_LOCAL_PREFIX + hashVideoUrl(videoUrl);
        return mPreferences.getString(key, null);
    }

    public void setSubtitleMimeTypeForVideo(String videoUrl, String mimeType) {
        String key = KEY_SUBTITLE_MIME_PREFIX + hashVideoUrl(videoUrl);
        mPreferences.edit().putString(key, mimeType).apply();
    }

    public String getSubtitleMimeTypeForVideo(String videoUrl) {
        String key = KEY_SUBTITLE_MIME_PREFIX + hashVideoUrl(videoUrl);
        return mPreferences.getString(key, null);
    }

    /**
     * 保存视频对应的字幕延迟（毫秒），正值延后显示
     */
    public void setSubtitleDelayForVideo(String videoUrl, long delayMs) {
        String key = KEY_SUBTITLE_DELAY_PREFIX + hashVideoUrl(videoUrl);
        mPreferences.edit().putLong(key, delayMs).apply();
    }

    /**
     * 获取视频对应的字幕延迟（毫秒），默认 0
     */
    public long getSubtitleDelayForVideo(String videoUrl) {
        String key = KEY_SUBTITLE_DELAY_PREFIX + hashVideoUrl(videoUrl);
        return mPreferences.getLong(key, 0L);
    }

    /**
     * 清除视频的字幕记忆
     */
    public void clearSubtitleForVideo(String videoUrl) {
        String hash = hashVideoUrl(videoUrl);
        mPreferences.edit()
            .remove(KEY_SUBTITLE_URL_PREFIX + hash)
            .remove(KEY_SUBTITLE_LOCAL_PREFIX + hash)
            .remove(KEY_SUBTITLE_MIME_PREFIX + hash)
            .remove(KEY_SUBTITLE_DELAY_PREFIX + hash)
            .apply();
    }
    
    // ===== 自动旋转设置 =====
    
    public void setAutoRotateEnabled(boolean enabled) {
        mPreferences.edit().putBoolean(KEY_AUTO_ROTATE, enabled).apply();
    }
    
    public boolean isAutoRotateEnabled() {
        return mPreferences.getBoolean(KEY_AUTO_ROTATE, true); // 默认启用
    }

    // ===== TV 自动帧率匹配设置 =====

    public void setAutoFrameRateMatchingEnabled(boolean enabled) {
        mPreferences.edit().putBoolean(KEY_AUTO_FRAME_RATE_MATCHING, enabled).apply();
    }

    public boolean isAutoFrameRateMatchingEnabled() {
        return mPreferences.getBoolean(KEY_AUTO_FRAME_RATE_MATCHING, false);
    }

    // ===== 自动选择播放器内核设置 =====
    
    /**
     * 设置是否启用自动选择播放器内核
     * 
     * 启用后，播放器会根据视频 URL 协议自动选择最合适的内核：
     * - RTSP 协议 → ExoPlayer（阿里云不支持）
     * - RTMP 协议 → 阿里云（延迟低，性能好）
     * - HLS (m3u8) → 阿里云（商业级优化）
     * - HTTP/HTTPS → ExoPlayer（性能好）
     * 
     * 注意：自动选择前会检测相关依赖是否已导入
     * 
     * @param enabled true 启用，false 禁用
     */
    public void setAutoSelectEngine(boolean enabled) {
        mPreferences.edit().putBoolean(KEY_AUTO_SELECT_ENGINE, enabled).apply();
    }
    
    /**
     * 是否启用自动选择播放器内核
     * 
     * @return true 启用，false 禁用（默认禁用）
     */
    public boolean isAutoSelectEngine() {
        return mPreferences.getBoolean(KEY_AUTO_SELECT_ENGINE, false); // 默认禁用
    }
    
    // ===== 智能全屏设置 =====
    
    /**
     * 设置是否启用智能全屏
     * 
     * 启用后，播放器会根据视频宽高比自动选择全屏模式：
     * - 横屏视频（宽 > 高）→ 横屏全屏
     * - 竖屏视频（高 > 宽）→ 竖屏全屏
     * 
     * @param enabled true 启用，false 禁用
     */
    public void setSmartFullscreenEnabled(boolean enabled) {
        mPreferences.edit().putBoolean(KEY_SMART_FULLSCREEN, enabled).apply();
    }
    
    /**
     * 是否启用智能全屏
     * 
     * @return true 启用，false 禁用（默认启用）
     */
    public boolean isSmartFullscreenEnabled() {
        return mPreferences.getBoolean(KEY_SMART_FULLSCREEN, true); // 默认启用
    }
    
    // ===== 嗅探自动播放设置 =====
    
    /**
     * 设置是否启用嗅探自动播放第一个视频
     * 
     * 启用后，嗅探完成时会自动播放第一个嗅探到的视频，并隐藏嗅探组件
     * 
     * @param enabled true 启用，false 禁用
     */
    public void setSniffingAutoPlayEnabled(boolean enabled) {
        mPreferences.edit().putBoolean(KEY_SNIFFING_AUTO_PLAY, enabled).apply();
    }
    
    /**
     * 是否启用嗅探自动播放第一个视频
     * 
     * @return true 启用，false 禁用（默认禁用）
     */
    public boolean isSniffingAutoPlayEnabled() {
        return mPreferences.getBoolean(KEY_SNIFFING_AUTO_PLAY, false); // 默认禁用
    }
    
    /**
     * 对视频URL进行哈希，避免key过长
     */
    private String hashVideoUrl(String url) {
        if (url == null) return "null";
        return String.valueOf(url.hashCode());
    }

    // ===== 下载功能设置 =====

    /**
     * 设置是否启用下载功能
     * @param enabled true 启用，false 禁用
     */
    public void setDownloadEnabled(boolean enabled) {
        mPreferences.edit().putBoolean(KEY_DOWNLOAD_ENABLED, enabled).apply();
    }

    /**
     * 查询是否启用了下载功能
     * @return true 启用，false 禁用（默认启用）
     */
    public boolean isDownloadEnabled() {
        return mPreferences.getBoolean(KEY_DOWNLOAD_ENABLED, true);
    }

    // ===== M3U8 去广告设置 =====

    private static final String KEY_AD_REMOVAL_ENABLED = "ad_removal_enabled";

    /**
     * 设置是否启用 M3U8 去广告
     * @param enabled true 启用，false 禁用
     */
    public void setAdRemovalEnabled(boolean enabled) {
        mPreferences.edit().putBoolean(KEY_AD_REMOVAL_ENABLED, enabled).apply();
    }

    /**
     * 查询是否启用了 M3U8 去广告
     * @return true 启用，false 禁用（默认启用，与 demo 历史行为一致）
     */
    public boolean isAdRemovalEnabled() {
        return mPreferences.getBoolean(KEY_AD_REMOVAL_ENABLED, true);
    }

    // ===== 记忆播放设置 =====
    
    /**
     * 设置是否启用记忆播放功能
     * @param enabled true 启用，false 禁用
     */
    public void setMemoryPlayEnabled(boolean enabled) {
        mPreferences.edit().putBoolean(KEY_MEMORY_PLAY_ENABLED, enabled).apply();
    }
    
    /**
     * 查询是否启用了记忆播放功能
     * @return true 启用，false 禁用（默认禁用）
     */
    public boolean isMemoryPlayEnabled() {
        return mPreferences.getBoolean(KEY_MEMORY_PLAY_ENABLED, false); // 默认关闭
    }
}
