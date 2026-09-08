package com.orange.player.mpv;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.shuyu.gsyvideoplayer.model.GSYModel;

import java.io.FileDescriptor;
import java.util.List;
import java.util.Map;

import dev.jdtech.mpv.MPVLib;
import tv.danmaku.ijk.media.player.AbstractMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.MediaInfo;

/**
 * libmpv → IMediaPlayer 语义桥接层。
 *
 * 继承 AbstractMediaPlayer（IJK 提供的监听器管理基类），GSY 全链路
 * （prepareVideo/startPlayLogic/进度回调）依赖非 null 的 IMediaPlayer。
 *
 * 事件映射结论（spike S4 桩验证 + S2 真机验证，见
 * experiments/orange-player-mpv/docs/S4-event-mapping.md）：
 *
 * - MPV_EVENT_FILE_LOADED        → notifyOnPrepared
 * - MPV_EVENT_END_FILE           → eof → notifyOnCompletion / 加载失败 → notifyOnError
 * - MPV_EVENT_VIDEO_RECONFIG     → notifyOnVideoSizeChanged（读 width/height）
 * - 属性 time-pos/duration/pause → 拉取式 API 缓存字段
 *
 * 时序约束（spike S2 真机实测）：
 * - MPVLib.create 后必须 init()，且在 loadfile 之前
 * - surface attach 后 force-window=yes；surfaceChanged 设 android-surface-size
 * - idle=once + 延迟 loadfile（prepareAsync 只缓存 URL，attach 时加载）
 */
public class MpvMediaPlayer extends AbstractMediaPlayer implements MPVLib.EventObserver {

    private static final String TAG = "MpvMediaPlayer";

    private MPVLib mpv;

    // 播放状态缓存（GSY 拉取式 API）
    private volatile long currentPositionMs;
    private volatile long durationMs;
    private volatile int videoWidth, videoHeight;
    private volatile int sarNum = 1, sarDen = 1;
    private volatile boolean prepared;
    private volatile boolean completed;
    private String dataSource;
    boolean surfaceAttached;
    private Surface attachedSurface;
    String attachedSurfaceName;
    private boolean loadIssued;
    // vo fatal 自愈重载进行中（loadfile replace 的旧文件 end-file 不上报）
    private boolean recovering;
    // 自愈重载完成后的回跳进度
    private long pendingSeekMs;
    // vo fatal 快速恢复已尝试（vid=auto 失败后才走 loadfile 全量重载）
    private boolean voBroken;
    // surface 主动退场标记（detachSurfaceInternal 置位，attach 清除）。
    // 退场期间 mpv 不该有视频帧输出诉求，playback-restart 的轨校验跳过，
    // 避免退场瞬间把合法的"视频暂无输出"误判为 fatal
    private boolean surfaceTearingDown;
    private volatile int bufferedPercent;
    private float speed = 1.0f;
    private boolean looping;

    // ===== 生命周期 =====

    public boolean create(Context context) {
        try {
            mpv = MPVLib.create(context);
            if (mpv == null) {
                Log.e(TAG, "MPVLib.create returned null");
                return false;
            }
            mpv.addObserver(this);

            // 前置选项（S2 实测顺序：create → 前置选项 → init → 后置选项）
            // hwdec=auto-copy：拷贝模式下 MediaCodec 输出到 CPU 缓冲再上传 GL，
            // 不依赖解码时已 attach 的 surface（GSY 的 showDisplay 晚于 prepareAsync，
            // 真机实测 direct 模式会 "Both surface and native_window are NULL"）
            mpv.setOptionString("hwdec", "auto-copy");
            mpv.setOptionString("vo", "gpu");
            mpv.setOptionString("ao", "opensles");
            mpv.setOptionString("config", "no");

            mpv.init();

            // 后置选项
            mpv.setOptionString("force-window", "no");
            mpv.setOptionString("idle", "once");

            // 属性观察（拉取式缓存）
            mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
            mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
            mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            mpv.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
            mpv.observeProperty("width", MPVLib.MpvFormat.MPV_FORMAT_INT64);
            mpv.observeProperty("height", MPVLib.MpvFormat.MPV_FORMAT_INT64);
            mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "create failed", t);
            return false;
        }
    }

    public void destroy() {
        if (mpv != null) {
            try {
                mpv.detachSurface();
                mpv.destroy();
            } catch (Exception ignored) {
            }
            mpv = null;
        }
    }

    // ===== IMediaPlayer 数据源 =====

    @Override
    public void setDataSource(Context context, Uri uri) {
        this.dataSource = uri.toString();
        loadIssued = false;
    }

    @Override
    public void setDataSource(Context context, Uri uri, Map<String, String> headers) {
        this.dataSource = uri.toString();
        loadIssued = false;
    }

    @Override
    public void setDataSource(FileDescriptor fd) {
        // mpv 不支持 FD 直传；URL 由宿主转为文件路径
    }

    @Override
    public void setDataSource(String path) {
        this.dataSource = path;
        // 共享实例换源（GSY 每次 prepare 都走 initVideoPlayer→setDataSource）：
        // 作废上一源的加载状态，等 surface attach 后重新 loadfile
        loadIssued = false;
    }

    @Override
    public String getDataSource() {
        return dataSource;
    }

    @Override
    public void prepareAsync() {
        maybeLoadFile();
    }

    /**
     * loadfile 必须等 surface attach（真机实测：无 surface 时 vo/gpu fatal
     * "Missing surface pointer" 且后续不可恢复）。GSY 的 prepareAsync 与
     * surfaceCreated 时序不定，两边都触发本方法，先到者只记录，条件齐备才加载。
     */
    private synchronized void maybeLoadFile() {
        if (mpv == null || loadIssued || dataSource == null || !surfaceAttached) {
            return;
        }
        mpv.command(new String[]{"loadfile", dataSource});
        loadIssued = true;
        Log.d(TAG, "loadfile issued: " + dataSource);
    }

    // ===== Surface（GSY 经 setSurface/setDisplay 传入） =====

    @Override
    public void setSurface(Surface surface) {
        if (mpv == null) return;
        if (surface == null || !surface.isValid()) {
            detachSurfaceInternal();
            return;
        }
        // 同一 surface 重复推送（GSY 补推/反取补推/全屏重设可能重叠）：
        // 幂等跳过，避免 attach→detach→attach 往返引发的 vo 重建
        // "Missing surface pointer"（真机实测：播放中往返后 mpv 无法恢复出画）。
        // 引用判等不可靠：GSY 每次可能用新包装对象包同一 SurfaceTexture，
        // 故按底层 SurfaceTexture 的 toString name（SurfaceTexture@hex）判同。
        if (surfaceAttached && sameSurfaceSource(surface, attachedSurface)) {
            return;
        }
        if (surfaceAttached) {
            detachSurfaceInternal();
        }
        try {
            mpv.attachSurface(surface);
            mpv.setOptionString("force-window", "yes");
        } catch (Throwable t) {
            Log.e(TAG, "attachSurface failed", t);
            surfaceAttached = false;
            attachedSurface = null;
            attachedSurfaceName = null;
            return;
        }
        surfaceAttached = true;
        attachedSurface = surface;
        attachedSurfaceName = surfaceName(surface);
        // 主动退场结束（detach 时曾 vid=no）：恢复视频轨。vid=auto 重选轨 +
        // seek relative 0 强制 demuxer 立即送关键帧（否则要等下一个 video
        // packet，实测 1s+ 空窗），触发 vo reinit + playback-restart 出画。
        // 解码器全程未销毁，音频不断——这是"接近无缝"的关键路径。
        if (surfaceTearingDown) {
            surfaceTearingDown = false;
            Log.d(TAG, "surface re-attached after teardown, restore vid=auto");
            try {
                mpv.setPropertyString("vid", "auto");
                if (loadIssued) {
                    // vid=auto 的轨重选本身已触发 lavf refresh seek（内部立即
                    // 定位+解码关键帧），无需再叠加 seek relative 0 —— 双重
                    // seek 会串行执行两遍定位（实测 0.7s+ 的出画延迟来源）。
                }
            } catch (Exception ignored) {
            }
            return;
        }
        maybeLoadFile();
        // 重绑/换 surface 后强制出帧：暂停状态下 mpv 不主动重绘，vo 已在新
        // surface 上重建但最后一帧未呈现（Android 16 全屏切换实测：重绑后
        // 画面仍黑，因为暂停中 vo 重启不触发 playmsg/refresh）。video-sync=
        // display-resample 场景下 seek 到当前位置等价于"刷新当前帧"。
        if (!loadIssued || prepared) {
            try {
                mpv.command(new String[]{"seek", "0", "relative"});
            } catch (Exception ignored) {
            }
        }
    }

    /** 两个 Surface 是否指向同一底层 SurfaceTexture */
    private boolean sameSurfaceSource(Surface a, Surface b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        String na = surfaceName(a);
        String nb = surfaceName(b);
        if (na == null || nb == null) return false;
        // 仅当两者都来自 SurfaceTexture（name=android.graphics.SurfaceTexture@hex）
        // 且底层对象相同才视为同源；PlaceholderSurface 等其他源退回引用判等。
        if (na.startsWith(SURFACE_TEXTURE_PREFIX) && nb.startsWith(SURFACE_TEXTURE_PREFIX)) {
            return na.equals(nb);
        }
        return false;
    }

    private static final String SURFACE_TEXTURE_PREFIX = "android.graphics.SurfaceTexture@";

    /** 提取 Surface 的底层对象名（Surface.toString: Surface(name=android.graphics.SurfaceTexture@hex)/@0x...） */
    private String surfaceName(Surface s) {
        try {
            String str = s.toString();
            int eq = str.indexOf('(');
            int close = str.indexOf(')', eq);
            if (eq >= 0 && close > eq + 1) {
                String inner = str.substring(eq + 1, close);
                int nameEq = inner.indexOf('=');
                if (nameEq >= 0 && nameEq + 1 < inner.length()) {
                    return inner.substring(nameEq + 1);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        if (holder != null) {
            setSurface(holder.getSurface());
        } else {
            detachSurfaceInternal();
        }
    }

    // ===== 画面比例（mpv 原生渲染选项；GSY TextureView 尺寸机制对 mpv 无效） =====
    // GSY 的裁剪/拉伸依赖渲染器按 TextureView 尺寸做矩阵适配，mpv 的 vo 层永远
    // 按视频 aspect 在 surface 内居中 fit（keepaspect=on）。因此 mpv 场景：
    //  - 全屏拉伸 = keepaspect=no（变形拉满 surface）
    //  - 全屏裁剪 = keepaspect=yes + video-zoom 等比放大到溢出（居中裁边）
    // 其余档位（默认/16:9/4:3）复位 keepaspect + zoom=0，容器黑边由 GSY
    // TextureView 尺寸机制呈现。
    private boolean mCropScale;
    private int mSurfaceW, mSurfaceH;

    /** 切换画面比例模式（宿主经 MpvPlayerManager 反射调用；档名字面量与宿主一致） */
    public void setVideoScaleMode(String mode) {
        if (mpv == null) return;
        try {
            if ("全屏拉伸".equals(mode)) {
                mpv.setOptionString("keepaspect", "no");
                mCropScale = false;
                Log.d(TAG, "video scale: 全屏拉伸 (keepaspect=no)");
            } else if ("全屏裁剪".equals(mode)) {
                mpv.setOptionString("keepaspect", "yes");
                mCropScale = true;
                updateCropZoom();
            } else {
                mpv.setOptionString("keepaspect", "yes");
                mpv.setPropertyString("video-zoom", "0");
                mCropScale = false;
                Log.d(TAG, "video scale: " + mode + " (默认 fit)");
            }
        } catch (Exception e) {
            Log.e(TAG, "setVideoScaleMode failed", e);
        }
    }

    /** 裁剪档按 surface/视频尺寸算等比放大系数（surface 尺寸到达时调用） */
    private void updateCropZoom() {
        if (!mCropScale || mpv == null || mSurfaceW <= 0 || mSurfaceH <= 0
                || videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        try {
            double sx = (double) mSurfaceW / videoWidth;
            double sy = (double) mSurfaceH / videoHeight;
            double coef = Math.max(sx, sy);
            if (coef <= 1.0) {
                // surface 不小于视频时无需放大（fit 已满）
                mpv.setPropertyString("video-zoom", "0");
                return;
            }
            double zoom = Math.log(coef) / Math.log(2);
            mpv.setPropertyString("video-zoom", String.valueOf(zoom));
            Log.d(TAG, "crop zoom=" + zoom + " (surface=" + mSurfaceW + "x" + mSurfaceH
                    + ", video=" + videoWidth + "x" + videoHeight + ")");
        } catch (Exception e) {
            Log.e(TAG, "updateCropZoom failed", e);
        }
    }

    /** surfaceChanged 时更新尺寸（宿主渲染层调用） */
    public void updateSurfaceSize(int width, int height) {
        mSurfaceW = width;
        mSurfaceH = height;
        if (mpv != null && surfaceAttached) {
            try {
                mpv.setPropertyString("android-surface-size", width + "x" + height);
            } catch (Exception ignored) {
            }
        }
        // 裁剪档的放大系数依赖 surface 尺寸，尺寸到位后重算
        updateCropZoom();
    }

    private void detachSurfaceInternal() {
        if (mpv != null && surfaceAttached) {
            try {
                // mpvRx PR#329 时序纪律：detach 前先摘视频轨（vid=no）。
                // 视频轨激活状态下 wid 空窗内的任何 vo 重配都会 fatal
                // （Missing surface pointer → deselect track）。主动退场让
                // mpv 全程无视频输出诉求，新 surface attach 后恢复。
                mpv.setPropertyString("vid", "no");
                mpv.setOptionString("force-window", "no");
                mpv.detachSurface();
            } catch (Exception ignored) {
            }
            surfaceAttached = false;
            attachedSurface = null;
            surfaceTearingDown = true;
        }
    }

    /**
     * 主动退场入口（宿主 surfaceDestroyed 时序纪律调用）：视频轨先摘除再
     * detach，与 detachSurfaceInternal 的区别是 vid=no 为本方法的固定步骤
     * 且不受 surfaceAttached 状态限制（未 attach 时也置 vid=no 兜底）。
     */
    public void teardownForSurfaceChange() {
        if (mpv == null) return;
        try {
            mpv.setPropertyString("vid", "no");
        } catch (Exception ignored) {
        }
        detachSurfaceInternal();
    }

    // ===== Anime4K 超分（spike S2 实测：change-list 逐个 add） =====

    /** 替换整条 glsl-shaders 链（先 clear 再逐个 add，mpv 实时生效） */
    /**
     * 整体替换 glsl-shaders 链（原子 set，实时生效）。
     * 不用 change-list clear+add：libmpv 实测 change-list 没有 clear 操作，
     * 旧 shader 不会移除、只会在链尾叠加，最终画面由最后一个 shader 决定
     * （真机验证：切档后画面保持上一档）。glsl-shaders 属性值为冒号分隔文件列表，
     * 空串=清空整链。
     */
    public void setShaderChain(List<String> shaderPaths) {
        if (mpv == null) return;
        try {
            String value = "";
            if (shaderPaths != null && !shaderPaths.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String p : shaderPaths) {
                    if (sb.length() > 0) sb.append(':');
                    sb.append(p);
                }
                value = sb.toString();
            }
            mpv.setPropertyString("glsl-shaders", value);
            Log.d(TAG, "setShaderChain: '" + value + "'");
        } catch (Exception e) {
            Log.e(TAG, "setShaderChain failed", e);
        }
    }

    // ===== MPV 事件（映射 IMediaPlayer 语义） =====

    @Override
    public void event(int eventId) {
        if (mpv == null) {
            // 实例已销毁（重建）：忽略旧实例迟到事件，避免误报到新会话监听器
            return;
        }
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
            prepared = true;
            notifyOnPrepared();
            // 自愈重载完成：恢复到重载前保存的进度（loadfile 后 seek 才作用于
            // 新文件；command 异步排队，紧跟 loadfile 的 seek 会打到旧文件流）
            if (recovering && pendingSeekMs > 0 && mpv != null) {
                Log.d(TAG, "recovery file loaded, seek back to " + pendingSeekMs + "ms");
                mpv.command(new String[]{"seek", String.valueOf(pendingSeekMs / 1000.0),
                        "absolute"});
                pendingSeekMs = 0;
            }
            recovering = false;
            voBroken = false;
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
            // 自愈重载进行中：loadfile replace 对旧文件发的 end-file 是流程
            // 内部事件，不映射 completed/error（实测会被 GSY 当完成弹完成界面）
            if (recovering) {
                Log.d(TAG, "END_FILE during recovery, ignored");
                return;
            }
            if (!prepared) {
                // 本会话从未 loadfile（stop/release 残留或旧会话迟到的 END_FILE，
                // 共享实例切内核时 stop 的异步事件会打到新会话监听器上）→ 忽略，
                // 不能当作本次加载失败上报。
                if (!loadIssued) {
                    Log.d(TAG, "END_FILE ignored: no loadfile in this session (stale stop)");
                    return;
                }
                // 加载失败（S2 实测 reason 4 等）
                notifyOnError(IMediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
            } else {
                completed = true;
                notifyOnCompletion();
            }
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG) {
            readVideoSize();
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART) {
            // 渲染（重新）出画信号：loadfile 后 vo 出首帧、seek 完成、surface 重建后恢复
            Log.d(TAG, "first video frame after restart shown");
            // vid=auto 快速恢复成功的时机兜底：恢复后 video-reconfig 可能早于
            // width 属性就绪（实测 voBroken 清除日志未出现），restart 时轨已
            // 有效则同样清除 voBroken，让下次 fatal 重新从快速恢复起步
            if (voBroken && mpv != null) {
                Integer rw = mpv.getPropertyInt("width");
                Integer rh = mpv.getPropertyInt("height");
                if (rw != null && rw > 0 && rh != null && rh > 0) {
                    Log.d(TAG, "vid=auto recovered (restart check), clear voBroken");
                    voBroken = false;
                }
            }
            // vo fatal（Missing surface pointer）自愈检测：fatal 后 mpv 不会进入
            // idle（idle 事件仅在初始发过一次），而是 deselect 视频轨继续放音频
            // （"playback restart complete ... video=eof"）。每次 playback-restart
            // 后校验视频轨有效性，轨丢失则恢复。时序纪律生效后（teardown 主动
            // 退场）正常不应再 fatal，本检测降级为兜底。
            if (surfaceTearingDown) {
                // 退场中（vid=no）：轨无视频输出是预期状态，跳过校验，
                // 否则 vid=no 会被误判为轨丢失触发恢复循环
                return;
            }
            if (loadIssued && dataSource != null && mpv != null) {
                Integer w = mpv.getPropertyInt("width");
                Integer h = mpv.getPropertyInt("height");
                if ((w == null || w <= 0) || (h == null || h <= 0)) {
                    Log.w(TAG, "vo fatal dropped video track (width=" + w + ", height="
                            + h + "), vid=auto fast recover at " + currentPositionMs + "ms");
                    // 快速恢复：deselect 的视频轨用 vid=auto 重新选择，
                    // demuxer 缓存未失效，无网络重载（loadfile replace 需
                    // 重新开流约 0.5s+，实测黑屏延迟不可接受）。
                    // vo fatal 标记 voBroken：其后的 vo reinit 若再 fatal，
                    // fallback 到 loadfile 全量重载。
                    if (!voBroken) {
                        voBroken = true;
                        mpv.command(new String[]{"set", "vid", "auto"});
                        // vid=auto 靠等下一个 video packet 才重选轨（实测 1s+）。
                        // 紧跟 seek relative 0 强制 demuxer 立即送关键帧，
                        // 触发 select track + vo reinit + playback-restart，
                        // 恢复时间从 1s+ 压到 200ms 内。
                        mpv.command(new String[]{"seek", "0", "relative"});
                    } else {
                        // 上次 vid=auto 后 vo 仍 fatal（surface 未就绪窗口），
                        // 全量重载兜底（recovering 流程：END_FILE 静默 +
                        // FILE_LOADED 回跳进度）
                        voBroken = false;
                        recovering = true;
                        pendingSeekMs = currentPositionMs;
                        loadIssued = false;
                        mpv.command(new String[]{"loadfile", dataSource, "replace"});
                        loadIssued = true;
                    }
                }
            }
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN) {
            // 核心终止（实测路径：stop 后 idle=once 时 EOF 走 end-file → shutdown）。
            // 之后的 loadfile 命令会被静默丢弃（无任何事件/日志），核心无法复用，
            // 标记重建，下次 initVideoPlayer 销毁重建实例。
            Log.e(TAG, "MPV_EVENT_SHUTDOWN: core terminated, mark rebuild");
            sCoreShutdown = true;
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG) {
            readVideoSize();
            // vid=auto 快速恢复后视频轨重选成功（width>0）：清除 voBroken，
            // 后续再 fatal 时重新从快速恢复起步
            if (videoWidth > 0 && videoHeight > 0 && voBroken) {
                Log.d(TAG, "vid=auto recovered, video track alive (" + videoWidth + "x"
                        + videoHeight + "), clear voBroken");
                voBroken = false;
            }
        }
    }

    private void readVideoSize() {
        if (mpv == null) return;
        Integer w = mpv.getPropertyInt("width");
        Integer h = mpv.getPropertyInt("height");
        if (w != null && h != null && w > 0 && h > 0) {
            if (w != videoWidth || h != videoHeight) {
                videoWidth = w;
                videoHeight = h;
                notifyOnVideoSizeChanged(w, h, sarNum, sarDen);
            }
        }
    }

    @Override
    public void eventProperty(String property) { }

    @Override
    public void eventProperty(String property, long value) {
        if ("width".equals(property)) videoWidth = (int) value;
        else if ("height".equals(property)) videoHeight = (int) value;
    }

    @Override
    public void eventProperty(String property, double value) {
        if ("time-pos".equals(property)) {
            currentPositionMs = (long) (value * 1000);
        } else if ("duration".equals(property)) {
            durationMs = (long) (value * 1000);
        } else if ("demuxer-cache-time".equals(property) && durationMs > 0) {
            int pct = (int) Math.min(100, value * 1000 * 100 / durationMs);
            if (pct != bufferedPercent) {
                bufferedPercent = pct;
                notifyOnBufferingUpdate(pct);
            }
        }
    }

    @Override
    public void eventProperty(String property, boolean value) {
        if ("eof-reached".equals(property) && value && prepared && !completed) {
            completed = true;
            notifyOnCompletion();
        }
    }

    @Override
    public void eventProperty(String property, String value) { }

    // ===== IMediaPlayer 控制语义 =====

    @Override
    public void start() {
        if (mpv != null) mpv.setPropertyBoolean("pause", false);
    }

    @Override
    public void pause() {
        if (mpv != null) mpv.setPropertyBoolean("pause", true);
    }

    @Override
    public void stop() {
        if (mpv != null) {
            try {
                mpv.command(new String[]{"stop"});
            } catch (Exception ignored) {
            }
            loadIssued = false;
            prepared = false;
            completed = false;
            recovering = false;
            pendingSeekMs = 0;
            voBroken = false;
        }
    }

    /** MPV_EVENT_SHUTDOWN：核心已终止（stop 后 idle=once 的 EOF 路径实测触发）。
     *  核心终止后无法复用，标记重建标志，下次 initVideoPlayer 销毁重建。 */
    private static volatile boolean sCoreShutdown;

    static boolean isCoreShutdown() {
        return sCoreShutdown;
    }

    static void clearCoreShutdown() {
        sCoreShutdown = false;
    }

    @Override
    public void release() {
        // 共享实例策略：停止 + detach，实例本身保留复用（so 常驻）。
        // 不销毁重建：release 后紧接的重播（全屏/同视频重载会立即 setUp +
        // startPlayLogic，同步同线程）会马上重新 attach 新 surface 并 loadfile，
        // 此时若销毁重建，旧实例刚初始化的 vo 被废弃、新实例在新 surface 上
        // 重建 vo 会竞态 Missing surface pointer（Android 16 实测全屏黑屏）。
        // 旧会话残留 END_FILE 误报由 event() 的 loadIssued 守卫兜底。
        stop();
        detachSurfaceInternal();
        // 共享实例不 destroy（内核切换复用；进程退出回收）
    }

    @Override
    public void reset() {
        stop();
        currentPositionMs = 0;
        durationMs = 0;
        prepared = false;
        completed = false;
        loadIssued = false;
        recovering = false;
        pendingSeekMs = 0;
        voBroken = false;
    }

    @Override
    public boolean isPlaying() {
        if (mpv == null) return false;
        Boolean paused = mpv.getPropertyBoolean("pause");
        return paused != null && !paused && prepared && !completed;
    }

    @Override
    public void seekTo(long timeMs) {
        if (mpv == null) return;
        // S2 实测：flags 必须单一值
        mpv.command(new String[]{"seek", String.valueOf(timeMs / 1000.0), "absolute"});
        if (completed) completed = false;
    }

    @Override
    public int getVideoWidth() {
        return videoWidth;
    }

    @Override
    public int getVideoHeight() {
        return videoHeight;
    }

    @Override
    public int getVideoSarNum() {
        return sarNum;
    }

    @Override
    public int getVideoSarDen() {
        return sarDen;
    }

    @Override
    public long getCurrentPosition() {
        return currentPositionMs;
    }

    @Override
    public long getDuration() {
        return durationMs;
    }

    public int getBufferedPercentage() {
        return bufferedPercent;
    }

    @Override
    public void setVolume(float left, float right) {
        if (mpv != null) mpv.setPropertyDouble("volume", (left + right) * 50);
    }

    public void setSpeed(float speed) {
        this.speed = speed;
        if (mpv != null) mpv.setPropertyDouble("speed", speed);
    }

    public float getSpeed() {
        return speed;
    }

    @Override
    public void setLooping(boolean looping) {
        this.looping = looping;
        if (mpv != null) mpv.setPropertyString("loop-file", looping ? "inf" : "no");
    }

    @Override
    public boolean isLooping() {
        return looping;
    }

    // ===== IMediaPlayer 剩余接口（mpv 场景空实现） =====

    @Override
    public void setScreenOnWhilePlaying(boolean screenOn) { }

    @Override
    public int getAudioSessionId() {
        return 0;
    }

    @Override
    public MediaInfo getMediaInfo() {
        return null;
    }

    @Override
    public void setLogEnabled(boolean enable) { }

    @Override
    public boolean isPlayable() {
        return true;
    }

    @Override
    public void setAudioStreamType(int streamtype) { }

    @Override
    public void setKeepInBackground(boolean keepInBackground) { }

    @Override
    public void setWakeMode(Context context, int mode) { }

    @Override
    public tv.danmaku.ijk.media.player.misc.ITrackInfo[] getTrackInfo() {
        return new tv.danmaku.ijk.media.player.misc.ITrackInfo[0];
    }

    @Override
    public void setDataSource(tv.danmaku.ijk.media.player.misc.IMediaDataSource mediaDataSource) {
        // mpv 经 URL 播放，DataSource 直传不支持
    }
}
