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
import java.util.function.Consumer;

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
    // ASR 视频文件选择请求码
    public static final int REQUEST_CODE_ASR_VIDEO = 10087;
    
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

    // mpv libass 原生字幕渲染：已下发给 mpv 的字幕文件路径（null=未启用）。
    // 原生渲染把字幕画进视频画面，SubtitleView 文本层必须同步禁用避免双重字幕；
    // 代价是翻译对照/说话人标签等叠加能力在原生模式下不可用（宿主 UI 提示）
    private String mNativeAssPath;
    private String mNativeAssVideoUrl;
    
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
            mVideoView.addOnStateChangeListener(mStateChangeListener);
        }
    }

    /** 状态监听器引用（保留以便 release 时解绑） */
    private final com.orange.playerlibrary.interfaces.OnStateChangeListener mStateChangeListener =
            new com.orange.playerlibrary.interfaces.OnStateChangeListener() {
                @Override
                public void onPlayerStateChanged(int playerState) {
                    handlePlayerStateChangedForOcr(playerState);
                }

                @Override
                public void onPlayStateChanged(int playState) {
                    // 播放结束：渐进识别已覆盖全部观看内容，收尾
                    if (playState == com.orange.playerlibrary.PlayerConstants.STATE_PLAYBACK_COMPLETED) {
                        // 会话会在这里被硬停，onCompleted 不会再触发；若识别已跟上播放
                        // 进度（全片块都过了），此刻提升为整片缓存，下次重看免识别
                        if (sProgressiveAsr != null && sProgressiveAsr.isFullyRecognized()) {
                            com.orange.playerlibrary.speech.AsrSubtitleCache.promoteToFull(
                                    mContext, mVideoView != null ? mVideoView.getUrl() : null);
                        }
                        stopProgressiveAsr();
                    }
                    maybeAutoGenerateAsr(playState);
                }
            };

    /**
     * 释放本事件管理器：解绑播放器状态监听、停止字幕相关后台任务。
     *
     * 调用场景：controller 被 {@code OrangevideoView.setVideoController} 替换时，
     * 旧 controller 的本对象若继续监听，会抢到播放状态回调并触发 ASR 写入自己的
     * SubtitleManager——宿主读到的却是新 controller 的空字幕（真机实测：AI 翻译报
     * 「没有已加载的字幕」而字幕实际已生成 21 条）。释放后旧实例不再抢事件。
     */
    public void release() {
        if (mVideoView != null) {
            try {
                mVideoView.removeOnStateChangeListener(mStateChangeListener);
            } catch (Throwable t) {
                Log.w(TAG, "release: 解绑状态监听失败", t);
            }
        }
        try {
            stopProgressiveAsr();
        } catch (Throwable ignored) {
        }
        try {
            stopOcrTranslate();
        } catch (Throwable ignored) {
        }
        // mVideoView/mController 为 final 且构造注入，不置空；解绑监听已使其失去事件源
    }

    /**
     * 最近一次成功发起识别的视频 URL（进程级）。
     *
     * 存在的理由：STATE_PLAYING 与 STATE_PLAYBACK_COMPLETED 都会走到这里，
     * 后者会在视频刚播完时把识别重新拉起（悬浮环又冒出来）。
     *
     * 但不能做成「本次运行触发过的 URL 集合」——那会让用户在剧集间来回切时，
     * 回到看过的那一集再也不触发（真机症状："点下一集/选集没反应，悬浮环不出来"）。
     * 单值记录天然满足两者：切走再切回来时与记录不同，照常触发；重复触发的代价
     * 也已被 {@code AsrSubtitleCache} 抹平（整片命中直接加载，部分识别续接缺失段）。
     */
    private static volatile String sAutoAsrTriggeredUrl;

    /**
     * 播放状态变化时按「自动生成字幕」设置触发 ASR。
     * 触发点：STATE_PLAYING（已缓存资源/本地文件即时生成）、
     *        STATE_PLAYBACK_COMPLETED（网络视频缓存 100% 后生成）。
     * 本地/已缓存 mp4 且开启「边看边识别」→ 渐进；否则完整版。
     */
    private void maybeAutoGenerateAsr(int playState) {
        try {
            // 每一道守卫都记下跳过原因：这条链路环节多（开关/状态/缓存/去重），
            // 出问题时"没反应"是唯一症状，没有日志就只能靠猜
            String skip = null;
            if (!mSettingsManager.isSubtitleEnabled()) {
                skip = "字幕未开启";
            } else if (!mSettingsManager.isAsrAutoEnabled()) {
                skip = "未开「新视频自动开始」";
            } else if (sIsAsrGenerating) {
                skip = "完整识别进行中";
            } else if (sProgressiveAsr != null) {
                skip = "已有渐进会话";
            } else if (playState != com.orange.playerlibrary.PlayerConstants.STATE_PLAYING
                    && playState != com.orange.playerlibrary.PlayerConstants.STATE_PLAYBACK_COMPLETED) {
                skip = "状态 " + playState + " 不触发";
            } else if (mVideoView == null) {
                skip = "播放器为空";
            } else if (mVideoView.isLiveVideo()) {
                skip = "直播不生成";   // 无终点，避免无限下载
            }
            final String url = skip == null && mVideoView != null ? mVideoView.getUrl() : null;
            if (skip == null && (url == null || url.isEmpty())) {
                skip = "无播放地址";
            } else if (skip == null && url.equals(sAutoAsrTriggeredUrl)) {
                skip = "本次播放已触发过";
            }
            if (skip != null) {
                Log.d(TAG, "自动生成字幕未触发: " + skip);
                return;
            }
            if (triggerAsrForCurrentVideo()) {
                sAutoAsrTriggeredUrl = url;
            }
            // 未发起成功（网络视频尚未缓存完）不记录，留给下次状态变化重试
        } catch (Exception e) {
            Log.w(TAG, "自动生成字幕触发异常", e);
        }
    }

    /**
     * 「新视频自动开始」开关拨开时立即对当前视频生效。
     * 与自动触发共用 {@link #triggerAsrForCurrentVideo()}，区别是给出明确反馈。
     */
    private void startAsrForCurrentVideoNow() {
        try {
            if (sIsAsrGenerating || sProgressiveAsr != null) {
                showToast("已在为当前视频生成字幕");
                return;
            }
            if (mVideoView == null || mVideoView.isLiveVideo()) {
                showToast("已开启自动生成；直播不生成字幕");
                return;
            }
            final String url = mVideoView.getUrl();
            if (url == null || url.isEmpty()) {
                return;   // 尚未开始播放：后续播放状态变化会正常触发
            }
            if (!com.orange.playerlibrary.speech.SherpaAvailabilityChecker.isSherpaAvailable()
                    || !com.orange.playerlibrary.speech.AsrSubtitleGenerator.isModelReady(mContext)) {
                showToast("已开启自动生成；ASR 模型未就绪");
                return;
            }
            if (triggerAsrForCurrentVideo()) {
                sAutoAsrTriggeredUrl = url;
                showToast("已开始为当前视频生成字幕");
            } else {
                // 网络视频尚未缓存完：本地无音频可读，只能等缓存
                showToast("已开启自动生成；本集将在缓存完成后识别");
            }
        } catch (Exception e) {
            Log.w(TAG, "开关触发 ASR 异常", e);
        }
    }

    /**
     * 对当前播放视频发起识别（自动触发与开关拨开共用）。
     *
     * @return true=已发起识别；false=此刻无法发起（资源未缓存完/m3u8 不支持渐进），
     *         调用方可择机重试
     */
    private boolean triggerAsrForCurrentVideo() {
        final String url = mVideoView != null ? mVideoView.getUrl() : null;
        if (url == null || url.isEmpty()) {
            return false;
        }

        // 网络 m3u8
        if (isM3u8Url(url)) {
            if (mSettingsManager.isAsrLiveEnabled()
                    && com.orange.playerlibrary.speech.ProgressiveAsrSession.isAvailable(mContext)) {
                Log.d(TAG, "自动生成字幕（HLS 渐进）: " + url);
                startProgressiveAsrHls(url);
            } else {
                Log.d(TAG, "自动生成字幕（HLS 后台下载）: " + url);
                downloadHlsAndGenerate(url, true);
            }
            return true;
        }

        final boolean progressiveAvailable =
                com.orange.playerlibrary.speech.ProgressiveAsrSession.isAvailable(mContext);
        final boolean progressive = mSettingsManager.isAsrLiveEnabled() && progressiveAvailable;

        // 本地文件 / 已缓存 mp4
        java.io.File local = resolveLocalVideoFileForAsr();

        if (progressive) {
            if (local != null) {
                Log.d(TAG, "自动生成字幕（渐进）: " + url);
                startProgressiveAsr(local);
                return true;
            }
            // 尚未缓存完的网络视频：不等整片缓存（danikula 随播随下，等它等于要等到
            // 快播完），直接经本地代理按区间读取——边下边识别，且不重复下载
            if (isNetworkUrl(url)) {
                Log.d(TAG, "自动生成字幕（网络渐进，按区间读取）: " + url);
                startHttpProgressiveAsr(url);
                return true;
            }
            Log.d(TAG, "自动生成字幕：无本地文件且非网络地址，无法识别: " + url);
            return false;
        }

        // 完整识别需要本地文件
        if (local == null) {
            // 未缓存网络视频走不到完整识别：整片缓存要等到快播完才齐。
            // 「新视频自动开始」的语义是「现在就为这集准备字幕」，让用户等一整集
            // 才看到结果等于没生效，故渐进可用时直接改走边下边识别——这是此刻
            // 唯一能立即开始的路径（用户不需要另外开「边看边识别」）。
            if (progressiveAvailable && isNetworkUrl(url)) {
                Log.d(TAG, "自动生成字幕（网络渐进兜底，按区间读取）: " + url);
                startHttpProgressiveAsr(url);
                return true;
            }
            Log.d(TAG, "自动生成字幕：当前视频尚未缓存完整，等待播放完成: " + url);
            return false;
        }
        Log.d(TAG, "自动生成字幕（完整版）: " + url);
        startAsrGenerate(local, false, true);
        return true;
    }

    private boolean isNetworkUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * 对未缓存的网络视频启动渐进识别：经 danikula 本地代理按区间读取音频，
     * 已缓存部分直接命中、缺失部分按 byte range 补下，与播放器共用同一份缓存。
     */
    private void startHttpProgressiveAsr(String url) {
        startProgressiveAsr(new com.orange.playerlibrary.speech.HttpBlockAudioSource(
                mContext, url, mVideoView != null ? mVideoView.getVideoHeaders() : null,
                mVideoView != null ? mVideoView.getDuration() : 0), url);
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
     * 【测试专用】直接触发播放器 UI 事件，绕过点击链路。
     * 供自动化测试（adb broadcast 或宿主 app 调试入口）调用。
     * 必须在主线程执行。
     *
     * 支持命令：
     *  subtitle_dialog  打开字幕设置对话框
     *  ai_settings      打开 AI 翻译设置对话框
     *  ai_translate     立即执行 AI 批量翻译（需已加载字幕且已配置 Key）
     *  ocr_settings     打开 OCR 翻译设置
     */
    public void handleTestCommand(String command) {
        if (command == null) {
            return;
        }
        Log.d(TAG, "handleTestCommand: " + command);
        try {
            // 复合命令：load_subtitle_url:<url> —— 直接加载字幕并显示
            if (command.startsWith("load_subtitle_url:")) {
                String url = command.substring("load_subtitle_url:".length()).trim();
                if (!url.isEmpty()) {
                    loadSubtitleFromUrl(url);
                }
                return;
            }
            // 复合命令：seek_to:<ms> —— 跳到指定播放位置（测试字幕时间轴用）
            if (command.startsWith("seek_to:")) {
                try {
                    long ms = Long.parseLong(command.substring("seek_to:".length()).trim());
                    if (mVideoView != null) {
                        mVideoView.seekTo(ms);
                        Log.d(TAG, "handleTestCommand: seekTo " + ms + "ms");
                    }
                } catch (NumberFormatException ignored) {
                }
                return;
            }
            // 复合命令：asr_gen_path:<视频绝对路径> —— 直接对文件生成字幕（测试用）
            if (command.startsWith("asr_gen_path:")) {
                String p = command.substring("asr_gen_path:".length()).trim();
                if (!p.isEmpty()) {
                    startAsrGenerate(new java.io.File(p));
                }
                return;
            }
            // 复合命令：asr_gen_hls:<m3u8 url> —— 下载 HLS 后生成字幕（测试用）
            if (command.startsWith("asr_gen_hls:")) {
                String u = command.substring("asr_gen_hls:".length()).trim();
                if (!u.isEmpty()) {
                    downloadHlsAndGenerate(u);
                }
                return;
            }
            switch (command) {
                case "subtitle_dialog":
                    showSubtitleDialog(null);
                    break;
                case "asr_gen_ui":
                    startAsrGenerateUi();
                    break;
                case "ai_settings":
                    showAiSettingsDialog();
                    break;
                case "ai_translate":
                    startAiTranslate();
                    break;
                case "ocr_settings":
                    showOcrTranslateSettings();
                    break;
                case "native_libs":
                    showNativeLibsDialog();
                    break;
                case "setup_dialog":
                    showSetupDialog();
                    break;
                default:
                    Log.w(TAG, "未知测试命令: " + command);
            }
        } catch (Exception e) {
            Log.e(TAG, "handleTestCommand(" + command + ") 执行异常", e);
        }
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
            // 不能静默：布局 inflate 或 DialogUtils 抛错时对话框根本不出现，
            // 用户只看到「点了没反应」，没有日志就无从排查
            Log.e(TAG, "倍速对话框创建失败", e);
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

            // 切换视频：渐进识别会话针对旧视频，立即结束
            stopProgressiveAsr();
            // mpv 侧切文件已清原生字幕轨，宿主状态同步复位
            // （新视频的字幕记忆由 tryLoadRememberedSubtitle 按 URL 重新恢复）
            if (mNativeAssPath != null) {
                mNativeAssPath = null;
                mNativeAssVideoUrl = null;
            }

            // 清空旧视频的字幕（渐进 ASR 注入的条目留在列表里，新视频播到
            // 相同时间段会把旧字幕显示出来）；sProgressiveSubtitleShown 同步复位，
            // 否则新视频的字幕面板不会自动显示。
            stopAsrProgressTicker();
            sProgressiveSubtitleShown = false;
            dismissAsrRing();
            if (mController != null && mController.getSubtitleManager() != null) {
                mController.getSubtitleManager().clear();
            }
            
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
     * 恢复上次播放的集数（选集记忆）。
     *
     * <p>由调用方在「选集列表填充完成后」调用一次。内部直接走 {@link #playEpisode(int)}，
     * 与用户点选集完全同一条路径（切源 + 设标题 + 起播），因此会立即开始播放该集；
     * 进度续播由播放器自身的记忆播放（按单集 URL 绑定）在 onPrepared 阶段完成。
     *
     * @return 实际恢复到的集数下标；无记录或列表为空时返回 -1
     */
    public int restoreLastEpisode() {
        ArrayList<HashMap<String, Object>> videoList = mController.getVideoList();
        if (videoList == null || videoList.isEmpty()) {
            android.util.Log.d("VideoEventManager", "restoreLastEpisode: 列表为空");
            return -1;
        }
        String seriesKey = getSeriesKey();
        int index = mSettingsManager.getLastEpisodeIndex(seriesKey);
        android.util.Log.d("VideoEventManager", "restoreLastEpisode: seriesKey=" + seriesKey
                + ", savedIndex=" + index + ", size=" + videoList.size());
        if (index < 0 || index >= videoList.size()) {
            return -1;
        }

        HashMap<String, Object> item = videoList.get(index);
        String url = item.get("url") != null ? item.get("url").toString() : "";
        if (url.isEmpty()) {
            return -1;
        }

        // 直接复用 playEpisode：恢复路径与「用户点选集」走同一套代码，
        // 避免两处逻辑漂移（标题、headers、release+startPlayLogic 的顺序都一致）。
        //
        // 调用方可能在工作线程填列表（iApp 的加载块就是），而 setUrl/startPlayLogic
        // 会触碰视图，必须在主线程执行，否则抛「新线程里不允许直接对视图更新」。
        final int idx = index;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mVideoView.post(new Runnable() {
                @Override
                public void run() {
                    playEpisode(idx);
                }
            });
            return index;
        }

        playEpisode(index);
        return index;
    }

    /**
     * 取上次播放的集数下标（不产生任何播放副作用），供 UI 高亮使用。
     *
     * @return 下标；无记录返回 -1
     */
    public int getLastEpisodeIndex() {
        return mSettingsManager.getLastEpisodeIndex(getSeriesKey());
    }

    /**
     * 取上次播放那一集的地址。用于 UI 高亮——比下标可靠，因为显示列表可能被
     * 排序或反转，下标会错位。
     *
     * @return 地址；无记录或下标越界时返回 null
     */
    public String getLastEpisodeUrl() {
        int index = getLastEpisodeIndex();
        ArrayList<HashMap<String, Object>> videoList = mController.getVideoList();
        if (index < 0 || videoList == null || index >= videoList.size()) {
            return null;
        }
        String url = getItemUrl(videoList, index);
        return url.isEmpty() ? null : url;
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
        
        // 设置播放核心按钮（MPV 可选内核）
        android.widget.TextView mpvEngineBtn = dialogView.findViewById(R.id.mpvhx);
        setupEngineButtons(aliEngineBtn, exoEngineBtn, ijkEngineBtn, systemEngineBtn, mpvEngineBtn);

        // 扩展包管理入口：紧邻播放核心——内核缺 so 时按钮变暗，用户据此进来下载
        bindNativeLibsEntry(dialogView, mCurrentSetupDialog);

        // 去广告开关：全屏观看时也能直接切换，与主界面按钮同状态
        bindAdRemovalEntry(dialogView);

        // 记忆播放 / 选集记忆
        bindMemoryPlayEntry(dialogView);

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

        // 绑定画质增强按钮点击事件（TV 模式隐藏入口，与 GL 渲染冲突）
        android.widget.LinearLayout filterButton = dialogView.findViewById(R.id.line_filter);
        if (filterButton != null) {
            if (OrangePlayerConfig.isTvMode(mContext)) {
                filterButton.setVisibility(View.GONE);
            } else {
                filterButton.setOnClickListener(v -> showVideoFilterDialog());
            }
        }
    }
    
    /**
     * 设置播放核心按钮
     */
    private void setupEngineButtons(android.widget.TextView aliBtn, android.widget.TextView exoBtn,
                                   android.widget.TextView ijkBtn, android.widget.TextView systemBtn) {
        setupEngineButtons(aliBtn, exoBtn, ijkBtn, systemBtn, null);
    }

    private void setupEngineButtons(android.widget.TextView aliBtn, android.widget.TextView exoBtn,
                                   android.widget.TextView ijkBtn, android.widget.TextView systemBtn,
                                   android.widget.TextView mpvBtn) {
        // 检查核心是否可用。so 已改为按需下载，故分三态：
        //   宿主未引入（类缺失）→ 隐藏按钮
        //   已引入但 so 未下载   → 显示按钮，点击进下载流程
        //   已就绪               → 显示按钮，点击直接切换
        boolean aliSupported = com.orange.playerlibrary.utils.PlayerEngineAvailability
                .isSupported(PlayerConstants.ENGINE_ALI);
        boolean ijkSupported = com.orange.playerlibrary.utils.PlayerEngineAvailability
                .isSupported(PlayerConstants.ENGINE_IJK);
        boolean mpvSupported = com.orange.playerlibrary.utils.PlayerEngineAvailability
                .isSupported(PlayerConstants.ENGINE_MPV);

        boolean isAliReady = com.orange.playerlibrary.utils.PlayerEngineAvailability
                .isUsable(PlayerConstants.ENGINE_ALI);
        boolean isIjkReady = isIjkPlayerAvailable();
        boolean isMpvReady = com.orange.playerlibrary.utils.PlayerEngineAvailability
                .isUsable(PlayerConstants.ENGINE_MPV);

        // ExoPlayer 检测：GSY 11.x 使用 Media3，也检测旧版 ExoPlayer2
        boolean isExoPlayerAvailable = isClassPresent("com.shuyu.gsyvideoplayer.player.Exo2PlayerManager") ||
                                       isClassPresent("com.google.android.exoplayer2.ExoPlayer") ||
                                       isClassPresent("com.google.android.exoplayer2.Player") ||
                                       isClassPresent("androidx.media3.exoplayer.ExoPlayer");

        // 调试日志：显示内核可用性
        android.util.Log.d("VideoEventManager", "setupEngineButtons: Ali=" + aliSupported
                + "(ready=" + isAliReady + "), IJK=" + ijkSupported + "(ready=" + isIjkReady
                + "), Exo=" + isExoPlayerAvailable + ", MPV=" + mpvSupported
                + "(ready=" + isMpvReady + ")");

        // 设置可见性：未引入才隐藏；已引入但缺 so 仍显示（否则用户无从发现与下载）
        if (aliBtn != null) aliBtn.setVisibility(aliSupported ? View.VISIBLE : View.GONE);
        if (ijkBtn != null) ijkBtn.setVisibility(ijkSupported ? View.VISIBLE : View.GONE);
        if (exoBtn != null) exoBtn.setVisibility(isExoPlayerAvailable ? View.VISIBLE : View.GONE);
        if (systemBtn != null) systemBtn.setVisibility(View.VISIBLE); // 系统核心始终可用
        if (mpvBtn != null) mpvBtn.setVisibility(mpvSupported ? View.VISIBLE : View.GONE);

        // 获取当前引擎
        String currentEngine = mSettingsManager.getPlayerEngine();

        // 调试日志：显示当前引擎
        android.util.Log.d("VideoEventManager", "setupEngineButtons: currentEngine=" + currentEngine);

        // 高亮当前引擎；未下载的内核用暗色暗示「需要先下载」（点击会引导下载）。
        // 颜色优先级：当前引擎 > 未就绪 > 普通。
        if (aliBtn != null) {
            aliBtn.setTextColor(engineTextColor(currentEngine, PlayerConstants.ENGINE_ALI, isAliReady));
            aliBtn.setOnClickListener(v -> selectEngine(PlayerConstants.ENGINE_ALI));
        }
        if (exoBtn != null) {
            exoBtn.setTextColor(engineTextColor(currentEngine, PlayerConstants.ENGINE_EXO, true));
            exoBtn.setOnClickListener(v -> selectEngine(PlayerConstants.ENGINE_EXO));
        }
        if (ijkBtn != null) {
            ijkBtn.setTextColor(engineTextColor(currentEngine, PlayerConstants.ENGINE_IJK, isIjkReady));
            ijkBtn.setOnClickListener(v -> selectEngine(PlayerConstants.ENGINE_IJK));
        }
        if (systemBtn != null) {
            systemBtn.setTextColor(engineTextColor(currentEngine, PlayerConstants.ENGINE_DEFAULT, true));
            systemBtn.setOnClickListener(v -> selectEngine(PlayerConstants.ENGINE_DEFAULT));
        }
        if (mpvBtn != null && mpvSupported) {
            mpvBtn.setTextColor(engineTextColor(currentEngine, PlayerConstants.ENGINE_MPV, isMpvReady));
            mpvBtn.setOnClickListener(v -> selectEngine(PlayerConstants.ENGINE_MPV));
        }
    }

    /** 未下载的内核比普通态更暗，提示「还需要先下载」 */
    private static final int COLOR_NOT_READY = Color.parseColor("#6B6B6B");

    private static int engineTextColor(String currentEngine, String engine, boolean ready) {
        if (engine.equals(currentEngine)) {
            return COLOR_HIGHLIGHT;
        }
        return ready ? COLOR_NORMAL : COLOR_NOT_READY;
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
        // so 已改为按需下载：未就绪的内核不能直接切（会静默回退却谎报切换成功），
        // 也不该写入偏好（否则下次启动又走一遍回退）。改为引导下载。
        if (com.orange.playerlibrary.utils.PlayerEngineAvailability.needsDownload(engine)) {
            promptDownloadEngine(engine);
            return;
        }
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
     * 内核的 so 未下载时引导下载，完成后自动切过去。
     *
     * 不走「先写偏好再切」：写进去的偏好下次启动会被 initPlayerFactory 判为不可用
     * 而回退，用户会看到「设置了却不生效」。这里下载成功才落偏好。
     */
    private void promptDownloadEngine(final String engine) {
        final String bundleId = com.orange.playerlibrary.utils.PlayerEngineAvailability
                .bundleIdFor(engine);
        if (bundleId == null) {
            return;
        }
        final com.orange.playerlibrary.tool.NativeLibManager.BundleInfo info =
                com.orange.playerlibrary.tool.NativeLibManager.getBundle(bundleId);
        if (info == null) {
            return;
        }
        final String sizeText = com.orange.playerlibrary.tool.NativeLibManager.formatSize(info.size());
        new AlertDialog.Builder(mActivity)
                .setTitle("下载 " + getEngineName(engine) + " 内核")
                .setMessage("该内核不再随安装包分发，首次使用需下载（约 " + sizeText
                        + "，支持断点续传）。\n\n下载完成后将自动切换。")
                .setPositiveButton("下载", (d, w) -> {
                    final DownloadProgressDialog progress =
                            new DownloadProgressDialog(mActivity);
                    progress.showWithRealProgress("下载 " + getEngineName(engine) + " 内核",
                            "共约 " + sizeText);
                    com.orange.playerlibrary.tool.NativeLibManager.download(mContext, bundleId,
                            new com.orange.playerlibrary.tool.NativeLibManager.InstallCallback() {
                                @Override
                                public void onProgress(final int percent, final long downloaded,
                                                       final long total, final String stage) {
                                    mActivity.runOnUiThread(() -> progress.setProgress(percent,
                                            String.format(java.util.Locale.US, "%.1f/%.1f MB",
                                                    downloaded / 1048576.0, total / 1048576.0)));
                                }

                                @Override
                                public void onSuccess(final boolean loaded) {
                                    mActivity.runOnUiThread(() -> {
                                        progress.complete("下载完成");
                                        if (loaded) {
                                            // so 已加载，直接切（selectEngine 此时能通过门禁）
                                            selectEngine(engine);
                                        } else {
                                            // 内核类的 <clinit> 可能已被污染（ali/mpv），
                                            // 本进程内加载不回来，只能提示重启
                                            showToast("内核已下载，请重启应用后生效");
                                        }
                                    });
                                }

                                @Override
                                public void onError(final String error) {
                                    mActivity.runOnUiThread(() -> {
                                        progress.fail("下载失败", error);
                                        showToast("下载失败：" + error);
                                    });
                                }
                            });
                })
                .setNegativeButton("取消", null)
                .show();
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
            case PlayerConstants.ENGINE_MPV:
                return "MPV";
            case PlayerConstants.ENGINE_DEFAULT:
            default:
                return "系统核心";
        }
    }
    
    /**
     * 检查类是否存在。
     *
     * <p>必须用 {@code initialize=false}：内核类（尤其 ali 的 NativePlayerBase、
     * mpv 的 MPVLib）的静态块会 loadLibrary，而 so 已改为按需下载——触发初始化
     * 会抛错并把该类永久污染（ali 更隐蔽：状态位先置 true 再加载，之后永不重试）。
     */
    private boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, getClass().getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 检查 IJK 播放器是否可用（Java 类存在 && so 已按需下载）。
     * 改造前只查类，so 剔出 APK 后会误判可用。
     */
    private boolean isIjkPlayerAvailable() {
        boolean available = com.orange.playerlibrary.utils.PlayerEngineAvailability
                .isUsable(PlayerConstants.ENGINE_IJK);
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
     * 显示画质增强（滤镜）选择对话框
     */
    private void showVideoFilterDialog() {
        // 阿里云内核渲染管线与 GL 滤镜不兼容（蓝白闪烁），直接提示不可用
        if (PlayerConstants.ENGINE_ALI.equals(mSettingsManager.getPlayerEngine())) {
            showToast("阿里云内核暂不支持画质增强");
            return;
        }

        // mpv 内核：GL 滤镜不适用（渲染层恒 TextureView、滤镜在 mpv 内部
        // glsl-shaders 链处理），面板改提供 Anime4K 超分档位
        boolean isMpv = PlayerConstants.ENGINE_MPV.equals(mSettingsManager.getPlayerEngine());

        if (mCurrentSetupDialog != null) {
            mCurrentSetupDialog.dismiss();
        }

        hideController();

        View dialogView = View.inflate(mActivity, R.layout.speed_dialog, null);

        final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                DialogUtils.DialogPosition.RIGHT, null, null);

        RecyclerView recyclerView = dialogView.findViewById(R.id.recycler);
        if (recyclerView == null) return;

        ArrayList<HashMap<String, Object>> arrayList = new ArrayList<>();
        String[] filters = isMpv
                ? new String[]{
                    PlayerSettingsManager.FILTER_OFF,
                    PlayerSettingsManager.FILTER_SHARPEN,
                    PlayerSettingsManager.FILTER_VIVID,
                    PlayerSettingsManager.FILTER_BLACK_WHITE,
                    PlayerSettingsManager.FILTER_SEPIA,
                    PlayerSettingsManager.FILTER_ANIME4K_SUPER}
                : new String[]{
                    PlayerSettingsManager.FILTER_OFF,
                    PlayerSettingsManager.FILTER_SHARPEN,
                    PlayerSettingsManager.FILTER_VIVID,
                    PlayerSettingsManager.FILTER_BLACK_WHITE,
                    PlayerSettingsManager.FILTER_SEPIA};
        for (String filter : filters) {
            HashMap<String, Object> map = new HashMap<>();
            map.put("name", filter);
            arrayList.add(map);
        }

        // 高亮当前项：mpv 走独立档位持久化，其他内核看 GL 滤镜档位
        final String currentFilter = isMpv
                ? mSettingsManager.getMpvVideoFilter()
                : mSettingsManager.getVideoFilter();

        OrangeRecyclerView orangeRecyclerView = new OrangeRecyclerView();
        orangeRecyclerView.setLinearLayoutManager(recyclerView, mActivity);
        orangeRecyclerView.setAdapter(recyclerView, R.layout.speed_dialog_item, arrayList,
            (holder, data, position) -> {
                android.widget.TextView filterName = holder.itemView.findViewById(R.id.title);
                String filterText = data.get(position).get("name").toString();

                // 高亮当前滤镜
                if (filterText.equals(currentFilter)) {
                    filterName.setTextColor(COLOR_HIGHLIGHT);
                    filterName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
                } else {
                    filterName.setTextColor(COLOR_NORMAL);
                    filterName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
                }

                filterName.setText(filterText);

                filterName.setOnClickListener(v -> {
                    if (isMpv) {
                        // 档位走 mpv glsl-shaders 链，点击即实时切换
                        if (mVideoView != null) {
                            mVideoView.applyMpvEnhancement(filterText);
                        }
                        showToast("画质增强: " + (PlayerSettingsManager.FILTER_OFF.equals(filterText)
                                ? "已关闭" : filterText + " 已开启"));
                    } else {
                        mSettingsManager.setVideoFilter(filterText);
                        if (mVideoView != null) {
                            mVideoView.applyVideoFilter(filterText);
                        }
                        showToast("画质增强: " + filterText);
                    }
                    dialog.dismiss();
                });
            });
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

        // 高亮当前播放集数：用描边+浅填充卡片区分，字号保持稳定。
        //
        // 两级判据：
        //   1) 地址匹配——正在播放时最准（注意不能只比 getUrl()，去广告后它会变成
        //      回环代理地址，见 findCurrentEpisodeIndex）
        //   2) 持久化的那一集地址——重启后还没起播时第 1 级必然全部落空，
        //      此时靠选集记忆把上次那一集高亮出来。
        //      注意不能用 position 比对下标：显示列表可能被排序/反转，position
        //      与真实列表下标已不对应，按地址比才可靠。
        String itemUrl = itemData.get("url") != null ? itemData.get("url").toString() : "";
        boolean isCurrent = matchesCurrentEpisodeUrl(itemUrl);
        if (!isCurrent) {
            String savedUrl = getLastEpisodeUrl();
            isCurrent = savedUrl != null && savedUrl.equals(itemUrl);
        }
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

        // 记住这一集，供下次启动恢复
        rememberCurrentEpisodeIndex(item);

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
     * 持久化「当前播到哪一集」。
     *
     * 进度秒数本身已由 PlaybackProgressManager 按单集 URL 绑定（每集地址不同，
     * 天然区分），这里只记录集数下标，重启后才能切回同一集。
     */
    private void rememberCurrentEpisodeIndex(HashMap<String, Object> item) {
        ArrayList<HashMap<String, Object>> videoList = mController.getVideoList();
        if (videoList == null || videoList.isEmpty()) {
            return;
        }
        String itemUrl = item.get("url") != null ? item.get("url").toString() : "";
        if (itemUrl.isEmpty()) {
            return;
        }
        for (int i = 0; i < videoList.size(); i++) {
            if (itemUrl.equals(getItemUrl(videoList, i))) {
                mSettingsManager.setLastEpisodeIndex(getSeriesKey(), i);
                android.util.Log.d("VideoEventManager", "rememberCurrentEpisodeIndex: index=" + i
                        + ", seriesKey=" + getSeriesKey());
                return;
            }
        }
    }

    /**
     * 剧集标识：用于把「上次播到哪一集」绑定到某个选集列表。
     * 复用列表 hash，同一列表内换集不会产生新记录。
     */
    private String getSeriesKey() {
        return "s" + getVideoListHash();
    }

    /**
     * 供外部（门面）构造同一套存储键，用于清除选集记忆等操作。
     */
    public String getSeriesKeyForStorage() {
        return getSeriesKey();
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

        // 记住这一集，供下次启动恢复
        rememberCurrentEpisodeIndex(item);

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
     * 在选集列表中定位当前播放集的下标。
     *
     * 不能只看 {@code mVideoView.getUrl()}：库内部会改写实际播放地址——M3U8 去广告
     * 后变成回环代理地址（{@code http://127.0.0.1:port/cleaned/<hash>.m3u8}）、已下载
     * 视频变成 {@code file://} 本地路径。此时 getUrl() 与列表里的原始地址不相等，
     * 反查会失败（表现为「点下一集没反应/提示已经是最后一集了」）。
     *
     * 依次尝试三个候选值，全部精确匹配（不做模糊/前缀匹配，避免不同集撞车）：
     * <ol>
     *   <li>{@code getSourceUrl()} —— 调用方最初传入的地址，最可靠</li>
     *   <li>{@code getUrl()} —— 当前实际播放地址，覆盖列表本身存的就是改写后地址的情况</li>
     *   <li>去掉 query 后比较 —— 部分源站会在播放过程中改写 URL 的查询串</li>
     * </ol>
     *
     * @return 命中下标；无法定位时返回 -1
     */
    private int findCurrentEpisodeIndex(ArrayList<HashMap<String, Object>> videoList) {
        if (videoList == null || videoList.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < videoList.size(); i++) {
            if (matchesCurrentEpisodeUrl(getItemUrl(videoList, i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 判断某个选集条目的地址是否就是当前正在播放的那一集。
     * 匹配规则见 {@link com.orange.playerlibrary.utils.EpisodeUrlMatcher}。
     */
    private boolean matchesCurrentEpisodeUrl(String itemUrl) {
        return com.orange.playerlibrary.utils.EpisodeUrlMatcher.matches(
                itemUrl, mVideoView.getSourceUrl(), mVideoView.getUrl());
    }

    private static String getItemUrl(ArrayList<HashMap<String, Object>> videoList, int index) {
        Object url = videoList.get(index).get("url");
        return url != null ? url.toString() : "";
    }

    /**
     * 播放下一集
     */
    public void playNextEpisode() {
        ArrayList<HashMap<String, Object>> videoList = mController.getVideoList();
        if (videoList == null || videoList.isEmpty()) {
            android.util.Log.d("VideoEventManager", "playNextEpisode: videoList 为空, size="
                    + (videoList == null ? "null" : videoList.size()));
            return;
        }

        int currentIndex = findCurrentEpisodeIndex(videoList);
        android.util.Log.d("VideoEventManager", "playNextEpisode: size=" + videoList.size()
                + ", currentIndex=" + currentIndex
                + ", sourceUrl=" + mVideoView.getSourceUrl()
                + ", playingUrl=" + mVideoView.getUrl()
                + ", item[0]=" + getItemUrl(videoList, 0));

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

        int currentIndex = findCurrentEpisodeIndex(videoList);
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
    /**
     * 按开关状态刷新「开启/关闭」按钮文案与配色。
     *
     * <p>字幕面板的布尔项用 TextView 承载（而非原生 Switch）：宿主是 Material3
     * 主题，Switch 的 thumb/track 取 colorSecondary，默认是紫色，与橘子配色冲突；
     * 改 tint 还要在 Java 侧维护两套状态。按钮 + 文案与设置面板的去广告开关
     * （{@code renderAdRemovalEntry}）完全一致，且不依赖任何主题属性。
     */
    private void renderSwitchButton(android.widget.TextView btn, boolean on) {
        if (btn == null) {
            return;
        }
        btn.setText(on ? "已开启" : "已关闭");
        btn.setTextColor(on ? 0xFFFFFFFF : 0xB3FFFFFF);
        btn.setBackgroundResource(on
                ? R.drawable.btn_bundle_action_bg      // 开：品牌橙
                : R.drawable.btn_bundle_delete_bg);    // 关：中性描边
    }

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
            
            // 字幕开关（TextView + 文案切换，见 SubtitlePanelSwitch 样式注释）
            android.widget.TextView subtitleSwitch = dialogView.findViewById(R.id.subtitle_switch);
            if (subtitleSwitch != null) {
                // 原生渲染模式下 SubtitleView 可见性由宿主控制（启用时强制 hide），
                // 开关语义映射为 mpv 字幕轨挂/卸，初始态用路径存在性判断
                final boolean[] subtitleOn = {mNativeAssPath != null
                        || mController.isSubtitleEnabled()};
                renderSwitchButton(subtitleSwitch, subtitleOn[0]);
                subtitleSwitch.setOnClickListener(v -> {
                    boolean isChecked = !subtitleOn[0];
                    subtitleOn[0] = isChecked;
                    renderSwitchButton(subtitleSwitch, isChecked);
                    mSettingsManager.setSubtitleEnabled(isChecked);
                    if (mNativeAssPath != null) {
                        // 原生模式：开关直接控制 mpv 字幕轨
                        mVideoView.setNativeAssSubtitle(isChecked ? mNativeAssPath : null);
                    } else if (isChecked) {
                        mController.getSubtitleManager().show();
                        mController.startSubtitle();
                    } else {
                        mController.getSubtitleManager().hide();
                        mController.stopSubtitle();
                        // 字幕关闭 = 识别/翻译全部停止（OCR 翻译、渐进 ASR、
                        // 悬浮环、渐进翻译），否则识别结果无处显示、
                        // 悬浮进度环残留（真机实测）
                        stopOcrTranslate();
                        stopProgressiveAsr();
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

            // 音频延迟调节（步进 100ms；正值延后音频。mpv 内核专属能力，
            // 其他内核 setAudioDelayMs 返回 false → 面板置灰不可用）
            android.widget.TextView audioDelayText = dialogView.findViewById(R.id.audio_delay_text);
            View btnAudioMinus = dialogView.findViewById(R.id.btn_audio_delay_minus);
            View btnAudioReset = dialogView.findViewById(R.id.btn_audio_delay_reset);
            View btnAudioPlus = dialogView.findViewById(R.id.btn_audio_delay_plus);
            boolean audioDelaySupported =
                    PlayerConstants.ENGINE_MPV.equals(mVideoView.getCurrentPlayerEngine());
            final long[] currentAudioDelay = {mSettingsManager.getAudioDelayForVideo(
                    mVideoView.getUrl() != null ? mVideoView.getUrl() : "")};
            if (audioDelayText != null) {
                updateAudioDelayText(audioDelayText, currentAudioDelay[0]);
                if (!audioDelaySupported) {
                    audioDelayText.setText("音频延迟: 仅 mpv 内核支持");
                }
            }
            for (View b : new View[]{btnAudioMinus, btnAudioReset, btnAudioPlus}) {
                if (b != null) {
                    b.setEnabled(audioDelaySupported);
                    b.setAlpha(audioDelaySupported ? 1f : 0.4f);
                }
            }
            View.OnClickListener audioDelayClickListener = v -> {
                long delta = v.getId() == R.id.btn_audio_delay_plus ? 100
                        : v.getId() == R.id.btn_audio_delay_minus ? -100 : -currentAudioDelay[0];
                long next = currentAudioDelay[0] + delta;
                if (applyAudioDelay(next)) {
                    currentAudioDelay[0] = next;
                }
                if (audioDelayText != null) {
                    updateAudioDelayText(audioDelayText, currentAudioDelay[0]);
                }
            };
            if (btnAudioMinus != null) btnAudioMinus.setOnClickListener(audioDelayClickListener);
            if (btnAudioReset != null) btnAudioReset.setOnClickListener(audioDelayClickListener);
            if (btnAudioPlus != null) btnAudioPlus.setOnClickListener(audioDelayClickListener);
            
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
                    // 组件未下载：区分「宿主未引依赖」（只能改 gradle）与「按需下载」
                    boolean ocrSupported = com.orange.playerlibrary.tool.NativeLibManager
                            .isSupported(com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_OCR);
                    boolean translateSupported = com.orange.playerlibrary.tool.NativeLibManager
                            .isSupported(com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_TRANSLATE);
                    if (ocrSupported && translateSupported) {
                        if (ocrStatus != null) {
                            ocrStatus.setText("需要先下载文字识别与翻译组件");
                            ocrStatus.setTextColor(0xFFFF8F3F);
                        }
                        ((android.widget.TextView) btnOcrTranslate).setText("去下载组件");
                        btnOcrTranslate.setOnClickListener(v -> {
                            dialog.dismiss();
                            showNativeLibsDialog();
                        });
                    } else {
                        if (ocrStatus != null) {
                            ocrStatus.setText("需要安装额外依赖");
                            ocrStatus.setTextColor(0xFFFF6B6B);
                        }
                        ((android.widget.TextView) btnOcrTranslate).setText("查看安装说明");
                        btnOcrTranslate.setOnClickListener(v -> {
                            dialog.dismiss();
                            showOcrInstallGuide();
                        });
                    }
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

            // ===== AI 批量翻译字幕（LLM 分批翻译已加载的外挂字幕）=====
            android.widget.TextView aiStatus = dialogView.findViewById(R.id.ai_status);
            View btnAiTranslate = dialogView.findViewById(R.id.btn_ai_translate);
            View btnAiSettings = dialogView.findViewById(R.id.btn_ai_settings);

            boolean subtitleLoaded = mController != null
                    && mController.getSubtitleManager() != null
                    && mController.getSubtitleManager().getSubtitleCount() > 0;
            // 能否走在线翻译由「翻译引擎」设置决定：填了 Key 也可能被选成「仅本地」
            boolean aiConfigured = isRemoteTranslationEnabled();
            boolean engineLocal = PlayerSettingsManager.ENGINE_LOCAL.equals(
                    mSettingsManager.getTranslateEngine());

            if (btnAiSettings != null) {
                btnAiSettings.setOnClickListener(v -> {
                    dialog.dismiss();
                    showAiSettingsDialog();
                });
            }

            if (btnAiTranslate != null) {
                if (engineLocal) {
                    // 批量翻译字幕只有在线大模型一条链路（本地模型不提供批量整片翻译），
                    // 故「仅本地」时必须明确告知，而不是让按钮点下去什么都不发生
                    if (aiStatus != null) {
                        aiStatus.setText("翻译引擎已设为「仅本地」——批量翻译字幕需在线大模型");
                        aiStatus.setTextColor(0xFFFF8F3F);
                    }
                    ((android.widget.TextView) btnAiTranslate).setText("改设置");
                    btnAiTranslate.setOnClickListener(v -> {
                        dialog.dismiss();
                        showAiSettingsDialog();
                    });
                } else if (!aiConfigured) {
                    if (aiStatus != null) {
                        aiStatus.setText("未配置 AI Key——点「AI 设置」填入接口地址与 Key");
                        aiStatus.setTextColor(0xFFFF6B6B);
                    }
                    ((android.widget.TextView) btnAiTranslate).setText("先配置 AI");
                    btnAiTranslate.setOnClickListener(v -> {
                        dialog.dismiss();
                        showAiSettingsDialog();
                    });
                } else if (isAiTranslating()) {
                    if (aiStatus != null) {
                        aiStatus.setText("AI 翻译进行中，请稍候...");
                        aiStatus.setTextColor(0xFF4CAF50);
                    }
                    ((android.widget.TextView) btnAiTranslate).setText("翻译中");
                    btnAiTranslate.setEnabled(false);
                } else if (!subtitleLoaded) {
                    if (aiStatus != null) {
                        aiStatus.setText("请先加载字幕（本地/网络）再使用 AI 翻译");
                        aiStatus.setTextColor(0xFFFF8F3F);
                    }
                    // 按钮承担「去加载」而不是弹一句无出路的 toast：
                    // 状态行已经说清了原因，按钮就该给下一步动作
                    ((android.widget.TextView) btnAiTranslate).setText("去加载字幕");
                    btnAiTranslate.setOnClickListener(v -> {
                        dialog.dismiss();
                        showSubtitleFilePicker();
                    });
                } else {
                    if (aiStatus != null) {
                        aiStatus.setText("将 " + mController.getSubtitleManager().getSubtitleCount()
                                + " 条字幕翻译为「" + mSettingsManager.getAiTargetLang() + "」");
                        aiStatus.setTextColor(0xFF4CAF50);
                    }
                    btnAiTranslate.setOnClickListener(v -> {
                        dialog.dismiss();
                        startAiTranslate();
                    });
                }
            }

            // ===== AI 语音生成字幕（离线 ASR）=====
            android.widget.TextView asrStatus = dialogView.findViewById(R.id.asr_status);
            View btnAsrGenerate = dialogView.findViewById(R.id.btn_asr_generate);
            android.widget.TextView asrLiveSwitch = dialogView.findViewById(R.id.asr_live_switch);
            if (asrLiveSwitch != null) {
                final boolean[] liveOn = {mSettingsManager.isAsrLiveEnabled()};
                renderSwitchButton(asrLiveSwitch, liveOn[0]);
                asrLiveSwitch.setOnClickListener(v -> {
                    boolean isChecked = !liveOn[0];
                    liveOn[0] = isChecked;
                    renderSwitchButton(asrLiveSwitch, isChecked);
                    mSettingsManager.setAsrLiveEnabled(isChecked);
                    if (!isChecked) {
                        // 关闭「边看边识别」：结束在途渐进会话、摘掉悬浮环，
                        // 并回收已注入的识别字幕——否则关掉后旧字幕仍在屏幕上
                        // 滚动（用户不知来源，且不会再更新）
                        stopProgressiveAsr();
                        if (mController != null && mController.getSubtitleManager() != null) {
                            mController.getSubtitleManager().clearAppended();
                        }
                    } else {
                        // 重新开启：对当前视频重启渐进识别。自动触发不会再启动
                        // （URL 已在 sAutoAsrTriggeredUrls 去重集合中），必须在此显式重启
                        startProgressiveAsrForCurrentVideo();
                    }
                });
            }
            android.widget.TextView asrAutoSwitch = dialogView.findViewById(R.id.asr_auto_switch);
            if (asrAutoSwitch != null) {
                final boolean[] autoOn = {mSettingsManager.isAsrAutoEnabled()};
                renderSwitchButton(asrAutoSwitch, autoOn[0]);
                asrAutoSwitch.setOnClickListener(v -> {
                    boolean isChecked = !autoOn[0];
                    autoOn[0] = isChecked;
                    renderSwitchButton(asrAutoSwitch, isChecked);
                    mSettingsManager.setAsrAutoEnabled(isChecked);
                    if (isChecked) {
                        // 拨开即对当前视频生效，与「边看边识别」开关行为一致。
                        // 光存 pref 不够：自动触发只认后续的播放状态变化，当前这集
                        // 不会有状态变化，要等到换集才生效（看起来像开关失灵）
                        startAsrForCurrentVideoNow();
                    }
                });
            }
            android.widget.TextView asrTranslateSwitch =
                    dialogView.findViewById(R.id.asr_translate_switch);
            if (asrTranslateSwitch != null) {
                final boolean[] trOn = {mSettingsManager.isAsrAutoTranslateEnabled()};
                renderSwitchButton(asrTranslateSwitch, trOn[0]);
                asrTranslateSwitch.setOnClickListener(v -> {
                    boolean isChecked = !trOn[0];
                    trOn[0] = isChecked;
                    renderSwitchButton(asrTranslateSwitch, isChecked);
                    mSettingsManager.setAsrAutoTranslateEnabled(isChecked);
                    if (isChecked) {
                        // 提示走的是哪条翻译链路。不能只看「有没有 API Key」：
                        // 引擎设为「仅本地」时即使配了 Key 也走本地，而本地又要求
                        // MLKit 组件已下载——此前一律报「（本地模型）」，组件缺失时
                        // 用户以为能用，实际翻译会静默失败。
                        String target = mSettingsManager.getAiTargetLang();
                        String engine = mSettingsManager.getTranslateEngine();
                        boolean localOk = com.orange.playerlibrary.ocr.OcrAvailabilityChecker
                                .isMlKitTranslateAvailable();
                        String route;
                        if (PlayerSettingsManager.ENGINE_LOCAL.equals(engine)) {
                            route = localOk ? "（本地模型）" : "（本地组件未下载，请先在 AI 设置中预装）";
                        } else if (isRemoteTranslationEnabled()) {
                            route = "（在线大模型）";
                        } else {
                            route = localOk ? "（本地模型）" : "（未配 API Key 且本地组件未下载）";
                        }
                        showToast("识别后自动翻译为「" + (target == null ? "" : target) + "」" + route);
                    }
                });
            }
            if (btnAsrGenerate != null) {
                boolean sherpaOk = com.orange.playerlibrary.speech.SherpaAvailabilityChecker
                        .isSherpaAvailable();
                boolean modelReady = com.orange.playerlibrary.speech.AsrSubtitleGenerator
                        .isModelReady(mContext);
                if (!sherpaOk) {
                    boolean asrSupported = com.orange.playerlibrary.tool.NativeLibManager
                            .isSupported(com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_ASR);
                    if (asrSupported) {
                        // 引擎类在，但 so 未下载（组件不再随 APK 分发）
                        long asrSize = com.orange.playerlibrary.tool.NativeLibManager
                                .getBundle(com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_ASR).size();
                        if (asrStatus != null) {
                            asrStatus.setText("语音识别组件未下载（约 "
                                    + com.orange.playerlibrary.tool.NativeLibManager.formatSize(asrSize)
                                    + "，支持中/英/日/韩）");
                            asrStatus.setTextColor(0xFFFF8F3F);
                        }
                        ((android.widget.TextView) btnAsrGenerate).setText("去下载组件");
                        btnAsrGenerate.setOnClickListener(v -> {
                            dialog.dismiss();
                            showNativeLibsDialog();
                        });
                    } else {
                        // 宿主没引 sherpa 依赖：App 内无解，但 SDK 使用者需要知道
                        // 该加什么 gradle 依赖——给出与 OCR 侧对等的安装说明，
                        // 而不是一句无从下手的 toast
                        if (asrStatus != null) {
                            asrStatus.setText("本版本未集成语音识别模块");
                            asrStatus.setTextColor(0xFFFF6B6B);
                        }
                        ((android.widget.TextView) btnAsrGenerate).setText("查看安装说明");
                        btnAsrGenerate.setOnClickListener(v -> showAsrInstallGuide());
                    }
                } else if (sAsrModelDownloader != null && sAsrModelDownloader.isDownloading()) {
                    // 模型正在下载：面板被关掉再打开时会走到这里。
                    // 下载在后台继续（detachCallback 是有意设计），但旧回调已随对话框失效，
                    // 这里重新接管，否则用户看到的是「未下载 · 可下载」——进度凭空消失。
                    ((android.widget.TextView) btnAsrGenerate).setText("下载中…");
                    btnAsrGenerate.setEnabled(false);
                    attachAsrDownloadUi(btnAsrGenerate, asrStatus, dialog);
                } else if (!modelReady) {
                    if (asrStatus != null) {
                        asrStatus.setText("ASR 模型未下载（约 228MB，支持中/英/日/韩）");
                        asrStatus.setTextColor(0xFFFF8F3F);
                    }
                    ((android.widget.TextView) btnAsrGenerate).setText("下载模型");
                    btnAsrGenerate.setOnClickListener(v -> startAsrModelDownload(
                            btnAsrGenerate, asrStatus, () -> {
                                // 模型就绪后：关掉设置面板并按「生成字幕」流程继续
                                dialog.dismiss();
                                startAsrGenerateUi();
                            }));
                } else if (sIsAsrGenerating) {
                    if (asrStatus != null) {
                        asrStatus.setText("语音字幕生成中...");
                        asrStatus.setTextColor(0xFF4CAF50);
                    }
                    btnAsrGenerate.setEnabled(false);
                } else {
                    if (asrStatus != null) {
                        asrStatus.setText(mSettingsManager.isAsrLiveEnabled()
                                ? "边看边识别：播到哪识别到哪，字幕渐进出现"
                                : "完整下载后一次识别全部字幕");
                        asrStatus.setTextColor(0xFF4CAF50);
                    }
                    btnAsrGenerate.setOnClickListener(v -> {
                        dialog.dismiss();
                        startAsrGenerateUi();
                    });
                }
            }

        } catch (Exception e) {
        }
    }
    
    /**
     * 显示 AI 翻译设置对话框（API Key / 地址 / 模型 / 目标语言）
     */
    private void showAiSettingsDialog() {
        View dialogView = View.inflate(mActivity, R.layout.dialog_ai_settings, null);

        final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                DialogUtils.DialogPosition.RIGHT, null, null);

        android.widget.EditText etKey = dialogView.findViewById(R.id.et_ai_api_key);
        android.widget.EditText etBase = dialogView.findViewById(R.id.et_ai_base_url);
        android.widget.EditText etModel = dialogView.findViewById(R.id.et_ai_model);
        android.widget.Spinner spinnerTarget =
                dialogView.findViewById(R.id.spinner_ai_target_lang);
        android.widget.Spinner spinnerSource =
                dialogView.findViewById(R.id.spinner_ai_source_lang);
        android.widget.TextView tvPreinstallStatus =
                dialogView.findViewById(R.id.tv_ai_preinstall_status);
        View btnPreinstall = dialogView.findViewById(R.id.btn_ai_preinstall_model);

        final java.util.List<String> langs = new java.util.ArrayList<>(java.util.Arrays.asList(
                com.orange.playerlibrary.ai.ProgressiveTranslator.TARGET_LANGUAGES));
        String savedTarget = mSettingsManager.getAiTargetLang();
        String savedTargetTrimmed = savedTarget == null ? "" : savedTarget.trim();
        // 旧版本手输过的语言可能不在列表内：保留为额外项，避免保存时被静默改写
        if (!savedTargetTrimmed.isEmpty() && !langs.contains(savedTargetTrimmed)) {
            langs.add(savedTargetTrimmed);
        }
        String savedSource = mSettingsManager.getAiSourceLang();
        final String savedSourceTrimmed = savedSource == null ? "" : savedSource.trim();

        if (etKey != null) {
            // 明文显示，且不设任何密码标记：OPPO 等机型只要字段被判定为
            // 「密码类型」（inputType=textPassword 或 PasswordTransformationMethod）
            // 就强制切到系统安全键盘，第三方输入法与粘贴全部不可用
            etKey.setText(mSettingsManager.getAiApiKey());
        }
        if (etBase != null) etBase.setText(mSettingsManager.getAiBaseUrl());
        if (etModel != null) etModel.setText(mSettingsManager.getAiModel());

        // 翻译引擎三选：自动 / 仅本地 / 仅远程（下标与 ENGINE_VALUES 对齐）
        android.widget.Spinner spinnerEngine = dialogView.findViewById(R.id.spinner_ai_engine);
        android.widget.TextView tvEngineHint = dialogView.findViewById(R.id.tv_ai_engine_hint);
        if (spinnerEngine != null) {
            android.widget.ArrayAdapter<String> engineAdapter = new android.widget.ArrayAdapter<>(
                    mActivity, R.layout.spinner_item,
                    new java.util.ArrayList<>(java.util.Arrays.asList(ENGINE_LABELS)));
            engineAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerEngine.setAdapter(engineAdapter);
            spinnerEngine.setSelection(engineIndex(mSettingsManager.getTranslateEngine()));
            final Runnable updateEngineHint = () -> {
                if (tvEngineHint == null) {
                    return;
                }
                String engine = ENGINE_VALUES[engineIndex(engineValue(spinnerEngine))];
                boolean hasKey = mSettingsManager.isAiConfigured();
                if (PlayerSettingsManager.ENGINE_LOCAL.equals(engine)) {
                    tvEngineHint.setText("只用本地 MLKit 模型：离线可译，不消耗 API 额度"
                            + (hasKey ? "（已填的 Key 将被忽略）" : ""));
                } else if (PlayerSettingsManager.ENGINE_REMOTE.equals(engine)) {
                    tvEngineHint.setText(hasKey
                            ? "只用在线大模型：效果最好，失败不会退回本地"
                            : "只用在线大模型：尚未填 API Key，翻译将无法进行");
                } else {
                    tvEngineHint.setText(hasKey
                            ? "优先在线大模型，不可用时自动退回本地模型"
                            : "尚未填 API Key：当前实际走本地模型");
                }
            };
            spinnerEngine.setOnItemSelectedListener(
                    new android.widget.AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(android.widget.AdapterView<?> parent,
                                                   android.view.View view, int position, long id) {
                            updateEngineHint.run();
                        }

                        @Override
                        public void onNothingSelected(android.widget.AdapterView<?> parent) {
                        }
                    });
            updateEngineHint.run();
        }

        // 语言下拉：显示名带「已装/未装」标注，选中值取纯语言名（下标与 langs 对齐）
        final Runnable[] refreshLangs = new Runnable[1];
        refreshLangs[0] = () -> {
            java.util.Set<String> installed =
                    com.orange.playerlibrary.ocr.MlKitTranslationEngine
                            .getInstalledLanguageCodes(mContext);
            java.util.List<String> display = new java.util.ArrayList<>(langs.size());
            for (String name : langs) {
                String code = com.orange.playerlibrary.ai.ProgressiveTranslator
                        .mapToMlKitCode(name);
                String mark;
                if (code == null) {
                    mark = "（无本地模型）";
                } else {
                    mark = installed.contains(code) ? "（已装）" : "（未装）";
                }
                display.add(name + mark);
            }
            bindLangSpinner(spinnerSource, display,
                    firstNonEmpty(selectedLangName(spinnerSource, langs), savedSource));
            bindLangSpinner(spinnerTarget, display,
                    firstNonEmpty(selectedLangName(spinnerTarget, langs), savedTargetTrimmed));
        };
        refreshLangs[0].run();

        // 预装本地翻译模型（MLKit）：模型按「语言↔英语」存储、翻译经英语中转，
        // 与 OCR 翻译共用同一份——预装一次两条链路都免去首次使用的下载等待
        if (btnPreinstall != null) {
            final Runnable updateStatus = () -> {
                if (tvPreinstallStatus == null) {
                    return;
                }
                String src = selectedLangName(spinnerSource, langs);
                String tgt = selectedLangName(spinnerTarget, langs);
                if (src.isEmpty() || tgt.isEmpty()) {
                    tvPreinstallStatus.setText("");
                } else if (!com.orange.playerlibrary.ocr.OcrAvailabilityChecker
                        .isMlKitTranslateAvailable()) {
                    // 组件没下时把话说在这里：否则状态行只显示「当前选择：X ↔ Y」，
                    // 用户点下去才吃到一句 toast，且当时没有任何出路
                    boolean classPresent = com.orange.playerlibrary.tool.NativeLibManager
                            .isSupported(
                                    com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_TRANSLATE);
                    tvPreinstallStatus.setText(classPresent
                            ? "翻译组件未下载，点上方按钮后会引导到「扩展包管理」"
                            : "本版本未集成翻译模块，本地翻译不可用");
                } else {
                    tvPreinstallStatus.setText("当前选择：" + src + " ↔ " + tgt);
                }
            };
            updateStatus.run();
            android.widget.AdapterView.OnItemSelectedListener refresh =
                    new android.widget.AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(android.widget.AdapterView<?> parent,
                                                   android.view.View view, int position, long id) {
                            updateStatus.run();
                        }

                        @Override
                        public void onNothingSelected(android.widget.AdapterView<?> parent) {
                        }
                    };
            if (spinnerSource != null) {
                spinnerSource.setOnItemSelectedListener(refresh);
            }
            if (spinnerTarget != null) {
                spinnerTarget.setOnItemSelectedListener(refresh);
            }
            btnPreinstall.setOnClickListener(v -> preinstallLocalTranslationModel(
                    dialog, spinnerSource, spinnerTarget, tvPreinstallStatus, langs,
                    () -> refreshLangs[0].run()));
        }

        // 获取模型：调 OpenAI 兼容 GET /models，下拉选择后填入模型输入框
        View btnFetchModels = dialogView.findViewById(R.id.btn_ai_fetch_models);
        if (btnFetchModels != null) {
            btnFetchModels.setOnClickListener(v -> fetchAiModelList(etBase, etKey, etModel));
        }

        View btnCancel = dialogView.findViewById(R.id.btn_ai_settings_cancel);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        View btnSave = dialogView.findViewById(R.id.btn_ai_settings_save);
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                if (etKey != null) mSettingsManager.setAiApiKey(etKey.getText().toString());
                if (etBase != null) mSettingsManager.setAiBaseUrl(etBase.getText().toString());
                if (etModel != null) mSettingsManager.setAiModel(etModel.getText().toString());
                if (spinnerEngine != null) {
                    mSettingsManager.setTranslateEngine(engineValue(spinnerEngine));
                }
                String target = selectedLangName(spinnerTarget, langs);
                String previousTarget = mSettingsManager.getAiTargetLang();
                if (!target.isEmpty()) {
                    mSettingsManager.setAiTargetLang(target);
                }
                String source = selectedLangName(spinnerSource, langs);
                if (!source.isEmpty()) {
                    mSettingsManager.setAiSourceLang(source);
                }
                dialog.dismiss();
                // 目标语言变了：让在途的识别会话改用新语言（重译已识别字幕），
                // 否则要等下次重开会话才生效
                boolean langChanged = !target.isEmpty()
                        && !target.equals(previousTarget == null ? "" : previousTarget.trim());
                if (langChanged) {
                    retargetProgressiveTranslation(target);
                    // 没有在途识别会话时（外挂字幕 / ASR 已整片识别完），上面那句
                    // 直接返回，屏幕上的仍是旧语言译文。此处显式用原文重译。
                    maybeRetranslateLoadedSubtitles(target);
                }
                // 先明确「已保存」，再分别说明两条翻译链路的可用性——
                // 旧文案只提 AI 未配置，用户会误以为设置没保存成功
                String engine = mSettingsManager.getTranslateEngine();
                String langText = mSettingsManager.getAiTargetLang();
                if (PlayerSettingsManager.ENGINE_LOCAL.equals(engine)) {
                    showToast("已保存：翻译走本地模型（离线），目标语言「" + langText + "」");
                } else if (!mSettingsManager.isAiConfigured()) {
                    showToast("已保存：目标语言「" + langText
                            + "」；未填 API Key，翻译走本地模型");
                } else if (PlayerSettingsManager.ENGINE_REMOTE.equals(engine)) {
                    showToast("已保存：翻译走在线大模型（" + mSettingsManager.getAiModel()
                            + "），目标语言「" + langText + "」");
                } else {
                    showToast("已保存：AI 翻译可用（" + mSettingsManager.getAiModel() + "）；"
                            + "识别后自动翻译「" + langText + "」");
                }
            });
        }
    }

    /**
     * 翻译引擎下拉项。文案与 {@link #ENGINE_VALUES} 一一对应，
     * 由 {@link #engineIndex} / {@link #engineValue} 做双向映射。
     */
    private static final String[] ENGINE_LABELS = {
            "自动（优先在线，失败退回本地）", "仅本地模型（离线，不耗额度）", "仅在线大模型"};

    private static final String[] ENGINE_VALUES = {
            PlayerSettingsManager.ENGINE_AUTO,
            PlayerSettingsManager.ENGINE_LOCAL,
            PlayerSettingsManager.ENGINE_REMOTE};

    private static int engineIndex(String engine) {
        for (int i = 0; i < ENGINE_VALUES.length; i++) {
            if (ENGINE_VALUES[i].equals(engine)) {
                return i;
            }
        }
        return 0;
    }

    private static String engineValue(android.widget.Spinner spinner) {
        if (spinner == null) {
            return PlayerSettingsManager.ENGINE_AUTO;
        }
        int pos = spinner.getSelectedItemPosition();
        return pos >= 0 && pos < ENGINE_VALUES.length
                ? ENGINE_VALUES[pos] : PlayerSettingsManager.ENGINE_AUTO;
    }

    /**
     * 从下拉框取当前选中的纯语言名（显示名带「已装/未装」标注，须按位取原值）。
     * 返回 "" 表示未选中。
     */
    private static String selectedLangName(android.widget.Spinner spinner,
                                           java.util.List<String> langs) {
        if (spinner == null) {
            return "";
        }
        int pos = spinner.getSelectedItemPosition();
        return pos >= 0 && pos < langs.size() ? langs.get(pos) : "";
    }

    /** 取第一个非空值（下拉首次填充时用已保存值兜底） */
    private static String firstNonEmpty(String first, String fallback) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        return fallback == null ? "" : fallback;
    }

    /** 填充语言下拉（显示名与 langs 同序，回调里按位取纯语言名） */
    private void bindLangSpinner(android.widget.Spinner spinner,
                                 java.util.List<String> display, String selectName) {
        if (spinner == null) {
            return;
        }
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                mActivity, R.layout.spinner_item, display);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);
        if (selectName != null && !selectName.isEmpty()) {
            int idx = -1;
            for (int i = 0; i < display.size(); i++) {
                if (display.get(i).startsWith(selectName + "（")) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                spinner.setSelection(idx);
            }
        }
    }

    /**
     * 拉取 AI 服务的可用模型列表（OpenAI 兼容 GET /models）供选择。
     * 用输入框里的地址与 Key（可能尚未保存），成功后就地填入模型输入框。
     */
    private void fetchAiModelList(final android.widget.EditText etBase,
                                  final android.widget.EditText etKey,
                                  final android.widget.EditText etModel) {
        final String base = etBase != null ? etBase.getText().toString().trim() : "";
        final String key = etKey != null ? etKey.getText().toString().trim() : "";
        if (key.isEmpty()) {
            showToast("请先填写 API Key");
            return;
        }
        if (base.isEmpty()) {
            showToast("请先填写 API 地址");
            return;
        }
        final com.orange.playerlibrary.ai.TranslatorSettings settings =
                com.orange.playerlibrary.ai.TranslatorSettings.builder()
                        .baseUrl(base)
                        .apiKey(key)
                        .model(etModel != null ? etModel.getText().toString() : "")
                        .build();
        showToast("正在获取模型列表…");
        new Thread(() -> {
            try {
                java.util.List<String> models =
                        new com.orange.playerlibrary.ai.OpenAiCompatibleProvider()
                                .listModels(settings);
                mActivity.runOnUiThread(() -> showModelPicker(models, etModel));
            } catch (com.orange.playerlibrary.ai.AiException e) {
                mActivity.runOnUiThread(() -> showToast("获取模型失败: " + e.getMessage()));
            } catch (Throwable t) {
                mActivity.runOnUiThread(() -> showToast("获取模型失败: " + t.getMessage()));
            }
        }, "ai-list-models").start();
    }

    /** 模型列表单选弹窗：选中即填入模型输入框 */
    private void showModelPicker(final java.util.List<String> models,
                                 final android.widget.EditText etModel) {
        if (models == null || models.isEmpty()) {
            showToast("该服务未返回可用模型");
            return;
        }
        String current = etModel != null ? etModel.getText().toString().trim() : "";
        int checked = models.indexOf(current);
        final String[] items = models.toArray(new String[0]);
        new AlertDialog.Builder(mActivity)
                .setTitle("选择模型（" + items.length + " 个）")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    if (etModel != null) {
                        etModel.setText(items[which]);
                    }
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 预装本地翻译模型（MLKit）：按当前选定的语言对触发下载。
     *
     * 实测 MLKit 按「语言↔英语」存模型（no_backup/com.google.mlkit.translate.models/
     * en_&lt;语言&gt;，每份 40-60MB），翻译时经英语中转——因此装过 en_zh + en_ja 后
     * 日→中直接可用、不产生新下载。downloadModelIfNeeded 只取缺失的语言模型，
     * 已装过的语言立即完成、不发网络请求；OCR 翻译与语音识别渐进翻译共用同一份。
     */
    private void preinstallLocalTranslationModel(final AlertDialog hostDialog,
                                                 final android.widget.Spinner spinnerSource,
                                                 final android.widget.Spinner spinnerTarget,
                                                 final android.widget.TextView statusView,
                                                 final java.util.List<String> langs,
                                                 final Runnable onFinished) {
        final String srcName = selectedLangName(spinnerSource, langs);
        final String tgtName = selectedLangName(spinnerTarget, langs);
        if (srcName.isEmpty() || tgtName.isEmpty()) {
            return;
        }
        final String srcCode =
                com.orange.playerlibrary.ai.ProgressiveTranslator.mapToMlKitCode(srcName);
        final String tgtCode =
                com.orange.playerlibrary.ai.ProgressiveTranslator.mapToMlKitCode(tgtName);
        if (srcCode == null || tgtCode == null) {
            showToast("该语言无本地模型，请改用 AI 翻译");
            return;
        }
        if (srcCode.equals(tgtCode)) {
            showToast("源语言与目标语言相同，无需预装");
            return;
        }
        if (!com.orange.playerlibrary.ocr.OcrAvailabilityChecker.isMlKitTranslateAvailable()) {
            // 「未集成」其实是两种成因，给的操作完全不同，此前合并成一句
            // 「未集成 MLKit 翻译模块」——用户既不知道差什么、也没有出路。
            // 与字幕对话框的 OCR 按钮（见 showSubtitleDialog）同一套判定：
            //   isSupported=false → 宿主没引依赖，只能改 gradle（面向 SDK 使用者）
            //   isSupported=true  → 依赖在但 so 没下（终端用户去扩展包管理下载即可）
            boolean classPresent = com.orange.playerlibrary.tool.NativeLibManager
                    .isSupported(com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_TRANSLATE);
            if (classPresent) {
                long size = com.orange.playerlibrary.tool.NativeLibManager
                        .getBundle(com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_TRANSLATE)
                        .size();
                showToast("翻译组件未下载（"
                        + com.orange.playerlibrary.tool.NativeLibManager.formatSize(size)
                        + "），请先下载");
                // 先关掉 AI 设置面板再跳：DialogUtils 的毛玻璃没有引用计数，
                // 两个面板叠加时先关的那个会把模糊一并清掉（与 bindNativeLibsEntry
                // 同一处理）
                if (hostDialog != null) {
                    hostDialog.dismiss();
                }
                showNativeLibsDialog();
            } else {
                showToast("本版本未集成翻译模块，本地翻译不可用");
            }
            return;
        }

        final com.orange.playerlibrary.ocr.MlKitTranslationEngine engine =
                new com.orange.playerlibrary.ocr.MlKitTranslationEngine();
        engine.init(mContext, srcCode, tgtCode);
        if (!engine.isInitialized()) {
            // 带上具体原因：笼统的「初始化失败」让用户与排查者都无从下手
            String reason = engine.getLastError();
            Log.e(TAG, "本地翻译引擎初始化失败: " + reason);
            engine.release();
            showToast(reason == null ? "本地翻译引擎初始化失败"
                    : "本地翻译引擎初始化失败：" + reason);
            return;
        }

        final DownloadProgressDialog progressDialog = new DownloadProgressDialog(mActivity);
        progressDialog.showWithRealProgress("正在下载本地翻译模型", srcName + " ↔ " + tgtName);
        final Runnable[] stopPollingHolder = new Runnable[1];
        final boolean[] settled = {false};
        final Runnable stopPolling = () -> {
            if (stopPollingHolder[0] != null) {
                stopPollingHolder[0].run();
            }
        };
        // 收尾：下载真正失败、和「一直 0 字节」（设备缺 GMS MLKit 运行时）共用，
        // settled 保证只收尾一次
        final Consumer<String> finish = error -> mActivity.runOnUiThread(() -> {
            if (settled[0]) {
                return;
            }
            settled[0] = true;
            Log.w(TAG, "本地翻译模型预装失败: " + error);
            stopPolling.run();
            progressDialog.fail(error);
            showToast("本地翻译模型下载失败: " + error);
            if (statusView != null) {
                statusView.setText("预装失败：" + srcName + "、" + tgtName + " 可重试");
            }
            engine.release();
        });
        if (statusView != null) {
            statusView.setText("正在下载：" + srcName + " ↔ " + tgtName);
        }
        engine.downloadModel(new com.orange.playerlibrary.ocr.TranslationEngine.ModelDownloadCallback() {
            @Override
            public void onProgress(int progress) {
            }

            @Override
            public void onSuccess() {
                mActivity.runOnUiThread(() -> {
                    if (settled[0]) {
                        return;
                    }
                    settled[0] = true;
                    stopPolling.run();
                    progressDialog.complete();
                    showToast("本地翻译已就绪：" + srcName + " ↔ " + tgtName);
                    engine.release();
                    // 重新扫描已装语言，刷新下拉框的「已装/未装」标注
                    if (onFinished != null) {
                        onFinished.run();
                    }
                    if (statusView != null) {
                        statusView.setText("已就绪：" + srcName + " ↔ " + tgtName
                                + "（可再选其他语言预装）");
                    }
                });
            }

            @Override
            public void onError(String error) {
                finish.accept(error);
            }
        });
        stopPollingHolder[0] = startMlKitDownloadProgressPolling(
                progressDialog, srcCode, tgtCode,
                () -> finish.accept("下载未启动：系统下载服务可能被省电策略冻结，"
                        + "请允许「下载管理器」后台运行后重试"));
    }

    /** MLKit 下载进度轮询间隔（毫秒） */
    private static final long MLKIT_PROGRESS_POLL_MS = 500;

    /** 进度上限：分母是估算值，留 5% 余量，完成时才由 complete() 跳到 100% */
    private static final int MLKIT_PROGRESS_CAP = 95;

    /** 连续多久仍是 0 字节就判定下载没起来（毫秒）。只判「一个字节都没下」，不设总时长上限 */
    private static final long MLKIT_NO_PROGRESS_TIMEOUT_MS = 20 * 1000L;

    /**
     * 让进度对话框显示 MLKit 模型下载的**真实字节进度**。
     *
     * MLKit 的 downloadModelIfNeeded 只有成功/失败回调、不报进度，但字节数可观测：
     * 模型目录（no_backup/com.google.mlkit.translate.models/）随每份模型下载完成而增长，
     * 下载中的那份则计入系统 DownloadManager 的在途字节。两者相加即基于真实下载量的进度。
     *
     * 分母按「本次尚缺的语言数 × 单语言典型体积」估算（MLKit 按语言↔英语存储、
     * 经英语中转，故已装的语言不会再下），因此进度会收敛到
     * {@link #MLKIT_PROGRESS_CAP}%，由调用方在成功回调里 complete() 补到 100%。
     *
     * 部分设备（实测 OnePlus PJA110 / Android 16）ColorOS 会冻结系统下载服务
     * com.android.providers.downloads，MLKit 交给它的任务不执行、一个字节都不下。
     * 这里连续 {@link #MLKIT_NO_PROGRESS_TIMEOUT_MS} 仍是 0 字节就回调 onStalled，
     * 避免进度条永久停在 0%。注意只判「零字节」，一旦开始下载就交给
     * {@link com.orange.playerlibrary.ocr.MlKitTranslationEngine} 的停滞检测，
     * 不在这里设总时长上限（慢速下载可能持续数分钟）。
     *
     * @return 停止轮询的句柄；调用方在 onSuccess/onError 里必须先调用它，
     *         否则 complete() 的 100% 会被后续轮询结果覆盖
     */
    private Runnable startMlKitDownloadProgressPolling(
            final DownloadProgressDialog dialog, final String srcCode, final String tgtCode,
            final Runnable onStalled) {
        final java.util.Set<String> installed =
                com.orange.playerlibrary.ocr.MlKitTranslationEngine
                        .getInstalledLanguageCodes(mContext);
        int missing = 0;
        if (!"en".equals(srcCode) && !installed.contains(srcCode)) {
            missing++;
        }
        if (!"en".equals(tgtCode) && !installed.contains(tgtCode)) {
            missing++;
        }
        final long estimate = Math.max(1, missing)
                * com.orange.playerlibrary.ocr.MlKitTranslationEngine.TYPICAL_MODEL_BYTES;
        final long baseline =
                com.orange.playerlibrary.ocr.MlKitTranslationEngine.getModelsDirSize(mContext);
        final boolean[] stopped = {false};
        final Runnable[] tick = new Runnable[1];
        final long startAt = android.os.SystemClock.elapsedRealtime();
        tick[0] = () -> {
            if (stopped[0] || !dialog.isShowing()) {
                return;
            }
            // 模型目录增长 + DownloadManager 在途字节：MLKit 下载完成前字节只在
            // 系统下载缓存里，只算目录会长时间停在 0
            long grown = com.orange.playerlibrary.ocr.MlKitTranslationEngine
                    .getModelsDirSize(mContext) - baseline
                    + com.orange.playerlibrary.ocr.MlKitTranslationEngine
                            .getInFlightDownloadBytes(mContext);
            if (grown <= 0
                    && android.os.SystemClock.elapsedRealtime() - startAt
                            >= MLKIT_NO_PROGRESS_TIMEOUT_MS) {
                stopped[0] = true;
                if (onStalled != null) {
                    onStalled.run();
                }
                return;
            }
            int pct = (int) Math.min(MLKIT_PROGRESS_CAP, Math.max(0, grown * 100 / estimate));
            dialog.setProgress(pct, "已下载 " + (grown / (1024 * 1024)) + "MB");
            mMainHandler.postDelayed(tick[0], MLKIT_PROGRESS_POLL_MS);
        };
        mMainHandler.postDelayed(tick[0], MLKIT_PROGRESS_POLL_MS);
        return () -> stopped[0] = true;
    }

    private volatile boolean mIsAiTranslating = false;

    /**
     * 同步控制器上字幕开关图标的显示状态（翻译完成后置为高亮）。
     * 全屏控制条是独立实例且无点击 View 可用时可能不同步，
     * 仅影响图标 alpha 提示，不影响字幕实际渲染。
     */
    private void syncSubtitleSwitchUi() {
        try {
            if (mVodControlView != null) {
                mVodControlView.updateSubtitleToggleState(true);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isAiTranslating() {
        return mIsAiTranslating;
    }

    /**
     * 开始 AI 批量翻译当前已加载字幕。
     * 后台线程执行（网络 + LLM 分批），完成后主线程回写 SubtitleManager 并刷新显示。
     */
    private void startAiTranslate() {
        final com.orange.playerlibrary.subtitle.SubtitleManager subtitleManager =
                mController != null ? mController.getSubtitleManager() : null;
        if (subtitleManager == null || subtitleManager.getSubtitleCount() == 0) {
            showToast("没有已加载的字幕");
            return;
        }
        if (!isRemoteTranslationEnabled()) {
            showToast(PlayerSettingsManager.ENGINE_LOCAL.equals(
                    mSettingsManager.getTranslateEngine())
                    ? "批量翻译字幕需在线大模型，请把「翻译引擎」改为在线或自动"
                    : "请先在 AI 设置中填写 API Key");
            showAiSettingsDialog();
            return;
        }
        if (mIsAiTranslating) {
            showToast("AI 翻译已在运行");
            return;
        }

        // 快照字幕与视频标识（翻译过程中用户可能切换视频/字幕）
        final java.util.List<com.orange.playerlibrary.subtitle.SubtitleEntry> snapshot =
                subtitleManager.getSubtitles();
        final String subtitlePath = subtitleManager.getCurrentSubtitlePath();
        final String videoUrl = mVideoView.getUrl();
        final String targetLang = mSettingsManager.getAiTargetLang();

        // 目标语言占位为空时翻译结果不显示（防御）
        if (targetLang == null || targetLang.trim().isEmpty()) {
            showToast("请在 AI 设置中填写目标语言");
            return;
        }

        mIsAiTranslating = true;
        final DownloadProgressDialog progress = new DownloadProgressDialog(mActivity);
        // 返回键可取消：网络不通时每批要等满读超时再重试，整片可能数分钟没进度；
        // 不可取消的话用户只能杀进程。取消仅关弹窗，翻译线程仍会跑完并回写结果。
        progress.setCancelable(true, null);
        progress.showWithRealProgress("AI 翻译中",
                "共 " + snapshot.size() + " 条字幕 · 网络异常会自动重试");

        new Thread(() -> {
            try {
                final com.orange.playerlibrary.ai.TranslatorSettings settings =
                        com.orange.playerlibrary.ai.TranslatorSettings.builder()
                                .baseUrl(mSettingsManager.getAiBaseUrl())
                                .apiKey(mSettingsManager.getAiApiKey())
                                .model(mSettingsManager.getAiModel())
                                .targetLanguage(targetLang)
                                .build();

                // SubtitleEntry -> SubtitleLine
                // 取原文而不是 getText()：上一次翻译已把译文覆盖进 text，
                // 换语言重译时必须回到原文，否则会把「中→英」的英文再拿去译
                java.util.List<com.orange.playerlibrary.ai.SubtitleLine> lines =
                        new java.util.ArrayList<>();
                for (int i = 0; i < snapshot.size(); i++) {
                    String text = snapshot.get(i).getOriginalText();
                    lines.add(new com.orange.playerlibrary.ai.SubtitleLine(i,
                            text == null ? "" : text.trim()));
                }

                final int total = lines.size();
                com.orange.playerlibrary.ai.AiTranslationEngine engine =
                        new com.orange.playerlibrary.ai.AiTranslationEngine(
                                new com.orange.playerlibrary.ai.OpenAiCompatibleProvider());

                // 缓存目录：应用缓存目录下按视频/字幕区分
                java.io.File cacheRoot = new java.io.File(
                        mActivity.getCacheDir(), "ai_translation");
                String cacheKey = "sub=" + (subtitlePath == null ? "" : subtitlePath)
                        + "|url=" + (videoUrl == null ? "" : videoUrl)
                        + "|to=" + targetLang;

                com.orange.playerlibrary.ai.AiTranslationEngine.Result result =
                        engine.translateAll(cacheRoot, cacheKey, lines, settings,
                                new com.orange.playerlibrary.ai.AiTranslationEngine.ProgressListener() {
                                    @Override
                                    public void onProgress(int done, int totalCount) {
                                        int pct = totalCount > 0
                                                ? (int) (done * 100L / totalCount) : 100;
                                        mActivity.runOnUiThread(() -> {
                                            if (progress.isShowing()) {
                                                progress.setProgress(pct, "已完成 "
                                                        + done + "/" + totalCount + " 条");
                                            }
                                        });
                                    }

                                    @Override
                                    public void onBatchRetry(int attempt, int maxAttempts,
                                                             String message) {
                                        mActivity.runOnUiThread(() -> {
                                            if (progress.isShowing()) {
                                                progress.setProgress(progress.getProgress(),
                                                        "第 " + attempt + "/" + maxAttempts
                                                                + " 次失败，重试中…");
                                            }
                                        });
                                    }
                                });

                // 主线程回写：校验字幕未被替换（路径/数量一致才回写）
                mActivity.runOnUiThread(() -> {
                    progress.complete("翻译完成");
                    if (subtitleManager.getSubtitleCount() == snapshot.size()
                            && (subtitlePath == null
                            || subtitlePath.equals(subtitleManager.getCurrentSubtitlePath()))) {
                        String[] translated = new String[result.lines.size()];
                        for (com.orange.playerlibrary.ai.SubtitleLine l : result.lines) {
                            if (l.hasTranslation()) {
                                translated[l.getIdx()] = l.getTranslated();
                            }
                        }
                        int written = subtitleManager.applyAiTranslation(translated);
                        if (written > 0) {
                            // 翻译完成必须强制开启字幕显示：普通字幕条目（非 OCR/语音的
                            // showText 直显路径）依赖 mEnabled + start() 循环才会渲染。
                            // 用户可能没开"显示字幕"开关或循环未运行——此处统一拉起。
                            subtitleManager.show();               // mEnabled = true
                            if (mController != null) {
                                mController.startSubtitle();      // 确保 update 循环在跑
                            }
                            mSettingsManager.setSubtitleEnabled(true);
                            syncSubtitleSwitchUi();
                        }
                        if (result.untranslated == 0) {
                            showToast("AI 翻译完成：" + written + "/" + total + " 条已替换");
                        } else {
                            showToast("AI 翻译部分完成：" + written + "/" + total
                                    + " 条成功，" + result.untranslated + " 条失败可重试");
                        }
                    } else {
                        showToast("字幕已切换，翻译结果未应用（已缓存，重新加载后可重试）");
                    }
                    mIsAiTranslating = false;
                });
            } catch (Throwable t) {
                Log.e(TAG, "AI 翻译失败", t);
                mActivity.runOnUiThread(() -> {
                    progress.fail("翻译失败", t.getMessage());
                    showToast("AI 翻译失败: " + t.getMessage());
                    mIsAiTranslating = false;
                });
            }
        }, "ai-subtitle-translate").start();
    }

    // ===== 边识别边翻译（ASR → 翻译）=====

    /** 渐进翻译会话（进程级：与渐进 ASR 同生命周期） */
    private static com.orange.playerlibrary.ai.ProgressiveTranslator sProgressiveTranslator;

    /**
     * 按设置启用识别后翻译：识别出的字幕翻成 AI 设置中的目标语言。
     * 未配置 API Key 或选了「仅本地」时传 null 配置 → 调度器走本地 MLKit 兜底。
     *
     * 渐进识别（逐块 submit）与完整识别（整片 submit(0, ...)）共用本入口。
     *
     * @param videoKey 视频标识（翻译缓存键，两条链路互相复用译文）
     * @return true=已配置好翻译会话，调用方可直接 submit
     */
    private boolean startAsrTranslationIfEnabled(String videoKey) {
        if (!mSettingsManager.isAsrAutoTranslateEnabled()) {
            stopProgressiveTranslation();
            return false;
        }
        final String targetLang = mSettingsManager.getAiTargetLang();
        if (targetLang == null || targetLang.trim().isEmpty()) {
            showToast("已开启识别后翻译，但未设置目标语言（AI 设置）");
            return false;
        }
        if (sProgressiveTranslator == null) {
            sProgressiveTranslator = new com.orange.playerlibrary.ai.ProgressiveTranslator(mContext);
        }
        com.orange.playerlibrary.ai.TranslatorSettings aiSettings = buildAiSettings(targetLang);
        if (aiSettings == null) {
            Log.d(TAG, "未配置 AI Key，识别后翻译使用本地兜底");
        }
        // 初始源语言取 AI 设置里的值（ASR 检测到语种后会被覆盖）：
        // 传 "auto" 会让本地兜底在引擎未回报语种时因源语言未知而整片不译。
        // 必须转成语言码——设置里存的是「英语」这类显示名，MLKit 只认 "en"
        String sourceCode = com.orange.playerlibrary.ai.ProgressiveTranslator
                .mapToMlKitCode(mSettingsManager.getAiSourceLang());
        sProgressiveTranslator.configure(videoKey, sourceCode,
                targetLang, aiSettings,
                new com.orange.playerlibrary.ai.ProgressiveTranslator.Callback() {
                    @Override
                    public void onTranslated(final int startIdx, final String[] translated) {
                        mActivity.runOnUiThread(() -> {
                            if (mController == null || mController.getSubtitleManager() == null) {
                                return;
                            }
                            mController.getSubtitleManager()
                                    .applyAiTranslationFrom(startIdx, translated);
                        });
                    }

                    @Override
                    public void onStatus(String message) {
                        Log.d(TAG, "渐进翻译: " + message);
                    }
                });
        return true;
    }

    private void stopProgressiveTranslation() {
        if (sProgressiveTranslator != null) {
            sProgressiveTranslator.reset();
        }
    }

    /**
     * AI 接入配置；返回 null 表示调用方应走本地 MLKit。
     *
     * 是否使用远程由「翻译引擎」设置决定，而非「有没有填 Key」：填了 Key 也
     * 可能被用户显式选成「仅本地」（离线/不耗 token），此时必须返回 null，
     * 否则设置形同虚设。
     */
    private com.orange.playerlibrary.ai.TranslatorSettings buildAiSettings(String targetLang) {
        if (!isRemoteTranslationEnabled()) {
            return null;
        }
        return com.orange.playerlibrary.ai.TranslatorSettings.builder()
                .baseUrl(mSettingsManager.getAiBaseUrl())
                .apiKey(mSettingsManager.getAiApiKey())
                .model(mSettingsManager.getAiModel())
                .targetLanguage(targetLang)
                .build();
    }

    /** 当前设置是否允许使用远程 LLM 翻译（「仅本地」恒为 false） */
    private boolean isRemoteTranslationEnabled() {
        if (PlayerSettingsManager.ENGINE_LOCAL.equals(mSettingsManager.getTranslateEngine())) {
            return false;
        }
        return mSettingsManager.isAiConfigured();
    }

    /**
     * 目标语言变更后让在途会话改用新语言：重配翻译并重译已识别的字幕。
     * 识别会话本身不受影响（源语言始终以 ASR 检测为准）。
     */
    private void retargetProgressiveTranslation(String newTargetLang) {
        if (sProgressiveTranslator == null
                || sProgressiveAsr == null || !sProgressiveAsr.isRunning()) {
            return;
        }
        sProgressiveTranslator.retarget(
                mVideoView != null ? mVideoView.getUrl() : null,
                newTargetLang, buildAiSettings(newTargetLang));
    }

    /**
     * 目标语言变更后，对屏幕上已加载的字幕按新语言重译。
     *
     * 只在「没有在途渐进识别会话」时兜底：有会话时 {@link #retargetProgressiveTranslation}
     * 已经接管（它会重译已识别字幕），两条链路都跑会重复请求同一批文本。
     *
     * 仅在线引擎可批量重译——本地 MLKit 不提供整片批量翻译（与
     * {@link #startAiTranslate} 的门禁一致），此时不动屏幕内容，只提示原因，
     * 避免用户以为设置没生效。
     */
    private void maybeRetranslateLoadedSubtitles(String newTargetLang) {
        if (sProgressiveAsr != null && sProgressiveAsr.isRunning()) {
            return;
        }
        if (mIsAiTranslating) {
            showToast("AI 翻译进行中，新语言将在下次翻译时生效");
            return;
        }
        com.orange.playerlibrary.subtitle.SubtitleManager subtitleManager =
                mController != null ? mController.getSubtitleManager() : null;
        if (subtitleManager == null || subtitleManager.getSubtitleCount() == 0) {
            return;
        }
        if (!isRemoteTranslationEnabled()) {
            // 未配置在线 Key / 引擎选了「仅本地」：批量翻译不可用，明说而不是静默
            showToast("已切换目标语言「" + newTargetLang
                    + "」；批量重译需在线大模型，当前字幕保持原译文");
            return;
        }
        showToast("目标语言已切换为「" + newTargetLang + "」，正在重译当前字幕…");
        startAiTranslate();
    }

    // ===== AI 语音生成字幕（离线 ASR）=====

    /**
     * 完整识别进行中（进程级）：播放器有多个 VideoEventManager 实例
     * （controller / error view 各自注册状态监听），实例字段挡不住另一个实例
     * 同时发起第二次完整识别。
     */
    private static volatile boolean sIsAsrGenerating = false;

    /**
     * 字幕面板"AI 语音生成字幕"入口。
     * 无视频文件时弹系统文件选择器选视频。
     */
    private void startAsrGenerateUi() {
        if (sIsAsrGenerating || sProgressiveAsr != null) {
            showToast("语音字幕生成已在运行");
            return;
        }
        if (!com.orange.playerlibrary.speech.SherpaAvailabilityChecker.isSherpaAvailable()) {
            showToast("未安装 ASR 引擎模块");
            return;
        }
        if (mSettingsManager.isAsrLiveEnabled()) {
            // 渐进（边看边识别）：本地/已缓存 mp4 或 HLS 按播放进度逐块识别
            if (startProgressiveAsrForCurrentVideo()) {
                return;
            }
            final String url = mVideoView != null ? mVideoView.getUrl() : null;
            if (url != null && isM3u8Url(url)) {
                showToast("边看边识别暂不可用，改用完整版");
            }
            // 回退完整版（不 return，让功能可用）
        }
        // 优先解析当前播放视频源
        if (resolveCurrentVideoForAsr()) {
            return;
        }
        // 当前视频不可用（网络未缓存等）→ 弹文件选择器选本地视频
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/mp4", "video/mkv",
                    "video/webm", "video/3gpp", "video/x-matroska", "video/quicktime"});
            mActivity.startActivityForResult(intent, REQUEST_CODE_ASR_VIDEO);
        } catch (Exception e) {
            Log.e(TAG, "打开视频选择器失败", e);
            showToast("无法打开视频选择器");
        }
    }

    /**
     * 解析可随机 seek 的视频文件（渐进 ASR 用）：
     * 本地路径 / file:// → 文件；网络 mp4 已完整缓存 → 缓存文件；
     * m3u8/HLS 或未缓存网络视频 → null（不支持渐进）。
     */
    private java.io.File resolveLocalVideoFileForAsr() {
        try {
            String url = mVideoView != null ? mVideoView.getUrl() : null;
            if (url == null || url.isEmpty() || isM3u8Url(url)) {
                return null;
            }
            if (url.startsWith("file://")) {
                java.io.File f = new java.io.File(android.net.Uri.parse(url).getPath());
                return f.exists() ? f : null;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                java.io.File f = new java.io.File(url);
                return f.exists() ? f : null;
            }
            return getCachedVideoFile(url);
        } catch (Exception e) {
            Log.w(TAG, "解析渐进 ASR 视频源失败", e);
            return null;
        }
    }

    /**
     * 解析当前播放视频供 ASR 生成字幕。
     * 返回 true 表示已接管（开始 ASR 或提示）；false 表示当前视频不可用需选文件。
     * 规则：
     *  - 本地路径 / file:// → 直接用
     *  - 网络 mp4 → danikula 缓存完整则取缓存文件，否则提示
     *  - m3u8/HLS → M3U8Downloader 下载完整后 ASR
     */
    private boolean resolveCurrentVideoForAsr() {
        try {
            String url = mVideoView != null ? mVideoView.getUrl() : null;
            if (url == null || url.isEmpty()) {
                return false;
            }
            Log.d(TAG, "ASR 解析当前视频: " + url);

            // m3u8/HLS 走完整下载
            if (isM3u8Url(url)) {
                downloadHlsAndGenerate(url);
                return true;
            }
            // 本地文件 / 已缓存 mp4
            java.io.File local = resolveLocalVideoFileForAsr();
            if (local != null) {
                startAsrGenerate(local);
                return true;
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                showToast("网络视频需先完整缓存（完整播放一遍或下载）再生成字幕");
                return true;   // 已提示，不回落文件选择器
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "解析当前视频失败", e);
            return false;
        }
    }

    private boolean isM3u8Url(String url) {
        String lower = url.toLowerCase();
        return lower.contains(".m3u8") || lower.contains("m3u8");
    }

    /**
     * 取网络 mp4 的 danikula 缓存文件（完整缓存才有）。
     * danikula 落盘名为 &lt;md5(url)&gt; + 扩展名（实测为 .mp4），
     * 未下完的为 &lt;md5&gt;.mp4.download，需排除。
     */
    private java.io.File getCachedVideoFile(String url) {
        try {
            String cacheDirPath = com.orange.playerlibrary.cache.ExternalProxyCacheManager
                    .getCacheDirectoryPath();
            if (cacheDirPath == null) {
                return null;
            }
            final java.io.File cacheDir = new java.io.File(cacheDirPath);
            final String md5 = md5Hex(url);
            java.io.File f = new java.io.File(cacheDir, md5);
            if (!f.exists() || f.length() == 0) {
                // danikula 带扩展名落盘：按 md5 前缀匹配，排除未下完的 .download
                java.io.File[] matches = cacheDir.listFiles((dir, name) ->
                        name.startsWith(md5) && !name.endsWith(".download"));
                if (matches != null && matches.length > 0) {
                    f = matches[0];
                }
            }
            if (f.exists() && f.length() > 0) {
                Log.d(TAG, "命中缓存文件: " + f.getAbsolutePath() + " (" + f.length() + "B)");
                return f;
            }
        } catch (Exception e) {
            Log.w(TAG, "取缓存文件失败", e);
        }
        return null;
    }

    private String md5Hex(String s) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * HLS 下载完整后 ASR：M3U8Downloader 下载 → 合并 mp4 → AsrSubtitleGenerator。
     */
    private void downloadHlsAndGenerate(final String m3u8Url) {
        downloadHlsAndGenerate(m3u8Url, false);
    }

    /**
     * HLS 下载完整后 ASR：M3U8Downloader 下载 → 合并 mp4 → AsrSubtitleGenerator。
     *
     * @param background true=自动触发的后台下载，用悬浮环展示进度（不弹对话框、不遮挡画面）
     */
    private void downloadHlsAndGenerate(final String m3u8Url, final boolean background) {
        if (sIsAsrGenerating || sProgressiveAsr != null) {
            showToast("字幕生成已在运行");
            return;
        }
        final DownloadProgressDialog progress;
        if (background) {
            progress = null;
            showAsrRing();
            updateAsrRing(0, "下载字幕素材");
        } else {
            progress = new DownloadProgressDialog(mActivity);
            progress.showWithRealProgress("下载字幕素材", "正在下载 HLS 分片");
        }

        com.orange.playerlibrary.download.M3U8Downloader downloader =
                new com.orange.playerlibrary.download.M3U8Downloader(mContext);
        downloader.download(m3u8Url, "asr_tmp",
                new com.orange.playerlibrary.download.M3U8Downloader.DownloadCallback() {
                    @Override
                    public void onProgress(int p, String msg) {
                        if (progress == null) {
                            mActivity.runOnUiThread(() -> updateAsrRing(p, "下载字幕素材"));
                            return;
                        }
                        mActivity.runOnUiThread(() -> {
                            if (progress.isShowing()) {
                                progress.setProgress(p, msg);
                            }
                        });
                    }

                    @Override
                    public void onSuccess(String filePath) {
                        mActivity.runOnUiThread(() -> {
                            if (progress != null) {
                                // 交接给识别阶段：立刻关，别用 complete() 的停留延迟，
                                // 否则会和紧随其后的识别进度弹窗叠在一起
                                progress.dismiss();
                            }
                            Log.d(TAG, "HLS 下载完成: " + filePath);
                            // ASR 临时下载产物：识别完即删，避免堆积
                            startAsrGenerate(new java.io.File(filePath), true, background);
                        });
                    }

                    @Override
                    public void onError(String error) {
                        mActivity.runOnUiThread(() -> {
                            if (progress != null) {
                                progress.fail(error);
                            }
                            if (background) {
                                dismissAsrRing();
                            }
                            showToast("HLS 下载失败: " + error);
                        });
                    }
                });
    }

    /**
     * 对指定视频文件执行 ASR 字幕生成：模型检查 → 生成器 → 进度 → srt 注入。
     */
    private void startAsrGenerate(final java.io.File videoFile) {
        startAsrGenerate(videoFile, false);
    }

    /**
     * 对指定视频文件执行 ASR 字幕生成。
     *
     * @param deleteSourceAfter 完成后是否删除源文件（用于 ASR 临时下载的 HLS 产物清理；
     *                          用户主动下载/本地文件传 false）
     */
    private void startAsrGenerate(final java.io.File videoFile, final boolean deleteSourceAfter) {
        startAsrGenerate(videoFile, deleteSourceAfter, false);
    }

    /**
     * 对指定视频文件执行 ASR 字幕生成。
     *
     * @param deleteSourceAfter 完成后是否删除源文件（ASR 临时下载的 HLS 产物清理）
     * @param floating          true=自动触发的后台任务，用悬浮环展示进度（不弹对话框、不遮挡画面）
     */
    private void startAsrGenerate(final java.io.File videoFile, final boolean deleteSourceAfter,
                                  final boolean floating) {
        if (sIsAsrGenerating || sProgressiveAsr != null) {
            showToast("语音字幕生成已在运行");
            cleanupAsrTempFile(videoFile, deleteSourceAfter);
            return;
        }
        if (videoFile == null || !videoFile.exists()) {
            showToast("视频文件不可用");
            return;
        }
        if (!com.orange.playerlibrary.speech.AsrSubtitleGenerator.isModelReady(mContext)) {
            showToast("ASR 模型未下载（需 model.int8.onnx 等，约 240MB）");
            // TODO: M3 模型下载引导
            cleanupAsrTempFile(videoFile, deleteSourceAfter);
            if (floating) {
                dismissAsrRing();
            }
            return;
        }

        // 识别结果缓存：整片识别过则直接加载（含翻译），不再重跑一遍
        final String cacheKey = asrCacheKeyFor(videoFile);
        java.io.File cachedSrt =
                com.orange.playerlibrary.speech.AsrSubtitleCache.fullSrtFile(mContext, cacheKey);
        if (cachedSrt != null) {
            Log.d(TAG, "命中整片 ASR 缓存，跳过识别: " + cacheKey);
            cleanupAsrTempFile(videoFile, deleteSourceAfter);
            if (floating) {
                dismissAsrRing();
            }
            java.util.List<com.orange.playerlibrary.subtitle.SubtitleEntry> entries =
                    parseAsrEntries(cachedSrt);
            // 走 loadAsrSrtToController（整表替换）而不是 appendSubtitles：
            // 后者的归属校验会用播放源 URL 比对，而 cacheKey 可能是文件选择器
            // 挑来的任意文件路径，比对不过会把整批字幕丢掉
            loadAsrSrtToController(cachedSrt, entries.size(), entries, cacheKey);
            return;
        }

        sIsAsrGenerating = true;
        final DownloadProgressDialog progress;
        if (floating) {
            progress = null;
            showAsrRing();
            updateAsrRing(0, "准备中");
        } else {
            progress = new DownloadProgressDialog(mActivity);
            // 取消仅停 UI，生成线程尽力完成
            progress.setCancelable(true, d -> sIsAsrGenerating = false);
            progress.showWithRealProgress("语音字幕生成", "准备中…");
        }

        final com.orange.playerlibrary.speech.AsrSubtitleGenerator generator =
                new com.orange.playerlibrary.speech.AsrSubtitleGenerator(mContext);
        generator.generate(videoFile, "auto",
                new com.orange.playerlibrary.speech.AsrSubtitleGenerator.GenerateCallback() {
                    @Override
                    public void onProgress(int percent, String stage) {
                        mActivity.runOnUiThread(() -> {
                            if (progress == null) {
                                updateAsrRing(percent, stage);
                            } else if (progress.isShowing()) {
                                progress.setProgress(percent, stage);
                            }
                        });
                    }

                    @Override
                    public void onSuccess(java.io.File srtFile, int subtitleCount) {
                        mActivity.runOnUiThread(() -> {
                            if (progress != null) {
                                progress.complete("字幕生成完成");
                            } else {
                                dismissAsrRing();
                            }
                            if (srtFile != null) {
                                // 完整识别没有「块」概念，一次就是整片：直接落整片缓存
                                java.util.List<com.orange.playerlibrary.subtitle.SubtitleEntry> entries =
                                        parseAsrEntries(srtFile);
                                if (!entries.isEmpty()) {
                                    com.orange.playerlibrary.speech.AsrSubtitleCache
                                            .saveFull(mContext, cacheKey, entries);
                                }
                                loadAsrSrtToController(srtFile, subtitleCount, entries, cacheKey);
                            }
                            cleanupAsrTempFile(videoFile, deleteSourceAfter);
                            sIsAsrGenerating = false;
                        });
                    }

                    @Override
                    public void onError(int code, String message) {
                        mActivity.runOnUiThread(() -> {
                            if (progress != null) {
                                progress.fail("字幕生成失败", message);
                            } else {
                                dismissAsrRing();
                            }
                            showToast("语音字幕失败: " + message);
                            cleanupAsrTempFile(videoFile, deleteSourceAfter);
                            sIsAsrGenerating = false;
                        });
                    }
                }, () -> false);
    }

    // ===== ASR 悬浮环形进度（自动触发的后台任务用）=====

    /**
     * 悬浮环（进程级）：播放器存在多个 VideoEventManager 实例（controller / error view），
     * 实例字段会导致「A 启动的环 B 关不掉」。
     */
    private static com.orange.playerlibrary.FloatingRingProgress sAsrRing;

    /** 挂载并显示悬浮环（重复调用安全） */
    private void showAsrRing() {
        if (mVideoView == null) {
            return;
        }
        if (sAsrRing == null) {
            sAsrRing = new com.orange.playerlibrary.FloatingRingProgress(mContext);
        }
        sAsrRing.attach(mVideoView);
        sAsrRing.show();
    }

    private void updateAsrRing(int percent, String hint) {
        if (sAsrRing != null) {
            sAsrRing.setProgress(percent);
            sAsrRing.setHint(hint);
        }
    }

    private void dismissAsrRing() {
        if (sAsrRing != null) {
            sAsrRing.dismiss();
            sAsrRing = null;
            Log.d(TAG, "ASR 悬浮环已移除");
        }
    }

    // ===== 渐进 ASR（边看边播）：按播放进度逐块识别 =====

    /** 渐进会话（进程级）：多实例共享，避免同一视频被并发识别两次 */
    private static com.orange.playerlibrary.speech.ProgressiveAsrSession sProgressiveAsr;
    private static boolean sProgressiveSubtitleShown = false;
    private final android.os.Handler mAsrTickHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mAsrTickRunnable;

    // ===== ASR 模型下载 =====

    /** 下载器（进程级：多个 VideoEventManager 实例共享，避免并发下载同一份模型） */
    private static com.orange.playerlibrary.speech.AsrModelDownloader sAsrModelDownloader;

    /**
     * 下载 ASR 模型并在对话框内展示进度（约 228MB，支持断点续传）。
     *
     * 完成后把按钮重绑为「生成字幕」：旧实现只改了文案与 enabled，监听器仍指向
     * 本方法——用户点「生成字幕」会再次进入下载路径（三个文件体积全部命中跳过
     * 分支、立即回调成功），表现为「字幕生成永远不开始，只弹下载完成」。
     *
     * @param onReadyToGenerate 模型就绪后点击按钮应执行的动作
     */
    private void startAsrModelDownload(final android.view.View btn, final android.widget.TextView status,
                                       final Runnable onReadyToGenerate) {
        if (sAsrModelDownloader == null) {
            sAsrModelDownloader = new com.orange.playerlibrary.speech.AsrModelDownloader(mContext);
        }
        if (sAsrModelDownloader.isDownloading()) {
            // 已在下载：不重复发起，把本界面的进度接管过来
            attachAsrDownloadUi(btn, status, null);
            return;
        }
        if (btn != null) {
            btn.setEnabled(false);
        }
        sAsrModelDownloader.download(buildAsrDownloadCallback(btn, status, onReadyToGenerate));
    }

    /**
     * 让新打开的面板接管正在进行的 ASR 模型下载进度。
     *
     * <p>关闭面板时 detachCallback 让下载继续跑（有意设计），但旧回调已失效；
     * 重开时必须把进度接回来，否则界面显示「未下载」、按钮可点，进度凭空消失。
     *
     * @param dialog 传入时在其 dismiss 时解绑回调，避免持有已销毁的 View
     */
    private void attachAsrDownloadUi(final android.view.View btn, final android.widget.TextView status,
                                     final android.app.Dialog dialog) {
        if (sAsrModelDownloader == null) {
            return;
        }
        boolean attached = sAsrModelDownloader.attachCallback(
                buildAsrDownloadCallback(btn, status, null));
        if (!attached) {
            return;
        }
        if (btn != null) {
            btn.setEnabled(false);
        }
        // 先把最近一次快照画上去：大文件回调间隔可达数秒，空窗期显示 0% 很突兀
        int[] snap = sAsrModelDownloader.getLastProgress();
        if (snap != null && status != null) {
            long downloaded = sAsrModelDownloader.getLastDownloadedBytes();
            status.setText(String.format(java.util.Locale.US,
                    "下载模型 %d%%（%.0f/%.0f MB）", snap[0],
                    downloaded / 1048576.0, snap[2] / 1048576.0));
            status.setTextColor(0xFF4CAF50);
        }
        if (btn instanceof android.widget.TextView && snap != null) {
            ((android.widget.TextView) btn).setText(snap[0] + "%");
        }
        if (dialog != null) {
            dialog.setOnDismissListener(d -> {
                if (sAsrModelDownloader != null && sAsrModelDownloader.isDownloading()) {
                    sAsrModelDownloader.detachCallback();
                }
            });
        }
    }

    /** ASR 模型下载的进度/完成/失败渲染；新发起与接管共用同一套 */
    private com.orange.playerlibrary.speech.AsrModelDownloader.DownloadCallback
            buildAsrDownloadCallback(final android.view.View btn, final android.widget.TextView status,
                                     final Runnable onReadyToGenerate) {
        return new com.orange.playerlibrary.speech.AsrModelDownloader.DownloadCallback() {
            @Override
            public void onProgress(final int percent, final long downloaded, final long total,
                                   final String stage) {
                mActivity.runOnUiThread(() -> {
                    // stage 含「续传」/当前文件名等关键状态，拼进状态行
                    String text = String.format(java.util.Locale.US,
                            "下载模型 %d%%（%.0f/%.0f MB）",
                            percent, downloaded / 1048576.0, total / 1048576.0)
                            + (stage == null || stage.isEmpty() ? "" : " · " + stage);
                    if (btn instanceof android.widget.TextView) {
                        ((android.widget.TextView) btn).setText(percent + "%");
                    }
                    if (status != null) {
                        status.setText(text);
                        status.setTextColor(0xFF4CAF50);
                    }
                });
            }

            @Override
            public void onSuccess() {
                mActivity.runOnUiThread(() -> {
                    if (status != null) {
                        status.setText("模型已就绪，可开始生成字幕");
                        status.setTextColor(0xFF4CAF50);
                    }
                    if (btn instanceof android.widget.TextView) {
                        ((android.widget.TextView) btn).setText("生成字幕");
                        btn.setEnabled(true);
                        // 关键：把监听器切到「生成字幕」，否则点击会再次进入下载路径
                        btn.setOnClickListener(v -> {
                            if (onReadyToGenerate != null) {
                                onReadyToGenerate.run();
                            }
                        });
                    }
                    showToast("ASR 模型下载完成");
                });
            }

            @Override
            public void onError(final String error) {
                mActivity.runOnUiThread(() -> {
                    if (status != null) {
                        status.setText("模型下载失败：" + error + "（可重试，支持续传）");
                        status.setTextColor(0xFFFF6B6B);
                    }
                    if (btn instanceof android.widget.TextView) {
                        ((android.widget.TextView) btn).setText("重试下载");
                        btn.setEnabled(true);
                    }
                });
            }
        };
    }

    /** 启动本地/已缓存 mp4 的渐进识别 */
    private void startProgressiveAsr(java.io.File videoFile) {
        long durationMs = mVideoView != null ? mVideoView.getDuration() : 0;
        startProgressiveAsr(
                new com.orange.playerlibrary.speech.LocalFileBlockSource(videoFile, durationMs),
                videoFile.getName());
    }

    /**
     * 对当前播放视频启动渐进识别（本地/已缓存 mp4 或 HLS）。
     *
     * @return true=已接管（含已在运行）；false=当前视频不支持渐进识别
     *         （未缓存网络视频等），由调用方决定回退策略
     */
    private boolean startProgressiveAsrForCurrentVideo() {
        if (sProgressiveAsr != null && sProgressiveAsr.isRunning()) {
            return true;
        }
        final String url = mVideoView != null ? mVideoView.getUrl() : null;
        if (url == null || url.isEmpty()
                || !com.orange.playerlibrary.speech.ProgressiveAsrSession.isAvailable(mContext)) {
            return false;
        }
        if (isM3u8Url(url)) {
            startProgressiveAsrHls(url);
            return true;
        }
        java.io.File local = resolveLocalVideoFileForAsr();
        if (local != null) {
            startProgressiveAsr(local);
            return true;
        }
        // 未缓存的网络 mp4：与自动触发路径保持一致，经本地代理按区间读取，
        // 否则拨开「边看边识别」会静默无反应（等整片缓存要等到快播完）
        if (isNetworkUrl(url)) {
            Log.d(TAG, "渐进 ASR（网络按区间读取）: " + url);
            startHttpProgressiveAsr(url);
            return true;
        }
        return false;
    }

    /**
     * 启动 HLS 渐进识别：按区间取 m3u8 分片，分片字节优先复用播放器
     * media3 缓存（Media3CacheExportUtils），不重复下载。
     */
    private void startProgressiveAsrHls(String m3u8Url) {
        java.io.File workDir = new java.io.File(mContext.getCacheDir(), "asr_work");
        if (!workDir.exists()) {
            workDir.mkdirs();
        }
        startProgressiveAsr(
                new com.orange.playerlibrary.speech.HlsCachedBlockSource(mContext, m3u8Url, workDir),
                "HLS");
    }

    /**
     * 启动渐进识别会话（本地/已缓存 mp4 或 HLS），并挂 1s 播放位置驱动。
     * HLS 的 duration 未知时由 source 首次解析清单后补齐。
     */
    private void startProgressiveAsr(com.orange.playerlibrary.speech.BlockAudioSource source,
                                     final String label) {
        // 本会话归属的视频源（启动时快照）：换视频后在途结果据此被丢弃
        final String owningUrl = mVideoView != null ? mVideoView.getUrl() : null;
        if (sIsAsrGenerating) {
            showToast("语音字幕生成已在运行");
            return;
        }
        if (sProgressiveAsr != null && sProgressiveAsr.isRunning()) {
            // 已在识别中，忽略重复启动（自动触发与手动入口可能同时命中）
            Log.d(TAG, "渐进 ASR 已在运行，忽略重复启动: " + label);
            return;
        }
        stopProgressiveAsr();

        // 识别结果缓存：整片识别过则直接加载，连会话都不必起
        com.orange.playerlibrary.speech.AsrSubtitleCache.Snapshot cached =
                com.orange.playerlibrary.speech.AsrSubtitleCache.load(mContext, owningUrl);
        if (cached != null && cached.isComplete()) {
            Log.d(TAG, "命中整片 ASR 缓存，跳过识别: " + label);
            startAsrTranslationIfEnabled(owningUrl);
            appendAsrEntries(cached.getEntries(), owningUrl);
            return;
        }

        final long durationMs = mVideoView != null ? mVideoView.getDuration() : 0;
        startAsrTranslationIfEnabled(owningUrl);
        sProgressiveAsr = new com.orange.playerlibrary.speech.ProgressiveAsrSession(
                mContext, source, "auto", durationMs,
                new com.orange.playerlibrary.speech.ProgressiveAsrSession.Callback() {
                    @Override
                    public void onBlockReady(final long blockStartMs,
                                             final java.util.List<com.orange.playerlibrary.subtitle.SubtitleEntry> entries) {
                        // 先落盘再显示：块已完成的事实必须持久化，否则换集后重看要重识别
                        com.orange.playerlibrary.speech.AsrSubtitleCache
                                .appendBlock(mContext, owningUrl, blockStartMs, entries);
                        appendAsrEntries(entries, owningUrl);
                    }

                    @Override
                    public void onLanguageDetected(final String lang) {
                        // 本地翻译兜底需要具体源语言（ASR 为 auto 模式）
                        if (sProgressiveTranslator != null) {
                            sProgressiveTranslator.onLanguageDetected(lang);
                        }
                    }

                    @Override
                    public void onProgress(final long recognizedUntilMs, final long durationMs2) {
                        mActivity.runOnUiThread(() -> {
                            int pct = durationMs2 > 0
                                    ? (int) (recognizedUntilMs * 100 / durationMs2) : 0;
                            updateAsrRing(pct, "边看边识别");
                        });
                    }

                    @Override
                    public void onCompleted() {
                        // 全片识别完毕：增量缓存提升为整片缓存，下次重看直接加载
                        com.orange.playerlibrary.speech.AsrSubtitleCache
                                .promoteToFull(mContext, owningUrl);
                        mActivity.runOnUiThread(VideoEventManager.this::dismissAsrRing);
                    }

                    @Override
                    public void onError(final int code, final String message) {
                        mActivity.runOnUiThread(() -> {
                            dismissAsrRing();
                            showToast("边看边识别失败: " + message);
                        });
                    }
                });
        if (cached != null) {
            // 上次只识别了一部分：预填已识别块，本轮只补缺失段
            sProgressiveAsr.seedDoneBlocks(cached.getDoneBlocks());
            appendAsrEntries(cached.getEntries(), owningUrl);
        }
        sProgressiveAsr.start();
        // 用当前播放位置初始化，避免从 0 开始识别已播过的块
        if (mVideoView != null) {
            sProgressiveAsr.updatePlaybackPosition(mVideoView.getCurrentPosition());
        }
        showAsrRing();
        updateAsrRing(0, "边看边识别");
        startAsrProgressTicker();
        Log.d(TAG, "渐进 ASR 已启动: " + label);
    }

    /**
     * 把一批 ASR 条目注入字幕列表，并按需提交渐进翻译。
     *
     * 渐进识别、缓存命中、完整识别三条链路共用：字幕下标可能被整表替换/清空，
     * 必须用 {@code AppendCallback} 回传的**实际写入下标**提交翻译。
     */
    private void appendAsrEntries(
            final java.util.List<com.orange.playerlibrary.subtitle.SubtitleEntry> entries,
            final String owningUrl) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        mActivity.runOnUiThread(() -> {
            if (mController == null || mController.getSubtitleManager() == null) {
                return;
            }
            com.orange.playerlibrary.subtitle.SubtitleManager sm =
                    mController.getSubtitleManager();
            if (!sProgressiveSubtitleShown) {
                sProgressiveSubtitleShown = true;
                sm.show();
                mController.startSubtitle();
            }
            sm.appendSubtitles(entries, owningUrl,
                    new com.orange.playerlibrary.subtitle.SubtitleManager.AppendCallback() {
                        @Override
                        public void onAppended(int startIdx) {
                            if (sProgressiveTranslator != null) {
                                sProgressiveTranslator.submit(startIdx, entries);
                            }
                        }

                        @Override
                        public void onDiscarded() {
                            // 本批被丢弃（换源/会话切换），不提交翻译，
                            // 翻译侧下标与列表保持一致
                        }
                    });
        });
    }

    /** 每秒把播放位置喂给渐进会话（会话按位置决定下一块） */
    private void startAsrProgressTicker() {
        stopAsrProgressTicker();
        mAsrTickRunnable = new Runnable() {
            @Override
            public void run() {
                if (sProgressiveAsr == null || !sProgressiveAsr.isRunning()) {
                    return;
                }
                // 视图已随 Activity 销毁：结束会话，避免静态引用与线程空转
                if (mVideoView == null || !mVideoView.isAttachedToWindow()) {
                    stopProgressiveAsr();
                    return;
                }
                long pos = mVideoView.getCurrentPosition();
                sProgressiveAsr.updatePlaybackPosition(pos);
                mAsrTickHandler.postDelayed(this, 1000);
            }
        };
        mAsrTickHandler.postDelayed(mAsrTickRunnable, 1000);
    }

    private void stopAsrProgressTicker() {
        if (mAsrTickRunnable != null) {
            mAsrTickHandler.removeCallbacks(mAsrTickRunnable);
            mAsrTickRunnable = null;
        }
    }

    /** 结束渐进会话（切换视频/播放结束/释放时调用） */
    private void stopProgressiveAsr() {
        stopAsrProgressTicker();
        stopProgressiveTranslation();
        boolean wasRunning = sProgressiveAsr != null;
        if (wasRunning) {
            sProgressiveAsr.stop();
            sProgressiveAsr = null;
            Log.d(TAG, "渐进 ASR 已停止");
        }
        if (wasRunning) {
            dismissAsrRing();
        }
        sProgressiveSubtitleShown = false;
    }

    /** 清理 ASR 临时下载产物（仅自动下载的 HLS 产物传 deleteSourceAfter=true） */
    private void cleanupAsrTempFile(java.io.File videoFile, boolean deleteSourceAfter) {
        if (deleteSourceAfter && videoFile != null && videoFile.exists()) {
            videoFile.delete();
        }
    }

    /**
     * 把 ASR 生成的 srt 注入当前播放器 SubtitleManager 并启用显示。
     *
     * @param entries  同一份字幕的条目；非空且开启「识别后自动翻译」时，在字幕
     *                 就位后提交整片翻译（下标从 0 起——这里走的是整表替换）
     * @param videoKey 翻译缓存键（与渐进链路一致，两条链路的译文互相复用）
     */
    private void loadAsrSrtToController(java.io.File srtFile, int subtitleCount,
                                        final java.util.List<com.orange.playerlibrary.subtitle.SubtitleEntry> entries,
                                        final String videoKey) {
        if (mController == null || mController.getSubtitleManager() == null) {
            showToast("播放器未就绪，字幕已保存到: " + srtFile.getAbsolutePath());
            return;
        }
        final com.orange.playerlibrary.subtitle.SubtitleManager sm =
                mController.getSubtitleManager();
        sm.loadSubtitle(srtFile, new com.orange.playerlibrary.subtitle.SubtitleManager.OnSubtitleLoadListener() {
            @Override
            public void onLoadSuccess(int count) {
                mActivity.runOnUiThread(() -> {
                    sm.show();
                    if (mController != null) {
                        mController.startSubtitle();
                    }
                    mSettingsManager.setSubtitleEnabled(true);
                    showToast("语音字幕已生成: " + count + " 条");
                    // 字幕就位后再提交翻译：早于 loadSubtitle 完成时列表还是空的，
                    // 译文回写会因下标越界被丢弃
                    if (entries != null && !entries.isEmpty()
                            && startAsrTranslationIfEnabled(videoKey)) {
                        if (sProgressiveTranslator != null) {
                            sProgressiveTranslator.submit(0, entries);
                        }
                    }
                });
            }

            @Override
            public void onLoadFailed(String error) {
                mActivity.runOnUiThread(() -> showToast("字幕加载失败: " + error));
            }
        });
    }

    /** 解析 ASR 产物 srt 为字幕条目；失败返回空列表（调用方据此跳过缓存/翻译） */
    private java.util.List<com.orange.playerlibrary.subtitle.SubtitleEntry> parseAsrEntries(
            java.io.File srtFile) {
        try {
            return com.orange.playerlibrary.speech.AsrSubtitleCache.parseSrt(srtFile);
        } catch (Throwable t) {
            Log.w(TAG, "解析 ASR 字幕失败", t);
            return new java.util.ArrayList<>();
        }
    }

    /**
     * ASR 缓存键：识别对象就是当前播放源时用 URL（跨会话、跨临时文件稳定），
     * 否则退回文件路径——用户从文件选择器挑的任意视频与当前播放源无关，
     * 用 URL 做键会把它的识别结果错误地挂到当前视频名下。
     */
    private String asrCacheKeyFor(java.io.File videoFile) {
        String url = mVideoView != null ? mVideoView.getUrl() : null;
        if (url != null && !url.isEmpty()) {
            if (isM3u8Url(url)) {
                return url;   // HLS 的临时下载文件名随机，只能按 URL 归属
            }
            java.io.File current = resolveLocalVideoFileForAsr();
            if (current != null && current.equals(videoFile)) {
                return url;
            }
        }
        return videoFile != null ? videoFile.getAbsolutePath() : null;
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
        if (requestCode == REQUEST_CODE_ASR_VIDEO) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    Log.d(TAG, "ASR 选择的视频: " + uri);
                    generateSubtitleFromVideoUri(uri);
                }
            }
            return true;
        }
        return false;
    }

    /**
     * ASR 视频选择：从 content Uri 拷贝到临时文件后生成字幕。
     * content:// 不能直接给 MediaExtractor 用本地路径，需先落盘。
     */
    private void generateSubtitleFromVideoUri(Uri uri) {
        try {
            // 持久化读权限（供后续复用）
            try {
                mActivity.getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            String displayName = queryDisplayName(uri);
            java.io.File tmp = new java.io.File(mContext.getCacheDir(),
                    "asr_src_" + System.currentTimeMillis() + "_"
                            + (displayName != null ? displayName : "video.mp4"));
            try (java.io.InputStream in = mActivity.getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            startAsrGenerate(tmp);
        } catch (Exception e) {
            Log.e(TAG, "读取视频失败", e);
            showToast("无法读取所选视频: " + e.getMessage());
        }
    }

    private String queryDisplayName(Uri uri) {
        try {
            android.database.Cursor c = mActivity.getContentResolver().query(
                    uri, new String[]{"_display_name"}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        return c.getString(0);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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

        // ASS/SSA 样式保留分引擎双路径：
        // - mpv：libmpv 静态内嵌 libass 0.17.4，sub-add 原生渲染（立即生效，
        //   保留全部特效标签；代价是字幕进画面，SubtitleView 层禁用）
        // - Exo：Media3 MergingMediaSource 管线（需在下次 setUp 前注入，重播生效）
        // 需在下次 setUp 前注入；当前实现经 setExternalSubtitle 标记，
        // 由下次 prepareAsync 合并，纯文本层不再重复加载。
        if (fileName != null) {
            if (isAssSubtitlePath(fileName)) {
                String videoUrl = mVideoView.getUrl();
                if (PlayerConstants.ENGINE_MPV.equals(mVideoView.getCurrentPlayerEngine())
                        && enableNativeAssRender(resolveSubtitleLocalPath(uri, fileName), videoUrl)) {
                    showToast("ASS 字幕已由 libass 原生渲染");
                    if (videoUrl != null) {
                        mSettingsManager.setSubtitleLocalForVideo(videoUrl, uriString);
                        mSettingsManager.setSubtitleUrlForVideo(videoUrl, null);
                        mSettingsManager.setSubtitleMimeTypeForVideo(videoUrl, "text/x-ssa");
                    }
                    return;
                }
                if (mVideoView.setExternalSubtitle(uriString, "text/x-ssa")) {
                    showToast("ASS 字幕将以增强样式渲染（重新播放生效）");
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
                    // 用户主动加载字幕的意图即显示：开启字幕显示（mEnabled=true）
                    // 并确保 update 循环运行，否则字幕永不渲染（updateSubtitle 依赖 mEnabled）
                    mController.getSubtitleManager().show();
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
        String videoUrl = mVideoView.getUrl();
        // mpv 引擎：ASS URL 直接交给 sub-add（mpv 可读网络字幕），libass 原生渲染
        if (isAssSubtitlePath(url)
                && PlayerConstants.ENGINE_MPV.equals(mVideoView.getCurrentPlayerEngine())
                && enableNativeAssRender(url, videoUrl)) {
            showToast("ASS 字幕已由 libass 原生渲染");
            if (videoUrl != null) {
                mSettingsManager.setSubtitleUrlForVideo(videoUrl, url);
                mSettingsManager.setSubtitleLocalForVideo(videoUrl, null);
                mSettingsManager.setSubtitleMimeTypeForVideo(videoUrl, "text/x-ssa");
            }
            return;
        }
        if (isAssSubtitlePath(url) && mVideoView.setExternalSubtitle(url, "text/x-ssa")) {
            showToast("ASS 字幕将以增强样式渲染（重新播放生效）");
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
                    // 用户主动加载字幕的意图即显示：开启字幕显示并启动循环
                    mController.getSubtitleManager().show();
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
     * 应用字幕延迟并按视频持久化。
     * mpv 原生字幕模式下同时下发 sub-delay（libass 自按 pts 取帧，
     * SubtitleManager 的时间轴偏移对原生渲染无效）。
     */
    private void applySubtitleDelay(long delayMs) {
        if (mController == null || mController.getSubtitleManager() == null) {
            return;
        }
        mController.getSubtitleManager().setSubtitleDelayMs(delayMs);
        if (mNativeAssPath != null) {
            mVideoView.setNativeSubtitleDelayMs(delayMs);
        }
        String videoUrl = mVideoView.getUrl();
        if (videoUrl != null) {
            mSettingsManager.setSubtitleDelayForVideo(videoUrl, delayMs);
        }
        Log.d(TAG, "字幕延迟: " + delayMs + "ms");
    }

    /** 应用音频延迟（mpv 内核专属）并按视频持久化；返回是否生效 */
    private boolean applyAudioDelay(long delayMs) {
        boolean ok = mVideoView.setAudioDelayMs(delayMs);
        String videoUrl = mVideoView.getUrl();
        if (videoUrl != null) {
            mSettingsManager.setAudioDelayForVideo(videoUrl, delayMs);
        }
        Log.d(TAG, "音频延迟: " + delayMs + "ms (applied=" + ok + ")");
        return ok;
    }

    /**
     * 把字幕 Uri 落盘为本地文件路径（mpv 的 sub-add 走文件系统 open，
     * 不认 content://）。http(s) 直接返回原 URL（mpv 可网络读取）。
     */
    private String resolveSubtitleLocalPath(Uri uri, String displayName) {
        try {
            if ("file".equals(uri.getScheme())) {
                String p = uri.getPath();
                return (p != null && !p.isEmpty()) ? p : null;
            }
            if ("content".equals(uri.getScheme())) {
                java.io.File tmp = new java.io.File(mContext.getCacheDir(),
                        "native_sub_" + System.currentTimeMillis() + "_"
                                + (displayName != null ? displayName : "sub.ass"));
                try (java.io.InputStream in = mActivity.getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    if (in == null) return null;
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                return tmp.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.w(TAG, "字幕落盘失败", e);
        }
        return null;
    }

    /**
     * 启用 mpv libass 原生渲染（仅 ASS/SSA + mpv 内核）：把字幕文件交给 mpv，
     * 同时禁用 SubtitleView 文本层避免双重字幕。
     *
     * @return true=已生效；false=内核/路径不支持，调用方走纯文本降级
     */
    private boolean enableNativeAssRender(String localPath, String videoUrl) {
        if (localPath == null) {
            return false;
        }
        // 同路径重复下发会叠加字幕轨（sub-add 不去重），幂等保护
        boolean alreadyApplied = localPath.equals(mNativeAssPath)
                && videoUrl != null && videoUrl.equals(mNativeAssVideoUrl);
        if (!alreadyApplied && !mVideoView.setNativeAssSubtitle(localPath)) {
            return false;
        }
        mNativeAssPath = localPath;
        mNativeAssVideoUrl = videoUrl;
        // 用户加载字幕 = 开启字幕意图（原生模式下开关语义映射字幕轨挂/卸，
        // 初始态读该标志）
        mSettingsManager.setSubtitleEnabled(true);
        // 文本层让位：原生渲染的字幕画进画面，SubtitleView 再叠一份会重影
        if (mController.getSubtitleManager() != null) {
            mController.getSubtitleManager().hide();
        }
        mController.stopSubtitle();
        // 字幕延迟语义同步切换到 sub-delay（读记忆值下发）
        long remembered = mSettingsManager.getSubtitleDelayForVideo(videoUrl);
        if (remembered != 0) {
            mVideoView.setNativeSubtitleDelayMs(remembered);
        }
        Log.d(TAG, "mpv 原生 ASS 渲染已启用: " + localPath);
        return true;
    }

    /** 关闭原生渲染并恢复 SubtitleView 文本层（若字幕开关开启） */
    private void disableNativeAssRender() {
        if (mNativeAssPath == null) {
            return;
        }
        mNativeAssPath = null;
        mNativeAssVideoUrl = null;
        mVideoView.setNativeAssSubtitle(null);
        if (mSettingsManager.isSubtitleEnabled() && mController.getSubtitleManager() != null) {
            mController.getSubtitleManager().show();
            mController.startSubtitle();
        }
    }

    /**
     * 换视频/重进播放时恢复记忆的原生 ASS（mpv 内核）。
     * 本地字幕优先（content:// 落盘缓存文件），其次网络 URL。
     *
     * @return true=已恢复原生渲染；false=无 ASS 记忆或非字幕场景，走常规文本加载
     */
    private boolean tryRestoreNativeAss(String videoUrl) {
        String mime = mSettingsManager.getSubtitleMimeTypeForVideo(videoUrl);
        if (!"text/x-ssa".equals(mime) || !mSettingsManager.isSubtitleEnabled()) {
            // 用户上次关着字幕：不恢复原生轨（与文本路径「加载但隐藏」语义对齐，
            // 此处直接交给文本流程加载，重开开关时显示）
            return false;
        }
        String localUri = mSettingsManager.getSubtitleLocalForVideo(videoUrl);
        if (localUri != null && !localUri.isEmpty()) {
            try {
                Uri uri = Uri.parse(localUri);
                String name = null;
                if ("content".equals(uri.getScheme())) {
                    name = queryDisplayName(uri);
                }
                if (enableNativeAssRender(resolveSubtitleLocalPath(uri, name), videoUrl)) {
                    Log.d(TAG, "恢复原生 ASS 渲染(本地): " + localUri);
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "恢复原生 ASS 失败(本地): " + e);
            }
        }
        String subUrl = mSettingsManager.getSubtitleUrlForVideo(videoUrl);
        if (subUrl != null && !subUrl.isEmpty() && isAssSubtitlePath(subUrl)) {
            if (enableNativeAssRender(subUrl, videoUrl)) {
                Log.d(TAG, "恢复原生 ASS 渲染(网络): " + subUrl);
                return true;
            }
        }
        // 原生启用失败：文本层照常加载（下方流程），并清理半途状态
        disableNativeAssRender();
        return false;
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

    private void updateAudioDelayText(android.widget.TextView delayText, long delayMs) {
        delayText.setText(String.format(java.util.Locale.getDefault(),
                "音频延迟: %+.1fs", delayMs / 1000f));
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
        // 音频延迟记忆恢复（仅 mpv 内核生效，applied=false 时属性下发内部跳过）
        long rememberedAudioDelay = mSettingsManager.getAudioDelayForVideo(videoUrl);
        if (rememberedAudioDelay != 0) {
            mVideoView.setAudioDelayMs(rememberedAudioDelay);
        }
        if (mSettingsManager.isSubtitleEnabled()) {
            mController.getSubtitleManager().show();
        } else {
            mController.getSubtitleManager().hide();
        }

        // Media3 Exo 管线已在 prepare 前接管记忆的 ASS/SSA，避免 onPrepared 后重复走纯文本解析。
        if (mVideoView.isExternalSubtitleConfiguredForCurrentUrl()) {
            return;
        }

        // mpv 引擎 + 记忆的 ASS/SSA：恢复 libass 原生渲染（优先本地文件，
        // 其次网络 URL——mpv sub-add 可读网络字幕）
        if (PlayerConstants.ENGINE_MPV.equals(mVideoView.getCurrentPlayerEngine())) {
            if (tryRestoreNativeAss(videoUrl)) {
                return;
            }
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
                            // 记忆恢复加载即显示（用户上次是开着字幕的）
                            mController.getSubtitleManager().show();
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
                        // 记忆恢复加载即显示（用户上次是开着字幕的）
                        mController.getSubtitleManager().show();
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
     * 全屏设置弹窗里的扩展包入口：显示「已安装 N/M」并跳转到管理面板。
     *
     * @param dialog 宿主设置弹窗，跳转前先关掉，避免面板叠在设置上
     */
    private void bindNativeLibsEntry(View dialogView, final AlertDialog dialog) {
        android.widget.TextView status =
                dialogView.findViewById(R.id.tv_setup_native_libs_status);
        if (status != null) {
            int installed = 0;
            int supported = 0;
            for (com.orange.playerlibrary.tool.NativeLibManager.BundleInfo b
                    : com.orange.playerlibrary.tool.NativeLibManager.getBundles()) {
                if (!com.orange.playerlibrary.tool.NativeLibManager.isSupported(b.id)) {
                    continue;
                }
                supported++;
                if (com.orange.playerlibrary.tool.NativeLibManager.isInstalled(b.id)) {
                    installed++;
                }
            }
            if (supported == 0) {
                status.setText("本版本未集成任何可下载组件");
            } else {
                status.setText("已安装 " + installed + "/" + supported
                        + (installed < supported ? " · 有组件可按需下载" : ""));
            }
        }
        View btn = dialogView.findViewById(R.id.btn_setup_native_libs);
        if (btn != null) {
            btn.setOnClickListener(v -> {
                if (dialog != null) {
                    dialog.dismiss();
                }
                showNativeLibsDialog();
            });
        }
    }

    /**
     * 全屏设置弹窗里的「去广告」开关。
     *
     * <p>与主界面同名按钮共用一份状态：{@link M3U8AdManager#setEnabled} 管本次运行，
     * {@link PlayerSettingsManager#setAdRemovalEnabled} 负责持久化。两处入口都改这两者，
     * 否则会出现「全屏里开了、回主界面看还是关」的割裂。
     *
     * <p>开关只影响之后发起的 m3u8 解析；正在播放的流需重新加载才生效，
     * 这一点在副标题文案里说明，避免用户以为点了没反应。
     */
    private void bindAdRemovalEntry(View dialogView) {
        final M3U8AdManager adManager = M3U8AdManager.getInstance(mContext);
        final android.widget.TextView status =
                dialogView.findViewById(R.id.tv_setup_ad_removal_status);
        final android.widget.TextView btn =
                dialogView.findViewById(R.id.btn_setup_ad_removal);
        if (btn == null) {
            return;
        }
        renderAdRemovalEntry(adManager, status, btn);
        btn.setOnClickListener(v -> {
            boolean enabled = !adManager.isEnabled();
            adManager.setEnabled(enabled);
            mSettingsManager.setAdRemovalEnabled(enabled);
            if (enabled) {
                // 开启时清缓存，否则旧的无广告清洗结果会被复用
                adManager.clearCache();
            }
            renderAdRemovalEntry(adManager, status, btn);
        });
    }

    /** 按当前开关状态刷新去广告条目的按钮文案与副标题 */
    private void renderAdRemovalEntry(M3U8AdManager adManager,
                                      android.widget.TextView status,
                                      android.widget.TextView btn) {
        boolean enabled = adManager.isEnabled();
        btn.setText(enabled ? "已开启" : "已关闭");
        if (status != null) {
            status.setText(enabled
                    ? "播放 m3u8 时自动跳过广告片段 · 重新加载后生效"
                    : "当前不做广告片段清洗");
        }
    }

    /**
     * 记忆播放 / 选集记忆条目。
     *
     * <p>两个开关的区别：
     * <ul>
     *   <li><b>记忆播放</b>——记住每集的播放进度（秒数），按单集地址绑定，
     *       下次播到同一集会从上次位置续播。开关由
     *       {@code PlayerSettingsManager.isMemoryPlayEnabled()} 持久化。</li>
     *   <li><b>选集记忆</b>——记住上次看到第几集，重启后自动切回那一集。
     *       它依赖记忆播放开关（进度续播需要前者），所以关闭记忆播放时
     *       一并停止生效，但记录本身保留——用户重新打开即可恢复。</li>
     * </ul>
     *
     * <p>这里不提供「选集记忆」的开关：它是记忆播放的自然延伸，单独开关
     * 会让用户面对两个语义相近的选项。提供「清除」按钮即可满足重置需求。
     */
    private void bindMemoryPlayEntry(View dialogView) {
        final android.widget.TextView status =
                dialogView.findViewById(R.id.tv_setup_memory_play_status);
        final android.widget.TextView btn =
                dialogView.findViewById(R.id.btn_setup_memory_play);
        final android.widget.TextView epStatus =
                dialogView.findViewById(R.id.tv_setup_episode_memory_status);
        final android.widget.TextView epBtn =
                dialogView.findViewById(R.id.btn_setup_episode_memory_clear);

        if (btn != null) {
            renderMemoryPlayEntry(status, btn);
            btn.setOnClickListener(v -> {
                boolean enabled = !mSettingsManager.isMemoryPlayEnabled();
                mSettingsManager.setMemoryPlayEnabled(enabled);
                // 开关作用于本实例：立即生效，无需重启
                if (mVideoView != null) {
                    mVideoView.setKeepVideoPlaying(enabled);
                }
                renderMemoryPlayEntry(status, btn);
                renderEpisodeMemoryEntry(epStatus, epBtn);
            });
        }

        if (epBtn != null) {
            renderEpisodeMemoryEntry(epStatus, epBtn);
            epBtn.setOnClickListener(v -> {
                mSettingsManager.clearLastEpisodeIndex(getSeriesKey());
                // 已保存的进度也一并清掉，否则「清除」后下次仍会从中间续播
                if (mVideoView != null) {
                    mVideoView.clearSavedProgress();
                }
                renderEpisodeMemoryEntry(epStatus, epBtn);
                showToast("已清除本剧集的播放记录");
            });
        }
    }

    /** 刷新记忆播放条目的按钮文案与副标题 */
    private void renderMemoryPlayEntry(android.widget.TextView status,
                                       android.widget.TextView btn) {
        boolean enabled = mSettingsManager.isMemoryPlayEnabled();
        btn.setText(enabled ? "已开启" : "已关闭");
        if (status != null) {
            status.setText(enabled
                    ? "记住每集进度，下次从上次位置续播"
                    : "每次都从头播放");
        }
    }

    /** 刷新选集记忆条目的状态文案 */
    private void renderEpisodeMemoryEntry(android.widget.TextView status,
                                          android.widget.TextView btn) {
        if (status == null) {
            return;
        }
        int index = getLastEpisodeIndex();
        if (!mSettingsManager.isMemoryPlayEnabled()) {
            status.setText("记忆播放已关闭，暂不生效");
        } else if (index >= 0) {
            status.setText("上次看到第 " + (index + 1) + " 集，重启后自动续播");
        } else {
            status.setText("尚无记录 · 切集后自动记住");
        }
    }

    /**
     * 扩展包管理面板：按需下载 native 组件（功能组件 + 播放内核）。
     *
     * 这些 so 不再随 APK 分发（见 NativeLibManager）。每个条目按状态显示：
     * 未支持（宿主未引依赖）/ 未安装（显示体积）/ 已安装。
     * 下载完成后组件的 so 会被 System.load 进当前进程，功能立即可用；
     * 若加载失败（部分 ROM 限制），提示需重启应用。
     *
     * <p>宿主可在设置面板等任意位置调用（入口不再局限于字幕面板）。
     *
     * <p>渲染前先确保 NativeLibManager 已初始化：{@code isInstalled} 在未初始化
     * 时会返回 false，而 {@code download} 会惰性补初始化——两者判定不一致时，
     * 面板会把已下载的组件显示成「未安装」，点下载却瞬间完成。
     */
    public void showNativeLibsDialog() {
        com.orange.playerlibrary.tool.NativeLibManager.install(mContext);
        View dialogView = View.inflate(mActivity, R.layout.dialog_native_libs, null);

        final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                DialogUtils.DialogPosition.RIGHT, null, null);

        View layout = dialogView.findViewById(R.id.layout);
        if (layout != null) {
            layout.setOnClickListener(v -> dialog.dismiss());
        }

        final int[] rowIds = {
                R.id.btn_bundle_torrent, R.id.btn_bundle_asr,
                R.id.btn_bundle_ocr, R.id.btn_bundle_translate,
                R.id.btn_bundle_ijk, R.id.btn_bundle_ali,
                R.id.btn_bundle_mpv, R.id.btn_bundle_ffmpeg,
        };
        final int[] statusIds = {
                R.id.tv_bundle_torrent_status, R.id.tv_bundle_asr_status,
                R.id.tv_bundle_ocr_status, R.id.tv_bundle_translate_status,
                R.id.tv_bundle_ijk_status, R.id.tv_bundle_ali_status,
                R.id.tv_bundle_mpv_status, R.id.tv_bundle_ffmpeg_status,
        };
        final String[] bundleIds = {
                com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_TORRENT,
                com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_ASR,
                com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_OCR,
                com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_TRANSLATE,
                com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_IJK,
                com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_ALI,
                com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_MPV,
                com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_FFMPEG,
        };

        for (int i = 0; i < bundleIds.length; i++) {
            final String bundleId = bundleIds[i];
            final android.widget.TextView btn = dialogView.findViewById(rowIds[i]);
            final android.widget.TextView status = dialogView.findViewById(statusIds[i]);
            if (btn == null) {
                continue;
            }
            bindBundleRow(dialog, bundleId, btn, status);
        }

        android.widget.TextView hint = dialogView.findViewById(R.id.tv_native_libs_hint);
        if (hint != null) {
            String abi = com.orange.playerlibrary.tool.NativeLibManager.currentAbi();
            hint.setText(abi == null
                    ? "当前设备架构（" + android.os.Build.SUPPORTED_ABIS[0]
                      + "）不提供这些组件，相关功能不可用。"
                    : "以下组件不再随安装包分发，用到时按需下载（支持断点续传，"
                      + "解压后占用约 " + com.orange.playerlibrary.tool.NativeLibManager
                            .formatSize(totalRawSize()) + "）。");
        }

        // 面板关闭时解绑回调：下载在后台继续，但回调持有本对话框的 View，
        // 不解绑会泄漏；重开面板时由 bindBundleRow 重新接管。
        dialog.setOnDismissListener(d -> {
            for (String id : bundleIds) {
                if (com.orange.playerlibrary.tool.NativeLibManager.isDownloading(id)) {
                    com.orange.playerlibrary.tool.NativeLibManager.detachDownloadCallback(id);
                }
            }
        });
    }

    /** 单个组件行的状态绑定与下载按钮（紧凑单行：左状态、右小按钮） */
    private void bindBundleRow(final AlertDialog dialog, final String bundleId,
                               final android.widget.TextView btn,
                               final android.widget.TextView status) {
        final com.orange.playerlibrary.tool.NativeLibManager.BundleInfo info =
                com.orange.playerlibrary.tool.NativeLibManager.getBundle(bundleId);
        if (info == null) {
            return;
        }
        final String sizeText = com.orange.playerlibrary.tool.NativeLibManager
                .formatSize(info.size());

        if (!com.orange.playerlibrary.tool.NativeLibManager.isSupported(bundleId)) {
            if (status != null) {
                status.setText("本版本未集成");
                status.setTextColor(0xFFFF6B6B);
            }
            btn.setText("不可用");
            // TextView 的 setEnabled(false) 不改外观，需显式降透明度。
            // 另需 setClickable(false)：setOnClickListener(null) 只摘监听器、
            // 不会清 clickable 标志，按下仍有背景反馈却无响应。
            btn.setAlpha(0.4f);
            btn.setOnClickListener(null);
            btn.setClickable(false);
            btn.setBackgroundResource(R.drawable.btn_bundle_delete_bg);
            return;
        }
        // 该组件正在下载：面板被关掉再打开时会走到这里。
        // 下载本身在后台继续（这是有意设计），但进度回调已随旧对话框失效，
        // 必须在这里重新接管，否则用户看到的是「未安装 · 可下载」——
        // 进度凭空消失，再点还会被拒（"下载已在进行中"）。
        if (com.orange.playerlibrary.tool.NativeLibManager.isDownloading(bundleId)) {
            btn.setText("下载中…");
            btn.setAlpha(0.6f);
            btn.setBackgroundResource(R.drawable.btn_bundle_action_bg);
            // 先接管回调，再把当前进度补画上去（attach 会把最新快照回调一次）
            btn.setOnClickListener(null);
            com.orange.playerlibrary.tool.NativeLibManager.attachDownloadCallback(bundleId,
                    buildBundleInstallCallback(dialog, bundleId, btn, status));
            return;
        }

        // 宿主把 so 打进 APK（完整引入）时来源是 BUNDLED：一样可用，但删不掉
        // APK 里的 so，故显示「内置」而不是给出点了没反应的「删除」按钮。
        if (com.orange.playerlibrary.tool.NativeLibManager.installSource(bundleId)
                == com.orange.playerlibrary.tool.NativeLibManager.InstallSource.BUNDLED) {
            if (status != null) {
                status.setText("已内置 · 随应用分发");
                status.setTextColor(0xFF4CAF50);
            }
            btn.setText("内置");
            btn.setAlpha(0.4f);
            btn.setOnClickListener(null);
            btn.setClickable(false);   // 同「不可用」分支：null 监听器不清 clickable
            btn.setBackgroundResource(R.drawable.btn_bundle_delete_bg);
            return;
        }
        if (com.orange.playerlibrary.tool.NativeLibManager.isInstalled(bundleId)) {
            if (status != null) {
                status.setText("已安装 · " + com.orange.playerlibrary.tool.NativeLibManager
                        .formatSize(info.rawSize()));
                status.setTextColor(0xFF4CAF50);
            }
            btn.setText("删除");
            btn.setAlpha(1f);
            // 删除是破坏性操作，用弱化描边样式，不与「下载」抢注意力
            btn.setBackgroundResource(R.drawable.btn_bundle_delete_bg);
            btn.setOnClickListener(v -> {
                new AlertDialog.Builder(mActivity)
                        .setTitle("删除组件")
                        .setMessage("删除后相关功能将不可用，需要时可重新下载。")
                        .setPositiveButton("删除", (d, w) -> {
                            com.orange.playerlibrary.tool.NativeLibManager
                                    .remove(mContext, bundleId);
                            bindBundleRow(dialog, bundleId, btn, status);
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
            return;
        }
        // 播放内核比功能组件多一层平台约束（如 mpv 需 API 26+）：先下载再提示
        // 也用不了会白下，故在未安装状态下把约束一并说明。
        String platformNote = platformNoteFor(bundleId);
        if (status != null) {
            status.setText(platformNote == null
                    ? "未安装 · " + sizeText
                    : "未安装 · " + sizeText + " · " + platformNote);
            status.setTextColor(0xFFFF8F3F);
        }
        // 体积已在上方状态行给出，按钮只留动作词，避免窄按钮里塞长文案
        btn.setText("下载");
        btn.setAlpha(1f);
        btn.setBackgroundResource(R.drawable.btn_bundle_action_bg);
        btn.setOnClickListener(v -> startBundleDownload(dialog, bundleId, btn, status));
    }

    /** 内核包在该设备上的额外约束说明；无约束返回 null */
    private static String platformNoteFor(String bundleId) {
        if (com.orange.playerlibrary.tool.NativeLibManager.BUNDLE_MPV.equals(bundleId)
                && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            return "系统低于 8.0，不可用";
        }
        return null;
    }

    /**
     * 构造「进度写入该行状态文本」的安装回调。
     *
     * <p>抽出来是为了让「新发起下载」与「重开面板接管进行中的下载」共用同一套渲染，
     * 否则两条路径的文案会各写一遍、容易漂移。
     */
    private com.orange.playerlibrary.tool.NativeLibManager.InstallCallback
            buildBundleInstallCallback(final AlertDialog dialog, final String bundleId,
                                       final android.widget.TextView btn,
                                       final android.widget.TextView status) {
        return new com.orange.playerlibrary.tool.NativeLibManager.InstallCallback() {
            @Override
            public void onProgress(final int percent, final long downloaded,
                                   final long total, final String stage) {
                mActivity.runOnUiThread(() -> {
                    if (status != null) {
                        status.setText(String.format(java.util.Locale.US,
                                "下载中 %d%% · %.1f/%.1f MB%s", percent,
                                downloaded / 1048576.0, total / 1048576.0,
                                stage == null || stage.isEmpty() ? "" : " · " + stage));
                        status.setTextColor(0xFFFF8F3F);
                    }
                    btn.setText(percent + "%");
                });
            }

            @Override
            public void onSuccess(final boolean loaded) {
                mActivity.runOnUiThread(() -> {
                    // 重新绑定：状态变「已安装」，按钮变「删除」
                    bindBundleRow(dialog, bundleId, btn, status);
                    if (status != null && !loaded) {
                        status.setText("已安装 · 重启后生效");
                        status.setTextColor(0xFFFF8F3F);
                    }
                    showToast(loaded ? "组件已安装，可立即使用" : "组件已安装，重启应用后生效");
                });
            }

            @Override
            public void onError(final String error) {
                mActivity.runOnUiThread(() -> {
                    if (status != null) {
                        status.setText("下载失败 · 可重试");
                        status.setTextColor(0xFFFF6B6B);
                    }
                    btn.setText("重试");
                    btn.setAlpha(1f);
                    btn.setBackgroundResource(R.drawable.btn_bundle_action_bg);
                    btn.setOnClickListener(v ->
                            startBundleDownload(dialog, bundleId, btn, status));
                });
            }
        };
    }

    /** 下载并安装单个组件，进度写入该行状态文本 */
    private void startBundleDownload(final AlertDialog dialog, final String bundleId,
                                     final android.widget.TextView btn,
                                     final android.widget.TextView status) {
        // TextView 无 enabled 视觉反馈，且置 false 仍会收点击，故清监听 + 降透明度
        btn.setOnClickListener(null);
        btn.setAlpha(0.6f);
        btn.setText("下载中…");
        if (status != null) {
            status.setText("准备下载…");
            status.setTextColor(0xFFFF8F3F);
        }
        com.orange.playerlibrary.tool.NativeLibManager.download(mContext, bundleId,
                buildBundleInstallCallback(dialog, bundleId, btn, status));
    }

    /** 已支持组件的解压后总占用（提示文案用） */
    private static long totalRawSize() {
        long sum = 0;
        for (com.orange.playerlibrary.tool.NativeLibManager.BundleInfo b
                : com.orange.playerlibrary.tool.NativeLibManager.getBundles()) {
            if (com.orange.playerlibrary.tool.NativeLibManager.isSupported(b.id)) {
                sum += b.rawSize();
            }
        }
        return sum;
    }
    
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
     * 显示 ASR 安装指南（与 {@link #showOcrInstallGuide()} 对等）。
     *
     * <p>宿主没引 sherpa 模块时 App 内无解，但 SDK 使用者需要知道该加什么依赖；
     * 此前只弹一句「未安装 ASR 引擎模块」，既没说清缺什么也没给下一步。
     */
    private void showAsrInstallGuide() {
        String message = com.orange.playerlibrary.speech.SherpaAvailabilityChecker
                .getMissingDependenciesMessage();

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
        
        // RIGHT 而非 CENTER：本布局是 layout_gravity="end" 的右侧面板，
        // 只有 RIGHT 才会施加滑入动效与毛玻璃（见 DialogUtils）。
        // 用 CENTER 时两者都静默失效，面板生硬弹出且背后无模糊。
        final AlertDialog dialog = DialogUtils.showCustomDialog(mActivity, dialogView,
                DialogUtils.DialogPosition.RIGHT, null, null);
        
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
            // 失败可能是组件未下载（so 不再随 APK 分发），也可能是语言包缺失，
            // 两者给的操作完全不同，需分别提示
            if (!com.orange.playerlibrary.ocr.OcrAvailabilityChecker.isOcrTranslateAvailable()) {
                showToast("文字识别组件未下载，请先下载");
                showNativeLibsDialog();
            } else {
                showToast("OCR 初始化失败，请检查语言包是否已安装");
                showOcrLanguagePackHint(sourceLang);
            }
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
        final Runnable[] stopPolling = {null};

        mMainHandler.postDelayed(() -> {
            if (!downloadCompleted[0]) {
                // MLKit 不报进度：显示基于「模型目录真实字节增长」的进度
                progressDialog.showWithRealProgress("正在下载翻译模型");
                // 识别流程里不做「0 字节」判定：识别本身可能本来就无需下载，
                // 误报会打断识别；这里只展示进度，失败交给下面的 onError
                stopPolling[0] = startMlKitDownloadProgressPolling(progressDialog,
                        com.orange.playerlibrary.ai.ProgressiveTranslator.mapToMlKitCode(sourceLang),
                        com.orange.playerlibrary.ai.ProgressiveTranslator.mapToMlKitCode(targetLang),
                        null);
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
                    if (stopPolling[0] != null) {
                        stopPolling[0].run();
                    }
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
                    if (stopPolling[0] != null) {
                        stopPolling[0].run();
                    }
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
    
}

