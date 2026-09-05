package com.orange.playerlibrary.subtitle;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.media3.common.text.CueGroup;
import androidx.media3.ui.CaptionStyleCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字幕管理器
 * 支持 SRT、VTT 格式字幕的加载和显示
 */
public class SubtitleManager {

    private static final String TAG = "SubtitleManager";

    private Context mContext;
    private SubtitleView mSubtitleView;
    private androidx.media3.ui.SubtitleView mMedia3SubtitleView;
    private ViewGroup mPlayerContainer;
    private boolean mHasMedia3Cues;
    private List<SubtitleEntry> mSubtitles = new ArrayList<>();
    private Handler mHandler;
    private ExecutorService mExecutor;
    
    // 字幕状态
    private boolean mEnabled = false; // 默认关闭
    private boolean mLoaded = false;
    private String mCurrentSubtitlePath;
    
    // 字幕样式
    private float mTextSize = 18f; // 默认18sp
    private int mTextColor = Color.WHITE;
    private int mBackgroundColor = 0x80000000; // 半透明黑色
    private int mBottomMargin = 10; // dp - 字幕位置
    
    // 进度提供者
    private ProgressProvider mProgressProvider;
    private Runnable mUpdateRunnable;
    private boolean mIsUpdating = false;
    private static final int UPDATE_INTERVAL = 100; // 100ms 更新一次

    // 字幕延迟（毫秒）：正值延后显示，负值提前显示。
    // 作用于渲染时间轴（position - delay），不触碰播放器 seek，跨引擎切换天然生效
    private long mSubtitleDelayMs = 0;

    public interface ProgressProvider {
        long getCurrentPosition();
        boolean isPlaying();
    }
    
    public interface OnSubtitleLoadListener {
        void onLoadSuccess(int count);
        void onLoadFailed(String error);
    }

    public SubtitleManager(Context context) {
        mContext = context.getApplicationContext();
        mHandler = new Handler(Looper.getMainLooper());
        mExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * 绑定到播放器容器
     */
    public void attachToPlayer(ViewGroup playerContainer) {
        mPlayerContainer = playerContainer;
        if (mSubtitleView == null) {
            mSubtitleView = new SubtitleView(mContext);
            mSubtitleView.setTextSize(mTextSize);
            mSubtitleView.setTextColor(mTextColor);
            mSubtitleView.setBackgroundColor(mBackgroundColor);
        }
        
        // 移除旧的
        if (mSubtitleView.getParent() != null) {
            ((ViewGroup) mSubtitleView.getParent()).removeView(mSubtitleView);
        }
        
        // 添加到播放器容器底部
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dpToPx(mBottomMargin);
        params.leftMargin = dpToPx(20);
        params.rightMargin = dpToPx(20);
        
        playerContainer.addView(mSubtitleView, params);
        attachMedia3SubtitleView(playerContainer);
        Log.d(TAG, "SubtitleView attached to player");
    }

    /**
     * 将 Media3 字幕视图挂到当前播放器容器。
     */
    public void attachMedia3SubtitleView(ViewGroup playerContainer) {
        if (playerContainer == null || mMedia3SubtitleView == null) {
            return;
        }
        if (mMedia3SubtitleView.getParent() != null) {
            ((ViewGroup) mMedia3SubtitleView.getParent()).removeView(mMedia3SubtitleView);
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        playerContainer.addView(mMedia3SubtitleView, params);
        // 置顶，避免被视频 TextureView / SurfaceView 覆盖导致字幕不可见
        mMedia3SubtitleView.bringToFront();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            mMedia3SubtitleView.setZ(100f);
        }
    }
    
    /**
     * 重新附加到新的播放器容器（用于全屏切换）
     * 解决全屏模式下 SubtitleView 仍附加在旧播放器上的问题
     */
    public void reattachToPlayer(ViewGroup newPlayerContainer) {
        Log.d(TAG, "reattachToPlayer called, newPlayerContainer=" + newPlayerContainer);
        
        if (newPlayerContainer == null) {
            Log.e(TAG, "reattachToPlayer: newPlayerContainer is null!");
            return;
        }
        mPlayerContainer = newPlayerContainer;

        if (mSubtitleView == null) {
            Log.w(TAG, "reattachToPlayer: mSubtitleView is null, creating new one");
            mSubtitleView = new SubtitleView(mContext);
            mSubtitleView.setTextSize(mTextSize);
            mSubtitleView.setTextColor(mTextColor);
            mSubtitleView.setBackgroundColor(mBackgroundColor);
        }
        
        // 移除旧的父容器
        ViewGroup oldParent = (ViewGroup) mSubtitleView.getParent();
        if (oldParent != null) {
            Log.d(TAG, "reattachToPlayer: removing from old parent: " + oldParent.getClass().getSimpleName());
            oldParent.removeView(mSubtitleView);
        }
        
        // 添加到新的播放器容器底部
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dpToPx(mBottomMargin);
        params.leftMargin = dpToPx(20);
        params.rightMargin = dpToPx(20);
        
        newPlayerContainer.addView(mSubtitleView, params);
        attachMedia3SubtitleView(newPlayerContainer);
        Log.d(TAG, "reattachToPlayer: SubtitleView added to " + newPlayerContainer.getClass().getSimpleName());
        
        // 强制布局更新
        mSubtitleView.requestLayout();
        
        // 延迟检查附加状态
        mHandler.postDelayed(() -> {
            if (mSubtitleView != null) {
                Log.d(TAG, "reattachToPlayer (delayed check): isAttachedToWindow=" + (mSubtitleView.getWindowToken() != null)
                    + ", size=" + mSubtitleView.getWidth() + "x" + mSubtitleView.getHeight());
            }
        }, 500);
    }
    
    /**
     * 获取 Media3 原生字幕视图，用于渲染 ExoPlayer Cue（包括 ASS/SSA 样式）。
     */
    public androidx.media3.ui.SubtitleView getMedia3SubtitleView() {
        if (mMedia3SubtitleView == null) {
            mMedia3SubtitleView = new androidx.media3.ui.SubtitleView(mContext);
            mMedia3SubtitleView.setStyle(new CaptionStyleCompat(
                    mTextColor, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK,
                    null));
            mMedia3SubtitleView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    mTextSize);
        }
        if (mPlayerContainer != null && mMedia3SubtitleView.getParent() == null) {
            attachMedia3SubtitleView(mPlayerContainer);
        }
        return mMedia3SubtitleView;
    }

    /**
     * 设置当前 Media3 Cue。
     */
    public void setMedia3Cues(CueGroup cueGroup) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mHandler.post(() -> setMedia3Cues(cueGroup));
            return;
        }
        androidx.media3.ui.SubtitleView view = getMedia3SubtitleView();
        if (cueGroup == null) {
            mHasMedia3Cues = false;
            view.setCues(null);
            view.setVisibility(View.GONE);
            return;
        }
        mHasMedia3Cues = !cueGroup.cues.isEmpty();
        view.setCues(cueGroup.cues);
        view.setVisibility(mEnabled && mHasMedia3Cues ? View.VISIBLE : View.GONE);
    }

    /**
     * 清除当前 Media3 Cue。
     */
    public void clearMedia3Cues() {
        mHasMedia3Cues = false;
        if (mMedia3SubtitleView != null) {
            mMedia3SubtitleView.setCues(null);
            mMedia3SubtitleView.setVisibility(View.GONE);
        }
    }

    /**
     * 设置进度提供者
     */
    public void setProgressProvider(ProgressProvider provider) {
        mProgressProvider = provider;
    }
    
    /**
     * 从 URL 加载字幕
     */
    public void loadSubtitle(String url, OnSubtitleLoadListener listener) {
        if (url == null || url.isEmpty()) {
            if (listener != null) {
                listener.onLoadFailed("URL is empty");
            }
            return;
        }
        
        mCurrentSubtitlePath = url;
        mExecutor.execute(() -> {
            try {
                String content;
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    content = downloadSubtitle(url);
                } else {
                    content = readLocalFile(url);
                }
                
                List<SubtitleEntry> subtitles = parseSubtitle(content, url);

                // 0 条目视为解析失败（如 ASS 声明支持但格式不符），不再静默成功
                if (subtitles.isEmpty()) {
                    final String failReason = AssParser.isAssContent(content)
                            ? "UNSUPPORTED_SUBTITLE_FORMAT: ASS 内容无法解析出字幕条目"
                            : "字幕解析结果为空（格式不匹配或文件损坏）";
                    Log.e(TAG, "Load subtitle failed: " + failReason);
                    mHandler.post(() -> {
                        if (listener != null) {
                            listener.onLoadFailed(failReason);
                        }
                    });
                    return;
                }

                mHandler.post(() -> {
                    mSubtitles.clear();
                    mSubtitles.addAll(subtitles);
                    mLoaded = true;
                    Log.d(TAG, "Loaded " + subtitles.size() + " subtitle entries");
                    if (listener != null) {
                        listener.onLoadSuccess(subtitles.size());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Load subtitle failed", e);
                mHandler.post(() -> {
                    if (listener != null) {
                        listener.onLoadFailed(e.getMessage());
                    }
                });
            }
        });
    }
    
    /**
     * 从本地文件加载字幕
     */
    public void loadSubtitle(File file, OnSubtitleLoadListener listener) {
        if (file == null || !file.exists()) {
            if (listener != null) {
                listener.onLoadFailed("File not found");
            }
            return;
        }
        loadSubtitle(file.getAbsolutePath(), listener);
    }
    
    /**
     * 从 Uri 加载字幕（支持 SAF 文件选择器返回的 Uri）
     */
    public void loadSubtitle(Uri uri, OnSubtitleLoadListener listener) {
        if (uri == null) {
            if (listener != null) {
                listener.onLoadFailed("Uri is null");
            }
            return;
        }
        
        mCurrentSubtitlePath = uri.toString();
        mExecutor.execute(() -> {
            try {
                String content = readFromUri(uri);
                // 从 Uri 获取文件名来判断格式
                String fileName = getFileNameFromUri(uri);
                List<SubtitleEntry> subtitles = parseSubtitle(content, fileName != null ? fileName : ".srt");

                // 0 条目视为解析失败，不再静默成功
                if (subtitles.isEmpty()) {
                    final String failReason = AssParser.isAssContent(content)
                            ? "UNSUPPORTED_SUBTITLE_FORMAT: ASS 内容无法解析出字幕条目"
                            : "字幕解析结果为空（格式不匹配或文件损坏）";
                    Log.e(TAG, "Load subtitle from Uri failed: " + failReason);
                    mHandler.post(() -> {
                        if (listener != null) {
                            listener.onLoadFailed(failReason);
                        }
                    });
                    return;
                }

                mHandler.post(() -> {
                    mSubtitles.clear();
                    mSubtitles.addAll(subtitles);
                    mLoaded = true;
                    Log.d(TAG, "Loaded " + subtitles.size() + " subtitle entries from Uri");
                    if (listener != null) {
                        listener.onLoadSuccess(subtitles.size());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Load subtitle from Uri failed", e);
                mHandler.post(() -> {
                    if (listener != null) {
                        listener.onLoadFailed(e.getMessage());
                    }
                });
            }
        });
    }
    
    /**
     * 从 Uri 读取内容
     */
    private String readFromUri(Uri uri) throws Exception {
        try (InputStream is = mContext.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }
    
    /**
     * 从 Uri 获取文件名
     */
    private String getFileNameFromUri(Uri uri) {
        String fileName = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = mContext.getContentResolver().query(
                    uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to get file name from Uri", e);
            }
        }
        if (fileName == null) {
            fileName = uri.getLastPathSegment();
        }
        return fileName;
    }
    
    /**
     * 下载字幕文件
     */
    private String downloadSubtitle(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        
        try (InputStream is = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
    
    /**
     * 读取本地文件
     */
    private String readLocalFile(String path) throws Exception {
        try (FileInputStream fis = new FileInputStream(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }
    
    /**
     * 解析字幕内容
     */
    private List<SubtitleEntry> parseSubtitle(String content, String path) {
        String lowerPath = path.toLowerCase();
        if (lowerPath.endsWith(".srt")) {
            return parseSrt(content);
        } else if (lowerPath.endsWith(".vtt")) {
            return parseVtt(content);
        } else if (lowerPath.endsWith(".ass") || lowerPath.endsWith(".ssa")) {
            return AssParser.parse(content);
        } else {
            // 尝试自动检测格式
            if (content.contains("WEBVTT")) {
                return parseVtt(content);
            }
            if (AssParser.isAssContent(content)) {
                return AssParser.parse(content);
            }
            return parseSrt(content);
        }
    }

    /**
     * 解析 SRT 格式字幕
     */
    private List<SubtitleEntry> parseSrt(String content) {
        List<SubtitleEntry> entries = new ArrayList<>();
        // SRT 时间格式: 00:00:00,000 --> 00:00:00,000
        Pattern timePattern = Pattern.compile(
                "(\\d{2}):(\\d{2}):(\\d{2})[,\\.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,\\.](\\d{3})");
        
        String[] blocks = content.split("\n\n");
        for (String block : blocks) {
            String[] lines = block.trim().split("\n");
            if (lines.length < 2) continue;
            
            // 查找时间行
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = timePattern.matcher(lines[i]);
                if (matcher.find()) {
                    long startTime = parseTime(matcher.group(1), matcher.group(2), 
                            matcher.group(3), matcher.group(4));
                    long endTime = parseTime(matcher.group(5), matcher.group(6), 
                            matcher.group(7), matcher.group(8));
                    
                    // 收集字幕文本（时间行之后的所有行）
                    StringBuilder text = new StringBuilder();
                    for (int j = i + 1; j < lines.length; j++) {
                        if (text.length() > 0) text.append("\n");
                        text.append(cleanSubtitleText(lines[j]));
                    }
                    
                    if (text.length() > 0) {
                        entries.add(new SubtitleEntry(startTime, endTime, text.toString()));
                    }
                    break;
                }
            }
        }
        return entries;
    }
    
    /**
     * 解析 VTT 格式字幕
     */
    private List<SubtitleEntry> parseVtt(String content) {
        List<SubtitleEntry> entries = new ArrayList<>();
        // VTT 时间格式: 00:00:00.000 --> 00:00:00.000 或 00:00.000 --> 00:00.000
        Pattern timePattern = Pattern.compile(
                "(?:(\\d{2}):)?(\\d{2}):(\\d{2})[,\\.](\\d{3})\\s*-->\\s*(?:(\\d{2}):)?(\\d{2}):(\\d{2})[,\\.](\\d{3})");
        
        String[] blocks = content.split("\n\n");
        for (String block : blocks) {
            if (block.trim().startsWith("WEBVTT") || block.trim().startsWith("NOTE")) {
                continue;
            }
            
            String[] lines = block.trim().split("\n");
            if (lines.length < 2) continue;
            
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = timePattern.matcher(lines[i]);
                if (matcher.find()) {
                    String h1 = matcher.group(1) != null ? matcher.group(1) : "00";
                    String h2 = matcher.group(5) != null ? matcher.group(5) : "00";
                    
                    long startTime = parseTime(h1, matcher.group(2), matcher.group(3), matcher.group(4));
                    long endTime = parseTime(h2, matcher.group(6), matcher.group(7), matcher.group(8));
                    
                    StringBuilder text = new StringBuilder();
                    for (int j = i + 1; j < lines.length; j++) {
                        if (text.length() > 0) text.append("\n");
                        text.append(cleanSubtitleText(lines[j]));
                    }
                    
                    if (text.length() > 0) {
                        entries.add(new SubtitleEntry(startTime, endTime, text.toString()));
                    }
                    break;
                }
            }
        }
        return entries;
    }
    
    /**
     * 解析时间为毫秒
     */
    private long parseTime(String hours, String minutes, String seconds, String millis) {
        return Long.parseLong(hours) * 3600000
                + Long.parseLong(minutes) * 60000
                + Long.parseLong(seconds) * 1000
                + Long.parseLong(millis);
    }
    
    /**
     * 清理字幕文本（移除 HTML 标签等）
     */
    private String cleanSubtitleText(String text) {
        // 移除 HTML 标签
        text = text.replaceAll("<[^>]+>", "");
        // 移除 VTT 样式标记
        text = text.replaceAll("\\{[^}]+\\}", "");
        return text.trim();
    }
    
    /**
     * 开始字幕更新
     */
    public void start() {
        if (mIsUpdating) return;
        mIsUpdating = true;
        
        mUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateSubtitle();
                if (mIsUpdating) {
                    mHandler.postDelayed(this, UPDATE_INTERVAL);
                }
            }
        };
        mHandler.post(mUpdateRunnable);
        Log.d(TAG, "Subtitle update started");
    }
    
    /**
     * 停止字幕更新
     */
    public void stop() {
        mIsUpdating = false;
        if (mUpdateRunnable != null) {
            mHandler.removeCallbacks(mUpdateRunnable);
        }
        Log.d(TAG, "Subtitle update stopped");
    }
    
    /**
     * 更新字幕显示
     */
    private void updateSubtitle() {
        if (!mEnabled || !mLoaded || mSubtitleView == null || mProgressProvider == null) {
            return;
        }

        long position = mProgressProvider.getCurrentPosition();
        // 应用字幕延迟：正值延后显示（查询更早的时间轴），负值提前
        long adjustedPosition = position - mSubtitleDelayMs;
        SubtitleEntry current = findSubtitleAt(adjustedPosition);

        if (current != null) {
            mSubtitleView.setText(current.getText());
            mSubtitleView.setVisibility(View.VISIBLE);
        } else {
            mSubtitleView.setText("");
            mSubtitleView.setVisibility(View.GONE);
        }
    }

    /**
     * 设置字幕延迟（毫秒）。正值延后显示，负值提前显示，0 恢复同步。
     * 仅影响渲染时间轴，不触碰播放器。
     */
    public void setSubtitleDelayMs(long delayMs) {
        mSubtitleDelayMs = delayMs;
        Log.d(TAG, "Subtitle delay set to " + delayMs + "ms");
    }

    /**
     * 获取当前字幕延迟（毫秒）
     */
    public long getSubtitleDelayMs() {
        return mSubtitleDelayMs;
    }
    
    /**
     * 查找当前时间点的字幕
     */
    private SubtitleEntry findSubtitleAt(long position) {
        for (SubtitleEntry entry : mSubtitles) {
            if (position >= entry.getStartTime() && position <= entry.getEndTime()) {
                return entry;
            }
        }
        return null;
    }
    
    // ===== 字幕控制方法 =====
    
    public void show() {
        mEnabled = true;
        if (mSubtitleView != null) {
            mSubtitleView.setVisibility(View.VISIBLE);
        }
        if (mMedia3SubtitleView != null && mHasMedia3Cues) {
            mMedia3SubtitleView.setVisibility(View.VISIBLE);
        }
    }

    public void hide() {
        mEnabled = false;
        if (mSubtitleView != null) {
            mSubtitleView.setVisibility(View.GONE);
        }
        if (mMedia3SubtitleView != null) {
            mMedia3SubtitleView.setVisibility(View.GONE);
        }
    }
    
    /**
     * 直接显示文字（用于 OCR 翻译字幕）
     * @param text 要显示的文字
     */
    public void showText(String text) {
        // 确保在主线程执行
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mHandler.post(() -> showTextInternal(text));
        } else {
            showTextInternal(text);
        }
    }
    
    /**
     * 内部方法：显示文字（必须在主线程调用）
     */
    private void showTextInternal(String text) {
        if (mSubtitleView == null) {
            Log.w(TAG, "showText: mSubtitleView is null!");
            return;
        }
        
        // 调试日志：检查 SubtitleView 状态
        boolean isAttached = mSubtitleView.getWindowToken() != null;
        int width = mSubtitleView.getWidth();
        int height = mSubtitleView.getHeight();
        android.view.ViewParent parent = mSubtitleView.getParent();
        
        Log.d(TAG, "showText: text=" + (text != null && text.length() > 0 ? text.substring(0, Math.min(20, text.length())) + "..." : "empty"));
        Log.d(TAG, "showText: mSubtitleView=" + mSubtitleView + " (hashCode=" + System.identityHashCode(mSubtitleView) + ")");
        Log.d(TAG, "showText: isAttachedToWindow=" + isAttached + ", size=" + width + "x" + height);
        Log.d(TAG, "showText: parent=" + (parent != null ? parent.getClass().getSimpleName() : "null") + 
                   " (hashCode=" + (parent != null ? System.identityHashCode(parent) : 0) + ")");
        
        if (!isAttached) {
            Log.e(TAG, "showText: SubtitleView is NOT attached to window! Subtitle will not be visible.");
            Log.e(TAG, "showText: This is likely a stale SubtitleView instance from before fullscreen switch.");
            Log.e(TAG, "showText: Need to call reattachToPlayer() after fullscreen switch!");
            return; // 不显示，因为视图未附加
        }
        
        // 禁用动画，直接显示（语音识别需要立即显示，不能等待淡入动画）
        mSubtitleView.setAnimationEnabled(false);
        
        // 禁用自动隐藏（语音识别字幕由外部控制显示时间）
        mSubtitleView.setAutoHideEnabled(false);
        
        // 直接设置文本和可见性，不使用 setSubtitleText（避免动画问题）
        if (text == null || text.isEmpty()) {
            mSubtitleView.setText("");
            mSubtitleView.setVisibility(View.GONE);
        } else {
            mSubtitleView.setText(text);
            mSubtitleView.setVisibility(View.VISIBLE);
            mSubtitleView.setAlpha(1f);
            
            // 强制立即测量和布局，确保尺寸正确
            // 关键：从 GONE 变为 VISIBLE 时，必须先 measure 再 layout
            if (width == 0 || height == 0) {
                // 获取父容器尺寸
                if (parent instanceof android.view.ViewGroup) {
                    android.view.ViewGroup parentView = (android.view.ViewGroup) parent;
                    int parentWidth = parentView.getWidth();
                    int parentHeight = parentView.getHeight();
                    
                    Log.d(TAG, "showText: parent size=" + parentWidth + "x" + parentHeight);
                    
                    // 强制测量（使用父容器尺寸作为约束）
                    int widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                        parentWidth - dpToPx(40), // 减去左右边距
                        android.view.View.MeasureSpec.AT_MOST);
                    int heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                        parentHeight,
                        android.view.View.MeasureSpec.AT_MOST);
                    mSubtitleView.measure(widthSpec, heightSpec);
                    
                    // 强制布局
                    android.view.ViewGroup.LayoutParams lp = mSubtitleView.getLayoutParams();
                    if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                        android.widget.FrameLayout.LayoutParams flp = (android.widget.FrameLayout.LayoutParams) lp;
                        int left = flp.leftMargin;
                        int top = parentHeight - mSubtitleView.getMeasuredHeight() - flp.bottomMargin;
                        int right = left + mSubtitleView.getMeasuredWidth();
                        int bottom = top + mSubtitleView.getMeasuredHeight();
                        mSubtitleView.layout(left, top, right, bottom);
                        
                        Log.d(TAG, "showText: forced layout, new size=" + mSubtitleView.getWidth() + "x" + mSubtitleView.getHeight());
                    }
                }
            }
            
            // 确保重绘
            mSubtitleView.invalidate();
        }
        
        Log.d(TAG, "showText: visibility=" + mSubtitleView.getVisibility() + ", alpha=" + mSubtitleView.getAlpha());
    }
    
    public void toggle() {
        if (mEnabled) {
            hide();
        } else {
            show();
        }
    }
    
    public boolean isEnabled() {
        return mEnabled;
    }
    
    public boolean isLoaded() {
        return mLoaded;
    }
    
    public void setTextSize(float size) {
        mTextSize = size;
        if (mSubtitleView != null) {
            mSubtitleView.setTextSize(size);
        }
        if (mMedia3SubtitleView != null) {
            mMedia3SubtitleView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size);
        }
    }
    
    public void setTextColor(int color) {
        mTextColor = color;
        if (mSubtitleView != null) {
            mSubtitleView.setTextColor(color);
        }
        if (mMedia3SubtitleView != null) {
            mMedia3SubtitleView.setStyle(new CaptionStyleCompat(
                    color, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK,
                    null));
        }
    }
    
    public void setBottomMargin(int marginDp) {
        mBottomMargin = marginDp;
        if (mSubtitleView != null && mSubtitleView.getParent() != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mSubtitleView.getLayoutParams();
            params.bottomMargin = dpToPx(marginDp);
            mSubtitleView.setLayoutParams(params);
        }
    }
    
    public void clear() {
        mSubtitles.clear();
        mLoaded = false;
        mCurrentSubtitlePath = null;
        if (mSubtitleView != null) {
            mSubtitleView.setText("");
            mSubtitleView.setVisibility(View.GONE);
        }
        clearMedia3Cues();
    }

    public void release() {
        stop();
        clear();
        if (mSubtitleView != null && mSubtitleView.getParent() != null) {
            ((ViewGroup) mSubtitleView.getParent()).removeView(mSubtitleView);
        }
        if (mMedia3SubtitleView != null && mMedia3SubtitleView.getParent() != null) {
            ((ViewGroup) mMedia3SubtitleView.getParent()).removeView(mMedia3SubtitleView);
        }
        mMedia3SubtitleView = null;
        mPlayerContainer = null;
        mSubtitleView = null;
        mExecutor.shutdown();
    }
    
    public String getCurrentSubtitlePath() {
        return mCurrentSubtitlePath;
    }
    
    public int getSubtitleCount() {
        return mSubtitles.size();
    }
    
    private int dpToPx(int dp) {
        return (int) (dp * mContext.getResources().getDisplayMetrics().density);
    }
}
