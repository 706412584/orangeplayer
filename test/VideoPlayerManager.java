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

    /** 当前内核，init() 与 selectEngine() 共用；默认 IJK */
    private String mEngine = PlayerConstants.ENGINE_IJK;

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
        this.mVideoController = new OrangeVideoController(activity);

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
            // 保存的内核不可用（被卸载 / 未下载）→ 退回系统播放器，避免静默回退
            debug("init: 内核 " + mEngine + " 不可用，回退系统播放器");
            mEngine = PlayerConstants.ENGINE_DEFAULT;
            selectEngine(mEngine);
        }

        // 预览（拖动进度条显示小窗）
        this.mVideoController.setPreViewEnabled(true);
        this.mVideoView.setVideoController(this.mVideoController);

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
        if (this.mVideoView != null && this.mVideoView.isPlaying()) {
            this.mVideoView.onVideoPause();
        }
    }
    
    /**
     * 处理Activity onResume
     */
    public void onVideoResume() {
        if (this.mVideoView != null) {
            this.mVideoView.resume();
        }
    }
    
    /**
     * 释放播放器资源
     */
    public void release() {
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

    // ===================== 字幕 =====================

    /**
     * 加载字幕文件。
     */
    public void loadSubtitle(String url) {
        if (mVideoController == null) {
            debug("loadSubtitle: 播放器未初始化");
            return;
        }
        mVideoController.loadSubtitle(url);
    }

    /**
     * 字幕开关。
     */
    public void toggleSubtitle() {
        if (mVideoController == null) {
            debug("toggleSubtitle: 播放器未初始化");
            return;
        }
        mVideoController.toggleSubtitle();
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