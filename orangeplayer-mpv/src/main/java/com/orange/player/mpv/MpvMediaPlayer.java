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
    private boolean surfaceAttached;
    private Surface attachedSurface;
    private String attachedSurfaceName;
    private boolean loadIssued;
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
        maybeLoadFile();
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

    /** surfaceChanged 时更新尺寸（宿主渲染层调用） */
    public void updateSurfaceSize(int width, int height) {
        if (mpv != null && surfaceAttached) {
            try {
                mpv.setPropertyString("android-surface-size", width + "x" + height);
            } catch (Exception ignored) {
            }
        }
    }

    private void detachSurfaceInternal() {
        if (mpv != null && surfaceAttached) {
            try {
                mpv.setOptionString("force-window", "no");
                mpv.setPropertyString("vo", "gpu");
                mpv.detachSurface();
            } catch (Exception ignored) {
            }
            surfaceAttached = false;
            attachedSurface = null;
        }
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
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
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
        }
    }

    @Override
    public void release() {
        // 共享实例策略：正常空闲退出（无加载/未 prepared）可复用；
        // 播放中被切换走则 mpv 停在"文件残留"状态，其异步 vo teardown/
        // re-init 会在下次会话的 detach 窗口触发 Missing surface pointer
        // fatal（真机实测：第二次切回 mpv 报错黑屏）。标记需重建，
        // 下次 initVideoPlayer 时销毁重建，保证每次会话从干净 idle 起步。
        if (loadIssued || prepared) {
            sNeedsRebuild = true;
        }
        stop();
        detachSurfaceInternal();
        // 共享实例不 destroy（内核切换复用；进程退出回收）
    }

    private static volatile boolean sNeedsRebuild;

    /** 上次会话是否非空闲退出（需要重建实例） */
    public static boolean needsRebuild() {
        return sNeedsRebuild;
    }

    public static void clearRebuild() {
        sNeedsRebuild = false;
    }

    @Override
    public void reset() {
        stop();
        currentPositionMs = 0;
        durationMs = 0;
        prepared = false;
        completed = false;
        loadIssued = false;
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
