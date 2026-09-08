package com.orange.playerlibrary;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.view.OrientationEventListener;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.orange.playerlibrary.exo.OrangeExoPlayerManager;
import com.orange.playerlibrary.player.OrangeSystemPlayerManager;
import com.shuyu.gsyvideoplayer.GSYVideoManager;

/**
 * 自定义全屏辅助类 - 支持重力感应旋转
 * 
 * 核心思路：
 * 1. 将 OrangevideoView 移动到 DecorView，实现全屏效果
 * 2. 通过隐藏系统 UI 实现沉浸式全屏
 * 3. 使用 OrientationEventListener 监听设备方向，实现重力感应旋转
 */
public class CustomFullscreenHelper {
    
    private static final String TAG = "CustomFullscreenHelper";
    
    private OrangevideoView mVideoView;
    private boolean mIsFullscreen = false;
    private int mOriginalSystemUiVisibility = 0;
    
    // 保存原始布局参数和父容器
    private ViewGroup.LayoutParams mOriginalLayoutParams;
    private ViewGroup mOriginalParent;
    private int mOriginalIndex;
    
    // 全屏背景遮罩
    private View mFullscreenBackground;
    
    // 兼容旧接口
    private long mPendingSeekPosition = 0;
    private boolean mPendingResume = false;
    
    // 全屏切换中标志
    private boolean mFullscreenTransitioning = false;
    
    // 自动旋转设置（基于重力感应）
    private boolean mAutoRotateEnabled = true;
    private OrientationEventListener mOrientationListener;
    private int mCurrentOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    private boolean mIsLandscape = true;  // 当前是否横屏
    
    public CustomFullscreenHelper(OrangevideoView videoView) {
        this.mVideoView = videoView;
        initOrientationListener();
    }
    
    /**
     * 初始化方向监听器
     */
    private void initOrientationListener() {
        if (mVideoView == null || mVideoView.getContext() == null) {
            return;
        }
        
        mOrientationListener = new OrientationEventListener(mVideoView.getContext()) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (!mAutoRotateEnabled || !mIsFullscreen || mFullscreenTransitioning) {
                    return;
                }
                
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                    return;
                }
                
                Activity activity = mVideoView.getActivity();
                if (activity == null || activity.isFinishing()) {
                    return;
                }
                
                // 全屏模式下，只在横屏的两个方向之间切换
                // 忽略竖屏方向（0度和180度），防止误退出全屏
                int newOrientation;
                
                if (orientation > 45 && orientation <= 135) {
                    // 反横屏 (90度) - 设备顺时针旋转90度
                    newOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                } else if (orientation > 225 && orientation <= 315) {
                    // 正横屏 (270度) - 设备逆时针旋转90度
                    newOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                } else {
                    // 竖屏方向（0度附近或180度附近）- 忽略，保持当前横屏方向
                    android.util.Log.d(TAG, "onOrientationChanged: 忽略竖屏方向 orientation=" + orientation);
                    return;
                }
                
                // 只有方向真正改变时才更新
                if (newOrientation != mCurrentOrientation) {
                    mCurrentOrientation = newOrientation;
                    activity.setRequestedOrientation(newOrientation);
                    android.util.Log.d(TAG, "onOrientationChanged: orientation=" + orientation + 
                        ", newOrientation=" + getOrientationName(newOrientation));
                }
            }
        };
    }
    
    /**
     * 获取方向名称（用于日志）
     */
    private String getOrientationName(int orientation) {
        switch (orientation) {
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                return "PORTRAIT";
            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                return "LANDSCAPE";
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                return "REVERSE_PORTRAIT";
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                return "REVERSE_LANDSCAPE";
            default:
                return "UNKNOWN";
        }
    }
    
    /**
     * 启动方向监听
     */
    private void startOrientationListener() {
        if (mOrientationListener != null && mOrientationListener.canDetectOrientation()) {
            mOrientationListener.enable();
            android.util.Log.d(TAG, "startOrientationListener: enabled");
        }
    }
    
    /**
     * 停止方向监听
     */
    private void stopOrientationListener() {
        if (mOrientationListener != null) {
            mOrientationListener.disable();
            android.util.Log.d(TAG, "stopOrientationListener: disabled");
        }
    }
    
    /**
     * 设置是否启用自动旋转（基于重力感应）
     * @param enabled true 启用自动旋转
     */
    public void setAutoRotateEnabled(boolean enabled) {
        mAutoRotateEnabled = enabled;
        if (mIsFullscreen) {
            if (enabled) {
                startOrientationListener();
            } else {
                stopOrientationListener();
            }
        }
    }
    
    /**
     * 是否启用自动旋转
     */
    public boolean isAutoRotateEnabled() {
        return mAutoRotateEnabled;
    }
    
    // 兼容旧接口的方法
    public long getPendingSeekPosition() {
        return mPendingSeekPosition;
    }
    
    public void clearPendingSeekPosition() {
        mPendingSeekPosition = 0;
        mPendingResume = false;
    }
    
    public boolean isPendingResume() {
        return mPendingResume;
    }
    
    /**
     * 进入全屏模式
     * 
     * 关键改进：先移动 View 到 DecorView，再旋转屏幕
     * 这样 View 已经在 DecorView 中，旋转时不会触发 Surface 销毁
     * 
     * SystemPlayerManager 特殊处理：暂停播放，切换后恢复
     */
    public void startFullScreen() {
        android.util.Log.d(TAG, "startFullScreen: called");
        
        if (mIsFullscreen || mVideoView == null) {
            android.util.Log.d(TAG, "startFullScreen: already fullscreen or videoView is null");
            return;
        }
        
        Activity activity = mVideoView.getActivity();
        if (activity == null || activity.isFinishing()) {
            android.util.Log.d(TAG, "startFullScreen: activity is null or finishing");
            return;
        }
        
        final ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        if (decorView == null) {
            android.util.Log.d(TAG, "startFullScreen: decorView is null");
            return;
        }
        
        // 调试日志：记录全屏切换前的状态
        android.util.Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        android.util.Log.d(TAG, "startFullScreen: 开始进入全屏");
        android.util.Log.d(TAG, "  VideoView: @" + Integer.toHexString(mVideoView.hashCode()));
        android.util.Log.d(TAG, "  VodControlView: " + (mVideoView.getVodControlView() != null ? 
            "@" + Integer.toHexString(mVideoView.getVodControlView().hashCode()) : "null"));
        android.util.Log.d(TAG, "  TitleView: " + (mVideoView.getTitleView() != null ? 
            "@" + Integer.toHexString(mVideoView.getTitleView().hashCode()) : "null"));
        
        // OCR 全屏切换处理：先暂停 OCR 并切换到 SurfaceView
        android.util.Log.d(TAG, "startFullScreen: calling pauseOcrIfRunning");
        pauseOcrIfRunning();
        
        mIsFullscreen = true;
        mIsPortraitFullscreen = false;  // 横屏全屏模式，清除竖屏全屏标志
        mFullscreenTransitioning = true;
        mOriginalSystemUiVisibility = decorView.getSystemUiVisibility();
        
        // 直接执行全屏切换，不再对系统播放器做特殊处理
        // enableMediaCodecTexture() 已修复横竖屏切换时 SurfaceTexture 保留问题
        mVideoView.post(new Runnable() {
            @Override
            public void run() {
                // 1. 先保存原始父容器和布局参数
                mOriginalParent = (ViewGroup) mVideoView.getParent();
                if (mOriginalParent != null) {
                    mOriginalIndex = mOriginalParent.indexOfChild(mVideoView);
                    mOriginalLayoutParams = mVideoView.getLayoutParams();
                    
                    android.util.Log.d(TAG, "startFullScreen: 保存原始 LayoutParams: " + 
                        mOriginalLayoutParams.width + "x" + mOriginalLayoutParams.height);
                    android.util.Log.d(TAG, "startFullScreen: mVideoView 当前尺寸: " + 
                        mVideoView.getWidth() + "x" + mVideoView.getHeight());
                    
                    // 从原始父容器移除
                    mOriginalParent.removeView(mVideoView);
                }
                
                // 2. 添加全屏黑色背景到 DecorView
                mFullscreenBackground = new View(activity);
                mFullscreenBackground.setBackgroundColor(Color.BLACK);
                FrameLayout.LayoutParams bgParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                );
                decorView.addView(mFullscreenBackground, bgParams);
                
                // 3. 将 OrangevideoView 添加到 DecorView
                FrameLayout.LayoutParams fullParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                );
                decorView.addView(mVideoView, fullParams);
                
                // 4. 隐藏系统 UI
                hideSysBar(decorView, activity);
                
                // 5. 最后设置屏幕方向 - View 已经在 DecorView 中，旋转不会影响它
                // 默认先设置横屏，如果启用了自动旋转，后续会根据重力感应调整
                mCurrentOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                activity.setRequestedOrientation(mCurrentOrientation);
                
                // 6. 通知状态变化
                mVideoView.setOrangePlayerState(PlayerConstants.PLAYER_FULL_SCREEN);
                
                // 调试日志：记录全屏切换后的状态
                android.util.Log.d(TAG, "startFullScreen: 全屏切换完成");
                android.util.Log.d(TAG, "  VideoView: @" + Integer.toHexString(mVideoView.hashCode()));
                android.util.Log.d(TAG, "  VodControlView: " + (mVideoView.getVodControlView() != null ? 
                    "@" + Integer.toHexString(mVideoView.getVodControlView().hashCode()) : "null"));
                android.util.Log.d(TAG, "  TitleView: " + (mVideoView.getTitleView() != null ? 
                    "@" + Integer.toHexString(mVideoView.getTitleView().hashCode()) : "null"));
                android.util.Log.d(TAG, "  ⚠️ 检查：组件实例是否与进入全屏前一致？");
                android.util.Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                
                if (mVideoView.getTitleView() != null) {
                    mVideoView.getTitleView().setVisibility(View.VISIBLE);
                }
                if (mVideoView.getVodControlView() != null) {
                    mVideoView.getVodControlView().onPlayerStateChanged(PlayerConstants.PLAYER_FULL_SCREEN);
                }
                
                // 7. 关键：触发渲染视图重新布局
                // 移动 View 后，内部的 TextureView/SurfaceView 需要重新测量尺寸
                mVideoView.requestLayout();
                
                // 8. 延迟再次触发布局，确保旋转完成后尺寸正确
                mVideoView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // 再次请求布局，确保渲染视图尺寸正确
                        mVideoView.requestLayout();
                        
                        // 通知渲染视图重新布局
                        if (mVideoView.getRenderProxy() != null) {
                            mVideoView.getRenderProxy().requestLayout();
                        }
                        
                        // ExoPlayer 特殊处理：更新 SurfaceControl 尺寸
                        updateExoSurfaceControlSize();
                    }
                }, 100);
                
                // 9. 延迟重置标志，等待旋转完成
                mVideoView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mFullscreenTransitioning = false;

                        // mpv 绑定漂移检测：Android 16 旋转时框架静默换新
                        // SurfaceTexture（view 不销毁、无回调），mpv 仍绑旧
                        // SurfaceTexture → 全屏黑屏。切换完成后比对重绑。
                        mVideoView.checkMpvSurfaceDrift();

                        // 最终确保渲染视图尺寸正确
                        mVideoView.requestLayout();
                        if (mVideoView.getRenderProxy() != null) {
                            mVideoView.getRenderProxy().requestLayout();
                        }

                        // ExoPlayer 特殊处理：再次更新 SurfaceControl 尺寸
                        updateExoSurfaceControlSize();

                        // 启动重力感应旋转监听（如果启用）
                        if (mAutoRotateEnabled) {
                            startOrientationListener();
                        }
                    }
                }, 800);
                
                // 10. 额外延迟更新，确保屏幕旋转完全完成后尺寸正确
                mVideoView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        updateExoSurfaceControlSize();
                    }
                }, 1200);
            }
        });
    }
    
    /**
     * 将播放器移动到全屏位置 - 已废弃，合并到 startFullScreen
     */
    private void moveToFullscreen(Activity activity, ViewGroup decorView) {
        // 已合并到 startFullScreen
    }
    
    /**
     * 退出全屏模式
     * 
     * 关键改进：先设置竖屏，等待旋转完成后再移动 View
     * SystemPlayerManager 特殊处理：暂停播放，切换后恢复
     */
    public void stopFullScreen() {
        android.util.Log.d(TAG, "stopFullScreen: called");
        
        if (!mIsFullscreen || mVideoView == null) {
            android.util.Log.d(TAG, "stopFullScreen: not fullscreen or videoView is null");
            return;
        }
        
        Activity activity = mVideoView.getActivity();
        if (activity == null || activity.isFinishing()) {
            android.util.Log.d(TAG, "stopFullScreen: activity is null or finishing");
            return;
        }
        
        final ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        if (decorView == null) {
            android.util.Log.d(TAG, "stopFullScreen: decorView is null");
            return;
        }
        
        // 记录屏幕尺寸信息
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        android.util.Log.d(TAG, "stopFullScreen: 屏幕尺寸 widthPixels=" + dm.widthPixels + ", heightPixels=" + dm.heightPixels);
        android.util.Log.d(TAG, "stopFullScreen: DecorView 尺寸=" + decorView.getWidth() + "x" + decorView.getHeight());
        
        // 检查导航栏高度
        int navigationBarHeight = 0;
        int resourceId = activity.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            navigationBarHeight = activity.getResources().getDimensionPixelSize(resourceId);
            android.util.Log.d(TAG, "stopFullScreen: 导航栏高度=" + navigationBarHeight);
        }
        
        // OCR 全屏切换处理：先暂停 OCR 并切换到 SurfaceView
        android.util.Log.d(TAG, "stopFullScreen: calling pauseOcrIfRunning");
        pauseOcrIfRunning();
        
        mIsFullscreen = false;
        mFullscreenTransitioning = true;
        
        // 停止重力感应旋转监听
        stopOrientationListener();
        
        // 直接执行退出全屏，不再对系统播放器做特殊处理
        // enableMediaCodecTexture() 已修复横竖屏切换时 SurfaceTexture 保留问题
        mVideoView.post(new Runnable() {
            @Override
            public void run() {
                // 1. 显示系统 UI
                showSysBar(decorView, activity);
                
                // 2. 先设置竖屏
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                
                // 3. 从 DecorView 移除 OrangevideoView
                decorView.removeView(mVideoView);
                
                // 4. 移除全屏背景
                if (mFullscreenBackground != null) {
                    decorView.removeView(mFullscreenBackground);
                    mFullscreenBackground = null;
                }
                
                // 5. 恢复到原始父容器
                if (mOriginalParent != null && mOriginalLayoutParams != null) {
                    android.util.Log.d(TAG, "stopFullScreen: 恢复原始 LayoutParams: " + 
                        mOriginalLayoutParams.width + "x" + mOriginalLayoutParams.height);
                    mOriginalParent.addView(mVideoView, mOriginalIndex, mOriginalLayoutParams);
                    
                    // 关键修复：强制重新测量和布局
                    // 因为根布局有 fitsSystemWindows=true，系统 UI 恢复后布局会变化
                    // 需要强制 mVideoView 重新计算尺寸
                    mVideoView.post(new Runnable() {
                        @Override
                        public void run() {
                            // 强制父容器重新布局
                            if (mOriginalParent != null) {
                                mOriginalParent.requestLayout();
                            }
                            
                            // 强制 mVideoView 重新测量
                            mVideoView.requestLayout();
                            
                            android.util.Log.d(TAG, "stopFullScreen: 恢复后 mVideoView size=" + 
                                mVideoView.getWidth() + "x" + mVideoView.getHeight());
                            android.util.Log.d(TAG, "stopFullScreen: 恢复后 LayoutParams=" + 
                                mVideoView.getLayoutParams().width + "x" + mVideoView.getLayoutParams().height);
                        }
                    });
                }
                
                // 6. 通知状态变化
                mVideoView.setOrangePlayerState(PlayerConstants.PLAYER_NORMAL);
                
                if (mVideoView.getTitleView() != null) {
                    mVideoView.getTitleView().setVisibility(View.GONE);
                }
                if (mVideoView.getVodControlView() != null) {
                    mVideoView.getVodControlView().onPlayerStateChanged(PlayerConstants.PLAYER_NORMAL);
                }
                
                // 7. 触发渲染视图重新布局
                android.util.Log.d(TAG, "stopFullScreen: 步骤7 - 触发渲染视图重新布局");
                android.util.Log.d(TAG, "stopFullScreen: mVideoView size=" + mVideoView.getWidth() + "x" + mVideoView.getHeight());
                if (mVideoView.getRenderProxy() != null) {
                    android.util.Log.d(TAG, "stopFullScreen: RenderProxy size=" + 
                        mVideoView.getRenderProxy().getWidth() + "x" + mVideoView.getRenderProxy().getHeight());
                }
                mVideoView.requestLayout();
                
                // 8. 延迟再次触发布局
                mVideoView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        android.util.Log.d(TAG, "stopFullScreen: 步骤8 (100ms延迟) - 再次触发布局");
                        android.util.Log.d(TAG, "stopFullScreen: mVideoView size=" + mVideoView.getWidth() + "x" + mVideoView.getHeight());
                        if (mVideoView.getRenderProxy() != null) {
                            android.util.Log.d(TAG, "stopFullScreen: RenderProxy size=" + 
                                mVideoView.getRenderProxy().getWidth() + "x" + mVideoView.getRenderProxy().getHeight());
                        }
                        
                        mVideoView.requestLayout();
                        if (mVideoView.getRenderProxy() != null) {
                            mVideoView.getRenderProxy().requestLayout();
                        }
                        updateExoSurfaceControlSize();
                    }
                }, 100);
                
                // 9. 延迟重置标志
                mVideoView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mFullscreenTransitioning = false;

                        // mpv 绑定漂移检测（同 startFullScreen，覆盖退出全屏方向）
                        mVideoView.checkMpvSurfaceDrift();

                        // 最终确保渲染视图尺寸正确
                        mVideoView.requestLayout();
                        if (mVideoView.getRenderProxy() != null) {
                            mVideoView.getRenderProxy().requestLayout();
                        }
                        updateExoSurfaceControlSize();
                    }
                }, 800);
            }
        });
    }

    public void enterFullscreen(Activity activity) {
        startFullScreen();
    }
    
    public void exitFullscreen(Activity activity) {
        stopFullScreen();
    }
    
    private void hideSysBar(ViewGroup decorView, Activity activity) {
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        
        decorView.setSystemUiVisibility(uiOptions);
        activity.getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
    }
    
    private void showSysBar(ViewGroup decorView, Activity activity) {
        decorView.setSystemUiVisibility(mOriginalSystemUiVisibility);
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }
    
    public boolean isFullscreen() {
        return mIsFullscreen;
    }
    
    /**
     * 是否正在进行全屏切换
     */
    public boolean isFullscreenTransitioning() {
        return mFullscreenTransitioning;
    }
    
    /**
     * 是否竖屏全屏
     */
    public boolean isPortraitFullscreen() {
        return mIsPortraitFullscreen;
    }
    
    // 竖屏全屏标志
    private boolean mIsPortraitFullscreen = false;
    
    /**
     * 进入竖屏全屏模式
     * 不旋转屏幕，只是将播放器移动到 DecorView 并全屏显示
     */
    public void startPortraitFullScreen() {
        if (mIsFullscreen || mVideoView == null) {
            return;
        }
        
        final Activity activity = mVideoView.getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        
        final ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        if (decorView == null) {
            return;
        }
        
        long currentPosition = mVideoView.getCurrentPositionWhenPlaying();
        mIsFullscreen = true;
        mIsPortraitFullscreen = true;
        mFullscreenTransitioning = true;
        mOriginalSystemUiVisibility = decorView.getSystemUiVisibility();
        
        // 使用 post 避免在主线程阻塞（修复 ANR）
        mVideoView.post(new Runnable() {
            @Override
            public void run() {
                // 1. 保存原始父容器和布局参数
                mOriginalParent = (ViewGroup) mVideoView.getParent();
                if (mOriginalParent != null) {
                    mOriginalIndex = mOriginalParent.indexOfChild(mVideoView);
                    mOriginalLayoutParams = mVideoView.getLayoutParams();
                    mOriginalParent.removeView(mVideoView);
                }
                
                // 2. 添加全屏黑色背景
                mFullscreenBackground = new View(activity);
                mFullscreenBackground.setBackgroundColor(Color.BLACK);
                FrameLayout.LayoutParams bgParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                );
                decorView.addView(mFullscreenBackground, bgParams);
                
                // 3. 将播放器添加到 DecorView
                FrameLayout.LayoutParams fullParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                );
                decorView.addView(mVideoView, fullParams);
                
                // 4. 隐藏系统 UI
                hideSysBar(decorView, activity);
                
                // 5. 锁定竖屏方向（不旋转）
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                
                // 6. 通知状态变化
                mVideoView.setOrangePlayerState(PlayerConstants.PLAYER_FULL_SCREEN);
                
                if (mVideoView.getTitleView() != null) {
                    mVideoView.getTitleView().setVisibility(View.VISIBLE);
                }
                if (mVideoView.getVodControlView() != null) {
                    mVideoView.getVodControlView().onPlayerStateChanged(PlayerConstants.PLAYER_FULL_SCREEN);
                }
                
                // 7. 延迟重置标志
                mVideoView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mFullscreenTransitioning = false;

                        // mpv 绑定漂移检测（View 搬家路径也可能触发
                        // TextureView SurfaceTexture 换新，同 startFullScreen）
                        mVideoView.checkMpvSurfaceDrift();
                    }
                }, 500);
            }
        });
    }
    
    /**
     * 退出竖屏全屏模式
     */
    public void stopPortraitFullScreen() {
        if (!mIsFullscreen || !mIsPortraitFullscreen || mVideoView == null) {
            return;
        }
        
        Activity activity = mVideoView.getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        
        final ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        if (decorView == null) {
            return;
        }
        
        long currentPosition = mVideoView.getCurrentPositionWhenPlaying();
        mIsFullscreen = false;
        mIsPortraitFullscreen = false;
        mFullscreenTransitioning = true;
        
        // 使用 post 避免在主线程阻塞（修复 ANR）
        mVideoView.post(new Runnable() {
            @Override
            public void run() {
                // 1. 显示系统 UI
                showSysBar(decorView, activity);
                
                // 2. 从 DecorView 移除播放器
                decorView.removeView(mVideoView);
                
                // 3. 移除全屏背景
                if (mFullscreenBackground != null) {
                    decorView.removeView(mFullscreenBackground);
                    mFullscreenBackground = null;
                }
                
                // 4. 恢复到原始父容器
                if (mOriginalParent != null && mOriginalLayoutParams != null) {
                    mOriginalParent.addView(mVideoView, mOriginalIndex, mOriginalLayoutParams);
                }
                
                // 5. 通知状态变化
                mVideoView.setOrangePlayerState(PlayerConstants.PLAYER_NORMAL);
                
                if (mVideoView.getTitleView() != null) {
                    mVideoView.getTitleView().setVisibility(View.GONE);
                }
                if (mVideoView.getVodControlView() != null) {
                    mVideoView.getVodControlView().onPlayerStateChanged(PlayerConstants.PLAYER_NORMAL);
                }
                
                // 6. 延迟重置标志
                mVideoView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mFullscreenTransitioning = false;

                        // mpv 绑定漂移检测（退出竖屏全屏路径）
                        mVideoView.checkMpvSurfaceDrift();
                    }
                }, 500);
            }
        });
    }

    /**
     * 检查是否使用 SystemPlayerManager 或 OrangeSystemPlayerManager
     */
    private boolean isUsingSystemPlayer() {
        try {
            com.shuyu.gsyvideoplayer.player.IPlayerManager playerManager = 
                com.shuyu.gsyvideoplayer.GSYVideoManager.instance().getPlayer();
            if (playerManager != null) {
                String className = playerManager.getClass().getSimpleName();
                return "SystemPlayerManager".equals(className) || 
                       "OrangeSystemPlayerManager".equals(className) ||
                       playerManager instanceof OrangeSystemPlayerManager;
            }
        } catch (Exception e) {
        }
        return false;
    }
    
    /**
     * 更新 SurfaceControl 尺寸
     * 解决全屏切换后画面只显示一小块的问题
     * 支持 ExoPlayer 和系统播放器
     */
    private void updateExoSurfaceControlSize() {
        if (mVideoView == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        
        try {
            // 获取当前的 PlayerManager
            com.shuyu.gsyvideoplayer.player.IPlayerManager playerManager = 
                GSYVideoManager.instance().getPlayer();
            
            // 获取当前的 SurfaceView
            SurfaceView surfaceView = null;
            if (mVideoView.getRenderProxy() != null && 
                mVideoView.getRenderProxy().getShowView() instanceof SurfaceView) {
                surfaceView = (SurfaceView) mVideoView.getRenderProxy().getShowView();
            }
            
            if (surfaceView == null) {
                return;
            }
            
            // ExoPlayer
            if (playerManager instanceof OrangeExoPlayerManager) {
                OrangeExoPlayerManager exoManager = (OrangeExoPlayerManager) playerManager;
                exoManager.updateSurfaceControlSize(surfaceView);
            }
            // 系统播放器
            else if (playerManager instanceof OrangeSystemPlayerManager) {
                OrangeSystemPlayerManager systemManager = (OrangeSystemPlayerManager) playerManager;
                systemManager.updateSurfaceControlSize(surfaceView);
            }
        } catch (Exception e) {
        }
    }
    
    public void toggleFullScreen() {
        if (mIsFullscreen) {
            stopFullScreen();
        } else {
            startFullScreen();
        }
    }
    
    /**
     * 如果 OCR 正在运行，暂停 OCR 并切换到 SurfaceView 模式
     * 用于全屏切换前调用，避免 TextureView 模式下屏幕旋转导致崩溃
     */
    private void pauseOcrIfRunning() {
        android.util.Log.d(TAG, "pauseOcrIfRunning: called, mVideoView=" + mVideoView);
        
        if (mVideoView == null) {
            android.util.Log.w(TAG, "pauseOcrIfRunning: mVideoView is null");
            return;
        }
        
        try {
            // 获取 VideoEventManager
            OrangeVideoController controller = mVideoView.getVideoController();
            android.util.Log.d(TAG, "pauseOcrIfRunning: controller=" + controller);
            
            if (controller != null) {
                VideoEventManager eventManager = controller.getVideoEventManager();
                android.util.Log.d(TAG, "pauseOcrIfRunning: eventManager=" + eventManager);
                
                if (eventManager != null) {
                    boolean ocrRunning = eventManager.isOcrRunning();
                    android.util.Log.d(TAG, "pauseOcrIfRunning: ocrRunning=" + ocrRunning);
                    
                    if (ocrRunning) {
                        android.util.Log.d(TAG, "pauseOcrIfRunning: OCR 正在运行，暂停并切换到 SurfaceView");
                        eventManager.pauseOcrForFullscreenSwitch();
                    }
                } else {
                    android.util.Log.w(TAG, "pauseOcrIfRunning: eventManager is null");
                }
            } else {
                android.util.Log.w(TAG, "pauseOcrIfRunning: controller is null");
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error pausing OCR for fullscreen switch", e);
        }
    }
    
    // 兼容旧接口
    public void initPlayerContainer() {
    }
    
    public FrameLayout getPlayerContainer() {
        return null;
    }
    
    public boolean isContainerInitialized() {
        return true;
    }
}
