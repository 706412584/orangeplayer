package com.orange.playerlibrary;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.util.AttributeSet;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;

import com.orange.playerlibrary.interfaces.ControlWrapper;
import com.orange.playerlibrary.interfaces.IControlComponent;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * 橘子播放器完整控制器
 * 继承 OrangeStandardVideoController，管理 UI 组件和手势交互
 * 实现 ControlWrapper 接口，提供播放器控制方法
 * 
 * Requirements: 2.1, 2.2, 2.6, 2.8, 2.9
 */
public class OrangeVideoController extends OrangeStandardVideoController implements ControlWrapper {

    private static final String TAG = "OrangeVideoController";
    
    // ===== 调试模式 =====
    private static boolean sDebug = false;
    
    // ===== 视频标题 =====
    private String mVideoTitle = "";
    
    // ===== 视频列表（集数管理）=====
    private ArrayList<HashMap<String, Object>> mVideoList;
    
    // ===== 缩略图 =====
    private Object mThumbnail;
    
    // ===== 关联的播放器视图 =====
    private OrangevideoView mVideoView;
    
    // ===== 事件管理器 =====
    private VideoEventManager mVideoEventManager;
    
    // ===== 竖屏全屏相关 =====
    private boolean mIsPortraitFullScreen = false;
    private int mOriginalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    
    // ===== 弹幕相关 =====
    private boolean mIsAddDanmu = false;
    private com.orange.playerlibrary.interfaces.IDanmakuController mDanmakuController;
    
    // ===== 字幕相关 =====
    private com.orange.playerlibrary.subtitle.SubtitleManager mSubtitleManager;
    
    // ===== 嗅探相关 =====
    private com.orange.playerlibrary.component.SniffingView mSniffingView;
    
    // ===== 预览功能 =====
    private boolean mPreViewEnabled = false;
    
    // ===== 加载动画类型 =====
    private IndicatorType mCurrentIndicatorType = IndicatorType.LINE_SCALE_PULSE_OUT;

    // ===== 构造函数 (Requirements: 2.1) =====

    public OrangeVideoController(Context context) {
        super(context);
        initController(context);
    }

    public OrangeVideoController(Context context, AttributeSet attrs) {
        super(context, attrs);
        initController(context);
    }

    public OrangeVideoController(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initController(context);
    }

    /**
     * 初始化控制器
     * Requirements: 2.1
     */
    private void initController(Context context) {
        mVideoList = new ArrayList<>();
    }
    
    /**
     * 设置关联的播放器视图并初始化事件管理器
     * 
     * @param videoView 播放器视图
     */
    public void setVideoView(OrangevideoView videoView) {
        mVideoView = videoView;
        
        // 初始化 VideoEventManager
        if (mVideoEventManager == null && videoView != null) {
            mVideoEventManager = new VideoEventManager(getContext(), videoView, this);
            debug("VideoEventManager initialized");
        }
        
        // 应用默认加载动画
        if (mCurrentIndicatorType != null && videoView != null) {
            setLoading(mCurrentIndicatorType);
        }
    }
    
    /**
     * 获取事件管理器
     * 
     * @return 事件管理器
     */
    public VideoEventManager getVideoEventManager() {
        return mVideoEventManager;
    }


    // ===== 控制组件管理 (Requirements: 2.2) =====

    /**
     * 添加控制组件
     * Requirements: 2.2
     * 
     * @param components 控制组件
     */
    @Override
    public void addControlComponent(IControlComponent... components) {
        super.addControlComponent(components);
    }

    /**
     * 移除所有控制组件
     * Requirements: 2.2
     */
    @Override
    public void removeAllControlComponent() {
        super.removeAllControlComponent();
    }

    /**
     * 添加默认控制组件
     * Requirements: 2.2
     * 
     * @param title 视频标题
     * @param isLive 是否直播模式
     */
    public void addDefaultControlComponent(String title, boolean isLive) {
        // 清除现有组件
        removeAllControlComponent();
        
        // 设置标题
        mVideoTitle = title;
        
        // 创建 VodControlView
        com.orange.playerlibrary.component.VodControlView vodControlView = 
                new com.orange.playerlibrary.component.VodControlView(getContext());
        vodControlView.setOrangeVideoController(this);
        addControlComponent(vodControlView);
        // 如果 VideoEventManager 已初始化，绑定控制器组件
        if (mVideoEventManager != null) {
            mVideoEventManager.bindControllerComponents(vodControlView);
            
            // 绑定 TitleView（如果存在）
            if (mVideoView != null) {
                com.orange.playerlibrary.component.TitleView titleView = mVideoView.getTitleView();
                if (titleView != null) {
                    mVideoEventManager.bindTitleView(titleView);
                }
            }
            
            debug("VideoEventManager bound to VodControlView");
        } else {
        }
        
        // 自动初始化弹幕功能（如果弹幕库可用）
        if (mDanmakuController == null) {
            initDanmaku();
        }
        
        debug("addDefaultControlComponent: title=" + title + ", isLive=" + isLive);
    }

    // ===== 视频集数管理 (Requirements: 2.6) =====

    /**
     * 添加视频到列表
     * Requirements: 2.6
     * 
     * @param name 视频名称
     * @param url 视频地址
     */
    public synchronized void addVideo(String name, String url) {
        addVideo(name, url, null);
    }

    /**
     * 添加视频到列表（带请求头）
     * Requirements: 2.6
     * 
     * @param name 视频名称
     * @param url 视频地址
     * @param headers 请求头
     */
    public synchronized void addVideo(String name, String url, HashMap<String, String> headers) {
        if (mVideoList == null) {
            mVideoList = new ArrayList<>();
        }
        
        HashMap<String, Object> video = new HashMap<>();
        video.put("name", name);
        video.put("url", url);
        video.put("headers", headers != null ? headers : new HashMap<>());
        mVideoList.add(video);
        
        debug("addVideo: name=" + name + ", url=" + url);
        
        // 如果是第一个视频且播放器还没有设置视频地址，自动设置第一个视频
        if (mVideoList.size() == 1 && mVideoView != null) {
            String currentUrl = mVideoView.getUrl();
            if (currentUrl == null || currentUrl.isEmpty()) {
                mVideoView.setUrl(url, headers);
                setVideoTitle(name);
                debug("Auto set first video: " + name);
            }
        }
    }

    /**
     * 添加独立视频到列表
     * 
     * @param name 视频名称
     * @param url 视频地址
     * @param isIndependent 是否独立视频
     */
    public synchronized void addVideo(String name, String url, boolean isIndependent) {
        addVideo(name, url, isIndependent, null);
    }

    /**
     * 添加独立视频到列表（带请求头）
     * 
     * @param name 视频名称
     * @param url 视频地址
     * @param isIndependent 是否独立视频
     * @param headers 请求头
     */
    public synchronized void addVideo(String name, String url, boolean isIndependent, HashMap<String, String> headers) {
        if (mVideoList == null) {
            mVideoList = new ArrayList<>();
        }
        
        HashMap<String, Object> video = new HashMap<>();
        video.put("name", name);
        video.put("url", url);
        video.put("headers", headers != null ? headers : new HashMap<>());
        if (isIndependent) {
            video.put("dlsp", "独立");
        }
        mVideoList.add(video);
    }

    /**
     * 获取视频列表
     * Requirements: 2.6
     * 
     * @return 视频列表
     */
    public synchronized ArrayList<HashMap<String, Object>> getVideoList() {
        return mVideoList;
    }

    /**
     * 设置视频列表
     * 
     * @param list 视频列表
     */
    public synchronized void setVideoList(ArrayList<HashMap<String, Object>> list) {
        if (list != null) {
            // 确保每个视频都有 headers 字段
            for (HashMap<String, Object> item : list) {
                if (!item.containsKey("headers")) {
                    item.put("headers", new HashMap<>());
                }
            }
        }
        mVideoList = list;
        // 不再自动设置第一个视频，避免干扰后续播放
        // 如果需要自动播放第一个，请手动调用 mVideoView.setUp() 和 startPlayLogic()
    }

    /**
     * 清空视频列表
     * Requirements: 2.6
     */
    public synchronized void removeVideoList() {
        if (mVideoList != null) {
            mVideoList.clear();
        }
        mVideoList = null;
    }

    /**
     * 获取指定位置视频的请求头
     * 
     * @param position 位置索引
     * @return 请求头
     */
    @SuppressWarnings("unchecked")
    public synchronized HashMap<String, String> getVideoHeaders(int position) {
        if (mVideoList == null || position < 0 || position >= mVideoList.size()) {
            return new HashMap<>();
        }
        
        HashMap<String, Object> item = mVideoList.get(position);
        Object headers = item.get("headers");
        return headers instanceof HashMap ? (HashMap<String, String>) headers : new HashMap<>();
    }


    // ===== 全屏控制 (Requirements: 2.8) =====

    /**
     * 进入全屏
     * Requirements: 2.8
     * 
     * @return true 成功
     */
    public boolean startFullScreen() {
        if (mVideoView != null) {
            mVideoView.startFullScreen();
            return true;
        }
        return false;
    }

    /**
     * 退出全屏
     * Requirements: 2.8
     * 
     * @return true 成功
     */
    public boolean stopFullScreen() {
        if (mIsPortraitFullScreen) {
            exitPortraitFullScreen();
            return true;
        }
        
        if (mVideoView != null) {
            mVideoView.stopFullScreen();
            return true;
        }
        return false;
    }

    /**
     * 切换全屏状态
     * 实现 ControlWrapper 接口
     */
    public void toggleFullScreen() {
        if (mVideoView != null) {
            if (mVideoView.isFullScreen() || mIsPortraitFullScreen) {
                stopFullScreen();
            } else {
                startFullScreen();
            }
        }
    }

    /**
     * 进入竖屏全屏
     * Requirements: 2.8
     */
    public void startPortraitFullScreen() {
        Activity activity = getActivity();
        if (activity == null) return;
        
        // 保存原始方向
        mOriginalOrientation = activity.getRequestedOrientation();
        
        // 设置竖屏
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        
        // 进入全屏
        if (mVideoView != null) {
            mVideoView.startFullScreen();
        }
        
        mIsPortraitFullScreen = true;
        
        // 锁定竖屏方向
        lockPortraitOrientation(activity);
        
        debug("startPortraitFullScreen");
    }

    /**
     * 退出竖屏全屏
     * Requirements: 2.8
     */
    public void exitPortraitFullScreen() {
        Activity activity = getActivity();
        if (activity == null || !mIsPortraitFullScreen) return;
        
        mIsPortraitFullScreen = false;
        
        // 退出全屏
        if (mVideoView != null) {
            mVideoView.stopFullScreen();
        }
        
        // 恢复原始方向
        activity.setRequestedOrientation(mOriginalOrientation);
        
        debug("exitPortraitFullScreen");
    }

    /**
     * 是否处于竖屏全屏状态
     * 
     * @return true 竖屏全屏
     */
    public boolean isPortraitFullScreen() {
        return mIsPortraitFullScreen;
    }

    /**
     * 锁定竖屏方向
     */
    private void lockPortraitOrientation(Activity activity) {
        if (activity == null) return;
        
        // Activity 层面锁定
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        
        // Window 层面锁定
        Window window = activity.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            window.setAttributes(params);
            window.setWindowAnimations(0);
        }
    }

    // ===== 状态回调通知 (Requirements: 2.9) =====

    /**
     * 播放状态改变回调
     * Requirements: 2.9
     * 
     * @param playState 播放状态
     */
    @Override
    public void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        
        // 处理特殊状态
        switch (playState) {
            case PlayerConstants.STATE_PLAYING:
                // 播放开始，可以加载弹幕
                if (!mIsAddDanmu) {
                    mIsAddDanmu = true;
                    debug("onPlayStateChanged: STATE_PLAYING, danmu ready");
                }
                break;
            case PlayerConstants.STATE_STARTSNIFFING:
                // 开始嗅探
                // 注意：加载动画由 OrangevideoView 控制
                debug("onPlayStateChanged: STATE_STARTSNIFFING");
                break;
            case PlayerConstants.STATE_ENDSNIFFING:
                // 结束嗅探
                // 注意：加载动画由 OrangevideoView 控制
                debug("onPlayStateChanged: STATE_ENDSNIFFING");
                break;
            case PlayerConstants.STATE_M3U8_AD_REMOVAL:
                // 开始 M3U8 去广告处理
                // 注意：加载动画由 OrangevideoView 控制
                debug("onPlayStateChanged: STATE_M3U8_AD_REMOVAL");
                break;
            case PlayerConstants.STATE_M3U8_AD_REMOVAL_END:
                // 结束 M3U8 去广告处理
                // 注意：加载动画由 OrangevideoView 控制
                debug("onPlayStateChanged: STATE_M3U8_AD_REMOVAL_END");
                break;
        }
        
        debug("onPlayStateChanged: " + playState);
    }

    /**
     * 播放器状态改变回调
     * Requirements: 2.9
     * 
     * @param playerState 播放器状态
     */
    @Override
    public void onPlayerStateChanged(int playerState) {
        super.onPlayerStateChanged(playerState);
        
        debug("onPlayerStateChanged: " + playerState);
    }


    // ===== 视频标题管理 =====

    /**
     * 设置视频标题
     * 
     * @param title 标题
     */
    public void setVideoTitle(String title) {
        mVideoTitle = title;
        // 更新 TitleView 显示
        updateTitleViewDisplay(title);
    }

    /**
     * 获取视频标题
     * 
     * @return 标题
     */
    public String getVideoTitle() {
        return mVideoTitle;
    }

    /**
     * 设置标题（兼容原 API，现在直接设置标题）
     * 
     * @param title 标题
     */
    public void setTitle(String title) {
        mVideoTitle = title;
        // 更新 TitleView 显示
        updateTitleViewDisplay(mVideoTitle);
    }
    
    /**
     * 更新 TitleView 显示
     * 遍历父容器找到正确的 TitleView 实例（避免全屏模式下的多实例问题）
     */
    private void updateTitleViewDisplay(String title) {
        if (title == null || title.isEmpty()) {
            return;
        }
        
        // 方案一：通过 VideoView 获取 TitleView（可能是旧实例）
        if (mVideoView != null) {
            com.orange.playerlibrary.component.TitleView titleView = mVideoView.getTitleView();
            if (titleView != null && titleView.getWindowToken() != null) {
                titleView.setTitle(title);
                return;
            }
        }
        
        // 方案二：遍历父容器找到正确的 TitleView 实例
        if (mVideoView != null) {
            android.view.ViewParent parent = mVideoView.getParent();
            if (parent instanceof android.view.ViewGroup) {
                android.view.ViewGroup container = (android.view.ViewGroup) parent;
                for (int i = 0; i < container.getChildCount(); i++) {
                    android.view.View child = container.getChildAt(i);
                    if (child instanceof com.orange.playerlibrary.component.TitleView) {
                        com.orange.playerlibrary.component.TitleView titleView = 
                            (com.orange.playerlibrary.component.TitleView) child;
                        if (titleView.getWindowToken() != null) {
                            titleView.setTitle(title);
                            return;
                        }
                    }
                }
            }
        }
    }

    // ===== 缩略图管理 =====

    /**
     * 设置缩略图并更新PrepareView
     * 
     * @param thumbnail 缩略图（Bitmap、资源ID、URL字符串、File对象）
     */
    public void setThumbnail(Object thumbnail) {
        mThumbnail = thumbnail;
        // 同步更新PrepareView的缩略图
        if (mVideoView != null) {
            com.orange.playerlibrary.component.PrepareView prepareView = mVideoView.getPrepareView();
            if (prepareView != null) {
                prepareView.setThumbnail(thumbnail);
            }
        }
    }

    /**
     * 获取缩略图
     * 
     * @return 缩略图
     */
    public Object getThumbnail() {
        return mThumbnail;
    }

    // ===== 播放器视图关联 =====

    /**
     * 获取关联的播放器视图
     * 
     * @return 播放器视图
     */
    public OrangevideoView getVideoView() {
        return mVideoView;
    }

    // ===== 弹幕相关 =====

    /**
     * 设置是否添加弹幕
     * 
     * @param add 是否添加
     */
    public void isaddDanmu(boolean add) {
        mIsAddDanmu = add;
    }

    /**
     * 是否已添加弹幕
     * 
     * @return true 已添加
     */
    public boolean isAddDanmu() {
        return mIsAddDanmu;
    }
    
    /**
     * 设置弹幕控制器
     * App 层实现 IDanmakuController 接口后，通过此方法设置
     * 
     * @param controller 弹幕控制器
     */
    public void setDanmakuController(com.orange.playerlibrary.interfaces.IDanmakuController controller) {
        mDanmakuController = controller;
        debug("setDanmakuController: " + controller);
    }
    
    /**
     * 获取弹幕控制器
     * 
     * @return 弹幕控制器，如果未设置返回 null
     */
    public com.orange.playerlibrary.interfaces.IDanmakuController getDanmakuController() {
        return mDanmakuController;
    }
    
    /**
     * 检查弹幕功能是否可用
     * 
     * @return true 如果弹幕库已导入且控制器已设置
     */
    public boolean isDanmakuAvailable() {
        return DanmakuHelper.isDanmakuLibraryAvailable() && mDanmakuController != null;
    }
    
    /**
     * 初始化弹幕功能（SDK 层封装）
     * 自动检测弹幕库是否可用，创建控制器并附加到播放器
     * 
     * @return 弹幕控制器，如果弹幕库不可用返回 null
     */
    public DanmakuControllerImpl initDanmaku() {
        if (!DanmakuHelper.isDanmakuLibraryAvailable()) {
            debug("initDanmaku: 弹幕库不可用");
            return null;
        }
        
        if (mVideoView == null) {
            debug("initDanmaku: mVideoView 为 null");
            return null;
        }
        
        // 创建弹幕控制器
        DanmakuControllerImpl controller = new DanmakuControllerImpl(getContext());
        
        // 附加到播放器容器
        controller.attachToContainer(mVideoView);
        
        // 设置到控制器
        setDanmakuController(controller);
        
        debug("initDanmaku: 弹幕初始化完成");
        return controller;
    }
    
    /**
     * 释放弹幕资源
     * 在 Activity onDestroy 时调用
     */
    public void releaseDanmaku() {
        if (mDanmakuController instanceof DanmakuControllerImpl) {
            DanmakuControllerImpl impl = (DanmakuControllerImpl) mDanmakuController;
            impl.releaseDanmaku();
            impl.detachFromContainer();
        }
        mDanmakuController = null;
        debug("releaseDanmaku: 弹幕资源已释放");
    }

    // ===== 预览功能 =====

    /**
     * 设置是否启用预览
     * 
     * @param enabled 是否启用
     */
    public void setPreViewEnabled(boolean enabled) {
        mPreViewEnabled = enabled;
    }

    /**
     * 是否启用预览
     * 
     * @return true 启用
     */
    public boolean isPreViewEnabled() {
        return mPreViewEnabled;
    }

    // ===== 加载动画类型 =====

    /**
     * 加载动画类型枚举
     */
    public enum IndicatorType {
        BALL_BEAT(1, "BallBeatIndicator"),
        BALL_CLIP_ROTATE(2, "BallClipRotateIndicator"),
        BALL_CLIP_ROTATE_MULTIPLE(3, "BallClipRotateMultipleIndicator"),
        BALL_CLIP_ROTATE_PULSE(4, "BallClipRotatePulseIndicator"),
        BALL_GRID_BEAT(5, "BallGridBeatIndicator"),
        BALL_GRID_PULSE(6, "BallGridPulseIndicator"),
        BALL_PULSE(7, "BallPulseIndicator"),
        BALL_PULSE_RISE(8, "BallPulseRiseIndicator"),
        BALL_PULSE_SYNC(9, "BallPulseSyncIndicator"),
        BALL_ROTATE(10, "BallRotateIndicator"),
        BALL_SCALE(11, "BallScaleIndicator"),
        BALL_SCALE_MULTIPLE(12, "BallScaleMultipleIndicator"),
        BALL_SCALE_RIPPLE(13, "BallScaleRippleIndicator"),
        BALL_SCALE_RIPPLE_MULTIPLE(14, "BallScaleRippleMultipleIndicator"),
        BALL_SPIN_FADE_LOADER(15, "BallSpinFadeLoaderIndicator"),
        BALL_TRIANGLE_PATH(16, "BallTrianglePathIndicator"),
        BALL_ZIG_ZAG_DEFLECT(17, "BallZigZagDeflectIndicator"),
        BALL_ZIG_ZAG(18, "BallZigZagIndicator"),
        CUBE_TRANSITION(19, "CubeTransitionIndicator"),
        LINE_SCALE(20, "LineScaleIndicator"),
        LINE_SCALE_PARTY(21, "LineScalePartyIndicator"),
        LINE_SCALE_PULSE_OUT(22, "LineScalePulseOutIndicator"),
        LINE_SCALE_PULSE_OUT_RAPID(23, "LineScalePulseOutRapidIndicator"),
        LINE_SPIN_FADE_LOADER(24, "LineSpinFadeLoaderIndicator"),
        PACMAN(25, "PacmanIndicator"),
        SEMI_CIRCLE_SPIN(26, "SemiCircleSpinIndicator"),
        SQUARE_SPIN(27, "SquareSpinIndicator"),
        TRIANGLE_SKEW_SPIN(28, "TriangleSkewSpinIndicator");

        private final int id;
        private final String indicatorName;

        IndicatorType(int id, String indicatorName) {
            this.id = id;
            this.indicatorName = indicatorName;
        }

        public int getId() {
            return id;
        }

        public String getIndicatorName() {
            return indicatorName;
        }

        public static IndicatorType findById(int id) {
            for (IndicatorType type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * 设置加载动画类型
     * Requirements: 6.5 - THE OrangeVideoController SHALL 支持多种加载动画样式 (setLoading)
     * 
     * @param type 动画类型
     */
    public void setLoading(IndicatorType type) {
        if (type == null) {
            debug("setLoading: type is null, using default");
            type = IndicatorType.LINE_SCALE_PULSE_OUT;
        }
        mCurrentIndicatorType = type;
        
        // 创建并设置指示器
        com.orange.playerlibrary.loading.Indicator indicator = 
                com.orange.playerlibrary.loading.IndicatorFactory.createIndicator(type);
        
        // 通过 mVideoView 设置加载动画指示器
        if (mVideoView != null) {
            mVideoView.setLoadingIndicator(indicator);
        }
        
        debug("setLoading: " + type.getIndicatorName());
    }

    /**
     * 设置加载动画类型（通过 ID）
     * 
     * @param typeId 动画类型 ID
     */
    public void setLoading(int typeId) {
        IndicatorType type = IndicatorType.findById(typeId);
        setLoading(type);
    }

    /**
     * 获取当前加载动画类型
     * @return 加载动画类型
     */
    public IndicatorType getCurrentIndicatorType() {
        return mCurrentIndicatorType;
    }


    // ===== 播放列表可见性 =====

    /**
     * 设置播放列表可见性
     * 
     * @param visible 是否可见
     */
    public void setplaylistVisibility(boolean visible) {
        // TODO: 实现播放列表可见性控制
        debug("setplaylistVisibility: " + visible);
    }

    /**
     * 设置加载速度文本
     * 
     * @param text 文本
     */
    public void setLoadingSpeedText(String text) {
        // 加载速度文本由 OrangevideoView 控制
        debug("setLoadingSpeedText: " + text);
    }

    // ===== 辅助方法 =====

    /**
     * 获取 Activity
     * 
     * @return Activity
     */
    public Activity getActivity() {
        Context context = getContext();
        if (context instanceof Activity) {
            return (Activity) context;
        }
        return null;
    }

    /**
     * 是否直播模式
     * 
     * @return true 直播
     */
    public boolean isLiveVideoModel() {
        if (mVideoView != null) {
            return mVideoView.isLiveVideo();
        }
        return false;
    }

    // ===== 调试相关 =====

    /**
     * 设置调试模式
     * 
     * @param debug 是否调试
     */
    public static void setdebug(boolean debug) {
        sDebug = debug;
    }

    /**
     * 设置调试模式（兼容原 API）
     * 
     * @param debug 是否调试
     */
    public static void setDebug(boolean debug) {
        sDebug = debug;
    }

    /**
     * 是否调试模式
     * 
     * @return true 调试模式
     */
    public static boolean isdebug() {
        return sDebug;
    }

    /**
     * 调试日志
     * 
     * @param message 日志信息
     */
    protected void debug(Object message) {
        if (sDebug && sDebugLogger != null) {
            sDebugLogger.log(TAG, message);
        }
    }

    /**
     * 调试日志接口
     */
    public interface DebugLogger {
        void log(String tag, Object message);
    }

    /**
     * 默认调试日志实现
     */
    public static DebugLogger sDebugLogger = new DebugLogger() {
        @Override
        public void log(String tag, Object message) {
            if (sDebug) {
            }
        }
    };

    /**
     * 设置调试日志实现
     * 
     * @param logger 日志实现
     */
    public static void setDebugLogger(DebugLogger logger) {
        if (logger != null) {
            sDebugLogger = logger;
        }
    }
    
    // ===== 字幕功能 =====
    
    /**
     * 获取字幕管理器
     */
    public com.orange.playerlibrary.subtitle.SubtitleManager getSubtitleManager() {
        if (mSubtitleManager == null) {
            mSubtitleManager = new com.orange.playerlibrary.subtitle.SubtitleManager(getContext());
            // 绑定到播放器容器
            if (mVideoView != null) {
                mSubtitleManager.attachToPlayer(mVideoView);
                // 设置进度提供者
                mSubtitleManager.setProgressProvider(new com.orange.playerlibrary.subtitle.SubtitleManager.ProgressProvider() {
                    @Override
                    public long getCurrentPosition() {
                        return mVideoView != null ? mVideoView.getCurrentPositionWhenPlaying() : 0;
                    }
                    
                    @Override
                    public boolean isPlaying() {
                        return mVideoView != null && mVideoView.isPlaying();
                    }
                });
            }
        }
        return mSubtitleManager;
    }
    
    /**
     * 重新附加字幕视图到新的播放器容器（用于全屏切换）
     * 解决全屏模式下 SubtitleView 仍附加在旧播放器上导致字幕不显示的问题
     * @param newPlayerContainer 新的播放器容器（全屏播放器）
     */
    public void reattachSubtitleView(android.view.ViewGroup newPlayerContainer) {
        if (mSubtitleManager != null && newPlayerContainer != null) {
            mSubtitleManager.reattachToPlayer(newPlayerContainer);
        }
    }
    
    /**
     * 加载字幕文件
     * @param url 字幕文件 URL 或本地路径
     */
    public void loadSubtitle(String url) {
        loadSubtitle(url, null);
    }
    
    /**
     * 加载字幕文件
     * @param url 字幕文件 URL 或本地路径
     * @param listener 加载监听器
     */
    public void loadSubtitle(String url, com.orange.playerlibrary.subtitle.SubtitleManager.OnSubtitleLoadListener listener) {
        getSubtitleManager().loadSubtitle(url, listener);
    }
    
    /**
     * 显示/隐藏字幕
     */
    public void toggleSubtitle() {
        com.orange.playerlibrary.subtitle.SubtitleManager subtitleManager = getSubtitleManager();
        subtitleManager.toggle();
        PlayerSettingsManager.getInstance(getContext())
                .setSubtitleEnabled(subtitleManager.isEnabled());
    }
    
    /**
     * 字幕是否启用
     */
    public boolean isSubtitleEnabled() {
        return mSubtitleManager != null && mSubtitleManager.isEnabled();
    }
    
    /**
     * 字幕是否已加载
     */
    public boolean isSubtitleLoaded() {
        return mSubtitleManager != null && mSubtitleManager.isLoaded();
    }
    
    /**
     * 开始字幕更新
     */
    public void startSubtitle() {
        if (mSubtitleManager != null) {
            mSubtitleManager.start();
        }
    }
    
    /**
     * 停止字幕更新
     */
    public void stopSubtitle() {
        if (mSubtitleManager != null) {
            mSubtitleManager.stop();
        }
    }
    
    /**
     * 释放字幕资源
     */
    public void releaseSubtitle() {
        if (mSubtitleManager != null) {
            mSubtitleManager.release();
            mSubtitleManager = null;
        }
    }
    
    // ===== 锁定功能 =====
    
    /**
     * 设置锁定状态
     * @param locked 是否锁定
     */
    @Override
    public void setLocked(boolean locked) {
        // 调用父类方法设置 mIsLocked 并通知组件
        super.setLocked(locked);
        debug("setLocked: " + locked);
    }
    
    /**
     * 内部设置锁定状态（只更新状态，不触发 UI 操作）
     * 用于从 VodControlView 回调时避免循环调用
     */
    public void setLockedInternal(boolean locked) {
        mIsLocked = locked;
        debug("setLockedInternal: " + locked);
    }
    
    /**
     * 切换锁定状态
     */
    @Override
    public void toggleLockState() {
        setLocked(!isLocked());
    }
    
    // ===== 嗅探功能 =====
    
    /**
     * 获取嗅探视图组件
     */
    public com.orange.playerlibrary.component.SniffingView getSniffingView() {
        return mSniffingView;
    }
    
    /**
     * 设置嗅探视图组件
     */
    public void setSniffingView(com.orange.playerlibrary.component.SniffingView sniffingView) {
        mSniffingView = sniffingView;
    }
    
    /**
     * 显示嗅探视图
     */
    public void showSniffingView() {
        if (mSniffingView != null) {
            mSniffingView.show();
        }
    }
    
    /**
     * 隐藏嗅探视图
     */
    public void hideSniffingView() {
        if (mSniffingView != null) {
            mSniffingView.hide();
        }
    }
    
    /**
     * 更新嗅探按钮状态（根据持久化记录）
     */
    public void updateSniffingButton() {
        if (mVideoView != null) {
            com.orange.playerlibrary.component.TitleView titleView = mVideoView.getTitleView();
            if (titleView != null && mSniffingView != null) {
                titleView.updateSniffingButton(mSniffingView.getResultCount() > 0);
            }
        }
    }
    
    // ==================== ControlWrapper 接口实现 ====================
    
    @Override
    public void start() {
        if (mVideoView != null) {
            mVideoView.startPlayLogic();
        }
    }
    
    @Override
    public void pause() {
        if (mVideoView != null) {
            mVideoView.onVideoPause();
        }
    }
    
    @Override
    public void seekTo(long position) {
        if (mVideoView != null) {
            mVideoView.seekTo(position);
        }
    }
    
    @Override
    public long getDuration() {
        return mVideoView != null ? mVideoView.getDuration() : 0;
    }
    
    @Override
    public long getCurrentPosition() {
        return mVideoView != null ? mVideoView.getCurrentPositionWhenPlaying() : 0;
    }
    
    @Override
    public boolean isPlaying() {
        return mVideoView != null && mVideoView.isPlaying();
    }
    
    @Override
    public void togglePlay() {
        if (mVideoView != null) {
            if (mVideoView.isPlaying()) {
                mVideoView.onVideoPause();
            } else {
                mVideoView.startPlayLogic();
            }
        }
    }
    
    @Override
    public boolean isFullScreen() {
        return mVideoView != null && (mVideoView.isFullScreen() || mIsPortraitFullScreen);
    }
    
    @Override
    public void setSpeed(float speed) {
        if (mVideoView != null) {
            mVideoView.setSpeed(speed);
        }
    }
    
    @Override
    public float getSpeed() {
        return mVideoView != null ? mVideoView.getSpeed() : 1.0f;
    }
    
    @Override
    public int getBufferedPercentage() {
        return mVideoView != null ? mVideoView.getBufferedPercentage() : 0;
    }
    
    @Override
    public void setMute(boolean isMute) {
        if (mVideoView != null) {
            mVideoView.setMute(isMute);
        }
    }
    
    @Override
    public boolean isMute() {
        return mVideoView != null && mVideoView.isMute();
    }
    
    @Override
    public void setVolume(float volume) {
        // OrangevideoView doesn't have setVolume method, use setMute instead
        if (mVideoView != null) {
            if (volume == 0) {
                mVideoView.setMute(true);
            } else {
                mVideoView.setMute(false);
            }
        }
    }
    
    @Override
    public void replay(boolean resetPosition) {
        if (mVideoView != null) {
            mVideoView.onVideoReset();
            if (resetPosition) {
                mVideoView.seekTo(0);
            }
            mVideoView.startPlayLogic();
        }
    }
    
    @Override
    public void hide() {
        super.hide();
    }
    
    @Override
    public void show() {
        super.show();
    }
    
    @Override
    public boolean isShowing() {
        return super.isShowing();
    }
    
    @Override
    public void stopProgress() {
        super.stopProgress();
    }
    
    @Override
    public void startProgress() {
        super.startProgress();
    }
    
    @Override
    public void stopFadeOut() {
        super.stopFadeOut();
    }
    
    @Override
    public void startFadeOut() {
        super.startFadeOut();
    }
    
    @Override
    public boolean hasCutout() {
        // OrangevideoView doesn't have hasCutout method, return false
        return false;
    }
    
    @Override
    public int getCutoutHeight() {
        // OrangevideoView doesn't have getCutoutHeight method, return 0
        return 0;
    }
    
    @Override
    public int getVideoWidth() {
        return mVideoView != null ? mVideoView.getCurrentVideoWidth() : 0;
    }
    
    @Override
    public int getVideoHeight() {
        return mVideoView != null ? mVideoView.getCurrentVideoHeight() : 0;
    }
    
    @Override
    public String getVideoUrl() {
        return mVideoView != null ? mVideoView.getUrl() : "";
    }
    
    @Override
    public void onLockStateChanged(boolean locked) {
        // 立即更新锁定状态（用于 VodControlView 的锁定按钮）
        setLockedInternal(locked);
    }
}

