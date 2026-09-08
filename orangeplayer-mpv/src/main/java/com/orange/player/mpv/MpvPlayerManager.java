package com.orange.player.mpv;

import android.content.Context;
import android.os.Message;
import android.view.Surface;
import android.view.TextureView;

import com.shuyu.gsyvideoplayer.cache.ICacheManager;
import com.shuyu.gsyvideoplayer.model.GSYModel;
import com.shuyu.gsyvideoplayer.model.VideoOptionModel;
import com.shuyu.gsyvideoplayer.player.BasePlayerManager;

import java.util.List;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/**
 * mpv 第五内核的 GSY 管理器。
 *
 * 注册纪律（experiments/orange-player-mpv README）：
 * - 仅用户显式选择，永不自动回退到达
 * - surface 生命周期遵循 spike S2 实测时序
 */
public class MpvPlayerManager extends BasePlayerManager {

    private static final String TAG = "MpvPlayerManager";

    private Context context;
    private MpvMediaPlayer mediaPlayer;
    // GSY 时序：surfaceCreated（setDisplay→showDisplay）可能先于 prepareAsync，
    // 此时 mediaPlayer 尚未创建——缓存 surface 待 initVideoPlayer 后补绑
    private static Surface pendingSurface;

    // mpv 实例进程内共享（so 常驻；GSY 每次 initVideoPlayer 会 new 管理器）
    private static MpvMediaPlayer sSharedPlayer;
    // 最近一次 showDisplay 的 TextureView SurfaceTexture 源（同源幂等，避免 detach/attach 往返）
    private static android.graphics.SurfaceTexture sLastSurfaceTexture;

    @Override
    public IMediaPlayer getMediaPlayer() {
        return mediaPlayer;
    }

    @Override
    public void initVideoPlayer(Context context, Message msg, List<VideoOptionModel> optionModelList, ICacheManager cacheManager) {
        this.context = context.getApplicationContext();

        // MPV_EVENT_SHUTDOWN 后核心无法复用（loadfile 被静默丢弃），必须重建；
        // 首次创建同理。其余情况复用共享实例（release 只 stop+detach，实例保留；
        // 紧接的重播直接重新 attach + loadfile，避免销毁重建的 vo 竞态）。
        if (sSharedPlayer == null || MpvMediaPlayer.isCoreShutdown()) {
            if (sSharedPlayer != null) {
                android.util.Log.d(TAG, "initVideoPlayer: mpv 核心已 shutdown，重建共享实例");
                sSharedPlayer.destroy();
                sSharedPlayer = null;
                MpvMediaPlayer.clearCoreShutdown();
            }
            sSharedPlayer = new MpvMediaPlayer();
            if (!sSharedPlayer.create(this.context)) {
                sSharedPlayer = null;
                throw new IllegalStateException("libmpv create failed");
            }
        }
        // 上一次会话可能残留监听器状态
        sSharedPlayer.resetListeners();
        mediaPlayer = sSharedPlayer;

        // GSY 时序补偿：surface 可能先于 prepareAsync 到达（showDisplay 缓存），
        // 在 loadfile 前先绑定，否则 mpv 渲染器 Missing surface pointer。
        // 宿主换内核期间渲染 View 可能已重建，缓存的 pendingSurface 可能指向
        // 已销毁的旧 SurfaceTexture（attach 死 texture → EGL createSurface
        // 永久 fatal），绑定前必须用活渲染 View 校验/覆盖。
        android.util.Log.d(TAG, "initVideoPlayer: pendingSurface=" + pendingSurface
                + ", valid=" + (pendingSurface != null && pendingSurface.isValid()));
        if (pendingSurface != null && pendingSurface.isValid()) {
            mediaPlayer.setSurface(pendingSurface);
            pendingSurface = null;
        }
        // 冷启动 surface 回调早于 player 的场景由宿主 startPlayLogic 后调
        // bindRenderProxySurface(getRenderProxy()) 补推（见 OrangevideoView）；
        // 补推的活 surface 优先级高于上面的 pendingSurface（后者可能已死）

        GSYModel gsyModel = (GSYModel) msg.obj;
        try {
            mediaPlayer.setLooping(gsyModel.isLooping());

            String url = gsyModel.getUrl();
            // 代理缓存：mpv 无法播 proxy URL 时由宿主处理；直接用原 URL
            if (cacheManager != null && gsyModel.isCache()) {
                cacheManager.doCacheLogic(context, mediaPlayer, url,
                        gsyModel.getMapHeadData(), gsyModel.getCachePath());
            }
            mediaPlayer.setDataSource(url);

            initSuccess(gsyModel);
        } catch (Exception e) {
            android.util.Log.e(TAG, "initVideoPlayer failed", e);
        }
    }

    @Override
    public void showDisplay(Message msg) {
        if (msg == null) return;
        Surface surface = null;
        if (msg.obj instanceof Surface) {
            surface = (Surface) msg.obj;
        } else if (msg.obj instanceof TextureView) {
            TextureView tv = (TextureView) msg.obj;
            if (tv.getSurfaceTexture() != null) {
                // TextureView 源：每次包装出的 Surface 对象都不同，直接交给
                // MpvMediaPlayer.setSurface 会命中不了幂等 → detach→attach 往返，
                // mpv 在播放中途重建 vo 会 fatal（真机实测全屏后黑屏：
                // "Error opening/initializing the selected video_out"）。
                // 同 SurfaceTexture 源视为已 attach，跳过（尺寸变化走 onSurfaceSizeChanged）。
                if (tv.getSurfaceTexture() == sLastSurfaceTexture
                        && sLastSurfaceTexture != null) {
                    android.util.Log.d(TAG, "showDisplay: 同 SurfaceTexture 源，跳过重复 attach");
                    return;
                }
                sLastSurfaceTexture = tv.getSurfaceTexture();
                surface = new Surface(tv.getSurfaceTexture());
            }
        }
        if (surface == null) {
            sLastSurfaceTexture = null;
            // null surface：渲染层已销毁，主动 detach（GSY 侧 releaseSurface 兜底）
            if (mediaPlayer != null) {
                android.util.Log.d(TAG, "showDisplay: null surface, detach");
                mediaPlayer.setSurface(null);
            }
            return;
        }
        // 死 surface 防线：Surface.isValid() 只反映本地对象状态，SurfaceTexture
        // 被 release（切内核渲染 View 重建）后包装 Surface 仍"valid"，attach 后
        // mpv EGL createSurface 永久失败且核心不可恢复（实测无限 fatal 循环）。
        // 校验同源 TextureView 的 isAvailable：源已死则丢弃本次推送，等宿主
        // 用活渲染 View 补推。
        if (msg.obj instanceof TextureView) {
            TextureView tv = (TextureView) msg.obj;
            if (!tv.isAvailable()) {
                android.util.Log.w(TAG, "showDisplay: TextureView not available"
                        + "（死 SurfaceTexture）, drop to avoid EGL fatal loop");
                return;
            }
        }

        android.util.Log.d(TAG, "showDisplay: surface=" + surface
                + ", valid=" + surface.isValid()
                + ", playerReady=" + (mediaPlayer != null));
        if (mediaPlayer != null) {
            mediaPlayer.setSurface(surface);
        } else {
            // player 未创建：缓存待 initVideoPlayer 补绑
            pendingSurface = surface;
        }
    }

    @Override
    public void setNeedMute(boolean needMute) {
        if (mediaPlayer != null) mediaPlayer.setVolume(needMute ? 0 : 1, needMute ? 0 : 1);
    }

    @Override
    public void setVolume(float left, float right) {
        if (mediaPlayer != null) mediaPlayer.setVolume(left, right);
    }

    @Override
    public void releaseSurface() {
        if (mediaPlayer != null) mediaPlayer.setSurface(null);
    }

    @Override
    public void release() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        // 共享 mpv 实例保留（内核切换复用；进程退出回收）
    }

    @Override
    public int getBufferedPercentage() {
        return mediaPlayer != null ? mediaPlayer.getBufferedPercentage() : 0;
    }

    @Override
    public long getNetSpeed() {
        return 0; // mpv demuxer-cache 已换算进 bufferedPercent
    }

    @Override
    public void setSpeedPlaying(float speed, boolean soundTouch) {
        if (mediaPlayer != null) mediaPlayer.setSpeed(speed);
    }

    @Override
    public boolean isSurfaceSupportLockCanvas() {
        return false; // GPU VO 渲染，外部 lockCanvas 不支持（同 Exo）
    }

    @Override
    public void setSpeed(float speed, boolean soundTouch) {
        if (mediaPlayer != null) mediaPlayer.setSpeed(speed);
    }

    @Override
    public void start() {
        if (mediaPlayer != null) mediaPlayer.start();
    }

    @Override
    public void stop() {
        if (mediaPlayer != null) mediaPlayer.stop();
    }

    @Override
    public void pause() {
        if (mediaPlayer != null) mediaPlayer.pause();
    }

    @Override
    public int getVideoWidth() {
        return mediaPlayer != null ? mediaPlayer.getVideoWidth() : 0;
    }

    @Override
    public int getVideoHeight() {
        return mediaPlayer != null ? mediaPlayer.getVideoHeight() : 0;
    }

    @Override
    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    @Override
    public void seekTo(long time) {
        if (mediaPlayer != null) mediaPlayer.seekTo(time);
    }

    @Override
    public long getCurrentPosition() {
        return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
    }

    @Override
    public long getDuration() {
        return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
    }

    @Override
    public int getVideoSarNum() {
        return mediaPlayer != null ? mediaPlayer.getVideoSarNum() : 1;
    }

    @Override
    public int getVideoSarDen() {
        return mediaPlayer != null ? mediaPlayer.getVideoSarDen() : 1;
    }

    /** 渲染 surface 尺寸更新（宿主 onSurfaceSizeChanged 反射调用）。
     *  mpv 的 EGL 渲染 surface 尺寸依赖 android-surface-size 属性；
     *  不设置会在部分驱动上出现渲染尺寸错乱（纯色闪动/花屏）。 */
    public static void updateSurfaceSize(int width, int height) {
        if (sSharedPlayer != null) {
            sSharedPlayer.updateSurfaceSize(width, height);
        }
    }

    /** 画质增强档位切换（宿主经反射调用）。档位名 = PlayerSettingsManager.FILTER_*
     *  字面量（本模块不依赖宿主常量，字符串对齐）；映射为 glsl-shaders 链实时切换，
     *  不重建渲染 View（重建会 detach 播放中 surface → vo 重启失败黑屏）。 */
    public static void applyEnhancement(Context ctx, String filterName) {
        if (sSharedPlayer == null || filterName == null) return;
        List<String> chain = null;
        if ("标准增强".equals(filterName)) {
            // 单档 Anime4K Restore（AI 降噪+锐化），与超分链同资产
            String p = Anime4KShaderLoader.getShaderPath(ctx, "Anime4K_Restore_CNN_M.glsl");
            if (p != null) chain = java.util.Collections.singletonList(p);
        } else if ("鲜艳".equals(filterName)) {
            String p = Anime4KShaderLoader.getToneShaderPath(ctx, "vivid.glsl");
            if (p != null) chain = java.util.Collections.singletonList(p);
        } else if ("黑白".equals(filterName)) {
            String p = Anime4KShaderLoader.getToneShaderPath(ctx, "bw.glsl");
            if (p != null) chain = java.util.Collections.singletonList(p);
        } else if ("复古".equals(filterName)) {
            String p = Anime4KShaderLoader.getToneShaderPath(ctx, "sepia.glsl");
            if (p != null) chain = java.util.Collections.singletonList(p);
        } else if ("Anime4K 超分".equals(filterName)) {
            chain = Anime4KShaderLoader.getAnime4KChain(ctx);
        }
        sSharedPlayer.setShaderChain(chain);
    }

    /** 画面比例模式切换（宿主经反射调用；档名字面量：默认/16:9/4:3/全屏裁剪/全屏拉伸） */
    public static void applyVideoScaleMode(String mode) {
        if (sSharedPlayer != null && mode != null) {
            sSharedPlayer.setVideoScaleMode(mode);
        }
    }

    /** 引擎可用性检测 */
    public static boolean isKernelAvailable() {
        return sSharedPlayer != null;
    }

    /**
     * 当前 mpv 绑定的底层 SurfaceTexture 对象名（SurfaceTexture@hex）。
     * 宿主用它和渲染 View 的 SurfaceTexture 比对，检测 Android 16 旋转时
     * 框架静默换新 SurfaceTexture（view 不销毁、无回调）导致的绑定漂移。
     *
     * @return 已 attach 时返回 name 字符串；未 attach/无 player 返回 null
     */
    public static String getAttachedSurfaceName() {
        if (sSharedPlayer == null || !sSharedPlayer.surfaceAttached) {
            return null;
        }
        return sSharedPlayer.attachedSurfaceName;
    }

    /**
     * 冷启动 surface 补取：渲染 View 的 surface 回调早于 player 创建时，
     * 由宿主（OrangevideoView）在 onPlayerInitSuccess 后调用本方法，
     * 从其渲染代理的 showView 反取 SurfaceTexture/Surface 推给 mpv。
     *
     * @return Boolean.TRUE 绑定成功/已绑定；Boolean.FALSE 渲染 View 未就绪（宿主可重试）
     */
    public static Object bindRenderProxySurface(Object gsyRenderView) {
        if (sSharedPlayer == null || gsyRenderView == null) return Boolean.FALSE;
        try {
            Object showView = gsyRenderView.getClass()
                    .getMethod("getShowView").invoke(gsyRenderView);
            if (showView instanceof android.view.TextureView) {
                android.view.TextureView tv = (android.view.TextureView) showView;
                if (tv.isAvailable() && tv.getSurfaceTexture() != null) {
                    android.util.Log.d("MpvPlayerManager", "bind surface from TextureView");
                    sSharedPlayer.setSurface(new Surface(tv.getSurfaceTexture()));
                    // 绑定后同步渲染尺寸：Android 16 旋转时框架会静默换新
                    // SurfaceTexture（getHardwareLayer createNewSurface:true），
                    // 不触发 onSurfaceSizeChanged，android-surface-size 必须随
                    // 重绑显式下发，否则 vo 重启后渲染尺寸错乱
                    if (tv.getWidth() > 0 && tv.getHeight() > 0) {
                        sSharedPlayer.updateSurfaceSize(tv.getWidth(), tv.getHeight());
                    }
                    return Boolean.TRUE;
                }
                android.util.Log.d("MpvPlayerManager",
                        "bind surface skipped: TextureView not ready");
                return Boolean.FALSE;
            } else if (showView instanceof android.view.SurfaceView) {
                android.view.SurfaceHolder holder =
                        ((android.view.SurfaceView) showView).getHolder();
                if (holder != null && holder.getSurface() != null
                        && holder.getSurface().isValid()) {
                    android.util.Log.d("MpvPlayerManager", "bind surface from SurfaceView");
                    sSharedPlayer.setSurface(holder.getSurface());
                    return Boolean.TRUE;
                }
                android.util.Log.d("MpvPlayerManager",
                        "bind surface skipped: SurfaceView not ready");
                return Boolean.FALSE;
            }
            return Boolean.TRUE;
        } catch (Exception e) {
            android.util.Log.w("MpvPlayerManager", "bindRenderProxySurface failed: " + e);
            return Boolean.FALSE;
        }
    }
}
