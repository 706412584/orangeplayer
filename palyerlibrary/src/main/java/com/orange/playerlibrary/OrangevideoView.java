package com.orange.playerlibrary;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Message;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Surface;

import android.view.SurfaceView;
import android.view.View;

import com.orange.playerlibrary.interfaces.OnPlayCompleteListener;
import com.orange.playerlibrary.interfaces.OnProgressListener;
import com.orange.playerlibrary.interfaces.OnStateChangeListener;
import com.orange.playerlibrary.history.PlayHistoryManager;
import com.shuyu.gsyvideoplayer.GSYVideoManager;
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack;
import com.shuyu.gsyvideoplayer.listener.GSYVideoProgressListener;
import com.shuyu.gsyvideoplayer.player.IPlayerManager;
import com.shuyu.gsyvideoplayer.player.IjkPlayerManager;
import com.shuyu.gsyvideoplayer.player.PlayerFactory;
import com.shuyu.gsyvideoplayer.player.SystemPlayerManager;
import com.shuyu.gsyvideoplayer.video.base.GSYBaseVideoPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class OrangevideoView extends GSYBaseVideoPlayer {

    private static final String TAG = "OrangevideoView";

    public static final int STATE_STARTSNIFFING = PlayerConstants.STATE_STARTSNIFFING;
    public static final int STATE_ENDSNIFFING = PlayerConstants.STATE_ENDSNIFFING;
    public static final int STATE_M3U8_AD_REMOVAL = PlayerConstants.STATE_M3U8_AD_REMOVAL;
    public static final int STATE_M3U8_AD_REMOVAL_END = PlayerConstants.STATE_M3U8_AD_REMOVAL_END;

    public static OrangeSharedSqlite sqlite;

    private String mVideoUrl;
    private Map<String, String> mVideoHeaders;
    private static float sSpeed = 1.0f;
    private static float sLongSpeed = 3.0f; // 默认 3.0x，最高 3.0x
    private boolean mKeepVideoPlaying = false;
    private boolean mAutoThumbnailEnabled = true;
    private Object mDefaultThumbnail = null;
    private boolean mIsLiveVideo = false;
    private boolean mAutoRotateOnFullscreen = true;

    // 首帧加载状态
    private boolean mIsLoadingThumbnail = false;

    // 用户主动暂停标记（用于修复后台自动恢复播放bug）
    private boolean mUserPaused = false;

    private SkipManager mSkipManager;
    private VideoScaleManager mVideoScaleManager;
    private PlaybackStateManager mPlaybackStateManager;
    private ComponentStateManager mComponentStateManager;
    private ErrorRecoveryManager mErrorRecoveryManager;
    private CustomFullscreenHelper mFullscreenHelper;
    private M3U8AdManager mM3U8AdManager;
    private com.orange.playerlibrary.torrent.TorrentDelegate mTorrentDelegate;
    private com.orange.playerlibrary.sniffing.SniffingDelegate mSniffingDelegate;

    // M3U8去广告状态管理
    private M3U8AdRemovalState mAdState = new M3U8AdRemovalState();


    // ExoPlayer Surface 切换相关 (Android Q+)
    private SurfaceControlHelper mSurfaceControlHelper = new SurfaceControlHelper();

    private com.orange.playerlibrary.interfaces.ControlWrapper mControlWrapper;
    private OrangeVideoController mOrangeController;

    private com.orange.playerlibrary.component.PrepareView mPrepareView;
    private com.orange.playerlibrary.component.TitleView mTitleView;
    private com.orange.playerlibrary.component.VodControlView mVodControlView;
    private com.orange.playerlibrary.component.LiveControlView mLiveControlView;
    private com.orange.playerlibrary.component.CompleteView mCompleteView;
    private com.orange.playerlibrary.component.ErrorView mErrorView;
    private boolean mUseOrangeComponents = true;

    private List<OnStateChangeListener> mStateChangeListeners;
    private OnProgressListener mProgressListener;
    private OnPlayCompleteListener mPlayCompleteListener;

    private int mCurrentPlayState = PlayerConstants.STATE_IDLE;
    private int mCurrentPlayerState = PlayerConstants.PLAYER_NORMAL;

    private boolean mDebug = false;
    private boolean mEnteringPiPMode = false;

    // 网速显示相关
    private android.widget.TextView mLoadingSpeedText;
    private boolean mIsShowingLoading = false;
    private LoadingStateHelper mLoadingStateHelper;
    // 播放器核心是否已初始化
    private boolean mPlayerFactoryInitialized = false;
    private final Runnable mSpeedUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateLoadingSpeed();
            mLoadingStateHelper.scheduleNext(this);
        }
    };

    private DebugLogCallback mDebugLogCallback;

    public interface DebugLogCallback {
        void onLog(String msg);
    }

    /**
     * 兼容 API 16+ 的 isAttachedToWindow 方法
     * API 19+ 使用 View.isAttachedToWindow()
     * API 16-18 使用 View.getWindowToken() != null
     */
    private static boolean isViewAttachedToWindow(View view) {
        if (view == null) {
            return false;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            return view.isAttachedToWindow();
        } else {
            return view.getWindowToken() != null;
        }
    }

    public OrangevideoView(Context context) {
        super(context);
    }

    public OrangevideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public OrangevideoView(Context context, boolean fullFlag) {
        super(context, fullFlag);
    }

    @Override
    protected void init(Context context) {
        super.init(context);
        initOrangePlayer();
    }

    private void initOrangePlayer() {
        // 初始化状态监听器列表（必须在最开始，因为 VideoEventManager 构造时会用到）
        if (mStateChangeListeners == null) {
            mStateChangeListeners = new ArrayList<>();
        }

        mUseOrangeComponents = true;

        // 初始化播放核心（必须在这里初始化，因为需要 Context）
        initPlayerFactory();
        
        // 初始化 TsPtsChecker（用于检测加密 TS 的 PTS）
        TsPtsChecker.init(getContext());

        mSkipManager = new SkipManager();
        mSkipManager.attachVideoView(this);

        mVideoScaleManager = new VideoScaleManager(this, PlayerSettingsManager.getInstance(getContext()));
        mPlaybackStateManager = new PlaybackStateManager();
        mComponentStateManager = new ComponentStateManager();

        mErrorRecoveryManager = new ErrorRecoveryManager();
        mErrorRecoveryManager.attachVideoView(this);

        mFullscreenHelper = new CustomFullscreenHelper(this);
        // 应用自动旋转设置
        mFullscreenHelper.setAutoRotateEnabled(
                PlayerSettingsManager.getInstance(getContext()).isAutoRotateEnabled());

        // 初始化M3U8去广告管理器
        mM3U8AdManager = M3U8AdManager.getInstance(getContext());

        // 初始化种子播放代理
        mTorrentDelegate = new com.orange.playerlibrary.torrent.TorrentDelegate(getContext());
        mTorrentDelegate.setViewCallback(new com.orange.playerlibrary.torrent.TorrentDelegate.ViewCallback() {
            @Override
            public void onTorrentReady(String proxyUrl, String fileName, long fileSize) {
                setCustomLoadingText(null);
                setUpInternal(proxyUrl, false, fileName);
                startPlayLogic();
            }

            @Override
            public void onTorrentError(String error) {
                setCustomLoadingText(null);
            }

            @Override
            public void onMagnetResolvingProgress(int elapsedSeconds, int totalSeconds) {
                int progress = (int) (elapsedSeconds * 100.0 / totalSeconds);
                String progressText = String.format("解析磁力链接中 %d/%ds (%d%%)",
                        elapsedSeconds, totalSeconds, progress);
                setCustomLoadingText(progressText);
            }

            @Override
            public void onTorrentLoadingProgress(int elapsedSeconds, int totalSeconds) {
                // 可以在这里更新加载进度 UI
            }

            @Override
            public void onTorrentBufferProgress(int bufferedPieces, int totalPieces, long bufferedBytes) {
                // 可以在这里更新缓冲进度 UI
            }

            @Override
            public void onTorrentDownloadProgress(int progress, long downloadSpeed, long uploadSpeed) {
                // 可以在这里更新下载进度 UI
            }
        });

        // 初始化视频嗅探代理
        mSniffingDelegate = new com.orange.playerlibrary.sniffing.SniffingDelegate(getContext());
        mSniffingDelegate.setViewCallback(new com.orange.playerlibrary.sniffing.SniffingDelegate.ViewCallback() {
            @Override
            public void onSniffingStarted(boolean autoPlay) {
                setOrangePlayState(STATE_STARTSNIFFING);
                if (mOrangeController != null) {
                    com.orange.playerlibrary.component.SniffingView sniffingView = mOrangeController.getSniffingView();
                    if (sniffingView != null) {
                        if (!autoPlay) {
                            sniffingView.show();
                        }
                        sniffingView.startSniffing();
                    }
                }
            }

            @Override
            public void onSniffingReceived(VideoSniffing.VideoInfo videoInfo) {
                if (mOrangeController != null) {
                    com.orange.playerlibrary.component.SniffingView sniffingView = mOrangeController.getSniffingView();
                    if (sniffingView != null) {
                        sniffingView.addSniffingResult(videoInfo);
                    }
                }
            }

            @Override
            public void onSniffingReceivedRaw(String contentType, java.util.HashMap<String, String> headers,
                                              String title, String url) {
                if (mStateChangeListeners != null) {
                    for (OnStateChangeListener listener : mStateChangeListeners) {
                        if (listener instanceof OnSniffingListener) {
                            ((OnSniffingListener) listener).onSniffingReceived(contentType, headers, title, url);
                        }
                    }
                }
            }

            @Override
            public void onSniffingFinished(java.util.List<VideoSniffing.VideoInfo> videoList, int videoSize, boolean autoPlay) {
                setOrangePlayState(STATE_ENDSNIFFING);

                if (mOrangeController != null) {
                    com.orange.playerlibrary.component.SniffingView sniffingView = mOrangeController.getSniffingView();
                    if (sniffingView != null) {
                        sniffingView.finishSniffing(videoSize);
                        sniffingView.setSniffingResults(videoList);
                        if (autoPlay && videoSize > 0) {
                            sniffingView.hide();
                        }
                    }
                    mOrangeController.updateSniffingButton();
                }

                // 将嗅探到的视频添加到选集列表
                if (videoList != null && !videoList.isEmpty() && mOrangeController != null) {
                    mOrangeController.removeVideoList();
                    for (int i = 0; i < videoList.size(); i++) {
                        VideoSniffing.VideoInfo info = videoList.get(i);
                        String name = info.title;
                        if (name == null || name.isEmpty()) {
                            name = "视频 " + (i + 1);
                        }
                        mOrangeController.addVideo(name, info.url, info.headers);
                    }
                }

                // 如果启用自动播放且有视频，自动播放第一个视频
                if (autoPlay && videoList != null && !videoList.isEmpty()) {
                    VideoSniffing.VideoInfo firstVideo = videoList.get(0);
                    final String videoUrl = firstVideo.url;
                    final String videoTitle = firstVideo.title != null && !firstVideo.title.isEmpty()
                            ? firstVideo.title : "视频 1";
                    post(new Runnable() {
                        @Override
                        public void run() {
                            setUp(videoUrl, false, videoTitle);
                            startPlayLogic();
                        }
                    });
                }

                if (mStateChangeListeners != null) {
                    for (OnStateChangeListener listener : mStateChangeListeners) {
                        if (listener instanceof OnSniffingListener) {
                            ((OnSniffingListener) listener).onSniffingFinish(videoList, videoSize);
                        }
                    }
                }
            }
        });

        // 初始化网速计算 Helper
        mLoadingStateHelper = new LoadingStateHelper();

        setShowFullAnimation(false);
        setRotateViewAuto(false);
        setNeedLockFull(false);
        setLockLand(false);
        setRotateWithSystem(false);
        setNeedShowWifiTip(false);
        setNeedOrientationUtils(false);
        setIsTouchWiget(true);
        setIsTouchWigetFull(true);

        if (mUseOrangeComponents) {
            initOrangeComponents();
        }

        if (mComponentStateManager != null) {
            mComponentStateManager.reregisterProgressListener(this);
        }

        setVideoAllCallBack(new GSYSampleCallBack() {
            @Override
            public void onPrepared(String url, Object... objects) {
                super.onPrepared(url, objects);
                setOrangePlayState(PlayerConstants.STATE_PREPARED);
                long duration = getDuration();
                android.util.Log.d(TAG,
                        "onPrepared: url=" + url + ", duration=" + duration + "ms (" + (duration / 1000) + "s)");
                if (duration <= 0) {
                    mIsLiveVideo = true;
                }
                if (mVideoScaleManager != null) {
                    mVideoScaleManager.applyVideoScale();
                }

                if (mFullscreenHelper != null && mFullscreenHelper.getPendingSeekPosition() > 0) {
                    final long pendingPosition = mFullscreenHelper.getPendingSeekPosition();
                    final boolean pendingResume = mFullscreenHelper.isPendingResume();
                    mFullscreenHelper.clearPendingSeekPosition();

                    postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            seekTo(pendingPosition);
                            if (pendingResume && !isPlaying()) {
                                resume();
                            }
                        }
                    }, 100);
                } else {
                    boolean hasRestoredProgress = false;
                    if (mKeepVideoPlaying) {
                        hasRestoredProgress = restorePlaybackProgress();
                        android.util.Log.d(TAG, "Tried to restore playback progress: " + hasRestoredProgress);
                    }

                    if (mSkipManager != null) {
                        // 直接检查并执行跳过片头，不依赖 SkipManager.mVideoView
                        long skipTime = mSkipManager.getSkipIntroTime();
                        boolean enabled = mSkipManager.isSkipIntroEnabled();
                        android.util.Log.d(TAG, "Checking skip intro: enabled=" + enabled + ", skipTime=" + skipTime
                                + ", skipped=" + mSkipManager.isIntroSkipped() + ", hasRestoredProgress="
                                + hasRestoredProgress);

                        // 如果已经恢复了记忆播放，就不再执行跳过片头（因为记忆的位置肯定更靠后）
                        if (hasRestoredProgress) {
                            mSkipManager.setIntroSkipped(true);
                            android.util.Log.d(TAG, "Skipping intro skipped because playback progress was restored");
                        } else if (enabled && skipTime > 0 && !mSkipManager.isIntroSkipped()) {
                            android.util.Log.d(TAG, "Performing skip intro: seeking to " + skipTime + "ms");
                            mSkipManager.setIntroSkipped(true);
                            seekTo(skipTime);
                        }
                    }
                }
                setOrangePlayState(PlayerConstants.STATE_PLAYING);

                // 启动播放历史自动保存
                startPlayHistoryAutoSave();

                // 尝试自动加载已记忆的字幕
                if (mOrangeController != null) {
                    VideoEventManager eventManager = mOrangeController.getVideoEventManager();
                    if (eventManager != null) {
                        eventManager.tryLoadRememberedSubtitle();
                    }
                }
            }

            @Override
            public void onAutoComplete(String url, Object... objects) {
                super.onAutoComplete(url, objects);
                setOrangePlayState(PlayerConstants.STATE_PLAYBACK_COMPLETED);

                // 停止播放历史自动保存
                stopPlayHistoryAutoSave();

                // 播放完成，删除历史进度记录
                PlayHistoryManager.getInstance(getContext()).deleteHistory(url);

                if (mKeepVideoPlaying) {
                    clearSavedProgress();
                }
                if (mSkipManager != null) {
                    mSkipManager.reset();
                }
                if (mPlayCompleteListener != null) {
                    mPlayCompleteListener.onPlayComplete();
                }

                // 处理播放模式（顺序播放、单集循环等）
                if (mOrangeController != null) {
                    VideoEventManager eventManager = mOrangeController.getVideoEventManager();
                    if (eventManager != null) {
                        eventManager.handlePlaybackCompleted();
                    }
                }
            }

            @Override
            public void onPlayError(String url, Object... objects) {
                super.onPlayError(url, objects);
                android.util.Log.e(TAG, "onPlayError: url=" + url + ", objects=" + java.util.Arrays.toString(objects));

                boolean adRemovalEnabled = mM3U8AdManager != null && mM3U8AdManager.isEnabled();
                boolean hasOriginalM3U8 = mAdState.getOriginalUrl() != null && M3U8AdRemover.isHttpM3U8(mAdState.getOriginalUrl());
                boolean currentIsProcessedM3U8 = url != null
                        && (url.contains("127.0.0.1") || url.contains("cleaned.m3u8") || url.contains("m3u8_cache"));

                // 去广告开启下，只要当前播放流疑似去广告处理结果且未重试过，就自动回退原始URL
                if (adRemovalEnabled && hasOriginalM3U8 && !mAdState.hasRetriedOriginalUrl()
                        && (mAdState.isPlayingAdRemoved() || currentIsProcessedM3U8)) {
                    android.util.Log.w(TAG,
                            "M3U8 playback failed after ad-removal pipeline, retrying with original URL: "
                                    + mAdState.getOriginalUrl());
                    mAdState.setHasRetriedOriginalUrl(true);
                    mAdState.setPlayingAdRemoved(false);

                    // 重试原始URL（跳过去广告流程）
                    post(() -> {
                        release();
                        setOrangePlayState(PlayerConstants.STATE_PREPARING);
                        setStateAndUi(CURRENT_STATE_PREPAREING);
                        if (mPrepareView != null) {
                            mPrepareView.setVisibility(View.VISIBLE);
                        }

                        saveVideoUrl(mAdState.getOriginalUrl());
                        if (mOrangeController != null && mOrangeController.getVideoEventManager() != null) {
                            mOrangeController.getVideoEventManager().resetTemporarySettings(mAdState.getOriginalUrl());
                        }
                        if (mSkipManager != null) {
                            mSkipManager.attachVideoView(OrangevideoView.this);
                        }
                        autoSelectPlayerEngine(mAdState.getOriginalUrl());
                        getVideoFirstFrameAsync(mAdState.getOriginalUrl());

                        if (mAdState.getOriginalHeaders() != null) {
                            OrangevideoView.super.setUp(mAdState.getOriginalUrl(), mAdState.isOriginalCacheWithPlay(), null,
                                    mAdState.getOriginalHeaders(), mAdState.getOriginalTitle());
                        } else {
                            OrangevideoView.super.setUp(mAdState.getOriginalUrl(), mAdState.isOriginalCacheWithPlay(),
                                    mAdState.getOriginalTitle());
                        }
                        startPlayLogic();
                    });
                    return;
                }

                setOrangePlayState(PlayerConstants.STATE_ERROR);

                // 去广告链路播放失败时，清除原始URL对应缓存，避免下次命中坏缓存
                if (adRemovalEnabled && hasOriginalM3U8 && mM3U8AdManager != null) {
                    android.util.Log.d(TAG, "Clearing cache for failed m3u8 originalUrl=" + mAdState.getOriginalUrl());
                    mM3U8AdManager.clearCacheForUrl(mAdState.getOriginalUrl());
                }

            }

            @Override
            public void onEnterFullscreen(String url, Object... objects) {
                super.onEnterFullscreen(url, objects);
                setOrangePlayerState(PlayerConstants.PLAYER_FULL_SCREEN);
            }

            @Override
            public void onQuitFullscreen(String url, Object... objects) {
                super.onQuitFullscreen(url, objects);
                setOrangePlayerState(PlayerConstants.PLAYER_NORMAL);
                if (mAutoRotateOnFullscreen) {
                    Activity activity = getActivity();
                    if (activity != null) {
                        activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    }
                }
            }
        });

        // 启动错误检测定时器
        startErrorDetectionTimer();
    }

    /**
     * 启动错误检测定时器
     * 检测播放器是否卡在 PREPARING 状态超过 30 秒
     */
    private void startErrorDetectionTimer() {
        if (mInnerHandler != null) {
            mInnerHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // 如果卡在 PREPARING 状态超过 30 秒，认为出错
                    if (mCurrentState == CURRENT_STATE_PREPAREING) {
                        long preparingTime = System.currentTimeMillis() - mPreparingStartTime;
                        if (preparingTime > 30000) {
                            android.util.Log.e(TAG, "错误检测: 播放器卡在 PREPARING 状态超过 30 秒，触发错误状态");
                            setOrangePlayState(PlayerConstants.STATE_ERROR);
                            setStateAndUi(CURRENT_STATE_ERROR);
                        } else {
                            // 继续检测
                            startErrorDetectionTimer();
                        }
                    }
                }
            }, 5000); // 每 5 秒检测一次
        }
    }

    // 记录进入 PREPARING 状态的时间
    private long mPreparingStartTime = 0;

    /**
     * 初始化播放器核心
     * 根据用户设置选择合适的播放引擎
     * 如果选择的内核不可用，自动回退到系统播放器
     */
    private void initPlayerFactory() {
        if (mPlayerFactoryInitialized) {
            return; // 已经初始化过了
        }

        PlayerSettingsManager settingsManager = PlayerSettingsManager.getInstance(getContext());
        String engine = settingsManager.getPlayerEngine();
        boolean fallbackToSystem = false;

        // 根据设置切换播放器核心
        switch (engine) {
            case PlayerConstants.ENGINE_IJK:
                // IJK 播放器需要 Android 4.1+ (API 16)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    // 检查 IJK so 库是否可用
                    if (isIjkPlayerAvailable()) {
                        try {
                            PlayerFactory.setPlayManager(com.orange.playerlibrary.player.OrangeIjkPlayerManager.class);
                            android.util.Log.d(TAG, "initPlayerFactory: 使用 Orange IJK 播放器（支持本地文件）");
                            // IJK 播放器使用 TextureView 模式（更稳定）
                            com.shuyu.gsyvideoplayer.utils.GSYVideoType.setRenderType(
                                    com.shuyu.gsyvideoplayer.utils.GSYVideoType.TEXTURE);
                        } catch (Exception e) {
                            android.util.Log.w(TAG, "initPlayerFactory: IJK 播放器初始化失败，回退到系统播放器", e);
                            fallbackToSystem = true;
                        }
                    } else {
                        android.util.Log.w(TAG, "initPlayerFactory: IJK so 库未找到，回退到系统播放器");
                        fallbackToSystem = true;
                    }
                } else {
                    android.util.Log.w(TAG, "initPlayerFactory: Android 版本过低（需要 4.1+），不支持 IJK 播放器，回退到系统播放器");
                    fallbackToSystem = true;
                }
                break;

            case PlayerConstants.ENGINE_EXO:
                // ExoPlayer 需要 Android 5.0+ (API 21)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // 使用自定义的 OrangeExoPlayerManager，支持 SurfaceControl 无缝切换
                    // 解决横竖屏切换时 MediaCodec IllegalStateException 问题
                    try {
                        PlayerFactory.setPlayManager(com.orange.playerlibrary.exo.OrangeExoPlayerManager.class);
                        android.util.Log.d(TAG, "initPlayerFactory: 使用 ExoPlayer");

                        // 默认强制使用 TextureView 渲染模式（已修复横竖屏切换崩溃问题）
                        // 用户可以通过 setRenderMode() 手动切换到 SurfaceView
                        com.shuyu.gsyvideoplayer.utils.GSYVideoType.setRenderType(
                                com.shuyu.gsyvideoplayer.utils.GSYVideoType.TEXTURE);
                        com.orange.playerlibrary.exo.OrangeExoPlayerManager.setForceTextureViewMode(true);
                        android.util.Log.d(TAG, "initPlayerFactory: 默认使用 TextureView 渲染模式");
                    } catch (Exception e) {
                        android.util.Log.w(TAG, "initPlayerFactory: ExoPlayer 初始化失败", e);
                        // 回退到 GSY 原生 Exo2PlayerManager
                        try {
                            @SuppressWarnings("unchecked")
                            Class<? extends IPlayerManager> exoClass = (Class<? extends IPlayerManager>) Class
                                    .forName("tv.danmaku.ijk.media.exo2.Exo2PlayerManager");
                            PlayerFactory.setPlayManager(exoClass);
                            android.util.Log.d(TAG, "initPlayerFactory: 回退到 GSY Exo2PlayerManager");
                        } catch (ClassNotFoundException ex) {
                            android.util.Log.w(TAG, "initPlayerFactory: Exo2PlayerManager 未找到，回退到系统播放器", ex);
                            fallbackToSystem = true;
                        }
                    }
                } else {
                    android.util.Log.w(TAG, "initPlayerFactory: Android 版本过低（需要 5.0+），不支持 ExoPlayer，回退到系统播放器");
                    fallbackToSystem = true;
                }
                break;

            case PlayerConstants.ENGINE_ALI:
                // 阿里云播放器需要 Android 5.0+ (API 21)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // GSY AliPlayer 类名: com.shuyu.aliplay.AliPlayerManager
                    try {
                        @SuppressWarnings("unchecked")
                        Class<? extends IPlayerManager> aliClass = (Class<? extends IPlayerManager>) Class
                                .forName("com.shuyu.aliplay.AliPlayerManager");
                        PlayerFactory.setPlayManager(aliClass);
                        android.util.Log.d(TAG, "initPlayerFactory: 使用阿里云播放器");
                    } catch (ClassNotFoundException e) {
                        android.util.Log.w(TAG, "initPlayerFactory: 阿里云播放器未找到，回退到系统播放器", e);
                        fallbackToSystem = true;
                    }
                } else {
                    android.util.Log.w(TAG, "initPlayerFactory: Android 版本过低（需要 5.0+），不支持阿里云播放器，回退到系统播放器");
                    fallbackToSystem = true;
                }
                break;

            case PlayerConstants.ENGINE_DEFAULT:
            default:
                fallbackToSystem = true;
                break;
        }

        // 回退到系统播放器
        if (fallbackToSystem) {
            // 使用自定义的 OrangeSystemPlayerManager，统一网速计算和 SurfaceControl 支持
            PlayerFactory.setPlayManager(com.orange.playerlibrary.player.OrangeSystemPlayerManager.class);
            android.util.Log.d(TAG, "initPlayerFactory: 使用系统播放器");

            // 默认强制使用 TextureView 渲染模式（已修复横竖屏切换崩溃问题）
            // 用户可以通过 setRenderMode() 手动切换到 SurfaceView
            com.shuyu.gsyvideoplayer.utils.GSYVideoType.setRenderType(
                    com.shuyu.gsyvideoplayer.utils.GSYVideoType.TEXTURE);
            com.orange.playerlibrary.player.OrangeSystemPlayerManager.setForceTextureViewMode(true);
            android.util.Log.d(TAG, "initPlayerFactory: 默认使用 TextureView 渲染模式");

            // 如果用户设置的不是系统播放器，但回退到了系统播放器，更新设置
            if (!PlayerConstants.ENGINE_DEFAULT.equals(engine)) {
                settingsManager.setPlayerEngine(PlayerConstants.ENGINE_DEFAULT);
                android.util.Log.i(TAG, "initPlayerFactory: 已自动切换到系统播放器，并更新设置");
            }
        }

        // 应用解码方式设置
        applyDecodeMode(settingsManager);

        mPlayerFactoryInitialized = true;
    }

    /**
     * 检查 IJK 播放器是否可用
     * 同时检查 Java 类和 native 库
     */
    private boolean isIjkPlayerAvailable() {
        // 1. 先检查 Java 类是否存在
        try {
            Class.forName("tv.danmaku.ijk.media.player.IjkMediaPlayer");
        } catch (ClassNotFoundException e) {
            android.util.Log.d(TAG, "isIjkPlayerAvailable: IJK Java 类未找到");
            return false;
        }

        // 2. 再检查 SO 库是否可用
        try {
            // 尝试加载 IJK 的 native 库
            System.loadLibrary("ijkffmpeg");
            System.loadLibrary("ijksdl");
            System.loadLibrary("ijkplayer");
            return true;
        } catch (UnsatisfiedLinkError e) {
            // so 库未找到
            android.util.Log.d(TAG, "isIjkPlayerAvailable: IJK so 库未找到 - " + e.getMessage());
            return false;
        } catch (Exception e) {
            android.util.Log.w(TAG, "isIjkPlayerAvailable: 检查 IJK 可用性时出错", e);
            return false;
        }
    }

    /**
     * 应用解码方式设置
     */
    private void applyDecodeMode(PlayerSettingsManager settingsManager) {
        boolean useHardware = settingsManager.isHardwareDecode();

        if (useHardware) {
            // 硬件解码
            com.shuyu.gsyvideoplayer.utils.GSYVideoType.enableMediaCodec();
        } else {
            // 软件解码
            com.shuyu.gsyvideoplayer.utils.GSYVideoType.disableMediaCodec();
        }

        // 关键修复：无论硬解还是软解，都启用 MediaCodecTexture
        // 这样 TextureView 在横竖屏切换时会保留 SurfaceTexture，不会重新创建
        // 避免 ExoPlayer 和系统播放器在 TextureView 模式下横竖屏切换时崩溃
        //
        // 原理：enableMediaCodecTexture() 会让 GSYTextureView.onSurfaceTextureDestroyed() 返回
        // false
        // 这样系统就不会销毁 SurfaceTexture，横竖屏切换时可以复用
        com.shuyu.gsyvideoplayer.utils.GSYVideoType.enableMediaCodecTexture();
        android.util.Log.d(TAG, "applyDecodeMode: 已启用 MediaCodecTexture（保留 SurfaceTexture，避免横竖屏切换重建）");
    }

    /**
     * 设置渲染模式
     * 
     * @param useTextureView true: 使用 TextureView（推荐，已修复横竖屏切换崩溃）
     *                       false: 使用 SurfaceView（Android Q+ 支持 SurfaceControl
     *                       无缝切换）
     */
    public void setRenderMode(boolean useTextureView) {
        if (useTextureView) {
            // 使用 TextureView 渲染
            com.shuyu.gsyvideoplayer.utils.GSYVideoType.setRenderType(
                    com.shuyu.gsyvideoplayer.utils.GSYVideoType.TEXTURE);

            // 设置播放器管理器的强制模式
            String engine = PlayerSettingsManager.getInstance(getContext()).getPlayerEngine();
            if (PlayerConstants.ENGINE_EXO.equals(engine)) {
                com.orange.playerlibrary.exo.OrangeExoPlayerManager.setForceTextureViewMode(true);
            } else if (PlayerConstants.ENGINE_DEFAULT.equals(engine)) {
                com.orange.playerlibrary.player.OrangeSystemPlayerManager.setForceTextureViewMode(true);
            }

            android.util.Log.d(TAG, "setRenderMode: 已切换到 TextureView 渲染模式");
        } else {
            // 使用 SurfaceView 渲染
            com.shuyu.gsyvideoplayer.utils.GSYVideoType.setRenderType(
                    com.shuyu.gsyvideoplayer.utils.GSYVideoType.SURFACE);

            // 设置播放器管理器的强制模式
            String engine = PlayerSettingsManager.getInstance(getContext()).getPlayerEngine();
            if (PlayerConstants.ENGINE_EXO.equals(engine)) {
                com.orange.playerlibrary.exo.OrangeExoPlayerManager.setForceTextureViewMode(false);
            } else if (PlayerConstants.ENGINE_DEFAULT.equals(engine)) {
                com.orange.playerlibrary.player.OrangeSystemPlayerManager.setForceTextureViewMode(false);
            }

            android.util.Log.d(TAG, "setRenderMode: 已切换到 SurfaceView 渲染模式");
        }

        // 智能检测：如果使用 TextureView，确保启用 MediaCodecTexture
        if (useTextureView) {
            com.shuyu.gsyvideoplayer.utils.GSYVideoType.enableMediaCodecTexture();
            android.util.Log.d(TAG, "setRenderMode: 已自动启用 MediaCodecTexture（TextureView 模式必需）");
        }
    }

    /**
     * 获取当前渲染模式
     * 
     * @return true: TextureView, false: SurfaceView
     */
    public boolean isTextureViewMode() {
        return com.shuyu.gsyvideoplayer.utils.GSYVideoType
                .getRenderType() == com.shuyu.gsyvideoplayer.utils.GSYVideoType.TEXTURE;
    }

    public void setDebugLogCallback(DebugLogCallback callback) {
        mDebugLogCallback = callback;
    }

    public void enableOrangeComponents() {
        if (mUseOrangeComponents)
            return;
        mUseOrangeComponents = true;
        initOrangeComponents();
    }

    private void initOrangeComponents() {
        Context context = getContext();
        android.widget.RelativeLayout.LayoutParams matchParentParams = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT);

        // 调试日志：记录组件初始化
        android.util.Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        android.util.Log.d(TAG, "initOrangeComponents: 开始初始化组件");
        android.util.Log.d(TAG, "  VideoView 实例: @" + Integer.toHexString(this.hashCode()));

        mControlWrapper = createControlWrapper();

        // 自动创建控制器（如果用户没有手动设置）
        if (mOrangeController == null) {
            mOrangeController = new OrangeVideoController(context);
            mOrangeController.setVideoView(this);
            android.util.Log.d(TAG, "  创建新控制器: @" + Integer.toHexString(mOrangeController.hashCode()));
        } else {
            android.util.Log.d(TAG, "  复用已有控制器: @" + Integer.toHexString(mOrangeController.hashCode()));
        }

        mPrepareView = new com.orange.playerlibrary.component.PrepareView(context);
        mPrepareView.attach(mControlWrapper);
        mPrepareView.setClickStart();
        addView(mPrepareView, matchParentParams);
        android.util.Log.d(TAG, "  PrepareView: @" + Integer.toHexString(mPrepareView.hashCode()));

        mCompleteView = new com.orange.playerlibrary.component.CompleteView(context);
        mCompleteView.attach(mControlWrapper);
        addView(mCompleteView, matchParentParams);
        android.util.Log.d(TAG, "  CompleteView: @" + Integer.toHexString(mCompleteView.hashCode()));

        mErrorView = new com.orange.playerlibrary.component.ErrorView(context);
        mErrorView.attach(mControlWrapper);
        addView(mErrorView, matchParentParams);
        android.util.Log.d(TAG, "  ErrorView: @" + Integer.toHexString(mErrorView.hashCode()));

        mTitleView = new com.orange.playerlibrary.component.TitleView(context);
        mTitleView.attach(mControlWrapper);
        addView(mTitleView, matchParentParams);
        android.util.Log.d(TAG, "  TitleView: @" + Integer.toHexString(mTitleView.hashCode()));

        mVodControlView = new com.orange.playerlibrary.component.VodControlView(context);
        if (mOrangeController != null) {
            mVodControlView.setOrangeVideoController(mOrangeController);
        }
        mVodControlView.attach(mControlWrapper);
        addView(mVodControlView, matchParentParams);
        android.util.Log.d(TAG, "  VodControlView: @" + Integer.toHexString(mVodControlView.hashCode()));

        android.util.Log.d(TAG, "initOrangeComponents: 组件初始化完成");
        android.util.Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // 使用 post 延迟状态通知，确保所有组件都已附加到窗口
        // 这样可以避免在 setUp() 后立即调用 startPlayLogic() 时出现的问题
        post(new Runnable() {
            @Override
            public void run() {
                setOrangePlayState(PlayerConstants.STATE_IDLE);
            }
        });

        ensureEventBinding();
    }

    public void debugLog(String msg) {
        if (mDebugLogCallback != null) {
            mDebugLogCallback.onLog(msg);
        }
    }

    private void ensureEventBinding() {
        if (mOrangeController == null) {
            return;
        }

        VideoEventManager eventManager = mOrangeController.getVideoEventManager();
        if (eventManager == null) {
            return;
        }

        if (mVodControlView != null) {
            eventManager.bindControllerComponents(mVodControlView);
        }

        if (mTitleView != null) {
            eventManager.bindTitleView(mTitleView);
        }
    }

    private com.orange.playerlibrary.interfaces.ControlWrapper createControlWrapper() {
        final OrangevideoView videoView = this;
        return new com.orange.playerlibrary.interfaces.ControlWrapper() {
            @Override
            public void start() {
                // 调用 start() 方法，它会设置 STATE_PREPARING 状态
                videoView.start();
            }

            @Override
            public void pause() {
                videoView.pause();
            }

            @Override
            public void seekTo(long position) {
                videoView.seekTo(position);
            }

            @Override
            public long getDuration() {
                return videoView.getDuration();
            }

            @Override
            public long getCurrentPosition() {
                return videoView.getCurrentPositionWhenPlaying();
            }

            @Override
            public boolean isPlaying() {
                return videoView.isPlaying();
            }

            @Override
            public void togglePlay() {
                if (isPlaying()) {
                    pause();
                } else {
                    videoView.resume();
                }
            }

            @Override
            public void toggleFullScreen() {
                if (isFullScreen()) {
                    // 退出全屏
                    videoView.stopFullScreen();
                } else {
                    // 进入全屏 - 调用 videoView.startFullScreen() 以支持智能全屏
                    videoView.startFullScreen();
                }
            }

            @Override
            public void toggleLockState() {
                if (mOrangeController != null) {
                    mOrangeController.toggleLockState();
                }
            }

            @Override
            public void setLocked(boolean locked) {
                if (mOrangeController != null) {
                    mOrangeController.setLocked(locked);
                }
                // 立即更新 UI
                videoView.onLockStateChanged(locked);
            }

            @Override
            public void onLockStateChanged(boolean locked) {
                videoView.onLockStateChanged(locked);
            }

            @Override
            public boolean isFullScreen() {
                return mFullscreenHelper != null && mFullscreenHelper.isFullscreen();
            }

            @Override
            public boolean isLocked() {
                return mOrangeController != null && mOrangeController.isLocked();
            }

            @Override
            public void setSpeed(float speed) {
                videoView.setSpeed(speed);
            }

            @Override
            public float getSpeed() {
                return videoView.getSpeed();
            }

            @Override
            public int getBufferedPercentage() {
                return videoView.getBufferedPercentage();
            }

            @Override
            public void setMute(boolean isMute) {
                videoView.setMute(isMute);
            }

            @Override
            public boolean isMute() {
                return videoView.isMute();
            }

            @Override
            public void setVolume(float volume) {
                videoView.setPlayerVolume(volume);
            }

            @Override
            public void replay(boolean resetPosition) {
                if (resetPosition) {
                    videoView.seekTo(0);
                }
                videoView.startPlayLogic();
            }

            @Override
            public void hide() {
                if (mOrangeController != null) {
                    mOrangeController.hide();
                }
            }

            @Override
            public void show() {
                if (mOrangeController != null) {
                    mOrangeController.show();
                }
            }

            @Override
            public boolean isShowing() {
                return mOrangeController != null && mOrangeController.isShowing();
            }

            @Override
            public void stopProgress() {
                // 停止进度更新 - 由控制器处理
                if (mOrangeController != null) {
                    mOrangeController.stopProgress();
                }
            }

            @Override
            public void startProgress() {
                // 开始进度更新 - 由控制器处理
                if (mOrangeController != null) {
                    mOrangeController.startProgress();
                }
            }

            @Override
            public void stopFadeOut() {
                // 停止自动隐藏 - 由控制器处理
                if (mOrangeController != null) {
                    mOrangeController.stopFadeOut();
                }
            }

            @Override
            public void startFadeOut() {
                // 开始自动隐藏倒计时 - 由控制器处理
                if (mOrangeController != null) {
                    mOrangeController.startFadeOut();
                }
            }

            @Override
            public boolean hasCutout() {
                return false;
            }

            @Override
            public int getCutoutHeight() {
                return 0;
            }

            @Override
            public int getVideoWidth() {
                return videoView.getCurrentVideoWidth();
            }

            @Override
            public int getVideoHeight() {
                return videoView.getCurrentVideoHeight();
            }

            @Override
            public String getVideoUrl() {
                return videoView.getVideoUrl();
            }

            @Override
            public String getVideoTitle() {
                // GSY基类使用 mTitle 存储标题
                return mTitle;
            }
        };
    }

    public boolean isUseOrangeComponents() {
        return mUseOrangeComponents;
    }

    public com.orange.playerlibrary.component.PrepareView getPrepareView() {
        return mPrepareView;
    }

    public com.orange.playerlibrary.component.TitleView getTitleView() {
        return mTitleView;
    }

    public com.orange.playerlibrary.component.VodControlView getVodControlView() {
        return mVodControlView;
    }

    public com.orange.playerlibrary.component.LiveControlView getLiveControlView() {
        return mLiveControlView;
    }

    public com.orange.playerlibrary.component.CompleteView getCompleteView() {
        return mCompleteView;
    }

    public com.orange.playerlibrary.component.ErrorView getErrorView() {
        return mErrorView;
    }

    public com.orange.playerlibrary.interfaces.ControlWrapper getControlWrapper() {
        return mControlWrapper;
    }

    // ==================== 重写 setUp 方法以支持预览功能 ====================

    /**
     * 保存视频 URL 并设置预览功能
     * 所有 setUp 方法的公共逻辑
     */
    private void saveVideoUrl(String url) {
        this.mVideoUrl = url;
        // 设置视频URL给VodControlView用于预览功能
        com.orange.playerlibrary.component.VodControlView.setVideoUrl(url);
    }

    /**
     * 更新标题到 TitleView
     */
    private void updateTitleView(String title) {
        if (mTitleView != null && title != null && !title.isEmpty()) {
            mTitleView.setTitle(title);
        }
    }

    // 种子加载状态由 mTorrentDelegate.isPendingLoad() 管理

    @Override
    public boolean setUp(String url, boolean cacheWithPlay, String title) {
        android.util.Log.d(TAG, "setUp() called with url=" + url);
        mVideoHeaders = null;
        clearM3U8AdRemovalState();

        // 检查本地是否已下载，如果已下载则使用本地路径

        String finalUrl = url;
        try {
            com.orange.playerlibrary.download.SimpleDownloadManager downloadManager = com.orange.playerlibrary.download.SimpleDownloadManager
                    .getInstance(getContext());
            String localPath = downloadManager.getLocalVideoPath(url);
            if (localPath != null && !localPath.isEmpty()) {
                java.io.File localFile = new java.io.File(localPath);
                if (localFile.exists()) {
                    android.util.Log.d(TAG, "setUp: Found local downloaded video, playing local file: " + localPath);
                    // 修复 IjkMediaPlayer Error (-10000,0) 的问题
                    // 必须加上 file:// 协议，否则 IjkPlayer 会把它当成相对路径或者非法协议
                    if (!localPath.startsWith("file://") && !localPath.startsWith("http")) {
                        finalUrl = "file://" + localPath;
                    } else {
                        finalUrl = localPath;
                    }

                    // 播放本地文件不需要缓存
                    cacheWithPlay = false;
                }
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error checking local downloaded video", e);
        }

        // 清除旧URL，防止播放旧视频
        mVideoUrl = null;
        mOriginUrl = null;
        // 种子播放统一入口（magnet/.torrent）
        if (mTorrentDelegate.handleIfTorrentUrl(finalUrl)) {
            // 设置加载中状态
            setOrangePlayState(PlayerConstants.STATE_PREPARING);
            setStateAndUi(CURRENT_STATE_PREPAREING);
            if (mPrepareView != null) {
                mPrepareView.setVisibility(View.VISIBLE);
            }
            hideAllWidget();
            return true;
        }

    
        saveVideoUrl(finalUrl);
        // 重置临时设置（跳过片头片尾、倍速、画面比例）
        if (mOrangeController != null && mOrangeController.getVideoEventManager() != null) {
            mOrangeController.getVideoEventManager().resetTemporarySettings(finalUrl);
        }
        // 自动选择最合适的播放器内核
        autoSelectPlayerEngine(finalUrl);
        // 异步获取视频首帧作为封面
        getVideoFirstFrameAsync(finalUrl);
        boolean result = super.setUp(finalUrl, cacheWithPlay, title);
        // 重新绑定 SkipManager（必须在 super.setUp() 之后，因为 setUp 内部会调用 release() 解绑）
        if (mSkipManager != null) {
            mSkipManager.attachVideoView(this);
        }
        // 更新标题到 TitleView
        updateTitleView(title);
        return result;
    }

    // ===== 种子播放功能（可选） =====

    public void playTorrent(java.io.File torrentFile, java.io.File saveDir,
            com.orange.playerlibrary.torrent.TorrentPlayerManager.TorrentCallback callback) {
        mTorrentDelegate.loadTorrent(torrentFile, saveDir, callback);
    }

    public void playMagnet(String magnetUri, java.io.File saveDir,
            com.orange.playerlibrary.torrent.TorrentPlayerManager.TorrentCallback callback) {
        mTorrentDelegate.loadMagnet(magnetUri, saveDir, callback);
    }

    public void stopTorrent() {
        mTorrentDelegate.stop();
    }

    /**
     * 清理当前 M3U8 去广告链路状态
     */
    private void clearM3U8AdRemovalState() {
        mAdState.clear();
    }

    private boolean shouldProcessM3U8WithAdRemoval(String url) {
        if (mM3U8AdManager == null || !mM3U8AdManager.isEnabled() || mAdState.isBypassOnce()) {
            return false;
        }
        if (url == null || !M3U8AdRemover.isHttpM3U8(url)) {
            return false;
        }
        String lower = url.toLowerCase();
        if (lower.contains("127.0.0.1") || lower.contains("cleaned/") || lower.contains("m3u8_cache")) {
            return false;
        }
        return true;
    }

    private void bindResolvedVideoSource(String url, boolean cacheWithPlay, String title,
            Map<String, String> headers) {
        Map<String, String> headerCopy = headers != null ? new HashMap<>(headers) : null;
        mVideoHeaders = headerCopy;
        saveVideoUrl(url);
        if (mOrangeController != null && mOrangeController.getVideoEventManager() != null) {
            mOrangeController.getVideoEventManager().resetTemporarySettings(url);
        }
        // 设置跳过内核恢复标志，因为这是 M3U8 去广告后的内部调用
        mAdState.setSkipEngineRestore(true);
        autoSelectPlayerEngine(url);
        getVideoFirstFrameAsync(url);
        OrangevideoView.super.setUp(url, cacheWithPlay, mCachePath, headerCopy, title);
        if (mSkipManager != null) {
            mSkipManager.attachVideoView(this);
        }
        updateTitleView(title);
    }

    /**
     * 在 `startPlayLogic()` 阶段处理 M3U8 去广告
     */
    private void processM3U8WithAdRemoval(String url, boolean cacheWithPlay, String title,
            Map<String, String> headers) {
        if (mM3U8AdManager == null) {
            return;
        }

        android.util.Log.d(TAG, "Processing M3U8 for ad removal in startPlayLogic: " + url);

        final Map<String, String> requestHeaders = headers != null ? new HashMap<>(headers) : null;
        final String requestTitle = title != null ? title : "";
        final int requestToken = mAdState.nextToken();

        mAdState.setPendingAdRemoval(true);
        mAdState.setOriginalUrl(url);
        mAdState.setOriginalHeaders(requestHeaders);
        mAdState.setOriginalTitle(requestTitle);
        mAdState.setOriginalCacheWithPlay(cacheWithPlay);
        mAdState.setPlayingAdRemoved(false);
        mAdState.setHasRetriedOriginalUrl(false);

        mVideoUrl = null;
        mOriginUrl = null;

        // 设置 M3U8 去广告状态，触发加载动画显示（类似嗅探模式）
        // 注意：不要调用 setStateAndUi() 和 release()，否则会覆盖我们的状态
        setOrangePlayState(STATE_M3U8_AD_REMOVAL);
        if (mPrepareView != null) {
            mPrepareView.setVisibility(View.GONE);
        }
        setStateAndUi(CURRENT_STATE_PREPAREING);

        mM3U8AdManager.processVideoUrl(url, new M3U8AdManager.Callback() {
            @Override
            public void onResult(String playUrl, boolean isLocalFile, int adSegmentsRemoved, boolean hasPtsJump, String message) {
                android.util.Log.d(TAG, "M3U8 ad removal result: isLocalFile=" + isLocalFile
                        + ", adSegmentsRemoved=" + adSegmentsRemoved + ", hasPtsJump=" + hasPtsJump + ", message=" + message);

                post(() -> {
                    if (requestToken != mAdState.getRequestToken() || !TextUtils.equals(mAdState.getOriginalUrl(), url)) {
                        android.util.Log.d(TAG, "Ignore stale M3U8 ad removal result: " + url);
                        return;
                    }

                    mAdState.setPendingAdRemoval(false);
                    mAdState.setPlayingAdRemoved(adSegmentsRemoved > 0 && isLocalFile);
                    
                    // 如果检测到 PTS 跳变，且当前使用 IJK 内核，自动切换到 ExoPlayer（带回退机制）
                    // 原因：IJK 基于 FFmpeg，不支持 HLS discontinuity，会导致 seek 跳转错误
                    // ExoPlayer 和阿里云播放器原生支持 discontinuity，不需要切换
                    if (hasPtsJump) {
                        String currentEngine = getCurrentPlayerEngine();
                        
                        // 只有当前使用 IJK 内核时才需要切换
                        if (PlayerConstants.ENGINE_IJK.equals(currentEngine)) {
                            String targetEngine = null;
                            
                            // 优先级：ExoPlayer > 阿里云 > 系统播放器
                            if (isEngineAvailable(PlayerConstants.ENGINE_EXO)) {
                                targetEngine = PlayerConstants.ENGINE_EXO;
                                android.util.Log.w(TAG, "PTS jump detected with IJK player, switching to ExoPlayer for better compatibility");
                            } else if (isEngineAvailable(PlayerConstants.ENGINE_ALI)) {
                                targetEngine = PlayerConstants.ENGINE_ALI;
                                android.util.Log.w(TAG, "PTS jump detected with IJK player, ExoPlayer not available, switching to AliPlayer");
                            } else {
                                targetEngine = PlayerConstants.ENGINE_DEFAULT;
                                android.util.Log.w(TAG, "PTS jump detected with IJK player, ExoPlayer and AliPlayer not available, switching to System Player");
                            }
                            
                            selectPlayerFactory(targetEngine, true); // 临时切换
                        } else {
                            android.util.Log.i(TAG, "PTS jump detected but current player (" + currentEngine + ") supports discontinuity, no need to switch");
                        }
                    }
                    
                    // 结束 M3U8 去广告状态
                    setOrangePlayState(STATE_M3U8_AD_REMOVAL_END);
                    bindResolvedVideoSource(playUrl, cacheWithPlay, requestTitle, requestHeaders);
                    setOrangePlayState(PlayerConstants.STATE_PREPARING);
                    setStateAndUi(CURRENT_STATE_PREPAREING);
                    mAdState.setBypassOnce(true);
                    startPlayLogic();
                });
            }
        });
    }

    /**
     * 内部setUp方法，用于去广告后设置播放
     */
    private boolean setUpInternal(String url, boolean cacheWithPlay, String title) {
        return super.setUp(url, cacheWithPlay, title);
    }

    /**
     * 内部setUp方法，用于去广告后设置播放（带headers）
     */
    private boolean setUpInternal(String url, boolean cacheWithPlay, java.io.File cachePath,
            Map<String, String> mapHeadData, String title) {
        return super.setUp(url, cacheWithPlay, cachePath, mapHeadData, title);
    }


    @Override
    public boolean setUp(String url, boolean cacheWithPlay, java.io.File cachePath, String title) {
        mVideoHeaders = null;
        clearM3U8AdRemovalState();
        saveVideoUrl(url);

        // 重置临时设置（跳过片头片尾、倍速、画面比例）
        if (mOrangeController != null && mOrangeController.getVideoEventManager() != null) {
            mOrangeController.getVideoEventManager().resetTemporarySettings(url);
        }
        
        // 恢复用户原始内核偏好（如果之前有临时切换）
        if (mAdState.getUserPreferredEngine() != null && !mAdState.isSkipEngineRestore()) {
            String currentEngine = getCurrentPlayerEngine();
            if (!currentEngine.equals(mAdState.getUserPreferredEngine())) {
                android.util.Log.i(TAG, "恢复用户原始内核偏好: " + mAdState.getUserPreferredEngine() + " (当前: " + currentEngine + ")");
                selectPlayerFactory(mAdState.getUserPreferredEngine(), false);
            } else {
                android.util.Log.i(TAG, "当前内核已是用户偏好: " + mAdState.getUserPreferredEngine() + "，无需恢复");
            }
            mAdState.setUserPreferredEngine(null);
        }
        
        // 重置跳过标志
        mAdState.setSkipEngineRestore(false);
        
        // 自动选择最合适的播放器内核
        autoSelectPlayerEngine(url);
        boolean result = super.setUp(url, cacheWithPlay, cachePath, title);
        // 重新绑定 SkipManager（必须在 super.setUp() 之后，因为 setUp 内部会调用 release() 解绑）
        if (mSkipManager != null) {
            mSkipManager.attachVideoView(this);
        }
        updateTitleView(title);
        return result;
    }

    @Override
    public boolean setUp(String url, boolean cacheWithPlay, java.io.File cachePath, Map<String, String> mapHeadData,
            String title) {
        mVideoHeaders = mapHeadData != null ? new HashMap<>(mapHeadData) : null;
        clearM3U8AdRemovalState();
        saveVideoUrl(url);

        // 重置临时设置（跳过片头片尾、倍速、画面比例）
        if (mOrangeController != null && mOrangeController.getVideoEventManager() != null) {
            mOrangeController.getVideoEventManager().resetTemporarySettings(url);
        }
        
        // 恢复用户原始内核偏好（如果之前有临时切换）
        if (mAdState.getUserPreferredEngine() != null && !mAdState.isSkipEngineRestore()) {
            String currentEngine = getCurrentPlayerEngine();
            if (!currentEngine.equals(mAdState.getUserPreferredEngine())) {
                android.util.Log.i(TAG, "恢复用户原始内核偏好: " + mAdState.getUserPreferredEngine() + " (当前: " + currentEngine + ")");
                selectPlayerFactory(mAdState.getUserPreferredEngine(), false);
            } else {
                android.util.Log.i(TAG, "当前内核已是用户偏好: " + mAdState.getUserPreferredEngine() + "，无需恢复");
            }
            mAdState.setUserPreferredEngine(null);
        }
        
        // 重置跳过标志
        mAdState.setSkipEngineRestore(false);
        
        // 自动选择最合适的播放器内核
        autoSelectPlayerEngine(url);
        boolean result = super.setUp(url, cacheWithPlay, cachePath, mapHeadData, title);
        // 重新绑定 SkipManager（必须在 super.setUp() 之后，因为 setUp 内部会调用 release() 解绑）
        if (mSkipManager != null) {
            mSkipManager.attachVideoView(this);
        }
        updateTitleView(title);
        return result;
    }

    /**
     * 根据 URL 自动选择最合适的播放器内核
     * 
     * 选择规则：
     * - RTSP 协议 → ExoPlayer（阿里云不支持）
     * - RTMP 协议 → 阿里云（延迟低，性能好）
     * - HLS (m3u8) → 阿里云（商业级优化）
     * - HTTP/HTTPS → ExoPlayer（性能好）
     * - 其他 → ExoPlayer
     * 
     * 注意：只有启用自动选择功能时才会执行
     */
    private void autoSelectPlayerEngine(String url) {
        // 检查是否启用自动内核选择
        if (!PlayerSettingsManager.getInstance(getContext()).isAutoSelectEngine()) {
            return;
        }

        if (url == null || url.isEmpty()) {
            return;
        }

        // 使用智能内核选择器
        String recommendedEngine = com.orange.playerlibrary.utils.PlayerEngineSelector.selectEngine(url);

        // 检查推荐的内核是否可用
        if (!isEngineAvailable(recommendedEngine)) {
            android.util.Log.w(TAG, "推荐的播放器内核不可用: " +
                    com.orange.playerlibrary.utils.PlayerEngineSelector.getEngineName(recommendedEngine) +
                    "，将使用当前内核");
            return;
        }

        // 只在需要时才切换内核（避免不必要的切换）
        String currentEngine = getCurrentPlayerEngine();
        if (!currentEngine.equals(recommendedEngine)) {
            selectPlayerFactory(recommendedEngine, true); // 临时切换
            android.util.Log.i(TAG, "自动切换播放器内核: " +
                    com.orange.playerlibrary.utils.PlayerEngineSelector.getEngineName(recommendedEngine) +
                    " (协议: " + com.orange.playerlibrary.utils.PlayerEngineSelector.getProtocolType(url) + ")");
        }
    }

    /**
     * 获取当前使用的播放器内核
     */
    private String getCurrentPlayerEngine() {
        IPlayerManager currentManager = GSYVideoManager.instance().getPlayer();

        if (currentManager == null) {
            return PlayerConstants.ENGINE_EXO; // 默认 ExoPlayer（现代、稳定、支持 discontinuity）
        }

        String className = currentManager.getClass().getName();

        if (className.contains("OrangeExoPlayerManager")) {
            return PlayerConstants.ENGINE_EXO;
        } else if (className.contains("IjkPlayerManager")) {
            return PlayerConstants.ENGINE_IJK;
        } else if (className.contains("AliPlayerManager")) {
            return PlayerConstants.ENGINE_ALI;
        } else if (className.contains("SystemPlayerManager")) {
            return PlayerConstants.ENGINE_DEFAULT;
        }

        return PlayerConstants.ENGINE_EXO; // 默认 ExoPlayer
    }

    public void setUrl(String url) {
        setUrl(url, null);
    }

    public void setUrl(String url, Map<String, String> headers) {
        this.mVideoHeaders = headers != null ? new HashMap<>(headers) : null;

        if (headers != null) {

            setUp(url, true, null, headers, "");
        } else {
            setUp(url, true, "");
        }
    }

    public String getUrl() {
        return mOriginUrl != null ? mOriginUrl : mVideoUrl;
    }

    public String getVideoUrl() {
        return mOriginUrl != null ? mOriginUrl : mVideoUrl;
    }

    public Map<String, String> getVideoHeaders() {
        return mVideoHeaders;
    }

    public void start() {
        mUserPaused = false; // 清除用户暂停标记
        mSniffingDelegate.setSniffing(false);
        mIsLiveVideo = false;
        mIsLoadingThumbnail = false; // 重置首帧加载状态
        if (mSkipManager != null) {
            mSkipManager.reset();
        }
        if (mErrorRecoveryManager != null) {
            mErrorRecoveryManager.startBlackScreenDetection();
            mErrorRecoveryManager.startStateConsistencyCheck();
        }
        // 清除旧的缩略图
        if (mOrangeController != null) {
            mOrangeController.setThumbnail(null);
        }

        // 不在这里设置 STATE_PREPARING，移到 startPlayLogic() 中
        // 确保在 PrepareView 附加到窗口后才发送状态通知
        startPlayLogic();
    }

    public void pause() {
        mUserPaused = true; // 标记用户主动暂停
        if (mKeepVideoPlaying) {
            savePlaybackProgress();
        }
        if (mSkipManager != null) {
            mSkipManager.stopOutroCheck();
        }
        onVideoPause();
    }

    public void resume() {
        mUserPaused = false; // 清除用户暂停标记
        onVideoResume();
        if (mSkipManager != null) {
            mSkipManager.startOutroCheck();
        }
    }

    /**
     * 重写 onSurfaceDestroyed - 关键方法
     * 
     * 问题分析：
     * 当屏幕旋转时，TextureView 会被销毁并重建，触发 onSurfaceTextureDestroyed
     * GSY 的默认实现会调用 setDisplay(null) 和 releaseSurface()，导致播放器重置
     * 
     * 解决方案：
     * 1. 在全屏切换期间，跳过 Surface 释放，保持播放器状态
     * 2. 在 TextureView 模式下（OCR 功能），先切换到 PlaceholderSurface 再销毁
     */
    @Override
    public boolean onSurfaceDestroyed(Surface surface) {
        // 全屏切换时跳过
        if (mFullscreenHelper != null && mFullscreenHelper.isFullscreenTransitioning()) {
            return true;
        }

        // 画中画模式时跳过
        if (mEnteringPiPMode) {
            return true;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Activity activity = getActivity();
            if (activity != null && activity.isInPictureInPictureMode()) {
                return true;
            }
        }

        // TextureView 模式下（OCR 功能启用时），先切换到 PlaceholderSurface
        // 这样可以避免 MediaCodec 渲染到已销毁的 Surface 导致崩溃
        String currentEngine = PlayerSettingsManager.getInstance(getContext()).getPlayerEngine();
        boolean isExoTextureMode = PlayerConstants.ENGINE_EXO.equals(currentEngine)
                && com.orange.playerlibrary.exo.OrangeExoPlayerManager.isForceTextureViewMode();
        boolean isSystemTextureMode = PlayerConstants.ENGINE_DEFAULT.equals(currentEngine)
                && com.orange.playerlibrary.player.OrangeSystemPlayerManager.isForceTextureViewMode();

        if (isExoTextureMode || isSystemTextureMode) {
            // 通过 setDisplay(null) 触发切换到 PlaceholderSurface
            setDisplay(null);
            // 返回 true 表示我们已经处理了 Surface 销毁
            return true;
        }

        return super.onSurfaceDestroyed(surface);
    }

    /**
     * 重写 setDisplay - 关键方法
     * 
     * ExoPlayer 全屏切换问题解决方案：
     * 使用 SurfaceControl.reparent() (Android Q+) 来无缝切换 Surface，
     * 避免 MediaCodec 在 Surface 切换时被释放导致的 IllegalStateException
     */
    @Override
    protected void setDisplay(Surface surface) {
        // 检查是否使用 ExoPlayer 或系统播放器
        String currentEngine = PlayerSettingsManager.getInstance(getContext()).getPlayerEngine();
        boolean isExoPlayer = PlayerConstants.ENGINE_EXO.equals(currentEngine);
        boolean isSystemPlayer = PlayerConstants.ENGINE_DEFAULT.equals(currentEngine);

        // Android Q+ 使用 SurfaceControl.reparent 方式处理 Surface 切换
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isExoPlayer) {
                setDisplayForExo(surface);
                return;
            } else if (isSystemPlayer) {
                setDisplayForSystem(surface);
                return;
            }
        }

        // 其他播放器或低版本 Android，使用原有逻辑
        if (mFullscreenHelper != null && mFullscreenHelper.isFullscreenTransitioning()) {
            if (surface != null) {
                super.setDisplay(surface);
            }
            // 跳过 setDisplay(null)，保持播放状态
            return;
        }
        super.setDisplay(surface);
    }

    /**
     * 系统播放器专用的 Surface 切换方法
     * 使用 OrangeSystemPlayerManager 的 setDisplayNew 方法实现无缝切换
     */
    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.Q)
    private void setDisplayForSystem(Surface surface) {
        com.shuyu.gsyvideoplayer.player.IPlayerManager playerManager = GSYVideoManager.instance().getPlayer();

        boolean isSurfaceView = mTextureView != null && mTextureView.getShowView() instanceof SurfaceView;

        if (playerManager instanceof com.orange.playerlibrary.player.OrangeSystemPlayerManager) {
            com.orange.playerlibrary.player.OrangeSystemPlayerManager systemManager = (com.orange.playerlibrary.player.OrangeSystemPlayerManager) playerManager;

            if (surface != null && isSurfaceView) {
                SurfaceView surfaceView = (SurfaceView) mTextureView.getShowView();
                systemManager.setDisplayNew(surfaceView);
            } else if (surface != null) {
                GSYVideoManager.instance().setDisplay(surface);
            } else {
                systemManager.setDisplayNew(null);
            }
        } else {
            if (surface != null) {
                GSYVideoManager.instance().setDisplay(surface);
            }
        }
    }

    /**
     * ExoPlayer 专用的 Surface 切换方法
     * 完全按照 GSY 官方 GSYExo2PlayerView 的实现方式
     * 使用 OrangeExoPlayerManager 的 setDisplayNew 方法实现无缝切换
     */
    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.Q)
    private void setDisplayForExo(Surface surface) {
        // 获取当前的 PlayerManager
        com.shuyu.gsyvideoplayer.player.IPlayerManager playerManager = GSYVideoManager.instance().getPlayer();

        // 添加调试日志
        boolean isSurfaceView = mTextureView != null && mTextureView.getShowView() instanceof SurfaceView;
        // 检查是否是 OrangeExoPlayerManager
        if (playerManager instanceof com.orange.playerlibrary.exo.OrangeExoPlayerManager) {
            com.orange.playerlibrary.exo.OrangeExoPlayerManager exoManager = (com.orange.playerlibrary.exo.OrangeExoPlayerManager) playerManager;

            // 完全按照 GSY 官方的逻辑
            if (surface != null && isSurfaceView) {
                // 使用 SurfaceView 进行 reparent
                SurfaceView surfaceView = (SurfaceView) mTextureView.getShowView();
                exoManager.setDisplayNew(surfaceView);
            } else if (surface != null) {
                // 非 SurfaceView，使用普通方式（这种情况不应该发生）
                GSYVideoManager.instance().setDisplay(surface);
            } else {
                // surface 为 null，也要通过 setDisplayNew 处理
                exoManager.setDisplayNew(null);
            }
        } else {
            // 不是 OrangeExoPlayerManager，使用传统方式
            if (surface != null) {
                GSYVideoManager.instance().setDisplay(surface);
            }
        }
    }

    /**
     * 使用 SurfaceControl.reparent 切换 Surface
     * 这是 GSY 官方 ExoPlayer 示例的核心方法
     */
    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.Q)
    private void reparentExoSurface(SurfaceView surfaceView) {
        if (!mSurfaceControlHelper.reparent(surfaceView)) {
            // 回退到普通方式
            if (surfaceView != null) {
                super.setDisplay(surfaceView.getHolder().getSurface());
            }
        }
    }

    /**
     * 初始化 ExoPlayer 的 SurfaceControl
     * 在播放开始时调用
     */
    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.Q)
    private void initExoSurfaceControl() {
        mSurfaceControlHelper.init();
    }

    /**
     * 释放 ExoPlayer 的 SurfaceControl
     */
    private void releaseExoSurfaceControl() {
        mSurfaceControlHelper.release();
    }

    /**
     * 重写 releaseSurface - 关键方法
     * 
     * 在全屏切换时跳过 Surface 释放
     * ExoPlayer 使用 SurfaceControl 时不需要释放 Surface
     */
    @Override
    protected void releaseSurface(Surface surface) {
        // ExoPlayer 使用 SurfaceControl 时，不释放 Surface
        if (mSurfaceControlHelper.isActive()) {
            return;
        }

        if (mFullscreenHelper != null && mFullscreenHelper.isFullscreenTransitioning()) {
            return;
        }
        if (mEnteringPiPMode) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Activity activity = getActivity();
            if (activity != null && activity.isInPictureInPictureMode()) {
                return;
            }
        }
        super.releaseSurface(surface);
    }

    @Override
    public void onVideoPause() {
        long startTime = System.currentTimeMillis();
        if (mEnteringPiPMode) {
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Activity activity = getActivity();
            if (activity != null && activity.isInPictureInPictureMode()) {
                return;
            }
        }

        // 如果已经是暂停状态，不需要再次暂停
        if (mCurrentPlayState == PlayerConstants.STATE_PAUSED) {
            return;
        }

        boolean shouldUpdateState = (mCurrentPlayState == PlayerConstants.STATE_PLAYING ||
                mCurrentPlayState == PlayerConstants.STATE_BUFFERING ||
                mCurrentPlayState == PlayerConstants.STATE_BUFFERED);

        // 直接调用 GSYVideoManager 暂停，确保立即生效
        try {
            getGSYVideoManager().getPlayer().pause();
        } catch (Exception e) {
        }
        super.onVideoPause();

        if (shouldUpdateState) {
            mCurrentPlayState = PlayerConstants.STATE_PAUSED;
            notifyComponentsPlayStateChanged(PlayerConstants.STATE_PAUSED);
            if (mCurrentState != CURRENT_STATE_PAUSE) {
                mCurrentState = CURRENT_STATE_PAUSE;
            }
        }

        long endTime = System.currentTimeMillis();
    }

    @Override
    public void onVideoResume() {
        // 默认不 seek，直接从当前位置继续播放
        // seek=true 适用于"从指定位置恢复"的场景，而非切后台继续
        onVideoResume(false);
    }

    @Override
    public void onVideoResume(boolean seek) {
        if (mUserPaused) {
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Activity activity = getActivity();
            if (activity != null && activity.isInPictureInPictureMode()) {
                return;
            }
        }

        // 检查当前播放器内核
        String currentEngine = PlayerSettingsManager.getInstance(getContext()).getPlayerEngine();
        boolean isExoOrSystem = PlayerConstants.ENGINE_EXO.equals(currentEngine)
                || PlayerConstants.ENGINE_DEFAULT.equals(currentEngine);
        boolean isIjk = PlayerConstants.ENGINE_IJK.equals(currentEngine);

        if ((isExoOrSystem || isIjk) && mCurrentState == CURRENT_STATE_PAUSE) {
            // ExoPlayer、系统播放器和 IJK 播放器都需要特殊处理
            // 保存暂停时的位置（GSY 基类的 mCurrentPosition 在 onVideoPause 时保存）
            long savedPosition = mCurrentPosition;
            try {
                if (savedPosition >= 0 && getGSYVideoManager() != null) {
                    if (isIjk) {
                        // IJK 播放器：先 seek 再 start，确保 seek 操作在 start 之前完成
                        // 这样可以避免后台切换时进度重置的问题
                        if (seek && savedPosition > 0) {
                            getGSYVideoManager().seekTo(savedPosition);
                            // 给 IJK 一点时间完成 seek 操作（IJK 的 seekTo 是同步调用但异步执行）
                            // 使用 post 延迟 start，确保 seek 先执行
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        if (getGSYVideoManager() != null) {
                                            getGSYVideoManager().start();
                                        }
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            });
                        } else {
                            getGSYVideoManager().start();
                        }
                    } else {
                        // ExoPlayer 和系统播放器：先 start 再 seek（在播放状态下 seek 更可靠）
                        getGSYVideoManager().start();
                        if (seek && savedPosition > 0) {
                            getGSYVideoManager().seekTo(savedPosition);
                        }
                    }

                    setStateAndUi(CURRENT_STATE_PLAYING);

                    // 更新 Orange 状态
                    mCurrentPlayState = PlayerConstants.STATE_PLAYING;
                    notifyComponentsPlayStateChanged(PlayerConstants.STATE_PLAYING);

                    // 清零位置（与 GSY 基类行为一致）
                    mCurrentPosition = 0;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            // 其他情况，使用 GSY 基类的默认实现
            boolean shouldUpdateState = (mCurrentPlayState == PlayerConstants.STATE_PAUSED);
            super.onVideoResume(seek);
            if (shouldUpdateState) {
                mCurrentPlayState = PlayerConstants.STATE_PLAYING;
                notifyComponentsPlayStateChanged(PlayerConstants.STATE_PLAYING);
                if (mCurrentState != CURRENT_STATE_PLAYING) {
                    mCurrentState = CURRENT_STATE_PLAYING;
                }
            }
        }
    }

    public void setEnteringPiPMode(boolean entering) {
        this.mEnteringPiPMode = entering;
    }

    public boolean isEnteringPiPMode() {
        return mEnteringPiPMode;
    }

    @Override
    public void release() {
        // 停止播放历史自动保存
        stopPlayHistoryAutoSave();

        if (mKeepVideoPlaying) {
            savePlaybackProgress();
        }
        if (mSkipManager != null) {
            mSkipManager.detachVideoView();
        }
        if (mErrorRecoveryManager != null) {
            mErrorRecoveryManager.detachVideoView();
        }
        // 释放 ExoPlayer 的 SurfaceControl
        releaseExoSurfaceControl();
        super.release();
        setOrangePlayState(PlayerConstants.STATE_IDLE);
        GSYVideoManager.releaseAllVideos();
    }

    public long getCurrentPosition() {
        return getCurrentPositionWhenPlaying();
    }

    public void seekTo(int position) {
        seekTo((long) position);
    }

    public void seekTo(long position) {
        long duration = getDuration();
        android.util.Log.d(TAG, "seekTo: position=" + position + "ms, duration=" + duration + "ms, url=" + mOriginUrl);
        if (GSYVideoManager.instance().getPlayer() != null) {
            GSYVideoManager.instance().getPlayer().seekTo(position);
        } else {
            setSeekOnStart(position);
        }
    }

    @Override
    public void setSpeed(float speed) {
        // 扩大倍速范围限制：0.35x - 5.0x（普通倍速）
        if (speed < 0.35f)
            speed = 0.35f;
        if (speed > 5.0f)
            speed = 5.0f;
        sSpeed = speed;

        // 添加日志
        String currentEngine = PlayerSettingsManager.getInstance(getContext()).getPlayerEngine();
        // 直接设置倍速
        try {
            super.setSpeed(speed);
        } catch (Exception e) {
        }

        // 对于 IJK 和系统播放器，确保倍速立即生效
        // 某些情况下需要在播放状态下才能设置倍速
        if (isPlaying()) {
            final float finalSpeed = speed;
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.postDelayed(() -> {
                try {
                    // 延迟再次设置，确保生效
                    super.setSpeed(finalSpeed);
                } catch (Exception e) {
                }
            }, 100);
        }
    }

    public static float getSpeeds() {
        return sSpeed;
    }

    public static void setSpeeds(float speed) {
        // 扩大倍速范围限制：0.35x - 5.0x
        if (speed < 0.35f)
            speed = 0.35f;
        if (speed > 5.0f)
            speed = 5.0f;
        sSpeed = speed;
    }

    public static float getLongSpeeds() {
        return sLongSpeed;
    }

    public static void setLongSpeeds(float speed) {
        sLongSpeed = speed;
    }

    public void startFullScreen() {
        // 检查智能全屏是否启用
        PlayerSettingsManager settingsManager = PlayerSettingsManager.getInstance(getContext());
        if (settingsManager != null && settingsManager.isSmartFullscreenEnabled()) {
            // 智能全屏：根据视频宽高比自动选择全屏模式
            android.util.Log.d(TAG, "startFullScreen: 智能全屏已启用，检测视频宽高比");

            // 延迟执行，确保视频尺寸已经准备好
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    applySmartFullscreen();
                }
            }, 100);
        } else {
            // 默认行为：横屏全屏
            android.util.Log.d(TAG, "startFullScreen: 使用默认横屏全屏");
            Activity activity = getActivity();
            if (activity != null && mFullscreenHelper != null) {
                mFullscreenHelper.enterFullscreen(activity);
            }
        }
    }

    public void stopFullScreen() {
        Activity activity = getActivity();
        if (activity != null && mFullscreenHelper != null) {
            mFullscreenHelper.exitFullscreen(activity);
        }
    }

    /**
     * 进入竖屏全屏模式
     * 不旋转屏幕，只是将播放器移动到全屏显示
     */
    public void startPortraitFullScreen() {
        if (mFullscreenHelper != null) {
            mFullscreenHelper.startPortraitFullScreen();
        }
    }

    /**
     * 退出竖屏全屏模式
     */
    public void stopPortraitFullScreen() {
        if (mFullscreenHelper != null) {
            mFullscreenHelper.stopPortraitFullScreen();
        }
    }

    /**
     * 是否竖屏全屏
     */
    public boolean isPortraitFullScreen() {
        return mFullscreenHelper != null && mFullscreenHelper.isPortraitFullscreen();
    }

    public boolean isFullScreen() {
        return mFullscreenHelper != null && mFullscreenHelper.isFullscreen();
    }

    /**
     * 智能全屏：根据视频宽高比自动选择全屏模式
     * 横屏视频（宽 > 高）→ 横屏全屏
     * 竖屏视频（高 > 宽）→ 竖屏全屏
     */
    private void applySmartFullscreen() {
        android.util.Log.d(TAG, "applySmartFullscreen: 开始智能全屏检测");

        // 检查 mFullscreenHelper 是否为 null
        if (mFullscreenHelper == null) {
            android.util.Log.e(TAG, "applySmartFullscreen: mFullscreenHelper 为 null，无法执行全屏操作");
            return;
        }

        // 获取视频宽高
        int videoWidth = getCurrentVideoWidth();
        int videoHeight = getCurrentVideoHeight();

        android.util.Log.d(TAG, "applySmartFullscreen: 获取到视频尺寸 width=" + videoWidth + ", height=" + videoHeight);

        if (videoWidth <= 0 || videoHeight <= 0) {
            android.util.Log.w(TAG, "applySmartFullscreen: 视频尺寸无效，使用默认横屏全屏");
            // 视频尺寸无效，使用默认横屏全屏
            Activity activity = getActivity();
            if (activity != null) {
                android.util.Log.d(TAG, "applySmartFullscreen: 调用 enterFullscreen()");
                mFullscreenHelper.enterFullscreen(activity);
            } else {
                android.util.Log.e(TAG, "applySmartFullscreen: Activity 为 null");
            }
            return;
        }

        // 计算宽高比
        float aspectRatio = (float) videoWidth / videoHeight;
        android.util.Log.d(TAG,
                "applySmartFullscreen: 视频尺寸 " + videoWidth + "x" + videoHeight + ", 宽高比=" + aspectRatio);

        // 根据宽高比选择全屏模式
        if (videoWidth > videoHeight) {
            // 横屏视频 → 横屏全屏
            android.util.Log.d(TAG, "applySmartFullscreen: 检测到横屏视频，进入横屏全屏");
            Activity activity = getActivity();
            if (activity != null) {
                android.util.Log.d(TAG, "applySmartFullscreen: 调用 enterFullscreen()");
                mFullscreenHelper.enterFullscreen(activity);
            } else {
                android.util.Log.e(TAG, "applySmartFullscreen: Activity 为 null");
            }
        } else {
            // 竖屏视频 → 竖屏全屏
            android.util.Log.d(TAG, "applySmartFullscreen: 检测到竖屏视频，进入竖屏全屏");
            android.util.Log.d(TAG, "applySmartFullscreen: 调用 startPortraitFullScreen()");
            mFullscreenHelper.startPortraitFullScreen();
        }
    }

    /**
     * 设置是否启用智能全屏
     * 
     * @param enabled true 启用，false 禁用
     */
    public void setSmartFullscreenEnabled(boolean enabled) {
        PlayerSettingsManager settingsManager = PlayerSettingsManager.getInstance(getContext());
        if (settingsManager != null) {
            settingsManager.setSmartFullscreenEnabled(enabled);
            android.util.Log.d(TAG, "setSmartFullscreenEnabled: " + enabled);
        }
    }

    /**
     * 查询智能全屏是否启用
     * 
     * @return true 启用，false 禁用
     */
    public boolean isSmartFullscreenEnabled() {
        PlayerSettingsManager settingsManager = PlayerSettingsManager.getInstance(getContext());
        return settingsManager != null && settingsManager.isSmartFullscreenEnabled();
    }

    public boolean isTinyScreen() {
        return mCurrentPlayerState == PlayerConstants.PLAYER_TINY_SCREEN;
    }

    public void setAutoRotateOnFullscreen(boolean autoRotate) {
        this.mAutoRotateOnFullscreen = autoRotate;

        // 同步到 PlayerSettingsManager
        PlayerSettingsManager settingsManager = PlayerSettingsManager.getInstance(getContext());
        if (settingsManager != null) {
            settingsManager.setAutoRotateEnabled(autoRotate);
        }

        // 同步到 CustomFullscreenHelper
        if (mFullscreenHelper != null) {
            mFullscreenHelper.setAutoRotateEnabled(autoRotate);
        }
    }

    public boolean isAutoRotateOnFullscreen() {
        return mAutoRotateOnFullscreen;
    }

    @SuppressWarnings("unchecked")
    /**
     * 选择播放器工厂（临时切换）
     * @param engineType 播放器内核类型
     * @param temporary 是否为临时切换（true=临时切换，false=永久切换）
     */
    public void selectPlayerFactory(String engineType, boolean temporary) {
        if (engineType == null) {
            engineType = PlayerConstants.ENGINE_DEFAULT;
        }
        
        // 如果是临时切换，保存用户原始偏好
        if (temporary && mAdState.getUserPreferredEngine() == null) {
            String currentUserPreference = PlayerSettingsManager.getInstance(getContext()).getPlayerEngine();
            // 只有当临时切换的内核与用户偏好不同时，才保存用户偏好
            if (!currentUserPreference.equals(engineType)) {
                mAdState.setUserPreferredEngine(currentUserPreference);
                android.util.Log.i(TAG, "临时切换内核: " + engineType + "，已保存用户偏好: " + mAdState.getUserPreferredEngine());
            } else {
                android.util.Log.i(TAG, "临时切换内核: " + engineType + "，与用户偏好相同，无需保存");
            }
        } else if (!temporary) {
            // 永久切换，清除临时偏好并更新用户设置
            mAdState.setUserPreferredEngine(null);
            PlayerSettingsManager.getInstance(getContext()).setPlayerEngine(engineType);
            android.util.Log.i(TAG, "永久切换内核: " + engineType);
        }
        
        // 执行实际的内核切换
        selectPlayerFactoryInternal(engineType);
        
        // 通知 UI 更新（如果有控制器）
        if (mOrangeController != null && mOrangeController.getVideoEventManager() != null) {
            mOrangeController.getVideoEventManager().notifyEngineChanged(engineType);
        }
    }
    
    /**
     * 选择播放器工厂（兼容旧版本，默认为永久切换）
     * @param engineType 播放器内核类型
     */
    public void selectPlayerFactory(String engineType) {
        selectPlayerFactory(engineType, false);
    }
    
    /**
     * 内部方法：实际执行播放器工厂切换
     */
    private void selectPlayerFactoryInternal(String engineType) {
        if (engineType == null) {
            engineType = PlayerConstants.ENGINE_DEFAULT;
        }
        // 1. 先释放当前播放器
        GSYVideoManager.releaseAllVideos();

        // 2. 设置新的播放器工厂
        switch (engineType) {
            case PlayerConstants.ENGINE_IJK:
                PlayerFactory.setPlayManager(com.orange.playerlibrary.player.OrangeIjkPlayerManager.class);
                // IJK 播放器使用 TextureView 模式（更稳定）
                com.shuyu.gsyvideoplayer.utils.GSYVideoType.setRenderType(
                        com.shuyu.gsyvideoplayer.utils.GSYVideoType.TEXTURE);
                break;
            case PlayerConstants.ENGINE_EXO:
                // 使用自定义的 OrangeExoPlayerManager，支持 SurfaceControl 无缝切换
                try {
                    PlayerFactory.setPlayManager(com.orange.playerlibrary.exo.OrangeExoPlayerManager.class);

                    // 默认强制使用 TextureView 渲染模式（与 initPlayerFactory 保持一致）
                    // 用户可以通过 setRenderMode() 手动切换到 SurfaceView
                    com.shuyu.gsyvideoplayer.utils.GSYVideoType.setRenderType(
                            com.shuyu.gsyvideoplayer.utils.GSYVideoType.TEXTURE);
                    com.orange.playerlibrary.exo.OrangeExoPlayerManager.setForceTextureViewMode(true);
                    android.util.Log.d(TAG, "selectPlayerFactory: ExoPlayer 使用 TextureView 渲染模式");
                } catch (Exception e) {
                    // 回退到 GSY 原生 Exo2PlayerManager
                    try {
                        Class<?> exoClass = Class.forName("tv.danmaku.ijk.media.exo2.Exo2PlayerManager");
                        PlayerFactory.setPlayManager((Class<? extends IPlayerManager>) exoClass);
                        android.util.Log.w(TAG, "selectPlayerFactory: 回退到 GSY Exo2PlayerManager");
                    } catch (ClassNotFoundException ex) {
                        android.util.Log.e(TAG, "selectPlayerFactory: Exo2PlayerManager 未找到，回退到系统播放器", ex);
                        PlayerFactory.setPlayManager(com.orange.playerlibrary.player.OrangeSystemPlayerManager.class);
                    }
                }
                break;
            case PlayerConstants.ENGINE_ALI:
                // GSY AliPlayer 类名: com.shuyu.aliplay.AliPlayerManager
                try {
                    Class<?> aliClass = Class.forName("com.shuyu.aliplay.AliPlayerManager");
                    PlayerFactory.setPlayManager((Class<? extends IPlayerManager>) aliClass);
                    com.shuyu.gsyvideoplayer.utils.GSYVideoType.setRenderType(
                            com.shuyu.gsyvideoplayer.utils.GSYVideoType.TEXTURE);
                    android.util.Log.d(TAG, "selectPlayerFactory: 使用阿里云播放器");
                } catch (ClassNotFoundException e) {
                    android.util.Log.e(TAG, "selectPlayerFactory: 阿里云播放器未找到，回退到系统播放器", e);
                    PlayerFactory.setPlayManager(com.orange.playerlibrary.player.OrangeSystemPlayerManager.class);
                }
                break;
            case PlayerConstants.ENGINE_DEFAULT:
            default:
                // 使用自定义的 OrangeSystemPlayerManager，统一网速计算和 SurfaceControl 支持
                PlayerFactory.setPlayManager(com.orange.playerlibrary.player.OrangeSystemPlayerManager.class);

                // 默认强制使用 TextureView 渲染模式（与 initPlayerFactory 保持一致）
                com.shuyu.gsyvideoplayer.utils.GSYVideoType.setRenderType(
                        com.shuyu.gsyvideoplayer.utils.GSYVideoType.TEXTURE);
                com.orange.playerlibrary.player.OrangeSystemPlayerManager.setForceTextureViewMode(true);
                android.util.Log.d(TAG, "selectPlayerFactory: 系统播放器使用 TextureView 渲染模式");
                break;
        }

        // 3. 重置播放器初始化标志，确保下次播放时使用新的工厂
        mPlayerFactoryInitialized = true;
    }

    protected void setOrangePlayState(int playState) {
        mCurrentPlayState = playState;
        notifyPlayStateChanged(playState);

        post(new Runnable() {
            @Override
            public void run() {
                if (playState == PlayerConstants.STATE_PLAYING) {
                    showController();
                } else if (playState == PlayerConstants.STATE_PAUSED) {
                    showController();
                    cancelAutoHideTimer();
                } else if (playState == STATE_STARTSNIFFING) {
                    // 嗅探开始 - 显示加载动画
                    changeUiToSniffingShow();
                } else if (playState == STATE_ENDSNIFFING) {
                    // 嗅探结束 - 隐藏加载动画
                    changeUiToSniffingEnd();
                } else if (playState == STATE_M3U8_AD_REMOVAL) {
                    // M3U8去广告开始 - 显示加载动画
                    changeUiToM3U8AdRemovalShow();
                } else if (playState == STATE_M3U8_AD_REMOVAL_END) {
                    // M3U8去广告结束 - 隐藏加载动画
                    changeUiToM3U8AdRemovalEnd();
                } else {
                    cancelAutoHideTimer();
                }
            }
        });
    }

    protected void setOrangePlayerState(int playerState) {
        mCurrentPlayerState = playerState;
        notifyPlayerStateChanged(playerState);
    }

    public int getPlayState() {
        return mCurrentPlayState;
    }

    public int getPlayerState() {
        return mCurrentPlayerState;
    }

    // ===== 播放器对象获取方法 =====

    /**
     * 获取当前播放器管理器（泛型）
     * 
     * @param <T>          播放器管理器类型
     * @param managerClass 期望的管理器类型 Class
     * @return 播放器管理器实例，类型不匹配时返回 null
     * 
     *         示例：
     *         OrangeExoPlayerManager exoManager =
     *         videoView.getPlayerManager(OrangeExoPlayerManager.class);
     *         OrangeIjkPlayerManager ijkManager =
     *         videoView.getPlayerManager(OrangeIjkPlayerManager.class);
     */
    @SuppressWarnings("unchecked")
    public <T extends com.shuyu.gsyvideoplayer.player.IPlayerManager> T getPlayerManager(Class<T> managerClass) {
        try {
            com.shuyu.gsyvideoplayer.player.IPlayerManager manager = getGSYVideoManager().getPlayer();
            if (manager != null && managerClass.isInstance(manager)) {
                return (T) manager;
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "getPlayerManager: 获取播放器管理器失败", e);
        }
        return null;
    }

    /**
     * 获取当前播放器管理器（原始类型）
     * 
     * @return IPlayerManager 接口实例
     */
    public com.shuyu.gsyvideoplayer.player.IPlayerManager getPlayerManager() {
        try {
            return getGSYVideoManager().getPlayer();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取当前播放器内核对象（泛型）
     * 
     * @param <T>         播放器内核类型
     * @param playerClass 期望的播放器类型 Class
     * @return 播放器内核实例，类型不匹配时返回 null
     * 
     *         示例：
     *         // ExoPlayer 内核
     *         tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer exoPlayer =
     *         videoView.getMediaPlayer(tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer.class);
     * 
     *         // IJK 内核
     *         tv.danmaku.ijk.media.player.IjkMediaPlayer ijkPlayer =
     *         videoView.getMediaPlayer(tv.danmaku.ijk.media.player.IjkMediaPlayer.class);
     * 
     *         // 系统播放器内核
     *         android.media.MediaPlayer systemPlayer =
     *         videoView.getMediaPlayer(android.media.MediaPlayer.class);
     */
    @SuppressWarnings("unchecked")
    public <T extends tv.danmaku.ijk.media.player.IMediaPlayer> T getMediaPlayer(Class<T> playerClass) {
        try {
            com.shuyu.gsyvideoplayer.player.IPlayerManager manager = getGSYVideoManager().getPlayer();
            if (manager != null) {
                tv.danmaku.ijk.media.player.IMediaPlayer mediaPlayer = manager.getMediaPlayer();
                if (mediaPlayer != null && playerClass.isInstance(mediaPlayer)) {
                    return (T) mediaPlayer;
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "getMediaPlayer: 获取播放器内核失败", e);
        }
        return null;
    }

    /**
     * 获取当前播放器内核对象（原始类型）
     * 
     * @return IMediaPlayer 接口实例
     */
    public tv.danmaku.ijk.media.player.IMediaPlayer getMediaPlayer() {
        try {
            com.shuyu.gsyvideoplayer.player.IPlayerManager manager = getGSYVideoManager().getPlayer();
            if (manager != null) {
                return manager.getMediaPlayer();
            }
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }

    /**
     * 获取当前播放器内核类型
     * 
     * @return 内核类型常量（ENGINE_EXO, ENGINE_IJK, ENGINE_DEFAULT, ENGINE_ALI）
     */
    public String getCurrentEngineType() {
        return PlayerSettingsManager.getInstance(getContext()).getPlayerEngine();
    }

    /**
     * 设置播放器音量（不影响系统音量）
     * 
     * @param volume 音量值（0.0-1.0）
     */
    public void setPlayerVolume(float volume) {
        try {
            com.shuyu.gsyvideoplayer.player.IPlayerManager manager = getGSYVideoManager().getPlayer();
            if (manager != null) {
                manager.setVolume(volume, volume);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "setPlayerVolume: 设置播放器音量失败", e);
        }
    }

    /**
     * 设置播放器音量（百分比形式）
     * 
     * @param volumePercent 音量百分比（0-100）
     */
    public void setPlayerVolumePercent(int volumePercent) {
        float volume = Math.max(0, Math.min(100, volumePercent)) / 100.0f;
        setPlayerVolume(volume);
    }

    // ==================== 静音控制 ====================

    private boolean mIsMuted = false;

    /**
     * 设置静音
     * 
     * @param isMute 是否静音
     */
    public void setMute(boolean isMute) {
        mIsMuted = isMute;
        try {
            com.shuyu.gsyvideoplayer.player.IPlayerManager manager = getGSYVideoManager().getPlayer();
            if (manager != null) {
                manager.setNeedMute(isMute);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "setMute: 设置静音失败", e);
        }
    }

    /**
     * 是否静音
     * 
     * @return true 静音
     */
    public boolean isMute() {
        return mIsMuted;
    }

    /**
     * 切换静音状态
     * 
     * @return 切换后的静音状态
     */
    public boolean toggleMute() {
        setMute(!mIsMuted);
        return mIsMuted;
    }

    // ==================== 循环播放 ====================

    private boolean mIsLooping = false;

    /**
     * 设置循环播放
     * 
     * @param looping 是否循环
     */
    public void setLooping(boolean looping) {
        mIsLooping = looping;
        // 通过设置管理器设置播放模式
        PlayerSettingsManager settingsManager = PlayerSettingsManager.getInstance(getContext());
        if (settingsManager != null) {
            settingsManager.setPlayMode(looping ? "single_loop" : "sequential");
        }
    }

    /**
     * 是否循环播放
     * 
     * @return true 循环
     */
    public boolean isLooping() {
        return mIsLooping;
    }

    // ==================== 截图功能 ====================

    /**
     * 截图
     * 
     * @param callback 截图回调
     */
    public void takeScreenshot(com.orange.playerlibrary.screenshot.ScreenshotManager.ScreenshotCallback callback) {
        com.orange.playerlibrary.screenshot.ScreenshotManager screenshotManager = new com.orange.playerlibrary.screenshot.ScreenshotManager(
                getContext(), this);
        screenshotManager.takeScreenshot(callback);
    }

    /**
     * 截图并保存到相册
     * 
     * @param callback 保存回调
     */
    public void takeScreenshotAndSave(com.orange.playerlibrary.screenshot.ScreenshotManager.SaveCallback callback) {
        com.orange.playerlibrary.screenshot.ScreenshotManager screenshotManager = new com.orange.playerlibrary.screenshot.ScreenshotManager(
                getContext(), this);
        screenshotManager.takeAndSave(callback);
    }

    // ==================== 缓冲进度 ====================

    /**
     * 获取缓冲进度百分比
     * 
     * @return 缓冲进度 (0-100)
     */
    public int getBufferedPercentage() {
        return getBuffterPoint();
    }

    /**
     * 判断当前是否使用 ExoPlayer 内核
     */
    public boolean isExoPlayerEngine() {
        return PlayerConstants.ENGINE_EXO.equals(getCurrentEngineType());
    }

    /**
     * 判断当前是否使用 IJK 内核
     */
    public boolean isIjkPlayerEngine() {
        return PlayerConstants.ENGINE_IJK.equals(getCurrentEngineType());
    }

    /**
     * 判断当前是否使用系统播放器内核
     */
    public boolean isSystemPlayerEngine() {
        return PlayerConstants.ENGINE_DEFAULT.equals(getCurrentEngineType());
    }

    /**
     * 判断当前是否使用阿里云播放器内核
     */
    public boolean isAliPlayerEngine() {
        return PlayerConstants.ENGINE_ALI.equals(getCurrentEngineType());
    }

    /**
     * 检查指定播放器内核是否可用（依赖是否已导入）
     * 
     * @param engine 内核类型常量
     * @return true 可用，false 不可用
     */
    public boolean isEngineAvailable(String engine) {
        try {
            if (PlayerConstants.ENGINE_EXO.equals(engine)) {
                // 检查 ExoPlayer 依赖（GSY ExoPlayer 或 Media3）
                try {
                    Class.forName("tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer");
                    return true;
                } catch (ClassNotFoundException e) {
                    // 尝试检测 Media3
                    Class.forName("androidx.media3.exoplayer.ExoPlayer");
                    return true;
                }
            } else if (PlayerConstants.ENGINE_IJK.equals(engine)) {
                // 检查 IJK 依赖（Java 类 + SO 库）
                Class.forName("tv.danmaku.ijk.media.player.IjkMediaPlayer");
                try {
                    tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(null);
                    return true;
                } catch (UnsatisfiedLinkError e) {
                    android.util.Log.w(TAG, "IJK SO 库未加载: " + e.getMessage());
                    return false;
                }
            } else if (PlayerConstants.ENGINE_ALI.equals(engine)) {
                // 检查阿里云播放器依赖
                Class.forName("com.aliyun.player.AliPlayer");
                return true;
            } else if (PlayerConstants.ENGINE_DEFAULT.equals(engine)) {
                // 系统播放器总是可用
                return true;
            }
            return false;
        } catch (ClassNotFoundException e) {
            android.util.Log.w(TAG, "播放器内核不可用: " +
                    com.orange.playerlibrary.utils.PlayerEngineSelector.getEngineName(engine) +
                    " (依赖未导入)");
            return false;
        }
    }

    public void addOnStateChangeListener(OnStateChangeListener listener) {
        if (mStateChangeListeners == null) {
            mStateChangeListeners = new ArrayList<>();
        }
        if (listener != null && !mStateChangeListeners.contains(listener)) {
            mStateChangeListeners.add(listener);
        }
    }

    public void removeOnStateChangeListener(OnStateChangeListener listener) {
        if (mStateChangeListeners != null) {
            mStateChangeListeners.remove(listener);
        }
    }

    public void clearOnStateChangeListeners() {
        if (mStateChangeListeners != null) {
            mStateChangeListeners.clear();
        }
    }

    private void notifyPlayStateChanged(int playState) {
        if (mStateChangeListeners != null) {
            for (OnStateChangeListener listener : mStateChangeListeners) {
                listener.onPlayStateChanged(playState);
            }
        }
        if (mUseOrangeComponents) {
            notifyComponentsPlayStateChanged(playState);
        }
        // 通知控制器更新加载动画和网速显示
        if (mOrangeController != null) {
            mOrangeController.onPlayStateChanged(playState);
        }
    }

    private void notifyPlayerStateChanged(int playerState) {
        if (mStateChangeListeners != null) {
            for (OnStateChangeListener listener : mStateChangeListeners) {
                listener.onPlayerStateChanged(playerState);
            }
        }
        if (mUseOrangeComponents) {
            notifyComponentsPlayerStateChanged(playerState);
        }
        // 通知控制器更新状态
        if (mOrangeController != null) {
            mOrangeController.onPlayerStateChanged(playerState);
        }
    }

    private void notifyComponentsPlayStateChanged(int playState) {
        if (mPrepareView != null) {
            mPrepareView.onPlayStateChanged(playState);
        }
        if (mCompleteView != null)
            mCompleteView.onPlayStateChanged(playState);
        if (mErrorView != null)
            mErrorView.onPlayStateChanged(playState);
        if (mTitleView != null)
            mTitleView.onPlayStateChanged(playState);
        if (mVodControlView != null)
            mVodControlView.onPlayStateChanged(playState);
        if (mLiveControlView != null)
            mLiveControlView.onPlayStateChanged(playState);
    }

    private void notifyComponentsPlayerStateChanged(int playerState) {
        if (mPrepareView != null)
            mPrepareView.onPlayerStateChanged(playerState);
        if (mCompleteView != null)
            mCompleteView.onPlayerStateChanged(playerState);
        if (mErrorView != null)
            mErrorView.onPlayerStateChanged(playerState);
        if (mTitleView != null)
            mTitleView.onPlayerStateChanged(playerState);
        if (mVodControlView != null)
            mVodControlView.onPlayerStateChanged(playerState);
        if (mLiveControlView != null)
            mLiveControlView.onPlayerStateChanged(playerState);
    }

    public void updateComponentsProgress(int duration, int position) {
        if (mVodControlView == null && mLiveControlView == null) {
            return;
        }

        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            final int finalDuration = duration;
            final int finalPosition = position;
            post(new Runnable() {
                @Override
                public void run() {
                    updateComponentsProgressInternal(finalDuration, finalPosition);
                }
            });
        } else {
            updateComponentsProgressInternal(duration, position);
        }
    }

    private void updateComponentsProgressInternal(int duration, int position) {
        // 调试日志：追踪进度更新和组件实例
        if (position % 5000 < 100) { // 每5秒打印一次，避免日志过多
            android.util.Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            android.util.Log.d(TAG, "updateComponentsProgress: 更新进度");
            android.util.Log.d(TAG, "  VideoView: @" + Integer.toHexString(this.hashCode()));
            android.util.Log.d(TAG, "  Duration: " + duration + "ms, Position: " + position + "ms");
            android.util.Log.d(TAG, "  Controller: "
                    + (mOrangeController != null ? "@" + Integer.toHexString(mOrangeController.hashCode()) : "null"));
            android.util.Log.d(TAG, "  VodControlView: "
                    + (mVodControlView != null ? "@" + Integer.toHexString(mVodControlView.hashCode()) : "null"));
            android.util.Log.d(TAG, "  LiveControlView: "
                    + (mLiveControlView != null ? "@" + Integer.toHexString(mLiveControlView.hashCode()) : "null"));
            android.util.Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        }

        // 通过控制器分发进度更新给所有控制组件（包括弹幕）
        if (mOrangeController != null) {
            try {
                mOrangeController.setProgress(duration, position);
            } catch (Exception e) {
            }
        }

        // 保留直接调用以兼容旧代码
        if (mVodControlView != null) {
            try {
                mVodControlView.setProgress(duration, position);
            } catch (Exception e) {
            }
        }

        if (mLiveControlView != null) {
            try {
                mLiveControlView.setProgress(duration, position);
            } catch (Exception e) {
            }
        }
    }

    public void setOnProgressListener(OnProgressListener listener) {
        this.mProgressListener = listener;
    }

    public void setOnPlayCompleteListener(OnPlayCompleteListener listener) {
        this.mPlayCompleteListener = listener;
    }

    public OrangeVideoController getVideoController() {
        return mOrangeController;
    }

    /**
     * 获取全屏辅助类
     */
    public CustomFullscreenHelper getFullscreenHelper() {
        return mFullscreenHelper;
    }

    /**
     * 在全屏切换前暂停 OCR 并切换到 SurfaceView 模式
     * 用于避免 TextureView 模式下屏幕旋转导致 MediaCodec 崩溃
     */
    private void pauseOcrForFullscreenSwitch() {
        if (mOrangeController == null) {
            return;
        }

        try {
            VideoEventManager eventManager = mOrangeController.getVideoEventManager();
            if (eventManager == null) {
                return;
            }

            // 检查是否需要拦截（OCR 运行中 + EXO/系统内核 + Android Q+）
            if (eventManager.shouldInterceptFullscreenForOcr()) {
                eventManager.pauseOcrForFullscreenSwitch();
            } else {
            }
        } catch (Exception e) {
        }
    }

    public void setVideoController(OrangeVideoController controller) {
        this.mOrangeController = controller;

        if (controller != null) {
            controller.setVideoView(this);
            
            // 设置 ControlWrapper，让控制器可以访问播放器
            controller.setControlWrapper(controller);

            if (mTitleView != null) {
                mTitleView.setController(controller);
            }

            if (mVodControlView != null) {
                mVodControlView.setOrangeVideoController(controller);
            }

            // 初始化嗅探组件
            initSniffingView(controller);

            ensureEventBinding();
        }
    }

    /**
     * 设置控制器可见性是否启用
     * 用于某些播放模式需要保留控制器功能但不显示UI
     * 
     * @param enabled true: 允许显示控制器(默认), false: 禁止显示控制器UI
     */
    public void setControllerVisibilityEnabled(boolean enabled) {
        if (mOrangeController != null) {
            mOrangeController.setControllerVisibilityEnabled(enabled);
        }

        // 立即触发显示或隐藏控制器
        if (enabled) {
            // 启用时立即显示控制器
            showController();
            android.util.Log.d("OrangevideoView", "setControllerVisibilityEnabled(true) - show controller");
        } else {
            // 禁用时立即隐藏控制器
            hideController();
            android.util.Log.d("OrangevideoView", "setControllerVisibilityEnabled(false) - hide controller");
        }
    }

    /**
     * 控制器可见性是否启用
     * 
     * @return true: 允许显示, false: 禁止显示
     */
    public boolean isControllerVisibilityEnabled() {
        if (mOrangeController != null) {
            return mOrangeController.isControllerVisibilityEnabled();
        }
        return true;
    }

    public void setAutoThumbnailEnabled(boolean enabled) {
        this.mAutoThumbnailEnabled = enabled;
    }

    public boolean isAutoThumbnailEnabled() {
        return mAutoThumbnailEnabled;
    }

    public void setDefaultThumbnail(Object thumbnail) {
        this.mDefaultThumbnail = thumbnail;
    }

    public Object getDefaultThumbnail() {
        return mDefaultThumbnail;
    }

    public void getVideoFirstFrameAsync(VideoThumbnailHelper.ThumbnailCallback callback) {
        if (mVideoUrl == null || mVideoUrl.isEmpty()) {
            if (callback != null) {
                callback.onError("Video URL is empty");
            }
            return;
        }
        VideoThumbnailHelper.getVideoFirstFrameAsync(mVideoUrl, mVideoHeaders, callback);
    }

    public void getFrameAtTimeAsync(long timeUs, VideoThumbnailHelper.ThumbnailCallback callback) {
        if (mVideoUrl == null || mVideoUrl.isEmpty()) {
            if (callback != null) {
                callback.onError("Video URL is empty");
            }
            return;
        }
        VideoThumbnailHelper.getFrameAtTimeAsync(mVideoUrl, timeUs, mVideoHeaders, callback);
    }

    public void setKeepVideoPlaying(boolean keep) {
        this.mKeepVideoPlaying = keep;
    }

    public boolean isKeepVideoPlaying() {
        return mKeepVideoPlaying;
    }

    public void savePlaybackProgress() {
        if (!mKeepVideoPlaying || mVideoUrl == null || mVideoUrl.isEmpty()) {
            return;
        }

        long position = getCurrentPosition();
        long duration = getDuration();

        if (position > 0 && duration > 0) {
            PlaybackProgressManager.getInstance(getContext())
                    .saveProgress(mVideoUrl, position, duration);
        }
    }

    public boolean restorePlaybackProgress() {
        if (!mKeepVideoPlaying || mVideoUrl == null || mVideoUrl.isEmpty()) {
            return false;
        }

        PlaybackProgressManager manager = PlaybackProgressManager.getInstance(getContext());
        long resumePosition = manager.getResumePosition(mVideoUrl, getContext());

        if (resumePosition > 0) {
            seekTo(resumePosition);
            return true;
        }
        return false;
    }

    public long getSavedProgress() {
        if (mVideoUrl == null || mVideoUrl.isEmpty()) {
            return 0;
        }
        return PlaybackProgressManager.getInstance(getContext()).getProgress(mVideoUrl);
    }

    public boolean hasSavedProgress() {
        if (mVideoUrl == null || mVideoUrl.isEmpty()) {
            return false;
        }
        return PlaybackProgressManager.getInstance(getContext()).hasProgress(mVideoUrl);
    }

    public void clearSavedProgress() {
        if (mVideoUrl == null || mVideoUrl.isEmpty()) {
            return;
        }
        PlaybackProgressManager.getInstance(getContext()).removeProgress(mVideoUrl);
    }

    // ==================== 播放历史功能 ====================

    /**
     * 启动播放历史自动保存
     */
    private void startPlayHistoryAutoSave() {
        if (mVideoUrl == null || mVideoUrl.isEmpty()) {
            return;
        }

        String title = "";
        if (mOrangeController != null) {
            title = mOrangeController.getVideoTitle();
        }
        final OrangevideoView self = this;
        PlayHistoryManager.getInstance(getContext()).startAutoSave(
                mVideoUrl,
                title,
                new PlayHistoryManager.ProgressProvider() {
                    @Override
                    public long getCurrentPosition() {
                        return self.getCurrentPositionWhenPlaying();
                    }

                    @Override
                    public long getDuration() {
                        return self.getDuration();
                    }

                    @Override
                    public View getVideoView() {
                        return self;
                    }
                });
    }

    /**
     * 停止播放历史自动保存
     */
    private void stopPlayHistoryAutoSave() {
        PlayHistoryManager.getInstance(getContext()).stopAutoSave();
    }

    /**
     * 获取播放历史中保存的进度
     */
    public long getHistoryProgress() {
        if (mVideoUrl == null || mVideoUrl.isEmpty()) {
            return 0;
        }
        return PlayHistoryManager.getInstance(getContext()).getProgress(mVideoUrl);
    }

    /**
     * 检查是否有播放历史
     */
    public boolean hasPlayHistory() {
        if (mVideoUrl == null || mVideoUrl.isEmpty()) {
            return false;
        }
        return PlayHistoryManager.getInstance(getContext()).getProgress(mVideoUrl) > 0;
    }

    public void setSkipIntroTime(long timeMs) {
        if (mSkipManager != null) {
            mSkipManager.setSkipIntroTime(timeMs);
        }
    }

    public void setSkipIntroSeconds(int seconds) {
        if (mSkipManager != null) {
            mSkipManager.setSkipIntroSeconds(seconds);
        }
    }

    public long getSkipIntroTime() {
        return mSkipManager != null ? mSkipManager.getSkipIntroTime() : 0;
    }

    public void setSkipIntroEnabled(boolean enabled) {
        if (mSkipManager != null) {
            mSkipManager.setSkipIntroEnabled(enabled);
        }
    }

    public boolean isSkipIntroEnabled() {
        return mSkipManager != null && mSkipManager.isSkipIntroEnabled();
    }

    public void setSkipOutroTime(long timeMs) {
        if (mSkipManager != null) {
            mSkipManager.setSkipOutroTime(timeMs);
        }
    }

    public void setSkipOutroSeconds(int seconds) {
        if (mSkipManager != null) {
            mSkipManager.setSkipOutroSeconds(seconds);
        }
    }

    public long getSkipOutroTime() {
        return mSkipManager != null ? mSkipManager.getSkipOutroTime() : 0;
    }

    public void setSkipOutroEnabled(boolean enabled) {
        if (mSkipManager != null) {
            mSkipManager.setSkipOutroEnabled(enabled);
        }
    }

    public boolean isSkipOutroEnabled() {
        return mSkipManager != null && mSkipManager.isSkipOutroEnabled();
    }

    public void setOnSkipListener(SkipManager.OnSkipListener listener) {
        if (mSkipManager != null) {
            mSkipManager.setOnSkipListener(listener);
        }
    }

    public SkipManager getSkipManager() {
        return mSkipManager;
    }

    public VideoScaleManager getVideoScaleManager() {
        return mVideoScaleManager;
    }

    public PlaybackStateManager getPlaybackStateManager() {
        return mPlaybackStateManager;
    }

    public ComponentStateManager getComponentStateManager() {
        return mComponentStateManager;
    }

    public ErrorRecoveryManager getErrorRecoveryManager() {
        return mErrorRecoveryManager;
    }

    public void refreshVideoShowType() {
        changeTextureViewShowType();
    }

    /**
     * 更新 SurfaceControl 尺寸（如果需要）
     * 用于视频比例切换后更新画面位置
     * 支持 ExoPlayer 和系统播放器
     */
    public void updateSurfaceControlIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return;
        }

        try {
            String currentEngine = PlayerSettingsManager.getInstance(getContext()).getPlayerEngine();
            boolean isExoPlayer = PlayerConstants.ENGINE_EXO.equals(currentEngine);
            boolean isSystemPlayer = PlayerConstants.ENGINE_DEFAULT.equals(currentEngine);

            if (!isExoPlayer && !isSystemPlayer) {
                return;
            }

            com.shuyu.gsyvideoplayer.player.IPlayerManager playerManager = GSYVideoManager.instance().getPlayer();

            if (mTextureView != null && mTextureView.getShowView() instanceof android.view.SurfaceView) {
                android.view.SurfaceView surfaceView = (android.view.SurfaceView) mTextureView.getShowView();

                if (isExoPlayer && playerManager instanceof com.orange.playerlibrary.exo.OrangeExoPlayerManager) {
                    com.orange.playerlibrary.exo.OrangeExoPlayerManager exoManager = (com.orange.playerlibrary.exo.OrangeExoPlayerManager) playerManager;
                    exoManager.updateSurfaceControlSize(surfaceView);
                } else if (isSystemPlayer
                        && playerManager instanceof com.orange.playerlibrary.player.OrangeSystemPlayerManager) {
                    com.orange.playerlibrary.player.OrangeSystemPlayerManager systemManager = (com.orange.playerlibrary.player.OrangeSystemPlayerManager) playerManager;
                    systemManager.updateSurfaceControlSize(surfaceView);
                }
            }
        } catch (Exception e) {
        }
    }

    /**
     * 更新 ExoPlayer 的 SurfaceControl 尺寸（如果需要）
     * 
     * @deprecated 使用 updateSurfaceControlIfNeeded() 代替
     */
    @Deprecated
    public void updateExoSurfaceControlIfNeeded() {
        updateSurfaceControlIfNeeded();
    }

    public boolean isLiveVideo() {
        return mIsLiveVideo;
    }

    public void setLiveVideo(boolean isLive) {
        this.mIsLiveVideo = isLive;
    }

    /**
     * 获取网络速度（字节/秒）
     * 使用 Android 系统 API 计算实时网速，因为 GSY 的 getNetSpeed 在某些播放器返回 0
     * 
     * @return 网络速度
     */
    public long getNetSpeed() {
        return mLoadingStateHelper.calculateSpeed();
    }

    /**
     * 获取网络速度（兼容旧 API）
     * 
     * @return 网络速度
     */
    public long getTcpSpeed() {
        return getNetSpeed();
    }

    /**
     * 获取格式化的网速文本
     * 
     * @return 网速文本，如 "1.5 MB/s"
     */
    public String getNetSpeedText() {
        long speed = getNetSpeed();
        return formatSpeed(speed);
    }

    /**
     * 格式化网速
     */
    private String formatSpeed(long speed) {
        if (speed <= 0)
            return "0 KB/s";

        final long KB = 1024;
        final long MB = KB * 1024;

        if (speed < KB) {
            return speed + " B/s";
        } else if (speed < MB) {
            float speedKB = speed / (float) KB;
            return String.format(speedKB >= 100 ? "%.0f KB/s" : "%.1f KB/s", speedKB);
        } else {
            float speedMB = speed / (float) MB;
            return speedMB >= 10 ? String.format("%.1f MB/s", speedMB) : String.format("%.2f MB/s", speedMB);
        }
    }

    public boolean isSniffing() {
        return mSniffingDelegate.isSniffing();
    }

    public void startSniffing() {
        if (mVideoUrl == null || mVideoUrl.isEmpty()) {
            return;
        }
        startSniffing(mVideoUrl, null);
    }

    public void startSniffing(String url, java.util.Map<String, String> headers) {
        mSniffingDelegate.startSniffing(url, headers);
    }

    public void stopSniffing() {
        mSniffingDelegate.stop();
        setOrangePlayState(STATE_ENDSNIFFING);
    }

    /**
     * 嗅探监听器接口
     * 注意：这是一个独立接口，不继承 OnStateChangeListener
     * 通过 addOnStateChangeListener 添加时，会在内部检查是否实现此接口
     */
    public interface OnSniffingListener extends OnStateChangeListener {
        void onSniffingReceived(String contentType, java.util.HashMap<String, String> headers,
                String title, String url);

        void onSniffingFinish(java.util.List<VideoSniffing.VideoInfo> videoList, int videoSize);
    }

    /**
     * 嗅探监听器适配器（提供默认空实现）
     */
    public static abstract class OnSniffingAdapter implements OnSniffingListener {
        @Override
        public void onPlayStateChanged(int playState) {
        }

        @Override
        public void onPlayerStateChanged(int playerState) {
        }
    }

    public void setDebug(boolean debug) {
        this.mDebug = debug;
    }

    public boolean isDebug() {
        return mDebug;
    }

    protected void debug(Object message) {
        if (mDebug) {
        }
    }

    public Activity getActivity() {
        Context context = getContext();
        if (context instanceof Activity) {
            return (Activity) context;
        }
        return null;
    }

    public boolean isPlaying() {
        return mCurrentPlayState == PlayerConstants.STATE_PLAYING;
    }

    public boolean isInNormalState() {
        return !isFullScreen() && !isTinyScreen();
    }

    private com.orange.playerlibrary.component.GestureView mGestureView;

    @Override
    protected void touchSurfaceMove(float deltaX, float deltaY, float y) {
        // 判断是否全屏
        boolean isFullscreen = mFullscreenHelper != null && mFullscreenHelper.isFullscreen();

        int curWidth;
        int curHeight;

        if (isFullscreen) {
            // 全屏横屏模式：使用 View 的实际尺寸
            curWidth = getWidth();
            curHeight = getHeight();
        } else {
            // 竖屏模式：使用屏幕尺寸（与基类保持一致）
            curWidth = mScreenWidth;
            curHeight = mScreenHeight;
        }

        if (mChangePosition) {
            long totalTimeDuration = getDuration();
            mSeekTimePosition = (int) (mDownPosition + (deltaX * totalTimeDuration / curWidth) / mSeekRatio);
            if (mSeekTimePosition < 0) {
                mSeekTimePosition = 0;
            }
            if (mSeekTimePosition > totalTimeDuration)
                mSeekTimePosition = totalTimeDuration;
            String seekTime = com.shuyu.gsyvideoplayer.utils.CommonUtil.stringForTime(mSeekTimePosition);
            String totalTime = com.shuyu.gsyvideoplayer.utils.CommonUtil.stringForTime(totalTimeDuration);
            showProgressDialog(deltaX, seekTime, mSeekTimePosition, totalTime, totalTimeDuration);
        } else if (mChangeVolume) {
            deltaY = -deltaY;
            // 直接获取 AudioManager，不依赖 GSY 的 mAudioFocusManager
            android.media.AudioManager audioManager = (android.media.AudioManager) getContext()
                    .getSystemService(android.content.Context.AUDIO_SERVICE);
            if (audioManager != null) {
                int max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                int deltaV = (int) (max * deltaY * 3 / curHeight);
                int newVolume = Math.max(0, Math.min(max, mGestureDownVolume + deltaV));
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newVolume, 0);
                int volumePercent = (int) (mGestureDownVolume * 100 / max + deltaY * 3 * 100 / curHeight);
                // 限制音量百分比在 0-100 之间
                volumePercent = Math.max(0, Math.min(100, volumePercent));
                showVolumeDialog(-deltaY, volumePercent);
            }
        } else if (mBrightness) {
            if (Math.abs(deltaY) > mThreshold) {
                float percent = (-deltaY / curHeight);
                onBrightnessSlide(percent);
                mDownY = y;
            }
        }
    }

    /**
     * 重写手势判断逻辑，修复全屏模式下音量手势不工作的问题
     * 
     * 问题原因：GSY 基类使用 CommonUtil.getCurrentScreenLand() 判断屏幕方向，
     * 但在自定义全屏模式下（CustomFullscreenHelper）这个判断可能不准确，
     * 导致 curWidth 计算错误，进而导致左右区域判断错误。
     * 
     * 解决方案：
     * - 全屏模式：使用 View 的实际宽度
     * - 竖屏模式：使用屏幕宽度（与基类保持一致）
     */
    @Override
    protected void touchSurfaceMoveFullLogic(float absDeltaX, float absDeltaY) {
        int curWidth;

        // 判断是否全屏
        boolean isFullscreen = mFullscreenHelper != null && mFullscreenHelper.isFullscreen();

        if (isFullscreen) {
            // 全屏横屏模式：使用 View 的实际宽度
            curWidth = getWidth();
        } else {
            // 竖屏模式：使用屏幕宽度（与基类保持一致）
            curWidth = mScreenWidth;
        }

        if (absDeltaX > mThreshold || absDeltaY > mThreshold) {
            cancelProgressTimer();
            if (absDeltaX >= mThreshold) {
                // 水平滑动 - 进度调节
                // 防止全屏虚拟按键区域误触
                int screenWidth = com.shuyu.gsyvideoplayer.utils.CommonUtil.getScreenWidth(getContext());
                if (Math.abs(screenWidth - mDownX) > mSeekEndOffset) {
                    mChangePosition = true;
                    mDownPosition = getCurrentPositionWhenPlaying();
                } else {
                    mShowVKey = true;
                }
            } else {
                // 垂直滑动 - 亮度/音量调节
                int screenHeight = com.shuyu.gsyvideoplayer.utils.CommonUtil.getScreenHeight(getContext());
                boolean noEnd = Math.abs(screenHeight - mDownY) > mSeekEndOffset;
                if (mFirstTouch) {
                    // 左半边 = 亮度，右半边 = 音量
                    mBrightness = (mDownX < curWidth * 0.5f) && noEnd;
                    mFirstTouch = false;
                }
                if (!mBrightness) {
                    mChangeVolume = noEnd;
                    // 直接获取 AudioManager，不依赖 GSY 的 mAudioFocusManager
                    android.media.AudioManager audioManager = (android.media.AudioManager) getContext()
                            .getSystemService(android.content.Context.AUDIO_SERVICE);
                    if (audioManager != null) {
                        mGestureDownVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                    }
                }
                mShowVKey = !noEnd;
            }
        }
    }

    @Override
    protected void showBrightnessDialog(float percent) {
        ensureGestureView();
        if (mGestureView != null) {
            mGestureView.onStartSlide();
            mGestureView.onBrightnessChange((int) (percent * 100));
        }
    }

    @Override
    protected void showVolumeDialog(float deltaY, int volumePercent) {
        ensureGestureView();
        if (mGestureView != null) {
            mGestureView.onStartSlide();
            mGestureView.onVolumeChange(volumePercent);
        }
    }

    @Override
    protected void showProgressDialog(float deltaX, String seekTime, long seekTimePosition, String totalTime,
            long totalTimeDuration) {
        ensureGestureView();
        if (mGestureView != null) {
            mGestureView.onStartSlide();
            mGestureView.onPositionChange((int) seekTimePosition, (int) getCurrentPosition(), (int) getDuration());
        }
    }

    @Override
    protected void dismissBrightnessDialog() {
        if (mGestureView != null) {
            mGestureView.onStopSlide();
        }
    }

    @Override
    protected void dismissVolumeDialog() {
        if (mGestureView != null) {
            mGestureView.onStopSlide();
        }
    }

    @Override
    protected void dismissProgressDialog() {
        if (mGestureView != null) {
            mGestureView.onStopSlide();
        }
    }

    private void ensureGestureView() {
        if (mGestureView == null) {
            mGestureView = new com.orange.playerlibrary.component.GestureView(getContext());
            android.widget.RelativeLayout.LayoutParams lp = new android.widget.RelativeLayout.LayoutParams(
                    android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                    android.widget.RelativeLayout.LayoutParams.MATCH_PARENT);
            addView(mGestureView, lp);
        }
        // 确保 GestureView 在最上层
        mGestureView.bringToFront();
    }

    public com.orange.playerlibrary.component.GestureView getGestureView() {
        ensureGestureView();
        return mGestureView;
    }

    public void setThisPlayState(int state) {
        setOrangePlayState(state);
    }

    public void setThisPlayerState(int state) {
        setOrangePlayerState(state);
    }

    @Override
    public int getLayoutId() {
        return R.layout.layout_orange_base_player;
    }

    @Override
    public int getSmallId() {
        return 0;
    }

    @Override
    public int getFullId() {
        return GSYVideoManager.FULLSCREEN_ID;
    }

    @Override
    public android.widget.ImageView getBackButton() {
        return null;
    }

    @SuppressWarnings("ResourceType")
    public OrangevideoView getOrangeFullWindowPlayer() {
        Activity activity = com.shuyu.gsyvideoplayer.utils.CommonUtil.scanForActivity(getContext());
        if (activity == null) {
            return null;
        }
        android.view.ViewGroup vp = (android.view.ViewGroup) activity
                .findViewById(android.view.Window.ID_ANDROID_CONTENT);
        final android.view.View full = vp.findViewById(getFullId());
        OrangevideoView orangeVideoView = null;
        if (full != null && full instanceof OrangevideoView) {
            orangeVideoView = (OrangevideoView) full;
        }
        return orangeVideoView;
    }

    @Override
    protected void checkoutState() {
        removeCallbacks(mOrangeCheckoutTask);
        mInnerHandler.postDelayed(mOrangeCheckoutTask, 500);
    }

    private Runnable mOrangeCheckoutTask=new Runnable(){@Override public void run(){OrangevideoView fullPlayer=getOrangeFullWindowPlayer();if(fullPlayer!=null&&fullPlayer.mCurrentState!=mCurrentState){if(fullPlayer.mCurrentState==CURRENT_STATE_PLAYING_BUFFERING_START&&mCurrentState!=CURRENT_STATE_PREPAREING){fullPlayer.setStateAndUi(mCurrentState);}}}};

    @Override
    @SuppressWarnings({ "ResourceType", "unchecked" })
    public GSYBaseVideoPlayer startWindowFullscreen(Context context, boolean actionBar, boolean statusBar) {
        // OCR 全屏切换处理：先暂停 OCR 并切换到 SurfaceView
        pauseOcrForFullscreenSwitch();

        hideStatusBarAndNavigation(context);

        if (mAutoRotateOnFullscreen) {
            Activity activity = getActivity();
            if (activity != null) {
                activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
        }

        GSYBaseVideoPlayer fullPlayer = super.startWindowFullscreen(context, true, true);

        if (fullPlayer instanceof OrangevideoView) {
            final OrangevideoView orangeFullPlayer = (OrangevideoView) fullPlayer;
            orangeFullPlayer.mIfCurrentIsFullscreen = true;

            orangeFullPlayer.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mTitleView != null && orangeFullPlayer.mTitleView != null) {
                        String title = mTitleView.getTitle();
                        orangeFullPlayer.mTitleView.setTitle(title);
                        if (mOrangeController != null) {
                            orangeFullPlayer.mTitleView.setController(mOrangeController);
                        }
                    }

                    if (mOrangeController != null && orangeFullPlayer.mVodControlView != null) {
                        com.orange.playerlibrary.VideoEventManager eventManager = mOrangeController
                                .getVideoEventManager();
                        if (eventManager != null) {
                            eventManager.bindControllerComponents(orangeFullPlayer.mVodControlView);
                        }
                    }

                    // 重新附加字幕视图到全屏播放器（解决全屏模式下字幕不显示的问题）
                    if (mOrangeController != null) {
                        mOrangeController.reattachSubtitleView(orangeFullPlayer);
                    }

                    orangeFullPlayer.setOrangePlayState(mCurrentPlayState);
                    orangeFullPlayer.setOrangePlayerState(PlayerConstants.PLAYER_FULL_SCREEN);

                    if (orangeFullPlayer.mComponentStateManager != null) {
                        orangeFullPlayer.mComponentStateManager.reregisterProgressListener(orangeFullPlayer);
                    }

                    // 先隐藏原始播放器的控制器
                    hideController();

                    // 强制刷新全屏播放器的控制器：先隐藏再显示
                    orangeFullPlayer.hideController();
                    orangeFullPlayer.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            orangeFullPlayer.showController();
                            if (orangeFullPlayer.mTitleView != null) {
                                orangeFullPlayer.mTitleView.setVisibility(android.view.View.VISIBLE);
                                orangeFullPlayer.mTitleView.bringToFront();
                            }
                            if (orangeFullPlayer.mVodControlView != null) {
                                orangeFullPlayer.mVodControlView.setVisibility(android.view.View.VISIBLE);
                                orangeFullPlayer.mVodControlView.bringToFront();
                                orangeFullPlayer.mVodControlView
                                        .onPlayerStateChanged(PlayerConstants.PLAYER_FULL_SCREEN);
                                // 强制刷新进度
                                if (mComponentStateManager != null) {
                                    int duration = (int) getDuration();
                                    int position = (int) getCurrentPositionWhenPlaying();
                                    orangeFullPlayer.mVodControlView.setProgress(duration, position);
                                }
                            }
                            orangeFullPlayer.requestLayout();
                        }
                    }, 100);
                }
            }, 300);
        }

        setOrangePlayerState(PlayerConstants.PLAYER_FULL_SCREEN);
        return fullPlayer;
    }

    private void hideStatusBarAndNavigation(Context context) {
        Activity activity = com.shuyu.gsyvideoplayer.utils.CommonUtil.scanForActivity(context);
        if (activity != null) {
            android.view.View decorView = activity.getWindow().getDecorView();
            int uiOptions = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            decorView.setSystemUiVisibility(uiOptions);

            if (activity.getActionBar() != null) {
                activity.getActionBar().hide();
            }
            if (activity instanceof androidx.appcompat.app.AppCompatActivity) {
                androidx.appcompat.app.ActionBar supportActionBar = ((androidx.appcompat.app.AppCompatActivity) activity)
                        .getSupportActionBar();
                if (supportActionBar != null) {
                    supportActionBar.hide();
                }
            }
        }
    }

    @Override
    @SuppressWarnings("ResourceType")
    protected void clearFullscreenLayout() {
        if (!mFullAnimEnd) {
            return;
        }

        final android.view.ViewGroup vp = getViewGroup();
        final android.view.View oldF = vp.findViewById(getFullId());
        if (oldF != null && oldF instanceof OrangevideoView) {
            OrangevideoView orangeVideoPlayer = (OrangevideoView) oldF;
            if (mPlaybackStateManager != null) {
                mPlaybackStateManager.saveState(orangeVideoPlayer);
            }
            orangeVideoPlayer.mIfCurrentIsFullscreen = false;
        }

        mIfCurrentIsFullscreen = false;
        int delay = 0;
        if (mOrientationUtils != null) {
            delay = mOrientationUtils.backToProtVideo();
            mOrientationUtils.setEnable(false);
            if (mOrientationUtils != null) {
                mOrientationUtils.releaseListener();
                mOrientationUtils = null;
            }
        }

        if (!mShowFullAnimation) {
            delay = 0;
        }

        mInnerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                orangeBackToNormal();
            }
        }, delay);
    }

    @SuppressWarnings("ResourceType")
    protected void orangeBackToNormal() {
        // OCR 全屏切换处理：先暂停 OCR 并切换到 SurfaceView
        pauseOcrForFullscreenSwitch();

        final android.view.ViewGroup vp = getViewGroup();
        final android.view.View oldF = vp.findViewById(getFullId());
        final OrangevideoView orangeVideoPlayer;

        if (oldF != null && oldF instanceof OrangevideoView) {
            orangeVideoPlayer = (OrangevideoView) oldF;
            if (mShowFullAnimation && mListItemRect != null && mListItemSize != null) {
                android.transition.TransitionManager.beginDelayedTransition(vp);
                android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) orangeVideoPlayer
                        .getLayoutParams();
                lp.setMargins(mListItemRect[0], mListItemRect[1], 0, 0);
                lp.width = mListItemSize[0];
                lp.height = mListItemSize[1];
                lp.gravity = android.view.Gravity.NO_GRAVITY;
                orangeVideoPlayer.setLayoutParams(lp);
                mInnerHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        orangeResolveNormalVideoShow(oldF, vp, orangeVideoPlayer);
                    }
                }, 400);
            } else {
                orangeResolveNormalVideoShow(oldF, vp, orangeVideoPlayer);
            }
        } else {
            orangeResolveNormalVideoShow(null, vp, null);
        }
    }

    protected void orangeResolveNormalVideoShow(android.view.View oldF, android.view.ViewGroup vp,
            OrangevideoView orangeVideoPlayer) {
        // 移除全屏 View
        if (oldF != null && oldF.getParent() != null) {
            android.view.ViewGroup viewGroup = (android.view.ViewGroup) oldF.getParent();
            vp.removeView(viewGroup);
        }

        // 恢复状态（与 GSY 基类保持一致）
        mCurrentState = getGSYVideoManager().getLastState();

        if (orangeVideoPlayer != null) {
            cloneParams(orangeVideoPlayer, this);
        }

        if (mCurrentState != CURRENT_STATE_NORMAL
                || mCurrentState != CURRENT_STATE_AUTO_COMPLETE) {
            createNetWorkState();
        }

        // 切换监听器（关键：让原始播放器接管）
        getGSYVideoManager().setListener(getGSYVideoManager().lastListener());
        getGSYVideoManager().setLastListener(null);
        setStateAndUi(mCurrentState);

        // 重新添加 TextureView（GSY 基类的标准做法）
        addTextureView();

        // 延迟恢复组件状态（不做 seekTo，避免 ExoPlayer 状态混乱）
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mComponentStateManager != null) {
                    mComponentStateManager.restoreComponentState(OrangevideoView.this);
                    mComponentStateManager.reregisterProgressListener(OrangevideoView.this);
                }

                notifyComponentsPlayStateChanged(mCurrentPlayState);
                notifyComponentsPlayerStateChanged(PlayerConstants.PLAYER_NORMAL);

                // 重新附加字幕视图到原始播放器（解决退出全屏后字幕不显示的问题）
                if (mOrangeController != null) {
                    mOrangeController.reattachSubtitleView(OrangevideoView.this);
                }

                // 强制刷新控制器：先隐藏再显示，确保使用正确的实例
                hideController();
                postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        showController();
                        if (mVodControlView != null) {
                            mVodControlView.setVisibility(android.view.View.VISIBLE);
                            mVodControlView.onPlayerStateChanged(PlayerConstants.PLAYER_NORMAL);
                            // 强制刷新进度
                            int duration = (int) getDuration();
                            int position = (int) getCurrentPositionWhenPlaying();
                            mVodControlView.setProgress(duration, position);
                        }
                        requestLayout();
                    }
                }, 100);
            }
        }, 300);

        mSaveChangeViewTIme = System.currentTimeMillis();
        if (mVideoAllCallBack != null) {
            mVideoAllCallBack.onQuitFullscreen(mOriginUrl, mTitle, this);
        }
        mIfCurrentIsFullscreen = false;
        if (mHideKey) {
            com.shuyu.gsyvideoplayer.utils.CommonUtil.showNavKey(mContext, mSystemUiVisibility);
        }
        com.shuyu.gsyvideoplayer.utils.CommonUtil.showSupportActionBar(mContext, mActionBar, mStatusBar);
        if (getFullscreenButton() != null) {
            getFullscreenButton().setImageResource(getEnlargeImageRes());
        }
        setOrangePlayerState(PlayerConstants.PLAYER_NORMAL);
    }

    @Override
    public GSYVideoManager getGSYVideoManager() {
        GSYVideoManager.instance().initContext(getContext().getApplicationContext());
        return GSYVideoManager.instance();
    }

    @Override
    public void releaseVideos() {
        GSYVideoManager.releaseAllVideos();
    }

    @Override
    public boolean backFromFull(Context context) {
        if (mIfCurrentIsFullscreen) {
            mIfCurrentIsFullscreen = false;
            setOrangePlayerState(PlayerConstants.PLAYER_NORMAL);
            if (context instanceof Activity) {
                ((Activity) context)
                        .setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
            return true;
        }
        return false;
    }

    @Override
    protected void showWifiDialog() {
        if (mPrepareView != null) {
            setOrangePlayState(8);
        }
    }

    /**
     * 重写 setStateAndUi 添加详细日志
     */
    @Override
    protected void setStateAndUi(int state) {
        // 记录进入 PREPARING 状态的时间
        if (state == CURRENT_STATE_PREPAREING && mCurrentState != CURRENT_STATE_PREPAREING) {
            mPreparingStartTime = System.currentTimeMillis();
        }

        super.setStateAndUi(state);

        // 关键修复：同步 GSY 状态到 Orange 状态
        // GSY 的 setStateAndUi 会改变 mCurrentState，但不会触发 Orange 组件的状态通知
        // 所以我们需要手动同步状态
        int orangeState = mapGSYStateToOrangeState(state);
        if (orangeState != -1 && orangeState != mCurrentPlayState) {
            setOrangePlayState(orangeState);
        }
    }

    /**
     * 将 GSY 状态映射到 Orange 状态
     */
    private int mapGSYStateToOrangeState(int gsyState) {
        switch (gsyState) {
            case CURRENT_STATE_NORMAL:
                return PlayerConstants.STATE_IDLE;
            case CURRENT_STATE_PREPAREING:
                return PlayerConstants.STATE_PREPARING;
            case CURRENT_STATE_PLAYING:
                return PlayerConstants.STATE_PLAYING;
            case CURRENT_STATE_PAUSE:
                return PlayerConstants.STATE_PAUSED;
            case CURRENT_STATE_AUTO_COMPLETE:
                return PlayerConstants.STATE_PLAYBACK_COMPLETED;
            case CURRENT_STATE_ERROR:
                return PlayerConstants.STATE_ERROR;
            case CURRENT_STATE_PLAYING_BUFFERING_START:
                return PlayerConstants.STATE_BUFFERING;
            default:
                return -1; // 不映射
        }
    }

    /**
     * 获取状态名称（用于日志）
     */
    private String getStateName(int state) {
        switch (state) {
            case CURRENT_STATE_NORMAL:
                return "NORMAL";
            case CURRENT_STATE_PREPAREING:
                return "PREPARING";
            case CURRENT_STATE_PLAYING:
                return "PLAYING";
            case CURRENT_STATE_PLAYING_BUFFERING_START:
                return "BUFFERING_START";
            case CURRENT_STATE_PAUSE:
                return "PAUSE";
            case CURRENT_STATE_AUTO_COMPLETE:
                return "COMPLETE";
            case CURRENT_STATE_ERROR:
                return "ERROR";
            default:
                return "UNKNOWN_" + state;
        }
    }

    @Override
    protected void changeUiToNormal() {
        //android.util.Log.d(TAG, "changeUiToNormal: 隐藏加载动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, INVISIBLE);
        stopSpeedUpdate();
    }

    @Override
    protected void changeUiToPreparingShow() {
        // 视频加载时显示加载动画
        //android.util.Log.d(TAG, "changeUiToPreparingShow: 显示加载动画, state=" + mCurrentState + ", url=" + mOriginUrl);
        setViewShowState(mLoadingProgressBar, VISIBLE);
        startSpeedUpdate();
    }

    @Override
    protected void changeUiToPlayingShow() {
        //android.util.Log.d(TAG, "changeUiToPlayingShow: 隐藏加载动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, INVISIBLE);
        // 不停止网速更新，让它持续运行，updateLoadingSpeed 会根据 loading 可见性决定是否显示
    }

    @Override
    protected void changeUiToPlayingBufferingShow() {
        android.util.Log.d(TAG, "changeUiToPlayingBufferingShow: 显示缓冲动画, state=" + mCurrentState + ", bufferPercent="
                + getBuffterPoint());
        setViewShowState(mLoadingProgressBar, VISIBLE);
        startSpeedUpdate();
    }

    @Override
    protected void changeUiToPauseShow() {
        android.util.Log.d(TAG, "changeUiToPauseShow: 隐藏加载动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, INVISIBLE);
        stopSpeedUpdate();
    }

    @Override
    protected void changeUiToError() {
        android.util.Log.e(TAG, "changeUiToError: 隐藏加载动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, INVISIBLE);
        stopSpeedUpdate();
    }

    @Override
    protected void changeUiToCompleteShow() {
        android.util.Log.d(TAG, "changeUiToCompleteShow: 隐藏加载动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, INVISIBLE);
        stopSpeedUpdate();
    }

    protected void changeUiToPrepareingClear() {
        android.util.Log.d(TAG, "changeUiToPrepareingClear: 隐藏加载动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, INVISIBLE);
        stopSpeedUpdate();
    }

    protected void changeUiToPlayingClear() {
        android.util.Log.d(TAG, "changeUiToPlayingClear: 隐藏加载动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, INVISIBLE);
        stopSpeedUpdate();
    }

    protected void changeUiToPlayingBufferingClear() {
        android.util.Log.d(TAG, "changeUiToPlayingBufferingClear: 隐藏缓冲动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, INVISIBLE);
        stopSpeedUpdate();
    }

    protected void changeUiToPauseClear() {
        android.util.Log.d(TAG, "changeUiToPauseClear: 隐藏加载动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, INVISIBLE);
        stopSpeedUpdate();
    }

    protected void changeUiToCompleteClear() {
        android.util.Log.d(TAG, "changeUiToCompleteClear: 隐藏加载动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, INVISIBLE);
        stopSpeedUpdate();
    }

    /**
     * 嗅探开始 - 显示加载动画
     */
    protected void changeUiToSniffingShow() {
        android.util.Log.d(TAG, "changeUiToSniffingShow: 显示嗅探加载动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, VISIBLE);
        startSpeedUpdate();
    }

    /**
     * 嗅探结束 - 隐藏加载动画
     */
    protected void changeUiToSniffingEnd() {
        android.util.Log.d(TAG, "changeUiToSniffingEnd: 隐藏嗅探加载动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, INVISIBLE);
        stopSpeedUpdate();
    }

    /**
     * M3U8去广告开始 - 显示加载动画
     */
    protected void changeUiToM3U8AdRemovalShow() {
        android.util.Log.d(TAG, "changeUiToM3U8AdRemovalShow: 显示M3U8去广告加载动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, VISIBLE);
        startSpeedUpdate();
    }

    /**
     * M3U8去广告结束 - 隐藏加载动画
     */
    protected void changeUiToM3U8AdRemovalEnd() {
        android.util.Log.d(TAG, "changeUiToM3U8AdRemovalEnd: 隐藏M3U8去广告加载动画, state=" + mCurrentState);
        setViewShowState(mLoadingProgressBar, VISIBLE);
        startSpeedUpdate();
    }

    @Override
    protected void hideAllWidget() {
        android.util.Log.d(TAG, "hideAllWidget: 隐藏所有组件包括加载动画, state=" + mCurrentState);

        // 在 PREPARING 和 BUFFERING 状态下，不隐藏加载动画
        // 这些状态下用户需要看到加载进度
        if (mCurrentState == CURRENT_STATE_PREPAREING ||
                mCurrentState == CURRENT_STATE_PLAYING_BUFFERING_START) {
            android.util.Log.d(TAG, "hideAllWidget: 跳过隐藏加载动画（正在加载/缓冲）");
            return;
        }

        setViewShowState(mLoadingProgressBar, INVISIBLE);
        stopSpeedUpdate();
    }

    /**
     * 开始网速更新
     */
    private void startSpeedUpdate() {
        if (!mIsShowingLoading) {
            mIsShowingLoading = true;
            // 查找网速文本视图
            if (mLoadingSpeedText == null && mLoadingProgressBar != null) {
                if (mLoadingProgressBar instanceof android.view.ViewGroup) {
                    mLoadingSpeedText = ((android.view.ViewGroup) mLoadingProgressBar)
                            .findViewById(R.id.tv_loading_speed);
                } else {
                    android.view.ViewParent parent = mLoadingProgressBar.getParent();
                    if (parent instanceof android.view.ViewGroup) {
                        mLoadingSpeedText = ((android.view.ViewGroup) parent).findViewById(R.id.tv_loading_speed);
                    }
                }
            }
            if (mLoadingSpeedText != null) {
                mLoadingSpeedText.setVisibility(VISIBLE);
            }
            mLoadingStateHelper.start(mSpeedUpdateRunnable);
        }
    }

    /**
     * 停止网速更新
     */
    private void stopSpeedUpdate() {
        if (mIsShowingLoading) {
            mIsShowingLoading = false;
            mLoadingStateHelper.stop(mSpeedUpdateRunnable);
            if (mLoadingSpeedText != null) {
                mLoadingSpeedText.setVisibility(GONE);
            }
        }
    }

    /**
     * 更新网速显示
     * 只在网速大于 1 KB/s 且正在缓冲时显示
     */
    private void updateLoadingSpeed() {
        if (mLoadingSpeedText != null && mIsShowingLoading) {
            String customText = mLoadingStateHelper.getCustomText();
            if (customText != null) {
                mLoadingSpeedText.setText(customText);
                mLoadingSpeedText.setVisibility(VISIBLE);
                return;
            }

            long speed = getNetSpeed();

            // 限制最大显示速度为 100 MB/s，避免异常值
            if (speed > 100 * 1024 * 1024) {
                speed = 100 * 1024 * 1024;
            }

            // 只在网速大于 1 KB/s (1024 字节) 时显示
            if (speed > 1024) {
                String speedText = formatSpeed(speed);
                mLoadingSpeedText.setText(speedText);
                mLoadingSpeedText.setVisibility(VISIBLE);
            } else {
                mLoadingSpeedText.setText("");
                mLoadingSpeedText.setVisibility(GONE);
            }
        }
    }

    /**
     * 设置自定义加载文本（用于磁力链接解析等场景）
     * 
     * @param text 自定义文本，null 表示恢复显示网速
     */
    public void setCustomLoadingText(String text) {
        mLoadingStateHelper.setCustomText(text);
        if (mIsShowingLoading) {
            updateLoadingSpeed();
        }
    }

    /**
     * 设置加载动画指示器
     * 
     * @param indicator 指示器
     */
    public void setLoadingIndicator(com.orange.playerlibrary.loading.Indicator indicator) {
        if (mLoadingProgressBar != null) {
            com.orange.playerlibrary.loading.AVLoadingIndicatorView loadingView = null;
            if (mLoadingProgressBar instanceof android.view.ViewGroup) {
                loadingView = ((android.view.ViewGroup) mLoadingProgressBar).findViewById(R.id.loading_indicator);
            } else if (mLoadingProgressBar.getParent() instanceof android.view.ViewGroup) {
                android.view.ViewGroup parent = (android.view.ViewGroup) mLoadingProgressBar.getParent();
                loadingView = parent.findViewById(R.id.loading_indicator);
            }
            if (loadingView != null) {
                loadingView.setIndicator(indicator);
            }
        }
    }

    private static final int AUTO_HIDE_DELAY = 4000;
    private Runnable mAutoHideRunnable;

    @Override
    protected void onClickUiToggle(android.view.MotionEvent e) {
        // 检查控制器可见性是否被禁用
        if (!isControllerVisibilityEnabled()) {
            android.util.Log.d("OrangevideoView", "onClickUiToggle - controller visibility disabled, ignore click");
            return;
        }

        if (mCurrentPlayState != PlayerConstants.STATE_PLAYING &&
                mCurrentPlayState != PlayerConstants.STATE_PAUSED &&
                mCurrentPlayState != PlayerConstants.STATE_BUFFERING &&
                mCurrentPlayState != PlayerConstants.STATE_BUFFERED) {
            return;
        }

        boolean isShowing = isControllerShowing();
        if (isShowing) {
            hideController();
        } else {
            showController();
        }
    }

    public void showController() {
        // 检查控制器可见性是否被禁用
        if (!isControllerVisibilityEnabled()) {
            android.util.Log.d("OrangevideoView", "showController - controller visibility disabled, skip show");
            return;
        }

        // 检查锁定状态
        boolean isLocked = mOrangeController != null && mOrangeController.isLocked();
        if (mVodControlView != null) {
            if (isLocked) {
                // 锁定状态下只显示锁定按钮
                mVodControlView.onLockVisibilityChanged(true);
            } else {
                mVodControlView.setVisibility(android.view.View.VISIBLE);
            }
        }
        // 锁定状态下不显示标题栏
        if (mTitleView != null && !isLocked
                && (mIfCurrentIsFullscreen || mCurrentPlayerState == PlayerConstants.PLAYER_FULL_SCREEN)) {
            mTitleView.setVisibility(android.view.View.VISIBLE);
        }
        startAutoHideTimer();

        // 同步 Controller 的显示状态
        if (mOrangeController != null) {
            mOrangeController.setShowing(true);
        }
    }

    public void hideController() {
        // 检查是否正在拖动进度条，如果是则不隐藏
        if (mVodControlView != null && mVodControlView.isDragging()) {
            android.util.Log.d("OrangevideoView", "hideController() - VodControlView is dragging, skip hide");
            return;
        }

        // 检查锁定状态
        boolean isLocked = mOrangeController != null && mOrangeController.isLocked();
        if (mVodControlView != null) {
            if (isLocked) {
                // 锁定状态下只隐藏锁定按钮
                mVodControlView.onLockVisibilityChanged(false);
            } else {
                mVodControlView.setVisibility(android.view.View.GONE);
            }
        }
        if (mTitleView != null) {
            mTitleView.setVisibility(android.view.View.GONE);
        }
        cancelAutoHideTimer();

        // 同步 Controller 的显示状态
        if (mOrangeController != null) {
            mOrangeController.setShowing(false);
        }
    }

    /**
     * 锁定状态变化时调用，通知其他组件（不包括 VodControlView，它自己处理）
     * 
     * @param locked 是否锁定
     */
    public void onLockStateChanged(boolean locked) {
        // 更新 OrangeController 的锁定状态
        if (mOrangeController != null) {
            mOrangeController.setLockedInternal(locked);
        }

        // 只更新 TitleView，VodControlView 自己处理自己的 UI
        if (locked) {
            // 锁定时隐藏标题栏
            if (mTitleView != null) {
                mTitleView.setVisibility(android.view.View.GONE);
            }
        } else {
            // 解锁时显示标题栏（如果在全屏模式）
            if (mTitleView != null && isIfCurrentIsFullscreen()) {
                mTitleView.setVisibility(android.view.View.VISIBLE);
            }
        }
    }

    /**
     * 设置手势和自动旋转的锁定状态
     * 锁定时禁用手势和自动旋转，解锁时恢复设置中的自动旋转状态
     * 
     * @param locked 是否锁定
     */
    public void setGestureAndRotationLocked(boolean locked) {
        if (locked) {
            // 锁定时禁用手势
            setIsTouchWiget(false);
            setIsTouchWigetFull(false);

            // 锁定时禁用自动旋转（通过 CustomFullscreenHelper）
            if (mFullscreenHelper != null) {
                mFullscreenHelper.setAutoRotateEnabled(false);
            }
        } else {
            // 解锁时恢复手势
            setIsTouchWiget(true);
            setIsTouchWigetFull(true);

            // 解锁时恢复设置中的自动旋转状态
            PlayerSettingsManager settingsManager = PlayerSettingsManager.getInstance(getContext());
            boolean autoRotateEnabled = settingsManager.isAutoRotateEnabled();
            if (mFullscreenHelper != null) {
                mFullscreenHelper.setAutoRotateEnabled(autoRotateEnabled);
            }
        }
    }

    public boolean isControllerShowing() {
        // 锁定状态下，检查锁定按钮是否可见
        boolean isLocked = mOrangeController != null && mOrangeController.isLocked();
        if (isLocked && mVodControlView != null) {
            return mVodControlView.isLockButtonVisible();
        }
        return mVodControlView != null && mVodControlView.getVisibility() == android.view.View.VISIBLE;
    }

    private Runnable getAutoHideRunnable() {
        if (mAutoHideRunnable == null) {
            mAutoHideRunnable = new Runnable() {
                @Override
                public void run() {
                    hideController();
                }
            };
        }
        return mAutoHideRunnable;
    }

    private void startAutoHideTimer() {
        cancelAutoHideTimer();
        if (mCurrentPlayState == PlayerConstants.STATE_PLAYING && mInnerHandler != null) {
            mInnerHandler.postDelayed(getAutoHideRunnable(), AUTO_HIDE_DELAY);
        }
    }

    private void cancelAutoHideTimer() {
        if (mInnerHandler != null && mAutoHideRunnable != null) {
            mInnerHandler.removeCallbacks(mAutoHideRunnable);
        }
    }

    // 双击事件时间戳，用于防止双击后的单击事件干扰
    private static long sLastDoubleClickTime = 0;
    private static final long DOUBLE_CLICK_BLOCK_INTERVAL = 600; // 双击后600ms内阻止单击

    public static long getLastDoubleClickTime() {
        return sLastDoubleClickTime;
    }

    public static long getDoubleClickBlockInterval() {
        return DOUBLE_CLICK_BLOCK_INTERVAL;
    }

    @Override
    protected void touchDoubleUp(android.view.MotionEvent e) {
        // 检查控制器可见性是否被禁用
        if (!isControllerVisibilityEnabled()) {
            android.util.Log.d("OrangevideoView", "touchDoubleUp - controller visibility disabled, ignore double tap");
            return;
        }

        sLastDoubleClickTime = System.currentTimeMillis();
        if (mCurrentPlayState == PlayerConstants.STATE_PLAYING ||
                mCurrentPlayState == PlayerConstants.STATE_BUFFERING ||
                mCurrentPlayState == PlayerConstants.STATE_BUFFERED) {
            pause();
        } else if (mCurrentPlayState == PlayerConstants.STATE_PAUSED) {
            resume();
        }
    }

    // ===== 长按倍速功能 =====
    private boolean mIsLongPressing = false;
    private float mNormalSpeedBeforeLongPress = 1.0f;

    @Override
    protected void touchLongPress(android.view.MotionEvent e) {
        // 只在播放状态下响应长按
        if (!isPlaying()) {
            return;
        }

        mIsLongPressing = true;
        mNormalSpeedBeforeLongPress = getSpeed();

        // 从设置中获取长按倍速
        float longPressSpeed = PlayerSettingsManager.getInstance(getContext()).getLongPressSpeed();

        // IJK 内核限制：如果设置的长按倍速 > 2.0x，则限制为 2.0x
        String currentEngine = PlayerSettingsManager.getInstance(getContext()).getPlayerEngine();
        if (PlayerConstants.ENGINE_IJK.equals(currentEngine) && longPressSpeed > 2.0f) {
            longPressSpeed = 2.0f;
        }

        setSpeed(longPressSpeed);

        // 使用 GestureView 显示提示
        showLongPressSpeedHint(longPressSpeed, true);
    }

    @Override
    public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
        // 处理长按结束
        if (event.getAction() == android.view.MotionEvent.ACTION_UP ||
                event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
            if (mIsLongPressing) {
                mIsLongPressing = false;
                setSpeed(mNormalSpeedBeforeLongPress);
                // 使用 GestureView 显示提示
                showLongPressSpeedHint(mNormalSpeedBeforeLongPress, false);
            }
        }
        return super.onTouch(v, event);
    }

    /**
     * 显示长按倍速提示
     * 
     * @param speed       当前速度
     * @param isLongPress true=长按加速中，false=恢复正常
     */
    private void showLongPressSpeedHint(float speed, boolean isLongPress) {
        ensureGestureView();
        if (mGestureView != null) {
            mGestureView.onStartSlide();
            mGestureView.showSpeedHint(speed, isLongPress);
            // 延迟隐藏
            mGestureView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mGestureView != null) {
                        mGestureView.onStopSlide();
                    }
                }
            }, isLongPress ? 800 : 500);
        }
    }

    @Override
    public void startPlayLogic() {
        if (mAdState.isPendingAdRemoval()) {
            android.util.Log.d(TAG, "startPlayLogic: skipped due to pending m3u8 ad removal");
            return;
        }

        // 如果正在异步加载种子，跳过（等待回调触发）
        if (mTorrentDelegate.isPendingLoad()) {
            android.util.Log.d(TAG, "startPlayLogic: skipped due to pending torrent load");
            return;
        }
        // 检查 PrepareView 是否已附加到窗口
        // 使用兼容方法支持 API 16+
        if (mPrepareView != null && !isViewAttachedToWindow(mPrepareView)) {
            // 延迟到下一帧，等待组件附加到窗口
            post(new Runnable() {
                @Override
                public void run() {
                    startPlayLogic();
                }
            });
            return;
        }

        String targetUrl = mOriginUrl != null ? mOriginUrl : mVideoUrl;
        Map<String, String> headers = null;
        if (mMapHeadData != null && !mMapHeadData.isEmpty()) {
            headers = new HashMap<>(mMapHeadData);
        } else if (mVideoHeaders != null && !mVideoHeaders.isEmpty()) {
            headers = new HashMap<>(mVideoHeaders);
        }

        if (shouldProcessM3U8WithAdRemoval(targetUrl)) {
            processM3U8WithAdRemoval(targetUrl, mCache, mTitle, headers);
            return;
        }

        mAdState.setBypassOnce(false);

        // 在这里设置 STATE_PREPARING，确保 PrepareView 已附加到窗口
        setOrangePlayState(PlayerConstants.STATE_PREPARING);

        // 添加日志显示当前播放核心
        String currentEngine = PlayerSettingsManager.getInstance(getContext()).getPlayerEngine();
        com.shuyu.gsyvideoplayer.player.IPlayerManager playerManager = getGSYVideoManager().getPlayer();
        String playerClass = playerManager != null ? playerManager.getClass().getSimpleName() : "null";
        // ExoPlayer 使用 SurfaceControl 处理全屏切换 (Android Q+)
        if (PlayerConstants.ENGINE_EXO.equals(currentEngine) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            initExoSurfaceControl();
        } else {
            // 非 ExoPlayer，确保释放之前的 SurfaceControl
            releaseExoSurfaceControl();
        }

        prepareVideo();
    }


    @Override
    protected void prepareVideo() {
        // 添加日志
        String currentEngine = PlayerSettingsManager.getInstance(getContext()).getPlayerEngine();
        super.prepareVideo();
    }

    @Override
    public void startAfterPrepared() {
        super.startAfterPrepared();
        setSpeed(sSpeed);
    }

    // ==================== 首帧预览功能 ====================

    /**
     * 异步获取视频首帧作为封面
     * 使用 MediaMetadataRetriever（IJK/系统播放器自带支持）
     */
    private void getVideoFirstFrameAsync(final String videoUrl) {
        // 检查是否启用自动缩略图
        if (!mAutoThumbnailEnabled) {
            return;
        }

        // 如果已设置默认缩略图，直接使用
        if (mDefaultThumbnail != null) {
            if (mOrangeController != null) {
                mOrangeController.setThumbnail(mDefaultThumbnail);
            }
            return;
        }

        // 避免重复加载
        if (mIsLoadingThumbnail) {
            return;
        }
        mIsLoadingThumbnail = true;

        // 使用AsyncTask异步获取首帧
        new android.os.AsyncTask<Void, Void, android.graphics.Bitmap>() {
            @Override
            protected android.graphics.Bitmap doInBackground(Void... voids) {
                android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                try {
                    if (videoUrl.startsWith("http://") || videoUrl.startsWith("https://")) {
                        retriever.setDataSource(videoUrl, new java.util.HashMap<>());
                    } else {
                        retriever.setDataSource(videoUrl);
                    }
                    // 获取1秒处的帧（避免黑屏）
                    android.graphics.Bitmap bitmap = retriever.getFrameAtTime(
                            1000000, // 1秒 = 1000000微秒
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    retriever.release();
                    return bitmap;
                } catch (Exception e) {
                    try {
                        retriever.release();
                    } catch (Exception ignored) {
                    }
                    return null;
                }
            }

            @Override
            protected void onPostExecute(android.graphics.Bitmap bitmap) {
                mIsLoadingThumbnail = false;
                if (bitmap != null && mOrangeController != null) {
                    mOrangeController.setThumbnail(bitmap);
                } else if (mDefaultThumbnail != null && mOrangeController != null) {
                    // 首帧获取失败，使用默认缩略图
                    mOrangeController.setThumbnail(mDefaultThumbnail);
                }
            }
        }.execute();
    }

    /**
     * 检查用户是否主动暂停
     */
    public boolean isUserPaused() {
        return mUserPaused;
    }

    /**
     * 清除用户暂停状态（用于外部控制恢复播放）
     */
    public void clearUserPausedState() {
        mUserPaused = false;
    }

    /**
     * 初始化嗅探视图组件
     */
    private void initSniffingView(OrangeVideoController controller) {
        if (controller == null) {
            return;
        }

        // 创建嗅探视图
        com.orange.playerlibrary.component.SniffingView sniffingView = new com.orange.playerlibrary.component.SniffingView(
                getContext());

        // 设置到控制器
        controller.setSniffingView(sniffingView);

        // 添加到播放器容器
        addView(sniffingView);

        // 设置视频选择监听器
        sniffingView.setOnVideoSelectedListener(
                new com.orange.playerlibrary.component.SniffingView.OnVideoSelectedListener() {
                    @Override
                    public void onVideoSelected(VideoSniffing.VideoInfo videoInfo) {
                        // 如果正在嗅探，立即停止
                        if (mSniffingDelegate.isSniffing()) {
                            stopSniffing();
                        }

                        // 播放选中的视频
                        String url = videoInfo.url;
                        String title = videoInfo.title;
                        if (title == null || title.isEmpty()) {
                            title = "嗅探视频";
                        }

                        // 释放旧的播放器
                        release();

                        // 设置新视频
                        setUp(url, false, title);

                        // 设置请求头
                        if (videoInfo.headers != null && !videoInfo.headers.isEmpty()) {
                            setMapHeadData(videoInfo.headers);
                        }

                        // 开始播放
                        post(new Runnable() {
                            @Override
                            public void run() {
                                startPlayLogic();
                            }
                        });

                        // 更新标题
                        if (getTitleView() != null) {
                            getTitleView().setTitle(title);
                        }
                    }
                });

        // 绑定 TitleView 的嗅探按钮
        if (mTitleView != null) {
            mTitleView.setOnSniffingClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (sniffingView != null) {
                        if (sniffingView.isShowing()) {
                            sniffingView.hide();
                        } else {
                            sniffingView.show();
                        }
                    }
                }
            });

            // 初始化时更新嗅探按钮状态
            mTitleView.updateSniffingButton(sniffingView.getResultCount() > 0);
        }
    }

    // ===== M3U8去广告TS白名单API =====

    /**
     * 添加TS片段到白名单
     * 白名单中的片段不会被当作广告过滤
     * 对当前播放器实例生效，重启失效
     * 
     * @param tsUrl TS片段的完整URL或URL关键字（包含该关键字的URL都不会被过滤）
     */
    public void addTsToWhitelist(String tsUrl) {
        if (mM3U8AdManager != null) {
            mM3U8AdManager.addTsToWhitelist(tsUrl);
        }
    }

    /**
     * 批量添加TS片段到白名单
     * 
     * @param tsUrls TS片段URL列表
     */
    public void addTsToWhitelist(java.util.List<String> tsUrls) {
        if (mM3U8AdManager != null) {
            mM3U8AdManager.addTsToWhitelist(tsUrls);
        }
    }

    /**
     * 从白名单移除TS片段
     * 
     * @param tsUrl 要移除的URL
     */
    public void removeTsFromWhitelist(String tsUrl) {
        if (mM3U8AdManager != null) {
            mM3U8AdManager.removeTsFromWhitelist(tsUrl);
        }
    }

    /**
     * 清空TS白名单
     */
    public void clearTsWhitelist() {
        if (mM3U8AdManager != null) {
            mM3U8AdManager.clearTsWhitelist();
        }
    }

    /**
     * 检查URL是否在白名单中
     * 
     * @param tsUrl 要检查的URL
     * @return 是否在白名单中
     */
    public boolean isTsInWhitelist(String tsUrl) {
        if (mM3U8AdManager != null) {
            return mM3U8AdManager.isTsInWhitelist(tsUrl);
        }
        return false;
    }

    /**
     * 获取TS白名单大小
     */
    public int getTsWhitelistSize() {
        if (mM3U8AdManager != null) {
            return mM3U8AdManager.getTsWhitelistSize();
        }
        return 0;
    }
}
