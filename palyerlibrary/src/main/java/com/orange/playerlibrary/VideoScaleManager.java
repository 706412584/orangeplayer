package com.orange.playerlibrary;

import com.shuyu.gsyvideoplayer.utils.GSYVideoType;

/**
 * 视频比例管理器
 * 负责视频比例的应用和管理
 * 
 * Requirements: 1.1, 1.2
 */
public class VideoScaleManager {
    
    private static final String TAG = "VideoScaleManager";
    
    private final OrangevideoView mVideoView;
    private final PlayerSettingsManager mSettingsManager;
    
    /**
     * 构造函数
     * 
     * @param videoView 视频播放器视图
     * @param settingsManager 设置管理器
     */
    public VideoScaleManager(OrangevideoView videoView, PlayerSettingsManager settingsManager) {
        mVideoView = videoView;
        mSettingsManager = settingsManager;
    }
    
    /**
     * 应用保存的视频比例设置
     * 优先使用会话内的比例（mCurrentScreenScale），如果没有则从持久化存储读取
     * 
     * Requirements: 1.1, 1.2
     */
    public void applyVideoScale() {
        // 优先使用会话内的比例（由 VideoEventManager 管理）
        // 会话内比例在同一剧集内切换集数时保持，切换剧集时重置
        String scale = mSettingsManager.getSessionVideoScale();
        if (scale == null || scale.isEmpty()) {
            scale = mSettingsManager.getVideoScale();
        }
        applyScaleType(scale);
    }
    
    /**
     * 应用会话内的视频比例
     * 
     * @param scale 比例类型
     */
    public void applySessionScale(String scale) {
        mSettingsManager.setSessionVideoScale(scale);
        applyScaleType(scale);
    }
    
    /**
     * 根据比例类型设置视频显示模式
     * 
     * @param scaleType 比例类型（默认、16:9、4:3、全屏裁剪、全屏拉伸）
     * 
     * Requirements: 1.2, 1.3
     */
    public void applyScaleType(String scaleType) {
        if (scaleType == null || scaleType.isEmpty()) {
            scaleType = "默认";
        }
        
        android.util.Log.d(TAG, "========================================");
        android.util.Log.d(TAG, "applyScaleType: scaleType=" + scaleType);
        android.util.Log.d(TAG, "applyScaleType: mVideoView size=" + mVideoView.getWidth() + "x" + mVideoView.getHeight());
        android.util.Log.d(TAG, "applyScaleType: mVideoView isAttachedToWindow=" + (mVideoView.getWindowToken() != null));
        if (mVideoView.getRenderProxy() != null) {
            android.util.Log.d(TAG, "applyScaleType: RenderProxy size=" + 
                mVideoView.getRenderProxy().getWidth() + "x" + mVideoView.getRenderProxy().getHeight());
        }
        
        // 先设置 GSY 的显示类型
        switch (scaleType) {
            case "默认":
                GSYVideoType.setShowType(GSYVideoType.SCREEN_TYPE_DEFAULT);
                break;
            case "16:9":
                GSYVideoType.setShowType(GSYVideoType.SCREEN_TYPE_16_9);
                break;
            case "4:3":
                GSYVideoType.setShowType(GSYVideoType.SCREEN_TYPE_4_3);
                break;
            case "全屏裁剪":
                GSYVideoType.setShowType(GSYVideoType.SCREEN_TYPE_FULL);
                break;
            case "全屏拉伸":
                GSYVideoType.setShowType(GSYVideoType.SCREEN_MATCH_FULL);
                break;
            default:
                GSYVideoType.setShowType(GSYVideoType.SCREEN_TYPE_DEFAULT);
                break;
        }
        
        android.util.Log.d(TAG, "applyScaleType: GSYVideoType.getShowType()=" + GSYVideoType.getShowType());

        // mpv 内核：GSY 的裁剪/拉伸靠渲染器矩阵适配 TextureView 尺寸，mpv 无此层
        // （vo 恒按视频 aspect 在 surface 内 fit）。画面比例改由 mpv 自身渲染选项
        // 实现：拉伸=keepaspect=no，裁剪=video-zoom 放大裁边；其余档复位。
        // 反射调用保持零编译依赖。
        if (mVideoView != null) {
            try {
                Class<?> binder = Class.forName("com.orange.player.mpv.MpvPlayerManager");
                if (binder != null
                        && PlayerConstants.ENGINE_MPV.equals(mVideoView.getCurrentEngineType())) {
                    binder.getMethod("applyVideoScaleMode", String.class)
                            .invoke(null, scaleType);
                }
            } catch (ClassNotFoundException ignored) {
                // mpv 工件未引入：非 mpv 内核场景，忽略
            } catch (Exception e) {
                android.util.Log.w(TAG, "mpv applyVideoScaleMode failed", e);
            }
        }

        // 阿里云播放器特殊处理：需要调用 IPlayer.setScaleMode()
        applyAliPlayerScaleMode(scaleType);
        
        // 在主线程刷新视频显示
        mVideoView.post(new Runnable() {
            @Override
            public void run() {
                android.util.Log.d(TAG, "applyScaleType: 调用 refreshVideoShowType()");
                
                // 关键修复：先强制 RenderProxy 重新测量布局
                if (mVideoView.getRenderProxy() != null) {
                    mVideoView.getRenderProxy().requestLayout();
                }
                
                mVideoView.refreshVideoShowType();
                
                android.util.Log.d(TAG, "applyScaleType: refreshVideoShowType() 后 - mVideoView size=" + 
                    mVideoView.getWidth() + "x" + mVideoView.getHeight());
                if (mVideoView.getRenderProxy() != null) {
                    android.util.Log.d(TAG, "applyScaleType: refreshVideoShowType() 后 - RenderProxy size=" + 
                        mVideoView.getRenderProxy().getWidth() + "x" + mVideoView.getRenderProxy().getHeight());
                }
                
                // ExoPlayer 全屏时需要更新 SurfaceControl 尺寸
                // 延迟执行，等待布局完成
                mVideoView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        android.util.Log.d(TAG, "applyScaleType: 调用 updateExoSurfaceControlIfNeeded()");
                        mVideoView.updateExoSurfaceControlIfNeeded();
                        
                        android.util.Log.d(TAG, "applyScaleType: updateExoSurfaceControlIfNeeded() 后 - mVideoView size=" + 
                            mVideoView.getWidth() + "x" + mVideoView.getHeight());
                        if (mVideoView.getRenderProxy() != null) {
                            android.util.Log.d(TAG, "applyScaleType: updateExoSurfaceControlIfNeeded() 后 - RenderProxy size=" + 
                                mVideoView.getRenderProxy().getWidth() + "x" + mVideoView.getRenderProxy().getHeight());
                        }
                        
                        // 再次强制刷新，确保尺寸正确
                        mVideoView.refreshVideoShowType();
                        
                        android.util.Log.d(TAG, "========================================");
                    }
                }, 200);  // 增加延迟到 200ms
            }
        });
    }
    
    /**
     * 为阿里云播放器设置缩放模式
     * 阿里云播放器有自己的缩放模式控制，需要通过反射调用 IPlayer.setScaleMode()
     * 
     * @param scaleType 比例类型
     */
    private void applyAliPlayerScaleMode(final String scaleType) {
        try {
            // 获取当前播放器管理器（注意：要通过 getPlayer() 获取实际的播放器实例）
            com.shuyu.gsyvideoplayer.GSYVideoManager videoManager = mVideoView.getGSYVideoManager();
            if (videoManager == null) {
                android.util.Log.d(TAG, "applyAliPlayerScaleMode: videoManager is null");
                return;
            }
            
            // 获取实际的播放器管理器实例
            Object playerManager = videoManager.getPlayer();
            if (playerManager == null) {
                android.util.Log.d(TAG, "applyAliPlayerScaleMode: playerManager (getPlayer()) is null");
                return;
            }
            
            String managerClassName = playerManager.getClass().getName();
            android.util.Log.d(TAG, "applyAliPlayerScaleMode: playerManager class=" + managerClassName);
            
            // 只处理阿里云播放器
            if (!managerClassName.contains("AliPlayerManager")) {
                android.util.Log.d(TAG, "applyAliPlayerScaleMode: 不是阿里云播放器，跳过");
                return;
            }
            
            android.util.Log.d(TAG, "applyAliPlayerScaleMode: 检测到阿里云播放器，开始设置缩放模式");
            
            // 通过反射获取 AliMediaPlayer
            java.lang.reflect.Method getMediaPlayerMethod = playerManager.getClass().getMethod("getMediaPlayer");
            Object mediaPlayer = getMediaPlayerMethod.invoke(playerManager);
            
            if (mediaPlayer == null) {
                android.util.Log.w(TAG, "applyAliPlayerScaleMode: mediaPlayer is null，播放器可能还未初始化");
                // 延迟重试
                mVideoView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        android.util.Log.d(TAG, "applyAliPlayerScaleMode: 延迟重试设置缩放模式");
                        applyAliPlayerScaleMode(scaleType);
                    }
                }, 500);
                return;
            }
            
            android.util.Log.d(TAG, "applyAliPlayerScaleMode: mediaPlayer class=" + mediaPlayer.getClass().getName());
            
            // 通过反射获取 AliPlayer 实例（AliMediaPlayer 内部的 mInternalPlayer）
            java.lang.reflect.Field internalPlayerField = mediaPlayer.getClass().getDeclaredField("mInternalPlayer");
            internalPlayerField.setAccessible(true);
            Object aliPlayer = internalPlayerField.get(mediaPlayer);
            
            if (aliPlayer == null) {
                android.util.Log.w(TAG, "applyAliPlayerScaleMode: aliPlayer (mInternalPlayer) is null，播放器可能还未准备好");
                // 延迟重试
                mVideoView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        android.util.Log.d(TAG, "applyAliPlayerScaleMode: 延迟重试设置缩放模式（第2次）");
                        applyAliPlayerScaleMode(scaleType);
                    }
                }, 500);
                return;
            }
            
            android.util.Log.d(TAG, "applyAliPlayerScaleMode: aliPlayer class=" + aliPlayer.getClass().getName());
            
            // 获取 IPlayer.ScaleMode 枚举类
            Class<?> scaleModeClass = Class.forName("com.aliyun.player.IPlayer$ScaleMode");
            Object scaleModeValue = null;
            
            // 根据 scaleType 选择对应的 ScaleMode
            switch (scaleType) {
                case "默认":
                case "16:9":
                case "4:3":
                    // SCALE_ASPECT_FIT - 自动缩放适配（保持比例）
                    scaleModeValue = java.lang.Enum.valueOf((Class<Enum>) scaleModeClass, "SCALE_ASPECT_FIT");
                    android.util.Log.d(TAG, "applyAliPlayerScaleMode: 选择 SCALE_ASPECT_FIT");
                    break;
                case "全屏裁剪":
                    // SCALE_ASPECT_FILL - 填充适配（保持比例，裁剪）
                    scaleModeValue = java.lang.Enum.valueOf((Class<Enum>) scaleModeClass, "SCALE_ASPECT_FILL");
                    android.util.Log.d(TAG, "applyAliPlayerScaleMode: 选择 SCALE_ASPECT_FILL");
                    break;
                case "全屏拉伸":
                    // SCALE_TO_FILL - 拉伸适配（不保持比例）
                    scaleModeValue = java.lang.Enum.valueOf((Class<Enum>) scaleModeClass, "SCALE_TO_FILL");
                    android.util.Log.d(TAG, "applyAliPlayerScaleMode: 选择 SCALE_TO_FILL");
                    break;
                default:
                    scaleModeValue = java.lang.Enum.valueOf((Class<Enum>) scaleModeClass, "SCALE_ASPECT_FIT");
                    android.util.Log.d(TAG, "applyAliPlayerScaleMode: 默认选择 SCALE_ASPECT_FIT");
                    break;
            }
            
            // 调用 IPlayer.setScaleMode()
            java.lang.reflect.Method setScaleModeMethod = aliPlayer.getClass().getMethod("setScaleMode", scaleModeClass);
            setScaleModeMethod.invoke(aliPlayer, scaleModeValue);
            
            android.util.Log.d(TAG, "✅ applyAliPlayerScaleMode: 成功设置阿里云播放器缩放模式 - " + scaleModeValue);
            
        } catch (ClassNotFoundException e) {
            android.util.Log.e(TAG, "applyAliPlayerScaleMode: 找不到阿里云播放器类，可能未集成阿里云SDK", e);
        } catch (NoSuchMethodException e) {
            android.util.Log.e(TAG, "applyAliPlayerScaleMode: 找不到方法", e);
        } catch (NoSuchFieldException e) {
            android.util.Log.e(TAG, "applyAliPlayerScaleMode: 找不到字段 mInternalPlayer", e);
        } catch (Exception e) {
            android.util.Log.e(TAG, "applyAliPlayerScaleMode: 设置阿里云播放器缩放模式失败", e);
        }
    }
    
    /**
     * 获取当前视频比例设置
     * 优先返回会话比例（用户当前选择的），如果没有则返回持久化比例
     * 
     * @return 当前视频比例
     */
    public String getCurrentScale() {
        String sessionScale = mSettingsManager.getSessionVideoScale();
        if (sessionScale != null && !sessionScale.isEmpty()) {
            return sessionScale;
        }
        return mSettingsManager.getVideoScale();
    }
    
    /**
     * 设置并保存视频比例
     * 
     * @param scaleType 比例类型
     * 
     * Requirements: 1.1
     */
    public void setAndSaveScale(String scaleType) {
        mSettingsManager.setVideoScale(scaleType);
        applyScaleType(scaleType);
    }
}
