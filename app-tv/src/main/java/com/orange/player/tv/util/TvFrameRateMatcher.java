package com.orange.player.tv.util;

import android.app.Activity;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV 自动帧率匹配（API 23-29）。
 * <p>
 * API 30+：ExoPlayer (Media3) 内部经 Surface.setFrameRate 自动处理
 * （ONLY_IF_SEAMLESS 策略），本类不介入。
 * API 23-29：读视频帧率 → 匹配 Display 支持的 mode →
 * WindowManager.LayoutParams.preferredDisplayModeId，退出时恢复原 mode。
 * <p>
 * 默认关闭，由调用方按设置开关启用。
 */
public class TvFrameRateMatcher {

    private static final String TAG = "TvFrameRate";
    /** 帧率匹配容差（fps），避免浮点误差导致 23.98 vs 24 反复切换 */
    private static final float FPS_TOLERANCE = 0.1f;

    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int originalDisplayModeId = -1;
    private boolean applied = false;
    private boolean released = false;

    public TvFrameRateMatcher(Activity activity) {
        this.activity = activity;
    }

    /** 是否需要本类处理（API 23-29 才用 preferredDisplayModeId） */
    public static boolean isApplicable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.R;
    }

    /**
     * 尝试把显示模式切换到与视频帧率匹配。
     *
     * @return true 表示已提交匹配任务（onDestroy 时须调用 restore()）
     */
    public boolean match(String videoUrl) {
        if (!isApplicable() || videoUrl == null || applied || released) {
            return false;
        }
        executor.execute(() -> {
            Float videoFps = readVideoFrameRate(videoUrl);
            activity.runOnUiThread(() -> applyMatch(videoUrl, videoFps));
        });
        return true;
    }

    private void applyMatch(String videoUrl, Float videoFps) {
        if (released || activity.isFinishing() || videoFps == null || videoFps <= 0) {
            if (videoFps == null || videoFps <= 0) {
                Log.d(TAG, "无法读取视频帧率: " + videoUrl);
            }
            return;
        }
        Display.Mode target = findMatchingMode(videoFps);
        if (target == null) {
            Log.d(TAG, "无匹配的显示模式，视频帧率=" + videoFps);
            return;
        }
        Display display = getDisplay();
        if (display == null) {
            return;
        }
        Display.Mode current = display.getMode();
        if (current.getModeId() == target.getModeId()) {
            Log.d(TAG, "当前模式已匹配: " + videoFps + "fps");
            return;
        }
        originalDisplayModeId = current.getModeId();
        WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
        attrs.preferredDisplayModeId = target.getModeId();
        activity.getWindow().setAttributes(attrs);
        applied = true;
        Log.i(TAG, "帧率匹配: " + videoFps + "fps → mode " + target.getModeId()
                + " (" + target.getRefreshRate() + "Hz)");
    }

    /** 恢复进入播放页前的显示模式（onDestroy 调用） */
    public void restore() {
        released = true;
        executor.shutdownNow();
        if (!applied || originalDisplayModeId < 0) {
            return;
        }
        WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
        attrs.preferredDisplayModeId = originalDisplayModeId;
        activity.getWindow().setAttributes(attrs);
        applied = false;
        originalDisplayModeId = -1;
        Log.i(TAG, "显示模式已恢复");
    }

    /** 读视频帧率：优先 MediaExtractor（精确），失败回退 MediaMetadataRetriever */
    private Float readVideoFrameRate(String url) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(url);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.getString(MediaFormat.KEY_MIME) != null
                        && format.getString(MediaFormat.KEY_MIME).startsWith("video/")
                        && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    return (float) format.getInteger(MediaFormat.KEY_FRAME_RATE);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, "MediaExtractor 读取帧率失败: " + e.getMessage());
        } finally {
            extractor.release();
        }
        // 回退：本地文件/部分 http 可用
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(url);
            String fps = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            return fps != null ? Float.parseFloat(fps) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                Log.w(TAG, "MediaMetadataRetriever 释放失败", e);
            }
        }
    }

    /** 在支持的显示模式中找分辨率相同且刷新率匹配视频帧率的模式 */
    private Display.Mode findMatchingMode(float videoFps) {
        Display display = getDisplay();
        if (display == null) {
            return null;
        }
        Display.Mode current = display.getMode();
        Display.Mode best = null;
        float bestDiff = Float.MAX_VALUE;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() != current.getPhysicalWidth()
                    || mode.getPhysicalHeight() != current.getPhysicalHeight()) {
                continue; // 不改变分辨率
            }
            float diff = Math.abs(mode.getRefreshRate() - videoFps);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = mode;
            }
        }
        // 容差内才算匹配（避免把 60Hz 内容切到 50Hz）
        return (best != null && bestDiff <= FPS_TOLERANCE + 0.05f) ? best : null;
    }

    @SuppressWarnings("deprecation")
    private Display getDisplay() {
        WindowManager windowManager = activity.getWindowManager();
        return windowManager != null ? windowManager.getDefaultDisplay() : null;
    }
}
