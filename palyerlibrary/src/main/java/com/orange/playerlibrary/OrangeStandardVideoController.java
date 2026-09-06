package com.orange.playerlibrary;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.orange.playerlibrary.interfaces.ControlWrapper;
import com.orange.playerlibrary.interfaces.IControlComponent;

import java.util.ArrayList;

/**
 * 橘子播放器标准控制器基类
 * 提供基础控制功能：锁屏、UI显示控制等
 * 注意：加载动画由 OrangevideoView 直接控制，不在此类中处理
 * 
 * Requirements: 2.1, 2.3, 2.5
 */
public class OrangeStandardVideoController extends FrameLayout {

    private static final String TAG = "OrangeStandardController";

    // 控制组件容器
    protected FrameLayout mControlContainer;
    
    // 锁屏按钮
    protected ImageView mLockButton;
    
    // 横竖屏切换按钮
    protected ImageView mRotationButton;
    
    // 控制组件列表
    protected final ArrayList<IControlComponent> mControlComponents = new ArrayList<>();
    
    // 控制包装器
    protected ControlWrapper mControlWrapper;
    
    // 锁屏状态
    protected boolean mIsLocked = false;
    
    // UI 显示状态
    protected boolean mIsShowing = false;
    
    // 控制器可见性开关（临时禁用显示，但保留功能）
    protected boolean mControllerVisibilityEnabled = true;
    
    // 显示/隐藏动画
    protected Animation mShowAnim;
    protected Animation mHideAnim;
    
    // 默认显示超时时间（毫秒）
    protected int mDefaultTimeout = 4000;
    
    // 隐藏 Runnable
    private final Runnable mFadeOut = this::hide;

    public OrangeStandardVideoController(Context context) {
        super(context);
        init(context);
    }

    public OrangeStandardVideoController(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public OrangeStandardVideoController(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }


    /**
     * 初始化
     */
    protected void init(Context context) {
        // 加载布局
        LayoutInflater.from(context).inflate(getLayoutId(), this, true);
        
        // 初始化视图
        initViews();
        
        // 初始化动画
        initAnimations(context);
        
        // 设置点击事件
        setupClickListeners();
    }

    /**
     * 获取布局 ID
     * 子类可重写此方法返回自定义布局
     * 
     * @return 布局资源 ID
     */
    protected int getLayoutId() {
        return R.layout.layout_orange_standard_controller;
    }

    /**
     * 初始化视图
     */
    protected void initViews() {
        mControlContainer = findViewById(R.id.control_container);
        
        // 支持两种布局的锁屏按钮 ID
        mLockButton = findViewById(R.id.lock_screen);
        if (mLockButton == null) {
            mLockButton = findViewById(R.id.iv_lock);
        }
        
        // 初始化横竖屏切换按钮
        mRotationButton = findViewById(R.id.rotation_button);
        
        android.util.Log.d(TAG, "initViews: mLockButton=" + mLockButton + ", mRotationButton=" + mRotationButton);
    }

    /**
     * 初始化动画
     */
    protected void initAnimations(Context context) {
        mShowAnim = AnimationUtils.loadAnimation(context, android.R.anim.fade_in);
        mHideAnim = AnimationUtils.loadAnimation(context, android.R.anim.fade_out);
        mShowAnim.setDuration(300);
        mHideAnim.setDuration(300);
    }

    /**
     * 设置点击事件
     */
    protected void setupClickListeners() {
        if (mLockButton != null) {
            mLockButton.setOnClickListener(v -> toggleLockState());
        }
        
        if (mRotationButton != null) {
            mRotationButton.setOnClickListener(v -> onRotationButtonClick());
        }
    }

    // ==================== 锁屏功能 ====================

    /**
     * 设置锁屏状态
     * Requirements: 2.5
     * 
     * @param locked 是否锁屏
     */
    public void setLocked(boolean locked) {
        android.util.Log.d("OrangeStandard", "setLocked: locked=" + locked + ", old mIsLocked=" + mIsLocked);
        mIsLocked = locked;
        updateLockButtonState();
        onLockStateChanged(locked);
        android.util.Log.d("OrangeStandard", "setLocked done: mIsLocked=" + mIsLocked);
    }

    /**
     * 是否锁屏
     * Requirements: 2.5
     * 
     * @return true 锁屏状态
     */
    public boolean isLocked() {
        return mIsLocked;
    }

    /**
     * 切换锁屏状态
     */
    public void toggleLockState() {
        setLocked(!mIsLocked);
    }

    /**
     * 锁屏状态改变回调
     * Requirements: 2.5
     * 
     * @param locked 是否锁屏
     */
    protected void onLockStateChanged(boolean locked) {
        // 通知所有控制组件
        for (IControlComponent component : mControlComponents) {
            component.onLockStateChanged(locked);
        }
        
        // 锁屏时隐藏其他控制组件
        if (locked) {
            hideAllControlComponents();
        } else {
            showAllControlComponents();
        }
        
        // 更新横竖屏切换按钮可见性
        updateRotationButtonVisibility();
    }

    /**
     * 更新锁屏按钮状态
     */
    protected void updateLockButtonState() {
        if (mLockButton != null) {
            mLockButton.setImageResource(mIsLocked
                ? R.drawable.op_ic_lock
                : R.drawable.op_ic_lock_open);
        }
    }

    // ==================== 横竖屏切换功能 ====================

    /**
     * 横竖屏切换按钮点击处理
     * Requirements: 3.2, 3.3, 3.6, 3.7
     */
    protected void onRotationButtonClick() {
        // 空指针保护
        if (mControlWrapper == null) {
            android.util.Log.w(TAG, "onRotationButtonClick: mControlWrapper is null");
            return;
        }
        
        // 获取 CustomFullscreenHelper
        CustomFullscreenHelper helper = getFullscreenHelper();
        if (helper == null) {
            android.util.Log.w(TAG, "onRotationButtonClick: CustomFullscreenHelper is null");
            return;
        }
        
        // 状态检查：确保处于竖屏全屏模式
        if (!helper.isPortraitFullscreen()) {
            android.util.Log.w(TAG, "onRotationButtonClick: Not in portrait fullscreen mode");
            return;
        }
        
        android.util.Log.d(TAG, "onRotationButtonClick: Switching from portrait to landscape fullscreen");
        
        // 从竖屏全屏切换到横屏全屏
        // 先退出竖屏全屏
        helper.stopPortraitFullScreen();
        
        // 延迟 100ms 后进入横屏全屏，等待退出动画完成
        postDelayed(new Runnable() {
            @Override
            public void run() {
                CustomFullscreenHelper h = getFullscreenHelper();
                if (h != null) {
                    h.startFullScreen();
                }
            }
        }, 100);
    }

    /**
     * 获取全屏辅助类实例
     * Requirements: 3.6
     * 
     * @return CustomFullscreenHelper 实例，如果无法获取则返回 null
     */
    protected CustomFullscreenHelper getFullscreenHelper() {
        android.util.Log.d(TAG, "getFullscreenHelper: mControlWrapper=" + mControlWrapper);
        
        if (mControlWrapper == null) {
            android.util.Log.w(TAG, "getFullscreenHelper: mControlWrapper is null");
            return null;
        }
        
        // 方法1: 如果 mControlWrapper 是 OrangevideoView 实例
        if (mControlWrapper instanceof OrangevideoView) {
            OrangevideoView videoView = (OrangevideoView) mControlWrapper;
            CustomFullscreenHelper helper = videoView.getFullscreenHelper();
            android.util.Log.d(TAG, "getFullscreenHelper: got helper from OrangevideoView: " + helper);
            return helper;
        }
        
        // 方法2: 如果 mControlWrapper 是 OrangeVideoController 实例
        if (mControlWrapper instanceof OrangeVideoController) {
            OrangeVideoController controller = (OrangeVideoController) mControlWrapper;
            OrangevideoView videoView = controller.getVideoView();
            if (videoView != null) {
                CustomFullscreenHelper helper = videoView.getFullscreenHelper();
                android.util.Log.d(TAG, "getFullscreenHelper: got helper from OrangeVideoController: " + helper);
                return helper;
            }
        }
        
        android.util.Log.w(TAG, "getFullscreenHelper: mControlWrapper type not supported: " + mControlWrapper.getClass().getName());
        return null;
    }

    // ==================== UI 显示控制 ====================

    /**
     * 显示控制器
     * Requirements: 2.3
     */
    public void show() {
        // 如果控制器可见性被禁用，直接返回
        if (!mControllerVisibilityEnabled) {
            android.util.Log.d("OrangeStandard", "show() - controller visibility disabled, skip");
            return;
        }
        android.util.Log.d("OrangeStandard", "show() called, mIsLocked=" + mIsLocked + ", mIsShowing=" + mIsShowing);
        if (!mIsShowing) {
            mIsShowing = true;
            startShowAnimation();
            // 锁定状态下只通知锁定按钮显示，其他组件保持隐藏
            if (mIsLocked) {
                android.util.Log.d("OrangeStandard", "show() - locked, calling onLockVisibilityChanged(true)");
                onLockVisibilityChanged(true);
            } else {
                android.util.Log.d("OrangeStandard", "show() - not locked, calling onVisibilityChanged(true)");
                onVisibilityChanged(true, mShowAnim);
            }
        }
        // 重置隐藏计时器
        removeCallbacks(mFadeOut);
        postDelayed(mFadeOut, mDefaultTimeout);
    }

    /**
     * 隐藏控制器
     * Requirements: 2.3
     */
    public void hide() {
        android.util.Log.d("OrangeStandard", "hide() called, mIsLocked=" + mIsLocked + ", mIsShowing=" + mIsShowing);
        
        // 检查是否有组件正在拖动进度条，如果是则不隐藏
        for (IControlComponent component : mControlComponents) {
            if (component instanceof com.orange.playerlibrary.component.VodControlView) {
                com.orange.playerlibrary.component.VodControlView vodView = 
                    (com.orange.playerlibrary.component.VodControlView) component;
                if (vodView.isDragging()) {
                    android.util.Log.d("OrangeStandard", "hide() - VodControlView is dragging, skip hide");
                    return;
                }
            }
        }
        
        if (mIsShowing) {
            mIsShowing = false;
            startHideAnimation();
            // 锁定状态下只通知锁定按钮隐藏
            if (mIsLocked) {
                android.util.Log.d("OrangeStandard", "hide() - locked, calling onLockVisibilityChanged(false)");
                onLockVisibilityChanged(false);
            } else {
                android.util.Log.d("OrangeStandard", "hide() - not locked, calling onVisibilityChanged(false)");
                onVisibilityChanged(false, mHideAnim);
            }
        }
        removeCallbacks(mFadeOut);
    }
    
    /**
     * 锁定状态下的可见性变化（只影响 VodControlView 的锁定按钮）
     */
    protected void onLockVisibilityChanged(boolean isVisible) {
        android.util.Log.d("OrangeStandard", "onLockVisibilityChanged: isVisible=" + isVisible + ", components=" + mControlComponents.size());
        for (IControlComponent component : mControlComponents) {
            if (component instanceof com.orange.playerlibrary.component.VodControlView) {
                com.orange.playerlibrary.component.VodControlView vodView = 
                    (com.orange.playerlibrary.component.VodControlView) component;
                vodView.onLockVisibilityChanged(isVisible);
            }
        }
    }

    /**
     * 是否显示
     * Requirements: 2.3
     * 
     * @return true 显示状态
     */
    public boolean isShowing() {
        return mIsShowing;
    }
    
    /**
     * 设置显示状态（用于同步状态）
     * @param showing 是否显示
     */
    public void setShowing(boolean showing) {
        mIsShowing = showing;
    }

    /**
     * 切换显示状态
     */
    public void toggleShowState() {
        if (mIsShowing) {
            hide();
        } else {
            show();
        }
    }
    
    // ==================== 控制器可见性控制 ====================
    
    /**
     * 设置控制器可见性是否启用
     * 用于某些播放模式需要保留控制器功能但不显示UI
     * 
     * @param enabled true: 允许显示控制器(默认), false: 禁止显示控制器
     */
    public void setControllerVisibilityEnabled(boolean enabled) {
        mControllerVisibilityEnabled = enabled;
        android.util.Log.d("OrangeStandard", "setControllerVisibilityEnabled: " + enabled);
        
        // 如果禁用可见性，立即隐藏控制器
        if (!enabled && mIsShowing) {
            hide();
        }
    }
    
    /**
     * 控制器可见性是否启用
     * 
     * @return true: 允许显示, false: 禁止显示
     */
    public boolean isControllerVisibilityEnabled() {
        return mControllerVisibilityEnabled;
    }

    /**
     * 可见性改变回调
     * Requirements: 2.3
     * 
     * @param isVisible 是否可见
     * @param anim 动画
     */
    protected void onVisibilityChanged(boolean isVisible, Animation anim) {
        // 通知所有控制组件
        for (IControlComponent component : mControlComponents) {
            component.onVisibilityChanged(isVisible, anim);
        }
        
        // 更新锁屏按钮可见性
        updateLockButtonVisibility(isVisible);
        
        // 更新横竖屏切换按钮可见性
        updateRotationButtonVisibility();
    }


    /**
     * 开始显示动画
     */
    protected void startShowAnimation() {
        setVisibility(VISIBLE);
        if (mShowAnim != null) {
            startAnimation(mShowAnim);
        }
    }

    /**
     * 开始隐藏动画
     */
    protected void startHideAnimation() {
        if (mHideAnim != null) {
            startAnimation(mHideAnim);
            mHideAnim.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {}

                @Override
                public void onAnimationEnd(Animation animation) {
                    setVisibility(GONE);
                }

                @Override
                public void onAnimationRepeat(Animation animation) {}
            });
        } else {
            setVisibility(GONE);
        }
    }

    /**
     * 更新锁屏按钮可见性
     */
    protected void updateLockButtonVisibility(boolean isVisible) {
        if (mLockButton != null) {
            // 全屏模式下才显示锁屏按钮
            if (isFullScreen()) {
                mLockButton.setVisibility(isVisible ? VISIBLE : GONE);
            } else {
                mLockButton.setVisibility(GONE);
            }
        }
    }

    /**
     * 更新横竖屏切换按钮可见性
     * Requirements: 6.2, 6.3, 6.4
     */
    protected void updateRotationButtonVisibility() {
        android.util.Log.d(TAG, "updateRotationButtonVisibility: mRotationButton=" + mRotationButton);
        
        if (mRotationButton == null) {
            android.util.Log.w(TAG, "updateRotationButtonVisibility: mRotationButton is null!");
            return;
        }
        
        // 添加详细的按钮状态日志
        android.util.Log.d(TAG, "updateRotationButtonVisibility: button parent=" + mRotationButton.getParent());
        android.util.Log.d(TAG, "updateRotationButtonVisibility: button visibility=" + mRotationButton.getVisibility());
        android.util.Log.d(TAG, "updateRotationButtonVisibility: button width=" + mRotationButton.getWidth() + ", height=" + mRotationButton.getHeight());
        android.util.Log.d(TAG, "updateRotationButtonVisibility: button layoutParams=" + mRotationButton.getLayoutParams());
        
        // 获取 CustomFullscreenHelper 实例
        CustomFullscreenHelper helper = getFullscreenHelper();
        android.util.Log.d(TAG, "updateRotationButtonVisibility: helper=" + helper);
        
        if (helper == null) {
            android.util.Log.w(TAG, "updateRotationButtonVisibility: helper is null, hiding button");
            mRotationButton.setVisibility(GONE);
            return;
        }
        
        // 在全屏模式下显示按钮（横屏全屏或竖屏全屏），且未锁屏且控制器显示
        boolean shouldShow = helper.isFullscreen() 
                          && !mIsLocked 
                          && mIsShowing;
        
        mRotationButton.setVisibility(shouldShow ? VISIBLE : GONE);
        
        android.util.Log.d(TAG, "updateRotationButtonVisibility: shouldShow=" + shouldShow 
            + ", isFullscreen=" + helper.isFullscreen()
            + ", isPortraitFullscreen=" + helper.isPortraitFullscreen()
            + ", isLocked=" + mIsLocked
            + ", isShowing=" + mIsShowing);
        
        // 强制请求布局
        if (shouldShow) {
            mRotationButton.requestLayout();
            android.util.Log.d(TAG, "updateRotationButtonVisibility: requested layout");
        }
    }

    /**
     * 隐藏所有控制组件
     */
    protected void hideAllControlComponents() {
        for (IControlComponent component : mControlComponents) {
            View view = component.getView();
            if (view != null && view != mLockButton) {
                view.setVisibility(GONE);
            }
        }
    }

    /**
     * 显示所有控制组件
     */
    protected void showAllControlComponents() {
        for (IControlComponent component : mControlComponents) {
            View view = component.getView();
            if (view != null) {
                view.setVisibility(VISIBLE);
            }
        }
    }

    // ==================== 控制组件管理 ====================

    /**
     * 添加控制组件
     * 
     * @param components 控制组件
     */
    public void addControlComponent(IControlComponent... components) {
        for (IControlComponent component : components) {
            if (component != null && !mControlComponents.contains(component)) {
                mControlComponents.add(component);
                if (mControlWrapper != null) {
                    component.attach(mControlWrapper);
                }
                View view = component.getView();
                if (view != null && mControlContainer != null) {
                    mControlContainer.addView(view);
                }
            }
        }
    }
    
    /**
     * 添加控制组件（不添加视图到容器）
     * 用于弹幕等需要独立显示但仍需接收回调的组件
     * 
     * @param components 控制组件
     */
    public void addControlComponentWithoutView(IControlComponent... components) {
        for (IControlComponent component : components) {
            if (component != null && !mControlComponents.contains(component)) {
                mControlComponents.add(component);
                if (mControlWrapper != null) {
                    component.attach(mControlWrapper);
                }
                // 不添加视图到 mControlContainer
            }
        }
    }

    /**
     * 移除所有控制组件
     */
    public void removeAllControlComponent() {
        if (mControlContainer != null) {
            mControlContainer.removeAllViews();
        }
        mControlComponents.clear();
    }

    /**
     * 设置控制包装器
     * 
     * @param controlWrapper 控制包装器
     */
    public void setControlWrapper(ControlWrapper controlWrapper) {
        mControlWrapper = controlWrapper;
        // 通知所有已添加的组件
        for (IControlComponent component : mControlComponents) {
            component.attach(controlWrapper);
        }
    }

    /**
     * 获取控制包装器
     * 
     * @return 控制包装器
     */
    public ControlWrapper getControlWrapper() {
        return mControlWrapper;
    }

    // ==================== 状态回调 ====================

    /**
     * 播放状态改变
     * 
     * @param playState 播放状态
     */
    public void onPlayStateChanged(int playState) {
        // 通知所有控制组件
        for (IControlComponent component : mControlComponents) {
            component.onPlayStateChanged(playState);
        }
    }

    /**
     * 播放器状态改变
     * 
     * @param playerState 播放器状态
     */
    public void onPlayerStateChanged(int playerState) {
        // 更新锁屏按钮可见性
        updateLockButtonVisibility(mIsShowing);
        
        // 更新横竖屏切换按钮可见性
        updateRotationButtonVisibility();
        
        // 通知所有控制组件
        for (IControlComponent component : mControlComponents) {
            component.onPlayerStateChanged(playerState);
        }
    }

    /**
     * 设置播放进度
     * 
     * @param duration 总时长
     * @param position 当前位置
     */
    public void setProgress(int duration, int position) {
        for (IControlComponent component : mControlComponents) {
            component.setProgress(duration, position);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 是否全屏
     * 
     * @return true 全屏状态
     */
    protected boolean isFullScreen() {
        if (mControlWrapper != null) {
            return mControlWrapper.isFullScreen();
        }
        return false;
    }

    /**
     * 设置默认显示超时时间
     * 
     * @param timeout 超时时间（毫秒）
     */
    public void setDefaultTimeout(int timeout) {
        mDefaultTimeout = timeout;
    }

    /**
     * 获取默认显示超时时间
     * 
     * @return 超时时间（毫秒）
     */
    public int getDefaultTimeout() {
        return mDefaultTimeout;
    }

    // ==================== 进度和淡出控制 ====================

    /**
     * 停止进度更新
     */
    public void stopProgress() {
        // 进度更新由播放器内部控制，这里主要用于拖动时暂停更新
    }

    /**
     * 开始进度更新
     */
    public void startProgress() {
        // 进度更新由播放器内部控制
    }

    /**
     * 停止自动隐藏倒计时
     */
    public void stopFadeOut() {
        removeCallbacks(mFadeOut);
    }

    /**
     * 开始自动隐藏倒计时
     */
    public void startFadeOut() {
        removeCallbacks(mFadeOut);
        postDelayed(mFadeOut, mDefaultTimeout);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(mFadeOut);
    }
}
