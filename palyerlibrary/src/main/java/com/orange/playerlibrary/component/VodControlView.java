package com.orange.playerlibrary.component;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.orange.playerlibrary.OrangeVideoController;
import com.orange.playerlibrary.OrangevideoView;
import com.orange.playerlibrary.PlayerConstants;
import com.orange.playerlibrary.R;
import com.orange.playerlibrary.VideoThumbnailHelper;
import com.orange.playerlibrary.interfaces.ControlWrapper;
import com.orange.playerlibrary.interfaces.IControlComponent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 视频点播控制视图
 * 显示播放/暂停按钮、进度条、时间、全屏按钮、弹幕控制区
 */
public class VodControlView extends FrameLayout implements IControlComponent,
        View.OnClickListener, SeekBar.OnSeekBarChangeListener {

    private static final String TAG = "VodControlView";
    
    // 线程池和主线程 Handler（用于异步加载预览）
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 双击防抖：记录上次状态变化时间，避免双击后的单击事件干扰
    private long mLastStateChangeTime = 0;
    private static final long DOUBLE_CLICK_INTERVAL = 500;

    // 图标更新防抖
    private long mLastIconUpdateTime = 0;

    // 长按倍速相关（默认 3.0x，最高 3.0x）
    private float mLongPressSpeed = 3.0f;
    private float mNormalSpeed = 1.0f;
    private boolean mIsLongPressing = false;
    
    // 点击事件监听器
    private View.OnClickListener mOnSpeedControlClickListener;
    private View.OnClickListener mOnEpisodeSelectClickListener;
    private View.OnClickListener mOnSetupClickListener;
    private View.OnClickListener mOnDanmuToggleClickListener;
    private View.OnClickListener mOnDanmuSetClickListener;
    private View.OnClickListener mOnDanmuInputClickListener;
    private View.OnClickListener mOnSkipOpeningClickListener;
    private View.OnClickListener mOnSkipEndingClickListener;
    private View.OnClickListener mOnPlayNextClickListener;

    // 控制包装器
    private ControlWrapper mControlWrapper;
    private OrangeVideoController mOrangeController;
    private static OrangeVideoController sSharedController;

    // UI 组件
    private LinearLayout mBottomContainer;
    private LinearLayout mTopContainer;
    private LinearLayout mDanmuContainer;
    private ImageView mPlayButton;
    private ImageView mFullScreen;
    private SeekBar mVideoProgress;
    private TextView mCurrTime;
    private TextView mTotalTime;
    private ProgressBar mBottomProgress;

    // 弹幕相关
    private ImageView mDanmuToggle;  // 保留用于兼容性
    private ImageView mDanmuToggleOn;  // 开启状态的图标
    private ImageView mDanmuToggleOff; // 关闭状态的图标
    private ImageView mDanmuSet;
    private EditText mDanmuInput;

    // 功能按钮
    private TextView mSpeedControl;
    private TextView mEpisodeSelect;
    private TextView mSkipButton;
    private TextView mSourceSelect;
    
    // 字幕按钮
    private ImageView mSubtitleToggle;
    private View.OnClickListener mOnSubtitleToggleClickListener;

    // 全屏时弹幕区的播放和全屏按钮
    private ImageView mPlayButtonFullscreen;
    private ImageView mFullScreenDanmu;
    
    // 下一集按钮
    private ImageView mPlayNext;
    
    // 竖屏全屏按钮
    private ImageView mShup;
    
    // 锁定按钮
    private ImageView mLockButton;
    private boolean mIsLocked = false;
    private View.OnClickListener mOnLockClickListener;
    
    // 横竖屏切换按钮
    private ImageView mRotationButton;
    private boolean mRotationButtonEnabled = true;  // 默认启用横竖屏切换按钮
    
    /**
     * 横竖屏切换按钮显示模式
     */
    public enum RotationButtonDisplayMode {
        /** 始终显示（全屏模式下） */
        ALWAYS,
        /** 仅竖屏全屏时显示 */
        PORTRAIT_ONLY,
        /** 仅横屏全屏时显示 */
        LANDSCAPE_ONLY,
        /** 始终隐藏 */
        NEVER
    }
    
    private RotationButtonDisplayMode mDisplayMode = RotationButtonDisplayMode.PORTRAIT_ONLY;  // 默认仅竖屏全屏显示

    // 状态
    private boolean mIsDragging = false;
    private boolean mIsShowBottomProgress = true;
    private static boolean sShowBottomProgress = true;
    
    // TV 模式
    private boolean mIsTvMode = false;
    
    // ===== 自主进度更新 =====
    private Handler mProgressHandler;
    private Runnable mProgressRunnable;
    private static final int PROGRESS_UPDATE_INTERVAL = 1000; // 1秒更新一次
    private boolean mIsProgressUpdating = false;
    
    // ===== 进度条拖动预览功能 =====
    private LinearLayout mPreviewContainer;
    private FrameLayout mPreviewContent;
    private ImageView mPreviewImage;
    private TextView mPreviewTime;
    private ProgressBar mPreviewProgress;
    private TextView mPreviewError;
    
    private boolean mIsPreviewShowing = false;
    private boolean mIsLongDrag = false;  // 是否长时间拖动
    private long mLastPreviewTime = 0;
    private long mCurrentPreviewPosition = -1;
    private CustomTarget<Bitmap> mCurrentPreviewTarget;
    
    private Handler mPreviewHandler = new Handler(Looper.getMainLooper());
    private Handler mDelayHandler = new Handler(Looper.getMainLooper());
    
    // 预览配置
    private static boolean sPreviewEnabled = true;  // 是否启用预览
    private static String sVideoUrl = "";  // 视频URL
    private static final int PREVIEW_WIDTH = 320;
    private static final int PREVIEW_HEIGHT = 180;
    private static final long PREVIEW_DELAY_MS = 400;  // 拖动多久后显示预览
    private static final long PREVIEW_THROTTLE_MS = 200;  // 预览图加载节流（降低到200ms提高响应速度）
    
    private Runnable mShowPreviewRunnable;

    public VodControlView(@NonNull Context context) {
        super(context);
        init();
    }

    public VodControlView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VodControlView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    /**
     * 重写 onInterceptTouchEvent，让触摸事件穿透到下面的 surface_container
     * 只有当触摸点在底部控制栏区域时才拦截事件
     */
    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
        // 检查触摸点是否在底部控制栏区域内
        if (mBottomContainer != null && mBottomContainer.getVisibility() == VISIBLE) {
            int[] location = new int[2];
            mBottomContainer.getLocationOnScreen(location);
            float touchY = event.getRawY();
            if (touchY >= location[1] && touchY <= location[1] + mBottomContainer.getHeight()) {
                // 触摸点在底部控制栏区域，拦截事件让控制栏处理
                return super.onInterceptTouchEvent(event);
            }
        }
        
        // 检查触摸点是否在锁定按钮区域
        if (mLockButton != null && mLockButton.getVisibility() == VISIBLE) {
            int[] location = new int[2];
            mLockButton.getLocationOnScreen(location);
            float touchX = event.getRawX();
            float touchY = event.getRawY();
            if (touchX >= location[0] && touchX <= location[0] + mLockButton.getWidth() &&
                touchY >= location[1] && touchY <= location[1] + mLockButton.getHeight()) {
                // 触摸点在锁定按钮区域，拦截事件
                return super.onInterceptTouchEvent(event);
            }
        }
        
        // 其他区域不拦截，让事件穿透到 surface_container
        return false;
    }
    
    /**
     * 重写 onTouchEvent，不消费事件，让事件传递给下面的 surface_container
     */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        // 不消费事件，让事件传递给 surface_container
        return false;
    }

    private void init() {
        setVisibility(GONE);
        setClickable(false);
        
        // 检测 TV 模式
        mIsTvMode = com.orange.playerlibrary.OrangePlayerConfig.isTvMode(getContext());
        
        LayoutInflater.from(getContext()).inflate(R.layout.orange_layout_vod_control_view, this, true);
        mBottomContainer = findViewById(R.id.bottom_container);
        mTopContainer = findViewById(R.id.container_main);
        mDanmuContainer = findViewById(R.id.danmu_container);

        mPlayButton = findViewById(R.id.iv_play);
        if (mPlayButton != null) {
            mPlayButton.setOnClickListener(this);
            setupLongPressSpeed(mPlayButton);
        }

        mFullScreen = findViewById(R.id.fullscreen);
        if (mFullScreen != null) {
            mFullScreen.setOnClickListener(this);
        }

        mVideoProgress = findViewById(R.id.seekBar);
        if (mVideoProgress != null) {
            mVideoProgress.setOnSeekBarChangeListener(this);
            
            // 优化触摸事件处理，防止父容器拦截
            mVideoProgress.setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            // 请求父容器不要拦截触摸事件
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            // 恢复父容器的触摸事件拦截
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                            break;
                    }
                    // 返回 false 让 SeekBar 自己处理触摸事件
                    return false;
                }
            });
        }

        mCurrTime = findViewById(R.id.curr_time);
        mTotalTime = findViewById(R.id.total_time);
        mBottomProgress = findViewById(R.id.bottom_progress);

        mDanmuToggleOn = findViewById(R.id.danmu_toggle_on);
        mDanmuToggleOff = findViewById(R.id.danmu_toggle_off);
        mDanmuToggle = mDanmuToggleOff; // 默认指向关闭状态，保持兼容性
        mDanmuSet = findViewById(R.id.danmu_set);
        mDanmuInput = findViewById(R.id.danmu_input);
        
        // 设置弹幕按钮点击事件 - 两个ImageView都要设置
        if (mDanmuToggleOn != null) {
            mDanmuToggleOn.setOnClickListener(this);
        }
        if (mDanmuToggleOff != null) {
            mDanmuToggleOff.setOnClickListener(this);
        }
        if (mDanmuSet != null) {
            mDanmuSet.setOnClickListener(this);
        }
        // 设置弹幕输入框点击事件
        if (mDanmuInput != null) {
            mDanmuInput.setOnClickListener(this);
        }

        mSpeedControl = findViewById(R.id.speed_control);
        mEpisodeSelect = findViewById(R.id.episode_select);
        mSkipButton = findViewById(R.id.film_header_footer);
        mSourceSelect = findViewById(R.id.source_select);
        
        // 字幕按钮
        mSubtitleToggle = findViewById(R.id.subtitle_toggle);
        if (mSubtitleToggle != null) {
            mSubtitleToggle.setOnClickListener(this);
        }
        
        // 设置跳过片头片尾按钮点击事件
        if (mSkipButton != null) {
            mSkipButton.setOnClickListener(this);
        }

        mPlayButtonFullscreen = findViewById(R.id.iv_play_fullscreen);
        mFullScreenDanmu = findViewById(R.id.fullscreen_danmu);
        mPlayNext = findViewById(R.id.playnext);
        if (mPlayNext != null) {
            mPlayNext.setOnClickListener(this);
        }
        
        mShup = findViewById(R.id.shup);
        if (mShup != null) {
            mShup.setOnClickListener(this);
        }
        
        // 锁定按钮
        mLockButton = findViewById(R.id.iv_lock);
        if (mLockButton != null) {
            mLockButton.setOnClickListener(this);
        }
        
        // 横竖屏切换按钮
        mRotationButton = findViewById(R.id.rotation_button);
        if (mRotationButton != null) {
            mRotationButton.setOnClickListener(this);
        }

        if (mSpeedControl != null) {
            mSpeedControl.setOnClickListener(this);
        }
        if (mEpisodeSelect != null) {
            mEpisodeSelect.setVisibility(GONE);
            mEpisodeSelect.setOnClickListener(this);
        }
        if (mPlayButtonFullscreen != null) {
            mPlayButtonFullscreen.setOnClickListener(this);
            setupLongPressSpeed(mPlayButtonFullscreen);
        }
        if (mFullScreenDanmu != null) {
            mFullScreenDanmu.setOnClickListener(this);
        }
        
        // 初始化预览视图
        initPreviewViews();
    }
    
    /**
     * 初始化预览视图
     */
    private void initPreviewViews() {
        mPreviewContainer = findViewById(R.id.preview_container);
        mPreviewContent = findViewById(R.id.preview_content);
        mPreviewImage = findViewById(R.id.preview_image);
        mPreviewTime = findViewById(R.id.preview_time);
        mPreviewProgress = findViewById(R.id.preview_progress);
        mPreviewError = findViewById(R.id.preview_error);
        
        if (mPreviewImage != null) {
            mPreviewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
        
        // 初始化延迟显示预览的任务
        mShowPreviewRunnable = () -> {
            mIsLongDrag = true;
            if (sPreviewEnabled && isFullScreen() && mIsDragging) {
                showPreviewContainer();
            }
        };
    }

    public void setOrangeVideoController(OrangeVideoController controller) {
        mOrangeController = controller;
        sSharedController = controller;
    }

    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
        mControlWrapper = controlWrapper;
        
        if (mOrangeController == null && sSharedController != null) {
            mOrangeController = sSharedController;
        }
        
        if (mOrangeController != null) {
            com.orange.playerlibrary.VideoEventManager eventManager = 
                    mOrangeController.getVideoEventManager();
            if (eventManager != null) {
                eventManager.bindControllerComponents(this);
            }
        }
        
        // 初始化弹幕按钮状态
        initDanmakuButtonState();
        
        // 初始化自主进度更新
        initProgressUpdater();
    }
    
    /**
     * 初始化自主进度更新器
     */
    private void initProgressUpdater() {
        if (mProgressHandler == null) {
            mProgressHandler = new Handler(Looper.getMainLooper());
        }
        
        mProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (mControlWrapper != null && getWindowToken() != null && !mIsDragging) {
                    int duration = (int) mControlWrapper.getDuration();
                    int position = (int) mControlWrapper.getCurrentPosition();
                    if (duration > 0) {
                        updateProgressInternal(duration, position);
                    }
                }
                // 继续下一次更新
                if (mIsProgressUpdating && mProgressHandler != null) {
                    mProgressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL);
                }
            }
        };
    }
    
    /**
     * 内部进度更新方法（不打印日志，避免刷屏）
     */
    private void updateProgressInternal(int duration, int position) {
        if (mVideoProgress != null && duration > 0) {
            mVideoProgress.setEnabled(true);
            int progress = (int) ((position * 1.0 / duration) * mVideoProgress.getMax());
            mVideoProgress.setProgress(progress);
            if (mBottomProgress != null) {
                mBottomProgress.setProgress(progress);
            }
            
            if (mControlWrapper != null) {
                int buffered = mControlWrapper.getBufferedPercentage();
                if (buffered >= 95) {
                    mVideoProgress.setSecondaryProgress(mVideoProgress.getMax());
                    if (mBottomProgress != null) {
                        mBottomProgress.setSecondaryProgress(mBottomProgress.getMax());
                    }
                } else {
                    mVideoProgress.setSecondaryProgress(buffered * 10);
                    if (mBottomProgress != null) {
                        mBottomProgress.setSecondaryProgress(buffered * 10);
                    }
                }
            }
        }
        
        if (mTotalTime != null) mTotalTime.setText(stringForTime(duration));
        if (mCurrTime != null) mCurrTime.setText(stringForTime(position));
    }
    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startProgressUpdate();
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopProgressUpdate();
    }
    
    /**
     * 启动自主进度更新
     */
    private void startProgressUpdate() {
        if (!mIsProgressUpdating && mProgressHandler != null && mProgressRunnable != null) {
            mIsProgressUpdating = true;
            mProgressHandler.post(mProgressRunnable);
        }
    }
    
    /**
     * 停止自主进度更新
     */
    private void stopProgressUpdate() {
        mIsProgressUpdating = false;
        if (mProgressHandler != null && mProgressRunnable != null) {
            mProgressHandler.removeCallbacks(mProgressRunnable);
        }
    }
    
    /**
     * 初始化弹幕按钮状态
     */
    private void initDanmakuButtonState() {
        if (mDanmuToggleOn != null && mDanmuToggleOff != null && getContext() != null) {
            com.orange.playerlibrary.PlayerSettingsManager settingsManager = 
                com.orange.playerlibrary.PlayerSettingsManager.getInstance(getContext());
            boolean enabled = settingsManager.isDanmakuEnabled();
            // 设置初始visibility
            if (enabled) {
                mDanmuToggleOn.setVisibility(VISIBLE);
                mDanmuToggleOff.setVisibility(GONE);
            } else {
                mDanmuToggleOn.setVisibility(GONE);
                mDanmuToggleOff.setVisibility(VISIBLE);
            }
            
            // 同时控制输入框和设置按钮的可见性
            if (mDanmuInput != null) {
                mDanmuInput.setVisibility(enabled ? VISIBLE : INVISIBLE);
            }
            if (mDanmuSet != null) {
                mDanmuSet.setVisibility(enabled ? VISIBLE : GONE);
            }
        }
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.fullscreen || id == R.id.fullscreen_danmu) {
            toggleFullScreen();
        } else if (id == R.id.iv_play || id == R.id.iv_play_fullscreen) {
            long currentTime = System.currentTimeMillis();
            
            // 检查是否在双击阻止时间内
            long timeSinceDoubleClick = currentTime - OrangevideoView.getLastDoubleClickTime();
            if (timeSinceDoubleClick < OrangevideoView.getDoubleClickBlockInterval()) {
                return;
            }
            
            long timeSinceLastChange = currentTime - mLastStateChangeTime;
            if (timeSinceLastChange < DOUBLE_CLICK_INTERVAL) {
                return;
            }
            if (mControlWrapper != null) {
                mControlWrapper.togglePlay();
            }
        } else if (id == R.id.speed_control) {
            if (mOnSpeedControlClickListener != null) {
                mOnSpeedControlClickListener.onClick(v);
            }
        } else if (id == R.id.episode_select) {
            if (mOnEpisodeSelectClickListener != null) {
                mOnEpisodeSelectClickListener.onClick(v);
            }
        } else if (id == R.id.shup) {
            toggleFullScreen();
        } else if (id == R.id.danmu_toggle_on || id == R.id.danmu_toggle_off) {
            if (mOnDanmuToggleClickListener != null) {
                mOnDanmuToggleClickListener.onClick(v);
            }
        } else if (id == R.id.danmu_set) {
            if (mOnDanmuSetClickListener != null) {
                mOnDanmuSetClickListener.onClick(v);
            }
        } else if (id == R.id.danmu_input) {
            if (mOnDanmuInputClickListener != null) {
                mOnDanmuInputClickListener.onClick(v);
            }
        } else if (id == R.id.film_header_footer) {
            if (mOnSkipOpeningClickListener != null) {
                mOnSkipOpeningClickListener.onClick(v);
            }
        } else if (id == R.id.playnext) {
            if (mOnPlayNextClickListener != null) {
                mOnPlayNextClickListener.onClick(v);
            }
        } else if (id == R.id.subtitle_toggle) {
            if (mOnSubtitleToggleClickListener != null) {
                mOnSubtitleToggleClickListener.onClick(v);
            }
        } else if (id == R.id.iv_lock) {
            // 从点击的View找到实际的VodControlView实例（全屏模式下可能是新实例）
            VodControlView actualView = findParentVodControlView(v);
            if (actualView != null) {
                actualView.toggleLock();
            } else {
                toggleLock();
            }
        } else if (id == R.id.rotation_button) {
            // 横竖屏切换按钮点击处理
            onRotationButtonClick();
        }
    }
    
    /**
     * 从View向上遍历找到VodControlView父组件
     * 解决全屏模式下组件实例问题
     */
    private VodControlView findParentVodControlView(View view) {
        android.view.ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent instanceof VodControlView) {
                return (VodControlView) parent;
            }
            parent = parent.getParent();
        }
        return null;
    }
    
    /**
     * 切换锁定状态
     */
    public void toggleLock() {
        setLocked(!mIsLocked);
    }
    
    /**
     * 设置锁定状态
     */
    public void setLocked(boolean locked) {
        android.util.Log.d(TAG, "setLocked: " + locked + " (fullscreen=" + isFullScreen() + ")");
        mIsLocked = locked;
        updateLockButtonState();
        
        // 直接在当前实例上更新 UI（不通过 ControlWrapper 绕回来，避免全屏模式下操作旧实例）
        if (locked) {
            // 锁定时隐藏其他控制组件，只显示锁定按钮
            if (mBottomContainer != null) mBottomContainer.setVisibility(GONE);
            if (mBottomProgress != null) mBottomProgress.setVisibility(GONE);
            // 确保锁定按钮可见，隐藏横竖屏切换按钮
            if (mLockButton != null && isFullScreen()) {
                mLockButton.setVisibility(VISIBLE);
            }
            if (mRotationButton != null) {
                mRotationButton.setVisibility(GONE);
            }
        } else {
            // 解锁时显示控制组件
            if (mBottomContainer != null) mBottomContainer.setVisibility(VISIBLE);
            // 解锁时也显示横竖屏切换按钮（如果在全屏模式）
            if (mRotationButton != null && isFullScreen()) {
                mRotationButton.setVisibility(VISIBLE);
            }
        }
        
        // 找到同级的 TitleView 并通知它（避免使用可能是旧实例的引用）
        notifySiblingTitleView(locked);
        
        // 找到同级的 GestureView 并禁用/恢复手势
        notifySiblingGestureView(locked);
        
        // 找到父级的 OrangevideoView 并禁用/恢复手势和自动旋转
        notifyParentVideoView(locked);
        
        // 通知 Controller 更新锁定状态（只更新状态，不操作 UI）
        if (mControlWrapper != null) {
            mControlWrapper.onLockStateChanged(locked);
        }
    }
    
    /**
     * 通知同级的 GestureView 锁定状态变化
     */
    private void notifySiblingGestureView(boolean locked) {
        android.view.ViewParent parent = getParent();
        if (parent instanceof android.view.ViewGroup) {
            android.view.ViewGroup container = (android.view.ViewGroup) parent;
            for (int i = 0; i < container.getChildCount(); i++) {
                android.view.View child = container.getChildAt(i);
                if (child instanceof GestureView) {
                    // GestureView 锁定时禁用
                    child.setEnabled(!locked);
                    break;
                }
            }
        }
    }
    
    /**
     * 通知父级的 OrangevideoView 锁定状态变化
     * 锁定时禁用手势和自动旋转，解锁时恢复
     */
    private void notifyParentVideoView(boolean locked) {
        // 向上遍历找到 OrangevideoView
        android.view.ViewParent parent = getParent();
        while (parent != null) {
            if (parent instanceof com.orange.playerlibrary.OrangevideoView) {
                com.orange.playerlibrary.OrangevideoView videoView = 
                    (com.orange.playerlibrary.OrangevideoView) parent;
                videoView.setGestureAndRotationLocked(locked);
                break;
            }
            parent = parent.getParent();
        }
    }
    
    /**
     * 设置横竖屏切换按钮是否可见
     * @param visible true 显示，false 隐藏
     * @deprecated 请使用 setRotationButtonDisplayMode 代替
     */
    @Deprecated
    public void setRotationButtonVisible(boolean visible) {
        mRotationButtonEnabled = visible;
        updateRotationButtonVisibility();
        android.util.Log.d(TAG, "setRotationButtonVisible (deprecated): " + visible);
    }
    
    /**
     * 设置横竖屏切换按钮显示模式
     * @param mode 显示模式
     */
    public void setRotationButtonDisplayMode(RotationButtonDisplayMode mode) {
        if (mode == null) {
            mode = RotationButtonDisplayMode.PORTRAIT_ONLY;
        }
        mDisplayMode = mode;
        updateRotationButtonVisibility();
        android.util.Log.d(TAG, "setRotationButtonDisplayMode: " + mode);
    }
    
    /**
     * 获取横竖屏切换按钮显示模式
     * @return 当前显示模式
     */
    public RotationButtonDisplayMode getRotationButtonDisplayMode() {
        return mDisplayMode;
    }
    
    /**
     * 获取横竖屏切换按钮启用状态
     * @return true 表示启用（会显示），false 表示禁用（不显示）
     * @deprecated 请使用 getRotationButtonDisplayMode 代替
     */
    @Deprecated
    public boolean isRotationButtonEnabled() {
        return mRotationButtonEnabled;
    }
    
    /**
     * 通知同级的 TitleView 锁定状态变化
     * 通过遍历父容器找到 TitleView，避免使用可能是旧实例的引用
     */
    private void notifySiblingTitleView(boolean locked) {
        android.view.ViewParent parent = getParent();
        if (parent instanceof android.view.ViewGroup) {
            android.view.ViewGroup container = (android.view.ViewGroup) parent;
            for (int i = 0; i < container.getChildCount(); i++) {
                android.view.View child = container.getChildAt(i);
                if (child instanceof com.orange.playerlibrary.component.TitleView) {
                    com.orange.playerlibrary.component.TitleView titleView = 
                        (com.orange.playerlibrary.component.TitleView) child;
                    titleView.onLockStateChanged(locked);
                    break;
                }
            }
        }
    }
    
    /**
     * 是否锁定
     */
    public boolean isLocked() {
        return mIsLocked;
    }
    
    /**
     * 更新锁定按钮状态
     */
    private void updateLockButtonState() {
        if (mLockButton != null) {
            mLockButton.setSelected(mIsLocked);
        }
    }
    
    /**
     * 设置锁定按钮点击监听器
     */
    public void setOnLockClickListener(View.OnClickListener listener) {
        mOnLockClickListener = listener;
    }
    
    /**
     * 锁定状态下的可见性变化（只影响锁定按钮）
     * @param isVisible 是否可见
     */
    public void onLockVisibilityChanged(boolean isVisible) {
        if (mLockButton != null && isFullScreen()) {
            mLockButton.setVisibility(isVisible ? VISIBLE : GONE);
        }
    }
    
    /**
     * 锁定按钮是否可见
     */
    public boolean isLockButtonVisible() {
        boolean visible = mLockButton != null && mLockButton.getVisibility() == VISIBLE;
        return visible;
    }

    private void toggleFullScreen() {
        // 检查是否使用 SystemPlayerManager
        if (isUsingSystemPlayer()) {
            // SystemPlayerManager 不支持全屏切换，给出提示
            android.widget.Toast.makeText(getContext(), 
                "系统播放器不支持全屏切换\n建议切换到 IJK 或 EXO 播放器", 
                android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (mControlWrapper != null) {
            mControlWrapper.toggleFullScreen();
        }
    }
    
    /**
     * 检查是否使用 SystemPlayerManager
     */
    private boolean isUsingSystemPlayer() {
        try {
            com.shuyu.gsyvideoplayer.player.IPlayerManager playerManager = 
                com.shuyu.gsyvideoplayer.GSYVideoManager.instance().getPlayer();
            if (playerManager != null) {
                String className = playerManager.getClass().getSimpleName();
                return "SystemPlayerManager".equals(className);
            }
        } catch (Exception e) {
        }
        return false;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {
        // 如果控制器可见性被禁用，不显示UI
        if (isVisible && mOrangeController != null && !mOrangeController.isControllerVisibilityEnabled()) {
            android.util.Log.d("VodControlView", "onVisibilityChanged - controller visibility disabled, skip show");
            return;
        }
        
        // 如果正在拖动进度条，不隐藏控制器
        if (!isVisible && mIsDragging) {
            android.util.Log.d("VodControlView", "onVisibilityChanged - dragging, skip hide");
            return;
        }
        
        // 锁定状态下，只显示/隐藏锁定按钮
        if (mIsLocked) {
            if (mLockButton != null && isFullScreen()) {
                mLockButton.setVisibility(isVisible ? VISIBLE : GONE);
            }
            // 锁定时不显示其他控制组件
            return;
        }
        
        if (isVisible) {
            // 设置整个 VodControlView 可见
            setVisibility(VISIBLE);
            if (mBottomContainer != null) {
                mBottomContainer.setVisibility(VISIBLE);
                if (anim != null) {
                    mBottomContainer.startAnimation(anim);
                }
            }
            if (mIsShowBottomProgress && mBottomProgress != null) {
                mBottomProgress.setVisibility(GONE);
            }
            // 全屏时显示锁定按钮
            if (mLockButton != null && isFullScreen()) {
                mLockButton.setVisibility(VISIBLE);
            }
        } else {
            // 设置整个 VodControlView 隐藏
            setVisibility(GONE);
            if (mBottomContainer != null) {
                mBottomContainer.setVisibility(GONE);
                if (anim != null) {
                    mBottomContainer.startAnimation(anim);
                }
            }
            if (sShowBottomProgress && mIsShowBottomProgress && mBottomProgress != null) {
                mBottomProgress.setVisibility(VISIBLE);
                AlphaAnimation fadeIn = new AlphaAnimation(0.0f, 1.0f);
                fadeIn.setDuration(300L);
                mBottomProgress.startAnimation(fadeIn);
            }
            // 隐藏锁定按钮
            if (mLockButton != null) {
                mLockButton.setVisibility(GONE);
            }
        }
    }

    @Override
    public void onPlayStateChanged(int playState) {
        // 如果当前实例没有附加到窗口，跳过UI更新（全屏模式下旧实例会收到回调但不应该更新）
        if (getWindowToken() == null) {
            return;
        }
        
        mLastStateChangeTime = System.currentTimeMillis();
        switch (playState) {
            case PlayerConstants.STATE_ERROR:
            case PlayerConstants.STATE_PREPARING:
            case PlayerConstants.STATE_PREPARED:
            case 8:
                setVisibility(GONE);
                break;

            case PlayerConstants.STATE_IDLE:
            case PlayerConstants.STATE_PLAYBACK_COMPLETED:
                setVisibility(GONE);
                resetProgress();
                break;

            case PlayerConstants.STATE_PLAYING:
                setVisibility(VISIBLE);
                updatePlayButtonState(true);
                updateBottomProgressVisibility();
                break;

            case PlayerConstants.STATE_PAUSED:
                setVisibility(VISIBLE);
                updatePlayButtonState(false);
                break;

            case PlayerConstants.STATE_BUFFERING:
            case PlayerConstants.STATE_BUFFERED:
                setVisibility(VISIBLE);
                if (mControlWrapper != null) {
                    updatePlayButtonState(mControlWrapper.isPlaying());
                }
                break;
        }
    }

    private void updatePlayButtonState(final boolean isPlaying) {
        mLastIconUpdateTime = System.currentTimeMillis();
        if (mPlayButton != null) {
            mPlayButton.setSelected(isPlaying);
            mPlayButton.refreshDrawableState();
            mPlayButton.invalidate();
        }

        if (mPlayButtonFullscreen != null) {
            mPlayButtonFullscreen.setSelected(isPlaying);
            mPlayButtonFullscreen.refreshDrawableState();
            mPlayButtonFullscreen.invalidate();
        }
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
        // 如果当前实例没有附加到窗口，跳过UI更新
        if (getWindowToken() == null) {
            return;
        }
        if (playerState == PlayerConstants.PLAYER_FULL_SCREEN) {
            // TV 模式下隐藏弹幕区
            if (mDanmuContainer != null) {
                mDanmuContainer.setVisibility(mIsTvMode ? GONE : VISIBLE);
            }
            if (mPlayButton != null) mPlayButton.setVisibility(GONE);
            if (mFullScreen != null) mFullScreen.setVisibility(GONE);
            if (mPlayButtonFullscreen != null) mPlayButtonFullscreen.setVisibility(VISIBLE);
            // TV 模式下隐藏全屏弹幕按钮
            if (mFullScreenDanmu != null) {
                mFullScreenDanmu.setVisibility(mIsTvMode ? GONE : VISIBLE);
                if (!mIsTvMode) {
                    mFullScreenDanmu.setSelected(true);
                }
            }
            if (mSkipButton != null) mSkipButton.setVisibility(VISIBLE);
            if (mEpisodeSelect != null) mEpisodeSelect.setVisibility(VISIBLE);
            if (mSpeedControl != null) mSpeedControl.setVisibility(VISIBLE);
            // 全屏时显示锁定按钮
            if (mLockButton != null) mLockButton.setVisibility(VISIBLE);
            // 横竖屏切换按钮根据模式决定是否显示（不再直接设置 VISIBLE）
            updateRotationButtonVisibility();
            
            // 关键修复：如果控制器处于隐藏状态，进入全屏时主动显示
            // 调用 show() 方法显示整个控制器（包括 VodControlView 本身）
            if (mOrangeController != null && !mOrangeController.isShowing()) {
                postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mOrangeController.show();
                        android.util.Log.d(TAG, "onPlayerStateChanged: auto-show controller on fullscreen via OrangeController.show()");
                    }
                }, 200);  // 延迟 200ms 等待全屏动画完成
            }
        } else if (playerState == PlayerConstants.PLAYER_NORMAL) {
            if (mDanmuContainer != null) {
                mDanmuContainer.setVisibility(GONE);
            }
            if (mPlayButton != null) mPlayButton.setVisibility(VISIBLE);
            if (mFullScreen != null) {
                mFullScreen.setVisibility(VISIBLE);
                mFullScreen.setSelected(false);
            }
            if (mPlayButtonFullscreen != null) mPlayButtonFullscreen.setVisibility(GONE);
            if (mFullScreenDanmu != null) {
                mFullScreenDanmu.setVisibility(GONE);
                mFullScreenDanmu.setSelected(false);
            }
            if (mSkipButton != null) mSkipButton.setVisibility(GONE);
            if (mEpisodeSelect != null) mEpisodeSelect.setVisibility(GONE);
            // 非全屏时隐藏锁定按钮和横竖屏切换按钮，并重置锁定状态
            if (mLockButton != null) mLockButton.setVisibility(GONE);
            if (mRotationButton != null) mRotationButton.setVisibility(GONE);
            // 退出全屏若仍处于锁定态，走完整解锁传播（恢复手势/自动旋转/控制器锁定态），
            // 否则普通模式单击会被残留锁定态吞掉，需双击才响应。
            if (mIsLocked) {
                setLocked(false);
            }
        }
    }

    @Override
    public void setProgress(int duration, int position) {
        if (mIsDragging) return;
        
        // 如果不在窗口中，跳过更新
        if (getWindowToken() == null) {
            return;
        }

        if (mVideoProgress != null) {
            if (duration > 0) {
                mVideoProgress.setEnabled(true);
                int progress = (int) ((position * 1.0 / duration) * mVideoProgress.getMax());
                int oldProgress = mVideoProgress.getProgress();
                // 获取 SeekBar 在屏幕上的位置
                int[] location = new int[2];
                mVideoProgress.getLocationOnScreen(location);
                mVideoProgress.setProgress(progress);
                // 验证设置后的值
                int afterProgress = mVideoProgress.getProgress();
                // 强制刷新 - 使用 post 确保在主线程执行
                mVideoProgress.postInvalidate();
                mVideoProgress.requestLayout();
                if (mBottomProgress != null) {
                    mBottomProgress.setProgress(progress);
                }
            } else {
                mVideoProgress.setEnabled(false);
            }

            if (mControlWrapper != null) {
                int buffered = mControlWrapper.getBufferedPercentage();
                if (buffered >= 95) {
                    mVideoProgress.setSecondaryProgress(mVideoProgress.getMax());
                    if (mBottomProgress != null) {
                        mBottomProgress.setSecondaryProgress(mBottomProgress.getMax());
                    }
                } else {
                    mVideoProgress.setSecondaryProgress(buffered * 10);
                    if (mBottomProgress != null) {
                        mBottomProgress.setSecondaryProgress(buffered * 10);
                    }
                }
            }
        }

        if (mTotalTime != null) mTotalTime.setText(stringForTime(duration));
        if (mCurrTime != null) {
            String newTime = stringForTime(position);
            String oldTime = mCurrTime.getText().toString();
            mCurrTime.setText(newTime);
            // 强制刷新
            mCurrTime.postInvalidate();
            mCurrTime.requestLayout();
        }
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
        onVisibilityChanged(!isLocked, null);
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser && mControlWrapper != null) {
            long duration = mControlWrapper.getDuration();
            long position = duration * progress / seekBar.getMax();
            if (mCurrTime != null) {
                mCurrTime.setText(stringForTime((int) position));
            }
            
            // 拖动时持续阻止控制器自动隐藏
            if (mIsDragging) {
                mControlWrapper.stopFadeOut();
                android.util.Log.d("VodControlView", "onProgressChanged - stopFadeOut called, showing: " + mControlWrapper.isShowing());
            }
            
            // 预览功能：仅在全屏模式且长时间拖动时显示
            if (sPreviewEnabled && isFullScreen() && mIsDragging && mIsLongDrag) {
                updatePreview(seekBar, progress, position, duration);
            } else {
                // 调试日志：帮助排查预览不显示的原因
                android.util.Log.d("VodControlView", "Preview not shown - enabled:" + sPreviewEnabled 
                    + " fullscreen:" + isFullScreen() 
                    + " dragging:" + mIsDragging 
                    + " longDrag:" + mIsLongDrag
                    + " videoUrl:" + (TextUtils.isEmpty(sVideoUrl) ? "empty" : "set"));
            }
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        android.util.Log.d("VodControlView", "onStartTrackingTouch - stopping fadeout");
        mIsDragging = true;
        mIsLongDrag = false;
        
        // 延迟显示预览（避免快速点击时显示）
        if (mDelayHandler != null && mShowPreviewRunnable != null) {
            mDelayHandler.postDelayed(mShowPreviewRunnable, PREVIEW_DELAY_MS);
        }
        
        if (mControlWrapper != null) {
            mControlWrapper.stopProgress();
            mControlWrapper.stopFadeOut();
            android.util.Log.d("VodControlView", "onStartTrackingTouch - controller showing: " + mControlWrapper.isShowing());
        }
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        // 取消延迟任务
        if (mDelayHandler != null) {
            mDelayHandler.removeCallbacks(mShowPreviewRunnable);
        }
        cancelPreviewLoad();
        
        // 释放复用的 MediaMetadataRetriever
        VideoThumbnailHelper.releaseReusableRetriever();
        
        // 隐藏预览
        if (mIsLongDrag) {
            hidePreviewContainer();
        }
        mIsLongDrag = false;
        
        // 执行 seek
        if (mControlWrapper != null) {
            long duration = mControlWrapper.getDuration();
            int progress = seekBar.getProgress();
            int maxProgress = mVideoProgress.getMax();
            long position = duration * progress / maxProgress;
            
            // 详细日志：记录 seek 计算过程
            android.util.Log.d("VodControlView", "=== SEEK DEBUG ===");
            android.util.Log.d("VodControlView", "duration=" + duration + "ms (" + (duration/1000) + "s)");
            android.util.Log.d("VodControlView", "seekBar.getProgress()=" + progress);
            android.util.Log.d("VodControlView", "mVideoProgress.getMax()=" + maxProgress);
            android.util.Log.d("VodControlView", "calculated position=" + position + "ms (" + (position/1000) + "s)");
            android.util.Log.d("VodControlView", "==================");
            
            mControlWrapper.seekTo(position);
            mControlWrapper.startProgress();
            mControlWrapper.startFadeOut();
        }
        mIsDragging = false;
    }

    private void resetProgress() {
        if (mVideoProgress != null) {
            mVideoProgress.setProgress(0);
            mVideoProgress.setSecondaryProgress(0);
        }
        if (mBottomProgress != null) {
            mBottomProgress.setProgress(0);
            mBottomProgress.setSecondaryProgress(0);
        }
    }

    private void updateBottomProgressVisibility() {
        android.util.Log.d("VodControlView", "updateBottomProgressVisibility() called");
        android.util.Log.d("VodControlView", "  VodControlView size: " + getWidth() + "x" + getHeight());
        android.util.Log.d("VodControlView", "  mBottomProgress=" + mBottomProgress);
        android.util.Log.d("VodControlView", "  sShowBottomProgress=" + sShowBottomProgress);
        android.util.Log.d("VodControlView", "  mIsShowBottomProgress=" + mIsShowBottomProgress);
        
        if (mBottomProgress == null) {
            android.util.Log.w("VodControlView", "  mBottomProgress is null, returning");
            return;
        }
        
        // 如果全局或实例级别禁用了底部进度条，直接隐藏
        if (!sShowBottomProgress || !mIsShowBottomProgress) {
            android.util.Log.d("VodControlView", "  Setting GONE (disabled)");
            mBottomProgress.setVisibility(GONE);
            return;
        }
        
        // 如果底部控制栏可见，隐藏小进度条；否则显示小进度条
        // 注意：不能简单检查 mBottomContainer 可见性，因为设置弹窗打开时底部容器也可见
        // 应该检查控制器是否真正在显示（不是锁定状态）
        boolean bottomContainerVisible = mBottomContainer != null && mBottomContainer.getVisibility() == VISIBLE;
        boolean controllerReallyShowing = bottomContainerVisible && !mIsLocked && getVisibility() == VISIBLE;
        
        android.util.Log.d("VodControlView", "  mBottomContainer=" + mBottomContainer);
        android.util.Log.d("VodControlView", "  bottomContainerVisible=" + bottomContainerVisible);
        android.util.Log.d("VodControlView", "  mIsLocked=" + mIsLocked);
        android.util.Log.d("VodControlView", "  getVisibility()=" + getVisibility());
        android.util.Log.d("VodControlView", "  controllerReallyShowing=" + controllerReallyShowing);
        
        if (controllerReallyShowing) {
            android.util.Log.d("VodControlView", "  Setting GONE (controller showing)");
            mBottomProgress.setVisibility(GONE);
        } else {
            android.util.Log.d("VodControlView", "  Setting VISIBLE");
            mBottomProgress.setVisibility(VISIBLE);
            // 强制请求布局和重绘
            mBottomProgress.requestLayout();
            mBottomProgress.invalidate();
            
            // 延迟检查尺寸
            mBottomProgress.post(new Runnable() {
                @Override
                public void run() {
                    android.util.Log.d("VodControlView", "  After layout: " + mBottomProgress.getWidth() + "x" + mBottomProgress.getHeight());
                }
            });
        }
    }

    private String stringForTime(int timeMs) {
        int totalSeconds = timeMs / 1000;
        int seconds = totalSeconds % 60;
        int minutes = (totalSeconds / 60) % 60;
        int hours = totalSeconds / 3600;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }

    public static void setBottomProgress(boolean show) {
        android.util.Log.d("VodControlView", "setBottomProgress() called with show=" + show);
        sShowBottomProgress = show;
    }

    public void showBottomProgress(boolean show) {
        android.util.Log.d("VodControlView", "showBottomProgress() called with show=" + show);
        mIsShowBottomProgress = show;
        updateBottomProgressVisibility();
    }

    public boolean isFullScreen() {
        return mControlWrapper != null && mControlWrapper.isFullScreen();
    }

    /**
     * 是否正在拖动进度条
     * @return true 正在拖动
     */
    public boolean isDragging() {
        return mIsDragging;
    }

    public ImageView getPlayButton() { return mPlayButton; }
    public ImageView getFullScreenButton() { return mFullScreen; }
    public SeekBar getVideoProgress() { return mVideoProgress; }
    public ImageView getDanmuToggle() { return mDanmuToggle; }
    public ImageView getDanmuSet() { return mDanmuSet; }
    public TextView getSpeedControl() { return mSpeedControl; }
    public TextView getEpisodeSelect() { return mEpisodeSelect; }

    private void setupLongPressSpeed(View view) {
        if (view == null) return;
        
        view.setOnLongClickListener(v -> {
            if (mControlWrapper != null && mControlWrapper.isPlaying() && !mIsLongPressing) {
                mIsLongPressing = true;
                mNormalSpeed = mControlWrapper.getSpeed();
                mControlWrapper.setSpeed(mLongPressSpeed);
                String message = getContext().getString(R.string.orange_long_press_speed, mLongPressSpeed);
                android.widget.Toast.makeText(getContext(), message, android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });

        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP ||
                    event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                if (mIsLongPressing && mControlWrapper != null) {
                    mIsLongPressing = false;
                    mControlWrapper.setSpeed(mNormalSpeed);
                    String message = getContext().getString(R.string.orange_restore_normal_speed, mNormalSpeed);
                    android.widget.Toast.makeText(getContext(), message, android.widget.Toast.LENGTH_SHORT).show();
                }
            }
            return false;
        });
    }

    public void setLongPressSpeed(float speed) { mLongPressSpeed = speed; }
    public float getLongPressSpeed() { return mLongPressSpeed; }
    public void setOnSpeedControlClickListener(View.OnClickListener listener) { mOnSpeedControlClickListener = listener; }
    public void setOnEpisodeSelectClickListener(View.OnClickListener listener) { mOnEpisodeSelectClickListener = listener; }
    public void setOnSetupClickListener(View.OnClickListener listener) { mOnSetupClickListener = listener; }
    public void setOnDanmuToggleClickListener(View.OnClickListener listener) { mOnDanmuToggleClickListener = listener; }
    public void setOnDanmuSetClickListener(View.OnClickListener listener) { mOnDanmuSetClickListener = listener; }
    public void setOnDanmuInputClickListener(View.OnClickListener listener) { mOnDanmuInputClickListener = listener; }
    public void setOnSkipOpeningClickListener(View.OnClickListener listener) { mOnSkipOpeningClickListener = listener; }
    public void setOnSkipEndingClickListener(View.OnClickListener listener) { mOnSkipEndingClickListener = listener; }
    public void setOnPlayNextClickListener(View.OnClickListener listener) { mOnPlayNextClickListener = listener; }
    public void setOnSubtitleToggleClickListener(View.OnClickListener listener) { mOnSubtitleToggleClickListener = listener; }
    
    /**
     * 更新字幕按钮状态
     */
    public void updateSubtitleToggleState(boolean enabled) {
        if (mSubtitleToggle != null) {
            mSubtitleToggle.setAlpha(enabled ? 1.0f : 0.5f);
        }
    }
    
    public ImageView getSubtitleToggle() { return mSubtitleToggle; }
    
    /**
     * 更新弹幕开关按钮状态 - 使用两个ImageView切换visibility
     */
    public void updateDanmakuToggleState(boolean enabled) {
        // 使用post确保在布局完成后执行
        final boolean finalEnabled = enabled;
        post(() -> {
            // 检查父容器
            if (mDanmuContainer != null) {
            }
            
            // 通过控制两个ImageView的visibility来切换图标
            if (mDanmuToggleOn != null && mDanmuToggleOff != null) {
                if (finalEnabled) {
                    mDanmuToggleOn.setVisibility(VISIBLE);
                    mDanmuToggleOff.setVisibility(GONE);
                } else {
                    mDanmuToggleOn.setVisibility(GONE);
                    mDanmuToggleOff.setVisibility(VISIBLE);
                }
            } else {
            }
            
            // 同时控制输入框和设置按钮的可见性
            if (mDanmuInput != null) {
                mDanmuInput.setVisibility(finalEnabled ? VISIBLE : INVISIBLE);
            }
            if (mDanmuSet != null) {
                mDanmuSet.setVisibility(finalEnabled ? VISIBLE : GONE);
            }
        });
    }
    
    // ===== 进度条拖动预览功能方法 =====
    
    /**
     * 设置视频URL（用于预览加载）
     */
    public static void setVideoUrl(String url) {
        sVideoUrl = url;
    }
    
    /**
     * 设置是否启用预览功能
     */
    public static void setPreviewEnabled(boolean enabled) {
        sPreviewEnabled = enabled;
    }
    
    /**
     * 显示预览容器
     */
    private void showPreviewContainer() {
        if (mPreviewContainer != null) {
            mPreviewContainer.setVisibility(VISIBLE);
            mIsPreviewShowing = true;
            
            // 显示预览时阻止控制器自动隐藏
            if (mControlWrapper != null) {
                mControlWrapper.stopFadeOut();
            }
        }
    }
    
    /**
     * 隐藏预览容器
     */
    private void hidePreviewContainer() {
        if (mPreviewContainer != null) {
            android.util.Log.d("VodControlView", "hidePreviewContainer called");
            mPreviewContainer.setVisibility(GONE);
            mIsPreviewShowing = false;
        }
    }
    
    /**
     * 更新预览
     */
    private void updatePreview(SeekBar seekBar, int progress, long position, long duration) {
        android.util.Log.d("VodControlView", "updatePreview called - position:" + position + " videoUrl:" + sVideoUrl);
        
        // 更新预览时间
        if (mPreviewTime != null) {
            mPreviewTime.setText(stringForTime((int) position));
        }
        
        // 更新预览位置
        updatePreviewPosition(seekBar, progress);
        
        // 节流加载预览图
        long currentTime = System.currentTimeMillis();
        if (currentTime - mLastPreviewTime > PREVIEW_THROTTLE_MS) {
            cancelPreviewLoad();
            loadPreviewImage(position);
            mLastPreviewTime = currentTime;
        }
        
        // 确保预览容器可见
        if (!mIsPreviewShowing) {
            showPreviewContainer();
        }
    }
    
    /**
     * 更新预览容器位置（跟随拖动位置）
     * 优化版本：减少布局重绘，使用 translationX 代替 margin
     */
    private void updatePreviewPosition(SeekBar seekBar, int progress) {
        if (mPreviewContainer == null || seekBar == null) return;
        
        int seekBarWidth = seekBar.getWidth();
        int seekBarPaddingLeft = seekBar.getPaddingLeft();
        int seekBarPaddingRight = seekBar.getPaddingRight();
        int availableWidth = seekBarWidth - seekBarPaddingLeft - seekBarPaddingRight;
        
        // 计算拖动位置
        float thumbPosition = availableWidth * (progress / 1000f);
        
        // 获取 SeekBar 在父容器中的位置
        int[] seekBarLocation = new int[2];
        seekBar.getLocationInWindow(seekBarLocation);
        
        int[] containerLocation = new int[2];
        ((View) mPreviewContainer.getParent()).getLocationInWindow(containerLocation);
        
        float thumbCenterX = seekBarLocation[0] - containerLocation[0] + seekBarPaddingLeft + thumbPosition;
        
        // 计算预览容器位置
        int previewWidth = mPreviewContainer.getWidth();
        if (previewWidth == 0) {
            // 强制测量获取宽度
            mPreviewContainer.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );
            previewWidth = mPreviewContainer.getMeasuredWidth();
            if (previewWidth == 0) previewWidth = 340; // 默认宽度
        }
        
        int parentWidth = ((View) mPreviewContainer.getParent()).getWidth();
        float targetX = thumbCenterX - previewWidth / 2f;
        
        // 边界检查
        if (targetX < 0) {
            targetX = 0;
        } else if (targetX + previewWidth > parentWidth) {
            targetX = parentWidth - previewWidth;
        }
        
        // 更新位置
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mPreviewContainer.getLayoutParams();
        params.leftMargin = (int) targetX;
        mPreviewContainer.setLayoutParams(params);
    }
    
    /**
     * 加载预览图
     * 优先级：Glide > ScreenshotManager > VideoThumbnailHelper
     * 
     * 支持情况：
     * - Glide: 支持 mp4, avi, mkv, mov 等常规视频格式，不支持 m3u8/HLS 流
     * - ScreenshotManager: 支持所有播放器能播放的格式（直接截图）
     * - VideoThumbnailHelper: 支持所有本地和网络视频格式（MediaMetadataRetriever）
     */
    private void loadPreviewImage(long timeMs) {
        // 避免重复加载相同位置
        if (mCurrentPreviewPosition == timeMs) {
            return;
        }
        mCurrentPreviewPosition = timeMs;
        
        // 显示加载状态
        showPreviewLoading();
        
        // 检测视频格式
        boolean isHlsStream = isHlsStream(sVideoUrl);
        boolean isLiveStream = isLiveStream(sVideoUrl);
        
        OrangevideoView videoView = getVideoView();
        long currentPosition = videoView != null ? videoView.getCurrentPositionWhenPlaying() : -1;
        long positionDiff = Math.abs(timeMs - currentPosition);
        
        // 优先级 1: Glide（最快，但不支持 HLS 和直播流）
        if (!isHlsStream && !isLiveStream) {
            android.util.Log.d("VodControlView", "Using Glide (highest priority)");
            loadPreviewImageWithGlide(timeMs);
        }
        // 优先级 2: ScreenshotManager（如果接近当前播放位置）
        else if (videoView != null && positionDiff < 3000) {
            android.util.Log.d("VodControlView", "Using ScreenshotManager (near current position)");
            loadPreviewWithScreenshot(videoView, timeMs);
        }
        // 优先级 3: VideoThumbnailHelper（兜底，支持所有格式）
        else {
            android.util.Log.d("VodControlView", "Using VideoThumbnailHelper (fallback)");
            loadPreviewWithThumbnailHelper(timeMs);
        }
    }
    
    /**
     * 检测是否为 HLS 流
     */
    private boolean isHlsStream(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String lowerUrl = url.toLowerCase();
        return lowerUrl.contains(".m3u8") || lowerUrl.contains("/hls/");
    }
    
    /**
     * 检测是否为直播流
     */
    private boolean isLiveStream(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String lowerUrl = url.toLowerCase();
        return lowerUrl.startsWith("rtsp://") 
            || lowerUrl.startsWith("rtmp://") 
            || lowerUrl.contains(".flv");
    }
    
    /**
     * 使用 ScreenshotManager 截取当前画面作为预览
     */
    private void loadPreviewWithScreenshot(OrangevideoView videoView, long timeMs) {
        android.util.Log.d(TAG, "loadPreviewWithScreenshot - timeMs:" + timeMs + " (near current position)");
        
        try {
            com.orange.playerlibrary.screenshot.ScreenshotManager screenshotManager = 
                new com.orange.playerlibrary.screenshot.ScreenshotManager(getContext(), videoView);
            
            screenshotManager.takeScreenshot(false, new com.orange.playerlibrary.screenshot.ScreenshotManager.ScreenshotCallback() {
                @Override
                public void onSuccess(Bitmap bitmap, String message) {
                    android.util.Log.d(TAG, "Screenshot success - bitmap:" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " isDragging:" + mIsDragging + " isPreviewShowing:" + mIsPreviewShowing);
                    
                    // 只要还在拖动中，就显示预览
                    if (mPreviewImage != null && mCurrentPreviewPosition == timeMs && mIsDragging) {
                        // 缩放 Bitmap 到预览尺寸
                        Bitmap scaledBitmap = Bitmap.createScaledBitmap(
                            bitmap, PREVIEW_WIDTH, PREVIEW_HEIGHT, true);
                        
                        mPreviewImage.setImageBitmap(scaledBitmap);
                        hidePreviewLoading();
                        
                        // 确保预览容器可见
                        if (!mIsPreviewShowing) {
                            showPreviewContainer();
                        }
                        
                        animatePreviewChange();
                        
                        // 回收原始 Bitmap
                        if (bitmap != scaledBitmap) {
                            bitmap.recycle();
                        }
                    }
                }
                
                @Override
                public void onError(String error) {
                    // 截图失败，回退到 VideoThumbnailHelper 方案
                    android.util.Log.w(TAG, "Screenshot failed, fallback to VideoThumbnailHelper: " + error);
                    loadPreviewWithThumbnailHelper(timeMs);
                }
            });
        } catch (Exception e) {
            android.util.Log.e(TAG, "loadPreviewWithScreenshot error", e);
            // 回退到 VideoThumbnailHelper 方案
            loadPreviewWithThumbnailHelper(timeMs);
        }
    }
    
    /**
     * 使用 VideoThumbnailHelper 加载预览图（从视频文件提取帧）
     * 使用 MediaMetadataRetriever，比 Glide 更轻量高效
     */
    private void loadPreviewWithThumbnailHelper(long timeMs) {
        android.util.Log.d(TAG, "loadPreviewWithThumbnailHelper - timeMs:" + timeMs + " url:" + sVideoUrl);
        
        if (TextUtils.isEmpty(sVideoUrl)) {
            showPreviewError("无法加载预览");
            return;
        }
        
        try {
            // 使用 VideoThumbnailHelper 异步获取指定时间的帧
            // timeMs 是毫秒，需要转换为微秒
            long timeUs = timeMs * 1000;
            
            // 使用复用模式，避免每次都重新打开视频文件
            executor.execute(() -> {
                Bitmap bitmap = VideoThumbnailHelper.getFrameAtTime(sVideoUrl, timeUs, null, true);
                mainHandler.post(() -> {
                    if (bitmap != null) {
                        android.util.Log.d(TAG, "VideoThumbnailHelper success - bitmap:" + bitmap.getWidth() + "x" + bitmap.getHeight() 
                            + " isDragging:" + mIsDragging + " isPreviewShowing:" + mIsPreviewShowing);
                        
                        // 只要还在拖动中，就显示预览（不依赖 mIsLongDrag，因为图片是异步加载的）
                        if (mPreviewImage != null && mCurrentPreviewPosition == timeMs && mIsDragging) {
                            // 缩放 Bitmap 到预览尺寸
                            Bitmap scaledBitmap = Bitmap.createScaledBitmap(
                                bitmap, PREVIEW_WIDTH, PREVIEW_HEIGHT, true);
                            
                            mPreviewImage.setImageBitmap(scaledBitmap);
                            hidePreviewLoading();
                            
                            // 确保预览容器可见
                            if (!mIsPreviewShowing) {
                                showPreviewContainer();
                            }
                            
                            animatePreviewChange();
                            
                            // 回收原始 Bitmap
                            if (bitmap != scaledBitmap) {
                                bitmap.recycle();
                            }
                        } else {
                            // 不需要显示，回收 bitmap
                            bitmap.recycle();
                        }
                    } else {
                        // VideoThumbnailHelper 失败，最后尝试 Glide
                        android.util.Log.w(TAG, "VideoThumbnailHelper failed, fallback to Glide");
                        loadPreviewImageWithGlide(timeMs);
                    }
                });
            });
        } catch (Exception e) {
            android.util.Log.e(TAG, "loadPreviewWithThumbnailHelper error", e);
            // 最后回退到 Glide 方案
            loadPreviewImageWithGlide(timeMs);
        }
    }
    
    /**
     * 使用 Glide 加载预览图（从视频文件提取帧）
     */
    private void loadPreviewImageWithGlide(long timeMs) {
        android.util.Log.d(TAG, "loadPreviewImageWithGlide - timeMs:" + timeMs + " (fallback method)");
        
        if (TextUtils.isEmpty(sVideoUrl)) {
            showPreviewError("无法加载预览");
            return;
        }
        
        try {
            Context context = getContext().getApplicationContext();
            
            RequestOptions options = new RequestOptions()
                    .frame(timeMs * 1000)  // 微秒
                    .override(PREVIEW_WIDTH, PREVIEW_HEIGHT)
                    .fitCenter()
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC);
            
            mCurrentPreviewTarget = new CustomTarget<Bitmap>(PREVIEW_WIDTH, PREVIEW_HEIGHT) {
                @Override
                public void onResourceReady(@NonNull Bitmap resource,
                        @Nullable Transition<? super Bitmap> transition) {
                    android.util.Log.d(TAG, "Glide success - bitmap:" + resource.getWidth() + "x" + resource.getHeight()
                        + " isDragging:" + mIsDragging + " isPreviewShowing:" + mIsPreviewShowing);
                    
                    // 只要还在拖动中，就显示预览
                    if (mPreviewImage != null && mCurrentPreviewPosition == timeMs && mIsDragging) {
                        mPreviewImage.setImageBitmap(resource);
                        hidePreviewLoading();
                        
                        // 确保预览容器可见
                        if (!mIsPreviewShowing) {
                            showPreviewContainer();
                        }
                        
                        animatePreviewChange();
                    }
                }
                
                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {
                    // 清理资源
                }
                
                @Override
                public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    // 加载失败时静默处理，不显示错误
                    android.util.Log.w(TAG, "Glide failed to load preview");
                    hidePreviewLoading();
                }
            };
            
            Glide.with(context)
                    .asBitmap()
                    .load(sVideoUrl)
                    .apply(options)
                    .into(mCurrentPreviewTarget);
                    
        } catch (Exception e) {
            showPreviewError("预览加载失败");
        }
    }
    
    /**
     * 获取播放器视图
     */
    private OrangevideoView getVideoView() {
        if (mControlWrapper != null && mControlWrapper instanceof OrangeVideoController) {
            return ((OrangeVideoController) mControlWrapper).getVideoView();
        }
        return null;
    }
    
    /**
     * 取消预览加载
     */
    private void cancelPreviewLoad() {
        if (mCurrentPreviewTarget != null) {
            try {
                Glide.with(getContext()).clear(mCurrentPreviewTarget);
            } catch (Exception ignored) {
            }
            mCurrentPreviewTarget = null;
        }
    }
    
    /**
     * 显示预览加载状态
     */
    private void showPreviewLoading() {
        if (mPreviewProgress != null) {
            mPreviewProgress.setVisibility(VISIBLE);
        }
        if (mPreviewError != null) {
            mPreviewError.setVisibility(GONE);
        }
    }
    
    /**
     * 隐藏预览加载状态
     */
    private void hidePreviewLoading() {
        if (mPreviewProgress != null) {
            mPreviewProgress.setVisibility(GONE);
        }
    }
    
    /**
     * 显示预览错误
     */
    private void showPreviewError(String message) {
        hidePreviewLoading();
        if (mPreviewError != null) {
            mPreviewError.setText(message);
            mPreviewError.setVisibility(VISIBLE);
        }
    }
    
    /**
     * 预览图切换动画
     */
    private void animatePreviewChange() {
        if (mPreviewContainer == null) return;
        
        AnimationSet animationSet = new AnimationSet(true);
        animationSet.setDuration(150);
        
        AlphaAnimation fadeIn = new AlphaAnimation(0.7f, 1.0f);
        ScaleAnimation scale = new ScaleAnimation(
                0.97f, 1.0f, 0.97f, 1.0f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        
        animationSet.addAnimation(fadeIn);
        animationSet.addAnimation(scale);
        
        mPreviewContainer.startAnimation(animationSet);
    }
    
    // ===== 控件获取方法 =====
    
    // 注意：getPlayButton(), getFullScreenButton(), getVideoProgress(), getDanmuToggle(), getDanmuSet() 已在上方定义
    
    /**
     * 获取进度条
     * @return 进度条SeekBar
     */
    public SeekBar getProgressBar() {
        return mVideoProgress;
    }
    
    /**
     * 获取当前时间文本
     * @return 当前时间TextView
     */
    public TextView getCurrentTimeText() {
        return mCurrTime;
    }
    
    /**
     * 获取总时长文本
     * @return 总时长TextView
     */
    public TextView getTotalTimeText() {
        return mTotalTime;
    }
    
    /**
     * 获取底部进度条
     * @return 底部进度条ProgressBar
     */
    public ProgressBar getBottomProgressBar() {
        return mBottomProgress;
    }
    
    /**
     * 获取倍速控制按钮
     * @return 倍速控制TextView
     */
    public TextView getSpeedControlButton() {
        return mSpeedControl;
    }
    
    /**
     * 获取选集按钮
     * @return 选集TextView
     */
    public TextView getEpisodeSelectButton() {
        return mEpisodeSelect;
    }
    
    /**
     * 获取跳过按钮
     * @return 跳过按钮TextView
     */
    public TextView getSkipButton() {
        return mSkipButton;
    }
    
    /**
     * 获取字幕开关按钮
     * @return 字幕开关ImageView
     */
    public ImageView getSubtitleToggleButton() {
        return mSubtitleToggle;
    }
    
    /**
     * 获取弹幕输入框
     * @return 弹幕输入EditText
     */
    public EditText getDanmuInput() {
        return mDanmuInput;
    }
    
    /**
     * 获取下一集按钮
     * @return 下一集ImageView
     */
    public ImageView getPlayNextButton() {
        return mPlayNext;
    }
    
    /**
     * 获取锁定按钮
     * @return 锁定按钮ImageView
     */
    public ImageView getLockButton() {
        return mLockButton;
    }
    
    /**
     * 获取底部容器
     * @return 底部容器LinearLayout
     */
    public LinearLayout getBottomContainer() {
        return mBottomContainer;
    }
    
    /**
     * 获取弹幕容器
     * @return 弹幕容器LinearLayout
     */
    public LinearLayout getDanmuContainer() {
        return mDanmuContainer;
    }
    
    // ===== 便捷设置方法 =====
    
    /**
     * 设置播放按钮图标
     * @param resId drawable资源ID
     */
    public void setPlayButtonIcon(int resId) {
        if (mPlayButton != null) {
            mPlayButton.setImageResource(resId);
        }
    }
    
    /**
     * 设置全屏按钮图标
     * @param resId drawable资源ID
     */
    public void setFullScreenButtonIcon(int resId) {
        if (mFullScreen != null) {
            mFullScreen.setImageResource(resId);
        }
    }
    
    /**
     * 设置倍速按钮是否可见
     * @param visible true显示，false隐藏
     */
    public void setSpeedButtonVisible(boolean visible) {
        if (mSpeedControl != null) {
            mSpeedControl.setVisibility(visible ? VISIBLE : GONE);
        }
    }
    
    /**
     * 设置选集按钮是否可见
     * @param visible true显示，false隐藏
     */
    public void setEpisodeButtonVisible(boolean visible) {
        if (mEpisodeSelect != null) {
            mEpisodeSelect.setVisibility(visible ? VISIBLE : GONE);
        }
    }
    
    /**
     * 设置跳过按钮是否可见
     * @param visible true显示，false隐藏
     */
    public void setSkipButtonVisible(boolean visible) {
        if (mSkipButton != null) {
            mSkipButton.setVisibility(visible ? VISIBLE : GONE);
        }
    }
    
    /**
     * 设置字幕按钮是否可见
     * @param visible true显示，false隐藏
     */
    public void setSubtitleButtonVisible(boolean visible) {
        if (mSubtitleToggle != null) {
            mSubtitleToggle.setVisibility(visible ? VISIBLE : GONE);
        }
    }
    
    /**
     * 设置弹幕区域是否可见
     * @param visible true显示，false隐藏
     */
    public void setDanmuContainerVisible(boolean visible) {
        if (mDanmuContainer != null) {
            mDanmuContainer.setVisibility(visible ? VISIBLE : GONE);
        }
    }
    
    /**
     * 设置下一集按钮是否可见
     * @param visible true显示，false隐藏
     */
    public void setPlayNextButtonVisible(boolean visible) {
        if (mPlayNext != null) {
            mPlayNext.setVisibility(visible ? VISIBLE : GONE);
        }
    }
    
    /**
     * 设置锁定按钮是否可见
     * @param visible true显示，false隐藏
     */
    public void setLockButtonVisible(boolean visible) {
        if (mLockButton != null) {
            mLockButton.setVisibility(visible ? VISIBLE : GONE);
        }
    }
    
    /**
     * 设置底部进度条是否可见
     * @param visible true显示，false隐藏
     */
    public void setBottomProgressVisible(boolean visible) {
        if (mBottomProgress != null) {
            mBottomProgress.setVisibility(visible ? VISIBLE : GONE);
        }
    }
    
    /**
     * 横竖屏切换按钮点击处理
     */
    private void onRotationButtonClick() {
        if (mOrangeController == null) {
            android.util.Log.w(TAG, "onRotationButtonClick: mOrangeController is null");
            return;
        }
        
        OrangevideoView videoView = mOrangeController.getVideoView();
        if (videoView == null) {
            android.util.Log.w(TAG, "onRotationButtonClick: videoView is null");
            return;
        }
        
        com.orange.playerlibrary.CustomFullscreenHelper helper = videoView.getFullscreenHelper();
        if (helper == null) {
            android.util.Log.w(TAG, "onRotationButtonClick: CustomFullscreenHelper is null");
            return;
        }
        
        // 根据当前全屏状态决定切换方向
        if (helper.isPortraitFullscreen()) {
            // 从竖屏全屏切换到横屏全屏
            android.util.Log.d(TAG, "onRotationButtonClick: Switching from portrait to landscape fullscreen");
            helper.stopPortraitFullScreen();
            
            // 延迟 100ms 后进入横屏全屏，等待退出动画完成
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mOrangeController != null) {
                        OrangevideoView v = mOrangeController.getVideoView();
                        if (v != null) {
                            com.orange.playerlibrary.CustomFullscreenHelper h = v.getFullscreenHelper();
                            if (h != null) {
                                h.startFullScreen();
                            }
                        }
                    }
                }
            }, 100);
        } else if (helper.isFullscreen()) {
            // 从横屏全屏切换到竖屏全屏
            android.util.Log.d(TAG, "onRotationButtonClick: Switching from landscape to portrait fullscreen");
            helper.stopFullScreen();
            
            // 延迟 100ms 后进入竖屏全屏，等待退出动画完成
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mOrangeController != null) {
                        OrangevideoView v = mOrangeController.getVideoView();
                        if (v != null) {
                            com.orange.playerlibrary.CustomFullscreenHelper h = v.getFullscreenHelper();
                            if (h != null) {
                                h.startPortraitFullScreen();
                            }
                        }
                    }
                }
            }, 100);
        } else {
            android.util.Log.w(TAG, "onRotationButtonClick: Not in fullscreen mode");
        }
    }
    
    /**
     * 更新横竖屏切换按钮可见性
     */
    public void updateRotationButtonVisibility() {
        if (mRotationButton == null) {
            android.util.Log.w(TAG, "updateRotationButtonVisibility: mRotationButton is null");
            return;
        }
        
        if (mOrangeController == null) {
            android.util.Log.w(TAG, "updateRotationButtonVisibility: mOrangeController is null");
            mRotationButton.setVisibility(GONE);
            return;
        }
        
        OrangevideoView videoView = mOrangeController.getVideoView();
        if (videoView == null) {
            android.util.Log.w(TAG, "updateRotationButtonVisibility: videoView is null");
            mRotationButton.setVisibility(GONE);
            return;
        }
        
        com.orange.playerlibrary.CustomFullscreenHelper helper = videoView.getFullscreenHelper();
        if (helper == null) {
            android.util.Log.e(TAG, "updateRotationButtonVisibility: helper is null! videoView=" + videoView);
            mRotationButton.setVisibility(GONE);
            return;
        }
        
        boolean isFullscreen = helper.isFullscreen();
        boolean isPortraitFullscreen = helper.isPortraitFullscreen();
        boolean shouldShow = false;
        
        // 根据显示模式决定是否显示按钮
        switch (mDisplayMode) {
            case ALWAYS:
                // 全屏模式下始终显示
                shouldShow = isFullscreen && !mIsLocked && mRotationButtonEnabled;
                break;
            case PORTRAIT_ONLY:
                // 仅竖屏全屏时显示
                shouldShow = isFullscreen && isPortraitFullscreen && !mIsLocked && mRotationButtonEnabled;
                break;
            case LANDSCAPE_ONLY:
                // 仅横屏全屏时显示
                shouldShow = isFullscreen && !isPortraitFullscreen && !mIsLocked && mRotationButtonEnabled;
                break;
            case NEVER:
                // 始终隐藏
                shouldShow = false;
                break;
        }
        
        mRotationButton.setVisibility(shouldShow ? VISIBLE : GONE);
        
    }
}

