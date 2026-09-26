package com.orange.player;

import android.content.res.Configuration;
import android.app.Dialog;
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
 * https://github.com/706412584/orangeplayer
 */
public class MainActivity extends AppCompatActivity {

    private static final String DEFAULT_VIDEO_URL = "http://player.alicdn.com/video/aliyunmedia.mp4"; // 默认MP4
    // private static final String DEFAULT_VIDEO_URL = "https://your_test_m3u8_url_here.m3u8"; 
    private static final String DEFAULT_VIDEO_TITLE = "测试视频";

    private OrangevideoView mVideoView;
    private OrangeVideoController mController;
    private PiPHelper mPiPHelper;
    private DanmakuControllerImpl mDanmakuController;
    private com.orange.player.session.OrangePlayerSessionHelper mSessionHelper;

    // Demo UI
    private EditText mEtVideoUrl;
    private TextView mTvDebugLog;
    private ScrollView mScrollLog;
    private StringBuilder mLogBuilder = new StringBuilder();

    private String mCurrentUrl = DEFAULT_VIDEO_URL;
    private String mCurrentTitle = DEFAULT_VIDEO_TITLE;

    /**
     * 【测试专用】adb 广播入口：直接触发播放器 UI 事件（绕过点击链路）。
     * 用法：
     *   adb shell am broadcast -a com.orange.player.TEST_CMD --es cmd subtitle_dialog
     * 支持命令见 VideoEventManager.handleTestCommand()。
     */
    private static final String TEST_CMD_ACTION = "com.orange.player.TEST_CMD";
    private final android.content.BroadcastReceiver mTestCmdReceiver =
            new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            final String cmd = intent.getStringExtra("cmd");
            if (cmd == null) {
                return;
            }
            // 必须在主线程执行（涉及 UI 弹窗）
            runOnUiThread(() -> {
                // asr_smoke: 引擎冒烟（不依赖播放器）；格式 asr_smoke:<lang>:<wav绝对路径>
                if (cmd.startsWith("asr_smoke:")) {
                    handleAsrSmoke(cmd.substring("asr_smoke:".length()));
                    return;
                }
                // asr_gen_file: 全管线生成（视频→抽音频→识别→srt→加载显示）
                // 格式 asr_gen_file:<lang>:<视频绝对路径>
                if (cmd.startsWith("asr_gen_file:")) {
                    handleAsrGenFile(cmd.substring("asr_gen_file:".length()));
                    return;
                }
                // asr_diar_smoke: 说话人分离冒烟（不依赖播放器）
                // 格式 asr_diar_smoke:<lang>:<wav绝对路径>
                if (cmd.startsWith("asr_diar_smoke:")) {
                    handleAsrDiarSmoke(cmd.substring("asr_diar_smoke:".length()));
                    return;
                }
                // play_file: 播放本地文件（测试用）；格式 play_file:<绝对路径>
                if (cmd.startsWith("play_file:")) {
                    String path = cmd.substring("play_file:".length()).trim();
                    if (mEtVideoUrl != null && !path.isEmpty()) {
                        mEtVideoUrl.setText(path);
                        playInputUrl(false);
                    }
                    return;
                }
                // v3_episodes_smoke: 用 v3 宿主真实数组形状验证门面选集解析
                if ("v3_episodes_smoke".equals(cmd)) {
                    v3EpisodesSmoke();
                    return;
                }
                // play_url: 播放远程 URL（测试用）；格式 play_url:<url>
                if (cmd.startsWith("play_url:")) {
                    String url = cmd.substring("play_url:".length()).trim();
                    if (mEtVideoUrl != null && !url.isEmpty()) {
                        mEtVideoUrl.setText(url);
                        playInputUrl(false);
                    }
                    return;
                }
                if (mController == null || mController.getVideoEventManager() == null) {
                    android.util.Log.w("MainActivity", "TEST_CMD: 控制器未就绪, cmd=" + cmd);
                    return;
                }
                mController.getVideoEventManager().handleTestCommand(cmd);
            });
        }
    };

    /**
     * 【测试专用】ASR 冒烟：加载模型目录识别 wav 打印结果与耗时。
     * 格式：asr_smoke:<lang>:<wav路径>；模型目录固定 app 私有 filesDir/asr_model
     * 用法：
     *   adb push docs/asr_model/ /sdcard/Android/data/com.orange.player/files/asr_model/
     *   adb push docs/asr_samples/zh.wav /sdcard/Android/data/com.orange.player/files/asr_zh.wav
     *   adb shell am broadcast -a com.orange.player.TEST_CMD --es cmd "asr_smoke:auto:/sdcard/Android/data/com.orange.player/files/asr_zh.wav"
     */
    private void handleAsrSmoke(String spec) {
        try {
            int sep = spec.indexOf(':');
            String lang = sep > 0 ? spec.substring(0, sep).trim() : "auto";
            String wavPath = sep > 0 ? spec.substring(sep + 1).trim() : spec;
            final java.io.File modelDir = new java.io.File(getExternalFilesDir(null), "asr_model");
            if (!new java.io.File(modelDir, "model.int8.onnx").exists()) {
                android.util.Log.w("MainActivity", "ASR 冒烟: 模型缺失 " + modelDir);
                return;
            }
            android.util.Log.d("MainActivity", "ASR 冒烟开始 lang=" + lang + " wav=" + wavPath);

            final com.orange.playerlibrary.speech.BatchAsrEngine engine =
                    com.orange.playerlibrary.speech.SherpaAvailabilityChecker.createEngine();
            if (engine == null) {
                android.util.Log.e("MainActivity", "ASR 冒烟: 引擎不可用（模块未链接？）");
                return;
            }
            final String finalLang = lang;
            new Thread(() -> {
                long t0 = System.currentTimeMillis();
                final boolean ok = engine.init(modelDir.getAbsolutePath(), finalLang);
                long tInit = System.currentTimeMillis() - t0;
                if (!ok) {
                    android.util.Log.e("MainActivity", "ASR 冒烟: init 失败");
                    engine.release();
                    return;
                }
                final long[] tDone = {0};
                engine.transcribeFile(wavPath, new com.orange.playerlibrary.speech.BatchAsrEngine.BatchAsrCallback() {
                    @Override
                    public void onReady() {
                    }

                    @Override
                    public void onSegment(String text, long startMs, long endMs) {
                        android.util.Log.d("MainActivity", "ASR 段 [" + startMs + "-" + endMs + "ms] " + text);
                    }

                    @Override
                    public void onProgress(int percent, String stage) {
                    }

                    @Override
                    public void onCompleted(int segmentCount) {
                        tDone[0] = System.currentTimeMillis() - t0;
                        android.util.Log.d("MainActivity", "ASR 冒烟完成: " + segmentCount
                                + " 段, init=" + tInit + "ms, total=" + tDone[0] + "ms");
                        engine.release();
                    }

                    @Override
                    public void onError(int errorCode, String errorMessage) {
                        tDone[0] = System.currentTimeMillis() - t0;
                        android.util.Log.e("MainActivity", "ASR 冒烟失败: code=" + errorCode
                                + " msg=" + errorMessage + " total=" + tDone[0] + "ms");
                        engine.release();
                    }
                }, null);
            }, "asr-smoke").start();
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "ASR 冒烟异常", e);
        }
    }

    /**
     * 【测试专用】说话人分离冒烟：识别 wav 并打印每段的说话人标签与耗时。
     *
     * <p>与 {@link #handleAsrSmoke} 的区别：日志里带说话人编号（S0/S1/…），
     * 用来验证 diarization 是否真的生效、说话人切分是否合理。模型缺失时
     * 引擎静默降级，日志会显示全部段为 S-（无归属）——这本身也是有效结论。
     *
     * <p>格式：asr_diar_smoke:&lt;lang&gt;:&lt;wav路径&gt;；模型目录固定 filesDir/asr_model
     * 用法：
     * <pre>
     *   adb push docs/asr_model/ /sdcard/Android/data/com.orange.player/files/asr_model/
     *   adb push docs/asr_samples/meeting.wav /sdcard/Android/data/com.orange.player/files/
     *   adb shell am broadcast -a com.orange.player.TEST_CMD --es cmd \
     *     "asr_diar_smoke:auto:/sdcard/Android/data/com.orange.player/files/meeting.wav"
     * </pre>
     */
    private void handleAsrDiarSmoke(String spec) {
        try {
            int sep = spec.indexOf(':');
            String lang = sep > 0 ? spec.substring(0, sep).trim() : "auto";
            String wavPath = sep > 0 ? spec.substring(sep + 1).trim() : spec;
            final java.io.File modelDir = new java.io.File(getExternalFilesDir(null), "asr_model");
            if (!new java.io.File(modelDir, "model.int8.onnx").exists()) {
                android.util.Log.w("MainActivity", "说话人冒烟: 模型缺失 " + modelDir);
                return;
            }
            java.io.File diarDir = new java.io.File(modelDir, "diarization");
            android.util.Log.d("MainActivity", "说话人冒烟开始 lang=" + lang + " wav=" + wavPath
                    + " diarDir=" + (diarDir.isDirectory() ? "存在" : "缺失"));

            final com.orange.playerlibrary.speech.BatchAsrEngine engine =
                    com.orange.playerlibrary.speech.SherpaAvailabilityChecker.createEngine();
            if (engine == null) {
                android.util.Log.e("MainActivity", "说话人冒烟: 引擎不可用（模块未链接？）");
                return;
            }
            final String finalLang = lang;
            new Thread(() -> {
                long t0 = System.currentTimeMillis();
                final boolean ok = engine.init(modelDir.getAbsolutePath(), finalLang);
                long tInit = System.currentTimeMillis() - t0;
                if (!ok) {
                    android.util.Log.e("MainActivity", "说话人冒烟: init 失败");
                    engine.release();
                    return;
                }
                final int[] speakerCount = {0};
                final java.util.Set<Integer> seen = new java.util.TreeSet<>();
                engine.transcribeFile(wavPath,
                        new com.orange.playerlibrary.speech.BatchAsrEngine.BatchAsrCallback() {
                            @Override
                            public void onReady() {
                            }

                            @Override
                            public void onSegment(String text, long startMs, long endMs) {
                            }

                            @Override
                            public void onSegmentWithSpeaker(String text, long startMs,
                                                             long endMs, String lg, int speaker) {
                                speakerCount[0]++;
                                seen.add(speaker);
                                String tag = speaker < 0 ? "S-" : ("S" + speaker);
                                android.util.Log.d("MainActivity", "说话人段 [" + startMs + "-"
                                        + endMs + "ms] " + tag + " (" + lg + ") " + text);
                            }

                            @Override
                            public void onProgress(int percent, String stage) {
                                android.util.Log.d("MainActivity", "说话人冒烟进度 " + percent
                                        + "% " + stage);
                            }

                            @Override
                            public void onCompleted(int segmentCount) {
                                long total = System.currentTimeMillis() - t0;
                                android.util.Log.d("MainActivity", "说话人冒烟完成: 回调 "
                                        + speakerCount[0] + " 段(引擎报 " + segmentCount
                                        + "), 出现的说话人=" + seen
                                        + ", init=" + tInit + "ms, total=" + total + "ms");
                                engine.release();
                            }

                            @Override
                            public void onError(int errorCode, String errorMessage) {
                                android.util.Log.e("MainActivity", "说话人冒烟失败: code="
                                        + errorCode + " msg=" + errorMessage);
                                engine.release();
                            }
                        }, null);
            }, "asr-diar-smoke").start();
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "说话人冒烟异常", e);
        }
    }

    /**
     * 【测试专用】ASR 全管线：视频 → 抽音频 → 识别 → srt → 加载到播放器显示。
     * 格式：asr_gen_file:<lang>:<视频绝对路径>
     * 用法：
     *   adb push sample.mp4 /sdcard/Android/data/com.orange.player/files/sample.mp4
     *   adb shell am broadcast -a com.orange.player.TEST_CMD --es cmd \
     *     "asr_gen_file:auto:/sdcard/Android/data/com.orange.player/files/sample.mp4"
     */
    private void handleAsrGenFile(String spec) {
        try {
            int sep = spec.indexOf(':');
            String lang = sep > 0 ? spec.substring(0, sep).trim() : "auto";
            String videoPath = sep > 0 ? spec.substring(sep + 1).trim() : spec;
            final java.io.File videoFile = new java.io.File(videoPath);
            if (!videoFile.exists()) {
                android.util.Log.w("MainActivity", "ASR 生成: 视频不存在 " + videoPath);
                return;
            }
            android.util.Log.d("MainActivity", "ASR 生成开始 lang=" + lang + " video=" + videoPath);

            com.orange.playerlibrary.speech.AsrSubtitleGenerator generator =
                    new com.orange.playerlibrary.speech.AsrSubtitleGenerator(this);
            generator.generate(videoFile, lang,
                    new com.orange.playerlibrary.speech.AsrSubtitleGenerator.GenerateCallback() {
                        @Override
                        public void onProgress(int percent, String stage) {
                            android.util.Log.d("MainActivity", "ASR 生成进度: " + percent + "% " + stage);
                        }

                        @Override
                        public void onSuccess(java.io.File srtFile, int subtitleCount) {
                            android.util.Log.d("MainActivity", "ASR 生成成功: " + subtitleCount
                                    + " 条 srt=" + srtFile);
                            if (srtFile == null) {
                                return;
                            }
                            // 加载字幕到播放器显示
                            if (mController != null) {
                                com.orange.playerlibrary.subtitle.SubtitleManager sm =
                                        mController.getSubtitleManager();
                                if (sm != null) {
                                    sm.loadSubtitle(srtFile, new com.orange.playerlibrary.subtitle.SubtitleManager.OnSubtitleLoadListener() {
                                        @Override
                                        public void onLoadSuccess(int count) {
                                            runOnUiThread(() -> {
                                                sm.show();
                                                mController.startSubtitle();
                                                // 同步持久化开关与控制器图标（用户可能没开"显示字幕"）
                                                com.orange.playerlibrary.PlayerSettingsManager.getInstance(
                                                        MainActivity.this).setSubtitleEnabled(true);
                                                android.util.Log.d("MainActivity",
                                                        "ASR 字幕已加载显示: " + count + " 条");
                                            });
                                        }

                                        @Override
                                        public void onLoadFailed(String error) {
                                            android.util.Log.e("MainActivity", "ASR 字幕加载失败: " + error);
                                        }
                                    });
                                }
                            }
                        }

                        @Override
                        public void onError(int code, String message) {
                            android.util.Log.e("MainActivity", "ASR 生成失败: code=" + code + " msg=" + message);
                        }
                    }, null);
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "ASR 生成异常", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 【测试专用】注册测试命令广播（仅测试环境使用，无安全风险暴露面）
        // Android 13+ (API 33) 起 registerReceiver 必须显式指定 exported 标志，
        // 否则抛 SecurityException；adb shell 需要跨进程发送，故用 EXPORTED
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(TEST_CMD_ACTION);
            int flag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                    ? android.content.Context.RECEIVER_EXPORTED : 0;
            registerReceiver(mTestCmdReceiver, filter, flag);
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "注册测试广播失败", e);
        }

        // M3U8 去广告状态改为从设置恢复（onCreate 后半段按偏好同步 M3U8AdManager，
        // 必须在 setContentView 之前先按设置初始化，避免首帧播放用错状态）
        com.orange.playerlibrary.M3U8AdManager.getInstance(this).setEnabled(
                com.orange.playerlibrary.PlayerSettingsManager.getInstance(this)
                        .isAdRemovalEnabled());
        
        // 默认在 Demo 中开启记忆播放功能
        com.orange.playerlibrary.PlayerSettingsManager.getInstance(this).setMemoryPlayEnabled(true);
        
        // 恢复下载路径到外部隐私可见目录，方便测试和查看下载的文件是否完整
        java.io.File privateDir = new java.io.File(getExternalFilesDir(null), "Download");
        com.orange.playerlibrary.download.SimpleDownloadManager.getInstance(this).setDownloadPath(privateDir.getAbsolutePath());
        
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
        mScrollLog = findViewById(R.id.log_scroll);

        // 调试日志折叠开关
        TextView btnToggleLog = findViewById(R.id.btn_toggle_log);
        btnToggleLog.setOnClickListener(v -> {
            boolean show = mScrollLog.getVisibility() != View.VISIBLE;
            mScrollLog.setVisibility(show ? View.VISIBLE : View.GONE);
            btnToggleLog.setText(show ? "调试日志 ▾" : "调试日志 ▸");
        });

        // 设置默认URL
        mEtVideoUrl.setText(DEFAULT_VIDEO_URL);

        // 视频链接播放按钮
        Button btnPlayUrl = findViewById(R.id.btn_play_url);
        Button btnSniffPlay = findViewById(R.id.btn_sniff_play);
        Button btnPickLocal = findViewById(R.id.btn_pick_local);

        btnPlayUrl.setOnClickListener(v -> playInputUrl(false));
        btnSniffPlay.setOnClickListener(v -> playInputUrl(true));
        btnPickLocal.setOnClickListener(v -> pickLocalVideo());

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
        Button btnSwitchAdRemoval = findViewById(R.id.btn_switch_ad_removal);

        btnSpeedTest.setOnClickListener(v -> showSpeedDialog());
        btnSwitchPlayer.setOnClickListener(v -> showPlayerSwitchDialog());
        btnSubtitleTest.setOnClickListener(v -> testSubtitle());
        btnPipTest.setOnClickListener(v -> enterPictureInPicture());

        // 扩展包管理：按需下载的 native 组件与播放内核（种子/ASR/OCR/翻译/内核）。
        // 面板在 SDK 内（NativeLibManager），宿主只负责入口——故放设置面板而非字幕面板。
        Button btnNativeLibs = findViewById(R.id.btn_native_libs);
        if (btnNativeLibs != null) {
            btnNativeLibs.setOnClickListener(v -> {
                com.orange.playerlibrary.OrangeVideoController controller = mController;
                if (controller == null || controller.getVideoEventManager() == null) {
                    android.widget.Toast.makeText(this, "播放器尚未就绪，请稍后再试",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                controller.getVideoEventManager().showNativeLibsDialog();
            });
        }
        
        // 按设置恢复去广告状态（不再写死开启）
        boolean adRemovalEnabled = com.orange.playerlibrary.PlayerSettingsManager
                .getInstance(this).isAdRemovalEnabled();
        com.orange.playerlibrary.M3U8AdManager adManager =
                com.orange.playerlibrary.M3U8AdManager.getInstance(this);
        adManager.setEnabled(adRemovalEnabled);
        btnSwitchAdRemoval.setText(adRemovalEnabled ? "去广告(开)" : "去广告(关)");

        btnSwitchAdRemoval.setOnClickListener(v -> {
            boolean enabled = !adManager.isEnabled();
            adManager.setEnabled(enabled);
            // 同步持久化，重启后保持用户选择
            com.orange.playerlibrary.PlayerSettingsManager.getInstance(this)
                    .setAdRemovalEnabled(enabled);
            btnSwitchAdRemoval.setText(enabled ? "去广告(开)" : "去广告(关)");
            log(enabled ? "✅ M3U8去广告已开启" : "❌ M3U8去广告已关闭");
            if (enabled) {
                adManager.clearCache(); // 开启时清除缓存确保生效
            }
        });

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
        btnDownloadVideo.setOnClickListener(v -> {
            // 在 MainActivity 中点击下载测试按钮，也要遵循全局禁用配置
            com.orange.playerlibrary.PlayerSettingsManager settingsManager = 
                com.orange.playerlibrary.PlayerSettingsManager.getInstance(this);
            if (settingsManager != null && !settingsManager.isDownloadEnabled()) {
                log("✗ 无法下载：全局下载功能已被禁用");
                android.widget.Toast.makeText(this, "下载功能已被禁用，请在底部开启", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            testDownloadVideo();
        });
        btnClearEpisodes.setOnClickListener(v -> clearEpisodes());
        btnTestFeature.setOnClickListener(v -> showMoreTestDialog());

        // 去重追加 / 重复追加 / 选集数量（验证 addVideosFromJsonArrayDedup）
        Button btnAddEpisodesDedup = findViewById(R.id.btn_add_episodes_dedup);
        Button btnAddEpisodesDup = findViewById(R.id.btn_add_episodes_dup);
        Button btnShowListSize = findViewById(R.id.btn_show_list_size);
        if (btnAddEpisodesDedup != null) {
            btnAddEpisodesDedup.setOnClickListener(v -> addEpisodesDedup());
        }
        if (btnAddEpisodesDup != null) {
            btnAddEpisodesDup.setOnClickListener(v -> addEpisodesDuplicate());
        }
        if (btnShowListSize != null) {
            btnShowListSize.setOnClickListener(v -> showEpisodeListSize());
        }

        // 控制器可见性测试按钮
        Button btnControllerVisibility = findViewById(R.id.btn_controller_visibility);
        btnControllerVisibility.setOnClickListener(v -> toggleControllerVisibility(btnControllerVisibility));

        // Activity 跳转测试按钮
        Button btnJumpTest = findViewById(R.id.btn_jump_test);
        btnJumpTest.setOnClickListener(v -> {
            log("🔄 跳转到测试页面");
            android.content.Intent intent = new android.content.Intent(this, TestActivity.class);
            startActivity(intent);
        });

        // 禁用/开启下载功能测试按钮
        Button btnToggleDownload = findViewById(R.id.btn_toggle_download);
        btnToggleDownload.setOnClickListener(v -> {
            com.orange.playerlibrary.PlayerSettingsManager settingsManager = com.orange.playerlibrary.PlayerSettingsManager.getInstance(this);
            boolean isEnabled = settingsManager.isDownloadEnabled();
            settingsManager.setDownloadEnabled(!isEnabled);
            
            // 增加控制台日志输出和醒目的 Toast
            String status = !isEnabled ? "开启" : "禁用";
            log("⚙️ 切换下载功能: " + status);
            // 某些设备可能屏蔽了系统 Toast，这里使用全局 Context 或直接修改 UI 状态保证可见性
            android.widget.Toast.makeText(getApplicationContext(), "下载功能已" + status, android.widget.Toast.LENGTH_LONG).show();
            
            // 如果处于播放状态，顺便在播放器内部弹个自定义 Toast，百分百能看见
            if (mVideoView != null) {
                com.orange.playerlibrary.OrangeToast.show(mVideoView, "下载功能已" + status);
            }
        });

        log("🍊 橘子播放器 SDK Demo 启动");
        log("基于 GSYVideoPlayer 开源框架");
    }

    private void playInputUrl(boolean useSniff) {
        String url = mEtVideoUrl.getText().toString().trim();
        android.util.Log.d("MainActivity", "playInputUrl: url=" + url + ", useSniff=" + useSniff);
        if (TextUtils.isEmpty(url)) {
            log("❌ 请输入视频链接");
            return;
        }

        mCurrentUrl = url;
        mCurrentTitle = "自定义视频";

        // 每次播放新链接前，清空旧缓存以确保走去广告检测逻辑
        com.orange.playerlibrary.M3U8AdManager.getInstance(this).clearCache();

        if (useSniff) {
            log("🔍 开始嗅探: " + getShortUrl(url));
            
            // 使用嗅探播放 - 先设置URL再启动嗅探
            mVideoView.setUrl(url);
            mVideoView.startSniffing();
        } else {
            log("▶ 直接播放: " + getShortUrl(url));
            // 先释放旧的播放器资源
            mVideoView.release();
            com.shuyu.gsyvideoplayer.GSYVideoManager.releaseAllVideos();
            
            // 设置新视频（启用边看边存）
            android.util.Log.d("MainActivity", "calling setUp with url=" + url);
            // 这里调用 setUrl 才能触发 OrangevideoView 重写的包含去广告逻辑的方法，不要直接调用 setUp
            mVideoView.setUrl(url);
            
            // 延迟一帧再开始播放，让播放器有时间完成重置
            mVideoView.post(new Runnable() {
                @Override
                public void run() {
                    android.util.Log.d("MainActivity", "calling startPlayLogic");
                    mVideoView.startPlayLogic();
                }
            });
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
     * 【demo 功能】选择本地视频文件播放。
     *
     * 走 SAF（ACTION_OPEN_DOCUMENT），无需存储权限、适配分区存储。
     * 选中的 Uri 在 {@link #onActivityResult} 里转成可直接播放的路径。
     */
    private void pickLocalVideo() {
        try {
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES,
                    new String[]{"video/*", "application/octet-stream"});
            startActivityForResult(intent, REQUEST_CODE_PICK_LOCAL_VIDEO);
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "选择本地文件失败", e);
            log("❌ 无法打开文件选择器: " + e.getMessage());
        }
    }

    /**
     * SAF 返回的 content:// Uri → 播放器可直接读的路径。
     *
     * 优先取文件系统真实路径（/storage/...），取不到时回退到把内容复制进
     * 应用私有目录——SAF 的 content Uri 播放器无法直接读，复制是最稳的兜底。
     */
    private String resolvePlayablePath(android.net.Uri uri) {
        // 1) content://media/... 这类可直接查真实路径
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{android.provider.MediaStore.MediaColumns.DATA},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String path = c.getString(0);
                if (path != null && new java.io.File(path).exists()) {
                    return path;
                }
            }
        } catch (Throwable ignored) {
            // 分区存储下 DATA 列常被屏蔽，走下面的复制兜底
        }

        // 2) 复制到私有目录（保留扩展名，播放器按扩展名选解复用器）
        try {
            String name = "local_pick_" + System.currentTimeMillis();
            String ext = android.webkit.MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(getContentResolver().getType(uri));
            java.io.File out = new java.io.File(getExternalFilesDir(null),
                    name + (ext != null ? "." + ext : ".mp4"));
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                if (in == null) {
                    return null;
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "复制本地文件失败", e);
            return null;
        }
    }

    /** 请求码：选择本地视频 */
    private static final int REQUEST_CODE_PICK_LOCAL_VIDEO = 0x4C56;   // "LV"

    /** 处理本地视频选择结果 */
    private void handlePickLocalVideoResult(int resultCode, android.content.Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            log("已取消选择");
            return;
        }
        android.net.Uri uri = data.getData();
        log("📁 已选择: " + uri);
        String path = resolvePlayablePath(uri);
        if (path == null || path.isEmpty()) {
            log("❌ 无法解析所选文件的路径");
            return;
        }
        log("📁 播放本地文件: " + path);
        if (mEtVideoUrl != null) {
            mEtVideoUrl.setText(path);
        }
        mCurrentUrl = path;
        mCurrentTitle = new java.io.File(path).getName();
        mVideoView.release();
        com.shuyu.gsyvideoplayer.GSYVideoManager.releaseAllVideos();
        mVideoView.setUrl(path);
        mVideoView.post(() -> mVideoView.startPlayLogic());
        if (mVideoView.getTitleView() != null) {
            mVideoView.getTitleView().setTitle(mCurrentTitle);
        }
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
        mVideoView.setUp(url, true, "测试视频");
        log("   步骤2: 立即 startPlayLogic()");
        mVideoView.startPlayLogic();
        log("   ⚠️ 观察加载动画是否正常显示（不应该在准备视图上）");
    }

    private void initPlayer() {
        // 关闭嗅探自动播放功能（默认行为）
        com.orange.playerlibrary.PlayerSettingsManager.getInstance(this)
            .setSniffingAutoPlayEnabled(false);
        
        // 创建控制器
        // 复用播放器自带的控制器（SDK 在组件初始化时已创建），避免再 new 一个：
        // 两个 controller 会让「被换下的那个」仍监听播放状态，抢到 ASR 触发把字幕
        // 写进自己的 SubtitleManager，而宿主操作的是另一个实例（真机实测：字幕能
        // 显示但 AI 翻译报「没有已加载的字幕」）。SDK 未创建时兜底自建。
        mController = mVideoView.getVideoController();
        if (mController == null) {
            mController = new OrangeVideoController(this);
            mVideoView.setVideoController(mController);
        }

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

        // 启用记忆播放标志位，使得 PlaybackProgressManager 能够生效
        mVideoView.setKeepVideoPlaying(true);

        // 设置第一个视频（不自动播放，点击播放器可播放）
        if (mCurrentUrl != null && !mCurrentUrl.isEmpty()) {
            mVideoView.setUp(mCurrentUrl, true, mCurrentTitle);
        }
        mVideoView.setLooping(false);
        mVideoView.setAutoRotateOnFullscreen(true);

        log("✓ 播放器初始化完成");
        log("✓ 加载动画: LINE_SCALE_PULSE_OUT");
        log("✓ 已设置第一个视频，点击播放器开始播放");
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
                        // 注意：自动播放由 OrangevideoView 内部处理
                        // 这里只记录日志，不重复调用播放
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
                    // 本回调正是在 OrangevideoView 遍历监听器列表时被调用的，
                    // 此处直接解绑会在遍历中途改动列表。解绑推迟到本次通知结束后。
                    mVideoView.post(() -> mVideoView.removeOnStateChangeListener(this));
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
        // 使用不同的测试视频URL
        String[] videoUrls = {
            "http://player.alicdn.com/video/aliyunmedia.mp4",
            "https://media.w3.org/2010/05/sintel/trailer.mp4",
            "https://media.w3.org/2010/05/bunny/trailer.mp4",
            "http://clips.vorwaerts-gmbh.de/big_buck_bunny.mp4",
            "https://www.w3schools.com/html/mov_bbb.mp4"
        };
        
        ArrayList<HashMap<String, Object>> videoList = new ArrayList<>();
        for (int i = 0; i < videoUrls.length; i++) {
            HashMap<String, Object> video = new HashMap<>();
            video.put("name", "第" + (i + 1) + "集");
            video.put("url", videoUrls[i]);
            videoList.add(video);
        }
        
        // 设置视频列表（不再自动设置第一个视频）
        mController.setVideoList(videoList);
        
        // 手动设置第一个视频的URL和标题
        if (videoUrls.length > 0) {
            mCurrentUrl = videoUrls[0];
            mCurrentTitle = "第1集";
        }
        
        log("✓ 已添加 " + videoUrls.length + " 个选集");
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

    /**
     * 切换控制器可见性（测试临时禁用控制器UI功能）
     */
    private void toggleControllerVisibility(Button btn) {
        boolean currentEnabled = mVideoView.isControllerVisibilityEnabled();
        boolean newEnabled = !currentEnabled;
        mVideoView.setControllerVisibilityEnabled(newEnabled);
        btn.setText(newEnabled ? "禁用控制器UI" : "启用控制器UI");
        log("控制器UI: " + (newEnabled ? "已启用" : "已禁用（功能保留）"));
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
   
        mVideoView.onVideoResume();
        // 修复：不自动恢复播放
        // 原因：
        // 1. 用户可能在后台通过通知栏暂停了播放
        // 2. 用户可能在其他 App 中暂停了播放（如蓝牙耳机控制）
        // 3. 自动恢复播放会打断用户的操作
        // 
        // 如果需要自动恢复，用户可以在设置中开启
        // 或者在特定场景下（如视频详情页）才自动恢复
        
        // 清空标记，避免下次误判
        mWasPlayingBeforeBackground = false;
        
        log("📱 App 回到前台（不自动恢复播放）");
    }

    @Override
    protected void onStart() {
        super.onStart();
        // MediaSession：系统控制入口（耳机/锁屏/Assistant），不做后台播放
        if (mSessionHelper == null && mVideoView != null) {
            mSessionHelper = new com.orange.player.session.OrangePlayerSessionHelper(this, mVideoView);
        }
        if (mSessionHelper != null) {
            mSessionHelper.start(mCurrentUrl, mCurrentTitle);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mPiPHelper != null && mPiPHelper.handleOnStop()) {
            return;
        }
        // 在 onStop 中暂停播放，并强制保存一次播放进度
        mWasPlayingBeforeBackground = mVideoView.isPlaying();
        if (mVideoView != null) {
            mVideoView.savePlaybackProgress();
            if (mWasPlayingBeforeBackground) {
                mVideoView.onVideoPause();
                log("📱 App 切到后台，暂停播放并保存进度");
            }
        }
        // Session 生命周期与 Activity 同步（PiP 模式下保持会话供系统控制）
        if (!isInPictureInPictureMode() && mSessionHelper != null) {
            mSessionHelper.stop();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 本地视频选择（demo 功能）
        if (requestCode == REQUEST_CODE_PICK_LOCAL_VIDEO) {
            handlePickLocalVideoResult(resultCode, data);
            return;
        }
        if (mController != null && mController.getVideoEventManager() != null) {
            // 字幕文件选择结果（REQUEST_CODE_SUBTITLE_FILE）
            mController.getVideoEventManager().handleActivityResult(requestCode, resultCode, data);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 下载功能使用系统 DownloadManager，不需要权限处理
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
        try {
            unregisterReceiver(mTestCmdReceiver);
        } catch (Exception ignored) {
        }
        if (mSessionHelper != null) {
            mSessionHelper.stop();
            mSessionHelper = null;
        }
        if (mController != null) {
            mController.releaseDanmaku();
        }
        if (mVideoView != null) {
            // 销毁前强制保存进度
            mVideoView.savePlaybackProgress();
            mVideoView.release();
        }
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
        mVideoView.setUp(url, true, mCurrentTitle);

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
        // 内核清单由 PlayerEngineAvailability 决定，不硬编码——so 已改为按需下载，
        // 「宿主引了依赖」不等于「现在能用」。与全屏设置里的播放核心按钮同一套判定：
        //   不支持（宿主未引依赖）→ 不列出
        //   支持但 so 未下载       → 列出并标注，点击引导去扩展包管理下载
        //   可用                  → 正常可选
        // 之前这里硬编码 4 项，既漏了 MPV，也会让未下载的 IJK/阿里云显示成可选，
        // 选中后静默回退到系统内核。
        String[] allEngines = {
            com.orange.playerlibrary.PlayerConstants.ENGINE_DEFAULT,
            com.orange.playerlibrary.PlayerConstants.ENGINE_EXO,
            com.orange.playerlibrary.PlayerConstants.ENGINE_IJK,
            com.orange.playerlibrary.PlayerConstants.ENGINE_ALI,
            com.orange.playerlibrary.PlayerConstants.ENGINE_MPV,
        };

        java.util.List<String> engineList = new java.util.ArrayList<>();
        java.util.List<String> labelList = new java.util.ArrayList<>();
        java.util.List<Boolean> readyList = new java.util.ArrayList<>();

        for (String engine : allEngines) {
            if (!com.orange.playerlibrary.utils.PlayerEngineAvailability.isSupported(engine)) {
                continue;
            }
            boolean ready = com.orange.playerlibrary.utils.PlayerEngineAvailability.isUsable(engine);
            engineList.add(engine);
            labelList.add(engineLabel(engine) + (ready ? "" : "（需先下载）"));
            readyList.add(ready);
        }

        final String[] engines = engineList.toArray(new String[0]);
        final String[] players = labelList.toArray(new String[0]);
        final boolean[] ready = new boolean[readyList.size()];
        for (int i = 0; i < ready.length; i++) {
            ready[i] = readyList.get(i);
        }
        android.util.Log.d("MainActivity", "内核清单: " + java.util.Arrays.toString(players)
                + " ready=" + java.util.Arrays.toString(ready));

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

                // 未下载的内核：引导去扩展包管理，而不是切过去静默回退
                if (!ready[which]) {
                    dialog.dismiss();
                    String bundleId =
                            com.orange.playerlibrary.utils.PlayerEngineAvailability.bundleIdFor(engine);
                    log("⚠️ " + engineLabel(engine) + " 尚未下载，请先在扩展包管理中安装");
                    android.widget.Toast.makeText(this,
                            engineLabel(engine) + " 需要先下载，请到「扩展包管理」安装",
                            android.widget.Toast.LENGTH_LONG).show();
                    if (bundleId != null && mController != null
                            && mController.getVideoEventManager() != null) {
                        mController.getVideoEventManager().showNativeLibsDialog();
                    }
                    return;
                }

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
                mVideoView.setUp(mCurrentUrl, true, mCurrentTitle);

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

    /** 内核的展示名，与全屏设置里的播放核心按钮保持一致 */
    private static String engineLabel(String engine) {
        if (com.orange.playerlibrary.PlayerConstants.ENGINE_DEFAULT.equals(engine)) {
            return "系统播放器";
        }
        if (com.orange.playerlibrary.PlayerConstants.ENGINE_EXO.equals(engine)) {
            return "ExoPlayer";
        }
        if (com.orange.playerlibrary.PlayerConstants.ENGINE_IJK.equals(engine)) {
            return "IJK播放器";
        }
        if (com.orange.playerlibrary.PlayerConstants.ENGINE_ALI.equals(engine)) {
            return "阿里云播放器";
        }
        if (com.orange.playerlibrary.PlayerConstants.ENGINE_MPV.equals(engine)) {
            return "MPV播放器";
        }
        return engine;
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
     * 【回归验证】用 v3 宿主的**真实数组形状**喂官方门面，验证不再丢集 / 不再空白标题。
     *
     * <p>背景：真机日志（PJA110，原件见 {@code docs/evidence/device-v3-episodes-20260920*.logcat.txt}）
     * 显示 iApp v3 传给 {@code addVideosFromJsonArray} 的 JSONArray 里混着 Java null 槽位。
     * **「整批一个 try/catch 会在中途丢集」由本入口的 A/B 对照证明**（同一数组分别喂
     * 整批写法与逐条写法），不是靠断言。
     *
     * <p>三条子验证（全部走**真正的门面**，不是 SDK 直连）：
     * <ol>
     *   <li>真实 {@code item.json} 按 {@code #} 切分构造 → 验证有效条目一条不丢；</li>
     *   <li>按真机日志记录的槽位分布（69 槽位 / 48 null / 21 有效）复刻 → 含
     *       **A/B 对照**（整批 vs 逐条）。注意这是**日志里的一次观察**，数组长度
     *       两次运行不同（69 / 59），**不是固定值**；</li>
     *   <li>推荐入口 {@code addVideosFromMacCmsJson}（宿主别自己拼数组）→ 应得 26 集。</li>
     * </ol>
     *
     * <p>通过 {@code adb broadcast -a com.orange.player.TEST_CMD --es cmd v3_episodes_smoke}
     * 触发，结果打 {@code logcat -s V3Smoke:D}（demo 的 {@code log()} 只写界面，
     * 界面常被对话框遮挡，读不到）。
     */
    private void v3EpisodesSmoke() {
        // 1) 读真实 v3 数据（app/src/debug/assets/v3_item.json = 真机 item.json 原件）
        String json;
        try (java.io.InputStream in = getAssets().open("v3_item.json")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                bos.write(buf, 0, len);
            }
            json = new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            log("✗ 读 v3_item.json 失败：" + e);
            return;
        }

        // 2) 按 '#' 切分真实 vod_play_url 构造数组：
        //    含 '$' 的入对象、其余放 Java null。
        //
        //    注：这样构造出来的槽位数（51）与真机观察到的 69 不同——宿主侧
        //    循环逻辑没拿到，69 的来源**未证实**。本验证不依赖那个数字：
        //    子验证 2 单独覆盖真机观察到的精确形状。
        org.json.JSONArray arr = new org.json.JSONArray();
        int nullSlots = 0;
        int expected = 0;
        try {
            String vpu = new org.json.JSONObject(json)
                    .getJSONArray("list").getJSONObject(0).getString("vod_play_url");
            for (String seg : vpu.split("#", -1)) {
                int sep = seg.indexOf('$');
                if (sep > 0) {
                    // 含 '$$$' 的片段会被 split('#') 合并（第26集与第1集连在一起），
                    // 这里按宿主的判据放 null。
                    if (seg.contains("$$$")) {
                        arr.put((Object) null);
                        nullSlots++;
                        continue;
                    }
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("name", seg.substring(0, sep));
                    o.put("url", seg.substring(sep + 1));
                    o.put("isSpecial", false);
                    arr.put(o);
                    expected++;
                } else {
                    arr.put((Object) null);   // 用 Java null，与真机一致
                    nullSlots++;
                }
            }
        } catch (Exception e) {
            log("✗ 解析 v3_item.json 失败：" + e);
            return;
        }
        log("v3 冒烟：数组 " + arr.length() + " 槽位，null " + nullSlots
                + " 个，有效 " + expected + " 个");

        // 3) 走**真正的门面**（iApp 宿主用的那个类），完整复现集成路径
        com.orange.player.VideoPlayerManager pm =
                com.orange.player.VideoPlayerManager.getInstance();
        try {
            pm.init(this, findViewById(R.id.v3_smoke_container));
        } catch (Exception e) {
            log("✗ 门面 init 失败：" + e);
            return;
        }
        pm.addVideosFromJsonArray(arr);

        // 4) 校验：集数 = 有效槽位数；标题与地址都非空。
        //    读门面自己的控制器，不是 demo 的 mController——两者是不同的实例，
        //    读错了会拿到 demo 列表而误判通过。
        OrangeVideoController facadeController = pm.getVideoController();
        ArrayList<HashMap<String, Object>> list = facadeController == null
                ? null : facadeController.getVideoList();
        int n = list == null ? 0 : list.size();
        int blanks = 0;
        if (list != null) {
            for (HashMap<String, Object> it : list) {
                Object nm = it.get("name");
                Object u = it.get("url");
                if (nm == null || nm.toString().isEmpty() || "null".equals(nm.toString())
                        || u == null || u.toString().isEmpty() || "null".equals(u.toString())) {
                    blanks++;
                }
            }
        }
        boolean ok = (n == expected) && blanks == 0;
        String result = "v3冒烟结果: 选集 " + n + " 集(应 " + expected + "), 空白/无效 "
                + blanks + " 条 -> " + (ok ? "PASS" : "FAIL");
        log(result);
        // 同时打 logcat：demo 的 log() 只写界面，界面可能被对话框遮挡
        android.util.Log.d("V3Smoke", result);
        android.util.Log.d("V3Smoke", "slots=" + arr.length() + " nullSlots=" + nullSlots
                + " expected=" + expected);

        // 5) 再跑一遍真机日志记录的槽位分布（69 槽位 / 48 null / 21 有效），
        //    并对同一数组做**整批 vs 逐条**的 A/B 对照——这一步才是
        //    「整批写法会丢集」的直接证据。
        runExactDeviceShapeSmoke();

        // 6) 推荐给宿主的入口：直接把采集接口原文交给 SDK 解析。
        //    宿主自己拼数组会丢集（真机两次运行分别只放进 21 / 25 条），
        //    走这条能拿到全部 26 集。
        runMacCmsSmoke(json);

        // 7) 对照 v5 宿主：v5 的 main.iyu 用 `list.data[0].vod_play_url` 取数据，
        //    而真实 item.json 的 list 是**数组**（无 data 字段）→ 取不到 → 0 集。
        runV5HostPathSmoke(json);

        android.widget.Toast.makeText(this,
                "v3冒烟: " + n + "/" + expected + " 集, 空白 " + blanks,
                android.widget.Toast.LENGTH_LONG).show();
    }

    /**
     * 用真机日志记录的槽位分布复刻，并对同一数组做**整批 vs 逐条**的 A/B 对照。
     *
     * <p>有效槽位下标（1-based）来自 PJA110 的 VideoPlayerManager 日志
     * （{@code docs/evidence/device-v3-episodes-20260920.logcat.txt}，01:48 那次）：
     * 失败条目编号 = 16-26、28-33、35-39、42-55、57-60、62-69，
     * 取补集即有效槽位 {@code [1..15, 27, 34, 40, 41, 56, 61]}，共 21 个。
     *
     * <p><b>这是日志里的一次观察，不是「设备固定形状」</b>：12:15 再跑一次得到的是
     * 59 槽位 / 35 null / 25 有效。所以这里只是拿一个**真实出现过的**形状做回归，
     * 断言不依赖 69 这个数字本身。
     *
     * <p>本方法的价值在于 {@link #oldImplCount} 的 A/B 对照：同一数组喂整批写法
     * 与逐条写法，证明「整批会在第一个 null 处丢集」。真机上是否确实因此丢集
     * **没有直接证据**（01:48 那版不打印列表长度）。
     */
    private void runExactDeviceShapeSmoke() {
        final int totalSlots = 69;
        final int[] validIdx1Based = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                27, 34, 40, 41, 56, 61};
        org.json.JSONArray arr = new org.json.JSONArray();
        int vi = 0;
        for (int slot = 1; slot <= totalSlots; slot++) {
            boolean valid = vi < validIdx1Based.length && validIdx1Based[vi] == slot;
            if (valid) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    // 故意让其中一条**没有 name**：验证空白标题回退成「第N集」
                    o.put("name", vi == 3 ? "" : ("第" + (vi + 1) + "集"));
                    o.put("url", "https://v.x1/ep" + slot + ".m3u8");
                    o.put("isSpecial", false);
                    arr.put(o);
                } catch (Exception ignored) {
                }
                vi++;
            } else {
                // 用 **Java null**（不是 JSONObject.NULL）——真机日志的异常消息
                // "Value at N is null." 是 Android 内置 org.json 对 Java null
                // 的分支（已在黑鲨上探针实测确认）。
                arr.put((Object) null);
            }
        }
        com.orange.player.VideoPlayerManager pm =
                com.orange.player.VideoPlayerManager.getInstance();
        pm.addVideosFromJsonArray(arr);
        OrangeVideoController c = pm.getVideoController();
        ArrayList<HashMap<String, Object>> list = c == null ? null : c.getVideoList();
        int n = list == null ? 0 : list.size();
        int blanks = 0;
        if (list != null) {
            for (HashMap<String, Object> it : list) {
                Object nm = it.get("name");
                if (nm == null || nm.toString().isEmpty() || "null".equals(nm.toString())) {
                    blanks++;
                }
            }
        }
        // A/B 对照：同一数组喂**整批 try/catch 写法**会剩几集？
        // 这一步给出「整批会在第一个 null 处丢集」的直接证据，而不是只断言新实现的结果。
        int oldCount = oldImplCount(arr);
        android.util.Log.d("V3Smoke", "A/B对照: 整批写法=" + oldCount + " 集, 逐条写法=" + n + " 集");

        // 逐条写法应拿到全部有效条目；整批写法应在第一个 null（第 16 槽）处截断 → 15 集。
        // 15 是由本方法**自己构造的固定数组**推出的确定值，与真机数组长度无关。
        boolean ok = (n == validIdx1Based.length) && blanks == 0 && oldCount == 15;
        String msg = "v3精确形状: 槽位=" + totalSlots + " 有效=" + validIdx1Based.length
                + " -> 逐条 " + n + " 集 / 整批 " + oldCount + " 集, 空白 " + blanks
                + " 条 -> " + (ok ? "PASS" : "FAIL");
        log(msg);
        android.util.Log.d("V3Smoke", msg);
    }

    /**
     * **旧实现**的忠实复刻，逐语句对应 `git show HEAD:test/VideoPlayerManager.java`
     * 第 695-704 行 + 其调用的 `addVideoFromJson`（第 676-687 行）：
     *
     * <pre>
     * try {
     *     for (int i = 0; i &lt; jsonArray.length(); i++) {
     *         addVideoFromJson(jsonArray.getJSONObject(i).toString());
     *     }
     * } catch (JSONException e) { ... }
     * // addVideoFromJson: new JSONObject(s) → getString("name")/getString("url")
     * //                   /getBoolean("isSpecial")  ← 三者缺一即抛异常
     * </pre>
     *
     * <p>要点：**整批一个 try/catch**，任一条抛异常就中止整轮；且 `getString`
     * 是严格取值（键缺失/类型不符都抛），比新实现的 `optString` 更脆。
     * 只用于 A/B 对照，证明「null 槽位 → 中止 → 丢集」这条因果链成立。
     */
    private int oldImplCount(org.json.JSONArray jsonArray) {
        int added = 0;
        try {
            for (int i = 0; i < jsonArray.length(); i++) {
                // 对应旧实现的 jsonArray.getJSONObject(i).toString()
                String s = jsonArray.getJSONObject(i).toString();
                // 对应旧实现的 addVideoFromJson(s) 内部三次严格取值
                org.json.JSONObject o = new org.json.JSONObject(s);
                o.getString("name");
                o.getString("url");
                o.getBoolean("isSpecial");
                added++;
            }
        } catch (Exception e) {
            android.util.Log.d("V3Smoke", "旧实现在第 " + (added + 1)
                    + " 条中止: " + e.getMessage());
        }
        return added;
    }

    /**
     * 走推荐入口 {@code addVideosFromMacCmsJson}：宿主把采集接口原文直接交给 SDK。
     *
     * <p>宿主自己拼数组会丢集——真机 69 个槽位只放进 21 条有效数据，52 个有效
     * 片段（2 线路 × 26 集）大部分丢了。SDK 侧的 {@code VodPlayUrlParser}
     * 会选可播线路，这里应得到 26 集（单线路）而不是 21 或 52。
     */
    private void runMacCmsSmoke(String json) {
        com.orange.player.VideoPlayerManager pm =
                com.orange.player.VideoPlayerManager.getInstance();
        int added = pm.addVideosFromMacCmsJson(json);
        OrangeVideoController c = pm.getVideoController();
        ArrayList<HashMap<String, Object>> list = c == null ? null : c.getVideoList();
        int n = list == null ? 0 : list.size();
        int blanks = 0;
        if (list != null) {
            for (HashMap<String, Object> it : list) {
                Object nm = it.get("name");
                Object u = it.get("url");
                if (nm == null || nm.toString().isEmpty() || "null".equals(nm.toString())
                        || u == null || u.toString().isEmpty()) {
                    blanks++;
                }
            }
        }
        // 真机 item.json 每条线路 26 集 → 应得 26 集（不是 21）
        boolean ok = added == 26 && n == 26 && blanks == 0;
        String msg = "MacCMS入口: added=" + added + " 列表=" + n + " 集, 空白 " + blanks
                + " 条 -> " + (ok ? "PASS" : "FAIL");
        log(msg);
        android.util.Log.d("V3Smoke", msg);
    }

    /**
     * 复现 **v5 宿主**（{@code com.orange.demov5/src/main.iyu}）的取数据方式。
     *
     * <p>v5 的代码（原文，Java 风格）：
     * <pre>
     * org.json.JSONObject jo2 = new org.json.JSONObject(js2);
     * org.json.JSONObject jl  = jo2.optJSONObject("list");        // 期望「对象」
     * org.json.JSONArray  jd  = (jl == null) ? null : jl.optJSONArray("data");
     * org.json.JSONObject d0  = (jd == null || jd.length() == 0) ? null : jd.optJSONObject(0);
     * String src = (d0 == null) ? null : d0.optString("vod_play_url");
     * if(src != null){ ...拼数组... }
     * </pre>
     *
     * <p>但真实 {@code item.json} 里 {@code list} 是**数组**、没有 {@code data} 字段
     * （见 {@code app/src/debug/assets/v3_item.json}）。{@code optJSONObject} 对类型
     * 不符返回 null → 整条链断在第一步 → {@code src == null} → **一集都取不到**。
     * 这与 v3 完全不同：v3 是取到了数据、只是数组里混 null 槽位。
     */
    private void runV5HostPathSmoke(String json) {
        String src = null;
        String failAt = "无";
        try {
            org.json.JSONObject jo2 = new org.json.JSONObject(json);
            org.json.JSONObject jl = jo2.optJSONObject("list");      // ← 断在这里
            if (jl == null) {
                failAt = "optJSONObject(\"list\") 返回 null（list 是数组，不是对象）";
            } else {
                org.json.JSONArray jd = jl.optJSONArray("data");
                if (jd == null || jd.length() == 0) {
                    failAt = "list.data 缺失或为空";
                } else {
                    org.json.JSONObject d0 = jd.optJSONObject(0);
                    src = (d0 == null) ? null : d0.optString("vod_play_url");
                    if (src == null) {
                        failAt = "list.data[0].vod_play_url 为 null";
                    }
                }
            }
        } catch (Exception e) {
            failAt = "抛异常: " + e;
        }

        // 若真取到了，就按 v5 的方式拼数组并交给门面（复现完整链路）
        int added = 0;
        if (src != null) {
            org.json.JSONArray xjOut = new org.json.JSONArray();
            for (String seg : src.split("#")) {
                String[] kv = seg.split("\\$");
                if (kv.length < 2) continue;
                if (kv[0] == null || kv[0].isEmpty()) continue;
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("name", kv[0]);
                    o.put("url", kv[1]);
                    o.put("isSpecial", false);
                    xjOut.put(o);
                } catch (Exception ignored) {
                }
            }
            added = xjOut.length();
            if (added > 0) {
                com.orange.player.VideoPlayerManager.getInstance()
                        .addVideosFromJsonArray(xjOut);
            }
        }

        // 期望：v5 路径取不到数据（src == null）→ 0 集。这不是 SDK 的 bug，是宿主取值路径写错了。
        boolean ok = (src == null) && added == 0;
        String msg = "v5宿主路径: src=" + (src == null ? "null" : "非空") + " 拼出 " + added
                + " 集, 断点=" + failAt + " -> " + (ok ? "PASS(证实取不到数据)" : "FAIL");
        log(msg);
        android.util.Log.d("V3Smoke", msg);
    }

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

    /** 测试用：两批选集，第二批与第一批有重叠 */
    private static final String[] BATCH_A = {
            "http://player.alicdn.com/video/aliyunmedia.mp4",
            "https://media.w3.org/2010/05/sintel/trailer.mp4",
            "http://vfx.mtime.cn/Video/2019/03/21/mp4/190321153853126488.mp4",
    };
    private static final String[] BATCH_B = {
            // 前两条与 A 重复，第三条是新的
            "https://media.w3.org/2010/05/sintel/trailer.mp4",
            "http://vfx.mtime.cn/Video/2019/03/21/mp4/190321153853126488.mp4",
            "http://vfx.mtime.cn/Video/2019/03/19/mp4/190319222227698228.mp4",
    };

    private org.json.JSONArray buildBatch(String[] urls) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (int i = 0; i < urls.length; i++) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", "追加" + (i + 1));
                o.put("url", urls[i]);
                o.put("isSpecial", false);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    /**
     * 【测试】去重追加：先放 A 批，再去重追加 B 批。
     * 预期：A 3 集 → 追加 B 时只有 1 条新的（另 2 条已存在被跳过）→ 共 4 集。
     */
    private void addEpisodesDedup() {
        if (mController == null) {
            log("✗ 控制器未就绪");
            return;
        }
        mController.setVideoList(new ArrayList<>());
        addVideosDedupInternal(buildBatch(BATCH_A));
        int sizeAfterA = listSize();
        int added = addVideosDedupInternal(buildBatch(BATCH_B));
        int sizeAfterB = listSize();
        log("✓ 去重追加: A=" + sizeAfterA + " 集，B 新增 " + added
                + " 条（B 共 3 条，其中 2 条与 A 重复）→ 现 " + sizeAfterB + " 集");
    }

    /**
     * 【测试】重复追加：用不去重的入口追加 B 批（对照）。
     * 预期：A 3 集 → 追加 3 条 → 共 6 集（出现重复项）。
     */
    private void addEpisodesDuplicate() {
        if (mController == null) {
            log("✗ 控制器未就绪");
            return;
        }
        mController.setVideoList(new ArrayList<>());
        addVideosDedupInternal(buildBatch(BATCH_A));
        int sizeAfterA = listSize();
        // 逐个 addVideo：无条件追加，不去重
        for (String url : BATCH_B) {
            mController.addVideo("追加", url, false);
        }
        int sizeAfterB = listSize();
        log("✓ 重复追加（对照）: A=" + sizeAfterA + " 集，B 无条件追加 3 条 → 现 "
                + sizeAfterB + " 集（含重复）");
    }

    private int addVideosDedupInternal(org.json.JSONArray arr) {
        // 走门面的去重入口需要 VideoPlayerManager；demo 直连 controller，
        // 因此直接调 SDK 的 controller 去重方法。
        ArrayList<HashMap<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                org.json.JSONObject o = arr.getJSONObject(i);
                HashMap<String, Object> item = new HashMap<>();
                item.put("name", o.optString("name"));
                item.put("url", o.optString("url"));
                items.add(item);
            } catch (Exception ignored) {
            }
        }
        return mController.addVideosDedup(items);
    }

    /** 【测试】显示当前选集数量 */
    private void showEpisodeListSize() {
        int n = listSize();
        log("当前选集数量: " + n + " 集");
        android.widget.Toast.makeText(this, "选集数量: " + n, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int listSize() {
        if (mController == null || mController.getVideoList() == null) {
            return 0;
        }
        return mController.getVideoList().size();
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
        // 检查 URL 是否有效
        if (mCurrentUrl == null || mCurrentUrl.isEmpty()) {
            log("✗ 无法下载：视频 URL 为空");
            return;
        }
        
        // 创建自定义对话框（使用 AlertDialog）
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(
            com.orange.playerlibrary.R.layout.dialog_download_confirm, null);
        builder.setView(view);
        
        AlertDialog dialog = builder.create();
        
        // 设置对话框背景透明
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        
        // 设置视频信息
        TextView tvTitle = view.findViewById(com.orange.playerlibrary.R.id.tv_video_title);
        tvTitle.setText(mCurrentTitle);
        
        // 下载管理按钮
        view.findViewById(com.orange.playerlibrary.R.id.btn_download_manager).setOnClickListener(v -> {
            dialog.dismiss();
            showDownloadManager();
        });
        
        // 取消按钮
        view.findViewById(com.orange.playerlibrary.R.id.btn_cancel).setOnClickListener(v -> {
            dialog.dismiss();
        });
        
        // 确认下载按钮
        view.findViewById(com.orange.playerlibrary.R.id.btn_download).setOnClickListener(v -> {
            try {
                // 使用单例获取下载管理器
                com.orange.playerlibrary.download.SimpleDownloadManager downloadManager = 
                    com.orange.playerlibrary.download.SimpleDownloadManager.getInstance(this);
                
                long downloadId = downloadManager.startDownload(
                    mCurrentUrl,
                    mCurrentTitle,
                    "OrangePlayer 视频下载"
                );
                
                if (downloadId != -1) {
                    log("✓ 下载已开始");
                    log("  下载ID: " + downloadId);
                    log("  保存位置: Downloads/orangeplayer 文件夹");
                    log("  可在下载管理中查看进度");
                } else {
                    log("✗ 下载失败");
                }
            } catch (Exception e) {
                log("✗ 下载失败: " + e.getMessage());
                e.printStackTrace();
            }
            dialog.dismiss();
        });
        
        dialog.show();
    }

    /**
     * 显示下载管理对话框
     */
    private void showDownloadManager() {
        com.orange.playerlibrary.component.SimpleDownloadDialogView downloadDialog = 
            new com.orange.playerlibrary.component.SimpleDownloadDialogView(this);
        downloadDialog.setOnPlayLocalListener((filePath, item) -> {
            if (filePath == null || filePath.isEmpty()) {
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
            log("▶ 播放本地视频: " + title);
        });
        downloadDialog.show();
        log("📥 打开下载管理");
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
