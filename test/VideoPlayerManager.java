package com.orange.player;

import android.app.Activity;
import android.view.ViewGroup;
import com.orange.playerlibrary.OrangeVideoController;
import com.orange.playerlibrary.OrangevideoView;
import com.orange.playerlibrary.PlayerSettingsManager;
import com.orange.playerlibrary.PlayerConstants;
import com.orange.playerlibrary.DanmakuControllerImpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.graphics.Color;
import android.view.View;

/**
 * 播放器管理器
 * 封装播放器的创建、播放控制、生命周期管理等核心操作
 * 简化上层调用，统一管理播放器状态
 * 
 * 适配 iApp v3 手机编程
 */
public class VideoPlayerManager {
    
    private static volatile VideoPlayerManager instance;
    
    private Activity mActivity;
    private OrangevideoView mVideoView;
    private OrangeVideoController mVideoController;
    private ViewGroup mParentContainer;
    private String urlimage = "";
    
    // 弹幕控制器
    private DanmakuControllerImpl mDanmakuController;

    /** 当前内核，init() 与 selectEngine() 共用；默认 ExoPlayer（纯 Java 解码，无需下载 so） */
    private String mEngine = PlayerConstants.ENGINE_EXO;

    /** 当前音量百分比。SDK 只有 setter，读取靠这里记录。 */
    private int mVolumePercent = 100;

    private static String TAG = "VideoPlayerManager";
    
    /**
     * 单例模式获取管理器实例
     */
    public static VideoPlayerManager getInstance() {
        if (instance == null) {
            synchronized (VideoPlayerManager.class) {
                if (instance == null) {
                    instance = new VideoPlayerManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 初始化播放器
     * @param activity 关联的Activity
     * @param parent 播放器要添加到的父布局容器
     */
    public void init(Activity activity, ViewGroup parent) {
        if (activity == null || parent == null) {
            throw new IllegalArgumentException("Activity和父容器不能为空");
        }
        release();

        this.mActivity = activity;
        this.mParentContainer = parent;

        // 必须最先做：探测「so 是否随 APK 内置」并加载已下载的组件。
        // 内核可用性判定（PlayerEngineAvailability → NativeLibManager.isInstalled）
        // 依赖 install() 填的 sBundled/sFilesDir，不调则一律判「未安装」，
        // selectEngine 会把所有内核都拒掉。宿主（iApp）不会自己调这个，得由门面负责。
        try {
            com.orange.playerlibrary.tool.NativeLibManager.install(activity);
        } catch (Throwable t) {
            debug("NativeLibManager.install 失败: " + t.getMessage());
        }

        // ===== 先创建视图，再使用视图 =====
        // 原实现把 mVideoView.setKeepVideoPlaying(true) 写在了 new OrangevideoView()
        // 之前，首次 init 必 NPE（release() 刚把 mVideoView 置 null）。这里按依赖顺序重排。
        this.mVideoView = new OrangevideoView(activity);

        // controller 必须【复用】SDK 构造时已建好的那个，不要自己 new 再 setVideoController。
        //
        // 为什么：OrangevideoView 构造里已创建 controller（initOrangeComponents），
        // 此时 setVideoController(另一个实例) 会走 setVideoController 的替换分支 →
        // previous.releaseOnReplaced() → VideoEventManager.release() → stopOcrTranslate()。
        // 而 stopOcrTranslate 检测到 Exo 默认的 forceTextureViewMode=true（OrangevideoView:707）
        // 就会 release 播放器 + releaseAllVideos + 重新 setUp + 切回 SurfaceView，
        // 并弹「已切换回 SurfaceView 模式」。真机后果：进/出画中画后渲染状态错乱，
        // 画面仍在但所有点击失效。
        //
        // 官方 demo 就是这么做的（app/MainActivity.java:787 先 getVideoController，
        // 为 null 才兜底新建），所以它的画中画一切正常。
        this.mVideoController = this.mVideoView.getVideoController();
        if (this.mVideoController == null) {
            // SDK 未创建时才兜底自建
            this.mVideoController = new OrangeVideoController(activity);
            this.mVideoView.setVideoController(this.mVideoController);
        }

        // ===== 设置项 =====
        com.orange.playerlibrary.M3U8AdManager.getInstance(activity).setEnabled(true); // 开启 M3U8 去广告
        // 下载路径：应用私有目录，避免外部存储权限问题
        java.io.File privateDir = new java.io.File(activity.getFilesDir(), "MyCustomDownload");
        com.orange.playerlibrary.download.SimpleDownloadManager.getInstance(activity)
                .setDownloadPath(privateDir.getAbsolutePath());
        PlayerSettingsManager settings = PlayerSettingsManager.getInstance(activity);
        settings.setDownloadEnabled(true);      // 下载功能
        settings.setMemoryPlayEnabled(true);    // 记忆播放

        // 记忆播放开关作用于本实例
        this.mVideoView.setKeepVideoPlaying(true);

        // 内核：沿用用户上次的选择；没有则用默认。都走 selectEngine 的可用性门禁，
        // 避免把未下载的内核写进偏好（那样播放时会静默回退到系统内核）。
        String saved = settings.getPlayerEngine();
        if (saved != null && !saved.isEmpty()) {
            mEngine = saved;
        }
        if (!selectEngine(mEngine)) {
            // 保存的内核不可用（被卸载 / 未下载）→ 退回 ExoPlayer（纯 Java，无需 so），
            // 与 app demo 的按需下载策略一致；exo 也缺失时才退系统播放器。
            debug("init: 内核 " + mEngine + " 不可用，回退 ExoPlayer");
            mEngine = PlayerConstants.ENGINE_EXO;
            if (!selectEngine(mEngine)) {
                mEngine = PlayerConstants.ENGINE_DEFAULT;
                selectEngine(mEngine);
            }
        }

        // 预览（拖动进度条显示小窗）
        this.mVideoController.setPreViewEnabled(true);

        // 挂载到父容器
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        this.mParentContainer.addView(this.mVideoView, params);

        // 初始化控制器组件（默认非直播模式）
        this.mVideoController.addDefaultControlComponent("视频标题", false);

        // 弹幕控制器
        initDanmakuController();

        // 画中画辅助（含「进程重建后恢复播放位置」）
        initPiPHelper();
        // 轮询 PiP 状态（iApp 宿主拿不到 onPictureInPictureModeChanged 回调）
        startPiPPolling();
    }
    
    /**
     * 初始化弹幕控制器
     */
    private void initDanmakuController() {
        if (mVideoView != null && mVideoController != null) {
            mDanmakuController = new DanmakuControllerImpl(mActivity);
            mDanmakuController.attachToContainer(mVideoView);
            mVideoController.setDanmakuController(mDanmakuController);
        }
    }
    
    /**
     * 设置播放内核。
     *
     * <p>会先做可用性判定：宿主没引入该内核（{@code isSupported} 为 false）时直接拒绝，
     * 未下载（{@code needsDownload} 为 true）时也拒绝并提示——否则设置成功但播放时
     * 静默回退到系统播放器，用户以为在用某内核其实不是。
     *
     * <p>可用内核：{@code default}（系统）、{@code exo}、{@code ijk}、{@code ali}、{@code mpv}。
     * mpv 需 API 26+。
     *
     * @return true 表示已切换；false 表示该内核当前不可用（原因见日志）
     */
    public boolean selectEngine(String engine) {
        if (engine == null || engine.isEmpty()) {
            debug("selectEngine: 内核名为空");
            return false;
        }
        if (!com.orange.playerlibrary.utils.PlayerEngineAvailability.isSupported(engine)) {
            debug("selectEngine: 本版本未集成内核 " + engine);
            return false;
        }
        if (com.orange.playerlibrary.utils.PlayerEngineAvailability.needsDownload(engine)) {
            debug("selectEngine: 内核 " + engine + " 尚未下载，请先在「扩展包管理」中下载");
            return false;
        }

        mEngine = engine;
        if (mActivity != null) {
            PlayerSettingsManager.getInstance(mActivity).setPlayerEngine(engine);
        }
        if (mVideoView != null) {
            mVideoView.selectPlayerFactory(engine);
        }
        debug("selectEngine: 已切换到 " + engine);
        return true;
    }

    /**
     * 当前内核名。
     */
    public String getEngine() {
        return mEngine;
    }

    /**
     * 列出可用内核（供 iApp 生成选择菜单）。
     *
     * @return 形如 ["系统播放器","ExoPlayer","IJK播放器（需先下载）"]，含可用性标注
     */
    public String[] listEngines() {
        String[][] all = {
            {PlayerConstants.ENGINE_DEFAULT, "系统播放器"},
            {PlayerConstants.ENGINE_EXO, "ExoPlayer"},
            {PlayerConstants.ENGINE_IJK, "IJK播放器"},
            {PlayerConstants.ENGINE_ALI, "阿里云播放器"},
            {PlayerConstants.ENGINE_MPV, "MPV播放器"},
        };
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String[] e : all) {
            String id = e[0];
            if (!com.orange.playerlibrary.utils.PlayerEngineAvailability.isSupported(id)) {
                continue; // 宿主未引入，不列出
            }
            String label = e[1];
            if (com.orange.playerlibrary.utils.PlayerEngineAvailability.needsDownload(id)) {
                label += "（需先下载）";
            }
            out.add(label);
        }
        return out.toArray(new String[0]);
    }
    
    /**
     * 设置视频源
     * @param videoUrl 视频播放地址
     * @param videoTitle 视频标题
     */
    public void setVideoSource(String videoUrl, String videoTitle) {
        checkInitState();
        this.mVideoView.setUrl(videoUrl);
        this.mVideoController.setVideoTitle(videoTitle);
    }
    
    /**
     * 设置视频源（带请求头）
     * @param videoUrl 视频播放地址
     * @param head 请求头
     * @param videoTitle 视频标题
     */
    public void setVideoSource(String videoUrl, Map<String, String> head, String videoTitle) {
        checkInitState();
        this.mVideoView.setUrl(videoUrl, head);
        this.mVideoController.setVideoTitle(videoTitle);
    }
    
    /**
     * 设置播放列表
     * @param videoList 视频列表
     */
    public void setVideoList(ArrayList<HashMap<String, Object>> videoList) {
        checkInitState();
        this.mVideoController.setVideoList(videoList);
    }
    
    /**
     * 设置视频封面
     */
    public void setVideoImage(String url) {
        this.urlimage = url;
    }
    
    /**
     * 开始播放
     */
    public void start() {
        checkInitState();
        clearDanmakus();
        if (!this.mVideoView.isPlaying()) {
            this.mVideoView.start();
        }
    }
    
    /**
     * 嗅探播放
     */
    public void startSniffing() {
        checkInitState();
        this.mVideoView.startSniffing();
    }
    
    /**
     * 设置加载动画 (1-28)
     */
    public void setLoading(int i) {
        if (this.mVideoController != null) {
            this.mVideoController.setLoading(i);
        }
    }
    
    /**
     * 暂停播放
     */
    public void pause() {
        checkInitState();
        if (this.mVideoView.isPlaying()) {
            this.mVideoView.pause();
        }
    }
    
    /**
     * 停止播放
     */
    public void stop() {
        checkInitState();
        clearDanmakus();
        this.mVideoView.pause();
        this.mVideoView.release();
    }
    
    /**
     * 跳转进度
     * @param position 目标进度（毫秒）
     */
    public void seekTo(int position) {
        checkInitState();
        if (position >= 0 && position <= this.mVideoView.getDuration()) {
            this.mVideoView.seekTo(position);
        }
    }
    
    /**
     * 设置播放速度
     * @param speed 播放速度
     */
    public void setPlaybackSpeed(float speed) {
        checkInitState();
        if (speed > 0) {
            this.mVideoView.setSpeed(speed);
            OrangevideoView.setSpeeds(speed);
        }
    }
    
    /**
     * 切换全屏/非全屏
     */
    public void toggleFullScreen() {
        checkInitState();
        if (this.mVideoView.isFullScreen()) {
            this.mVideoController.stopFullScreen();
        } else {
            this.mVideoController.startFullScreen();
        }
    }
    
    /**
     * 是否全屏
     */
    public boolean isFullScreen() {
        checkInitState();
        return this.mVideoView.isFullScreen();
    }

    /**
     * 竖屏全屏开关。iApp 侧的方法名。
     *
     * <p>{@code true} 进入竖屏全屏（保持竖屏、隐藏系统栏），{@code false} 退出。
     *
     * @return 切换后的状态
     */
    public boolean setFullScreenVertical(boolean vertical) {
        checkInitState();
        // 进出必须走同一套状态机（OrangevideoView → CustomFullscreenHelper）。
        // 混用 OrangeVideoController.exitPortraitFullScreen() 会失效：
        // 两者各有独立的 mIsPortraitFullScreen 标志，controller 侧的仍是 false，
        // exit 会在 `if (!mIsPortraitFullScreen) return;` 处早退，卡在竖屏全屏出不来。
        if (vertical) {
            if (this.mVideoView.isPortraitFullScreen()) {
                return true;
            }
            // 已在横屏全屏时不能直接进竖屏：helper 内有 `if (mIsFullscreen) return;`。
            // 先退横屏，再进竖屏（SDK 内部切竖屏也是这个顺序）。
            if (this.mVideoView.isFullScreen()) {
                this.mVideoView.stopFullScreen();
            }
            this.mVideoView.startPortraitFullScreen();
        } else {
            if (this.mVideoView.isPortraitFullScreen()) {
                this.mVideoView.stopPortraitFullScreen();
            }
        }
        return this.mVideoView.isPortraitFullScreen();
    }

    /**
     * 是否处于竖屏全屏。
     */
    public boolean isFullScreenVertical() {
        checkInitState();
        return this.mVideoView.isPortraitFullScreen();
    }
    
    /**
     * 设置音量 (0-100)
     */
    public void setVolume(int volume) {
        checkInitState();
        if (volume >= 0 && volume <= 100) {
            // 使用SDK提供的播放器音量API
            this.mVideoView.setPlayerVolumePercent(volume);
            this.mVolumePercent = volume;
        }
    }
    
    /**
     * 获取当前播放进度
     */
    public long getCurrentPosition() {
        checkInitState();
        return this.mVideoView.getCurrentPosition();
    }
    
    /**
     * 获取视频总时长
     */
    public long getDuration() {
        checkInitState();
        return this.mVideoView.getDuration();
    }
    
    /**
     * 设置播放完成监听
     */
    public void setOnPlayCompleteListener(com.orange.playerlibrary.interfaces.OnPlayCompleteListener listener) {
        checkInitState();
        this.mVideoView.setOnPlayCompleteListener(listener);
    }
    
    /**
     * 设置进度更新监听
     */
    public void setOnProgressListener(com.orange.playerlibrary.interfaces.OnProgressListener listener) {
        checkInitState();
        this.mVideoView.setOnProgressListener(listener);
    }
    
    /**
     * 处理Activity onPause
     */
    public void onPause() {
        // 在画中画里时系统也会走 onPause，但小窗本来就该继续播，不能暂停。
        if (handlePiPOnPause()) {
            debug("onPause: 处于画中画，跳过暂停");
            return;
        }
        if (this.mVideoView != null && this.mVideoView.isPlaying()) {
            this.mVideoView.onVideoPause();
        }
    }

    /**
     * 处理Activity onResume
     */
    public void onVideoResume() {
        // 刚从画中画回来时 PiPHelper 已处理（显示控制器、跳过默认恢复），
        // 再 resume 一次会打断它刚恢复好的状态。
        if (handlePiPOnResume()) {
            debug("onVideoResume: 刚从画中画返回，已由 PiPHelper 处理");
            return;
        }
        if (this.mVideoView != null) {
            this.mVideoView.resume();
        }
    }
    
    /**
     * 释放播放器资源
     */
    public void release() {
        // 先停 PiP 轮询，否则 mVideoView 置空后回调还在跑
        stopPiPPolling();
        mPiPHelper = null;
        mLastPiPState = false;

        // 释放弹幕控制器
        if (mDanmakuController != null) {
            mDanmakuController.releaseDanmaku();
            mDanmakuController = null;
        }

        if (this.mVideoView != null) {
            // 销毁前强制落盘播放进度：记忆播放依赖它，漏掉会导致下次进来从 0 开始
            try {
                this.mVideoView.savePlaybackProgress();
            } catch (Throwable t) {
                debug("savePlaybackProgress 失败: " + t.getMessage());
            }
            this.mVideoView.release();
            this.mVideoView = null;
        }
        if (this.mVideoController != null) {
            this.mVideoController = null;
        }
        if (this.mParentContainer != null) {
            this.mParentContainer.removeAllViews();
            this.mParentContainer = null;
        }
        // 音量是实例状态，下一轮 init 的播放器实际是 100，不重置会让 getVolume 返回旧值。
        // mEngine 是用户偏好，保留。
        this.mVolumePercent = 100;
        this.mActivity = null;
    }
    
    /**
     * 检查是否已初始化
     */
    private void checkInitState() {
        if (this.mVideoView == null || this.mVideoController == null) {
            throw new IllegalStateException("播放器未初始化，请先调用init()方法");
        }
    }
    
    /**
     * 获取播放器视图
     */
    public OrangevideoView getVideoView() {
        return this.mVideoView;
    }
    
    /**
     * 获取控制器
     */
    public OrangeVideoController getVideoController() {
        return this.mVideoController;
    }
    
    /**
     * 投屏（DLNA）。iApp 侧的方法名。
     *
     * <p>走 SDK 的 {@code DLNACastManager}（内部反射调用 com.uaoanlao.tv.Screen），
     * 不再直接 {@code new Screen()}：
     * <ul>
     *   <li>硬引用会让本类编译期依赖 UaoanDLNA，宿主不引入就编译不过</li>
     *   <li>SDK 侧已有 R8 keep 规则与可用性判定，绕开它会重现「按钮可点但一点就崩」</li>
     * </ul>
     *
     * @return true 表示已发起投屏
     */
    public boolean openScreenTv() {
        if (mVideoView == null) {
            debug("openScreenTv: 播放器未初始化");
            return false;
        }
        if (!com.orange.playerlibrary.cast.DLNACastManager.isDLNAAvailable()) {
            debug("openScreenTv: 未引入 DLNA 投屏库（UaoanDLNA）");
            return false;
        }
        String url = mVideoView.getUrl();
        if (url == null || url.isEmpty()) {
            debug("openScreenTv: 当前无播放地址，无法投屏");
            return false;
        }
        String title = (mVideoController != null) ? mVideoController.getVideoTitle() : "";

        com.orange.playerlibrary.cast.DLNACastManager.getInstance().init(mActivity);
        com.orange.playerlibrary.cast.DLNACastManager.getInstance()
                .startCast(mActivity, url, title, urlimage);
        debug("openScreenTv: 已发起投屏 " + url);
        return true;
    }

    /**
     * 投屏（旧方法名，保留兼容）。
     * @deprecated 用 {@link #openScreenTv()}
     */
    @Deprecated
    public void ScreenTvOnClickListener() {
        openScreenTv();
    }
    
    /**
     * 添加单个视频
     * @param tile 标题
     * @param url 地址
     * @param is 是否独立视频
     */
    public synchronized void addVideo(String tile, String url, boolean is) {
        if (mVideoController != null) {
            mVideoController.addVideo(tile, url, is);
        }
    }
    
    /**
     * 从JSON对象添加视频
     */
    public void addVideoFromJson(String jsonObjStr) {
        try {
            JSONObject jsonObj = new JSONObject(jsonObjStr);
            String name = jsonObj.getString("name");
            String url = jsonObj.getString("url");
            boolean isSpecial = jsonObj.getBoolean("isSpecial");
            addVideo(name, url, isSpecial);
        } catch (JSONException e) {
            e.printStackTrace();
            debug("解析JSON失败：" + e.getMessage());
        }
    }
    
    /**
     * 从JSON数组批量添加视频
     */
    public void addVideosFromJsonArray(String jsonArrayStr) {
        try {
            JSONArray jsonArray = new JSONArray(jsonArrayStr);
            for (int i = 0; i < jsonArray.length(); i++) {
                addVideoFromJson(jsonArray.getJSONObject(i).toString());
            }
        } catch (JSONException e) {
            e.printStackTrace();
            debug("解析JSON数组失败：" + e.getMessage());
        }
    }
    
    public void addVideosFromJsonArray(JSONArray jsonArray) {
        try {
            for (int i = 0; i < jsonArray.length(); i++) {
                addVideoFromJson(jsonArray.getJSONObject(i).toString());
            }
        } catch (JSONException e) {
            e.printStackTrace();
            debug("解析JSON数组失败：" + e.getMessage());
        }
    }

    /**
     * 从 MacCMS（苹果 CMS）采集接口的选集 JSON 批量添加选集。
     *
     * <p>采集接口返回的 {@code vod_play_url} 形如：
     * <pre>
     * "第01集$url1#第02集$url2$$$第01集$url3#第02集$url4"
     *  └──── 线路0（liangzi）────┘└── 线路1（lzm3u8）──┘
     * </pre>
     * {@code $$$} 分隔线路、{@code #} 分隔剧集、{@code $} 分隔「剧集名」与「播放地址」。
     * 不同线路的同一集地址完全不同，且**并非所有线路都是可播直链**——实测同一采集源里
     * {@code liangzi} 线路返回 HTML 播放页，{@code lzm3u8} 才是真 m3u8。因此必须挑线路，
     * 不能把各线路拼在一起（剧集会重复且混入不可播地址）。
     *
     * <p>调用方式（iApp）：
     * <pre>
     * javax(null, 视频管理器, Playmanager, "addVideosFromMacCmsJson", "String", json文本)
     * </pre>
     * json 文本需含 {@code list[0].vod_play_url} 与（可选）{@code list[0].vod_play_from}。
     *
     * @param jsonStr 采集接口返回的完整 JSON 文本
     * @return 实际添加的集数；解析失败返回 0
     */
    public int addVideosFromMacCmsJson(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            debug("addVideosFromMacCmsJson: 输入为空");
            return 0;
        }
        // JSON 结构提取放在库侧（VodPlayUrlParser.parseJson），那里有单测覆盖
        return addVideosFromMacCmsResult(
                com.orange.playerlibrary.utils.VodPlayUrlParser.parseJson(jsonStr));
    }

    /**
     * 从采集接口的 {@code vod_play_url} / {@code vod_play_from} 原文添加选集。
     *
     * @return 实际添加的集数
     */
    public int addVideosFromMacCmsItem(String vodPlayUrl, String vodPlayFrom) {
        return addVideosFromMacCmsResult(
                com.orange.playerlibrary.utils.VodPlayUrlParser.parse(vodPlayUrl, vodPlayFrom));
    }

    /**
     * 把解析结果写入播放器的选集列表。
     */
    private int addVideosFromMacCmsResult(
            com.orange.playerlibrary.utils.VodPlayUrlParser.Result r) {
        if (r == null || r.isEmpty()) {
            debug("addVideosFromMacCms: 无有效剧集");
            return 0;
        }
        debug("addVideosFromMacCms: 选中线路[" + r.groupIndex + "]=" + r.groupName
                + "，共 " + r.episodes.size() + " 集");
        int added = 0;
        for (int i = 0; i < r.episodes.size(); i++) {
            com.orange.playerlibrary.utils.VodPlayUrlParser.Episode ep = r.episodes.get(i);
            String name = ep.name != null ? ep.name : ("第" + (i + 1) + "集");
            addVideo(name, ep.url, false);
            added++;
        }
        return added;
    }

    /**
     * 解析颜色字符串
     */
    private int parseColorString(String colorStr) {
        try {
            if (colorStr.length() == 4) {
                StringBuilder sb = new StringBuilder("#");
                for (int i = 1; i < 4; i++) {
                    char c = colorStr.charAt(i);
                    sb.append(c).append(c);
                }
                colorStr = sb.toString();
            } else if (colorStr.length() == 5) {
                StringBuilder sb = new StringBuilder("#");
                for (int i = 1; i < 5; i++) {
                    char c = colorStr.charAt(i);
                    sb.append(c).append(c);
                }
                colorStr = sb.toString();
            }
            return Color.parseColor(colorStr);
        } catch (Exception e) {
            e.printStackTrace();
            return Color.WHITE;
        }
    }
    
    /**
     * 从JSON设置弹幕列表
     */
    public void setDanmuListFromJson(String jsonStr) {
        if (mDanmakuController == null) {
            debug("弹幕控制器未初始化");
            return;
        }
        
        try {
            JSONArray jsonArray = new JSONArray(jsonStr);
            List<com.orange.playerlibrary.interfaces.IDanmakuController.DanmakuItem> danmakuList = new ArrayList<>();
            
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject danmakuObj = jsonArray.getJSONObject(i);
                String text = danmakuObj.getString("text");
                String colorStr = danmakuObj.getString("color");
                long timestamp = danmakuObj.getLong("timestamp");
                boolean isSelf = danmakuObj.getBoolean("isSelf");
                int color = parseColorString(colorStr);
                danmakuList.add(new com.orange.playerlibrary.interfaces.IDanmakuController.DanmakuItem(text, color, timestamp, isSelf));
            }
            
            mDanmakuController.setDanmakuData(danmakuList);
        } catch (JSONException e) {
            e.printStackTrace();
            debug("设置弹幕出错:" + e);
        }
    }
    
    public boolean setDanmuListFromJson(JSONArray jsonArray) {
        if (mDanmakuController == null) {
            debug("弹幕控制器未初始化");
            return false;
        }
        
        try {
            List<com.orange.playerlibrary.interfaces.IDanmakuController.DanmakuItem> danmakuList = new ArrayList<>();
            
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject danmakuObj = jsonArray.getJSONObject(i);
                String text = danmakuObj.getString("text");
                String colorStr = danmakuObj.getString("color");
                long timestamp = danmakuObj.getLong("timestamp");
                boolean isSelf = danmakuObj.getBoolean("isSelf");
                int color = parseColorString(colorStr);
                danmakuList.add(new com.orange.playerlibrary.interfaces.IDanmakuController.DanmakuItem(text, color, timestamp, isSelf));
            }
            
            mDanmakuController.setDanmakuData(danmakuList);
            return true;
        } catch (JSONException e) {
            e.printStackTrace();
            debug("设置弹幕出错:" + e);
            return false;
        }
    }
    
    /**
     * 发送弹幕
     */
    public void sendDanmu(String danmu, int color, boolean issf) {
        if (mDanmakuController != null) {
            mDanmakuController.sendDanmaku(danmu, color);
        }
    }
    
    /**
     * 发送弹幕（字符串颜色）
     */
    public void sendDanmu(String danmu, String colorStr, boolean issf) {
        int color = parseColorString(colorStr);
        sendDanmu(danmu, color, issf);
    }
    
    /**
     * 清空弹幕
     */
    public void clearDanmakus() {
        if (mDanmakuController != null) {
            mDanmakuController.clearDanmakus();
        }
    }

    // ===================== 控制器 / 选集 / 扩展包 =====================

    /**
     * 下一集按钮显隐。iApp 侧的方法名。
     *
     * <p>默认由 SDK 依选集列表自动控制，此方法用于强制指定。
     *
     * @return true 表示设置成功
     */
    public boolean setPlayNextVisibility(boolean visible) {
        if (mVideoView == null) {
            debug("setPlayNextVisibility: 播放器未初始化");
            return false;
        }
        com.orange.playerlibrary.component.VodControlView v = mVideoView.getVodControlView();
        if (v == null) {
            debug("setPlayNextVisibility: 当前非点播模式或无控制栏");
            return false;
        }
        v.setPlayNextButtonVisible(visible);
        return true;
    }

    /**
     * 播放下一集（需先通过 addVideo 添加选集）。
     */
    public void playNextEpisode() {
        if (mVideoController == null || mVideoController.getVideoEventManager() == null) {
            debug("playNextEpisode: 播放器未初始化");
            return;
        }
        mVideoController.getVideoEventManager().playNextEpisode();
    }

    /**
     * 播放方式：顺序 / 单集循环 / 列表循环 / 随机。
     *
     * @param mode 见 PlayerSettingsManager 的取值约定
     */
    public void setPlayMode(String mode) {
        if (mActivity == null) {
            debug("setPlayMode: 播放器未初始化");
            return;
        }
        PlayerSettingsManager.getInstance(mActivity).setPlayMode(mode);
    }

    /**
     * 当前播放方式。
     */
    public String getPlayMode() {
        return mActivity != null ? PlayerSettingsManager.getInstance(mActivity).getPlayMode() : null;
    }

    /**
     * 显示控制器。
     */
    public void showController() {
        if (mVideoController == null) {
            debug("showController: 播放器未初始化");
            return;
        }
        mVideoController.show();
    }

    /**
     * 隐藏控制器。
     */
    public void hideController() {
        if (mVideoController == null) {
            debug("hideController: 播放器未初始化");
            return;
        }
        mVideoController.hide();
    }

    /**
     * 控制器是否可见。
     */
    public boolean isControllerShowing() {
        return mVideoController != null && mVideoController.isShowing();
    }

    /**
     * 锁定 / 解锁屏幕（锁定后屏蔽手势与按钮）。
     */
    public void setLocked(boolean locked) {
        if (mVideoController == null) {
            debug("setLocked: 播放器未初始化");
            return;
        }
        mVideoController.setLocked(locked);
    }

    // ===================== 扩展包（按需下载的 so 组件）=====================

    /**
     * 打开扩展包管理面板（下载播放内核 / 语音识别 / OCR / 翻译等组件）。
     */
    public void showNativeLibs() {
        if (mVideoController == null || mVideoController.getVideoEventManager() == null) {
            debug("showNativeLibs: 播放器未初始化");
            return;
        }
        mVideoController.getVideoEventManager().showNativeLibsDialog();
    }

    /**
     * 弹出内核选择对话框（供 iApp 等宿主直接 javax 调用，避免在脚本里写 java 块）。
     * 未下载的核点击后自动跳转扩展包面板。
     */
    public void showEngineChooser() {
        if (mActivity == null) {
            debug("showEngineChooser: 播放器未初始化");
            return;
        }
        final String[] ids = {
            PlayerConstants.ENGINE_DEFAULT, PlayerConstants.ENGINE_EXO,
            PlayerConstants.ENGINE_IJK, PlayerConstants.ENGINE_ALI, PlayerConstants.ENGINE_MPV,
        };
        final String[] names = { "系统播放器", "ExoPlayer", "IJK播放器", "阿里云播放器", "MPV播放器" };
        java.util.List<String> labelList = new java.util.ArrayList<String>();
        final java.util.List<String> idList = new java.util.ArrayList<String>();
        for (int i = 0; i != ids.length; i++) {
            if (!com.orange.playerlibrary.utils.PlayerEngineAvailability.isSupported(ids[i])) {
                continue;
            }
            String label = names[i];
            if (ids[i].equals(mEngine)) {
                label += "（当前）";
            } else if (com.orange.playerlibrary.utils.PlayerEngineAvailability.needsDownload(ids[i])) {
                label += "（需下载扩展包）";
            }
            labelList.add(label);
            idList.add(ids[i]);
        }
        if (labelList.isEmpty()) {
            debug("showEngineChooser: 没有可用内核");
            return;
        }
        final Activity act = mActivity;
        new android.app.AlertDialog.Builder(act)
            .setTitle("选择播放内核")
            .setItems(labelList.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    String id = idList.get(which);
                    if (com.orange.playerlibrary.utils.PlayerEngineAvailability.needsDownload(id)) {
                        android.widget.Toast.makeText(act, id + " 需先下载扩展组件", android.widget.Toast.LENGTH_SHORT).show();
                        showNativeLibs();
                    } else {
                        boolean ok = selectEngine(id);
                        android.widget.Toast.makeText(act,
                                ok ? "已切换 " + id + "（下次播放生效）" : "切换失败",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ===================== 字幕 =====================

    /**
     * 加载字幕文件（.srt / .ass），支持 http(s) URL 与本地路径。
     */
    public void loadSubtitle(String url) {
        if (mVideoController == null) {
            debug("loadSubtitle: 播放器未初始化");
            return;
        }
        mVideoController.loadSubtitle(url);
    }

    /**
     * 加载字幕并回调结果。加载成功后会自动打开显示开关并持久化，
     * 否则用户看到「加载成功但没字幕」。
     */
    public void loadSubtitle(String url,
            final com.orange.playerlibrary.subtitle.SubtitleManager.OnSubtitleLoadListener listener) {
        if (mVideoController == null) {
            debug("loadSubtitle: 播放器未初始化");
            if (listener != null) {
                listener.onLoadFailed("播放器未初始化");
            }
            return;
        }
        mVideoController.loadSubtitle(url,
                new com.orange.playerlibrary.subtitle.SubtitleManager.OnSubtitleLoadListener() {
            @Override
            public void onLoadSuccess(int count) {
                // 同步持久化开关与控制器状态：用户可能没手动开「显示字幕」
                try {
                    if (mActivity != null) {
                        PlayerSettingsManager.getInstance(mActivity).setSubtitleEnabled(true);
                    }
                    mVideoController.startSubtitle();
                } catch (Throwable t) {
                    debug("loadSubtitle: 自动开启字幕失败 " + t.getMessage());
                }
                if (listener != null) {
                    listener.onLoadSuccess(count);
                }
            }

            @Override
            public void onLoadFailed(String error) {
                if (listener != null) {
                    listener.onLoadFailed(error);
                }
            }
        });
    }

    /**
     * 字幕开关（同时持久化偏好）。
     */
    public void toggleSubtitle() {
        if (mVideoController == null) {
            debug("toggleSubtitle: 播放器未初始化");
            return;
        }
        mVideoController.toggleSubtitle();
    }

    /**
     * 字幕当前是否启用。
     */
    public boolean isSubtitleEnabled() {
        return mVideoController != null && mVideoController.isSubtitleEnabled();
    }

    // ===================== 去广告 =====================

    /**
     * M3U8 去广告开关（本次运行 + 持久化）。
     */
    public void setAdRemovalEnabled(boolean enabled) {
        if (mActivity == null) {
            debug("setAdRemovalEnabled: 播放器未初始化");
            return;
        }
        com.orange.playerlibrary.M3U8AdManager.getInstance(mActivity).setEnabled(enabled);
        PlayerSettingsManager.getInstance(mActivity).setAdRemovalEnabled(enabled);
    }

    /**
     * 去广告是否开启。
     */
    public boolean isAdRemovalEnabled() {
        return mActivity != null && PlayerSettingsManager.getInstance(mActivity).isAdRemovalEnabled();
    }

    // ===================== 播放历史 =====================

    /**
     * 取播放历史列表。
     *
     * @param limit 最多返回条数
     * @return 形如 [{url, title, position, duration}, ...]，供 iApp 直接展示
     */
    public List<Map<String, Object>> getPlayHistory(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (mActivity == null) {
            return out;
        }
        List<com.orange.playerlibrary.history.PlayHistory> list =
                com.orange.playerlibrary.history.PlayHistoryManager.getInstance(mActivity).getHistoryList(limit);
        if (list == null) {
            return out;
        }
        for (com.orange.playerlibrary.history.PlayHistory h : list) {
            Map<String, Object> m = new HashMap<>();
            m.put("url", h.getVideoUrl());
            m.put("title", h.getVideoTitle());
            m.put("position", h.getPosition());
            m.put("duration", h.getDuration());
            m.put("lastPlayTime", h.getLastPlayTime());
            out.add(m);
        }
        return out;
    }

    /**
     * 删除某条播放历史。
     */
    public void deleteHistory(String url) {
        if (mActivity == null) {
            debug("deleteHistory: 播放器未初始化");
            return;
        }
        com.orange.playerlibrary.history.PlayHistoryManager.getInstance(mActivity).deleteHistory(url);
    }

    /**
     * 清空播放历史。
     */
    public void clearHistory() {
        if (mActivity == null) {
            debug("clearHistory: 播放器未初始化");
            return;
        }
        com.orange.playerlibrary.history.PlayHistoryManager.getInstance(mActivity).clearAll();
    }

    // ===================== 画中画 =====================

    /**
     * PiP 生命周期辅助。必须在 init() 后、进入 PiP 前创建。
     *
     * <p>为什么需要它：进/出画中画时系统会走 Activity 的 onPause/onResume/onStop
     * 与 onPictureInPictureModeChanged，而 PiP 期间【不能】把视频当后台处理去暂停，
     * 退出时又要按「用户点恢复按钮」还是「点 X 关闭」分别处理。SDK 把这段状态机
     * 收在 PiPHelper 里，宿主必须接上，否则进小窗会停、退出后状态错乱。
     */
    private com.orange.playerlibrary.PiPHelper mPiPHelper;

    /**
     * 初始化 PiP 辅助并尝试恢复上次的播放位置（进程被系统回收后重建时用）。
     */
    private void initPiPHelper() {
        if (mActivity == null || mVideoView == null) {
            return;
        }
        try {
            mPiPHelper = new com.orange.playerlibrary.PiPHelper(mActivity, mVideoView);
            long restorePosition = mPiPHelper.checkPiPRestore(getCurrentUrl());
            if (restorePosition > 0) {
                // 等播放器 PREPARED 后再 seek。SDK 遍历监听器列表时同步解绑会引发
                // 竞态，所以解绑推迟到本次通知结束之后（官方 demo 同做法）。
                com.orange.playerlibrary.interfaces.OnStateChangeListener restoreListener =
                        new com.orange.playerlibrary.interfaces.OnStateChangeListener() {
                    @Override
                    public void onPlayerStateChanged(int playerState) {
                    }

                    @Override
                    public void onPlayStateChanged(int playState) {
                        if (playState != PlayerConstants.STATE_PREPARED) {
                            return;
                        }
                        final com.orange.playerlibrary.interfaces.OnStateChangeListener self = this;
                        mVideoView.post(new Runnable() {
                            @Override
                            public void run() {
                                mVideoView.removeOnStateChangeListener(self);
                            }
                        });
                        mVideoView.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                mVideoView.seekTo((int) restorePosition);
                                mPiPHelper.clearPendingSeekPosition();
                            }
                        }, 200);
                    }
                };
                mVideoView.addOnStateChangeListener(restoreListener);
            }
        } catch (Throwable t) {
            debug("initPiPHelper 失败: " + t.getMessage());
        }
    }

    /**
     * 进/出画中画时调用。
     *
     * <p>宿主若能拿到 Activity 的 onPictureInPictureModeChanged 就转发进来；
     * 拿不到时（iApp 生成的 Activity 无法覆写）由下面的轮询自动触发。
     */
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        if (mPiPHelper != null) {
            mPiPHelper.onPictureInPictureModeChanged(isInPictureInPictureMode, getCurrentUrl());
        }
    }

    /** 上次轮询到的 PiP 状态，用于识别「进/出」跳变 */
    private boolean mLastPiPState = false;

    /** 轮询任务句柄，release() 时移除 */
    private Runnable mPiPPollTask;

    private static final long PIP_POLL_INTERVAL_MS = 400L;

    /**
     * 启动 PiP 状态轮询。
     *
     * <p>为什么用轮询：Activity 的 onPictureInPictureModeChanged 是 protected 回调，
     * 而 iApp 生成的 Activity 由宿主框架产出、无法继承覆写；Android 也没有公开的
     * 「PiP 模式变化」监听注册接口。轮询一个 boolean 的成本可忽略，但能保证
     * 进/出小窗都被感知到——漏掉会导致退出小窗后播放位置不保存、控制器不显示。
     */
    private void startPiPPolling() {
        if (mVideoView == null || mPiPPollTask != null) {
            return;
        }
        mPiPPollTask = new Runnable() {
            @Override
            public void run() {
                if (mActivity == null || mVideoView == null) {
                    mPiPPollTask = null;
                    return;
                }
                try {
                    boolean inPiP = android.os.Build.VERSION.SDK_INT
                            >= android.os.Build.VERSION_CODES.N
                            && mActivity.isInPictureInPictureMode();
                    if (inPiP != mLastPiPState) {
                        mLastPiPState = inPiP;
                        debug("PiP 状态变化: " + inPiP);
                        onPictureInPictureModeChanged(inPiP);
                    }
                } catch (Throwable t) {
                    debug("PiP 轮询异常: " + t.getMessage());
                }
                if (mVideoView != null) {
                    mVideoView.postDelayed(this, PIP_POLL_INTERVAL_MS);
                }
            }
        };
        mVideoView.postDelayed(mPiPPollTask, PIP_POLL_INTERVAL_MS);
    }

    private void stopPiPPolling() {
        if (mVideoView != null && mPiPPollTask != null) {
            mVideoView.removeCallbacks(mPiPPollTask);
        }
        mPiPPollTask = null;
    }

    /**
     * 当前数据源地址（PiP 恢复靠它比对是否是同一个视频）。
     */
    public String getCurrentUrl() {
        return mVideoView != null ? mVideoView.getUrl() : null;
    }

    // ===================== 种子 / 磁力播放 =====================

    /**
     * 种子播放回调（宿主友好版）。
     *
     * <p>为什么不直接用 SDK 的 {@code TorrentPlayerManager.TorrentCallback}：
     * 那个接口的父类 {@code TorrentPlayerManager} 实现了 {@code org.libtorrent4j.AlertListener}。
     * 宿主若直接 new 它，类加载器要解析该父接口——当宿主没引入 libtorrent4j 时，
     * 解析失败会抛 {@code NoClassDefFoundError}，而且是【类加载期】抛，
     * 连 SDK 自己的「组件未安装」提示都来不及走到，直接崩。
     *
     * <p>本接口不引用 torrent 包的任何类型，宿主只依赖门面即可安全使用。
     */
    public interface TorrentListener {
        /** 解析完成，proxyUrl 是可直接播放的本地代理地址 */
        void onReady(String proxyUrl, String fileName, long fileSize);

        /** 出错（含「组件未下载」这类可恢复原因） */
        void onError(String error);

        /**
         * 磁力链接元数据解析进度（可选，用于「正在获取种子信息 12/30 秒」这类提示）。
         * 默认空实现，宿主不需要就不必覆写。
         */
        default void onMagnetResolving(int elapsedSeconds, int totalSeconds) {
        }

        /**
         * 种子文件下载/缓冲进度（可选）。默认空实现。
         */
        default void onTorrentLoading(int elapsedSeconds, int totalSeconds) {
        }
    }

    /**
     * 种子组件是否可用（Java 类已引入 且 so 已下载/内置）。
     *
     * <p>未就绪时返回 false，宿主应引导用户去「扩展包下载」，
     * 而不是直接调用下面的方法。
     */
    public boolean isTorrentAvailable() {
        try {
            return com.orange.playerlibrary.torrent.TorrentSupport.isTorrentPlayable();
        } catch (Throwable t) {
            // 连 TorrentSupport 都加载不了（宿主没引入 libtorrent4j）时也走这里
            return false;
        }
    }

    /**
     * 种子组件未就绪的原因（可直接展示给用户）。
     */
    public String getTorrentUnavailableReason() {
        try {
            return com.orange.playerlibrary.torrent.TorrentSupport.getJlibtorrentMissingReason();
        } catch (Throwable t) {
            return "种子组件未引入";
        }
    }

    /**
     * 播放磁力链接（magnet:?xt=urn:btih:...）。
     *
     * @param saveDir 种子数据落地目录，null 时用应用私有目录
     */
    public void playMagnet(String magnetUri, java.io.File saveDir, final TorrentListener listener) {
        if (mVideoView == null) {
            notifyTorrentError(listener, "播放器未初始化");
            return;
        }
        if (!isTorrentAvailable()) {
            notifyTorrentError(listener, getTorrentUnavailableReason());
            return;
        }
        // 到这里才引用 TorrentCallback：此时类一定可解析
        mVideoView.playMagnet(magnetUri, resolveTorrentDir(saveDir), adaptTorrentCallback(listener));
    }

    /**
     * 播放本地 .torrent 文件。
     */
    public void playTorrent(java.io.File torrentFile, java.io.File saveDir, final TorrentListener listener) {
        if (mVideoView == null) {
            notifyTorrentError(listener, "播放器未初始化");
            return;
        }
        if (!isTorrentAvailable()) {
            notifyTorrentError(listener, getTorrentUnavailableReason());
            return;
        }
        mVideoView.playTorrent(torrentFile, resolveTorrentDir(saveDir), adaptTorrentCallback(listener));
    }

    /**
     * 停止种子任务并释放代理。
     */
    public void stopTorrent() {
        if (mVideoView != null) {
            try {
                mVideoView.stopTorrent();
            } catch (Throwable t) {
                debug("stopTorrent: " + t.getMessage());
            }
        }
    }

    /**
     * 把宿主回调适配成 SDK 回调；只在前置检查通过后调用。
     *
     * <p>必须把接口的**全部**方法都实现，包括 default 方法——哪怕只写空体。
     * 原因：iApp 对每个本地依赖文件独立 dex（一个 aar = 一个 dex），且 dexer 以
     * min-api&lt;24 运行，接口 default 方法会被脱糖成「abstract 方法 + Iface$-CC.$default$xxx」。
     * 当实现类与接口落在不同 dex 时，dexer 看不到 default 信息、不会生成转发桥接，
     * 于是调用方（如 TorrentDelegate$1 转发 onTorrentLoading）会抛 AbstractMethodError。
     * 只有在本类里显式覆写，方法体才一定存在于本 dex。
     */
    private com.orange.playerlibrary.torrent.TorrentPlayerManager.TorrentCallback
            adaptTorrentCallback(final TorrentListener listener) {
        return new com.orange.playerlibrary.torrent.TorrentPlayerManager.TorrentCallback() {
            @Override
            public void onReady(String proxyUrl, String fileName, long fileSize) {
                if (listener != null) {
                    listener.onReady(proxyUrl, fileName, fileSize);
                }
            }

            @Override
            public void onBufferProgress(int bufferedPieces, int totalPieces, long bufferedBytes) {
            }

            @Override
            public void onDownloadProgress(int progress, long downloadSpeed, long uploadSpeed) {
            }

            @Override
            public void onError(String error) {
                if (listener != null) {
                    listener.onError(error);
                }
            }

            // ↓ 以下两个是接口的 default 方法，必须显式覆写（见方法注释）

            @Override
            public void onMagnetResolving(int elapsedSeconds, int totalSeconds) {
                if (listener != null) {
                    listener.onMagnetResolving(elapsedSeconds, totalSeconds);
                }
            }

            @Override
            public void onTorrentLoading(int elapsedSeconds, int totalSeconds) {
                if (listener != null) {
                    listener.onTorrentLoading(elapsedSeconds, totalSeconds);
                }
            }
        };
    }

    private void notifyTorrentError(TorrentListener listener, String msg) {
        debug("torrent: " + msg);
        if (listener != null) {
            listener.onError(msg);
        }
    }

    /**
     * 种子数据目录：宿主没指定就用应用私有目录，避免外部存储权限问题。
     */
    private java.io.File resolveTorrentDir(java.io.File saveDir) {
        if (saveDir != null) {
            return saveDir;
        }
        if (mActivity != null) {
            return new java.io.File(mActivity.getFilesDir(), "TorrentDownload");
        }
        return null;
    }

    /**
     * PiP 感知的 onPause。返回 true 表示「已在 PiP 中，跳过宿主的暂停逻辑」。
     */
    public boolean handlePiPOnPause() {
        return mPiPHelper != null && mPiPHelper.handleOnPause();
    }

    /**
     * PiP 感知的 onResume。返回 true 表示「刚从 PiP 回来，已处理，跳过宿主默认恢复」。
     */
    public boolean handlePiPOnResume() {
        return mPiPHelper != null && mPiPHelper.handleOnResume();
    }

    /**
     * PiP 感知的 onStop。返回 true 表示「PiP 相关（进小窗或用户点 X），跳过宿主默认处理」。
     */
    public boolean handlePiPOnStop() {
        return mPiPHelper != null && mPiPHelper.handleOnStop();
    }

    /**
     * 进入画中画（Android 8.0+）。
     *
     * <p>注意：SDK 的 PiP 恢复监听器会在恢复时自解绑，宿主不要在
     * onPlayStateChanged 回调里同步解绑监听器（会触发 SDK 侧遍历竞态）。
     */
    public boolean enterPiP() {
        if (mActivity == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            debug("enterPiP: 需要 Android 8.0+");
            return false;
        }
        // 必须先置 SDK 的「正在进入 PiP」标记：onPause 时 pausePlayback 靠它
        // （配合 isInPictureInPictureMode()）跳过暂停。漏掉会导致一进小窗视频就停。
        boolean marked = false;
        if (mVideoView != null) {
            mVideoView.setEnteringPiPMode(true);
            marked = true;
        }
        try {
            android.app.PictureInPictureParams params = new android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(new android.util.Rational(16, 9))
                    .build();
            mActivity.enterPictureInPictureMode(params);
            return true;
        } catch (Exception e) {
            debug("enterPiP 失败: " + e.getMessage());
            if (marked) {
                mVideoView.setEnteringPiPMode(false); // 进入失败要复位，否则下次 onPause 不暂停
            }
            return false;
        }
    }

    // ===================== 音量 / 倍速（补充读取接口）=====================

    /**
     * 当前音量（0-100）。
     *
     * <p>SDK 只提供 setter，读取由本类自行记录。
     */
    public int getVolume() {
        return mVolumePercent;
    }

    /**
     * 当前倍速。
     */
    public float getPlaybackSpeed() {
        return mVideoController != null ? mVideoController.getSpeed() : 1.0f;
    }

    /**
     * 静音开关。
     */
    public void setMute(boolean mute) {
        if (mVideoController == null) {
            debug("setMute: 播放器未初始化");
            return;
        }
        mVideoController.setMute(mute);
    }

    /**
     * 是否静音。
     */
    public boolean isMute() {
        return mVideoController != null && mVideoController.isMute();
    }

    // ===================== 嗅探 =====================

    /**
     * 嗅探结果是否可用（是否已启用嗅探视图）。
     */
    public void stopSniffing() {
        if (mVideoController == null) {
            debug("stopSniffing: 播放器未初始化");
            return;
        }
        mVideoController.hideSniffingView();
    }

    /**
     * 调试日志
     */
    public static void debug(Object obj) {
        android.util.Log.d(TAG, String.valueOf(obj));
    }
}