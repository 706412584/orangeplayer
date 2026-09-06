package com.orange.playerlibrary;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.orange.playerlibrary.component.VodControlView;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * 视频事件管理
 * 负责处理播放器UI的各种点击事件和功能
 */
public class VideoEventManager {
    
    private static final String TAG = "VideoEventManager";
    private static final int COLOR_HIGHLIGHT = Color.parseColor("#FF6B35");
    private static final int COLOR_NORMAL = Color.parseColor("#B3B3B3");
    
    // 字幕文件选择请求码
    public static final int REQUEST_CODE_SUBTITLE_FILE = 10086;
    
    private final Context mContext;
    private final OrangevideoView mVideoView;
    private final Activity mActivity;
    private final OrangeVideoController mController;
    private final PlayerSettingsManager mSettingsManager;
    private final OrangeSharedSqlite mSqlite;
    
    // 组件引用
    private VodControlView mVodControlView;
    
    // 对话框引
    private AlertDialog mCurrentSetupDialog;
    
    // 长按倍速相关（默认 3.0x，最高 3.0x）
    private float mLongPressSpeed = 3.0f;
    private float mNormalSpeed = 1.0f;
    private boolean mIsLongPressing = false;
    
    // ===== 临时设置（不持久化，每次播放重置）=====
    private int mCurrentSkipOpening = 0;  // 跳过片头（毫秒）
    private int mCurrentSkipEnding = 0;   // 跳过片尾（毫秒）
    private float mCurrentLongPressSpeed = 2.0f;  // 长按倍速
    private String mCurrentScreenScale = "默认";  // 画面比例
    private String mCurrentVideoUrl = null;  // 当前视频 URL（用于判断是否切换视频）
    private int mCurrentVideoListHash = 0;  // 当前剧集列表hash（用于判断是否切换剧集）
    
    // OCR 全屏切换相关
    private boolean mOcrPausedForFullscreen = false;
    private String mOcrSourceLang = "chi_sim";
    private String mOcrTargetLang = "en";
    
    public VideoEventManager(Context context, OrangevideoView videoView, OrangeVideoController controller) {
        mContext = context;
        mVideoView = videoView;
        mController = controller;
        mActivity = (Activity) context;
        mSettingsManager = PlayerSettingsManager.getInstance(context);
        mSqlite = OrangevideoView.sqlite;
        
        // 从设置中读取长按倍
        mLongPressSpeed = mSettingsManager.getLongPressSpeed();
        
        // 片头尾设置不持久化，初始化为0
        mCurrentSkipOpening = 0;
        mCurrentSkipEnding = 0;
        
        // 注册播放器引擎变更监听器（用于更新UI）
        mSettingsManager.setEngineChangeListener(newEngine -> {
            updateEngineButtonsUI(newEngine);
        });
        
        // 绑定基础事件
        bindEvents();
        
        // 注册播放器状态监听器（用于处理 OCR 全屏切换）
        registerPlayerStateListener();
    }
    
    /**
     * 注册播放器状态监听器
     * 用于在全屏切换时暂停/恢复 OCR
     */
    private void registerPlayerStateListener() {
        if (mVideoView != null) {
            mVideoView.addOnStateChangeListener(new com.orange.playerlibrary.interfaces.OnStateChangeListener() {
                @Override
                public void onPlayerStateChanged(int playerState) {
                    handlePlayerStateChangedForOcr(playerState);
                }
                
                @Override
                public void onPlayStateChanged(int playState) {
                    // 不需要处理
                }
            });
        }
    }
    
    /**
     * 处理播放器状态变化（用于 OCR 全屏切换）
     * 
     * 问题：TextureView 模式下全屏切换（屏幕旋转）会导致 MediaCodec 崩溃
    /**
     * 处理播放器状态变化（用于 OCR 全屏切换）
     * 
     * 注意：由于 MediaCodecTexture 修复，OCR 现在可以在任何渲染模式下工作
     * 不再需要在全屏切换时暂停/恢复 OCR
     */
    private void handlePlayerStateChangedForOcr(int playerState) {
        // OCR 现在可以在全屏切换时正常工作，无需特殊处理
    }
    
    /**
     * 检查是否需要为 OCR 拦截全屏切换
     * 
     * @return false - 不再需要拦截（MediaCodecTexture 已修复横竖屏切换问题）
     */
    public boolean shouldInterceptFullscreenForOcr() {
        return false;
    }
    
    /**
     * 在全屏切换前暂停 OCR 并切换到 SurfaceView
     * 
     * 注意：此方法已废弃，不再需要调用
     */
    @Deprecated
    public void pauseOcrForFullscreenSwitch() {
        // 不再需要暂停 OCR
    }
    
    /**
     * 在全屏切换后恢复 OCR
     * 
     * 注意：此方法已废弃，不再需要调用
     */
    @Deprecated
    private void resumeOcrAfterFullscreenSwitch() {
        // 不再需要恢复 OCR
    }
    
    /**
     * 检查 OCR 是否正在运行
     */
    public boolean isOcrRunning() {
        return mOcrSubtitleManager != null && mOcrSubtitleManager.isRunning();
    }
    
    /**
     * 检查 OCR 是否被暂停等待恢复
     * 
     * @return false - 不再需要暂停/恢复机制
     */
    @Deprecated
    public boolean isOcrPausedForFullscreen() {
        return false;
    }
    
    /**
     * 显示自定义Toast
     */
    private void showToast(String message) {
        if (mVideoView != null) {
            OrangeToast.show(mVideoView, message);
        }
    }
    
    /**
     * 绑定控制器组件
     */
    public void bindControllerComponents(VodControlView vodControlView) {
        mVodControlView = vodControlView;
        bindControllerEvents();
    }
    
    /**
     * 绑定 TitleView 组件
     */
    public void bindTitleView(com.orange.playerlibrary.component.TitleView titleView) {
        if (titleView != null) {
            // 绑定设置按钮点击事件
            titleView.setOnSettingsClickListener(v -> {
                showSetupDialog();
            });
            
            // 绑定投屏按钮点击事件
            titleView.setOnCastClickListener(v -> {
                showCastDialog();
            });
            
            // 绑定小窗按钮点击事件
            titleView.setOnWindowClickListener(v -> {
                onSmallWindowPlayClick();
            });
        }
    }
    
    /**
     * 绑定基础事件
     */
    private void bindEvents() {
        // 倍速按钮事
        // 注意：这里使用接口方式绑定，实际调用在bindControllerEvents
    }
    
    /**
     * 绑定控制器事
     */
    private void bindControllerEvents() {
                
        if (mVodControlView == null) {
                        return;
        }
        
        // 绑定倍速按钮点击事
                mVodControlView.setOnSpeedControlClickListener(v -> {
                        showSpeedDialog();
        });
        
        // 绑定选集按钮点击事件
                mVodControlView.setOnEpisodeSelectClickListener(v -> {
                        showPlaylistDialog();
        });
        
        // 绑定弹幕开关按钮点击事件
        mVodControlView.setOnDanmuToggleClickListener(v -> {
            toggleDanmaku(v);
        });
        
        // 绑定弹幕设置按钮点击事件
        mVodControlView.setOnDanmuSetClickListener(v -> {
            showDanmakuSettingsDialog();
        });
        
        // 绑定弹幕输入框点击事件
        mVodControlView.setOnDanmuInputClickListener(v -> {
            showDanmakuInputDialog();
        });
        
        // 绑定跳过片头片尾按钮点击事件
        mVodControlView.setOnSkipOpeningClickListener(v -> {
            showSkipDialog();
        });
        
        // 绑定下一集按钮点击事件
        mVodControlView.setOnPlayNextClickListener(v -> {
            playNextEpisode();
        });
        
        // 绑定字幕按钮点击事件
        mVodControlView.setOnSubtitleToggleClickListener(v -> {
            showSubtitleDialog(v);
        });
        
        // 绑定播放按钮长按事件（用于长按倍速）
        ImageView playButton = mVodControlView.getPlayButton();
        if (playButton != null) {
                        setupLongPressSpeed(playButton);
        } else {
                    }
        
            }
    
    /**
     * 显示倍速选择对话
     */
    private void showSpeedDialog() {
        hideController(); // 隐藏播放器UI
        
        try {
            // 创建对话框视图
            View dialogView = View.inflate(mActivity, R.layout.speed_dialog, null);
                        
            // 始终显示在右侧
            final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                    DialogUtils.DialogPosition.RIGHT, null, null);
            
            // 设置倍速选项
            setupSpeedOptions(dialogView, dialog);
        } catch (Exception e) {
        }
    }
    
    /**
     * 设置倍速选项
     */
    private void setupSpeedOptions(View dialogView, AlertDialog dialog) {
        // 获取当前播放器内核
        String currentEngine = mSettingsManager.getPlayerEngine();
        boolean isIjkEngine = PlayerConstants.ENGINE_IJK.equals(currentEngine);
        
        // 根据内核设置最大倍速
        // IJK 内核：最高 2.0x（AudioTrack buffer 限制）
        // 其他内核：最高 5.0x
        final String[] speeds;
        if (isIjkEngine) {
            speeds = new String[]{"0.35x", "0.45x", "0.75x", "1.0x", "1.25x", "1.5x", "1.75x", "2.0x","2.5x", "3.0x", "3.5x"};
        } else {
            speeds = new String[]{"0.35x", "0.45x", "0.75x", "1.0x", "1.25x", "1.5x", "1.75x", "2.0x", 
                                 "2.5x", "3.0x", "3.5x", "4.0x", "4.5x", "5.0x"};
        }
        
        // 创建数据列表
        ArrayList<HashMap<String, Object>> arrayList = new ArrayList<>();
        for (String speed : speeds) {
            HashMap<String, Object> map = new HashMap<>();
            map.put("name", speed);
            arrayList.add(map);
        }
        
        // 使用 RecyclerView 显示倍速列表
        RecyclerView recyclerView = dialogView.findViewById(R.id.recycler);
        if (recyclerView != null) {
            OrangeRecyclerView orangeRecyclerView = new OrangeRecyclerView();
            orangeRecyclerView.setLinearLayoutManager(recyclerView, mActivity);
            orangeRecyclerView.setAdapter(recyclerView, R.layout.speed_dialog_item, arrayList,
                (holder, data, position) -> {
                    android.widget.TextView speedName = holder.itemView.findViewById(R.id.title);
                    String speedText = data.get(position).get("name").toString();
                    float speedValue = Float.parseFloat(speedText.replace("x", ""));
                    
                    // 高亮当前倍速
                    float currentSpeed = mVideoView.getSpeed();
                    if (Math.abs(speedValue - currentSpeed) < 0.01f) {
                        speedName.setTextColor(COLOR_HIGHLIGHT);
                        speedName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
                    } else {
                        speedName.setTextColor(COLOR_NORMAL);
                        speedName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
                    }
                    
                    speedName.setText(speedText);
                    
                    // 倍速选择事件
                    speedName.setOnClickListener(v -> {
                        mVideoView.setSpeed(speedValue);
                        showToast("倍速 " + speedText);
                        dialog.dismiss();
                    });
                });
        } else {
        }
    }
    
    /**
     * 设置长按倍速功
     * 长按视图时加速播放，松开恢复正常速度
     */
    private void setupLongPressSpeed(View view) {
        view.setOnLongClickListener(v -> {
            if (!mIsLongPressing && mVideoView.isPlaying()) {
                mIsLongPressing = true;
                mNormalSpeed = mVideoView.getSpeed();
                mVideoView.setSpeed(mLongPressSpeed);
                                return true;
            }
            return false;
        });
        
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP ||
                event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                if (mIsLongPressing) {
                    mIsLongPressing = false;
                    mVideoView.setSpeed(mNormalSpeed);
                                    }
            }
            return false;
        });
    }
    
    /**
     * 设置长按倍速
     */
    public void setLongPressSpeed(float speed) {
        mLongPressSpeed = speed;
        mSettingsManager.setLongPressSpeed(speed);
    }
    
    /**
     * 获取长按倍速
     */
    public float getLongPressSpeed() {
        return mLongPressSpeed;
    }
    
    /**
     * 重置临时设置（在切换视频时调用）
     * 跳过片头片尾、倍速、画面比例等设置不持久化，每次播放重置为默认值
     * 
     * @param videoUrl 新视频的 URL，用于判断是否切换了视频
     */
    public void resetTemporarySettings(String videoUrl) {
        android.util.Log.d("VideoEventManager", "resetTemporarySettings() called with url: " + videoUrl);
        android.util.Log.d("VideoEventManager", "Current URL: " + mCurrentVideoUrl);
        
        // 检查是否切换了视频（URL 不同）
        boolean isNewVideo = (mCurrentVideoUrl == null || !mCurrentVideoUrl.equals(videoUrl));
        android.util.Log.d("VideoEventManager", "isNewVideo: " + isNewVideo);
        
        // 检查是否切换了剧集（视频列表hash不同）
        int currentListHash = getVideoListHash();
        boolean isSeriesChanged = (mCurrentVideoListHash != 0 && mCurrentVideoListHash != currentListHash);
        android.util.Log.d("VideoEventManager", "isSeriesChanged: " + isSeriesChanged + " (old=" + mCurrentVideoListHash + ", new=" + currentListHash + ")");
        
        if (isNewVideo) {
            // 切换了视频，更新URL
            mCurrentVideoUrl = videoUrl;
            
            // 片头尾、倍数设置：同一剧集内切换集数时保持，切换剧集时重置
            if (isSeriesChanged) {
                // 切换了剧集，重置片头尾和倍数为默认
                android.util.Log.d("VideoEventManager", "Series changed, reset skip settings and screen scale to default");
                mCurrentSkipOpening = 0;
                mCurrentSkipEnding = 0;
                mCurrentScreenScale = "默认";
                mCurrentVideoListHash = currentListHash;
            }
            
            // 重新应用到播放器
            android.util.Log.d("VideoEventManager", "Applying skip settings: opening=" + mCurrentSkipOpening + ", ending=" + mCurrentSkipEnding);
            if (mVideoView != null) {
                mVideoView.setSkipIntroTime(mCurrentSkipOpening);
                mVideoView.setSkipIntroEnabled(mCurrentSkipOpening > 0);
                mVideoView.setSkipOutroTime(mCurrentSkipEnding);
                mVideoView.setSkipOutroEnabled(mCurrentSkipEnding > 0);
                SkipManager skipManager = mVideoView.getSkipManager();
                if (skipManager != null) {
                    android.util.Log.d("VideoEventManager", "Calling skipManager.resetAndAttach() for new video");
                    skipManager.resetAndAttach(mVideoView);
                }
                
                // 应用当前画面比例
                VideoScaleManager scaleManager = mVideoView.getVideoScaleManager();
                if (scaleManager != null) {
                    scaleManager.applyScaleType(mCurrentScreenScale);
                }
            }
        } else {
            // 同一个视频，保持当前设置不变
            // 但需要重新应用到播放器（因为播放器可能被重新创建）
            android.util.Log.d("VideoEventManager", "Same video, re-applying settings");
            android.util.Log.d("VideoEventManager", "Current skip opening: " + mCurrentSkipOpening);
            android.util.Log.d("VideoEventManager", "Current skip ending: " + mCurrentSkipEnding);
            
            if (mVideoView != null) {
                // 重新应用跳过片头片尾设置
                mVideoView.setSkipIntroTime(mCurrentSkipOpening);
                mVideoView.setSkipIntroEnabled(mCurrentSkipOpening > 0);
                mVideoView.setSkipOutroTime(mCurrentSkipEnding);
                mVideoView.setSkipOutroEnabled(mCurrentSkipEnding > 0);
                // 重置 SkipManager 的状态标志（允许再次跳过）
                SkipManager skipManager = mVideoView.getSkipManager();
                if (skipManager != null) {
                    android.util.Log.d("VideoEventManager", "Calling skipManager.resetAndAttach() for same video");
                    skipManager.resetAndAttach(mVideoView);
                }
                
                // 重新应用画面比例
                VideoScaleManager scaleManager = mVideoView.getVideoScaleManager();
                if (scaleManager != null) {
                    scaleManager.applyScaleType(mCurrentScreenScale);
                }
            }
        }
    }
    
    /**
     * 获取当前视频列表的hash值
     * 用于判断是否切换了剧集
     */
    private int getVideoListHash() {
        ArrayList<HashMap<String, Object>> videoList = mController.getVideoList();
        if (videoList == null || videoList.isEmpty()) {
            return 0;
        }
        // 基于列表大小和第一个/最后一个URL生成hash
        int hash = videoList.size();
        if (!videoList.isEmpty()) {
            String firstUrl = videoList.get(0).get("url") != null ? videoList.get(0).get("url").toString() : "";
            String lastUrl = videoList.get(videoList.size() - 1).get("url") != null ? videoList.get(videoList.size() - 1).get("url").toString() : "";
            hash = hash * 31 + firstUrl.hashCode();
            hash = hash * 31 + lastUrl.hashCode();
        }
        return hash;
    }
    
    /**
     * 设置新的剧集列表（外部调用，用于标记剧集切换）
     */
    public void onVideoListChanged() {
        int newHash = getVideoListHash();
        if (mCurrentVideoListHash != 0 && mCurrentVideoListHash != newHash) {
            // 剧集切换，重置片头尾和画面比例
            mCurrentSkipOpening = 0;
            mCurrentSkipEnding = 0;
            mCurrentScreenScale = "默认";
            mSettingsManager.clearSessionVideoScale();
            android.util.Log.d("VideoEventManager", "onVideoListChanged: series changed, reset skip settings and screen scale");
        }
        mCurrentVideoListHash = newHash;
    }
    
    /**
     * 获取播放模式
     */
    private String getPlayMode() {
        return mSettingsManager.getPlayMode();
    }
    
    /**
     * 设置播放模式
     */
    private void setPlayMode(String mode) {
        if (mCurrentSetupDialog != null) {
            mCurrentSetupDialog.dismiss();
        }
        mSettingsManager.setPlayMode(mode);
        showToast("播放模式: " + getPlayModeName(mode));
    }
    
    /**
     * 获取播放模式名称
     */
    private String getPlayModeName(String mode) {
        switch (mode) {
            case "sequential": return "顺序播放";
            case "single_loop": return "单集循环";
            case "play_pause": return "播放暂停";
            default: return "未知模式";
        }
    }
    
    // ==================== 设置对话====================
    
    /**
     * 显示投屏对话框
     */
    private void showCastDialog() {
        hideController(); // 隐藏播放器UI
        
        // 检查投屏库是否可用
        if (!com.orange.playerlibrary.cast.DLNACastManager.isDLNAAvailable()) {
            showToast("投屏功能未配置");
            return;
        }
        
        // 获取当前视频信息
        String videoUrl = mVideoView.getUrl();
        String title = mController.getVideoTitle();
        
        if (videoUrl == null || videoUrl.isEmpty()) {
            showToast("无法获取视频地址");
            return;
        }
        
        try {
            // 启动投屏
            com.orange.playerlibrary.cast.DLNACastManager.getInstance().startCast(mActivity, videoUrl, title);
        } catch (Exception e) {
            showToast("投屏失败: " + e.getMessage());
        }
    }
    
    /**
     * 显示设置对话框
     */
    public void showSetupDialog() {
        Log.d(TAG, "showSetupDialog() called");
        hideController(); // 隐藏播放器UI
        
        // 创建设置对话框视图
        View dialogView = View.inflate(mActivity, R.layout.setup_dialog, null);
        
        // 创建设置对话框 - 始终显示在右侧
        // DialogUtils 会自动处理点击外部区域关闭的逻辑
        mCurrentSetupDialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                DialogUtils.DialogPosition.RIGHT, null, null);
        
        // 只在非全屏状态下调整弹窗大小以适配播放器
        // 使用 isFullScreen() 方法判断，它通过 CustomFullscreenHelper 判断更准确
        boolean isFullscreen = mVideoView != null && mVideoView.isFullScreen();
        Log.d(TAG, "showSetupDialog: isFullscreen=" + isFullscreen);
        
        if (mVideoView != null && !isFullscreen) {
            adjustDialogSizeForPlayer(dialogView);
        }
        
        // 绑定所有设置项（使用 dialogView 而不是 mCurrentSetupDialog）
        bindSetupOptions(dialogView);
    }
    
    /**
     * 调整弹窗大小以适配播放器（仅非全屏状态）
     */
    private void adjustDialogSizeForPlayer(View dialogView) {
        // 获取播放器的位置和尺寸
        int[] location = new int[2];
        mVideoView.getLocationOnScreen(location);
        int playerTop = location[1];
        int playerHeight = mVideoView.getHeight();
        int playerWidth = mVideoView.getWidth();
        
        Log.d(TAG, "adjustDialogSizeForPlayer: playerTop=" + playerTop + 
              ", playerHeight=" + playerHeight + ", playerWidth=" + playerWidth);
        
        // 获取内容面板
        View contentLayout = dialogView.findViewById(R.id.content_layout);
        if (contentLayout != null) {
            // 设置内容面板的宽度为播放器宽度的 65%，高度为播放器高度
            android.view.ViewGroup.LayoutParams params = contentLayout.getLayoutParams();
            if (params instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams frameParams = 
                    (android.widget.FrameLayout.LayoutParams) params;
                frameParams.width = (int) (playerWidth * 0.65f); // 65% 宽度
                frameParams.height = playerHeight;
                frameParams.topMargin = playerTop;
                frameParams.gravity = android.view.Gravity.END | android.view.Gravity.TOP;
                contentLayout.setLayoutParams(frameParams);
                Log.d(TAG, "adjustDialogSizeForPlayer: adjusted width=" + frameParams.width + 
                      ", height=" + frameParams.height);
            }
        }
    }
    
    /**
     * 绑定设置选项
     */
    private void bindSetupOptions(View dialogView) {
        // 获取所有设置项视图 - 从 dialogView 获取而不是从 dialog 获取
        android.widget.LinearLayout screenScaleButton = dialogView.findViewById(R.id.line1);
        android.widget.LinearLayout longPressSpeedButton = dialogView.findViewById(R.id.line2);
        android.widget.LinearLayout timerCloseButton = dialogView.findViewById(R.id.line3);
        android.widget.LinearLayout skipOpeningButton = dialogView.findViewById(R.id.line4);
        android.widget.LinearLayout skipEndingButton = dialogView.findViewById(R.id.line5);
        android.widget.LinearLayout smallWindowButton = dialogView.findViewById(R.id.line6);
        android.widget.LinearLayout progressBarButton = dialogView.findViewById(R.id.line7);
        android.widget.LinearLayout downloadButton = dialogView.findViewById(R.id.line10);
        android.widget.ImageView progressBarIcon = dialogView.findViewById(R.id.kgImage);
        
        // 播放核心按钮
        android.widget.TextView aliEngineBtn = dialogView.findViewById(R.id.alihx);
        android.widget.TextView exoEngineBtn = dialogView.findViewById(R.id.exohx);
        android.widget.TextView ijkEngineBtn = dialogView.findViewById(R.id.ijkhx);
        android.widget.TextView systemEngineBtn = dialogView.findViewById(R.id.systemhx);
        
        // 播放模式按钮
        android.widget.TextView sequentialPlayBtn = dialogView.findViewById(R.id.sxbf);
        android.widget.TextView singleLoopBtn = dialogView.findViewById(R.id.djxh);
        android.widget.TextView playPauseBtn = dialogView.findViewById(R.id.bwzt);
        
        // 解码方式按钮
        android.widget.TextView decodeHardwareBtn = dialogView.findViewById(R.id.decode_hardware);
        android.widget.TextView decodeSoftwareBtn = dialogView.findViewById(R.id.decode_software);
        
        // 自动旋转按钮
        android.widget.TextView autoRotateOnBtn = dialogView.findViewById(R.id.auto_rotate_on);
        android.widget.TextView autoRotateOffBtn = dialogView.findViewById(R.id.auto_rotate_off);
        
        // 获取音量控制组件
        android.widget.SeekBar volumeSeekBar = dialogView.findViewById(R.id.volumeSeek_bar);
        android.widget.TextView volumeText = dialogView.findViewById(R.id.volumeText);
        
        // 设置播放核心按钮
        setupEngineButtons(aliEngineBtn, exoEngineBtn, ijkEngineBtn, systemEngineBtn);
        
        // 设置解码方式按钮
        setupDecodeModeButtons(decodeHardwareBtn, decodeSoftwareBtn);
        
        // 设置自动旋转按钮
        setupAutoRotateButtons(autoRotateOnBtn, autoRotateOffBtn);
        
        // 设置播放模式按钮
        setupPlayModeButtons(sequentialPlayBtn, singleLoopBtn, playPauseBtn);
        
        // 设置进度条开关状态
        if (progressBarIcon != null) {
            boolean showProgress = mSettingsManager.isBottomProgressEnabled();
            progressBarIcon.setImageResource(showProgress ? R.drawable.ic_progress_on : R.drawable.ic_progress_off);
        }
        
        // 绑定音量控制
        setupVolumeControl(volumeSeekBar, volumeText);
        
        // 绑定画面比例按钮点击事件
        if (screenScaleButton != null) {
            screenScaleButton.setOnClickListener(v -> showScreenScaleDialog());
        }
        
        // 绑定长按倍速按钮点击事件
        if (longPressSpeedButton != null) {
            longPressSpeedButton.setOnClickListener(v -> showLongPressSpeedDialog());
        }
        
        // 绑定定时关闭按钮点击事件
        if (timerCloseButton != null) {
            timerCloseButton.setOnClickListener(v -> showTimerCloseDialog());
        }
        
        // 绑定跳过片头按钮点击事件
        if (skipOpeningButton != null) {
            skipOpeningButton.setOnClickListener(v -> showSkipOpeningDialog());
        }
        
        // 绑定跳过片尾按钮点击事件
        if (skipEndingButton != null) {
            skipEndingButton.setOnClickListener(v -> showSkipEndingDialog());
        }
        
        // 绑定小窗播放按钮点击事件
        if (smallWindowButton != null) {
            smallWindowButton.setOnClickListener(v -> onSmallWindowPlayClick());
        }
        
        // 绑定进度条开关按钮点击事件
        if (progressBarButton != null) {
            progressBarButton.setOnClickListener(v -> onProgressBarClick(progressBarIcon));
        }
        
        // 绑定下载视频按钮点击事件
        if (downloadButton != null) {
            downloadButton.setOnClickListener(v -> onDownloadVideoClick());
        }
        
        // 绑定截图按钮点击事件
        android.widget.LinearLayout screenshotButton = dialogView.findViewById(R.id.line_screenshot);
        if (screenshotButton != null) {
            screenshotButton.setOnClickListener(v -> onScreenshotClick());
        }
    }
    
    /**
     * 设置播放核心按钮
     */
    private void setupEngineButtons(android.widget.TextView aliBtn, android.widget.TextView exoBtn, 
                                   android.widget.TextView ijkBtn, android.widget.TextView systemBtn) {
        // 检查核心是否可用
        boolean isAliPlayerAvailable = isClassPresent("com.aliyun.player.AliPlayer");
        boolean isIjkPlayerAvailable = isIjkPlayerAvailable(); // 使用新的检测方法，同时检查 Java 类和 SO 库
        // ExoPlayer 检测：GSY 11.x 使用 Media3，也检测旧版 ExoPlayer2
        boolean isExoPlayerAvailable = isClassPresent("com.shuyu.gsyvideoplayer.player.Exo2PlayerManager") ||
                                       isClassPresent("com.google.android.exoplayer2.ExoPlayer") ||
                                       isClassPresent("com.google.android.exoplayer2.Player") ||
                                       isClassPresent("androidx.media3.exoplayer.ExoPlayer");
        
        // 调试日志：显示内核可用性
        android.util.Log.d("VideoEventManager", "setupEngineButtons: Ali=" + isAliPlayerAvailable + 
            ", IJK=" + isIjkPlayerAvailable + ", Exo=" + isExoPlayerAvailable);
        
        // 设置可见性
        if (aliBtn != null) aliBtn.setVisibility(isAliPlayerAvailable ? View.VISIBLE : View.GONE);
        if (ijkBtn != null) ijkBtn.setVisibility(isIjkPlayerAvailable ? View.VISIBLE : View.GONE);
        if (exoBtn != null) exoBtn.setVisibility(isExoPlayerAvailable ? View.VISIBLE : View.GONE);
        if (systemBtn != null) systemBtn.setVisibility(View.VISIBLE); // 系统核心始终可用
        
        // 获取当前引擎
        String currentEngine = mSettingsManager.getPlayerEngine();
        
        // 调试日志：显示当前引擎
        android.util.Log.d("VideoEventManager", "setupEngineButtons: currentEngine=" + currentEngine);
        
        // 高亮当前引擎
        if (aliBtn != null) {
            aliBtn.setTextColor(PlayerConstants.ENGINE_ALI.equals(currentEngine) ? COLOR_HIGHLIGHT : COLOR_NORMAL);
            aliBtn.setOnClickListener(v -> selectEngine(PlayerConstants.ENGINE_ALI));
        }
        if (exoBtn != null) {
            exoBtn.setTextColor(PlayerConstants.ENGINE_EXO.equals(currentEngine) ? COLOR_HIGHLIGHT : COLOR_NORMAL);
            exoBtn.setOnClickListener(v -> selectEngine(PlayerConstants.ENGINE_EXO));
        }
        if (ijkBtn != null) {
            ijkBtn.setTextColor(PlayerConstants.ENGINE_IJK.equals(currentEngine) ? COLOR_HIGHLIGHT : COLOR_NORMAL);
            ijkBtn.setOnClickListener(v -> selectEngine(PlayerConstants.ENGINE_IJK));
        }
        if (systemBtn != null) {
            systemBtn.setTextColor(PlayerConstants.ENGINE_DEFAULT.equals(currentEngine) ? COLOR_HIGHLIGHT : COLOR_NORMAL);
            systemBtn.setOnClickListener(v -> selectEngine(PlayerConstants.ENGINE_DEFAULT));
        }
    }
    
    /**
     * 更新播放器引擎按钮UI状态（当代码设置内核时调用）
     * 
     * @param newEngine 新的引擎类型
     */
    private void updateEngineButtonsUI(String newEngine) {
        // 在主线程更新UI
        if (mActivity != null) {
            mActivity.runOnUiThread(() -> {
                // 查找设置对话框中的引擎按钮
                if (mCurrentSetupDialog != null) {
                    android.widget.TextView aliBtn = mCurrentSetupDialog.findViewById(R.id.alihx);
                    android.widget.TextView exoBtn = mCurrentSetupDialog.findViewById(R.id.exohx);
                    android.widget.TextView ijkBtn = mCurrentSetupDialog.findViewById(R.id.ijkhx);
                    android.widget.TextView systemBtn = mCurrentSetupDialog.findViewById(R.id.systemhx);
                    
                    // 更新高亮状态
                    if (aliBtn != null) {
                        aliBtn.setTextColor(PlayerConstants.ENGINE_ALI.equals(newEngine) ? COLOR_HIGHLIGHT : COLOR_NORMAL);
                    }
                    if (exoBtn != null) {
                        exoBtn.setTextColor(PlayerConstants.ENGINE_EXO.equals(newEngine) ? COLOR_HIGHLIGHT : COLOR_NORMAL);
                    }
                    if (ijkBtn != null) {
                        ijkBtn.setTextColor(PlayerConstants.ENGINE_IJK.equals(newEngine) ? COLOR_HIGHLIGHT : COLOR_NORMAL);
                    }
                    if (systemBtn != null) {
                        systemBtn.setTextColor(PlayerConstants.ENGINE_DEFAULT.equals(newEngine) ? COLOR_HIGHLIGHT : COLOR_NORMAL);
                    }
                }
                
                android.util.Log.d("VideoEventManager", "UI已更新，当前引擎: " + getEngineName(newEngine));
            });
        }
    }
    
    /**
     * 通知引擎已更改（用于外部调用更新 UI）
     */
    public void notifyEngineChanged(String newEngine) {
        updateEngineButtonsUI(newEngine);
    }
    
    /**
     * 选择播放引擎
     */
    private void selectEngine(String engine) {
        String oldEngine = mSettingsManager.getPlayerEngine();
        // 保存播放核心设置
        mSettingsManager.setPlayerEngine(engine);
        
        // 关闭设置对话框
        if (mCurrentSetupDialog != null) {
            mCurrentSetupDialog.dismiss();
        }
        
        // 即时切换播放核心
        if (mVideoView != null) {
            // 记录当前播放位置和URL
            long currentPosition = mVideoView.getCurrentPositionWhenPlaying();
            String currentUrl = mVideoView.getUrl();
            boolean wasPlaying = mVideoView.isPlaying();
            // 1. 先完全释放旧播放器（关键！）
            mVideoView.release();
            com.shuyu.gsyvideoplayer.GSYVideoManager.releaseAllVideos();
            
            // 2. 切换播放器工厂
            mVideoView.selectPlayerFactory(engine);
            // 3. 如果有正在播放的视频，重新加载
            if (currentUrl != null && !currentUrl.isEmpty()) {
                mVideoView.setUp(currentUrl, false, "");
                if (currentPosition > 0) {
                    mVideoView.setSeekOnStart(currentPosition);
                }
                if (wasPlaying) {
                    mVideoView.startPlayLogic();
                }
            }
        }
        
        // 提示用户
        showToast("播放核心已切换为 " + getEngineName(engine));
    }
    
    /**
     * 获取播放核心名称
     */
    private String getEngineName(String engine) {
        switch (engine) {
            case PlayerConstants.ENGINE_IJK:
                return "IJK";
            case PlayerConstants.ENGINE_EXO:
                return "EXO";
            case PlayerConstants.ENGINE_ALI:
                return "阿里云";
            case PlayerConstants.ENGINE_DEFAULT:
            default:
                return "系统核心";
        }
    }
    
    /**
     * 检查类是否存在
     */
    private boolean isClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    /**
     * 检查 IJK 播放器是否可用
     * 只检查 Java 类是否存在，SO 库由 GSY 内部处理
     */
    private boolean isIjkPlayerAvailable() {
        // 只检查 Java 类是否存在
        // SO 库由 GSY 内部加载，不需要手动检测
        boolean available = isClassPresent("tv.danmaku.ijk.media.player.IjkMediaPlayer");
        android.util.Log.d("VideoEventManager", "isIjkPlayerAvailable: " + available);
        return available;
    }
    
    /**
     * 设置解码方式按钮
     */
    private void setupDecodeModeButtons(android.widget.TextView hardwareBtn, android.widget.TextView softwareBtn) {
        // 获取当前解码方式
        String currentMode = mSettingsManager.getDecodeMode();
        boolean isHardware = PlayerSettingsManager.DECODE_HARDWARE.equals(currentMode);
        
        // 高亮当前解码方式
        if (hardwareBtn != null) {
            hardwareBtn.setTextColor(isHardware ? COLOR_HIGHLIGHT : COLOR_NORMAL);
            hardwareBtn.setOnClickListener(v -> selectDecodeMode(PlayerSettingsManager.DECODE_HARDWARE));
        }
        if (softwareBtn != null) {
            softwareBtn.setTextColor(!isHardware ? COLOR_HIGHLIGHT : COLOR_NORMAL);
            softwareBtn.setOnClickListener(v -> selectDecodeMode(PlayerSettingsManager.DECODE_SOFTWARE));
        }
    }
    
    /**
     * 选择解码方式
     */
    private void selectDecodeMode(String mode) {
        String oldMode = mSettingsManager.getDecodeMode();
        if (oldMode.equals(mode)) {
            return; // 没有变化
        }
        
        // 保存解码方式设置
        mSettingsManager.setDecodeMode(mode);
        
        // 应用解码方式到 GSY
        applyDecodeMode(mode);
        
        // 关闭设置对话框
        if (mCurrentSetupDialog != null) {
            mCurrentSetupDialog.dismiss();
        }
        
        // 重新加载视频以应用新的解码方式
        if (mVideoView != null) {
            // 记录当前播放位置和URL
            long currentPosition = mVideoView.getCurrentPositionWhenPlaying();
            String currentUrl = mVideoView.getUrl();
            boolean wasPlaying = mVideoView.isPlaying();
            
            // 释放并重新加载
            mVideoView.release();
            com.shuyu.gsyvideoplayer.GSYVideoManager.releaseAllVideos();
            
            // 重新初始化播放器
            String currentEngine = mSettingsManager.getPlayerEngine();
            mVideoView.selectPlayerFactory(currentEngine);
            
            // 重新加载视频
            if (currentUrl != null && !currentUrl.isEmpty()) {
                mVideoView.setUp(currentUrl, false, "");
                if (currentPosition > 0) {
                    mVideoView.setSeekOnStart(currentPosition);
                }
                if (wasPlaying) {
                    mVideoView.startPlayLogic();
                }
            }
        }
        
        // 提示用户
        String modeName = PlayerSettingsManager.DECODE_HARDWARE.equals(mode) ? "硬件解码" : "软件解码";
        showToast("已切换为 " + modeName);
    }
    
    /**
     * 应用解码方式到 GSY
     */
    private void applyDecodeMode(String mode) {
        boolean useHardware = PlayerSettingsManager.DECODE_HARDWARE.equals(mode);
        
        // GSY 使用 GSYVideoType 设置解码方式
        // enableMediaCodec: true = 硬件解码, false = 软件解码
        // enableMediaCodecTexture: 硬件解码时是否使用 TextureView 渲染
        if (useHardware) {
            // 硬件解码
            com.shuyu.gsyvideoplayer.utils.GSYVideoType.enableMediaCodec();
            com.shuyu.gsyvideoplayer.utils.GSYVideoType.enableMediaCodecTexture();
        } else {
            // 软件解码
            com.shuyu.gsyvideoplayer.utils.GSYVideoType.disableMediaCodec();
        }
    }
    
    /**
     * 设置自动旋转按钮
     */
    private void setupAutoRotateButtons(android.widget.TextView onBtn, android.widget.TextView offBtn) {
        // 获取当前自动旋转设置
        boolean isEnabled = mSettingsManager.isAutoRotateEnabled();
        
        // 高亮当前设置
        if (onBtn != null) {
            onBtn.setTextColor(isEnabled ? COLOR_HIGHLIGHT : COLOR_NORMAL);
            onBtn.setOnClickListener(v -> selectAutoRotate(true));
        }
        if (offBtn != null) {
            offBtn.setTextColor(!isEnabled ? COLOR_HIGHLIGHT : COLOR_NORMAL);
            offBtn.setOnClickListener(v -> selectAutoRotate(false));
        }
    }
    
    /**
     * 选择自动旋转设置
     */
    private void selectAutoRotate(boolean enabled) {
        boolean oldValue = mSettingsManager.isAutoRotateEnabled();
        if (oldValue == enabled) {
            return; // 没有变化
        }
        
        // 保存设置
        mSettingsManager.setAutoRotateEnabled(enabled);
        
        // 应用到 CustomFullscreenHelper
        if (mVideoView != null) {
            CustomFullscreenHelper helper = mVideoView.getFullscreenHelper();
            if (helper != null) {
                helper.setAutoRotateEnabled(enabled);
            }
        }
        
        // 关闭设置对话框
        if (mCurrentSetupDialog != null) {
            mCurrentSetupDialog.dismiss();
        }
        
        // 提示用户
        showToast(enabled ? "已开启全屏自动旋转" : "已关闭全屏自动旋转");
    }
    
    /**
     * 显示定时关闭对话框
     */
    private void showTimerCloseDialog() {
        if (mCurrentSetupDialog != null) {
            mCurrentSetupDialog.dismiss();
        }
        
        hideController();
        
        // 创建对话框
        View dialogView = View.inflate(mActivity, R.layout.timer_dialog, null);
        
        final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                DialogUtils.DialogPosition.RIGHT, null, null);
        
        // 设置定时关闭选项
        setupTimerOptions(dialogView, dialog);
    }
    
    /**
     * 设置定时关闭选项
     */
    private void setupTimerOptions(View dialogView, AlertDialog dialog) {
        android.widget.LinearLayout countdownLayout = dialogView.findViewById(R.id.line1);
        android.widget.LinearLayout optionsLayout = dialogView.findViewById(R.id.line2);
        android.widget.TextView timesText = dialogView.findViewById(R.id.times);
        android.widget.TextView cancelBtn = dialogView.findViewById(R.id.cancel);
        RecyclerView recyclerView = dialogView.findViewById(R.id.recycler);
        
        // 检查是否有正在运行的定时器
        if (mTimerRunning) {
            // 显示倒计时
            if (countdownLayout != null) countdownLayout.setVisibility(View.VISIBLE);
            if (optionsLayout != null) optionsLayout.setVisibility(View.GONE);
            
            // 更新倒计时显示
            if (timesText != null) {
                timesText.setText(formatTime(mRemainingTime));
            }
            
            // 取消按钮
            if (cancelBtn != null) {
                cancelBtn.setOnClickListener(v -> {
                    cancelTimer();
                    if (countdownLayout != null) countdownLayout.setVisibility(View.GONE);
                    if (optionsLayout != null) optionsLayout.setVisibility(View.VISIBLE);
                });
            }
        } else {
            // 显示选项列表
            if (countdownLayout != null) countdownLayout.setVisibility(View.GONE);
            if (optionsLayout != null) optionsLayout.setVisibility(View.VISIBLE);
            
            // 设置定时选项
            if (recyclerView != null) {
                ArrayList<HashMap<String, Object>> arrayList = new ArrayList<>();
                String[] options = {"30分钟", "60分钟", "90分钟", "120分钟"};
                for (String option : options) {
                    HashMap<String, Object> map = new HashMap<>();
                    map.put("name", option);
                    arrayList.add(map);
                }
                
                OrangeRecyclerView orangeRecyclerView = new OrangeRecyclerView();
                orangeRecyclerView.setLinearLayoutManager(recyclerView, mActivity);
                orangeRecyclerView.setAdapter(recyclerView, R.layout.timer_dialog_item, arrayList,
                    (holder, data, position) -> {
                        android.widget.TextView title = holder.itemView.findViewById(R.id.title);
                        String optionText = data.get(position).get("name").toString();
                        title.setText(optionText);
                        title.setTextColor(COLOR_NORMAL);
                        
                        title.setOnClickListener(v -> {
                            int minutes = Integer.parseInt(optionText.replace("分钟", ""));
                            startTimer(minutes * 60 * 1000);
                            dialog.dismiss();
                            showToast("定时关闭: " + optionText);
                        });
                    });
            }
        }
    }
    
    // 定时器相关变量
    private boolean mTimerRunning = false;
    private long mRemainingTime = 0;
    private android.os.Handler mTimerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mTimerRunnable;
    
    /**
     * 启动定时器
     */
    private void startTimer(long milliseconds) {
        mTimerRunning = true;
        mRemainingTime = milliseconds;
        
        mTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (mRemainingTime > 0) {
                    mRemainingTime -= 1000;
                    mTimerHandler.postDelayed(this, 1000);
                } else {
                    // 定时结束，关闭播放器
                    mTimerRunning = false;
                    if (mVideoView != null) {
                        mVideoView.pause();
                    }
                    if (mActivity != null) {
                        mActivity.finish();
                    }
                }
            }
        };
        mTimerHandler.postDelayed(mTimerRunnable, 1000);
    }
    
    /**
     * 取消定时器
     */
    private void cancelTimer() {
        mTimerRunning = false;
        mRemainingTime = 0;
        if (mTimerRunnable != null) {
            mTimerHandler.removeCallbacks(mTimerRunnable);
        }
        showToast("定时关闭已取消");
    }
    
    /**
     * 格式化时间显示
     */
    private String formatTime(long milliseconds) {
        long totalSeconds = milliseconds / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
    
    /**
     * 小窗播放功能
     */
    private void onSmallWindowPlayClick() {
        if (mCurrentSetupDialog != null) {
            mCurrentSetupDialog.dismiss();
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                // 记录当前播放位置
                long currentPos = mVideoView.getCurrentPositionWhenPlaying();
                // 保存播放位置到 SharedPreferences（用于 Activity 重建后恢复）
                android.content.SharedPreferences prefs = mActivity.getSharedPreferences("pip_prefs", android.content.Context.MODE_PRIVATE);
                prefs.edit()
                    .putBoolean("pip_active", true)
                    .putString("pip_url", mVideoView.getUrl())
                    .putLong("pip_position", currentPos)
                    .apply();
                // 设置正在进入 PiP 模式的标志
                // 这样 onPause 中可以检测到并跳过暂停操作
                mVideoView.setEnteringPiPMode(true);
                // 进入小窗模式
                mActivity.enterPictureInPictureMode();
            } catch (Exception e) {
                mVideoView.setEnteringPiPMode(false);
                showToast("进入小窗模式失败");
            }
        } else {
            showToast("您的设备不支持小窗播放");
        }
    }
    
    /**
     * 进度条开关功能
     */
    private void onProgressBarClick(android.widget.ImageView progressBarIcon) {
        android.util.Log.d("VideoEventManager", "onProgressBarClick() called");
        
        boolean currentState = mSettingsManager.isBottomProgressEnabled();
        boolean newState = !currentState;
        
        android.util.Log.d("VideoEventManager", "  currentState=" + currentState + ", newState=" + newState);
        
        // 保存设置
        mSettingsManager.setBottomProgressEnabled(newState);
        
        // 更新 VodControlView 的进度条显示（静态设置）
        VodControlView.setBottomProgress(newState);
        
        // 立即更新当前 VodControlView 实例的进度条显示
        VodControlView vodControlView = getActualVodControlView();
        android.util.Log.d("VideoEventManager", "  vodControlView=" + vodControlView);
        
        if (vodControlView != null) {
            vodControlView.showBottomProgress(newState);
        } else {
            android.util.Log.w("VideoEventManager", "  vodControlView is null!");
        }
        
        // 同时更新所有可能存在的 VodControlView 实例
        if (mVideoView != null) {
            VodControlView mainVodControlView = mVideoView.getVodControlView();
            if (mainVodControlView != null && mainVodControlView != vodControlView) {
                android.util.Log.d("VideoEventManager", "  Also updating mainVodControlView=" + mainVodControlView);
                mainVodControlView.showBottomProgress(newState);
            }
        }
        
        // 更新图标
        if (progressBarIcon != null) {
            progressBarIcon.setImageResource(newState ? R.drawable.ic_progress_on : R.drawable.ic_progress_off);
        }
        
        showToast(newState ? "底部进度条已开启" : "底部进度条已关闭");
    }
    
    /**
     * 下载视频功能
     */
    private void onDownloadVideoClick() {
        if (mCurrentSetupDialog != null) {
            mCurrentSetupDialog.dismiss();
        }
        
        // 检查全局下载开关是否开启
        com.orange.playerlibrary.PlayerSettingsManager settingsManager = 
            com.orange.playerlibrary.PlayerSettingsManager.getInstance(mContext);
        if (settingsManager != null && !settingsManager.isDownloadEnabled()) {
            android.util.Log.w(TAG, "onDownloadVideoClick: 下载功能已被禁用");
            // 使用自定义的 showToast 方法（在播放器中央弹出，不受系统限制）
            showToast("下载功能已被禁用，请在设置中开启");
            return;
        }
        
        // 获取当前视频URL和标题
        // getUrl() 会返回 mOriginUrl（当前播放的 URL）或 mVideoUrl
        String url = mVideoView.getUrl();
        
        // 优先从 TitleView 获取标题（最新的显示标题）
        // 如果为空，再从 Controller 获取
        String title = null;
        if (mVideoView.getTitleView() != null) {
            title = mVideoView.getTitleView().getTitle();
        }
        if (title == null || title.isEmpty()) {
            title = mController.getVideoTitle();
        }
        if (title == null || title.isEmpty()) {
            title = "未命名视频";
        }
        
        android.util.Log.d(TAG, "onDownloadVideoClick - Current URL: " + url);
        android.util.Log.d(TAG, "onDownloadVideoClick - Current title: " + title);
        
        if (url == null || url.isEmpty()) {
            showToast("无法获取视频地址");
            return;
        }
        
        // 优先使用外部监听器
        if (mOnDownloadClickListener != null) {
            mOnDownloadClickListener.onDownloadClick(url, title);
            return;
        }
        
        // 使用内置下载功能
        showDownloadDialog(url, title);
    }
    
    /**
     * 显示下载对话框
     */
    private void showDownloadDialog(String url, String title) {
        android.util.Log.d(TAG, "showDownloadDialog: url=" + url + ", title=" + title);
        
        // 创建自定义对话框（使用透明背景主题）
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(mContext);
        android.view.View view = android.view.LayoutInflater.from(mContext).inflate(
            R.layout.dialog_download_confirm, null);
        builder.setView(view);
        
        android.app.AlertDialog dialog = builder.create();
        
        // 设置对话框背景透明，让自定义布局的背景生效
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        
        // 设置视频信息
        android.widget.TextView tvTitle = view.findViewById(R.id.tv_video_title);
        tvTitle.setText(title != null ? title : "未命名视频");
        
        // 下载管理按钮
        view.findViewById(R.id.btn_download_manager).setOnClickListener(v -> {
            dialog.dismiss();
            showDownloadManagerDialog();
        });
        
        // 选集下载按钮判断
        android.widget.Button btnDownloadPlaylist = view.findViewById(R.id.btn_download_playlist);
        if (mController != null && mController.getVideoList() != null && !mController.getVideoList().isEmpty()) {
            btnDownloadPlaylist.setVisibility(android.view.View.VISIBLE);
            btnDownloadPlaylist.setOnClickListener(v -> {
                dialog.dismiss();
                showDownloadPlaylistDialog();
            });
        } else {
            btnDownloadPlaylist.setVisibility(android.view.View.GONE);
        }
        
        // 取消按钮
        view.findViewById(R.id.btn_cancel).setOnClickListener(v -> {
            dialog.dismiss();
        });
        
        // 确认下载按钮
        view.findViewById(R.id.btn_download).setOnClickListener(v -> {
            android.util.Log.d(TAG, "User confirmed download");
            dialog.dismiss();
            
            // 使用系统 DownloadManager 下载到公共 Downloads 目录
            // 不需要任何权限（Android 所有版本）
            startDownload(url, title);
        });
        
        dialog.show();
        dialog.show();
    }
    
    /**
     * 显示下载管理对话框
     */
    private com.orange.playerlibrary.component.SimpleDownloadDialogView mDownloadDialog;
    
    private void showDownloadManagerDialog() {
        if (mDownloadDialog == null) {
            mDownloadDialog = new com.orange.playerlibrary.component.SimpleDownloadDialogView(mContext);
            mDownloadDialog.setOnPlayLocalListener((filePath, item) -> {
                if (mVideoView == null) {
                    return;
                }
                String playUrl = filePath;
                if (!playUrl.startsWith("file://") && !playUrl.startsWith("http")) {
                    playUrl = "file://" + playUrl;
                }
                String title = item != null && item.getTitle() != null && !item.getTitle().isEmpty()
                        ? item.getTitle()
                        : "已下载视频";
                mVideoView.setUp(playUrl, false, title);
                mVideoView.startPlayLogic();
            });
        }
        mDownloadDialog.show();
    }
    
    /**
     * 显示下载选集对话框
     */
    private void showDownloadPlaylistDialog() {
        final ArrayList<HashMap<String, Object>> originalList = mController.getVideoList();
        if (originalList == null || originalList.isEmpty()) {
            showToast("暂无选集");
            return;
        }

        // 创建自定义对话框
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(mContext);
        android.view.View dialogView = android.view.LayoutInflater.from(mContext).inflate(
                R.layout.dialog_download_playlist, null);
        builder.setView(dialogView);
        
        final android.app.AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // 初始化组件
        androidx.recyclerview.widget.RecyclerView recyclerView = dialogView.findViewById(R.id.recycler_playlist);
        android.widget.TextView btnSelectAll = dialogView.findViewById(R.id.btn_select_all);
        android.widget.TextView tvSelectedCount = dialogView.findViewById(R.id.tv_selected_count);
        android.widget.Button btnStartDownload = dialogView.findViewById(R.id.btn_start_download);

        recyclerView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(mContext));

        // 选中的集合
        final java.util.Set<Integer> selectedPositions = new java.util.HashSet<>();
        com.orange.playerlibrary.download.SimpleDownloadManager downloadManager = com.orange.playerlibrary.download.SimpleDownloadManager.getInstance(mContext);

        // 适配器
        androidx.recyclerview.widget.RecyclerView.Adapter adapter = new androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            @androidx.annotation.NonNull
            @Override
            public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(@androidx.annotation.NonNull android.view.ViewGroup parent, int viewType) {
                android.view.View view = android.view.LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_download_playlist, parent, false);
                return new androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {};
            }

            @Override
            public void onBindViewHolder(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder holder, int position) {
                HashMap<String, Object> itemData = originalList.get(position);
                String title = itemData.get("name") != null ? itemData.get("name").toString() : "第" + (position + 1) + "集";
                String url = itemData.get("url") != null ? itemData.get("url").toString() : "";

                android.widget.TextView tvTitle = holder.itemView.findViewById(R.id.tv_title);
                android.widget.CheckBox cbSelect = holder.itemView.findViewById(R.id.cb_select);
                android.widget.TextView tvStatus = holder.itemView.findViewById(R.id.tv_status);

                tvTitle.setText(title);

                // 检查是否已下载或正在下载
                boolean isDownloaded = downloadManager.getLocalVideoPath(url) != null;
                boolean isDownloading = downloadManager.isDownloading(url);

                if (isDownloaded) {
                    tvStatus.setVisibility(android.view.View.VISIBLE);
                    tvStatus.setText("已下载");
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
                    cbSelect.setVisibility(android.view.View.GONE);
                    holder.itemView.setClickable(false);
                } else if (isDownloading) {
                    tvStatus.setVisibility(android.view.View.VISIBLE);
                    tvStatus.setText("下载中");
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#FF9800"));
                    cbSelect.setVisibility(android.view.View.GONE);
                    holder.itemView.setClickable(false);
                } else {
                    tvStatus.setVisibility(android.view.View.GONE);
                    cbSelect.setVisibility(android.view.View.VISIBLE);
                    
                    // 绑定选中状态
                    cbSelect.setOnCheckedChangeListener(null); // 防止复用引发问题
                    cbSelect.setChecked(selectedPositions.contains(position));
                    
                    android.view.View.OnClickListener clickListener = v -> {
                        if (selectedPositions.contains(position)) {
                            selectedPositions.remove(position);
                            cbSelect.setChecked(false);
                        } else {
                            selectedPositions.add(position);
                            cbSelect.setChecked(true);
                        }
                        tvSelectedCount.setText("已选择 " + selectedPositions.size() + " 集");
                    };
                    holder.itemView.setOnClickListener(clickListener);
                    cbSelect.setOnClickListener(clickListener);
                }
            }

            @Override
            public int getItemCount() {
                return originalList.size();
            }
        };
        recyclerView.setAdapter(adapter);

        // 全选/反选逻辑
        btnSelectAll.setOnClickListener(v -> {
            boolean isAllSelected = true;
            int availableCount = 0;
            
            for (int i = 0; i < originalList.size(); i++) {
                String url = originalList.get(i).get("url") != null ? originalList.get(i).get("url").toString() : "";
                if (downloadManager.getLocalVideoPath(url) == null && !downloadManager.isDownloading(url)) {
                    availableCount++;
                    if (!selectedPositions.contains(i)) {
                        isAllSelected = false;
                    }
                }
            }
            
            if (availableCount == 0) {
                showToast("所有剧集均已下载或正在下载");
                return;
            }

            if (isAllSelected) {
                selectedPositions.clear();
            } else {
                for (int i = 0; i < originalList.size(); i++) {
                    String url = originalList.get(i).get("url") != null ? originalList.get(i).get("url").toString() : "";
                    if (downloadManager.getLocalVideoPath(url) == null && !downloadManager.isDownloading(url)) {
                        selectedPositions.add(i);
                    }
                }
            }
            adapter.notifyDataSetChanged();
            tvSelectedCount.setText("已选择 " + selectedPositions.size() + " 集");
        });

        // 开始下载
        btnStartDownload.setOnClickListener(v -> {
            if (selectedPositions.isEmpty()) {
                showToast("请先选择要下载的剧集");
                return;
            }
            dialog.dismiss();
            
            int addCount = 0;
            for (Integer pos : selectedPositions) {
                HashMap<String, Object> itemData = originalList.get(pos);
                String title = itemData.get("name") != null ? itemData.get("name").toString() : "第" + (pos + 1) + "集";
                String url = itemData.get("url") != null ? itemData.get("url").toString() : "";
                if (!url.isEmpty()) {
                    downloadManager.startDownload(url, title, "批量下载");
                    addCount++;
                }
            }
            showToast("已添加 " + addCount + " 个下载任务");
            showDownloadManagerDialog();
        });

        dialog.show();
    }

    /**
     * 开始下载（使用 VideoDownloader）
     */
    private void startDownload(String url, String title) {
        android.util.Log.d(TAG, "startDownload: url=" + url + ", title=" + title);
        
        try {
            // 使用单例获取下载管理器
            com.orange.playerlibrary.download.SimpleDownloadManager downloadManager = 
                com.orange.playerlibrary.download.SimpleDownloadManager.getInstance(mContext);
            
            // 检查本地是否已下载
            String localPath = downloadManager.getLocalVideoPath(url);
            if (localPath != null) {
                android.util.Log.d(TAG, "Video already downloaded: " + localPath);
                showToast("视频已下载\n位置: " + localPath);
                return;
            }
            
            // 检查是否正在下载
            if (downloadManager.isDownloading(url)) {
                android.util.Log.d(TAG, "Video is already downloading");
                showToast("视频正在下载中");
                return;
            }
            
            // 开始下载
            downloadManager.startDownload(
                url,
                title != null ? title : "未命名视频",
                "OrangePlayer 视频下载"
            );
            
            // VideoDownloader 会在全局监听器中显示进度和结果 Toast
            // 这里只显示开始下载的提示
            showToast("开始下载视频");
        } catch (Exception e) {
            android.util.Log.e(TAG, "Download failed", e);
            showToast("下载失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // 下载点击监听器
    private OnDownloadClickListener mOnDownloadClickListener;
    
    /**
     * 设置下载点击监听器
     */
    public void setOnDownloadClickListener(OnDownloadClickListener listener) {
        mOnDownloadClickListener = listener;
    }
    
    /**
     * 下载点击监听器接口
     */
    public interface OnDownloadClickListener {
        void onDownloadClick(String url, String title);
    }
    
    // ==================== 截图功能 ====================
    
    /**
     * 截图按钮点击事件
     */
    private void onScreenshotClick() {
        if (mCurrentSetupDialog != null) {
            mCurrentSetupDialog.dismiss();
        }
        
        // 使用 ScreenshotManager 进行截图
        com.orange.playerlibrary.screenshot.ScreenshotManager screenshotManager = 
            new com.orange.playerlibrary.screenshot.ScreenshotManager(mContext, mVideoView);
        
        // 截图并保存到相册
        screenshotManager.takeAndSave(true, new com.orange.playerlibrary.screenshot.ScreenshotManager.SaveCallback() {
            @Override
            public void onSuccess(String filePath) {
                showToast("截图已保存");
            }
            
            @Override
            public void onError(String message) {
                showToast(message);
            }
        });
    }
    
    /**
     * 设置音量控制
     */
    private void setupVolumeControl(android.widget.SeekBar volumeSeekBar, android.widget.TextView volumeText) {
        if (volumeSeekBar == null) return;
        
        // 获取系统音量管理器
        android.media.AudioManager audioManager = (android.media.AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        
        // 获取当前音量和最大音量
        int maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        
        // 设置进度条最大值和当前值
        volumeSeekBar.setMax(maxVolume);
        volumeSeekBar.setProgress(currentVolume);
        
        // 更新音量文本
        if (volumeText != null) {
            int percent = (int) ((currentVolume * 100.0f) / maxVolume);
            volumeText.setText(percent + "%");
        }
        
        // 设置进度条监听器
        volumeSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    // 设置系统音量
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, progress, 0);
                    
                    // 更新音量文本
                    if (volumeText != null) {
                        int percent = (int) ((progress * 100.0f) / maxVolume);
                        volumeText.setText(percent + "%");
                    }
                }
            }
            
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                // 不需要处理
            }
            
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                // 不需要处理
            }
        });
    }
    
    /**
     * 设置播放模式选项
     */
    private void setupPlayModeButtons(android.widget.TextView sequentialBtn, 
                                     android.widget.TextView singleLoopBtn, 
                                     android.widget.TextView playPauseBtn) {
        if (sequentialBtn == null || singleLoopBtn == null || playPauseBtn == null) {
            return;
        }
        
        // 获取当前播放模式
        String currentMode = getPlayMode();
        if (currentMode == null || currentMode.isEmpty()) {
            currentMode = "sequential";
        }
        
        // 高亮当前模式
        sequentialBtn.setTextColor("sequential".equals(currentMode) ?
                COLOR_HIGHLIGHT : COLOR_NORMAL);
        singleLoopBtn.setTextColor("single_loop".equals(currentMode) ?
                COLOR_HIGHLIGHT : COLOR_NORMAL);
        playPauseBtn.setTextColor("play_pause".equals(currentMode) ?
                COLOR_HIGHLIGHT : COLOR_NORMAL);
        
        // 绑定点击事件
        sequentialBtn.setOnClickListener(v -> setPlayMode("sequential"));
        singleLoopBtn.setOnClickListener(v -> setPlayMode("single_loop"));
        playPauseBtn.setOnClickListener(v -> setPlayMode("play_pause"));
    }
    
    /**
     * 显示画面比例对话
     */
    private void showScreenScaleDialog() {
        if (mCurrentSetupDialog != null) {
            mCurrentSetupDialog.dismiss();
        }
        
        hideController();
        
        // 创建对话框
        View dialogView = View.inflate(mActivity, R.layout.speed_dialog, null);
        
        // 始终显示在右侧
        final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                DialogUtils.DialogPosition.RIGHT, null, null);
        
        // 设置画面比例选项
        setupScreenScaleOptions(dialogView, dialog);
    }
    
    /**
     * 设置画面比例选项
     */
    private void setupScreenScaleOptions(View dialogView, AlertDialog dialog) {
        RecyclerView recyclerView = dialogView.findViewById(R.id.recycler);
        if (recyclerView == null) return;
        
        ArrayList<HashMap<String, Object>> arrayList = new ArrayList<>();
        String[] scales = {"默认", "16:9", "4:3", "全屏裁剪", "全屏拉伸"};
        for (String scale : scales) {
            HashMap<String, Object> map = new HashMap<>();
            map.put("name", scale);
            arrayList.add(map);
        }
        
        // 当前选中的比例（使用私有变量）
        final String currentScale = mCurrentScreenScale;
        
        OrangeRecyclerView orangeRecyclerView = new OrangeRecyclerView();
        orangeRecyclerView.setLinearLayoutManager(recyclerView, mActivity);
        orangeRecyclerView.setAdapter(recyclerView, R.layout.speed_dialog_item, arrayList,
            (holder, data, position) -> {
                android.widget.TextView scaleName = holder.itemView.findViewById(R.id.title);
                String scaleText = data.get(position).get("name").toString();
                
                // 高亮当前比例
                if (scaleText.equals(currentScale)) {
                    scaleName.setTextColor(COLOR_HIGHLIGHT);
                    scaleName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
                } else {
                    scaleName.setTextColor(COLOR_NORMAL);
                    scaleName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
                }
                
                scaleName.setText(scaleText);
                
                // 比例选择事件
                scaleName.setOnClickListener(v -> {
                    // 保存到私有变量
                    mCurrentScreenScale = scaleText;
                    // 同步到会话比例（供 onPrepared 时使用）
                    mSettingsManager.setSessionVideoScale(scaleText);
                    // 立即应用到播放器
                    setScreenScaleType(scaleText);
                    showToast("画面比例: " + scaleText);
                    dialog.dismiss();
                });
            });
    }
    
    /**
     * 设置画面比例类型
     */
    private void setScreenScaleType(String scaleType) {
        // 立即应用到播放器（不保存到持久化存储）
        VideoScaleManager scaleManager = mVideoView.getVideoScaleManager();
        if (scaleManager != null) {
            scaleManager.applyScaleType(scaleType);
        }
    }
    
    /**
     * 显示长按倍速对话框
     */
    private void showLongPressSpeedDialog() {
        if (mCurrentSetupDialog != null) {
            mCurrentSetupDialog.dismiss();
        }
        
        hideController();
        
        // 创建对话框
        View dialogView = View.inflate(mActivity, R.layout.speed_dialog, null);
        
        // 始终显示在右侧
        final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                DialogUtils.DialogPosition.RIGHT, null, null);
        // 设置长按倍速选项
        setupLongPressSpeedOptions(dialogView, dialog);
    }
    
    /**
     * 设置长按倍速选项
     */
    private void setupLongPressSpeedOptions(View dialogView, AlertDialog dialog) {
        RecyclerView recyclerView = dialogView.findViewById(R.id.recycler);
        if (recyclerView == null) return;
        
        // 获取当前播放器内核
        String currentEngine = mSettingsManager.getPlayerEngine();
        boolean isIjkEngine = PlayerConstants.ENGINE_IJK.equals(currentEngine);
        
        // 根据内核设置长按倍速选项
        // IJK 内核：最高 2.0x（AudioTrack buffer 限制）
        // 其他内核：最高 3.0x
        String[] speeds;
        if (isIjkEngine) {
            speeds = new String[]{"1.5x", "1.75x", "2.0x"};
        } else {
            speeds = new String[]{"2.0x", "2.5x", "3.0x"};
        }
        
        ArrayList<HashMap<String, Object>> arrayList = new ArrayList<>();
        for (String speed : speeds) {
            HashMap<String, Object> map = new HashMap<>();
            map.put("name", speed);
            arrayList.add(map);
        }
        
        // 当前长按倍速（使用私有变量）
        final float currentSpeed = mCurrentLongPressSpeed;
        
        OrangeRecyclerView orangeRecyclerView = new OrangeRecyclerView();
        orangeRecyclerView.setLinearLayoutManager(recyclerView, mActivity);
        orangeRecyclerView.setAdapter(recyclerView, R.layout.speed_dialog_item, arrayList,
            (holder, data, position) -> {
                android.widget.TextView speedName = holder.itemView.findViewById(R.id.title);
                String speedText = data.get(position).get("name").toString();
                float speedValue = Float.parseFloat(speedText.replace("x", ""));
                
                // 高亮当前倍速
                if (Math.abs(speedValue - currentSpeed) < 0.01f) {
                    speedName.setTextColor(COLOR_HIGHLIGHT);
                    speedName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
                } else {
                    speedName.setTextColor(COLOR_NORMAL);
                    speedName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
                }
                
                speedName.setText(speedText);
                
                // 倍速选择事件
                speedName.setOnClickListener(v -> {
                    // 保存到私有变量
                    mCurrentLongPressSpeed = speedValue;
                    // 立即应用（更新 mLongPressSpeed）
                    setLongPressSpeed(speedValue);
                    showToast("长按倍数 " + speedText);
                    dialog.dismiss();
                });
            });
    }
    
    /**
     * 显示播放模式对话
     */
    private void showPlayModeDialog() {
        final String[] modes = {"顺序播放", "单集循环", "播放暂停"};
        final String[] modeValues = {"sequential", "single_loop", "play_pause"};
        
        // 获取当前播放模式
        String currentMode = getPlayMode();
        int checkedItem = 0;
        for (int i = 0; i < modeValues.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(mActivity);
        builder.setTitle("播放模式");
        builder.setSingleChoiceItems(modes, checkedItem, (dialog, which) -> {
            setPlayMode(modeValues[which]);
            dialog.dismiss();
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }
    
    /**
     * 显示播放引擎对话框
     */
    private void showEngineDialog() {
        final String[] engines = {"系统播放器", "IJK播放器", "EXO播放器"};
        final String[] engineValues = {
            PlayerConstants.ENGINE_DEFAULT,
            PlayerConstants.ENGINE_IJK,
            PlayerConstants.ENGINE_EXO
        };
        
        // 获取当前引擎
        String currentEngine = mSettingsManager.getPlayerEngine();
        int checkedItem = 0;
        for (int i = 0; i < engineValues.length; i++) {
            if (engineValues[i].equals(currentEngine)) {
                checkedItem = i;
                break;
            }
        }
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(mActivity);
        builder.setTitle("播放引擎");
        builder.setSingleChoiceItems(engines, checkedItem, (dialog, which) -> {
            mSettingsManager.setPlayerEngine(engineValues[which]);
            showToast("播放引擎: " + engines[which] + "\n重新播放生效");
            dialog.dismiss();
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }
    
    /**
     * 显示跳过片头片尾弹窗（使用skip_dialog_full布局）
     */
    public void showSkipDialog() {
        hideController();
        
        // 创建对话框视图
        View dialogView = View.inflate(mActivity, R.layout.skip_dialog_full, null);
        
        // 创建对话框 - 显示在右侧
        final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                DialogUtils.DialogPosition.RIGHT, null, null);
        
        // 获取对话框的根视图（DecorView）并设置触摸监听
        View decorView = dialog.getWindow().getDecorView();
        View shik = dialogView.findViewById(R.id.shik);
        
        decorView.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                if (shik != null) {
                    // 获取触摸位置
                    float x = event.getRawX();
                    float y = event.getRawY();
                    
                    // 获取内容区域的位置
                    int[] location = new int[2];
                    shik.getLocationOnScreen(location);
                    int left = location[0];
                    int top = location[1];
                    int right = left + shik.getWidth();
                    int bottom = top + shik.getHeight();
                    
                    // 如果点击在内容区域外，关闭对话框
                    if (x < left || x > right || y < top || y > bottom) {
                        dialog.dismiss();
                        return true;
                    }
                }
            }
            return false;
        });
        
        // 绑定跳过片头片尾的SeekBar
        bindSkipSeekBars(dialogView, dialog);
    }
    
    /**
     * 绑定跳过片头片尾的SeekBar
     */
    private void bindSkipSeekBars(View dialogView, AlertDialog dialog) {
        // 获取片头SeekBar和文本
        android.widget.SeekBar seekBarPt = dialogView.findViewById(R.id.seekBarpt);
        android.widget.TextView namePt = dialogView.findViewById(R.id.name);
        
        // 获取片尾SeekBar和文本
        android.widget.SeekBar seekBarPw = dialogView.findViewById(R.id.seekBarpw);
        android.widget.TextView namePw = dialogView.findViewById(R.id.wb2);
        
        // 获取当前设置值
        int skipOpening = mSettingsManager.getSkipOpening();
        int skipEnding = mSettingsManager.getSkipEnding();
        
        // 设置片头SeekBar
        if (seekBarPt != null) {
            // 最大值设为180秒（3分钟）
            seekBarPt.setMax(180000);
            // 使用私有变量（与设置弹窗同步）
            seekBarPt.setProgress(mCurrentSkipOpening);
            
            if (namePt != null) {
                namePt.setText(formatSkipTime(mCurrentSkipOpening));
            }
            
            seekBarPt.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && namePt != null) {
                        namePt.setText(formatSkipTime(progress));
                    }
                }
                
                @Override
                public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                
                @Override
                public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                    int progress = seekBar.getProgress();
                    // 同步更新私有变量
                    mCurrentSkipOpening = progress;
                    // 应用到播放器
                    if (mVideoView != null) {
                        mVideoView.setSkipIntroTime(progress);
                        mVideoView.setSkipIntroEnabled(progress > 0);
                    }
                    showToast("跳过片头: " + formatSkipTime(progress));
                }
            });
        }
        
        // 设置片尾SeekBar
        if (seekBarPw != null) {
            // 最大值设为180秒（3分钟）
            seekBarPw.setMax(180000);
            // 使用私有变量（与设置弹窗同步）
            seekBarPw.setProgress(mCurrentSkipEnding);
            
            if (namePw != null) {
                namePw.setText(formatSkipTime(mCurrentSkipEnding));
            }
            
            seekBarPw.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && namePw != null) {
                        namePw.setText(formatSkipTime(progress));
                    }
                }
                
                @Override
                public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                
                @Override
                public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                    int progress = seekBar.getProgress();
                    // 同步更新私有变量
                    mCurrentSkipEnding = progress;
                    // 应用到播放器
                    if (mVideoView != null) {
                        mVideoView.setSkipOutroTime(progress);
                        mVideoView.setSkipOutroEnabled(progress > 0);
                    }
                    showToast("跳过片尾: " + formatSkipTime(progress));
                }
            });
        }
    }
    
    /**
     * 格式化跳过时间显示
     */
    private String formatSkipTime(int milliseconds) {
        if (milliseconds <= 0) {
            return "不跳过";
        }
        int seconds = milliseconds / 1000;
        if (seconds < 60) {
            return seconds + "秒";
        } else {
            int minutes = seconds / 60;
            int remainingSeconds = seconds % 60;
            if (remainingSeconds == 0) {
                return minutes + "分钟";
            } else {
                return minutes + "分" + remainingSeconds + "秒";
            }
        }
    }
    
    /**
     * 显示跳过片头对话框
     */
    private void showSkipOpeningDialog() {
        if (mCurrentSetupDialog != null) {
            mCurrentSetupDialog.dismiss();
        }
        
        hideController();
        
        // 创建对话框
        View dialogView = View.inflate(mActivity, R.layout.speed_dialog, null);
        
        // 始终显示在右侧
        final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                DialogUtils.DialogPosition.RIGHT, null, null);
        
        // 设置跳过片头选项
        setupSkipOpeningOptions(dialogView, dialog);
    }
    
    /**
     * 设置跳过片头选项
     */
    private void setupSkipOpeningOptions(View dialogView, AlertDialog dialog) {
        RecyclerView recyclerView = dialogView.findViewById(R.id.recycler);
        if (recyclerView == null) return;
        
        ArrayList<HashMap<String, Object>> arrayList = new ArrayList<>();
        String[] options = {"不跳过", "15秒", "30秒", "60秒", "90秒"};
        for (String option : options) {
            HashMap<String, Object> map = new HashMap<>();
            map.put("name", option);
            arrayList.add(map);
        }
        
        // 当前设置（使用私有变量）
        final int currentValue = mCurrentSkipOpening;
        
        OrangeRecyclerView orangeRecyclerView = new OrangeRecyclerView();
        orangeRecyclerView.setLinearLayoutManager(recyclerView, mActivity);
        orangeRecyclerView.setAdapter(recyclerView, R.layout.speed_dialog_item, arrayList,
            (holder, data, position) -> {
                android.widget.TextView title = holder.itemView.findViewById(R.id.title);
                String optionText = data.get(position).get("name").toString();
                
                // 计算秒数
                int seconds = 0;
                if (!optionText.equals("不跳过")) {
                    seconds = Integer.parseInt(optionText.replace("秒", "")) * 1000;
                }
                
                // 高亮当前选项
                if (seconds == currentValue) {
                    title.setTextColor(COLOR_HIGHLIGHT);
                    title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
                } else {
                    title.setTextColor(COLOR_NORMAL);
                    title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
                }
                
                title.setText(optionText);
                
                // 设置点击事件
                final int finalSeconds = seconds;
                title.setOnClickListener(v -> {
                    // 保存到私有变量
                    mCurrentSkipOpening = finalSeconds;
                    android.util.Log.d("VideoEventManager", "User selected skip opening: " + finalSeconds + "ms");
                    // 立即应用到播放器
                    if (mVideoView != null) {
                        mVideoView.setSkipIntroTime(finalSeconds);
                        mVideoView.setSkipIntroEnabled(finalSeconds > 0);
                        android.util.Log.d("VideoEventManager", "Applied skip opening to player");
                    }
                    showToast("跳过片头: " + optionText);
                    dialog.dismiss();
                });
            });
    }
    
    /**
     * 显示跳过片尾对话框
     */
    private void showSkipEndingDialog() {
        if (mCurrentSetupDialog != null) {
            mCurrentSetupDialog.dismiss();
        }
        
        hideController();
        
        // 创建对话框
        View dialogView = View.inflate(mActivity, R.layout.speed_dialog, null);
        
        // 始终显示在右侧
        final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                DialogUtils.DialogPosition.RIGHT, null, null);
        
        // 设置跳过片尾选项
        setupSkipEndingOptions(dialogView, dialog);
    }
    
    /**
     * 设置跳过片尾选项
     */
    private void setupSkipEndingOptions(View dialogView, AlertDialog dialog) {
        RecyclerView recyclerView = dialogView.findViewById(R.id.recycler);
        if (recyclerView == null) return;
        
        ArrayList<HashMap<String, Object>> arrayList = new ArrayList<>();
        String[] options = {"不跳过", "15秒", "30秒", "60秒", "90秒"};
        for (String option : options) {
            HashMap<String, Object> map = new HashMap<>();
            map.put("name", option);
            arrayList.add(map);
        }
        
        // 当前设置（使用私有变量）
        final int currentValue = mCurrentSkipEnding;
        
        OrangeRecyclerView orangeRecyclerView = new OrangeRecyclerView();
        orangeRecyclerView.setLinearLayoutManager(recyclerView, mActivity);
        orangeRecyclerView.setAdapter(recyclerView, R.layout.speed_dialog_item, arrayList,
            (holder, data, position) -> {
                android.widget.TextView title = holder.itemView.findViewById(R.id.title);
                String optionText = data.get(position).get("name").toString();
                
                // 计算秒数
                int seconds = 0;
                if (!optionText.equals("不跳过")) {
                    seconds = Integer.parseInt(optionText.replace("秒", "")) * 1000;
                }
                
                // 高亮当前选项
                if (seconds == currentValue) {
                    title.setTextColor(COLOR_HIGHLIGHT);
                    title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
                } else {
                    title.setTextColor(COLOR_NORMAL);
                    title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
                }
                
                title.setText(optionText);
                
                // 设置点击事件
                final int finalSeconds = seconds;
                title.setOnClickListener(v -> {
                    // 保存到私有变量
                    mCurrentSkipEnding = finalSeconds;
                    // 立即应用到播放器
                    if (mVideoView != null) {
                        mVideoView.setSkipOutroTime(finalSeconds);
                        mVideoView.setSkipOutroEnabled(finalSeconds > 0);
                    }
                    showToast("跳过片尾: " + optionText);
                    dialog.dismiss();
                });
            });
    }
    
    // ==================== 选集功能 ====================
    
    // 排序状态
    private boolean mIsSortAscending = true; // 默认正序
    private boolean mIsSmartMode = false; // 默认模式
    
    /**
     * 显示选集列表
     */
    public void showPlaylistDialog() {
        final ArrayList<HashMap<String, Object>> originalList = mController.getVideoList();
        if (originalList == null || originalList.isEmpty()) {
            showToast("暂无选集");
            return;
        }
        
        hideController();
        
        try {
            // 创建对话框视图
            View dialogView = View.inflate(mActivity, R.layout.playliset, null);
            
            final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView, 
                    DialogUtils.DialogPosition.RIGHT, null, null);
            
            // 点击外部区域关闭对话框
            View playListView = dialogView.findViewById(R.id.playLiset_v);
            if (playListView != null) {
                playListView.setOnClickListener(v -> dialog.dismiss());
            }
            
            // 获取排序和模式按钮
            TextView sortBtn = dialogView.findViewById(R.id.shorts);
            TextView modeBtn = dialogView.findViewById(R.id.mode);
            
            // 初始化按钮状态
            if (sortBtn != null) sortBtn.setText(mIsSortAscending ? "正序" : "倒序");
            if (modeBtn != null) modeBtn.setText(mIsSmartMode ? "智能" : "默认");
            
            // 创建用于显示的列表副本
            final ArrayList<HashMap<String, Object>> displayList = new ArrayList<>(originalList);
            
            // 模式按钮点击事件
            if (modeBtn != null) {
                final View finalDialogView = dialogView;
                modeBtn.setOnClickListener(v -> {
                    mIsSmartMode = !mIsSmartMode;
                    modeBtn.setText(mIsSmartMode ? "智能" : "默认");
                    
                    // 重置显示列表
                    displayList.clear();
                    displayList.addAll(originalList);
                    
                    if (mIsSmartMode && !mIsSortAscending) {
                        sortVideoList(displayList);
                    } else if (!mIsSmartMode && !mIsSortAscending) {
                        java.util.Collections.reverse(displayList);
                    }
                    
                    refreshPlaylistRecyclerView(finalDialogView, dialog, displayList);
                });
            }
            
            // 排序按钮点击事件
            if (sortBtn != null) {
                final View finalDialogView2 = dialogView;
                sortBtn.setOnClickListener(v -> {
                    mIsSortAscending = !mIsSortAscending;
                    sortBtn.setText(mIsSortAscending ? "正序" : "倒序");
                    
                    if (mIsSmartMode) {
                        sortVideoList(displayList);
                    } else {
                        displayList.clear();
                        displayList.addAll(originalList);
                        if (!mIsSortAscending) {
                            java.util.Collections.reverse(displayList);
                        }
                    }
                    
                    refreshPlaylistRecyclerView(finalDialogView2, dialog, displayList);
                });
            }
            
            // 初始显示
            if (mIsSmartMode) {
                sortVideoList(displayList);
            } else if (!mIsSortAscending) {
                java.util.Collections.reverse(displayList);
            }
            
            refreshPlaylistRecyclerView(dialogView, dialog, displayList);
            
        } catch (Exception e) {
        }
    }
    
    /**
     * 刷新选集列表
     */
    private void refreshPlaylistRecyclerView(View dialogView, AlertDialog dialog, ArrayList<HashMap<String, Object>> dataList) {
        RecyclerView recyclerView = dialogView.findViewById(R.id.recycler);
        if (recyclerView == null) return;
        
        OrangeRecyclerView orangeRecyclerView = new OrangeRecyclerView();
        orangeRecyclerView.setGridLayoutManager(recyclerView, mActivity, 4);
        orangeRecyclerView.setAdapter(recyclerView, R.layout.playliset_item, dataList,
            (holder, data, position) -> bindPlaylistItemView(holder, data, position, dialog));
    }
    
    /**
     * 绑定选集列表项
     */
    private void bindPlaylistItemView(OrangeRecyclerViewAdapter.ViewHolder holder,
                                      ArrayList<HashMap<String, Object>> dataList,
                                      int position, AlertDialog dialog) {
        HashMap<String, Object> itemData = dataList.get(position);
        android.widget.TextView titleTv = holder.itemView.findViewById(R.id.title);
        
        // 绑定标题
        String title = itemData.get("name") != null ? itemData.get("name").toString() : "第" + (position + 1) + "集";
        titleTv.setText(title);

        // 高亮当前播放集数：用描边+浅填充卡片区分，字号保持稳定
        String currentUrl = mVideoView.getUrl();
        String itemUrl = itemData.get("url") != null ? itemData.get("url").toString() : "";
        boolean isCurrent = currentUrl != null && currentUrl.equals(itemUrl);
        titleTv.setSelected(isCurrent);
        titleTv.setTextColor(isCurrent ? COLOR_HIGHLIGHT : COLOR_NORMAL);
        
        // 点击事件
        titleTv.setOnClickListener(v -> {
            playEpisodeFromList(itemData);
            dialog.dismiss();
        });
    }
    
    /**
     * 从列表数据播放指定集数
     */
    private void playEpisodeFromList(HashMap<String, Object> item) {
        String url = item.get("url") != null ? item.get("url").toString() : "";
        String name = item.get("name") != null ? item.get("name").toString() : "";
        
        if (url.isEmpty()) {
            showToast("播放地址异常");
            return;
        }
        
        // 直接设置标题，不拼接
        mController.setVideoTitle(name);
        
        // 播放视频
        @SuppressWarnings("unchecked")
        HashMap<String, String> headers = (HashMap<String, String>) item.get("headers");
        mVideoView.setUrl(url, headers);
        mVideoView.release();
        mVideoView.startPlayLogic();
    }
    
    /**
     * 视频列表排序（智能排序）
     */
    private void sortVideoList(ArrayList<HashMap<String, Object>> dataList) {
        if (dataList == null || dataList.isEmpty()) return;
        
        java.util.Collections.sort(dataList, (map1, map2) -> {
            String name1 = map1.get("name") != null ? map1.get("name").toString() : "";
            String name2 = map2.get("name") != null ? map2.get("name").toString() : "";
            
            Integer num1 = extractNumber(name1);
            Integer num2 = extractNumber(name2);
            
            if (num1 != null && num2 != null) {
                int numCompare = Integer.compare(num1, num2);
                return mIsSortAscending ? numCompare : -numCompare;
            } else if (num1 != null) {
                return -1;
            } else if (num2 != null) {
                return 1;
            } else {
                int strCompare = name1.compareTo(name2);
                return mIsSortAscending ? strCompare : -strCompare;
            }
        });
    }
    
    /**
     * 从字符串中提取数字
     */
    private Integer extractNumber(String str) {
        if (str == null || str.isEmpty()) return null;
        
        // 尝试匹配阿拉伯数字
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(str);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group());
            } catch (NumberFormatException e) {
                // 忽略
            }
        }
        return null;
    }
    
    /**
     * 播放指定集数
     */
    private void playEpisode(int index) {
        ArrayList<HashMap<String, Object>> videoList = mController.getVideoList();
        if (videoList == null || index < 0 || index >= videoList.size()) {
            return;
        }
        
        HashMap<String, Object> item = videoList.get(index);
        String url = item.get("url") != null ? item.get("url").toString() : "";
        String name = item.get("name") != null ? item.get("name").toString() : "";
        
        if (url.isEmpty()) {
            showToast("播放地址异常");
            return;
        }
        
        // 直接设置标题，不拼接
        mController.setVideoTitle(name);
        
        // 播放视频
        @SuppressWarnings("unchecked")
        HashMap<String, String> headers = (HashMap<String, String>) item.get("headers");
        mVideoView.setUrl(url, headers);
        mVideoView.release();
        mVideoView.startPlayLogic();
    }
    
    /**
     * 播放下一集
     */
    public void playNextEpisode() {
        ArrayList<HashMap<String, Object>> videoList = mController.getVideoList();
        if (videoList == null || videoList.isEmpty()) {
            return;
        }
        
        String currentUrl = mVideoView.getUrl();
        int currentIndex = -1;
        
        for (int i = 0; i < videoList.size(); i++) {
            String url = videoList.get(i).get("url") != null ? videoList.get(i).get("url").toString() : "";
            if (url.equals(currentUrl)) {
                currentIndex = i;
                break;
            }
        }
        
        if (currentIndex >= 0 && currentIndex < videoList.size() - 1) {
            playEpisode(currentIndex + 1);
        } else {
            showToast("已经是最后一集了");
        }
    }
    
    /**
     * 检查是否有下一集
     */
    public boolean hasNextEpisode() {
        ArrayList<HashMap<String, Object>> videoList = mController.getVideoList();
        if (videoList == null || videoList.isEmpty()) {
            return false;
        }
        
        String currentUrl = mVideoView.getUrl();
        int currentIndex = -1;
        
        for (int i = 0; i < videoList.size(); i++) {
            String url = videoList.get(i).get("url") != null ? videoList.get(i).get("url").toString() : "";
            if (url.equals(currentUrl)) {
                currentIndex = i;
                break;
            }
        }
        
        return currentIndex >= 0 && currentIndex < videoList.size() - 1;
    }
    
    // ==================== 播放完成处理 ====================
    
    /**
     * 处理播放完成事件
     */
    public void handlePlaybackCompleted() {
        String currentMode = getPlayMode();
        
        switch (currentMode) {
            case "sequential": // 顺序播放
                if (hasNextEpisode()) {
                    playNextEpisode();
                }
                break;
                
            case "single_loop": // 单集循环
                // 重新播放当前视频
                mVideoView.seekTo(0);
                mVideoView.startPlayLogic();
                break;
                
            case "play_pause": // 播放暂停
                // 不做任何操作
                break;
        }
    }
    
    // ==================== 弹幕功能 ====================
    
    /**
     * 切换弹幕开关
     * @param clickedView 被点击的View，用于找到正确的VodControlView
     */
    private void toggleDanmaku(View clickedView) {
        // 检查弹幕库是否可用
        if (!DanmakuHelper.isDanmakuLibraryAvailable()) {
            DanmakuHelper.showDanmakuNotAvailableToast(mContext);
            return;
        }
        boolean currentState = mSettingsManager.isDanmakuEnabled();
        boolean newState = !currentState;
        // 保存设置
        mSettingsManager.setDanmakuEnabled(newState);
        // 从点击的View向上找到VodControlView
        VodControlView actualVodControlView = findParentVodControlView(clickedView);
        if (actualVodControlView != null) {
            actualVodControlView.updateDanmakuToggleState(newState);
        } else {
        }
        
        // 通知外部监听器（如果有DanmaView组件）
        if (mOnDanmakuStateChangeListener != null) {
            mOnDanmakuStateChangeListener.onDanmakuStateChanged(newState);
        }
        
        // 通知弹幕控制器
        if (mController != null && mController.getDanmakuController() != null) {
            mController.getDanmakuController().setDanmakuEnabled(newState);
        }
        
        showToast(newState ? "弹幕已开启" : "弹幕已关闭");
    }
    
    /**
     * 从View向上遍历找到父VodControlView
     */
    private VodControlView findParentVodControlView(View view) {
        if (view == null) return null;
        
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
     * 获取当前实际显示的VodControlView
     * 如果是全屏模式，返回全屏播放器的VodControlView
     */
    private VodControlView getActualVodControlView() {
        // 首先尝试获取全屏播放器的VodControlView
        if (mActivity != null) {
            android.view.ViewGroup vp = (android.view.ViewGroup) mActivity.findViewById(android.view.Window.ID_ANDROID_CONTENT);
            if (vp != null) {
                android.view.View fullView = vp.findViewById(com.shuyu.gsyvideoplayer.GSYVideoManager.FULLSCREEN_ID);
                if (fullView instanceof OrangevideoView) {
                    OrangevideoView fullPlayer = (OrangevideoView) fullView;
                    VodControlView fullVodControlView = fullPlayer.getVodControlView();
                    if (fullVodControlView != null) {
                        return fullVodControlView;
                    }
                }
            }
        }
        
        // 如果没有全屏播放器，返回当前绑定的VodControlView
        return mVodControlView;
    }
    
    /**
     * 获取当前实际显示的 Controller
     * 如果是全屏模式，返回全屏播放器的 Controller
     */
    private OrangeVideoController getActualController() {
        if (mActivity != null) {
            android.view.ViewGroup vp = (android.view.ViewGroup) mActivity.findViewById(android.view.Window.ID_ANDROID_CONTENT);
            if (vp != null) {
                android.view.View fullView = vp.findViewById(com.shuyu.gsyvideoplayer.GSYVideoManager.FULLSCREEN_ID);
                if (fullView instanceof OrangevideoView) {
                    OrangevideoView fullPlayer = (OrangevideoView) fullView;
                    OrangeVideoController fullController = fullPlayer.getVideoController();
                    if (fullController != null) {
                        return fullController;
                    }
                }
            }
        }
        return mController;
    }
    
    /**
     * 隐藏当前活动的控制器
     * 通过 Controller 的 hide() 方法隐藏，确保状态同步
     */
    private void hideController() {
        Log.d(TAG, "hideController() called");
        
        // 方法1：尝试通过全屏播放器获取 Controller
        OrangeStandardVideoController controller = findActualStandardController();
        Log.d(TAG, "hideController: findActualStandardController returned " + controller);
        
        if (controller != null) {
            Log.d(TAG, "hideController: calling controller.hide() on fullscreen controller");
            controller.hide();
            controller.stopFadeOut();
            return;
        }
        
        // 方法2：如果找不到，使用原始 Controller
        Log.d(TAG, "hideController: using mController=" + mController);
        if (mController != null) {
            mController.hide();
            mController.stopFadeOut();
        }
    }
    
    /**
     * 查找当前实际显示的 OrangeStandardVideoController
     * 全屏模式下会遍历全屏播放器的子 View 找到 Controller
     */
    private OrangeStandardVideoController findActualStandardController() {
        if (mActivity == null) {
            Log.d(TAG, "findActualStandardController: mActivity is null");
            return null;
        }
        
        android.view.ViewGroup vp = (android.view.ViewGroup) mActivity.findViewById(android.view.Window.ID_ANDROID_CONTENT);
        if (vp == null) {
            Log.d(TAG, "findActualStandardController: content view is null");
            return null;
        }
        
        android.view.View fullView = vp.findViewById(com.shuyu.gsyvideoplayer.GSYVideoManager.FULLSCREEN_ID);
        Log.d(TAG, "findActualStandardController: fullView=" + fullView);
        
        if (fullView instanceof android.view.ViewGroup) {
            // 遍历全屏播放器的子 View 找到 OrangeStandardVideoController
            OrangeStandardVideoController found = findControllerInViewGroup((android.view.ViewGroup) fullView);
            Log.d(TAG, "findActualStandardController: found controller=" + found);
            return found;
        }
        
        return null;
    }
    
    /**
     * 在 ViewGroup 中递归查找 OrangeStandardVideoController
     */
    private OrangeStandardVideoController findControllerInViewGroup(android.view.ViewGroup viewGroup) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            android.view.View child = viewGroup.getChildAt(i);
            Log.d(TAG, "findControllerInViewGroup: checking child " + i + ": " + child.getClass().getSimpleName());
            if (child instanceof OrangeStandardVideoController) {
                return (OrangeStandardVideoController) child;
            }
            if (child instanceof android.view.ViewGroup) {
                OrangeStandardVideoController found = findControllerInViewGroup((android.view.ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
    
    /**
     * 显示弹幕输入对话框
     */
    private void showDanmakuInputDialog() {
        // 检查弹幕库是否可用
        if (!DanmakuHelper.isDanmakuLibraryAvailable()) {
            DanmakuHelper.showDanmakuNotAvailableToast(mContext);
            return;
        }
        
        // 使用DanmuexitDialog显示弹幕发送界面
        com.orange.playerlibrary.tool.DanmuexitDialog danmuDialog = 
            new com.orange.playerlibrary.tool.DanmuexitDialog();
        
        // 设置发送监听器
        com.orange.playerlibrary.tool.DanmuexitDialog.setDanmuSendListener((text, color) -> {
            // 通知外部发送弹幕
            if (mOnDanmakuSendListener != null) {
                mOnDanmakuSendListener.onDanmakuSend(text, color);
            }
            
            // 通知弹幕控制器发送弹幕
            if (mController != null && mController.getDanmakuController() != null) {
                mController.getDanmakuController().sendDanmaku(text, color);
            }
            
            showToast("弹幕已发送");
        });
        
        // 显示对话框
        danmuDialog.show(mActivity);
    }
    
    /**
     * 显示弹幕设置对话框
     */
    private void showDanmakuSettingsDialog() {
        // 检查弹幕库是否可用
        if (!DanmakuHelper.isDanmakuLibraryAvailable()) {
            DanmakuHelper.showDanmakuNotAvailableToast(mContext);
            return;
        }
        
        hideController(); // 隐藏播放器UI
        
        // 创建对话框视图
        View dialogView = View.inflate(mActivity, R.layout.danmuset_dialog_full, null);
        
        // 创建对话框 - 显示在右侧
        final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                DialogUtils.DialogPosition.RIGHT, null, null);
        
        // 在 DecorView 上设置触摸监听，点击空白区域关闭对话框
        View decorView = dialog.getWindow().getDecorView();
        View shik = dialogView.findViewById(R.id.shik);
        
        decorView.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                if (shik != null) {
                    float x = event.getRawX();
                    float y = event.getRawY();
                    
                    int[] location = new int[2];
                    shik.getLocationOnScreen(location);
                    int left = location[0];
                    int top = location[1];
                    int right = left + shik.getWidth();
                    int bottom = top + shik.getHeight();
                    
                    if (x < left || x > right || y < top || y > bottom) {
                        dialog.dismiss();
                        return true;
                    }
                }
            }
            return false;
        });
        
        // 绑定弹幕设置选项
        bindDanmakuSettings(dialogView, dialog);
    }
    
    /**
     * 绑定弹幕设置选项
     */
    private void bindDanmakuSettings(View dialogView, AlertDialog dialog) {
        // 获取SeekBar控件
        android.widget.SeekBar sizeBar = dialogView.findViewById(R.id.ai_sizebar);
        android.widget.SeekBar speedBar = dialogView.findViewById(R.id.ai_speedbar);
        android.widget.SeekBar alphaBar = dialogView.findViewById(R.id.ai_alphabar);
        
        android.widget.TextView sizeText = dialogView.findViewById(R.id.ai_size);
        android.widget.TextView speedText = dialogView.findViewById(R.id.ai_speed);
        android.widget.TextView alphaText = dialogView.findViewById(R.id.ai_alpha);
        
        // 获取当前设置
        float currentSize = mSettingsManager.getDanmakuTextSize();
        float currentSpeed = mSettingsManager.getDanmakuSpeed();
        float currentAlpha = mSettingsManager.getDanmakuAlpha();
        
        // 设置初始值（范围：文字大小10-30sp，速度0.5-3.0倍，透明度0-100%）
        if (sizeBar != null) {
            int progress = (int) ((currentSize - 10) / 20 * 100);
            sizeBar.setProgress(progress);
            if (sizeText != null) {
                sizeText.setText("弹幕文字大小: " + String.format("%.0f", currentSize) + "sp");
            }
            
            sizeBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        float size = 10 + (progress / 100f) * 20; // 10-30sp
                        if (sizeText != null) {
                            sizeText.setText("弹幕文字大小: " + String.format("%.0f", size) + "sp");
                        }
                    }
                }
                
                @Override
                public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                
                @Override
                public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                    float size = 10 + (seekBar.getProgress() / 100f) * 20;
                    mSettingsManager.setDanmakuTextSize(size);
                    if (mOnDanmakuSettingsChangeListener != null) {
                        mOnDanmakuSettingsChangeListener.onTextSizeChanged(size);
                    }
                    // 通知弹幕控制器
                    if (mController != null && mController.getDanmakuController() != null) {
                        mController.getDanmakuController().setDanmakuTextSize(size);
                    }
                    showToast("弹幕文字大小: " + String.format("%.0f", size) + "sp");
                }
            });
        }
        
        if (speedBar != null) {
            int progress = (int) ((currentSpeed - 0.5f) / 2.5f * 100);
            speedBar.setProgress(progress);
            if (speedText != null) {
                speedText.setText("弹幕播放速度: " + String.format("%.1f", currentSpeed) + "x");
            }
            
            speedBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        float speed = 0.5f + (progress / 100f) * 2.5f; // 0.5-3.0x
                        if (speedText != null) {
                            speedText.setText("弹幕播放速度: " + String.format("%.1f", speed) + "x");
                        }
                    }
                }
                
                @Override
                public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                
                @Override
                public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                    float speed = 0.5f + (seekBar.getProgress() / 100f) * 2.5f;
                    mSettingsManager.setDanmakuSpeed(speed);
                    if (mOnDanmakuSettingsChangeListener != null) {
                        mOnDanmakuSettingsChangeListener.onSpeedChanged(speed);
                    }
                    // 通知弹幕控制器
                    if (mController != null && mController.getDanmakuController() != null) {
                        mController.getDanmakuController().setDanmakuSpeed(speed);
                    }
                    showToast("弹幕速度: " + String.format("%.1f", speed) + "x");
                }
            });
        }
        
        if (alphaBar != null) {
            int progress = (int) (currentAlpha * 100);
            alphaBar.setProgress(progress);
            if (alphaText != null) {
                alphaText.setText("弹幕透明度: " + progress + "%");
            }
            
            alphaBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && alphaText != null) {
                        alphaText.setText("弹幕透明度: " + progress + "%");
                    }
                }
                
                @Override
                public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                
                @Override
                public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                    float alpha = seekBar.getProgress() / 100f;
                    mSettingsManager.setDanmakuAlpha(alpha);
                    if (mOnDanmakuSettingsChangeListener != null) {
                        mOnDanmakuSettingsChangeListener.onAlphaChanged(alpha);
                    }
                    // 通知弹幕控制器
                    if (mController != null && mController.getDanmakuController() != null) {
                        mController.getDanmakuController().setDanmakuAlpha(alpha);
                    }
                    showToast("弹幕透明度: " + seekBar.getProgress() + "%");
                }
            });
        }
    }
    
    // 弹幕状态变化监听器
    private OnDanmakuStateChangeListener mOnDanmakuStateChangeListener;
    private OnDanmakuSettingsChangeListener mOnDanmakuSettingsChangeListener;
    private OnDanmakuSendListener mOnDanmakuSendListener;
    
    /**
     * 设置弹幕状态变化监听器
     */
    public void setOnDanmakuStateChangeListener(OnDanmakuStateChangeListener listener) {
        mOnDanmakuStateChangeListener = listener;
    }
    
    /**
     * 设置弹幕设置变化监听器
     */
    public void setOnDanmakuSettingsChangeListener(OnDanmakuSettingsChangeListener listener) {
        mOnDanmakuSettingsChangeListener = listener;
    }
    
    /**
     * 设置弹幕发送监听器
     */
    public void setOnDanmakuSendListener(OnDanmakuSendListener listener) {
        mOnDanmakuSendListener = listener;
    }
    
    /**
     * 弹幕状态变化监听器接口
     */
    public interface OnDanmakuStateChangeListener {
        void onDanmakuStateChanged(boolean enabled);
    }
    
    /**
     * 弹幕设置变化监听器接口
     */
    public interface OnDanmakuSettingsChangeListener {
        void onTextSizeChanged(float size);
        void onSpeedChanged(float speed);
        void onAlphaChanged(float alpha);
    }
    
    /**
     * 弹幕发送监听器接口
     */
    public interface OnDanmakuSendListener {
        void onDanmakuSend(String text, int color);
    }
    
    // ===== 字幕功能 =====
    
    /**
     * 显示字幕对话框
     * 按照 steering rules，从点击的 View 向上遍历找到正确的父组件
     */
    private void showSubtitleDialog(View clickedView) {
        hideController();
        
        // 从点击的 View 找到实际的 VodControlView（全屏模式下需要）
        VodControlView actualVodControlView = findParentVodControlView(clickedView);
        
        try {
            View dialogView = View.inflate(mActivity, R.layout.subtitle_dialog, null);
            
            final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                    DialogUtils.DialogPosition.RIGHT, null, null);
            
            // 点击外部关闭
            View layout = dialogView.findViewById(R.id.layout);
            if (layout != null) {
                layout.setOnClickListener(v -> dialog.dismiss());
            }
            
            // 字幕开关
            android.widget.Switch subtitleSwitch = dialogView.findViewById(R.id.subtitle_switch);
            if (subtitleSwitch != null) {
                subtitleSwitch.setChecked(mController.isSubtitleEnabled());
                subtitleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    mSettingsManager.setSubtitleEnabled(isChecked);
                    if (isChecked) {
                        mController.getSubtitleManager().show();
                        mController.startSubtitle();
                    } else {
                        mController.getSubtitleManager().hide();
                        mController.stopSubtitle();
                        // 同时停止 OCR 翻译
                        stopOcrTranslate();
                    }
                    // 更新按钮状态
                    if (actualVodControlView != null) {
                        actualVodControlView.updateSubtitleToggleState(isChecked);
                    }
                });
            }
            
            // 加载本地字幕按钮
            View btnLoadLocal = dialogView.findViewById(R.id.btn_load_local);
            if (btnLoadLocal != null) {
                btnLoadLocal.setOnClickListener(v -> {
                    dialog.dismiss();
                    showSubtitleFilePicker();
                });
            }
            
            // 加载网络字幕按钮
            View btnLoadUrl = dialogView.findViewById(R.id.btn_load_url);
            if (btnLoadUrl != null) {
                btnLoadUrl.setOnClickListener(v -> {
                    dialog.dismiss();
                    showSubtitleUrlInput();
                });
            }
            
            // 字幕大小调节
            android.widget.SeekBar sizeBar = dialogView.findViewById(R.id.subtitle_size_bar);
            android.widget.TextView sizeText = dialogView.findViewById(R.id.subtitle_size_text);
            if (sizeBar != null) {
                // 读取持久化的字幕大小（默认 18sp，与 PlayerSettingsManager 统一）
                float defaultSize = mSettingsManager.getSubtitleSize();
                int defaultProgress = (int) ((defaultSize - 12) / 24 * 100);
                sizeBar.setProgress(Math.max(0, Math.min(100, defaultProgress)));
                if (sizeText != null) {
                    sizeText.setText("字幕大小: " + (int) defaultSize + "sp");
                }

                sizeBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser) {
                            float size = 12 + (progress / 100f) * 24; // 12-36sp
                            if (sizeText != null) {
                                sizeText.setText("字幕大小: " + (int) size + "sp");
                            }
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                        float size = 12 + (seekBar.getProgress() / 100f) * 24;
                        mController.getSubtitleManager().setTextSize(size);
                        // 持久化字幕大小
                        mSettingsManager.setSubtitleSize(size);
                        showToast("字幕大小: " + (int) size + "sp");
                    }
                });
            }

            // 字幕延迟调节（步进 500ms；正值延后显示）
            android.widget.TextView delayText = dialogView.findViewById(R.id.subtitle_delay_text);
            View btnDelayMinus = dialogView.findViewById(R.id.btn_subtitle_delay_minus);
            View btnDelayReset = dialogView.findViewById(R.id.btn_subtitle_delay_reset);
            View btnDelayPlus = dialogView.findViewById(R.id.btn_subtitle_delay_plus);
            final long[] currentDelay = {
                    mController.getSubtitleManager() != null
                            ? mController.getSubtitleManager().getSubtitleDelayMs() : 0L};
            if (delayText != null) {
                updateDelayText(delayText, currentDelay[0]);
            }
            View.OnClickListener delayClickListener = v -> {
                long delta = v.getId() == R.id.btn_subtitle_delay_plus ? 500
                        : v.getId() == R.id.btn_subtitle_delay_minus ? -500 : -currentDelay[0];
                currentDelay[0] += delta;
                applySubtitleDelay(currentDelay[0]);
                if (delayText != null) {
                    updateDelayText(delayText, currentDelay[0]);
                }
            };
            if (btnDelayMinus != null) btnDelayMinus.setOnClickListener(delayClickListener);
            if (btnDelayReset != null) btnDelayReset.setOnClickListener(delayClickListener);
            if (btnDelayPlus != null) btnDelayPlus.setOnClickListener(delayClickListener);
            
            // 显示当前字幕状态
            android.widget.TextView statusText = dialogView.findViewById(R.id.subtitle_status);
            if (statusText != null) {
                if (mController.isSubtitleLoaded()) {
                    int count = mController.getSubtitleManager().getSubtitleCount();
                    statusText.setText("已加载 " + count + " 条字幕");
                } else {
                    statusText.setText("未加载字幕");
                }
            }
            
            // OCR 翻译字幕按钮
            View btnOcrTranslate = dialogView.findViewById(R.id.btn_ocr_translate);
            android.widget.TextView ocrStatus = dialogView.findViewById(R.id.ocr_status);
            if (btnOcrTranslate != null) {
                // 安全检查 OCR 功能是否可用（避免调用不存在的类）
                boolean ocrAvailable = false;
                boolean translateAvailable = false;
                try {
                    ocrAvailable = com.orange.playerlibrary.ocr.OcrAvailabilityChecker.isTesseractAvailable();
                    translateAvailable = com.orange.playerlibrary.ocr.OcrAvailabilityChecker.isMlKitTranslateAvailable();
                } catch (Throwable e) {
                    Log.e(TAG, "Error checking OCR availability", e);
                }
                
                if (!ocrAvailable || !translateAvailable) {
                    // 功能不可用，显示安装提示
                    if (ocrStatus != null) {
                        ocrStatus.setText("需要安装额外依赖");
                        ocrStatus.setTextColor(0xFFFF6B6B);
                    }
                    ((android.widget.Button) btnOcrTranslate).setText("查看安装说明");
                    btnOcrTranslate.setOnClickListener(v -> {
                        dialog.dismiss();
                        showOcrInstallGuide();
                    });
                } else {
                    // 功能可用
                    if (ocrStatus != null) {
                        ocrStatus.setText("识别视频画面中的硬字幕并翻译");
                    }
                    btnOcrTranslate.setOnClickListener(v -> {
                        dialog.dismiss();
                        showOcrTranslateSettings();
                    });
                }
            }
            
            // 语音识别翻译按钮
            View btnSpeechTranslate = dialogView.findViewById(R.id.btn_speech_translate);
            android.widget.TextView speechStatus = dialogView.findViewById(R.id.speech_status);
            if (btnSpeechTranslate != null) {
                // 安全检查 Vosk SDK 是否可用（避免调用不存在的类）
                boolean voskAvailable = false;
                try {
                    voskAvailable = com.orange.playerlibrary.speech.VoskAvailabilityChecker.isVoskAvailable();
                } catch (Throwable e) {
                    Log.e(TAG, "Error checking Vosk availability", e);
                }
                
                if (!voskAvailable) {
                    // Vosk SDK 不可用，显示安装提示
                    if (speechStatus != null) {
                        speechStatus.setText("需要安装 Vosk SDK");
                        speechStatus.setTextColor(0xFFFF6B6B);
                    }
                    ((android.widget.Button) btnSpeechTranslate).setText("查看安装说明");
                    btnSpeechTranslate.setOnClickListener(v -> {
                        dialog.dismiss();
                        showVoskInstallGuide();
                    });
                } else if (isSpeechRunning()) {
                    // 正在运行，显示停止按钮
                    if (speechStatus != null) {
                        speechStatus.setText("语音识别正在运行中");
                        speechStatus.setTextColor(0xFF4CAF50);
                    }
                    ((android.widget.Button) btnSpeechTranslate).setText("停止语音识别");
                    btnSpeechTranslate.setOnClickListener(v -> {
                        stopSpeechTranslate();
                        dialog.dismiss();
                        showToast("语音识别已停止");
                    });
                } else {
                    // 功能可用，显示正常状态
                    if (speechStatus != null) {
                        speechStatus.setText("识别视频音频并翻译为字幕");
                    }
                    btnSpeechTranslate.setOnClickListener(v -> {
                        dialog.dismiss();
                        showSpeechTranslateSettings();
                    });
                }
            }
            
        } catch (Exception e) {
        }
    }
    
    /**
     * 显示字幕文件选择器
     */
    private void showSubtitleFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            // 支持 .srt, .vtt, .ass, .ssa 字幕格式
            String[] mimeTypes = {
                "application/x-subrip",           // .srt
                "text/vtt",                        // .vtt
                "text/x-ssa",                      // .ssa/.ass
                "application/octet-stream",        // 通用二进制
                "text/plain"                       // 纯文本
            };
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            
            mActivity.startActivityForResult(intent, REQUEST_CODE_SUBTITLE_FILE);
            Log.d(TAG, "启动字幕文件选择器");
        } catch (Exception e) {
            Log.e(TAG, "启动文件选择器失败", e);
            showToast("无法打开文件选择器");
        }
    }
    
    /**
     * 处理字幕文件选择结果
     * 需要在 Activity 的 onActivityResult 中调用此方法
     * 
     * @param requestCode 请求码
     * @param resultCode 结果码
     * @param data Intent 数据
     * @return 是否处理了该结果
     */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_SUBTITLE_FILE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    Log.d(TAG, "选择的字幕文件: " + uri);
                    loadSubtitleFromUri(uri);
                }
            }
            return true;
        }
        return false;
    }
    
    /**
     * 从 Uri 加载字幕
     */
    private void loadSubtitleFromUri(Uri uri) {
        try {
            // 获取持久化读取权限
            mActivity.getContentResolver().takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            // 某些 Uri 可能不支持持久化权限，忽略
            Log.w(TAG, "无法获取持久化权限: " + e.getMessage());
        }
        
        if (mController == null || mController.getSubtitleManager() == null) {
            showToast("字幕管理器未初始化");
            return;
        }
        
        showToast("正在加载字幕...");

        final String uriString = uri.toString();
        final String fileName = getFileNameFromUri(uri);

        // P3 双路径：ASS/SSA 且 Exo 引擎时优先走 Media3 管线（保留样式）
        // 需在下次 setUp 前注入；当前实现经 setExternalSubtitle 标记，
        // 由下次 prepareAsync 合并，纯文本层不再重复加载。
        if (fileName != null) {
            if (isAssSubtitlePath(fileName)) {
                if (mVideoView.setExternalSubtitle(uriString, "text/x-ssa")) {
                    showToast("ASS 字幕将以增强样式渲染（重新播放生效）");
                    String videoUrl = mVideoView.getUrl();
                    if (videoUrl != null) {
                        mSettingsManager.setSubtitleLocalForVideo(videoUrl, uriString);
                        mSettingsManager.setSubtitleUrlForVideo(videoUrl, null);
                        mSettingsManager.setSubtitleMimeTypeForVideo(videoUrl, "text/x-ssa");
                    }
                    return;
                }
                // Exo 不可用：走 SubtitleManager 纯文本降级（下方）
                showToast("当前引擎不支持 ASS 样式，将以纯文本渲染");
            }
        }

        mController.getSubtitleManager().loadSubtitle(uri, new com.orange.playerlibrary.subtitle.SubtitleManager.OnSubtitleLoadListener() {
            @Override
            public void onLoadSuccess(int count) {
                mActivity.runOnUiThread(() -> {
                    showToast("字幕加载成功，共 " + count + " 条");
                    mController.startSubtitle();
                    // 保存本地字幕记忆
                    String videoUrl = mVideoView.getUrl();
                    if (videoUrl != null) {
                        mSettingsManager.setSubtitleLocalForVideo(videoUrl, uriString);
                        // 清除网络字幕记忆（本地优先）
                        mSettingsManager.setSubtitleUrlForVideo(videoUrl, null);
                        mSettingsManager.setSubtitleMimeTypeForVideo(videoUrl, null);
                        Log.d(TAG, "已保存本地字幕记忆: " + uriString);
                    }
                });
            }
            
            @Override
            public void onLoadFailed(String error) {
                mActivity.runOnUiThread(() -> {
                    showToast("字幕加载失败: " + error);
                });
            }
        });
    }
    
    /**
     * 显示字幕 URL 输入对话框（自定义风格）
     */
    private void showSubtitleUrlInput() {
        View dialogView = View.inflate(mActivity, R.layout.dialog_subtitle_url_input, null);
        
        android.widget.EditText etUrl = dialogView.findViewById(R.id.et_subtitle_url);
        View btnCancel = dialogView.findViewById(R.id.btn_cancel);
        View btnLoad = dialogView.findViewById(R.id.btn_load);
        
        final AlertDialog dialog = new AlertDialog.Builder(mActivity)
                .setView(dialogView)
                .create();
        
        // 设置对话框背景透明
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        
        btnLoad.setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            if (!url.isEmpty()) {
                dialog.dismiss();
                loadSubtitleFromUrl(url);
            } else {
                showToast("请输入字幕 URL");
            }
        });
        
        dialog.show();
    }
    
    /**
     * 从 URL 加载字幕
     */
    private void loadSubtitleFromUrl(String url) {
        if (isAssSubtitlePath(url) && mVideoView.setExternalSubtitle(url, "text/x-ssa")) {
            showToast("ASS 字幕将以增强样式渲染（重新播放生效）");
            String videoUrl = mVideoView.getUrl();
            if (videoUrl != null) {
                mSettingsManager.setSubtitleUrlForVideo(videoUrl, url);
                mSettingsManager.setSubtitleLocalForVideo(videoUrl, null);
                mSettingsManager.setSubtitleMimeTypeForVideo(videoUrl, "text/x-ssa");
            }
            return;
        }

        showToast("正在加载字幕...");
        mController.loadSubtitle(url, new com.orange.playerlibrary.subtitle.SubtitleManager.OnSubtitleLoadListener() {
            @Override
            public void onLoadSuccess(int count) {
                mActivity.runOnUiThread(() -> {
                    showToast("字幕加载成功，共 " + count + " 条");
                    mController.startSubtitle();
                    // 保存网络字幕记忆
                    String videoUrl = mVideoView.getUrl();
                    if (videoUrl != null) {
                        mSettingsManager.setSubtitleUrlForVideo(videoUrl, url);
                        // 清除本地字幕记忆（网络优先）
                        mSettingsManager.setSubtitleLocalForVideo(videoUrl, null);
                        mSettingsManager.setSubtitleMimeTypeForVideo(videoUrl, null);
                        Log.d(TAG, "已保存网络字幕记忆: " + url);
                    }
                });
            }
            
            @Override
            public void onLoadFailed(String error) {
                mActivity.runOnUiThread(() -> {
                    showToast("字幕加载失败: " + error);
                });
            }
        });
    }
    
    /**
     * 应用字幕延迟并按视频持久化
     */
    private void applySubtitleDelay(long delayMs) {
        if (mController == null || mController.getSubtitleManager() == null) {
            return;
        }
        mController.getSubtitleManager().setSubtitleDelayMs(delayMs);
        String videoUrl = mVideoView.getUrl();
        if (videoUrl != null) {
            mSettingsManager.setSubtitleDelayForVideo(videoUrl, delayMs);
        }
        Log.d(TAG, "字幕延迟: " + delayMs + "ms");
    }

    /**
     * 从 Uri 提取文件名（用于字幕格式判断）
     */
    private String getFileNameFromUri(Uri uri) {
        try {
            if ("content".equals(uri.getScheme())) {
                try (android.database.Cursor cursor = mActivity.getContentResolver().query(
                        uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(
                                android.provider.OpenableColumns.DISPLAY_NAME);
                        if (nameIndex >= 0) {
                            return cursor.getString(nameIndex);
                        }
                    }
                }
            }
            return uri.getLastPathSegment();
        } catch (Exception e) {
            return uri.getLastPathSegment();
        }
    }

    private boolean isAssSubtitlePath(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        int queryIndex = lower.indexOf('?');
        int fragmentIndex = lower.indexOf('#');
        int suffixEnd = lower.length();
        if (queryIndex >= 0) {
            suffixEnd = queryIndex;
        }
        if (fragmentIndex >= 0 && fragmentIndex < suffixEnd) {
            suffixEnd = fragmentIndex;
        }
        String pathWithoutSuffix = lower.substring(0, suffixEnd);
        return pathWithoutSuffix.endsWith(".ass") || pathWithoutSuffix.endsWith(".ssa");
    }

    private void updateDelayText(android.widget.TextView delayText, long delayMs) {
        delayText.setText(String.format(java.util.Locale.getDefault(),
                "字幕延迟: %+.1fs", delayMs / 1000f));
    }

    /**
     * 尝试自动加载已记忆的字幕
     * 应在视频开始播放时调用
     */
    public void tryLoadRememberedSubtitle() {
        String videoUrl = mVideoView.getUrl();
        if (videoUrl == null || mController == null || mController.getSubtitleManager() == null) {
            return;
        }
        
        // 应用持久化的字幕大小与该视频的记忆延迟
        mController.getSubtitleManager().setTextSize(mSettingsManager.getSubtitleSize());
        mController.getSubtitleManager().setSubtitleDelayMs(mSettingsManager.getSubtitleDelayForVideo(videoUrl));
        if (mSettingsManager.isSubtitleEnabled()) {
            mController.getSubtitleManager().show();
        } else {
            mController.getSubtitleManager().hide();
        }

        // Media3 Exo 管线已在 prepare 前接管记忆的 ASS/SSA，避免 onPrepared 后重复走纯文本解析。
        if (mVideoView.isExternalSubtitleConfiguredForCurrentUrl()) {
            return;
        }

        // 优先加载本地字幕
        String localUri = mSettingsManager.getSubtitleLocalForVideo(videoUrl);
        if (localUri != null && !localUri.isEmpty()) {
            Log.d(TAG, "自动加载本地字幕: " + localUri);
            try {
                Uri uri = Uri.parse(localUri);
                mController.getSubtitleManager().loadSubtitle(uri, new com.orange.playerlibrary.subtitle.SubtitleManager.OnSubtitleLoadListener() {
                    @Override
                    public void onLoadSuccess(int count) {
                        mActivity.runOnUiThread(() -> {
                            Log.d(TAG, "自动加载本地字幕成功，共 " + count + " 条");
                            mController.startSubtitle();
                        });
                    }
                    
                    @Override
                    public void onLoadFailed(String error) {
                        Log.w(TAG, "自动加载本地字幕失败: " + error);
                        // 本地字幕失败，尝试网络字幕
                        tryLoadRememberedUrlSubtitle(videoUrl);
                    }
                });
                return;
            } catch (Exception e) {
                Log.w(TAG, "解析本地字幕Uri失败", e);
            }
        }
        
        // 加载网络字幕
        tryLoadRememberedUrlSubtitle(videoUrl);
    }
    
    private void tryLoadRememberedUrlSubtitle(String videoUrl) {
        String subtitleUrl = mSettingsManager.getSubtitleUrlForVideo(videoUrl);
        if (subtitleUrl != null && !subtitleUrl.isEmpty()) {
            Log.d(TAG, "自动加载网络字幕: " + subtitleUrl);
            mController.loadSubtitle(subtitleUrl, new com.orange.playerlibrary.subtitle.SubtitleManager.OnSubtitleLoadListener() {
                @Override
                public void onLoadSuccess(int count) {
                    mActivity.runOnUiThread(() -> {
                        Log.d(TAG, "自动加载网络字幕成功，共 " + count + " 条");
                        mController.startSubtitle();
                    });
                }
                
                @Override
                public void onLoadFailed(String error) {
                    Log.w(TAG, "自动加载网络字幕失败: " + error);
                }
            });
        }
    }
    
    // ===== OCR 翻译字幕功能 =====
    
    /**
     * 显示 OCR 安装指南
     */
    private void showOcrInstallGuide() {
        String message = com.orange.playerlibrary.ocr.OcrAvailabilityChecker.getMissingDependenciesMessage();
        
        new AlertDialog.Builder(mActivity)
            .setTitle("安装 OCR 翻译功能")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show();
    }
    
    /**
     * 显示 Vosk 语音识别安装指南
     */
    private void showVoskInstallGuide() {
        String message = com.orange.playerlibrary.speech.VoskAvailabilityChecker.getMissingDependenciesMessage();
        
        new AlertDialog.Builder(mActivity)
            .setTitle("安装语音识别功能")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show();
    }
    
    /**
     * 显示 OCR 翻译设置弹窗
     */
    private void showOcrTranslateSettings() {
        View dialogView = View.inflate(mActivity, R.layout.dialog_ocr_settings, null);
        
        final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                DialogUtils.DialogPosition.CENTER, null, null);
        
        // 语言包管理器
        final com.orange.playerlibrary.ocr.LanguagePackManager manager = 
            new com.orange.playerlibrary.ocr.LanguagePackManager(mActivity);
        
        // 语言包管理入口
        View btnManageLanguagePack = dialogView.findViewById(R.id.btn_manage_language_pack);
        TextView tvLanguagePackStatus = dialogView.findViewById(R.id.tv_language_pack_status);
        
        // 源语言选择
        android.widget.Spinner spinnerSource = dialogView.findViewById(R.id.spinner_source_lang);
        android.widget.Spinner spinnerTarget = dialogView.findViewById(R.id.spinner_target_lang);
        
        // 动态加载已安装的语言包
        java.util.List<String> installedLangs = manager.getInstalledLanguages();
        final java.util.List<String> sourceLangNames = new java.util.ArrayList<>();
        final java.util.List<String> sourceLangCodes = new java.util.ArrayList<>();
        final java.util.List<String> targetLangNames = new java.util.ArrayList<>();
        final java.util.List<String> targetLangCodes = new java.util.ArrayList<>();
        
        // 使用 LanguagePackManager 的语言名称映射
        java.util.Map<String, String> langNameMap = com.orange.playerlibrary.ocr.LanguagePackManager.getLanguageNameMap();
        
        // OCR 语言代码到翻译语言代码的映射
        java.util.Map<String, String> ocrToTranslateCode = new java.util.HashMap<>();
        ocrToTranslateCode.put("chi_sim", "zh");
        ocrToTranslateCode.put("chi_tra", "zh");
        ocrToTranslateCode.put("eng", "en");
        ocrToTranslateCode.put("jpn", "ja");
        ocrToTranslateCode.put("kor", "ko");
        ocrToTranslateCode.put("fra", "fr");
        ocrToTranslateCode.put("deu", "de");
        ocrToTranslateCode.put("spa", "es");
        ocrToTranslateCode.put("rus", "ru");
        ocrToTranslateCode.put("ara", "ar");
        ocrToTranslateCode.put("tha", "th");
        ocrToTranslateCode.put("vie", "vi");
        ocrToTranslateCode.put("por", "pt");
        ocrToTranslateCode.put("ita", "it");
        ocrToTranslateCode.put("nld", "nl");
        ocrToTranslateCode.put("pol", "pl");
        ocrToTranslateCode.put("tur", "tr");
        ocrToTranslateCode.put("hin", "hi");
        ocrToTranslateCode.put("ind", "id");
        ocrToTranslateCode.put("msa", "ms");
        
        // 填充已安装的语言（源语言和目标语言）
        for (String langCode : installedLangs) {
            String displayName = langNameMap.get(langCode);
            if (displayName == null) {
                displayName = langCode; // 未知语言显示代码
            }
            // 源语言
            sourceLangNames.add(displayName);
            sourceLangCodes.add(langCode);
            // 目标语言（使用翻译代码）
            String translateCode = ocrToTranslateCode.get(langCode);
            if (translateCode != null) {
                targetLangNames.add(displayName);
                targetLangCodes.add(translateCode);
            }
        }
        
        // 更新语言包状态
        if (tvLanguagePackStatus != null) {
            tvLanguagePackStatus.setText("已安装 " + installedLangs.size() + " 个");
        }
        
        // 扫描区域设置入口 (Requirements: 9.1, 9.2)
        View btnSetScanRegion = dialogView.findViewById(R.id.btn_set_scan_region);
        TextView tvScanRegionStatus = dialogView.findViewById(R.id.tv_scan_region_status);
        
        // 更新扫描区域状态显示
        if (tvScanRegionStatus != null) {
            com.orange.playerlibrary.ocr.OcrSubtitleManager tempOcrManager = 
                new com.orange.playerlibrary.ocr.OcrSubtitleManager(mActivity);
            com.orange.playerlibrary.ocr.OcrScanRegionView.ScanRegion currentRegion = 
                tempOcrManager.getScanRegion();
            String regionDesc = formatScanRegionDescription(currentRegion);
            tvScanRegionStatus.setText(regionDesc);
        }
        
        // 点击打开扫描区域编辑模式
        if (btnSetScanRegion != null) {
            btnSetScanRegion.setOnClickListener(v -> {
                // 关闭设置对话框
                dialog.dismiss();
                // 显示 OcrScanRegionView 编辑界面
                showOcrScanRegionEditor();
            });
        }
        
        // 点击打开语言包管理，关闭后刷新下拉框
        if (btnManageLanguagePack != null) {
            btnManageLanguagePack.setOnClickListener(v -> {
                com.orange.playerlibrary.ocr.LanguagePackDialog packDialog = 
                    new com.orange.playerlibrary.ocr.LanguagePackDialog(mActivity);
                packDialog.setOnDismissListener(() -> {
                    // 刷新已安装语言列表
                    java.util.List<String> newInstalledLangs = manager.getInstalledLanguages();
                    sourceLangNames.clear();
                    sourceLangCodes.clear();
                    targetLangNames.clear();
                    targetLangCodes.clear();
                    for (String langCode : newInstalledLangs) {
                        String displayName = langNameMap.get(langCode);
                        if (displayName == null) {
                            displayName = langCode;
                        }
                        sourceLangNames.add(displayName);
                        sourceLangCodes.add(langCode);
                        String translateCode = ocrToTranslateCode.get(langCode);
                        if (translateCode != null) {
                            targetLangNames.add(displayName);
                            targetLangCodes.add(translateCode);
                        }
                    }
                    // 更新源语言下拉框
                    if (spinnerSource != null) {
                        java.util.List<String> sourceList = sourceLangNames.isEmpty() ? 
                            java.util.Arrays.asList("请先下载语言包") : sourceLangNames;
                        android.widget.ArrayAdapter<String> newAdapter = new android.widget.ArrayAdapter<>(
                            mActivity, R.layout.spinner_item, sourceList);
                        newAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                        spinnerSource.setAdapter(newAdapter);
                    }
                    // 更新目标语言下拉框
                    if (spinnerTarget != null) {
                        java.util.List<String> targetList = targetLangNames.isEmpty() ? 
                            java.util.Arrays.asList("请先下载语言包") : targetLangNames;
                        android.widget.ArrayAdapter<String> newAdapter = new android.widget.ArrayAdapter<>(
                            mActivity, R.layout.spinner_item, targetList);
                        newAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                        spinnerTarget.setAdapter(newAdapter);
                    }
                    // 更新状态文本
                    if (tvLanguagePackStatus != null) {
                        tvLanguagePackStatus.setText("已安装 " + newInstalledLangs.size() + " 个");
                    }
                });
                packDialog.show();
            });
        }
        
        // 设置源语言下拉框
        if (spinnerSource != null) {
            if (sourceLangNames.isEmpty()) {
                sourceLangNames.add("请先下载语言包");
                sourceLangCodes.add("");
            }
            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                mActivity, R.layout.spinner_item, sourceLangNames);
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerSource.setAdapter(adapter);
        }
        
        // 设置目标语言下拉框
        if (spinnerTarget != null) {
            if (targetLangNames.isEmpty()) {
                targetLangNames.add("请先下载语言包");
                targetLangCodes.add("");
            }
            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                mActivity, R.layout.spinner_item, targetLangNames);
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerTarget.setAdapter(adapter);
            // 默认选择英语（如果有）
            int engIndex = targetLangCodes.indexOf("en");
            if (engIndex >= 0) {
                spinnerTarget.setSelection(engIndex);
            }
        }
        
        // 开始按钮
        View btnStart = dialogView.findViewById(R.id.btn_start_ocr);
        if (btnStart != null) {
            btnStart.setOnClickListener(v -> {
                int sourceIndex = spinnerSource != null ? spinnerSource.getSelectedItemPosition() : 0;
                int targetIndex = spinnerTarget != null ? spinnerTarget.getSelectedItemPosition() : 0;
                
                // 检查是否选择了有效的源语言
                if (sourceIndex < 0 || sourceIndex >= sourceLangCodes.size() || 
                    sourceLangCodes.get(sourceIndex).isEmpty()) {
                    showToast("请先下载语言包");
                    return;
                }
                
                // 检查是否选择了有效的目标语言
                if (targetIndex < 0 || targetIndex >= targetLangCodes.size() || 
                    targetLangCodes.get(targetIndex).isEmpty()) {
                    showToast("请先下载语言包");
                    return;
                }
                
                String sourceLang = sourceLangCodes.get(sourceIndex);
                String targetLang = targetLangCodes.get(targetIndex);
                
                dialog.dismiss();
                startOcrTranslate(sourceLang, targetLang);
            });
        }
        
        // 取消按钮
        View btnCancel = dialogView.findViewById(R.id.btn_cancel);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }
    }
    
    /**
     * 开始 OCR 翻译
     */
    private void startOcrTranslate(String sourceLang, String targetLang) {
        // 保存语言设置，用于全屏切换后恢复
        mOcrSourceLang = sourceLang;
        mOcrTargetLang = targetLang;
        
        showToast("正在初始化 OCR...");
        doStartOcrTranslate(sourceLang, targetLang);
    }
    
    /**
     * 实际启动 OCR 翻译
     * 
     * 注意：Exo 和系统核心使用 SurfaceControl 模式时无法截图，
     * 需要切换到 TextureView 模式。
     * 横竖屏切换时通过 onSurfaceDestroyed 中先切换到 PlaceholderSurface 来避免崩溃。
     */
    private void doStartOcrTranslate(String sourceLang, String targetLang) {
        // OCR 功能现在可以在任何渲染模式下工作
        // 因为 MediaCodecTexture 修复已经解决了 TextureView 横竖屏切换崩溃问题
        // 不再需要强制切换到 TextureView 模式
        
        android.util.Log.d(TAG, "doStartOcrTranslate: 启动 OCR 翻译（无需切换渲染模式）");
        
        // 直接启动 OCR
        doStartOcrTranslateInternal(sourceLang, targetLang);
    }
    
    private android.os.Handler mMainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    
    /**
     * 实际启动 OCR 翻译（内部方法）
     * 注意：调用此方法前，调用方应该已经确保切换到了 TextureView 模式（如果需要的话）
     */
    private void doStartOcrTranslateInternal(String sourceLang, String targetLang) {
        // 获取 OcrSubtitleManager
        com.orange.playerlibrary.ocr.OcrSubtitleManager ocrManager = 
            new com.orange.playerlibrary.ocr.OcrSubtitleManager(mActivity);
        
        // 初始化 OCR
        if (!ocrManager.initOcr(sourceLang)) {
            showToast("OCR 初始化失败，请检查语言包是否已安装");
            showOcrLanguagePackHint(sourceLang);
            return;
        }
        
        // 设置视频视图
        if (mVideoView != null) {
            android.view.View renderView = mVideoView.getRenderProxy() != null ? 
                mVideoView.getRenderProxy().getShowView() : null;
            if (renderView != null) {
                ocrManager.setVideoView(renderView);
            }
            // 设置 GSY 渲染视图引用，用于 taskShotPic 截图（支持 SurfaceView）
            if (mVideoView.getRenderProxy() != null) {
                ocrManager.setRenderProxy(mVideoView.getRenderProxy());
            }
        }
        
        // 设置回调
        ocrManager.setCallback(new com.orange.playerlibrary.ocr.OcrSubtitleManager.OcrSubtitleCallback() {
            @Override
            public void onSubtitleRecognized(String originalText, String translatedText) {
                // 打印OCR识别结果到日志
                Log.d(TAG, "=== OCR识别结果 ===");
                Log.d(TAG, "原文: " + originalText);
                if (translatedText != null) {
                    Log.d(TAG, "译文: " + translatedText);
                }
                
                // 显示字幕 - 确保在主线程执行
                mActivity.runOnUiThread(() -> {
                    if (mController != null && mController.getSubtitleManager() != null) {
                        String displayText = translatedText != null ? 
                            originalText + "\n" + translatedText : originalText;
                        Log.d(TAG, "OCR: 调用 showText 显示字幕");
                        mController.getSubtitleManager().showText(displayText);
                    } else {
                        Log.w(TAG, "OCR: mController=" + mController + ", subtitleManager=" + 
                            (mController != null ? mController.getSubtitleManager() : "null"));
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                mActivity.runOnUiThread(() -> showToast("OCR 错误: " + error));
            }
        });
        
        // 延迟显示下载进度对话框（如果模型已下载，会在延迟前完成，不会显示对话框）
        final DownloadProgressDialog progressDialog = new DownloadProgressDialog(mActivity);
        final boolean[] downloadCompleted = {false};
        
        mMainHandler.postDelayed(() -> {
            if (!downloadCompleted[0]) {
                progressDialog.show("正在下载翻译模型");
            }
        }, 500); // 500ms 后如果还没完成才显示对话框
        
        // 初始化翻译
        ocrManager.initTranslation(targetLang, new com.orange.playerlibrary.ocr.TranslationEngine.ModelDownloadCallback() {
            @Override
            public void onProgress(int progress) {
                if (progress > 0) {
                    progressDialog.setProgress(progress);
                }
            }
            
            @Override
            public void onSuccess() {
                downloadCompleted[0] = true;
                mActivity.runOnUiThread(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.complete();
                    }
                    showToast("OCR 翻译已启动");
                    ocrManager.start();
                    
                    // 保存引用以便后续停止
                    mOcrSubtitleManager = ocrManager;
                });
            }
            
            @Override
            public void onError(String error) {
                downloadCompleted[0] = true;
                mActivity.runOnUiThread(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.fail(error);
                    }
                    showToast("翻译模型下载失败: " + error);
                    // 即使翻译失败，也可以只显示 OCR 结果
                    ocrManager.start();
                    mOcrSubtitleManager = ocrManager;
                });
            }
        });
    }
    
    /**
     * 显示语言包安装提示
     */
    private void showOcrLanguagePackHint(String language) {
        String hint = com.orange.playerlibrary.ocr.TesseractOcrEngine.getTrainedDataDownloadHint(language);
        
        new AlertDialog.Builder(mActivity)
            .setTitle("需要下载语言包")
            .setMessage(hint)
            .setPositiveButton("知道了", null)
            .show();
    }
    
    /**
     * 格式化扫描区域描述
     * Requirements: 9.1
     */
    private String formatScanRegionDescription(com.orange.playerlibrary.ocr.OcrScanRegionView.ScanRegion region) {
        if (region == null) {
            return "底部 20%";
        }
        
        // 检查是否是默认区域（底部20%）
        com.orange.playerlibrary.ocr.OcrScanRegionView.ScanRegion defaultRegion = 
            com.orange.playerlibrary.ocr.OcrScanRegionView.ScanRegion.getDefault();
        if (Math.abs(region.left - defaultRegion.left) < 0.01f &&
            Math.abs(region.top - defaultRegion.top) < 0.01f &&
            Math.abs(region.right - defaultRegion.right) < 0.01f &&
            Math.abs(region.bottom - defaultRegion.bottom) < 0.01f) {
            return "底部 20%";
        }
        
        // 计算区域高度百分比
        int heightPercent = (int) ((region.bottom - region.top) * 100);
        
        // 判断区域位置
        if (region.top >= 0.7f) {
            return "底部 " + heightPercent + "%";
        } else if (region.bottom <= 0.3f) {
            return "顶部 " + heightPercent + "%";
        } else {
            return "自定义区域";
        }
    }
    
    /**
     * 显示 OCR 扫描区域编辑界面
     * Requirements: 9.2
     */
    private void showOcrScanRegionEditor() {
        if (mVideoView == null) {
            showToast("请先播放视频");
            return;
        }
        
        // 获取当前播放器容器（全屏时播放器在 DecorView 中）
        android.view.ViewGroup playerContainer = (android.view.ViewGroup) mVideoView.getParent();
        
        if (playerContainer == null) {
            showToast("无法获取播放器容器");
            return;
        }
        
        Log.d(TAG, "showOcrScanRegionEditor: playerContainer=" + playerContainer + 
            ", isAttached=" + (playerContainer.getWindowToken() != null));
        
        // 每次都重新查找 OcrScanRegionView，避免使用旧实例
        com.orange.playerlibrary.ocr.OcrScanRegionView scanRegionView = 
            findOcrScanRegionView(playerContainer);
        
        if (scanRegionView == null) {
            // 创建新的 OcrScanRegionView
            scanRegionView = new com.orange.playerlibrary.ocr.OcrScanRegionView(mActivity);
            
            // 添加到播放器容器
            android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            );
            playerContainer.addView(scanRegionView, params);
            Log.d(TAG, "showOcrScanRegionEditor: created new OcrScanRegionView");
        } else {
            Log.d(TAG, "showOcrScanRegionEditor: found existing OcrScanRegionView, " +
                "visibility=" + scanRegionView.getVisibility() + 
                ", isAttached=" + (scanRegionView.getWindowToken() != null));
        }
        
        // 设置视频尺寸
        int videoWidth = mVideoView.getWidth();
        int videoHeight = mVideoView.getHeight();
        if (videoWidth > 0 && videoHeight > 0) {
            scanRegionView.setVideoSize(videoWidth, videoHeight);
            Log.d(TAG, "showOcrScanRegionEditor: set video size " + videoWidth + "x" + videoHeight);
        }
        
        // 加载当前保存的扫描区域
        com.orange.playerlibrary.ocr.OcrSubtitleManager ocrManager = 
            new com.orange.playerlibrary.ocr.OcrSubtitleManager(mActivity);
        com.orange.playerlibrary.ocr.OcrScanRegionView.ScanRegion savedRegion = 
            ocrManager.getScanRegion();
        scanRegionView.setScanRegion(savedRegion);
        
        // 设置区域变化监听器
        final com.orange.playerlibrary.ocr.OcrScanRegionView finalScanRegionView = scanRegionView;
        scanRegionView.setOnRegionChangedListener(
            new com.orange.playerlibrary.ocr.OcrScanRegionView.OnRegionChangedListener() {
                @Override
                public void onRegionChanged(com.orange.playerlibrary.ocr.OcrScanRegionView.ScanRegion region) {
                    // 保存区域设置
                    com.orange.playerlibrary.ocr.OcrSubtitleManager manager = 
                        new com.orange.playerlibrary.ocr.OcrSubtitleManager(mActivity);
                    manager.setScanRegion(region);
                    showToast("扫描区域已保存");
                }
                
                @Override
                public void onEditModeChanged(boolean isEditing) {
                    Log.d(TAG, "OcrScanRegionView: editMode=" + isEditing);
                }
            }
        );
        
        // 进入编辑模式
        scanRegionView.enterEditMode();
        Log.d(TAG, "showOcrScanRegionEditor: entered edit mode");
    }
    
    /**
     * 在容器中查找 OcrScanRegionView
     */
    private com.orange.playerlibrary.ocr.OcrScanRegionView findOcrScanRegionView(android.view.ViewGroup container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            android.view.View child = container.getChildAt(i);
            if (child instanceof com.orange.playerlibrary.ocr.OcrScanRegionView) {
                return (com.orange.playerlibrary.ocr.OcrScanRegionView) child;
            }
        }
        return null;
    }
    
    // OCR 字幕管理器引用
    private com.orange.playerlibrary.ocr.OcrSubtitleManager mOcrSubtitleManager;
    
    /**
     * 停止 OCR 翻译
     */
    public void stopOcrTranslate() {
        if (mOcrSubtitleManager != null) {
            mOcrSubtitleManager.release();
            mOcrSubtitleManager = null;
        }
        
        // 切换回 SurfaceView 模式（Android Q+ 需要 SurfaceView 才能无缝切换全屏）
        try {
            String currentEngine = mSettingsManager.getPlayerEngine();
            boolean needSwitchBack = (PlayerConstants.ENGINE_EXO.equals(currentEngine) 
                && com.orange.playerlibrary.exo.OrangeExoPlayerManager.isForceTextureViewMode())
                || (PlayerConstants.ENGINE_DEFAULT.equals(currentEngine)
                && com.orange.playerlibrary.player.OrangeSystemPlayerManager.isForceTextureViewMode());
            
            if (needSwitchBack && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // 记录当前播放状态
                long currentPosition = mVideoView.getCurrentPositionWhenPlaying();
                String currentUrl = mVideoView.getUrl();
                boolean wasPlaying = mVideoView.isPlaying();
                
                // 设置回 SurfaceView 模式
                if (PlayerConstants.ENGINE_EXO.equals(currentEngine)) {
                    com.orange.playerlibrary.exo.OrangeExoPlayerManager.setForceTextureViewMode(false);
                } else {
                    com.orange.playerlibrary.player.OrangeSystemPlayerManager.setForceTextureViewMode(false);
                }
                
                // 设置 GSYVideoType 为 SurfaceView
                com.shuyu.gsyvideoplayer.utils.GSYVideoType.setRenderType(
                    com.shuyu.gsyvideoplayer.utils.GSYVideoType.SURFACE);
                
                // 重新加载视频
                mVideoView.release();
                com.shuyu.gsyvideoplayer.GSYVideoManager.releaseAllVideos();
                mVideoView.selectPlayerFactory(currentEngine);
                
                if (currentUrl != null && !currentUrl.isEmpty()) {
                    mVideoView.setUp(currentUrl, false, "");
                    if (currentPosition > 0) {
                        mVideoView.setSeekOnStart(currentPosition);
                    }
                    if (wasPlaying) {
                        mVideoView.startPlayLogic();
                    }
                }
                
                showToast("已切换回 SurfaceView 模式");
            } else {
                // 只设置标志，不重新加载
                if (PlayerConstants.ENGINE_EXO.equals(currentEngine)) {
                    com.orange.playerlibrary.exo.OrangeExoPlayerManager.setForceTextureViewMode(false);
                } else if (PlayerConstants.ENGINE_DEFAULT.equals(currentEngine)) {
                    com.orange.playerlibrary.player.OrangeSystemPlayerManager.setForceTextureViewMode(false);
                }
            }
        } catch (Exception e) {
        }
    }
    
    // ==================== 语音识别翻译功能 ====================
    
    // 语音字幕管理器引用
    private com.orange.playerlibrary.speech.SpeechSubtitleManager mSpeechSubtitleManager;
    
    // 语音字幕自动隐藏相关
    private Runnable mSpeechSubtitleHideRunnable;
    private Runnable mSpeechSubtitleRefreshRunnable;
    private static final long SPEECH_SUBTITLE_PARTIAL_DURATION = 1500; // partial 结果显示 1.5 秒
    private static final long SPEECH_SUBTITLE_FINAL_DURATION = 3000;   // final 结果显示 3 秒
    private static final long SPEECH_SUBTITLE_REFRESH_INTERVAL = 1000; // 每秒刷新一次
    
    // 当前显示的语音字幕
    private String mCurrentSpeechSubtitle = "";
    private long mLastSpeechSubtitleUpdateTime = 0;
    private long mLastSpeechSubtitleClearTime = 0; // 上次清空字幕的时间
    
    /**
     * 显示语音识别字幕（超过10个字符自动清空）
     * @param text 字幕文本
     * @param isFinal 是否是最终结果
     */
    private void showSpeechSubtitle(String text, boolean isFinal) {
        // 直接使用 mController，因为全屏时播放器本身被移动，controller 也跟着移动
        if (mController == null || mController.getSubtitleManager() == null) {
            Log.w(TAG, "showSpeechSubtitle: controller or subtitleManager is null");
            return;
        }
        
        // 清理文本：去除多余空格
        text = cleanSpeechText(text);
        
        // 检查是否需要清空（当前字幕超过10个字符，且新文本也超过10个字符）
        if (mCurrentSpeechSubtitle.length() >= 10 && text.length() >= 10) {
            // 清空字幕
            mController.getSubtitleManager().showText("");
            mCurrentSpeechSubtitle = "";
            Log.d(TAG, "========== Auto CLEAR subtitle (>10 chars) ==========");
        }
        
        // 限制字幕最大长度：只显示最后 15 个字符
        final int MAX_SUBTITLE_LENGTH = 15;
        if (text.length() > MAX_SUBTITLE_LENGTH) {
            // 超过最大长度，只保留最后的部分
            text = "..." + text.substring(text.length() - MAX_SUBTITLE_LENGTH);
        }
        
        // 只有文本变化时才更新显示（避免频繁刷新）
        if (!text.equals(mCurrentSpeechSubtitle)) {
            mController.getSubtitleManager().showText(text);
            mCurrentSpeechSubtitle = text;
            Log.v(TAG, "Update subtitle: [" + text + "]");
        }
    }
    
    /**
     * 清理语音识别文本
     * 1. 去除首尾空格
     * 2. 将多个连续空格替换为单个空格（去除流式输出的空格问题）
     * 3. 去除换行符前后的空格
     */
    private String cleanSpeechText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // 去除首尾空格
        text = text.trim();
        
        // 将多个连续空格替换为单个空格（解决 "11 22 33 33 555" 的问题）
        text = text.replaceAll("\\s+", " ");
        
        // 如果包含换行符（翻译字幕），去除换行符前后的空格
        if (text.contains("\n")) {
            text = text.replaceAll("\\s*\\n\\s*", "\n");
        }
        
        return text;
    }
    
    /**
     * 格式化语音字幕（类似 OCR 的双层显示）
     * @param originalText 原文
     * @param translatedText 译文
     * @param translationEnabled 是否启用翻译
     * @return 格式化后的字幕文本
     */
    private String formatSpeechSubtitle(String originalText, String translatedText, boolean translationEnabled) {
        // 清理文本
        originalText = cleanSpeechText(originalText);
        if (translatedText != null) {
            translatedText = cleanSpeechText(translatedText);
        }
        
        if (!translationEnabled || translatedText == null || translatedText.isEmpty()) {
            // 未启用翻译或翻译失败，只显示原文
            return originalText;
        }
        
        // 启用翻译，显示双层字幕（原文 + 译文）
        return originalText + "\n" + translatedText;
    }
    
    /**
     * 检查语音识别是否正在运行
     */
    public boolean isSpeechRunning() {
        return mSpeechSubtitleManager != null && mSpeechSubtitleManager.isRunning();
    }
    
    // SharedPreferences 键
    private static final String PREF_SPEECH_SETTINGS = "speech_settings";
    private static final String PREF_TRANSLATION_ENABLED = "speech_translation_enabled";
    private static final String PREF_SOURCE_LANGUAGE = "speech_source_language";
    private static final String PREF_TARGET_LANGUAGE = "speech_target_language";
    
    /**
     * 显示语音识别设置对话框
     */
    private void showSpeechTranslateSettings() {
        try {
            View dialogView = View.inflate(mActivity, R.layout.speech_translate_dialog, null);
            
            // 使用 DialogUtils 创建对话框，从右侧滑出
            final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                    DialogUtils.DialogPosition.RIGHT, null, null);
            
            // 点击左侧空白区域关闭
            View layout = dialogView.findViewById(R.id.layout);
            if (layout != null) {
                layout.setOnClickListener(v -> {
                    Log.d(TAG, "layout clicked, dismissing dialog");
                    dialog.dismiss();
                });
            }
            // 阻止 ScrollView 的点击事件传递到父布局
            View scrollContent = dialogView.findViewById(R.id.scroll_content);
            if (scrollContent != null) {
                scrollContent.setOnClickListener(v -> {
                    // 不做任何事，只是阻止事件传递
                });
            }
            
            // 获取 SharedPreferences
            android.content.SharedPreferences prefs = mActivity.getSharedPreferences(PREF_SPEECH_SETTINGS, android.content.Context.MODE_PRIVATE);
            
            // 源语言下拉框
            android.widget.Spinner spinnerSource = dialogView.findViewById(R.id.spinner_speech_source);
            // 目标语言下拉框
            android.widget.Spinner spinnerTarget = dialogView.findViewById(R.id.spinner_speech_target);
            // 翻译开关
            android.widget.Switch translateSwitch = dialogView.findViewById(R.id.switch_translate);
            // 目标语言标签
            View tvTargetLabel = dialogView.findViewById(R.id.tv_target_label);
            // 无语言提示
            android.widget.TextView tvNoLanguageHint = dialogView.findViewById(R.id.tv_no_language_hint);
            // 已安装数量
            android.widget.TextView tvInstalledCount = dialogView.findViewById(R.id.tv_installed_count);
            // 管理语言包按钮
            View btnManageLanguagePack = dialogView.findViewById(R.id.btn_manage_language_pack);
            // 开始按钮
            View btnStart = dialogView.findViewById(R.id.btn_start_speech);
            
            // 创建 VoskModelManager 获取已安装语言
            com.orange.playerlibrary.speech.VoskModelManager modelManager = 
                new com.orange.playerlibrary.speech.VoskModelManager(mActivity);
            
            // 获取已安装的语言列表
            final java.util.List<String> sourceLangNames = new java.util.ArrayList<>();
            final java.util.List<String> sourceLangCodes = new java.util.ArrayList<>();
            
            // 动态加载已安装的语言
            java.util.List<com.orange.playerlibrary.speech.VoskModelManager.LanguageModel> allLanguages = 
                modelManager.getSupportedLanguages();
            for (com.orange.playerlibrary.speech.VoskModelManager.LanguageModel lang : allLanguages) {
                if (lang.isInstalled) {
                    sourceLangNames.add(lang.displayName);
                    sourceLangCodes.add(lang.languageCode);
                }
            }
            
            // 更新已安装数量显示
            if (tvInstalledCount != null) {
                tvInstalledCount.setText("已安装 " + sourceLangCodes.size() + " 个");
            }
            
            // 检查是否有已安装的语言
            boolean hasInstalledLanguages = !sourceLangCodes.isEmpty();
            
            // 显示/隐藏无语言提示
            if (tvNoLanguageHint != null) {
                tvNoLanguageHint.setVisibility(hasInstalledLanguages ? View.GONE : View.VISIBLE);
            }
            
            // 禁用/启用开始按钮
            if (btnStart != null) {
                btnStart.setEnabled(hasInstalledLanguages);
                btnStart.setAlpha(hasInstalledLanguages ? 1.0f : 0.5f);
            }
            
            // 禁用/启用源语言下拉框
            if (spinnerSource != null) {
                spinnerSource.setEnabled(hasInstalledLanguages);
                spinnerSource.setAlpha(hasInstalledLanguages ? 1.0f : 0.5f);
            }
            
            // 目标语言列表（翻译目标）
            final java.util.List<String> targetLangNames = new java.util.ArrayList<>();
            final java.util.List<String> targetLangCodes = new java.util.ArrayList<>();
            targetLangNames.add("英语");
            targetLangCodes.add("en");
            targetLangNames.add("中文");
            targetLangCodes.add("zh");
            targetLangNames.add("日语");
            targetLangCodes.add("ja");
            targetLangNames.add("韩语");
            targetLangCodes.add("ko");
            targetLangNames.add("法语");
            targetLangCodes.add("fr");
            targetLangNames.add("德语");
            targetLangCodes.add("de");
            targetLangNames.add("西班牙语");
            targetLangCodes.add("es");
            targetLangNames.add("俄语");
            targetLangCodes.add("ru");
            targetLangNames.add("意大利语");
            targetLangCodes.add("it");
            targetLangNames.add("葡萄牙语");
            targetLangCodes.add("pt");
            
            // 设置源语言下拉框
            if (spinnerSource != null && hasInstalledLanguages) {
                android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                    mActivity, R.layout.spinner_item, sourceLangNames);
                adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                spinnerSource.setAdapter(adapter);
                
                // 恢复上次选择的源语言
                String savedSourceLang = prefs.getString(PREF_SOURCE_LANGUAGE, null);
                if (savedSourceLang != null) {
                    int index = sourceLangCodes.indexOf(savedSourceLang);
                    if (index >= 0) {
                        spinnerSource.setSelection(index);
                    }
                }
            }
            
            // 设置目标语言下拉框
            if (spinnerTarget != null) {
                android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                    mActivity, R.layout.spinner_item, targetLangNames);
                adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                spinnerTarget.setAdapter(adapter);
                
                // 恢复上次选择的目标语言
                String savedTargetLang = prefs.getString(PREF_TARGET_LANGUAGE, null);
                if (savedTargetLang != null) {
                    int index = targetLangCodes.indexOf(savedTargetLang);
                    if (index >= 0) {
                        spinnerTarget.setSelection(index);
                    }
                }
            }
            
            // 恢复翻译开关状态
            boolean translationEnabled = prefs.getBoolean(PREF_TRANSLATION_ENABLED, true);
            if (translateSwitch != null) {
                translateSwitch.setChecked(translationEnabled);
                
                // 根据开关状态设置目标语言控件
                if (spinnerTarget != null) {
                    spinnerTarget.setEnabled(translationEnabled);
                    spinnerTarget.setAlpha(translationEnabled ? 1.0f : 0.5f);
                }
                if (tvTargetLabel != null) {
                    tvTargetLabel.setAlpha(translationEnabled ? 1.0f : 0.5f);
                }
                
                translateSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    // 保存翻译开关状态
                    prefs.edit().putBoolean(PREF_TRANSLATION_ENABLED, isChecked).apply();
                    
                    if (spinnerTarget != null) {
                        spinnerTarget.setEnabled(isChecked);
                        spinnerTarget.setAlpha(isChecked ? 1.0f : 0.5f);
                    }
                    if (tvTargetLabel != null) {
                        tvTargetLabel.setAlpha(isChecked ? 1.0f : 0.5f);
                    }
                });
            }
            
            // 管理语言包按钮点击事件
            if (btnManageLanguagePack != null) {
                btnManageLanguagePack.setOnClickListener(v -> {
                    dialog.dismiss();
                    showSpeechLanguagePackDialog();
                });
            }
            
            // 开始按钮
            if (btnStart != null) {
                btnStart.setOnClickListener(v -> {
                    if (!hasInstalledLanguages || sourceLangCodes.isEmpty()) {
                        showToast("请先下载至少一个语言包");
                        return;
                    }
                    
                    Log.d(TAG, "btn_start_speech clicked!");
                    int sourceIndex = spinnerSource != null ? spinnerSource.getSelectedItemPosition() : 0;
                    int targetIndex = spinnerTarget != null ? spinnerTarget.getSelectedItemPosition() : 0;
                    boolean enableTranslate = translateSwitch != null && translateSwitch.isChecked();
                    
                    if (sourceIndex < 0 || sourceIndex >= sourceLangCodes.size()) {
                        showToast("请选择识别语言");
                        return;
                    }
                    
                    String sourceLang = sourceLangCodes.get(sourceIndex);
                    String targetLang = enableTranslate ? targetLangCodes.get(targetIndex) : null;
                    
                    // 保存选择的语言
                    prefs.edit()
                        .putString(PREF_SOURCE_LANGUAGE, sourceLang)
                        .putString(PREF_TARGET_LANGUAGE, targetLangCodes.get(targetIndex))
                        .apply();
                    
                    Log.d(TAG, "Starting speech translate: source=" + sourceLang + ", target=" + targetLang);
                    dialog.dismiss();
                    
                    // 提前加载模型（在后台线程）
                    preloadSpeechModel(sourceLang, () -> {
                        // 模型加载完成后启动识别
                        startSpeechTranslate(sourceLang, targetLang);
                    });
                });
            }
            
            // 取消按钮
            View btnCancel = dialogView.findViewById(R.id.btn_cancel_speech);
            if (btnCancel != null) {
                btnCancel.setOnClickListener(v -> dialog.dismiss());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "showSpeechTranslateSettings error", e);
            showToast("打开语音识别设置失败");
        }
    }
    
    /**
     * 显示语音识别语言包管理对话框
     */
    private void showSpeechLanguagePackDialog() {
        try {
            com.orange.playerlibrary.speech.SpeechLanguagePackDialog dialog = 
                new com.orange.playerlibrary.speech.SpeechLanguagePackDialog(mActivity);
            
            // 设置语言变化监听器
            dialog.setOnLanguageChangedListener(new com.orange.playerlibrary.speech.SpeechLanguagePackDialog.OnLanguageChangedListener() {
                @Override
                public void onLanguageInstalled(String languageCode) {
                    Log.d(TAG, "Language installed: " + languageCode);
                }
                
                @Override
                public void onLanguageDeleted(String languageCode) {
                    Log.d(TAG, "Language deleted: " + languageCode);
                }
            });
            
            // 设置关闭监听器，关闭后重新打开设置对话框
            dialog.setOnDismissListener(() -> {
                // 重新打开设置对话框以刷新语言列表
                showSpeechTranslateSettings();
            });
            
            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "showSpeechLanguagePackDialog error", e);
            showToast("打开语言包管理失败");
        }
    }
    
    /**
     * 开始语音识别翻译
     */
    private void startSpeechTranslate(String sourceLang, String targetLang) {
        Log.d(TAG, "startSpeechTranslate: sourceLang=" + sourceLang + ", targetLang=" + targetLang);
        
        // AudioPlaybackCapture 不需要麦克风权限，只需要 MediaProjection 权限
        // MediaProjection 权限会在 SpeechSubtitleManager.start() 中请求
        doStartSpeechTranslate(sourceLang, targetLang);
    }
    
    /**
     * 提前加载语音模型（在后台线程，避免卡顿）
     * 点击开始识别时调用，加载完成后再启动识别服务
     * @param language 语言代码
     * @param onComplete 加载完成回调
     */
    private void preloadSpeechModel(String language, Runnable onComplete) {
        // 检查是否已缓存
        com.orange.playerlibrary.speech.VoskModelCache cache = com.orange.playerlibrary.speech.VoskModelCache.getInstance();
        if (cache.isModelCached(language)) {
            Log.d(TAG, "Model already cached, starting recognition immediately");
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        
        // 使用下载进度对话框
        final DownloadProgressDialog loadingDialog = new DownloadProgressDialog(mActivity);
        loadingDialog.showWithRealProgress("正在加载语音模型");
        
        // 在后台线程加载模型
        new Thread(() -> {
            try {
                // 模拟进度更新
                mActivity.runOnUiThread(() -> loadingDialog.setProgress(10, "正在读取模型文件..."));
                
                // 使用缓存管理器加载模型
                org.vosk.Model model = cache.loadAndCacheModel(mActivity, language);
                
                mActivity.runOnUiThread(() -> loadingDialog.setProgress(90, "模型加载完成"));
                
                // 短暂延迟让用户看到完成状态
                Thread.sleep(300);
                
                // 回到主线程
                mActivity.runOnUiThread(() -> {
                    loadingDialog.complete();
                    if (model != null) {
                        Log.d(TAG, "Model preloaded and cached successfully, starting recognition service");
                        // 模型加载成功，启动识别服务
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    } else {
                        loadingDialog.fail("模型加载失败");
                        showToast("模型加载失败");
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to preload model", e);
                mActivity.runOnUiThread(() -> {
                    loadingDialog.fail("模型加载失败: " + e.getMessage());
                    showToast("模型加载失败: " + e.getMessage());
                });
            }
        }, "ModelPreloadThread").start();
    }
    
    // MediaProjection 权限回调需要的变量（不是麦克风权限）
    private String mPendingSpeechSourceLang;
    private String mPendingSpeechTargetLang;
    
    /**
     * 处理录音权限请求结果（已废弃，AudioPlaybackCapture 不需要麦克风权限）
     */
    @Deprecated
    public void handleRecordAudioPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        // AudioPlaybackCapture 不需要麦克风权限，此方法已废弃
        Log.w(TAG, "handleRecordAudioPermissionResult: This method is deprecated, AudioPlaybackCapture doesn't need RECORD_AUDIO permission");
    }
    
    /**
     * 实际启动语音识别翻译（使用 Vosk + AudioPlaybackCapture）
     * 注意：调用此方法前应该已经通过 preloadSpeechModel 加载了模型
     */
    private void doStartSpeechTranslate(String sourceLang, String targetLang) {
        Log.d(TAG, "doStartSpeechTranslate: sourceLang=" + sourceLang + ", targetLang=" + targetLang);
        
        // 检查是否支持
        if (!com.orange.playerlibrary.speech.SpeechSubtitleManager.isSupported()) {
            showSpeechNotSupportedDialog();
            return;
        }
        
        // 检查语音模型是否已下载
        if (!com.orange.playerlibrary.speech.VoskSpeechEngine.isModelDownloaded(mActivity, sourceLang)) {
            showVoskModelDownloadDialog(sourceLang, targetLang);
            return;
        }
        
        // 创建语音字幕管理器（模型已经预加载，这里会很快）
        com.orange.playerlibrary.speech.SpeechSubtitleManager speechManager = 
            new com.orange.playerlibrary.speech.SpeechSubtitleManager(mActivity);
        
        // 设置回调
        speechManager.setCallback(new com.orange.playerlibrary.speech.SpeechSubtitleManager.SpeechSubtitleCallback() {
            @Override
            public void onPartialSubtitle(String text, String translatedText) {
                // 显示 partial 结果
                mActivity.runOnUiThread(() -> {
                    if (mController != null && mController.getSubtitleManager() != null) {
                        String displayText = formatSpeechSubtitle(text, translatedText, targetLang != null);
                        Log.d(TAG, "---------- Speech PARTIAL callback: original=[" + text + "], translated=[" + translatedText + "], display=[" + displayText + "] ----------");
                        showSpeechSubtitle(displayText, false);
                    }
                });
            }
            
            @Override
            public void onFinalSubtitle(String text, String translatedText) {
                // 显示最终结果
                mActivity.runOnUiThread(() -> {
                    if (mController != null && mController.getSubtitleManager() != null) {
                        String displayText = formatSpeechSubtitle(text, translatedText, targetLang != null);
                        Log.d(TAG, "---------- Speech FINAL callback: original=[" + text + "], translated=[" + translatedText + "], display=[" + displayText + "] ----------");
                        showSpeechSubtitle(displayText, true);
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                mActivity.runOnUiThread(() -> {
                    showToast("语音识别错误: " + error);
                });
            }
            
            @Override
            public void onStateChanged(boolean isListening) {
                Log.d(TAG, "Speech state changed: " + isListening);
                mActivity.runOnUiThread(() -> {
                    if (isListening) {
                        showToast("语音识别已启动");
                    }
                });
            }
        });
        
        // 保存引用
        mSpeechSubtitleManager = speechManager;
        mPendingSpeechSourceLang = sourceLang;
        mPendingSpeechTargetLang = targetLang;
        
        // 请求屏幕捕获权限
        speechManager.requestMediaProjection(mActivity);
    }
    
    /**
     * 处理屏幕捕获权限结果
     */
    public void handleMediaProjectionResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "handleMediaProjectionResult: requestCode=" + requestCode + ", resultCode=" + resultCode + ", data=" + data);
        Log.d(TAG, "handleMediaProjectionResult: mSpeechSubtitleManager=" + mSpeechSubtitleManager);
        
        if (mSpeechSubtitleManager != null) {
            if (mSpeechSubtitleManager.handleActivityResult(requestCode, resultCode, data)) {
                Log.d(TAG, "handleMediaProjectionResult: permission granted, starting speech recognition");
                // 权限已授予，开始语音识别
                mSpeechSubtitleManager.start(mPendingSpeechSourceLang, mPendingSpeechTargetLang);
            } else {
                Log.d(TAG, "handleMediaProjectionResult: permission not granted or wrong requestCode");
            }
        } else {
            Log.w(TAG, "handleMediaProjectionResult: mSpeechSubtitleManager is null");
        }
    }
    
    /**
     * 显示不支持的提示
     */
    private void showSpeechNotSupportedDialog() {
        String reason = com.orange.playerlibrary.speech.SpeechSubtitleManager.getUnsupportedReason();
        new AlertDialog.Builder(mActivity)
            .setTitle("不支持语音识别")
            .setMessage(reason != null ? reason : "您的设备不支持此功能")
            .setPositiveButton("知道了", null)
            .show();
    }
    
    /**
     * 显示 Vosk 模型下载对话框
     */
    private void showVoskModelDownloadDialog(String sourceLang, String targetLang) {
        String langName = getLanguageName(sourceLang);
        String sizeDesc = com.orange.playerlibrary.speech.VoskSpeechEngine.getModelSizeDescription(sourceLang);
        
        new AlertDialog.Builder(mActivity)
            .setTitle("需要下载语音模型")
            .setMessage("首次使用需要下载 " + langName + " 语音识别模型\n大小：" + sizeDesc + "\n\n下载后可离线使用")
            .setPositiveButton("下载", (dialog, which) -> {
                downloadVoskModel(sourceLang, targetLang);
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 下载 Vosk 模型
     */
    private void downloadVoskModel(String sourceLang, String targetLang) {
        final DownloadProgressDialog progressDialog = new DownloadProgressDialog(mActivity);
        progressDialog.showWithRealProgress("正在下载语音模型");
        
        com.orange.playerlibrary.speech.VoskModelManager modelManager = 
            new com.orange.playerlibrary.speech.VoskModelManager(mActivity);
        
        modelManager.downloadModel(sourceLang, new com.orange.playerlibrary.speech.VoskModelManager.DownloadCallback() {
            @Override
            public void onProgress(int progress, String status) {
                progressDialog.setProgress(progress, status);
            }
            
            @Override
            public void onSuccess() {
                progressDialog.complete();
                showToast("模型下载完成");
                // 重新启动语音识别
                mMainHandler.postDelayed(() -> {
                    doStartSpeechTranslate(sourceLang, targetLang);
                }, 500);
            }
            
            @Override
            public void onError(String error) {
                progressDialog.fail(error);
                showToast("模型下载失败: " + error);
            }
        });
    }
    
    /**
     * 获取语言名称
     */
    private String getLanguageName(String code) {
        if (code == null) return "未知";
        String lang = code.toLowerCase();
        if (lang.startsWith("zh")) return "中文";
        if (lang.startsWith("en")) return "英语";
        if (lang.startsWith("ja")) return "日语";
        if (lang.startsWith("ko")) return "韩语";
        return code;
    }
    
    /**
     * 显示语音识别不可用的提示对话框（旧方法，保留兼容）
     */
    private void showSpeechNotAvailableDialog() {
        showSpeechNotSupportedDialog();
    }
    
    /**
     * 停止语音识别翻译
     */
    public void stopSpeechTranslate() {
        if (mSpeechSubtitleManager != null) {
            mSpeechSubtitleManager.release();
            mSpeechSubtitleManager = null;
        }
        
        // 清理定时任务
        if (mSpeechSubtitleHideRunnable != null) {
            mMainHandler.removeCallbacks(mSpeechSubtitleHideRunnable);
            mSpeechSubtitleHideRunnable = null;
        }
        if (mSpeechSubtitleRefreshRunnable != null) {
            mMainHandler.removeCallbacks(mSpeechSubtitleRefreshRunnable);
            mSpeechSubtitleRefreshRunnable = null;
        }
        
        // 关闭字幕显示
        if (mController != null && mController.getSubtitleManager() != null) {
            mController.getSubtitleManager().showText("");
            mCurrentSpeechSubtitle = "";
        }
        
        // 清空字幕和时间戳
        mCurrentSpeechSubtitle = "";
        mLastSpeechSubtitleClearTime = 0;
        if (mController != null && mController.getSubtitleManager() != null) {
            mController.getSubtitleManager().showText("");
        }
    }
}

