package com.orange.player;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.orange.playerlibrary.DanmakuControllerImpl;
import com.orange.playerlibrary.OrangeVideoController;
import com.orange.playerlibrary.OrangevideoView;
import com.orange.playerlibrary.PiPHelper;
import com.orange.playerlibrary.VideoSniffing;
import com.orange.playerlibrary.history.PlayHistory;
import com.orange.playerlibrary.history.PlayHistoryManager;
import com.orange.playerlibrary.interfaces.IDanmakuController;
import com.orange.playerlibrary.interfaces.OnStateChangeListener;
import com.shuyu.gsyvideoplayer.GSYVideoManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 橘子播放器 Demo
 * 演示如何使用 OrangevideoView SDK
 * 
 * 基于 GSYVideoPlayer 开源播放器框架
 * https://github.com/CarGuo/GSYVideoPlayer
 */
public class MainActivity extends AppCompatActivity {

    private static final String DEFAULT_VIDEO_URL = "http://player.alicdn.com/video/aliyunmedia.mp4";
    private static final String DEFAULT_VIDEO_TITLE = "测试视频";

    private OrangevideoView mVideoView;
    private OrangeVideoController mController;
    private PiPHelper mPiPHelper;
    private DanmakuControllerImpl mDanmakuController;

    // Demo UI
    private EditText mEtVideoUrl;
    private TextView mTvDebugLog;
    private ScrollView mScrollLog;
    private StringBuilder mLogBuilder = new StringBuilder();

    private String mCurrentUrl = DEFAULT_VIDEO_URL;
    private String mCurrentTitle = DEFAULT_VIDEO_TITLE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initPlayer();
        initPiPHelper();
        setupBackPressedHandler();
    }

    private void initViews() {
        mVideoView = findViewById(R.id.video_view);
        mEtVideoUrl = findViewById(R.id.et_video_url);
        mTvDebugLog = findViewById(R.id.tv_debug_log);
        mScrollLog = (ScrollView) mTvDebugLog.getParent();

        // 设置默认URL
        mEtVideoUrl.setText(DEFAULT_VIDEO_URL);

        // 视频链接播放按钮
        Button btnPlayUrl = findViewById(R.id.btn_play_url);
        Button btnSniffPlay = findViewById(R.id.btn_sniff_play);

        btnPlayUrl.setOnClickListener(v -> playInputUrl(false));
        btnSniffPlay.setOnClickListener(v -> playInputUrl(true));

        // 播放控制按钮
        Button btnPlay = findViewById(R.id.btn_play);
        Button btnPause = findViewById(R.id.btn_pause);
        Button btnFullscreen = findViewById(R.id.btn_fullscreen);
        log("作者QQ706412584");
        btnPlay.setOnClickListener(v -> {
            log("▶ 播放");
            mVideoView.startPlayLogic();
        });

        btnPause.setOnClickListener(v -> {
            if (mVideoView.isInPlayingState()) {
                mVideoView.onVideoPause();
                log("⏸ 暂停");
            } else {
                mVideoView.clearUserPausedState();
                mVideoView.onVideoResume();
                log("▶ 继续");
            }
        });

        btnFullscreen.setOnClickListener(v -> {
            if (mVideoView.getControlWrapper() != null) {
                mVideoView.getControlWrapper().toggleFullScreen();
                log("⛶ 全屏切换");
            }
        });

        // 弹幕测试按钮
        Button btnBatchDanmaku = findViewById(R.id.btn_batch_danmaku);
        Button btnSendDanmaku = findViewById(R.id.btn_send_danmaku);
        Button btnToggleDanmaku = findViewById(R.id.btn_toggle_danmaku);

        btnBatchDanmaku.setOnClickListener(v -> loadBatchDanmaku());
        btnSendDanmaku.setOnClickListener(v -> sendDanmaku());
        btnToggleDanmaku.setOnClickListener(v -> toggleDanmaku(btnToggleDanmaku));

        // 播放历史按钮
        Button btnHistory = findViewById(R.id.btn_history);
        btnHistory.setOnClickListener(v -> showPlayHistoryDialog());

        // 竖屏全屏测试按钮
        Button btnEnterPortraitFullscreen = findViewById(R.id.btn_enter_portrait_fullscreen);
        Button btnExitPortraitFullscreen = findViewById(R.id.btn_exit_portrait_fullscreen);

        btnEnterPortraitFullscreen.setOnClickListener(v -> {
            log("📱 进入竖屏全屏");
            mVideoView.startPortraitFullScreen();
            log("   isPortraitFullScreen: " + mVideoView.isPortraitFullScreen());
        });

        btnExitPortraitFullscreen.setOnClickListener(v -> {
            log("📱 退出竖屏全屏");
            mVideoView.stopPortraitFullScreen();
            log("   isPortraitFullScreen: " + mVideoView.isPortraitFullScreen());
        });

        // 更多功能示例按钮
        Button btnSpeedTest = findViewById(R.id.btn_speed_test);
        Button btnSwitchPlayer = findViewById(R.id.btn_switch_player);
        Button btnSubtitleTest = findViewById(R.id.btn_subtitle_test);
        Button btnPipTest = findViewById(R.id.btn_pip_test);

        btnSpeedTest.setOnClickListener(v -> showSpeedDialog());
        btnSwitchPlayer.setOnClickListener(v -> showPlayerSwitchDialog());
        btnSubtitleTest.setOnClickListener(v -> testSubtitle());
        btnPipTest.setOnClickListener(v -> enterPictureInPicture());

        // 选集和播放功能按钮
        Button btnAddEpisodes = findViewById(R.id.btn_add_episodes);
        Button btnShowPlaylist = findViewById(R.id.btn_show_playlist);
        Button btnPlayNext = findViewById(R.id.btn_play_next);
        Button btnPlayMode = findViewById(R.id.btn_play_mode);

        btnAddEpisodes.setOnClickListener(v -> addEpisodes());
        btnShowPlaylist.setOnClickListener(v -> showPlaylist());
        btnPlayNext.setOnClickListener(v -> playNextEpisode());
        btnPlayMode.setOnClickListener(v -> showPlayModeDialog());

        // 投屏和下载功能按钮
        Button btnCastScreen = findViewById(R.id.btn_cast_screen);
        Button btnDownloadVideo = findViewById(R.id.btn_download_video);
        Button btnClearEpisodes = findViewById(R.id.btn_clear_episodes);
        Button btnTestFeature = findViewById(R.id.btn_test_feature);

        btnCastScreen.setOnClickListener(v -> testCastScreen());
        btnDownloadVideo.setOnClickListener(v -> testDownloadVideo());
        btnClearEpisodes.setOnClickListener(v -> clearEpisodes());
        btnTestFeature.setOnClickListener(v -> showMoreTestDialog());

        log("🍊 橘子播放器 SDK Demo 启动");
        log("基于 GSYVideoPlayer 开源框架");
    }

    private void playInputUrl(boolean useSniff) {
        String url = mEtVideoUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            log("❌ 请输入视频链接");
            return;
        }

        mCurrentUrl = url;
        mCurrentTitle = "自定义视频";

        if (useSniff) {
            log("🔍 开始嗅探: " + getShortUrl(url));
            // 使用嗅探播放 - 先设置URL再启动嗅探
            mVideoView.setUrl(url);
            mVideoView.startSniffing();
        } else {
            log("▶ 直接播放: " + getShortUrl(url));
            mVideoView.setUp(url, false, mCurrentTitle);
            mVideoView.startPlayLogic();
        }

        // 更新标题
        if (mVideoView.getTitleView() != null) {
            mVideoView.getTitleView().setTitle(mCurrentTitle);
        }
    }

    private String getShortUrl(String url) {
        if (url.length() > 50) {
            return url.substring(0, 47) + "...";
        }
        return url;
    }

    /**
     * 测试：设置链接后立即播放（复现准备视图问题）
     */
    private void testQuickPlay() {
        String url = mEtVideoUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            log("❌ 请输入视频链接");
            return;
        }

        log("🧪 测试快速播放: " + getShortUrl(url));
        log("   步骤1: setUp()");
        mVideoView.setUp(url, false, "测试视频");
        log("   步骤2: 立即 startPlayLogic()");
        mVideoView.startPlayLogic();
        log("   ⚠️ 观察加载动画是否正常显示（不应该在准备视图上）");
    }

    private void initPlayer() {
        // 创建控制器
        mController = new OrangeVideoController(this);
        mVideoView.setVideoController(mController);

        // 设置加载动画（默认已是 LINE_SCALE_PULSE_OUT）
        mController.setLoading(OrangeVideoController.IndicatorType.LINE_SCALE_PULSE_OUT);

        // 添加默认控制组件（内部会自动初始化弹幕）
        mController.addDefaultControlComponent(mCurrentTitle, false);

        // 获取弹幕控制器（用于加载测试数据）
        if (mController.isDanmakuAvailable()) {
            mDanmakuController = (DanmakuControllerImpl) mController.getDanmakuController();
            loadTestDanmaku();
            log("✓ 弹幕功能已启用");
        }

        // 设置嗅探监听器
        setupSniffingListener();

        // 设置测试视频列表
        setupVideoList();

        // 设置视频
        mVideoView.setUp(mCurrentUrl, false, mCurrentTitle);
        mVideoView.setLooping(false);
        mVideoView.setAutoRotateOnFullscreen(true);
        mVideoView.startPlayLogic();
        // 设置标题
        if (mVideoView.getTitleView() != null) {
            mVideoView.getTitleView().setTitle(mCurrentTitle);
        }

        log("✓ 播放器初始化完成");
        log("✓ 加载动画: LINE_SCALE_PULSE_OUT");
    }

    /**
     * 设置嗅探监听器
     */
    private void setupSniffingListener() {
        mVideoView.addOnStateChangeListener(new OnStateChangeListener() {
            @Override
            public void onPlayStateChanged(int playState) {
                // 处理嗅探状态
                if (playState == OrangevideoView.STATE_STARTSNIFFING) {
                    log("🔍 嗅探开始...");
                } else if (playState == OrangevideoView.STATE_ENDSNIFFING) {
                    log("✓ 嗅探结束");
                }
            }

            @Override
            public void onPlayerStateChanged(int playerState) {
                // 不处理
            }
        });

        // 添加嗅探结果监听器
        mVideoView.addOnStateChangeListener(new OrangevideoView.OnSniffingAdapter() {
            @Override
            public void onSniffingReceived(String contentType, HashMap<String, String> headers,
                    String title, String url) {
                runOnUiThread(() -> {
                    log("📹 发现视频: " + getShortUrl(url));
                    if (title != null && !title.isEmpty()) {
                        log("   标题: " + title);
                    }
                    if (contentType != null) {
                        log("   类型: " + contentType);
                    }
                });
            }

            @Override
            public void onSniffingFinish(java.util.List<VideoSniffing.VideoInfo> videoList, int videoSize) {
                runOnUiThread(() -> {
                    if (videoSize > 0) {
                        log("✓ 嗅探完成，共发现 " + videoSize + " 个视频");
                        // 自动播放第一个视频
                        if (videoList != null && !videoList.isEmpty()) {
                            VideoSniffing.VideoInfo firstVideo = videoList.get(0);
                            String videoUrl = firstVideo.url;
                            log("▶ 自动播放: " + getShortUrl(videoUrl));
                            mVideoView.setUp(videoUrl, false, mCurrentTitle);
                            mVideoView.startPlayLogic();
                        }
                    } else {
                        log("❌ 嗅探完成，未发现视频");
                    }
                });
            }
        });
    }

    private void initPiPHelper() {
        mPiPHelper = new PiPHelper(this, mVideoView);

        // 检查是否从 PiP 恢复
        long restorePosition = mPiPHelper.checkPiPRestore(mCurrentUrl);
        if (restorePosition > 0) {
            OnStateChangeListener restoreListener = new OnStateChangeListener() {
                @Override
                public void onPlayerStateChanged(int playerState) {
                }

                @Override
                public void onPlayStateChanged(int playState) {
                    if (playState != com.orange.playerlibrary.PlayerConstants.STATE_PREPARED) {
                        return;
                    }
                    mVideoView.removeOnStateChangeListener(this);
                    mVideoView.postDelayed(() -> {
                        mVideoView.seekTo(restorePosition);
                        mPiPHelper.clearPendingSeekPosition();
                    }, 200);
                }
            };
            mVideoView.addOnStateChangeListener(restoreListener);
        }
        log("✓ 画中画功能已启用");
    }

    private void setupVideoList() {
        ArrayList<HashMap<String, Object>> videoList = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            HashMap<String, Object> video = new HashMap<>();
            video.put("name", "第" + i + "集");
            video.put("url", mCurrentUrl);
            videoList.add(video);
        }
        mController.setVideoList(videoList);
    }

    // ===== 弹幕测试方法 =====

    private void loadTestDanmaku() {
        if (mDanmakuController == null)
            return;

        List<IDanmakuController.DanmakuItem> danmakus = new ArrayList<>();
        String[] texts = { "测试弹幕1", "橘子播放器！", "666", "前方高能", "好看" };
        int[] colors = { Color.WHITE, Color.RED, Color.GREEN, Color.CYAN, Color.YELLOW };

        for (int i = 0; i < texts.length; i++) {
            danmakus.add(new IDanmakuController.DanmakuItem(
                    texts[i], colors[i], (i + 1) * 3000, false));
        }
        mDanmakuController.setDanmakuData(danmakus);
    }

    private void loadBatchDanmaku() {
        if (mDanmakuController == null) {
            log("❌ 弹幕功能不可用");
            return;
        }

        List<IDanmakuController.DanmakuItem> danmakus = new ArrayList<>();
        int[] colors = { Color.WHITE, Color.RED, Color.GREEN, Color.CYAN, Color.YELLOW };
        long currentPos = mVideoView.getCurrentPositionWhenPlaying();

        for (int i = 0; i < 30; i++) {
            danmakus.add(new IDanmakuController.DanmakuItem(
                    "弹幕" + (i + 1), colors[i % colors.length], currentPos + i * 500, false));
        }
        mDanmakuController.setDanmakuData(danmakus);
        log("✓ 加载 30 条弹幕");
    }

    private void sendDanmaku() {
        if (mDanmakuController != null) {
            mDanmakuController.sendDanmaku("用户弹幕 " + System.currentTimeMillis() % 1000, Color.YELLOW);
            log("✓ 发送弹幕");
        } else {
            log("❌ 弹幕功能不可用");
        }
    }

    private void toggleDanmaku(Button btn) {
        if (mDanmakuController != null) {
            boolean enabled = !mDanmakuController.isDanmakuEnabled();
            mDanmakuController.setDanmakuEnabled(enabled);
            btn.setText(enabled ? "关闭弹幕" : "开启弹幕");
            log("弹幕: " + (enabled ? "开启" : "关闭"));
        }
    }

    private void setupBackPressedHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (GSYVideoManager.isFullState(MainActivity.this)) {
                    GSYVideoManager.backFromWindowFull(MainActivity.this);
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    // ===== 生命周期 =====

    // 记录切后台前是否在播放
    private boolean mWasPlayingBeforeBackground = false;

    @Override
    protected void onPause() {
        super.onPause();
        if (mPiPHelper != null && mPiPHelper.handleOnPause()) {
            return;
        }
        // 不在 onPause 暂停，改为在 onStop 暂停
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPiPHelper != null && mPiPHelper.handleOnResume()) {
            return;
        }
        // 如果切后台前在播放，且不是用户主动暂停的，则恢复播放
        if (mWasPlayingBeforeBackground && !mVideoView.isUserPaused()) {
            mVideoView.onVideoResume();
        }
        mWasPlayingBeforeBackground = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mPiPHelper != null && mPiPHelper.handleOnStop()) {
            return;
        }
        // 在 onStop 中暂停播放
        mWasPlayingBeforeBackground = mVideoView.isPlaying();
        if (mWasPlayingBeforeBackground) {
            mVideoView.onVideoPause();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 处理 MediaProjection 权限结果（语音识别需要）
        if (mController != null && mController.getVideoEventManager() != null) {
            mController.getVideoEventManager().handleMediaProjectionResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPiP, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPiP, newConfig);
        if (mPiPHelper != null) {
            mPiPHelper.onPictureInPictureModeChanged(isInPiP, mCurrentUrl);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mController != null) {
            mController.releaseDanmaku();
        }
        mVideoView.release();
    }

    // ===== Demo 辅助方法 =====

    private void log(String msg) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        mLogBuilder.append("[").append(timestamp).append("] ").append(msg).append("\n");

        // 限制日志行数
        String[] lines = mLogBuilder.toString().split("\n");
        if (lines.length > 50) {
            mLogBuilder = new StringBuilder();
            for (int i = lines.length - 50; i < lines.length; i++) {
                mLogBuilder.append(lines[i]).append("\n");
            }
        }

        if (mTvDebugLog != null) {
            mTvDebugLog.setText(mLogBuilder.toString());
            // 自动滚动到底部
            if (mScrollLog != null) {
                mScrollLog.post(() -> mScrollLog.fullScroll(ScrollView.FOCUS_DOWN));
            }
        }
    }

    // ===== 播放历史功能 =====

    private void showPlayHistoryDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_play_history, null);

        RecyclerView rvHistory = dialogView.findViewById(R.id.rv_history);
        TextView tvEmpty = dialogView.findViewById(R.id.tv_empty);
        Button btnClear = dialogView.findViewById(R.id.btn_clear_history);

        // 获取历史列表
        List<PlayHistory> historyList = PlayHistoryManager.getInstance(this).getHistoryList(50);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (historyList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            rvHistory.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            rvHistory.setVisibility(View.VISIBLE);

            rvHistory.setLayoutManager(new LinearLayoutManager(this));
            PlayHistoryAdapter adapter = new PlayHistoryAdapter(historyList,
                    new PlayHistoryAdapter.OnItemClickListener() {
                        @Override
                        public void onItemClick(PlayHistory history) {
                            // 播放选中的视频
                            dialog.dismiss();
                            playFromHistory(history);
                        }

                        @Override
                        public void onDeleteClick(PlayHistory history, int position) {
                            // 删除历史记录
                            PlayHistoryManager.getInstance(MainActivity.this).deleteHistory(history.getVideoUrl());
                            historyList.remove(position);
                            rvHistory.getAdapter().notifyItemRemoved(position);
                            log("🗑 删除历史: " + (history.getVideoTitle().isEmpty() ? "未命名" : history.getVideoTitle()));

                            if (historyList.isEmpty()) {
                                tvEmpty.setVisibility(View.VISIBLE);
                                rvHistory.setVisibility(View.GONE);
                            }
                        }
                    });
            rvHistory.setAdapter(adapter);
        }

        // 清空按钮
        btnClear.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("确认清空")
                    .setMessage("确定要清空所有播放历史吗？")
                    .setPositiveButton("清空", (d, w) -> {
                        PlayHistoryManager.getInstance(this).clearAll();
                        historyList.clear();
                        tvEmpty.setVisibility(View.VISIBLE);
                        rvHistory.setVisibility(View.GONE);
                        log("🗑 已清空所有播放历史");
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        dialog.show();
        log("📋 打开播放历史，共 " + historyList.size() + " 条记录");
    }

    private void playFromHistory(PlayHistory history) {
        String url = history.getVideoUrl();
        String title = history.getVideoTitle();
        long position = history.getPosition();

        mCurrentUrl = url;
        mCurrentTitle = title.isEmpty() ? "历史视频" : title;

        mEtVideoUrl.setText(url);
        mVideoView.setUp(url, false, mCurrentTitle);

        // 设置从历史位置开始播放
        if (position > 0) {
            mVideoView.setSeekOnStart(position);
        }

        mVideoView.startPlayLogic();

        if (mVideoView.getTitleView() != null) {
            mVideoView.getTitleView().setTitle(mCurrentTitle);
        }

        log("▶ 从历史播放: " + getShortUrl(url));
        if (position > 0) {
            log("   续播位置: " + formatTime(position));
        }
    }

    private String formatTime(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    // ===== 更多功能示例 =====

    /**
     * 倍速测试
     */
    private void showSpeedDialog() {
        String[] speeds = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x", "3.0x"};
        float[] speedValues = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f};
        
        new AlertDialog.Builder(this)
            .setTitle("选择播放倍速")
            .setItems(speeds, (dialog, which) -> {
                float speed = speedValues[which];
                mVideoView.setSpeed(speed);
                log("⚡ 设置倍速: " + speeds[which]);
            })
            .show();
    }

    /**
     * 切换播放器内核
     */
    private void showPlayerSwitchDialog() {
        String[] players = {"系统播放器", "ExoPlayer", "IJK播放器", "阿里云播放器"};
        String[] engines = {
            com.orange.playerlibrary.PlayerConstants.ENGINE_DEFAULT,
            com.orange.playerlibrary.PlayerConstants.ENGINE_EXO,
            com.orange.playerlibrary.PlayerConstants.ENGINE_IJK,
            com.orange.playerlibrary.PlayerConstants.ENGINE_ALI
        };
        
        // 获取当前播放器内核
        com.orange.playerlibrary.PlayerSettingsManager settingsManager = 
            com.orange.playerlibrary.PlayerSettingsManager.getInstance(this);
        String currentEngine = settingsManager.getPlayerEngine();
        
        // 找到当前选中的索引
        int currentIndex = 0;
        for (int i = 0; i < engines.length; i++) {
            if (engines[i].equals(currentEngine)) {
                currentIndex = i;
                break;
            }
        }
        
        new AlertDialog.Builder(this)
            .setTitle("选择播放器内核")
            .setSingleChoiceItems(players, currentIndex, (dialog, which) -> {
                String engine = engines[which];
                
                // 如果选择的是当前内核，不需要切换
                if (engine.equals(currentEngine)) {
                    dialog.dismiss();
                    return;
                }
                
                long currentPosition = mVideoView.getCurrentPositionWhenPlaying();
                boolean wasPlaying = mVideoView.isPlaying();
                
                // 1. 保存播放器内核设置（持久化）
                settingsManager.setPlayerEngine(engine);
                
                // 2. 完全释放旧播放器
                mVideoView.release();
                com.shuyu.gsyvideoplayer.GSYVideoManager.releaseAllVideos();
                
                // 3. 切换播放器工厂
                mVideoView.selectPlayerFactory(engine);
                
                // 4. 重新设置视频
                mVideoView.setUp(mCurrentUrl, false, mCurrentTitle);
                
                // 5. 从当前位置继续播放
                if (currentPosition > 0) {
                    mVideoView.setSeekOnStart(currentPosition);
                }
                
                if (wasPlaying) {
                    mVideoView.startPlayLogic();
                }
                
                log("🔄 切换播放器: " + players[which] + " (已持久化)");
                dialog.dismiss();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /**
     * 字幕测试
     */
    private void testSubtitle() {
        // 示例字幕URL（需要替换为实际的字幕文件）
        String subtitleUrl = "https://example.com/subtitle.srt";
        
        new AlertDialog.Builder(this)
            .setTitle("字幕功能")
            .setMessage("字幕功能需要提供 .srt 或 .ass 格式的字幕文件URL")
            .setPositiveButton("加载示例字幕", (dialog, which) -> {
                if (mController != null) {
                    mController.loadSubtitle(subtitleUrl, 
                        new com.orange.playerlibrary.subtitle.SubtitleManager.OnSubtitleLoadListener() {
                            @Override
                            public void onLoadSuccess(int count) {
                                log("✓ 字幕加载成功，共 " + count + " 条");
                                mController.startSubtitle();
                            }
                            
                            @Override
                            public void onLoadFailed(String error) {
                                log("❌ 字幕加载失败: " + error);
                            }
                        });
                }
            })
            .setNegativeButton("切换字幕显示", (dialog, which) -> {
                if (mController != null) {
                    mController.toggleSubtitle();
                    boolean enabled = mController.isSubtitleEnabled();
                    log("字幕: " + (enabled ? "显示" : "隐藏"));
                }
            })
            .setNeutralButton("取消", null)
            .show();
    }

    /**
     * 进入画中画模式
     */
    private void enterPictureInPicture() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                android.app.PictureInPictureParams params = 
                    new android.app.PictureInPictureParams.Builder()
                        .setAspectRatio(new android.util.Rational(16, 9))
                        .build();
                enterPictureInPictureMode(params);
                log("📺 进入画中画模式");
            } catch (Exception e) {
                log("❌ 进入画中画失败: " + e.getMessage());
            }
        } else {
            log("❌ 画中画模式需要 Android 8.0+");
        }
    }

    // ===== 选集功能 =====

    /**
     * 添加选集
     */
    private void addEpisodes() {
        // 创建测试选集列表
        ArrayList<HashMap<String, Object>> videoList = new ArrayList<>();
        
        // 添加多个测试视频
        String[] videoUrls = {
            "http://player.alicdn.com/video/aliyunmedia.mp4",
            "https://media.w3.org/2010/05/sintel/trailer.mp4",
            "http://vfx.mtime.cn/Video/2019/03/21/mp4/190321153853126488.mp4",
            "http://vfx.mtime.cn/Video/2019/03/19/mp4/190319222227698228.mp4",
            "http://vfx.mtime.cn/Video/2019/03/18/mp4/190318231014076505.mp4"
        };
        
        for (int i = 0; i < videoUrls.length; i++) {
            HashMap<String, Object> video = new HashMap<>();
            video.put("name", "第" + (i + 1) + "集");
            video.put("url", videoUrls[i]);
            videoList.add(video);
        }
        
        // 设置到控制器
        mController.setVideoList(videoList);
        log("✓ 添加选集: 共 " + videoList.size() + " 集");
    }

    /**
     * 显示选集列表
     */
    private void showPlaylist() {
        if (mController != null && mController.getVideoEventManager() != null) {
            mController.getVideoEventManager().showPlaylistDialog();
            log("📋 显示选集列表");
        } else {
            log("❌ 选集功能不可用");
        }
    }

    /**
     * 播放下一集
     */
    private void playNextEpisode() {
        if (mController != null && mController.getVideoEventManager() != null) {
            mController.getVideoEventManager().playNextEpisode();
            log("⏭ 播放下一集");
        } else {
            log("❌ 下一集功能不可用");
        }
    }

    /**
     * 清空选集
     */
    private void clearEpisodes() {
        if (mController != null) {
            mController.removeVideoList();
            log("🗑 清空选集列表");
        }
    }

    /**
     * 播放方式设置
     */
    private void showPlayModeDialog() {
        String[] modes = {"顺序播放", "单集循环", "播放后暂停"};
        String[] modeValues = {"sequential", "single_loop", "play_pause"};
        
        com.orange.playerlibrary.PlayerSettingsManager settings = 
            com.orange.playerlibrary.PlayerSettingsManager.getInstance(this);
        String currentMode = settings.getPlayMode();
        
        // 找到当前模式的索引
        int currentIndex = 0;
        for (int i = 0; i < modeValues.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                currentIndex = i;
                break;
            }
        }
        
        new AlertDialog.Builder(this)
            .setTitle("选择播放方式")
            .setSingleChoiceItems(modes, currentIndex, (dialog, which) -> {
                String mode = modeValues[which];
                settings.setPlayMode(mode);
                log("🔄 设置播放方式: " + modes[which]);
                dialog.dismiss();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ===== 投屏和下载功能 =====

    /**
     * 测试投屏功能
     */
    private void testCastScreen() {
        // 检查 DLNA 是否可用
        if (com.orange.playerlibrary.cast.DLNACastManager.isDLNAAvailable()) {
            try {
                com.orange.playerlibrary.cast.DLNACastManager.getInstance().startCast(
                    this,
                    mCurrentUrl,
                    mCurrentTitle
                );
                log("📡 启动投屏功能");
            } catch (Exception e) {
                log("❌ 投屏失败: " + e.getMessage());
            }
        } else {
            new AlertDialog.Builder(this)
                .setTitle("投屏功能不可用")
                .setMessage("投屏功能需要添加以下依赖：\n\n" +
                           "implementation 'com.github.CarGuo.GSYVideoPlayer:gsyVideoPlayer-java:v8.6.0-release-jitpack'\n" +
                           "implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.5.30'")
                .setPositiveButton("确定", null)
                .show();
            log("❌ 投屏功能不可用（缺少依赖）");
        }
    }

    /**
     * 测试下载视频功能
     */
    private void testDownloadVideo() {
        new AlertDialog.Builder(this)
            .setTitle("下载视频")
            .setMessage("当前视频：\n" + mCurrentTitle + "\n\n" +
                       "URL: " + getShortUrl(mCurrentUrl) + "\n\n" +
                       "下载功能需要自行实现，可以使用：\n" +
                       "1. Android DownloadManager\n" +
                       "2. OkHttp 下载\n" +
                       "3. 第三方下载库")
            .setPositiveButton("复制链接", (dialog, which) -> {
                android.content.ClipboardManager clipboard = 
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                android.content.ClipData clip = 
                    android.content.ClipData.newPlainText("video_url", mCurrentUrl);
                clipboard.setPrimaryClip(clip);
                log("✓ 视频链接已复制到剪贴板");
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /**
     * 更多测试功能
     */
    private void showMoreTestDialog() {
        String[] features = {
            "测试循环播放",
            "测试静音/恢复",
            "测试亮度调节",
            "测试音量调节",
            "测试截图功能",
            "测试播放信息"
        };
        
        new AlertDialog.Builder(this)
            .setTitle("更多测试功能")
            .setItems(features, (dialog, which) -> {
                switch (which) {
                    case 0: // 循环播放
                        boolean looping = !mVideoView.isLooping();
                        mVideoView.setLooping(looping);
                        log("🔁 循环播放: " + (looping ? "开启" : "关闭"));
                        break;
                    case 1: // 静音
                        // 通过音量设置实现静音
                        android.media.AudioManager audioManager = 
                            (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                        int currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                        if (currentVolume > 0) {
                            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0);
                            log("🔇 已静音");
                        } else {
                            int maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, maxVolume / 2, 0);
                            log("🔊 已恢复音量");
                        }
                        break;
                    case 2: // 亮度调节
                        showBrightnessDialog();
                        break;
                    case 3: // 音量调节
                        showVolumeDialog();
                        break;
                    case 4: // 截图
                        captureScreenshot();
                        break;
                    case 5: // 播放信息
                        showPlaybackInfo();
                        break;
                }
            })
            .show();
    }

    /**
     * 亮度调节对话框
     */
    private void showBrightnessDialog() {
        View dialogView = new android.widget.LinearLayout(this);
        ((android.widget.LinearLayout) dialogView).setOrientation(android.widget.LinearLayout.VERTICAL);
        ((android.widget.LinearLayout) dialogView).setPadding(50, 30, 50, 30);
        
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("调节亮度");
        title.setTextSize(18);
        title.setPadding(0, 0, 0, 20);
        ((android.widget.LinearLayout) dialogView).addView(title);
        
        android.widget.SeekBar seekBar = new android.widget.SeekBar(this);
        seekBar.setMax(100);
        
        // 获取当前亮度
        float brightness = getWindow().getAttributes().screenBrightness;
        if (brightness < 0) brightness = 0.5f;
        seekBar.setProgress((int) (brightness * 100));
        
        seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = progress / 100f;
                getWindow().setAttributes(lp);
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        
        ((android.widget.LinearLayout) dialogView).addView(seekBar);
        
        new AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("确定", null)
            .show();
    }

    /**
     * 音量调节对话框
     */
    private void showVolumeDialog() {
        View dialogView = new android.widget.LinearLayout(this);
        ((android.widget.LinearLayout) dialogView).setOrientation(android.widget.LinearLayout.VERTICAL);
        ((android.widget.LinearLayout) dialogView).setPadding(50, 30, 50, 30);
        
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("调节音量");
        title.setTextSize(18);
        title.setPadding(0, 0, 0, 20);
        ((android.widget.LinearLayout) dialogView).addView(title);
        
        android.widget.SeekBar seekBar = new android.widget.SeekBar(this);
        
        android.media.AudioManager audioManager = 
            (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        int maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        
        seekBar.setMax(maxVolume);
        seekBar.setProgress(currentVolume);
        
        seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, progress, 0);
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        
        ((android.widget.LinearLayout) dialogView).addView(seekBar);
        
        new AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("确定", null)
            .show();
    }

    /**
     * 截图功能
     */
    private void captureScreenshot() {
        log("📷 截图功能需要自行实现");
        log("   提示: 可以使用 TextureView.getBitmap() 或 SurfaceView 截图");
        new AlertDialog.Builder(this)
            .setTitle("截图功能")
            .setMessage("截图功能需要根据播放器渲染方式实现：\n\n" +
                       "1. TextureView: 使用 getBitmap()\n" +
                       "2. SurfaceView: 使用 PixelCopy API\n" +
                       "3. 或使用第三方截图库")
            .setPositiveButton("确定", null)
            .show();
    }

    /**
     * 显示播放信息
     */
    private void showPlaybackInfo() {
        long currentPos = mVideoView.getCurrentPositionWhenPlaying();
        long duration = mVideoView.getDuration();
        int bufferPercentage = mVideoView.getBuffterPoint();
        
        String info = "播放信息：\n\n" +
                     "标题: " + mCurrentTitle + "\n" +
                     "URL: " + getShortUrl(mCurrentUrl) + "\n\n" +
                     "当前位置: " + formatTime(currentPos) + "\n" +
                     "总时长: " + formatTime(duration) + "\n" +
                     "缓冲进度: " + bufferPercentage + "%\n" +
                     "播放状态: " + (mVideoView.isPlaying() ? "播放中" : "暂停") + "\n" +
                     "全屏状态: " + (mVideoView.isFullScreen() ? "全屏" : "普通") + "\n" +
                     "循环播放: " + (mVideoView.isLooping() ? "开启" : "关闭");
        
        new AlertDialog.Builder(this)
            .setTitle("播放信息")
            .setMessage(info)
            .setPositiveButton("确定", null)
            .show();
    }

    // ===== 播放历史适配器 =====

    private static class PlayHistoryAdapter extends RecyclerView.Adapter<PlayHistoryAdapter.ViewHolder> {

        private final List<PlayHistory> mList;
        private final OnItemClickListener mListener;

        interface OnItemClickListener {
            void onItemClick(PlayHistory history);

            void onDeleteClick(PlayHistory history, int position);
        }

        PlayHistoryAdapter(List<PlayHistory> list, OnItemClickListener listener) {
            mList = list;
            mListener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_play_history, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PlayHistory history = mList.get(position);
            holder.bind(history, mListener, position);
        }

        @Override
        public int getItemCount() {
            return mList.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvPosition, tvTime;
            ImageView ivThumbnail, btnDelete;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(R.id.tv_title);
                tvPosition = itemView.findViewById(R.id.tv_position);
                tvTime = itemView.findViewById(R.id.tv_time);
                ivThumbnail = itemView.findViewById(R.id.iv_thumbnail);
                btnDelete = itemView.findViewById(R.id.btn_delete);
            }

            void bind(PlayHistory history, OnItemClickListener listener, int position) {
                // 标题
                String title = history.getVideoTitle();
                tvTitle.setText(title.isEmpty() ? "未命名视频" : title);

                // 缩略图
                String thumbnailBase64 = history.getThumbnailBase64();
                if (thumbnailBase64 != null && !thumbnailBase64.isEmpty()) {
                    try {
                        byte[] bytes = android.util.Base64.decode(thumbnailBase64, android.util.Base64.NO_WRAP);
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0,
                                bytes.length);
                        ivThumbnail.setImageBitmap(bitmap);
                    } catch (Exception e) {
                        ivThumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
                    }
                } else {
                    ivThumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
                }

                // 播放位置和进度
                tvPosition.setText(history.getFormattedPosition() + " / " + history.getFormattedDuration() + " ("
                        + history.getProgressPercent() + "%)");

                // 时间
                tvTime.setText(getRelativeTime(history.getLastPlayTime()));

                // 点击播放
                itemView.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onItemClick(history);
                    }
                });

                // 删除按钮
                btnDelete.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onDeleteClick(history, position);
                    }
                });
            }

            private String getRelativeTime(long timestamp) {
                long now = System.currentTimeMillis();
                long diff = now - timestamp;

                if (diff < 60 * 1000) {
                    return "刚刚";
                } else if (diff < 60 * 60 * 1000) {
                    return (diff / (60 * 1000)) + "分钟前";
                } else if (diff < 24 * 60 * 60 * 1000) {
                    return (diff / (60 * 60 * 1000)) + "小时前";
                } else if (diff < 7 * 24 * 60 * 60 * 1000) {
                    return (diff / (24 * 60 * 60 * 1000)) + "天前";
                } else {
                    return new java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
                            .format(new java.util.Date(timestamp));
                }
            }
        }
    }
}
