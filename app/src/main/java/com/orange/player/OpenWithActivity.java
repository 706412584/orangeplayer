package com.orange.player;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.orange.playerlibrary.OrangeVideoController;
import com.orange.playerlibrary.OrangevideoView;
import com.orange.playerlibrary.PiPHelper;
import com.orange.playerlibrary.PlayerConstants;
import com.orange.playerlibrary.PlayerSettingsManager;
import com.orange.playerlibrary.interfaces.OnStateChangeListener;
import com.orange.playerlibrary.utils.PlayerEngineSelector;
import com.orange.player.session.OrangePlayerSessionHelper;
import com.shuyu.gsyvideoplayer.GSYVideoManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OpenWithActivity extends AppCompatActivity {

    private static final String TAG = "OpenWithActivity";
    private static final String CACHE_DIR_NAME = "open-with-cache";

    private OrangevideoView mVideoView;
    private OrangeVideoController mController;
    private PiPHelper mPiPHelper;
    private OrangePlayerSessionHelper mSessionHelper;
    private TextView mTvIntentSummary;
    private TextView mTvDebugLog;
    private ScrollView mScrollLog;

    private final StringBuilder mLogBuilder = new StringBuilder();
    private final ExecutorService mIoExecutor = Executors.newSingleThreadExecutor();

    private Uri mCurrentUri;
    private String mCurrentUrl;
    private String mCurrentTitle;
    private String mCachedFileUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_open_with);

        initViews();
        initPlayer();
        initPiPHelper();
        setupBackPressedHandler();
        handleIntent(getIntent(), true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, false);
    }

    private void initViews() {
        mVideoView = findViewById(R.id.video_view);
        mTvIntentSummary = findViewById(R.id.tv_intent_summary);
        mTvDebugLog = findViewById(R.id.tv_debug_log);
        mScrollLog = findViewById(R.id.scroll_log);

        Button btnRetry = findViewById(R.id.btn_retry);
        Button btnCacheIjk = findViewById(R.id.btn_cache_ijk);
        Button btnFinish = findViewById(R.id.btn_finish);

        btnRetry.setOnClickListener(v -> replayCurrentIntent());
        btnCacheIjk.setOnClickListener(v -> playCurrentUriThroughCachedFile());
        btnFinish.setOnClickListener(v -> finish());
    }

    private void initPlayer() {
        mController = new OrangeVideoController(this);
        mVideoView.setVideoController(mController);
        mController.setLoading(OrangeVideoController.IndicatorType.LINE_SCALE_PULSE_OUT);
        mController.addDefaultControlComponent("外部打开", false);
        mSessionHelper = new OrangePlayerSessionHelper(this, mVideoView);

        mVideoView.setKeepVideoPlaying(false);
        mVideoView.setLooping(false);
        mVideoView.setAutoRotateOnFullscreen(true);

        mVideoView.addOnStateChangeListener(new OnStateChangeListener() {
            @Override
            public void onPlayStateChanged(int playState) {
                log("playState=" + playState);
            }

            @Override
            public void onPlayerStateChanged(int playerState) {
                log("playerState=" + playerState);
            }
        });

        mVideoView.setDebugLogCallback(msg -> log("player: " + msg));
    }

    private void initPiPHelper() {
        mPiPHelper = new PiPHelper(this, mVideoView);
    }

    private void handleIntent(Intent intent, boolean firstLaunch) {
        if (intent == null) {
            log("未收到外部 Intent");
            return;
        }

        String action = intent.getAction();
        String type = intent.getType();
        Uri dataUri = intent.getData();
        Uri streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        Uri resolvedUri = dataUri != null ? dataUri : streamUri;

        log((firstLaunch ? "首次" : "更新") + " Intent action=" + action);
        log("Intent type=" + type);
        log("Intent data=" + dataUri);
        log("Intent extraStream=" + streamUri);
        log("Intent flags=0x" + Integer.toHexString(intent.getFlags()));

        if (resolvedUri == null) {
            mTvIntentSummary.setText("没有在外部 Intent 中找到可播放的 Uri。");
            log("没有找到可播放的 Uri");
            return;
        }

        mCurrentUri = resolvedUri;
        mCurrentUrl = resolvedUri.toString();
        mCurrentTitle = buildDisplayTitle(intent, resolvedUri);
        mCachedFileUrl = null;

        tryTakePersistablePermission(resolvedUri, intent.getFlags());
        updateIntentSummary(action, type, resolvedUri);
        playResolvedUri();
    }

    private void playResolvedUri() {
        if (TextUtils.isEmpty(mCurrentUrl)) {
            log("地址为空，跳过播放");
            return;
        }

        String engine = chooseEngineForUri(mCurrentUri, mCurrentUrl);
        log("准备播放: " + mCurrentUrl);
        startPlayback(mCurrentUrl, mCurrentTitle, engine, true);
    }

    private void replayCurrentIntent() {
        log("手动重新播放");
        if (mCurrentUri == null) {
            log("当前没有可重播的 Uri");
            return;
        }
        playResolvedUri();
    }

    private void playCurrentUriThroughCachedFile() {
        if (mCurrentUri == null) {
            log("当前没有可缓存的 Uri");
            return;
        }
        if (!isContentUri(mCurrentUri)) {
            log("当前 Uri 不是 content://，无需转缓存后用 IJK");
            return;
        }
        if (!TextUtils.isEmpty(mCachedFileUrl)) {
            log("使用已有缓存文件通过 IJK 播放");
            startPlayback(mCachedFileUrl, mCurrentTitle, PlayerConstants.ENGINE_IJK, false);
            return;
        }

        log("开始把 content Uri 缓存为本地文件，供 IJK 播放");
        mIoExecutor.execute(() -> {
            try {
                File cachedFile = copyCurrentUriToCacheFile();
                String fileUrl = "file://" + cachedFile.getAbsolutePath();
                runOnUiThread(() -> {
                    mCachedFileUrl = fileUrl;
                    log("缓存完成: " + cachedFile.getAbsolutePath());
                    startPlayback(fileUrl, mCurrentTitle, PlayerConstants.ENGINE_IJK, false);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to cache content uri for IJK", e);
                runOnUiThread(() -> log("缓存到本地失败: " + e.getMessage()));
            }
        });
    }

    private void updateIntentSummary(String action, String type, Uri uri) {
        StringBuilder summary = new StringBuilder();
        summary.append("动作: ").append(action == null ? "无" : action).append('\n');
        summary.append("类型: ").append(type == null ? "未知" : type).append('\n');
        summary.append("地址: ").append(uri).append('\n');
        summary.append("标题: ").append(mCurrentTitle).append('\n');
        summary.append("播放策略: ")
                .append(isContentUri(uri) ? "content:// 强制 Exo，可手动缓存后切 IJK" : "按地址智能选择内核");
        mTvIntentSummary.setText(summary.toString());
    }

    private String buildDisplayTitle(Intent intent, Uri uri) {
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (!TextUtils.isEmpty(subject)) {
            return subject;
        }

        String lastSegment = uri.getLastPathSegment();
        if (!TextUtils.isEmpty(lastSegment)) {
            return lastSegment;
        }

        return "外部视频";
    }

    private void tryTakePersistablePermission(Uri uri, int flags) {
        int readFlags = flags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        int persistableFlags = flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        if (readFlags == 0 || persistableFlags == 0) {
            return;
        }

        try {
            getContentResolver().takePersistableUriPermission(uri, readFlags);
            log("已获取持久读取权限");
        } catch (SecurityException e) {
            log("持久权限不可用: " + e.getMessage());
        }
    }

    private String chooseEngineForUri(Uri uri, String url) {
        if (isContentUri(uri)) {
            log("检测到 content:// Uri，默认强制使用 ExoPlayer");
            return PlayerConstants.ENGINE_EXO;
        }

        String engine = PlayerEngineSelector.selectEngine(url, PlayerConstants.ENGINE_EXO);
        log("智能选择内核: " + PlayerEngineSelector.getEngineName(engine));
        return engine;
    }

    private boolean isContentUri(Uri uri) {
        return uri != null && ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme());
    }

    private void applyPlayerEngine(String engine) {
        PlayerSettingsManager settingsManager = PlayerSettingsManager.getInstance(this);
        settingsManager.setPlayerEngine(engine);
        log("切换内核为: " + PlayerEngineSelector.getEngineName(engine));
    }

    private void startPlayback(String url, String title, String engine, boolean cacheWithPlay) {
        applyPlayerEngine(engine);
        mVideoView.release();
        GSYVideoManager.releaseAllVideos();
        mVideoView.selectPlayerFactory(engine);
        mVideoView.setUp(url, cacheWithPlay, title);
        if (mSessionHelper != null) {
            mSessionHelper.start(url, title);
        }
        mVideoView.post(() -> {
            log("调用 startPlayLogic()");
            mVideoView.startPlayLogic();
        });
    }

    private File copyCurrentUriToCacheFile() throws Exception {
        File cacheDir = new File(getCacheDir(), CACHE_DIR_NAME);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IllegalStateException("无法创建缓存目录: " + cacheDir.getAbsolutePath());
        }

        String fileName = buildCacheFileName();
        File targetFile = new File(cacheDir, fileName);

        try (InputStream inputStream = getContentResolver().openInputStream(mCurrentUri);
             OutputStream outputStream = new FileOutputStream(targetFile, false)) {
            if (inputStream == null) {
                throw new IllegalStateException("ContentResolver 返回了空输入流");
            }

            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            outputStream.flush();
        }

        return targetFile;
    }

    private String buildCacheFileName() {
        String baseName = mCurrentUri != null ? mCurrentUri.getLastPathSegment() : null;
        if (TextUtils.isEmpty(baseName)) {
            baseName = "open_with_video";
        }
        baseName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");

        String extension = resolveExtensionFromUri();
        if (!baseName.contains(".") && !TextUtils.isEmpty(extension)) {
            baseName = baseName + "." + extension;
        }

        return System.currentTimeMillis() + "_" + baseName;
    }

    private String resolveExtensionFromUri() {
        String mimeType = getContentResolver().getType(mCurrentUri);
        if (!TextUtils.isEmpty(mimeType)) {
            if ("application/vnd.apple.mpegurl".equalsIgnoreCase(mimeType)
                    || "application/x-mpegURL".equalsIgnoreCase(mimeType)) {
                return "m3u8";
            }
            if ("video/mp4".equalsIgnoreCase(mimeType)) {
                return "mp4";
            }
            if ("video/x-matroska".equalsIgnoreCase(mimeType)) {
                return "mkv";
            }
            if ("video/avi".equalsIgnoreCase(mimeType) || "video/x-msvideo".equalsIgnoreCase(mimeType)) {
                return "avi";
            }
        }

        String lastSegment = mCurrentUri != null ? mCurrentUri.getLastPathSegment() : null;
        if (!TextUtils.isEmpty(lastSegment)) {
            int dotIndex = lastSegment.lastIndexOf('.');
            if (dotIndex >= 0 && dotIndex < lastSegment.length() - 1) {
                return lastSegment.substring(dotIndex + 1);
            }
        }

        return "mp4";
    }

    private void setupBackPressedHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (GSYVideoManager.isFullState(OpenWithActivity.this)) {
                    GSYVideoManager.backFromWindowFull(OpenWithActivity.this);
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mPiPHelper != null && mPiPHelper.handleOnPause()) {
            return;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPiPHelper != null && mPiPHelper.handleOnResume()) {
            return;
        }
        mVideoView.onVideoResume();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mSessionHelper != null && !TextUtils.isEmpty(mCurrentUrl)) {
            mSessionHelper.start(mCurrentUrl, mCurrentTitle);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mPiPHelper != null && mPiPHelper.handleOnStop()) {
            return;
        }
        if (mSessionHelper != null) {
            mSessionHelper.stop();
        }
        if (mVideoView != null && mVideoView.isPlaying()) {
            mVideoView.onVideoPause();
            log("onStop() 中暂停播放");
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
        if (mSessionHelper != null) {
            mSessionHelper.stop();
        }
        super.onDestroy();
        if (mVideoView != null) {
            mVideoView.release();
        }
        mIoExecutor.shutdownNow();
    }

    private void log(String msg) {
        Log.d(TAG, msg);

        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        mLogBuilder.append('[').append(timestamp).append("] ").append(msg).append('\n');

        if (mTvDebugLog != null) {
            mTvDebugLog.setText(mLogBuilder.toString());
            if (mScrollLog != null) {
                mScrollLog.post(() -> mScrollLog.fullScroll(ScrollView.FOCUS_DOWN));
            }
        }
    }
}
