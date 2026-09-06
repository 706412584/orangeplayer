package com.orange.player.mpv.spike;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.jdtech.mpv.MPVLib;

/**
 * S2 真机渲染 spike：
 * 1. libmpv AAR 加载（so 冲突验证：同进程是否与其他 FFmpeg 共存——本 demo 单独进程不涉及，
 *    但验证 so 本身能跑）
 * 2. SurfaceView 渲染
 * 3. 播放/暂停/seek
 * 4. 旋转（configChanges 不重建）
 * 5. 事件映射（FILE_LOADED → onPrepared 语义验证）
 */
public class SpikeActivity extends Activity implements MPVLib.EventObserver {

    private static final String TAG = "MpvSpike";
    private static final String TEST_URL =
            "/sdcard/Android/data/com.orange.player.mpv.spike/files/Movies/lowres-test.mp4";  // 480x270 低清源：验证 Anime4K 放大效果

    private MPVLib mpv;
    private SurfaceView surfaceView;
    private TextView statusText;
    private TextView logText;
    private final StringBuilder logBuffer = new StringBuilder();

    private boolean prepared = false;
    private long durationMs;
    private long positionMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                log("surfaceCreated: attach");
                if (mpv != null) {
                    try {
                        // 官方流程：surfaceCreated 即 attach，然后强制渲染进 surface
                        mpv.attachSurface(holder.getSurface());
                        mpv.setOptionString("force-window", "yes");
                        attachSurfaceAndPlay();
                    } catch (Exception e) {
                        log("attachSurface err: " + e);
                    }
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                log("surfaceChanged: " + width + "x" + height);
                if (mpv != null) {
                    // 官方流程：GPU VO 需要感知 surface 尺寸
                    try {
                        mpv.setPropertyString("android-surface-size", width + "x" + height);
                    } catch (Exception e) {
                        log("setSurfaceSize err: " + e);
                    }
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (mpv != null) {
                    try {
                        mpv.setOptionString("force-window", "no");
                        mpv.setPropertyString("vo", "gpu");
                        mpv.detachSurface();
                    } catch (Exception e) { log("detachSurface err: " + e); }
                }
            }
        });

        initMpv();
    }

    private void initMpv() {
        try {
            mpv = MPVLib.create(this);
            if (mpv == null) {
                log("FATAL: MPVLib.create 返回 null");
                return;
            }
            mpv.addObserver(this);

            // 前置选项（init() 之前）
            mpv.setOptionString("hwdec", "auto");          // 硬解优先
            mpv.setOptionString("vo", "gpu");              // GPU 渲染
            mpv.setOptionString("ao", "opensles");
            mpv.setOptionString("config", "no");

            // 启动 mpv 事件循环（官方流程：init() 必须在 create 之后、loadfile 之前）
            mpv.init();

            // 后置选项（官方 BaseMPVView 同款）
            mpv.setOptionString("force-window", "no");     // surface 创建前不能强制窗口
            mpv.setOptionString("idle", "once");           // playFile 延迟加载需要 idle 模式

            // S2 诊断：开启 verbose 日志
            mpv.setOptionString("msg-level", "all=v");
            mpv.addLogObserver((prefix, level, text) -> Log.v(TAG, "[" + prefix + "] " + text));

            // 观察属性（S4 映射验证）
            mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
            mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
            mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG);

            // S2 超分验证：启动即自动开启 Anime4K（绕过按钮点击不确定因素）
            setupAnime4KAuto();

            log("mpv 初始化完成，等待 surface");
        } catch (Throwable t) {
            log("initMpv FATAL: " + t);
            Log.e(TAG, "initMpv", t);
        }
    }

    private void attachSurfaceAndPlay() {
        if (mpv == null) return;
        try {
            // S4 结论：延迟 loadfile——surface attach 后再加载
            if (!prepared) {
                String[] loadCmd = {"loadfile", TEST_URL};
                mpv.command(loadCmd);
                log("loadfile 已发出");
            }
        } catch (Exception e) {
            log("attachSurfaceAndPlay err: " + e);
        }
    }

    // ===== MPV 事件回调（验证 S4 映射） =====

    @Override
    public void event(int eventId) {
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
            prepared = true;
            runOnUiThread(() -> {
                statusText.setText("状态: PREPARED（FILE_LOADED）");
                log("EVENT: FILE_LOADED → 对应 GSY notifyOnPrepared");
            });
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
            runOnUiThread(() -> log("EVENT: END_FILE → 对应 GSY notifyOnCompletion"));
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_SEEK) {
            runOnUiThread(() -> log("EVENT: SEEK"));
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART) {
            runOnUiThread(() -> log("EVENT: PLAYBACK_RESTART（seek 结束/起播）"));
        } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG) {
            runOnUiThread(() -> log("EVENT: VIDEO_RECONFIG"));
        }
    }

    @Override
    public void eventProperty(String property) { }

    @Override
    public void eventProperty(String property, long value) { }

    @Override
    public void eventProperty(String property, double value) {
        if ("time-pos".equals(property)) {
            positionMs = (long) (value * 1000);
        } else if ("duration".equals(property)) {
            durationMs = (long) (value * 1000);
        }
        if ("time-pos".equals(property) || "duration".equals(property)) {
            runOnUiThread(() -> statusText.setText(String.format(
                    "状态: 播放中 %ds / %ds", positionMs / 1000, durationMs / 1000)));
        }
    }

    @Override
    public void eventProperty(String property, boolean value) {
        if ("pause".equals(property)) {
            runOnUiThread(() -> log("属性 pause=" + value));
        }
    }

    @Override
    public void eventProperty(String property, String value) { }

    // ===== UI =====

    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // 渲染区（16:9）
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // 控制区
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setBackgroundColor(0xFF1C1C1E);

        Button btnPause = mkButton("暂停/继续", v -> {
            if (mpv == null) return;
            Boolean paused = mpv.getPropertyBoolean("pause");
            mpv.setPropertyBoolean("pause", paused == null || !paused);
        });
        Button btnSeek = mkButton("Seek +30s", v -> {
            if (mpv == null) return;
            String[] cmd = {"seek", "30", "relative"};
            mpv.command(cmd);
        });
        Button btnRotate = mkButton("旋转", v ->
                setRequestedOrientation(getRequestedOrientation()
                        == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        Button btnSuperRes = mkButton("超分开/关", v -> toggleSuperRes());

        controls.addView(btnPause, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(btnSeek, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(btnRotate, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(btnSuperRes, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 日志区
        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setPadding(24, 12, 24, 12);
        statusText.setText("状态: 初始化...");
        root.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        logText = new TextView(this);
        logText.setTextColor(0xFFB3FFFF);
        logText.setPadding(24, 4, 24, 12);
        logText.setTextSize(11);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logText);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 400));

        return root;
    }

    private Button mkButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    // ===== Anime4K 超分（S2 附加验证：mpv glsl-shader 热加载） =====

    private boolean superResOn = false;

    private static final String[] ANIME4K_SHADERS = {
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Upscale_CNN_x2_L.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
    };

    private void toggleSuperRes() {
        if (mpv == null) return;
        if (!superResOn) {
            if (applyAnime4KChain()) {
                superResOn = true;
            }
        } else {
            try {
                mpv.command(new String[]{"change-list", "glsl-shaders", "clear", ""});
                superResOn = false;
                Log.i(TAG, "SUPERRES OFF");
                log("超分已关闭");
            } catch (Exception e) {
                log("超分关闭失败: " + e);
            }
        }
    }

    /**
     * 释放 assets 中的 Anime4K shader 到 filesDir 并挂到 glsl-shaders。
     * 使用 change-list glsl-shaders add 逐个添加（此构建不支持分号串）。
     * 成功返回 true。
     */
    private boolean applyAnime4KChain() {
        if (mpv == null) return false;
        try {
            for (String name : ANIME4K_SHADERS) {
                java.io.File out = new java.io.File(getFilesDir(), name);
                if (!out.exists()) {
                    try (java.io.InputStream is = getAssets().open("shaders/" + name);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
                mpv.command(new String[]{"change-list", "glsl-shaders", "add", out.getAbsolutePath()});
                Log.i(TAG, "SUPERRES add shader: " + name);
            }
            log("超分已开启: Anime4K Mode A（4 shader 链）");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "SUPERRES apply failed", e);
            log("超分开启失败: " + e);
            return false;
        }
    }

    /** 启动即自动开启超分（验证渲染管线）；adb 传 extra 可强制关闭做 A/B 对照 */
    private void setupAnime4KAuto() {
        boolean off = "off".equals(getIntent().getStringExtra("superres"));
        android.util.Log.i(TAG, "SUPERRES mode extra=" + (off ? "off" : "on"));
        if (off) return;
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.postDelayed(() -> {
            if (applyAnime4KChain()) {
                superResOn = true;
            }
        }, 2000);
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        runOnUiThread(() -> {
            logBuffer.insert(0, msg + "\n");
            if (logBuffer.length() > 4000) logBuffer.setLength(4000);
            logText.setText(logBuffer.toString());
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mpv != null) {
            try {
                mpv.detachSurface();
                mpv.destroy();
            } catch (Exception ignored) { }
            mpv = null;
        }
    }
}
