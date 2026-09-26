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
 * <p><b>全屏状态机（P1）</b>：对外只暴露 {@link #getMode()} 作为唯一真值，以及幂等的
 * {@link #request(int)} 作为唯一入口。旧入口 {@code startFullScreen()} / {@code stopFullScreen()} /
 * {@code startPortraitFullScreen()} / {@code stopPortraitFullScreen()} 保留为 {@code request} 的薄包装，
 * 兼容既有调用方。</p>
 *
 * <p><b>零 reparent（P2）</b>：若宿主通过 {@link OrangevideoView#setFullscreenContainer(FrameLayout)}
 * 提供了全屏容器，则进入全屏时只把播放器移入该容器（脱离 DecorView），退出时还原到原父容器。
 * 宿主未提供容器时，回退到旧的 DecorView 搬运路径（由 P1 的幂等状态机保护，不再错乱）。</p>
 *
 * <p>使用 OrientationEventListener 监听设备方向，实现重力感应旋转。</p>
 */
public class CustomFullscreenHelper {

    private static final String TAG = "CustomFullscreenHelper";

    /**
     * 全屏切换收尾的兜底延时（P3）。
     *
     * <p>正常路径下收尾由宿主 {@code onConfigurationChanged} 转发触发（见 {@link #onConfigurationChanged()}），
     * 立即完成；本延时的作用是：宿主未转发配置变化时，保证 {@code mFullscreenTransitioning}
     * 最终会被重置，不会永久卡在「切换中」而吞掉后续的全屏请求。</p>
     */
    private static final long TRANSITION_FALLBACK_MS = 1000;

    /**
     * 收尾兜底任务。持有单一实例以便在每次切换时先 {@code removeCallbacks}，
     * 避免上一轮遗留的定时器在本次过渡中提前触发、过早解除 Surface 过渡保护。
     */
    private final Runnable mFinalizeRunnable = new Runnable() {
        @Override
        public void run() {
            finalizeFullscreenTransition();
        }
    };

    private OrangevideoView mVideoView;

    /**
     * 全屏模式唯一真值。取值见 {@link FullscreenMode}。
     * 取代原先的 mIsFullscreen + mIsPortraitFullscreen 两个布尔标志。
     */
    private int mMode = FullscreenMode.NORMAL;

    private int mOriginalSystemUiVisibility = 0;

    // 保存原始布局参数和父容器
    private ViewGroup.LayoutParams mOriginalLayoutParams;
    private ViewGroup mOriginalParent;
    private int mOriginalIndex;

    // 全屏背景遮罩（仅 DecorView 回退路径使用）
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

    /** 设备物理方向是否为横置。随重力感应持续更新，用于提示「当前手持方向可切到哪种全屏」。 */
    private boolean mDeviceLandscape = true;

    /** 物理方向变化监听者（提示用）。可能有多处（控制器布局 / VodControlView 布局各有一个按钮）。 */
    private final java.util.List<Runnable> mDeviceOrientationListeners = new java.util.ArrayList<>();

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
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                    return;
                }

                // 1) 持续记录物理方向（与是否自动旋转无关），供「切换方向提示」使用。
                //    横置区间取 [45,135) ∪ [225,315)，其余视为竖置。
                boolean landscapeNow = !((orientation > 135 && orientation <= 225)
                        || orientation <= 45 || orientation > 315);
                if (landscapeNow != mDeviceLandscape) {
                    mDeviceLandscape = landscapeNow;
                    notifyDeviceOrientationChanged();
                }

                // 2) 自动旋转：仅横屏全屏、且非过渡期时，在正/反横屏之间跟随
                if (!mAutoRotateEnabled
                        || mMode != FullscreenMode.LANDSCAPE_FULLSCREEN
                        || mFullscreenTransitioning) {
                    return;
                }

                Activity activity = mVideoView.getActivity();
                if (activity == null || activity.isFinishing()) {
                    return;
                }

                int newOrientation;
                if (orientation > 45 && orientation <= 135) {
                    // 反横屏 (90度) - 设备顺时针旋转90度
                    newOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                } else if (orientation > 225 && orientation <= 315) {
                    // 正横屏 (270度) - 设备逆时针旋转90度
                    newOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                } else {
                    // 竖屏方向（0度附近或180度附近）- 忽略，保持当前横屏方向
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
     * 设备物理方向是否横置（重力感应实时值）。
     */
    public boolean isDeviceLandscape() {
        return mDeviceLandscape;
    }

    /**
     * 设置物理方向变化监听（提示用）。
     *
     * <p>由控制器注册，在用户转动手机时被调用，用于弹出「按当前手持方向切换全屏」的提示。
     * 仅在未启用自动旋转时有意义——自动旋转时屏幕已跟随物理方向，无需提示。</p>
     */
    public void setDeviceOrientationListener(Runnable listener) {
        if (listener != null && !mDeviceOrientationListeners.contains(listener)) {
            mDeviceOrientationListeners.add(listener);
        }
    }

    private void notifyDeviceOrientationChanged() {
        dispatchDeviceOrientationChanged();
    }

    /**
     * 向所有已注册的监听者分发「设备物理方向变化」事件。
     *
     * <p>正常由重力感应触发；也可由宿主在自行管理方向时主动调用。
     * 用于让各处的「切换全屏方向」提示按钮按当前手持方向刷新。</p>
     */
    public void dispatchDeviceOrientationChanged() {
        for (Runnable listener : mDeviceOrientationListeners) {
            listener.run();
        }
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
        // 监听器生命周期由全屏会话统一管理（进入时启动、退出时停止），
        // 因为物理方向现在也用于「切换方向提示」，与是否自动旋转无关。
        mAutoRotateEnabled = enabled;
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
        request(FullscreenMode.LANDSCAPE_FULLSCREEN);
    }

    /**
     * 全屏状态切换的唯一入口（幂等）。
     *
     * <p>已处于目标模式时直接返回，不产生任何副作用，因此调用方可以无条件调用，
     * 不需要自己判断当前状态。这是「手势意图式调用」与「UI 图标方向」不打架的前提。</p>
     *
     * @param targetMode {@link FullscreenMode#NORMAL} /
     *                   {@link FullscreenMode#LANDSCAPE_FULLSCREEN} /
     *                   {@link FullscreenMode#PORTRAIT_FULLSCREEN}
     */
    public void request(int targetMode) {
        if (targetMode != FullscreenMode.NORMAL
                && targetMode != FullscreenMode.LANDSCAPE_FULLSCREEN
                && targetMode != FullscreenMode.PORTRAIT_FULLSCREEN) {
            // 拒绝非法入参，避免误落进 enterLandscape()
            android.util.Log.w(TAG, "request: 非法目标模式 " + targetMode + "，忽略");
            return;
        }
        android.util.Log.d(TAG, "request: target=" + modeName(targetMode) + ", current=" + modeName(mMode));
        if (targetMode == mMode) {
            android.util.Log.d(TAG, "request: 已处于目标模式，忽略（幂等）");
            return;
        }
        if (mVideoView == null) {
            return;
        }
        if (targetMode == FullscreenMode.NORMAL) {
            exitToNormal();
        } else if (targetMode == FullscreenMode.PORTRAIT_FULLSCREEN) {
            enterPortrait();
        } else {
            enterLandscape();
        }
    }

    /** 供日志使用的模式名。 */
    private static String modeName(int mode) {
        switch (mode) {
            case FullscreenMode.NORMAL:
                return "NORMAL";
            case FullscreenMode.LANDSCAPE_FULLSCREEN:
                return "LANDSCAPE_FULLSCREEN";
            case FullscreenMode.PORTRAIT_FULLSCREEN:
                return "PORTRAIT_FULLSCREEN";
            default:
                return "UNKNOWN(" + mode + ")";
        }
    }

    /**
     * 进入横屏全屏。
     */
    private void enterLandscape() {
        android.util.Log.d(TAG, "enterLandscape: called");

        Activity activity = mVideoView.getActivity();
        if (activity == null || activity.isFinishing()) {
            android.util.Log.d(TAG, "enterLandscape: activity is null or finishing");
            return;
        }

        final ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        if (decorView == null) {
            android.util.Log.d(TAG, "enterLandscape: decorView is null");
            return;
        }

        android.util.Log.d(TAG, "  VideoView: @" + Integer.toHexString(mVideoView.hashCode()));

        // OCR 全屏切换处理：先暂停 OCR 并切换到 SurfaceView
        pauseOcrIfRunning();

        // 只在 NORMAL → 全屏时保存原始位置与系统栏状态。
        // 跨全屏模式切换（横屏全屏 ↔ 竖屏全屏）必须复用首次进入时保存的值，
        // 否则会把「全屏容器」或「已隐藏系统栏」的状态误当作原始状态保存，
        // 导致退出时播放器被放回错误容器、系统栏无法恢复。
        if (mMode == FullscreenMode.NORMAL) {
            saveOriginalPosition();
            mOriginalSystemUiVisibility = decorView.getSystemUiVisibility();
        }

        mMode = FullscreenMode.LANDSCAPE_FULLSCREEN;
        mFullscreenTransitioning = true;
        // 维护 GSY 的全屏标志：TV 的 BACK 键、解锁恢复标题栏、backFromFull 都读它
        mVideoView.setIfCurrentIsFullscreenInternal(true);

        mVideoView.post(new Runnable() {
            @Override
            public void run() {
                // 1. 移入全屏容器（容器契约）或回退到 DecorView 搬运
                moveIntoFullscreen(activity, decorView);

                // 2. 隐藏系统 UI
                hideSysBar(decorView, activity);

                // 3. 设置屏幕方向（默认横屏，启用自动旋转后由重力感应调整）
                mCurrentOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                activity.setRequestedOrientation(mCurrentOrientation);

                // 4. 通知状态变化
                mVideoView.setOrangePlayerState(PlayerConstants.PLAYER_FULL_SCREEN);

                if (mVideoView.getTitleView() != null) {
                    mVideoView.getTitleView().setVisibility(View.VISIBLE);
                }
                if (mVideoView.getVodControlView() != null) {
                    mVideoView.getVodControlView().onPlayerStateChanged(PlayerConstants.PLAYER_FULL_SCREEN);
                }

                // 5. 触发渲染视图重新布局（尺寸变化后需要重新测量）
                mVideoView.requestLayout();

                // 6. 延迟补一次布局：等待尺寸稳定（与旋转完成无关，保留短延时）
                mVideoView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mVideoView.requestLayout();
                        if (mVideoView.getRenderProxy() != null) {
                            mVideoView.getRenderProxy().requestLayout();
                        }
                        updateExoSurfaceControlSize();
                    }
                }, 100);

                // 7. 收尾：优先由宿主 onConfigurationChanged 转发（见 onConfigurationChanged）。
                //    这里注册兜底定时器，保证宿主未转发时也能收敛，不会卡在 transitioning。
                mVideoView.postDelayed(mFinalizeRunnable, TRANSITION_FALLBACK_MS);
            }
        });
    }

    /**
     * 进入竖屏全屏（不旋转屏幕，仅铺满竖屏）。
     */
    private void enterPortrait() {
        android.util.Log.d(TAG, "enterPortrait: called");

        final Activity activity = mVideoView.getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }

        final ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        if (decorView == null) {
            return;
        }

        pauseOcrIfRunning();

        // 同 enterLandscape：只在 NORMAL → 全屏时保存原始位置与系统栏状态
        if (mMode == FullscreenMode.NORMAL) {
            saveOriginalPosition();
            mOriginalSystemUiVisibility = decorView.getSystemUiVisibility();
        }

        mMode = FullscreenMode.PORTRAIT_FULLSCREEN;
        mFullscreenTransitioning = true;
        mVideoView.setIfCurrentIsFullscreenInternal(true);

        mVideoView.post(new Runnable() {
            @Override
            public void run() {
                // 1. 移入全屏容器（容器契约）或回退到 DecorView 搬运
                moveIntoFullscreen(activity, decorView);

                // 2. 隐藏系统 UI
                hideSysBar(decorView, activity);

                // 3. 锁定竖屏方向（不旋转）
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

                // 4. 通知状态变化
                mVideoView.setOrangePlayerState(PlayerConstants.PLAYER_FULL_SCREEN);

                if (mVideoView.getTitleView() != null) {
                    mVideoView.getTitleView().setVisibility(View.VISIBLE);
                }
                if (mVideoView.getVodControlView() != null) {
                    mVideoView.getVodControlView().onPlayerStateChanged(PlayerConstants.PLAYER_FULL_SCREEN);
                }

                // 5. 触发渲染视图重新布局（与 enterLandscape 对齐，避免竖屏全屏后短暂错尺寸）
                mVideoView.requestLayout();

                // 6. 收尾：优先由宿主 onConfigurationChanged 转发，兜底定时器保证收敛
                mVideoView.postDelayed(mFinalizeRunnable, TRANSITION_FALLBACK_MS);
            }
        });
    }

    /**
     * 保存「进入全屏前」的原始父容器、索引与布局参数。
     *
     * <p>只在 {@link FullscreenMode#NORMAL} → 全屏时调用；跨全屏模式切换时复用，不得重存。</p>
     */
    private void saveOriginalPosition() {
        mOriginalParent = (ViewGroup) mVideoView.getParent();
        if (mOriginalParent != null) {
            mOriginalIndex = mOriginalParent.indexOfChild(mVideoView);
            mOriginalLayoutParams = mVideoView.getLayoutParams();
        }
    }

    /**
     * 把播放器放进全屏位置。
     *
     * <p><b>容器契约优先</b>：宿主提供了 {@code fullscreenContainer} 时，只把播放器移入该容器
     * （脱离 DecorView），退出时还原。宿主未提供时回退到旧的 DecorView 搬运路径。</p>
     *
     * <p>两条路径都保证同一实例（不复制播放器），且退出时可逆还原。</p>
     */
    private void moveIntoFullscreen(Activity activity, ViewGroup decorView) {
        // 取消上一轮遗留的收尾兜底定时器，避免它在本次过渡中提前触发、
        // 过早解除 Surface 过渡保护（H4）。
        mVideoView.removeCallbacks(mFinalizeRunnable);

        // 从当前父容器（NORMAL 时是原容器；跨模式切换时是全屏容器）摘出播放器。
        // 注意：原始位置已在 saveOriginalPosition() 保存，此处不得再覆盖。
        ViewGroup currentParent = (ViewGroup) mVideoView.getParent();
        if (currentParent != null) {
            currentParent.removeView(mVideoView);
        }

        FrameLayout container = mVideoView.getFullscreenContainer();
        if (container != null) {
            // ---- 容器契约路径：不进 DecorView ----
            FrameLayout.LayoutParams fullParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            container.addView(mVideoView, fullParams);
            container.setVisibility(View.VISIBLE);
            container.bringToFront();
            android.util.Log.d(TAG, "moveIntoFullscreen: 使用容器契约，未搬运到 DecorView");
        } else {
            // ---- 回退路径：DecorView 搬运（旧行为，保留兼容）----
            android.util.Log.d(TAG, "moveIntoFullscreen: 宿主未提供全屏容器，回退到 DecorView 搬运");
            // 跨模式切换时旧的黑色遮罩仍在 DecorView 中，必须先移除，否则引用被覆盖后
            // 永远无法回收，退出全屏后整个应用被黑 View 盖死（C2）。
            if (mFullscreenBackground != null) {
                decorView.removeView(mFullscreenBackground);
                mFullscreenBackground = null;
            }
            mFullscreenBackground = new View(activity);
            mFullscreenBackground.setBackgroundColor(Color.BLACK);
            FrameLayout.LayoutParams bgParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            decorView.addView(mFullscreenBackground, bgParams);

            FrameLayout.LayoutParams fullParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            decorView.addView(mVideoView, fullParams);
        }
    }

    /**
     * 把播放器从全屏位置还原回原始父容器。
     */
    private void moveOutOfFullscreen(Activity activity, ViewGroup decorView) {
        FrameLayout container = mVideoView.getFullscreenContainer();
        if (container != null) {
            container.removeView(mVideoView);
            container.setVisibility(View.GONE);
        } else {
            decorView.removeView(mVideoView);
            if (mFullscreenBackground != null) {
                decorView.removeView(mFullscreenBackground);
                mFullscreenBackground = null;
            }
        }

        if (mOriginalParent != null && mOriginalLayoutParams != null) {
            mOriginalParent.addView(mVideoView, mOriginalIndex, mOriginalLayoutParams);
            mVideoView.post(new Runnable() {
                @Override
                public void run() {
                    if (mOriginalParent != null) {
                        mOriginalParent.requestLayout();
                    }
                    mVideoView.requestLayout();
                }
            });
        }
    }
    
    /**
     * 退出全屏，回到正常模式。
     *
     * <p>先设置竖屏、等待旋转完成后再移动 View（保持原有行为），
     * 播放器从全屏容器（或 DecorView）还原到原始父容器。</p>
     */
    private void exitToNormal() {
        android.util.Log.d(TAG, "exitToNormal: called");

        if (mVideoView == null) {
            return;
        }

        Activity activity = mVideoView.getActivity();
        if (activity == null || activity.isFinishing()) {
            android.util.Log.d(TAG, "exitToNormal: activity is null or finishing");
            return;
        }

        final ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        if (decorView == null) {
            android.util.Log.d(TAG, "exitToNormal: decorView is null");
            return;
        }

        pauseOcrIfRunning();

        mMode = FullscreenMode.NORMAL;
        mFullscreenTransitioning = true;
        mVideoView.setIfCurrentIsFullscreenInternal(false);

        // 停止重力感应旋转监听
        stopOrientationListener();

        mVideoView.post(new Runnable() {
            @Override
            public void run() {
                // 1. 显示系统 UI
                showSysBar(decorView, activity);

                // 2. 先设置竖屏
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

                // 3. 从全屏位置还原到原始父容器
                moveOutOfFullscreen(activity, decorView);

                // 4. 通知状态变化
                mVideoView.setOrangePlayerState(PlayerConstants.PLAYER_NORMAL);

                if (mVideoView.getTitleView() != null) {
                    mVideoView.getTitleView().setVisibility(View.GONE);
                }
                if (mVideoView.getVodControlView() != null) {
                    mVideoView.getVodControlView().onPlayerStateChanged(PlayerConstants.PLAYER_NORMAL);
                }

                // 5. 触发渲染视图重新布局
                mVideoView.requestLayout();

                // 6. 延迟补一次布局
                mVideoView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mVideoView.requestLayout();
                        if (mVideoView.getRenderProxy() != null) {
                            mVideoView.getRenderProxy().requestLayout();
                        }
                        updateExoSurfaceControlSize();
                    }
                }, 100);

                // 7. 收尾：优先由宿主 onConfigurationChanged 转发，兜底定时器保证收敛
                mVideoView.postDelayed(mFinalizeRunnable, TRANSITION_FALLBACK_MS);
            }
        });
    }

    /**
     * 退出全屏（兼容旧入口）。
     */
    public void stopFullScreen() {
        request(FullscreenMode.NORMAL);
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

    /**
     * 宿主 Activity 的 {@code onConfigurationChanged} 应转发到此处（P3：回调驱动）。
     *
     * <p><b>注意</b>：配置变化回调在配置生效时触发，早于 SurfaceView 重建完成。
     * 因此这里<b>不</b>立即收尾——那会在 Surface 尚未重建时解除
     * {@code onSurfaceDestroyed}/{@code setDisplay(null)}/{@code releaseSurface} 的过渡保护，
     * 正是黑屏防护要挡的场景。改为「重新计时兜底」：让保护窗口覆盖随后的 Surface 重建，
     * 由定时器在延时后收尾。</p>
     *
     * <p>未转发的宿主行为不变（本来也只依赖兜底定时器）。</p>
     */
    public void onConfigurationChanged() {
        if (mVideoView != null && mFullscreenTransitioning) {
            mVideoView.removeCallbacks(mFinalizeRunnable);
            mVideoView.postDelayed(mFinalizeRunnable, TRANSITION_FALLBACK_MS);
        }
    }

    /**
     * 全屏切换收尾（幂等）。旋转完成回调与兜底定时器都会调用。
     */
    private void finalizeFullscreenTransition() {
        // 无论是否仍在过渡，都取消兜底定时器，避免遗留任务在下一轮切换中误触发
        if (mVideoView != null) {
            mVideoView.removeCallbacks(mFinalizeRunnable);
        }
        if (mVideoView == null || !mFullscreenTransitioning) {
            return;
        }
        mFullscreenTransitioning = false;

        // mpv 绑定漂移检测：Android 16 旋转时框架静默换新 SurfaceTexture
        // （view 不销毁、无回调），mpv 仍绑旧 SurfaceTexture → 全屏黑屏。此处比对重绑。
        mVideoView.checkMpvSurfaceDrift();

        mVideoView.requestLayout();
        if (mVideoView.getRenderProxy() != null) {
            mVideoView.getRenderProxy().requestLayout();
        }
        updateExoSurfaceControlSize();

        // 只要仍在全屏，就保持方向监听（自动旋转 + 切换提示都需要它）
        if (mMode != FullscreenMode.NORMAL) {
            startOrientationListener();
        }
    }
    
    /**
     * 当前全屏模式唯一真值。
     *
     * @return {@link FullscreenMode#NORMAL} /
     *         {@link FullscreenMode#LANDSCAPE_FULLSCREEN} /
     *         {@link FullscreenMode#PORTRAIT_FULLSCREEN}
     */
    public int getMode() {
        return mMode;
    }

    public boolean isFullscreen() {
        return mMode != FullscreenMode.NORMAL;
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
        return mMode == FullscreenMode.PORTRAIT_FULLSCREEN;
    }

    /**
     * 进入竖屏全屏模式（不旋转屏幕，仅铺满竖屏）。
     */
    public void startPortraitFullScreen() {
        request(FullscreenMode.PORTRAIT_FULLSCREEN);
    }

    /**
     * 退出竖屏全屏模式。
     *
     * <p>只有当前确实处于竖屏全屏时才退出，避免误把横屏全屏也一并退出。</p>
     */
    public void stopPortraitFullScreen() {
        if (mMode == FullscreenMode.PORTRAIT_FULLSCREEN) {
            request(FullscreenMode.NORMAL);
        }
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
    
    /**
     * 在当前状态基础上取反。用于双击、返回键这类「反一下」的入口。
     *
     * <p>读的是同一个 {@link #getMode()}，因此不可能与 UI 显示的方向相反。
     * 手势本身是意图式的（上滑进入、下滑退出），应直接调用 {@link #request(int)}。</p>
     */
    public void toggleFullScreen() {
        request(isFullscreen() ? FullscreenMode.NORMAL : FullscreenMode.LANDSCAPE_FULLSCREEN);
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
