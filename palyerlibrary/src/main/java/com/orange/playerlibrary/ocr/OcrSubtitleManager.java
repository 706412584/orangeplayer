package com.orange.playerlibrary.ocr;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;

import com.shuyu.gsyvideoplayer.listener.GSYVideoShotListener;
import com.shuyu.gsyvideoplayer.render.GSYRenderView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OCR 字幕管理器
 * 负责截帧、OCR 识别、翻译和字幕显示
 */
public class OcrSubtitleManager {
    
    private static final String TAG = "OcrSubtitleManager";
    
    // SharedPreferences 配置
    private static final String PREF_NAME = "ocr_subtitle_settings";
    private static final String PREF_SCAN_REGION_LEFT = "scan_region_left";
    private static final String PREF_SCAN_REGION_TOP = "scan_region_top";
    private static final String PREF_SCAN_REGION_RIGHT = "scan_region_right";
    private static final String PREF_SCAN_REGION_BOTTOM = "scan_region_bottom";
    
    // 默认配置
    private static final int DEFAULT_FRAME_INTERVAL = 1000; // 1秒截取一帧
    private static final float DEFAULT_SUBTITLE_REGION_TOP = 0.82f; // 字幕区域从82%开始
    private static final float DEFAULT_SUBTITLE_REGION_BOTTOM = 0.95f; // 字幕区域到95%结束
    
    private Context mContext;
    private OcrEngine mOcrEngine;
    private TranslationEngine mTranslationEngine;
    
    private HandlerThread mOcrThread;
    private Handler mOcrHandler;
    private Handler mMainHandler;
    
    private boolean mIsRunning = false;
    private int mFrameInterval = DEFAULT_FRAME_INTERVAL;
    private float mSubtitleRegionTop = DEFAULT_SUBTITLE_REGION_TOP;
    private float mSubtitleRegionBottom = DEFAULT_SUBTITLE_REGION_BOTTOM;
    
    // 自定义扫描区域 (Requirements: 8.5)
    private OcrScanRegionView.ScanRegion mScanRegion;
    private SharedPreferences mPrefs;
    
    private String mSourceLanguage = "chi_sim"; // 默认中文
    private String mTargetLanguage = "en"; // 默认翻译成英文
    
    private String mLastRecognizedText = "";
    private OcrSubtitleCallback mCallback;
    
    private View mVideoView; // TextureView 或 SurfaceView
    private GSYRenderView mRenderProxy; // GSY 渲染视图引用，用于截图
    
    /**
     * OCR 字幕回调
     */
    public interface OcrSubtitleCallback {
        /**
         * 识别到字幕
         * @param originalText 原文
         * @param translatedText 译文（如果启用翻译）
         */
        void onSubtitleRecognized(String originalText, String translatedText);
        
        /**
         * 错误回调
         */
        void onError(String error);
    }
    
    public OcrSubtitleManager(Context context) {
        mContext = context.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
        mPrefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        
        // 加载持久化的扫描区域设置
        loadScanRegion();
    }
    
    /**
     * 检查 OCR 功能是否可用
     */
    public static boolean isAvailable() {
        return OcrAvailabilityChecker.isTesseractAvailable();
    }
    
    /**
     * 检查翻译功能是否可用
     */
    public static boolean isTranslationAvailable() {
        return OcrAvailabilityChecker.isMlKitTranslateAvailable();
    }
    
    /**
     * 获取缺失依赖提示
     */
    public static String getMissingDependenciesMessage() {
        return OcrAvailabilityChecker.getMissingDependenciesMessage();
    }
    
    /**
     * 初始化 OCR 引擎
     * @param sourceLanguage 源语言 (chi_sim, eng, jpn, kor)
     * @return 是否成功
     */
    public boolean initOcr(String sourceLanguage) {
        if (!isAvailable()) {
            Log.e(TAG, "OCR not available");
            return false;
        }
        
        mSourceLanguage = sourceLanguage;
        mOcrEngine = new TesseractOcrEngine();
        return mOcrEngine.init(mContext, sourceLanguage);
    }
    
    /**
     * 初始化翻译引擎
     * @param targetLanguage 目标语言
     * @param callback 模型下载回调
     */
    public void initTranslation(String targetLanguage, TranslationEngine.ModelDownloadCallback callback) {
        if (!isTranslationAvailable()) {
            Log.e(TAG, "Translation not available");
            if (callback != null) {
                callback.onError("Translation library not installed");
            }
            return;
        }
        
        mTargetLanguage = targetLanguage;
        final MlKitTranslationEngine engine = new MlKitTranslationEngine();
        mTranslationEngine = engine;
        engine.init(mContext, mSourceLanguage, targetLanguage);
        if (!engine.isInitialized()) {
            // 不带原因的话，UI 只能显示 downloadModel 抛出的
            // 「Translator not initialized」——那是后果不是原因
            String reason = engine.getLastError();
            Log.e(TAG, "翻译引擎初始化失败: " + reason);
            if (callback != null) {
                callback.onError("翻译引擎初始化失败"
                        + (reason == null ? "" : "：" + reason));
            }
            return;
        }

        // 下载模型
        engine.downloadModel(callback);
    }
    
    /**
     * 设置视频视图（用于截帧）
     */
    public void setVideoView(View videoView) {
        mVideoView = videoView;
    }
    
    /**
     * 设置 GSY 渲染视图（用于 taskShotPic 截图）
     */
    public void setRenderProxy(GSYRenderView renderProxy) {
        mRenderProxy = renderProxy;
        Log.d(TAG, "setRenderProxy: " + renderProxy);
    }
    
    /**
     * 设置回调
     */
    public void setCallback(OcrSubtitleCallback callback) {
        mCallback = callback;
    }
    
    /**
     * 设置截帧间隔
     * @param intervalMs 间隔毫秒
     */
    public void setFrameInterval(int intervalMs) {
        mFrameInterval = intervalMs;
    }
    
    /**
     * 设置字幕区域
     * @param top 顶部位置 (0-1)
     * @param bottom 底部位置 (0-1)
     */
    public void setSubtitleRegion(float top, float bottom) {
        mSubtitleRegionTop = top;
        mSubtitleRegionBottom = bottom;
    }
    
    // ==================== 扫描区域管理 (Requirements: 8.5, 8.6) ====================
    
    /**
     * 设置扫描区域
     * @param region 扫描区域（使用相对比例 0-1）
     */
    public void setScanRegion(OcrScanRegionView.ScanRegion region) {
        if (region != null && region.isValid()) {
            mScanRegion = region.copy();
            // 同步更新旧的字幕区域字段（兼容性）
            mSubtitleRegionTop = region.top;
            mSubtitleRegionBottom = region.bottom;
            // 持久化保存
            saveScanRegion();
            Log.d(TAG, "setScanRegion: " + mScanRegion);
        }
    }
    
    /**
     * 获取当前扫描区域
     * @return 扫描区域的副本
     */
    public OcrScanRegionView.ScanRegion getScanRegion() {
        if (mScanRegion == null) {
            mScanRegion = OcrScanRegionView.ScanRegion.getDefault();
        }
        return mScanRegion.copy();
    }
    
    /**
     * 重置扫描区域为默认值
     */
    public void resetScanRegion() {
        mScanRegion = OcrScanRegionView.ScanRegion.getDefault();
        mSubtitleRegionTop = mScanRegion.top;
        mSubtitleRegionBottom = mScanRegion.bottom;
        saveScanRegion();
        Log.d(TAG, "resetScanRegion: " + mScanRegion);
    }
    
    /**
     * 从 SharedPreferences 加载扫描区域设置
     */
    private void loadScanRegion() {
        float left = mPrefs.getFloat(PREF_SCAN_REGION_LEFT, 0f);
        float top = mPrefs.getFloat(PREF_SCAN_REGION_TOP, 0.8f);
        float right = mPrefs.getFloat(PREF_SCAN_REGION_RIGHT, 1f);
        float bottom = mPrefs.getFloat(PREF_SCAN_REGION_BOTTOM, 1f);
        
        mScanRegion = new OcrScanRegionView.ScanRegion(left, top, right, bottom);
        
        // 验证加载的区域是否有效
        if (!mScanRegion.isValid()) {
            Log.w(TAG, "Loaded scan region is invalid, resetting to default");
            mScanRegion = OcrScanRegionView.ScanRegion.getDefault();
        }
        
        // 同步更新旧的字幕区域字段（兼容性）
        mSubtitleRegionTop = mScanRegion.top;
        mSubtitleRegionBottom = mScanRegion.bottom;
        
        Log.d(TAG, "loadScanRegion: " + mScanRegion);
    }
    
    /**
     * 保存扫描区域设置到 SharedPreferences
     */
    private void saveScanRegion() {
        if (mScanRegion == null) return;
        
        mPrefs.edit()
                .putFloat(PREF_SCAN_REGION_LEFT, mScanRegion.left)
                .putFloat(PREF_SCAN_REGION_TOP, mScanRegion.top)
                .putFloat(PREF_SCAN_REGION_RIGHT, mScanRegion.right)
                .putFloat(PREF_SCAN_REGION_BOTTOM, mScanRegion.bottom)
                .apply();
        
        Log.d(TAG, "saveScanRegion: " + mScanRegion);
    }
    
    /**
     * 使用自定义区域裁剪视频帧
     * Requirements: 8.6
     * @param fullFrame 完整的视频帧
     * @return 裁剪后的区域图像，如果失败返回 null
     */
    public Bitmap captureRegion(Bitmap fullFrame) {
        if (fullFrame == null) {
            Log.w(TAG, "captureRegion: fullFrame is null");
            return null;
        }
        
        if (mScanRegion == null) {
            mScanRegion = OcrScanRegionView.ScanRegion.getDefault();
        }
        
        int frameWidth = fullFrame.getWidth();
        int frameHeight = fullFrame.getHeight();
        
        // 根据 ScanRegion 计算实际像素区域
        int left = (int) (mScanRegion.left * frameWidth);
        int top = (int) (mScanRegion.top * frameHeight);
        int right = (int) (mScanRegion.right * frameWidth);
        int bottom = (int) (mScanRegion.bottom * frameHeight);
        
        // 确保边界有效
        left = Math.max(0, Math.min(frameWidth - 1, left));
        top = Math.max(0, Math.min(frameHeight - 1, top));
        right = Math.max(left + 1, Math.min(frameWidth, right));
        bottom = Math.max(top + 1, Math.min(frameHeight, bottom));
        
        int cropWidth = right - left;
        int cropHeight = bottom - top;
        
        if (cropWidth <= 0 || cropHeight <= 0) {
            Log.w(TAG, "captureRegion: invalid crop dimensions " + cropWidth + "x" + cropHeight);
            return null;
        }
        
        Log.d(TAG, "captureRegion: frame=" + frameWidth + "x" + frameHeight + 
                   ", region=" + mScanRegion + 
                   ", pixels=[" + left + "," + top + "," + right + "," + bottom + "]" +
                   ", crop=" + cropWidth + "x" + cropHeight);
        
        try {
            return Bitmap.createBitmap(fullFrame, left, top, cropWidth, cropHeight);
        } catch (Exception e) {
            Log.e(TAG, "captureRegion: failed to crop bitmap", e);
            return null;
        }
    }
    
    /**
     * 开始 OCR 识别
     */
    public void start() {
        Log.d(TAG, "start() called, mIsRunning=" + mIsRunning);
        
        if (mIsRunning) {
            Log.w(TAG, "Already running, skip");
            return;
        }
        
        if (mOcrEngine == null || !mOcrEngine.isInitialized()) {
            Log.e(TAG, "OCR engine not initialized, mOcrEngine=" + mOcrEngine);
            return;
        }
        
        Log.d(TAG, "mVideoView=" + mVideoView + ", mCallback=" + mCallback);
        
        mIsRunning = true;
        
        // 创建后台线程
        mOcrThread = new HandlerThread("OcrThread");
        mOcrThread.start();
        mOcrHandler = new Handler(mOcrThread.getLooper());
        
        // 开始定时截帧
        mOcrHandler.post(mOcrRunnable);
        
        Log.d(TAG, "OCR subtitle started, interval=" + mFrameInterval + "ms");
    }
    
    /**
     * 停止 OCR 识别
     */
    public void stop() {
        mIsRunning = false;
        
        if (mOcrHandler != null) {
            mOcrHandler.removeCallbacks(mOcrRunnable);
        }
        
        if (mOcrThread != null) {
            mOcrThread.quitSafely();
            mOcrThread = null;
        }
        
        mOcrHandler = null;
        
        Log.d(TAG, "OCR subtitle stopped");
    }
    
    /**
     * 释放资源
     */
    public void release() {
        stop();
        
        if (mOcrEngine != null) {
            mOcrEngine.release();
            mOcrEngine = null;
        }
        
        if (mTranslationEngine != null) {
            mTranslationEngine.release();
            mTranslationEngine = null;
        }
    }
    
    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return mIsRunning;
    }
    
    private int mFrameCount = 0;
    private static final boolean DEBUG_SAVE_SCREENSHOT = false; // 调试：保存截图到文件
    
    private final Runnable mOcrRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsRunning) {
                Log.d(TAG, "mOcrRunnable: not running, exit");
                return;
            }
            
            mFrameCount++;
            Log.d(TAG, "=== OCR Frame #" + mFrameCount + " ===");
            
            // 截取视频帧
            Bitmap frame = captureFrame();
            Log.d(TAG, "captureFrame result: " + (frame != null ? frame.getWidth() + "x" + frame.getHeight() : "null"));
            
            if (frame != null) {
                // 调试：保存截图到文件
                if (DEBUG_SAVE_SCREENSHOT && mFrameCount <= 3) {
                    saveDebugBitmap(frame, "ocr_frame_" + mFrameCount + ".png");
                }
                
                // 裁剪字幕区域
                Bitmap subtitleRegion = cropSubtitleRegion(frame);
                Log.d(TAG, "cropSubtitleRegion result: " + (subtitleRegion != null ? subtitleRegion.getWidth() + "x" + subtitleRegion.getHeight() : "null"));
                
                // 调试：保存字幕区域截图
                if (DEBUG_SAVE_SCREENSHOT && mFrameCount <= 3 && subtitleRegion != null) {
                    saveDebugBitmap(subtitleRegion, "ocr_subtitle_" + mFrameCount + ".png");
                }
                
                frame.recycle();
                
                if (subtitleRegion != null) {
                    // OCR 识别
                    long startTime = System.currentTimeMillis();
                    String text = mOcrEngine.recognize(subtitleRegion);
                    long ocrTime = System.currentTimeMillis() - startTime;
                    subtitleRegion.recycle();
                    
                    Log.d(TAG, "OCR recognize took " + ocrTime + "ms, result: [" + (text != null ? text.replace("\n", "\\n") : "null") + "]");
                    
                    if (text != null && !text.isEmpty() && !text.equals(mLastRecognizedText)) {
                        Log.d(TAG, "New text detected, lastText was: [" + mLastRecognizedText.replace("\n", "\\n") + "]");
                        mLastRecognizedText = text;
                        
                        // 翻译（如果启用）
                        if (mTranslationEngine != null && mTranslationEngine.isInitialized()) {
                            Log.d(TAG, "Translating text...");
                            mTranslationEngine.translate(text, new TranslationEngine.TranslationCallback() {
                                @Override
                                public void onSuccess(String translatedText) {
                                    Log.d(TAG, "Translation success: [" + translatedText + "]");
                                    notifySubtitle(text, translatedText);
                                }
                                
                                @Override
                                public void onError(String error) {
                                    Log.e(TAG, "Translation error: " + error);
                                    // 翻译失败，只显示原文
                                    notifySubtitle(text, null);
                                }
                            });
                        } else {
                            Log.d(TAG, "No translation engine, showing original only");
                            // 不翻译，只显示原文
                            notifySubtitle(text, null);
                        }
                    } else {
                        if (text == null || text.isEmpty()) {
                            Log.d(TAG, "No text recognized");
                        } else {
                            Log.d(TAG, "Same text as before, skip");
                        }
                    }
                }
            } else {
                Log.w(TAG, "Failed to capture frame, mVideoView=" + mVideoView);
            }
            
            // 继续下一帧
            if (mIsRunning && mOcrHandler != null) {
                mOcrHandler.postDelayed(this, mFrameInterval);
            }
        }
    };
    
    /**
     * 截取视频帧
     * 优先使用 PixelCopy（SurfaceView），其次使用 TextureView.getBitmap()
     * 最后回退到 GSY 的 taskShotPic
     */
    private Bitmap captureFrame() {
        if (mVideoView == null && mRenderProxy == null) {
            Log.w(TAG, "captureFrame: no video view available");
            return null;
        }
        
        // 1. 尝试从 SurfaceView 使用 PixelCopy 截图（最可靠的方式）
        SurfaceView surfaceView = findSurfaceView();
        if (surfaceView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Bitmap bitmap = captureFromSurfaceViewDirect(surfaceView);
            if (bitmap != null) {
                return bitmap;
            }
        }
        
        // 2. 尝试从 TextureView 截图
        TextureView textureView = findTextureView();
        if (textureView != null && textureView.isAvailable()) {
            Bitmap bitmap = textureView.getBitmap();
            if (bitmap != null) {
                Log.d(TAG, "TextureView.getBitmap() success: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                return bitmap;
            }
        }
        
        // 3. 回退到 GSY 的 taskShotPic API
        if (mRenderProxy != null) {
            return captureFromRenderProxy();
        }
        
        Log.w(TAG, "captureFrame: all methods failed");
        return null;
    }
    
    /**
     * 查找 SurfaceView
     */
    private SurfaceView findSurfaceView() {
        // 从 mVideoView 查找
        if (mVideoView instanceof SurfaceView) {
            return (SurfaceView) mVideoView;
        }
        if (mVideoView instanceof android.view.ViewGroup) {
            SurfaceView sv = findSurfaceViewInGroup((android.view.ViewGroup) mVideoView);
            if (sv != null) return sv;
        }
        
        // 从 RenderProxy 查找
        if (mRenderProxy != null) {
            View showView = mRenderProxy.getShowView();
            if (showView instanceof SurfaceView) {
                return (SurfaceView) showView;
            }
        }
        
        return null;
    }
    
    /**
     * 递归查找 SurfaceView
     */
    private SurfaceView findSurfaceViewInGroup(android.view.ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof SurfaceView) {
                return (SurfaceView) child;
            }
            if (child instanceof android.view.ViewGroup) {
                SurfaceView sv = findSurfaceViewInGroup((android.view.ViewGroup) child);
                if (sv != null) return sv;
            }
        }
        return null;
    }
    
    /**
     * 查找 TextureView
     */
    private TextureView findTextureView() {
        // 从 mVideoView 查找
        if (mVideoView instanceof TextureView) {
            return (TextureView) mVideoView;
        }
        if (mVideoView instanceof android.view.ViewGroup) {
            TextureView tv = findTextureViewInGroup((android.view.ViewGroup) mVideoView);
            if (tv != null) return tv;
        }
        
        // 从 RenderProxy 查找
        if (mRenderProxy != null) {
            View showView = mRenderProxy.getShowView();
            if (showView instanceof TextureView) {
                return (TextureView) showView;
            }
        }
        
        return null;
    }
    
    /**
     * 递归查找 TextureView
     */
    private TextureView findTextureViewInGroup(android.view.ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextureView) {
                return (TextureView) child;
            }
            if (child instanceof android.view.ViewGroup) {
                TextureView tv = findTextureViewInGroup((android.view.ViewGroup) child);
                if (tv != null) return tv;
            }
        }
        return null;
    }
    
    /**
     * 直接从 SurfaceView 截图 (使用 PixelCopy API, Android O+)
     * 注意：当使用 SurfaceControl.reparent() 时，需要使用 Window 级别的 PixelCopy
     */
    private Bitmap captureFromSurfaceViewDirect(SurfaceView surfaceView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        
        // 检查 Surface 是否有效
        if (surfaceView.getHolder() == null || surfaceView.getHolder().getSurface() == null 
            || !surfaceView.getHolder().getSurface().isValid()) {
            Log.w(TAG, "SurfaceView surface not valid");
            return null;
        }
        
        int width = surfaceView.getWidth();
        int height = surfaceView.getHeight();
        
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "SurfaceView size invalid: " + width + "x" + height);
            return null;
        }
        
        // 先尝试直接从 SurfaceView 截图
        Bitmap bitmap = captureFromSurfaceViewInternal(surfaceView, width, height);
        if (bitmap != null) {
            return bitmap;
        }
        
        // 如果失败（可能是 SurfaceControl.reparent 模式），尝试从 Window 截图
        Log.d(TAG, "SurfaceView PixelCopy failed, trying Window PixelCopy");
        return captureFromWindow(surfaceView);
    }
    
    /**
     * 从 SurfaceView 内部截图
     */
    private Bitmap captureFromSurfaceViewInternal(SurfaceView surfaceView, int width, int height) {
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final AtomicReference<Integer> resultRef = new AtomicReference<>(-1);
        final CountDownLatch latch = new CountDownLatch(1);
        
        try {
            PixelCopy.request(
                surfaceView,
                bitmap,
                copyResult -> {
                    resultRef.set(copyResult);
                    latch.countDown();
                },
                mMainHandler
            );
            
            // 等待最多 300ms
            boolean completed = latch.await(300, TimeUnit.MILLISECONDS);
            
            if (!completed) {
                Log.w(TAG, "SurfaceView PixelCopy timeout");
                bitmap.recycle();
                return null;
            }
            
            int result = resultRef.get();
            if (result == PixelCopy.SUCCESS) {
                Log.d(TAG, "SurfaceView PixelCopy success: " + width + "x" + height);
                return bitmap;
            } else {
                Log.d(TAG, "SurfaceView PixelCopy failed with result: " + result);
                bitmap.recycle();
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "SurfaceView PixelCopy exception", e);
            bitmap.recycle();
            return null;
        }
    }
    
    /**
     * 从 Window 截图并裁剪出视频区域
     * 用于 SurfaceControl.reparent() 模式下的截图
     */
    private Bitmap captureFromWindow(SurfaceView surfaceView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        
        // 获取 Activity 的 Window
        android.app.Activity activity = getActivityFromView(surfaceView);
        if (activity == null || activity.getWindow() == null) {
            Log.w(TAG, "Cannot get Activity Window");
            return null;
        }
        
        android.view.Window window = activity.getWindow();
        
        // 获取 SurfaceView 在屏幕上的位置
        int[] location = new int[2];
        surfaceView.getLocationOnScreen(location);
        int left = location[0];
        int top = location[1];
        int width = surfaceView.getWidth();
        int height = surfaceView.getHeight();
        
        // 获取窗口尺寸
        android.view.View decorView = window.getDecorView();
        int windowWidth = decorView.getWidth();
        int windowHeight = decorView.getHeight();
        
        if (windowWidth <= 0 || windowHeight <= 0) {
            Log.w(TAG, "Window size invalid: " + windowWidth + "x" + windowHeight);
            return null;
        }
        
        // 创建窗口大小的 Bitmap
        final Bitmap windowBitmap = Bitmap.createBitmap(windowWidth, windowHeight, Bitmap.Config.ARGB_8888);
        final AtomicReference<Integer> resultRef = new AtomicReference<>(-1);
        final CountDownLatch latch = new CountDownLatch(1);
        
        try {
            PixelCopy.request(
                window,
                windowBitmap,
                copyResult -> {
                    resultRef.set(copyResult);
                    latch.countDown();
                },
                mMainHandler
            );
            
            // 等待最多 500ms
            boolean completed = latch.await(500, TimeUnit.MILLISECONDS);
            
            if (!completed) {
                Log.w(TAG, "Window PixelCopy timeout");
                windowBitmap.recycle();
                return null;
            }
            
            int result = resultRef.get();
            if (result == PixelCopy.SUCCESS) {
                Log.d(TAG, "Window PixelCopy success, cropping video region: " + left + "," + top + " " + width + "x" + height);
                
                // 裁剪出视频区域
                // 确保裁剪区域在有效范围内
                int cropLeft = Math.max(0, left);
                int cropTop = Math.max(0, top);
                int cropWidth = Math.min(width, windowWidth - cropLeft);
                int cropHeight = Math.min(height, windowHeight - cropTop);
                
                if (cropWidth <= 0 || cropHeight <= 0) {
                    Log.w(TAG, "Invalid crop region");
                    windowBitmap.recycle();
                    return null;
                }
                
                Bitmap croppedBitmap = Bitmap.createBitmap(windowBitmap, cropLeft, cropTop, cropWidth, cropHeight);
                windowBitmap.recycle();
                
                Log.d(TAG, "Cropped video bitmap: " + croppedBitmap.getWidth() + "x" + croppedBitmap.getHeight());
                return croppedBitmap;
            } else {
                Log.w(TAG, "Window PixelCopy failed with result: " + result);
                windowBitmap.recycle();
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Window PixelCopy exception", e);
            windowBitmap.recycle();
            return null;
        }
    }
    
    /**
     * 从 View 获取 Activity
     */
    private android.app.Activity getActivityFromView(View view) {
        android.content.Context context = view.getContext();
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof android.app.Activity) {
                return (android.app.Activity) context;
            }
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }
    
    /**
     * 使用 GSY RenderProxy 的 taskShotPic 截图
     */
    private Bitmap captureFromRenderProxy() {
        if (mRenderProxy == null) {
            Log.w(TAG, "captureFromRenderProxy: mRenderProxy is null");
            return null;
        }
        
        final AtomicReference<Bitmap> bitmapRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        
        try {
            // 在主线程调用 taskShotPic
            mMainHandler.post(() -> {
                try {
                    mRenderProxy.taskShotPic(bitmap -> {
                        Log.d(TAG, "taskShotPic callback: " + (bitmap != null ? bitmap.getWidth() + "x" + bitmap.getHeight() + ", config=" + bitmap.getConfig() : "null"));
                        if (bitmap != null) {
                            // 复制 bitmap 为 ARGB_8888 格式，确保 Tesseract 能读取
                            Bitmap.Config config = bitmap.getConfig();
                            if (config == null || config == Bitmap.Config.HARDWARE) {
                                // HARDWARE 配置的 bitmap 需要转换为软件 bitmap
                                Bitmap softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                                bitmapRef.set(softwareBitmap);
                            } else {
                                bitmapRef.set(bitmap.copy(Bitmap.Config.ARGB_8888, false));
                            }
                        }
                        latch.countDown();
                    }, false);
                } catch (Exception e) {
                    Log.e(TAG, "taskShotPic exception", e);
                    latch.countDown();
                }
            });
            
            // 等待最多 500ms（缩短超时时间）
            boolean completed = latch.await(500, TimeUnit.MILLISECONDS);
            
            if (!completed) {
                Log.w(TAG, "taskShotPic timeout, trying fallback to PixelCopy");
                // 回退到直接使用 PixelCopy
                SurfaceView sv = findSurfaceView();
                if (sv != null) {
                    return captureFromSurfaceViewDirect(sv);
                }
                return null;
            }
            
            Bitmap result = bitmapRef.get();
            Log.d(TAG, "captureFromRenderProxy result: " + (result != null ? result.getWidth() + "x" + result.getHeight() + ", config=" + result.getConfig() : "null"));
            return result;
        } catch (Exception e) {
            Log.e(TAG, "captureFromRenderProxy exception", e);
            return null;
        }
    }
    
    /**
     * 裁剪字幕区域
     * 使用自定义扫描区域 (Requirements: 8.6)
     */
    private Bitmap cropSubtitleRegion(Bitmap frame) {
        if (frame == null) {
            return null;
        }
        
        // 使用自定义扫描区域（如果已设置）
        if (mScanRegion != null && mScanRegion.isValid()) {
            return captureRegion(frame);
        }
        
        // 回退到旧的 top/bottom 方式（兼容性）
        int width = frame.getWidth();
        int height = frame.getHeight();
        
        int top = (int) (height * mSubtitleRegionTop);
        int bottom = (int) (height * mSubtitleRegionBottom);
        int cropHeight = bottom - top;
        
        // 打印字幕识别区域信息
        Log.d(TAG, "=== 字幕识别区域 (legacy) ===");
        Log.d(TAG, "视频尺寸: " + width + "x" + height);
        Log.d(TAG, "区域比例: " + (mSubtitleRegionTop * 100) + "% - " + (mSubtitleRegionBottom * 100) + "%");
        Log.d(TAG, "区域像素: y=" + top + " 到 y=" + bottom + ", 高度=" + cropHeight + "px");
        
        if (cropHeight <= 0) {
            return null;
        }
        
        try {
            return Bitmap.createBitmap(frame, 0, top, width, cropHeight);
        } catch (Exception e) {
            Log.e(TAG, "Failed to crop subtitle region", e);
            return null;
        }
    }
    
    /**
     * 通知字幕识别结果
     */
    private void notifySubtitle(String originalText, String translatedText) {
        if (mCallback != null) {
            mMainHandler.post(() -> {
                mCallback.onSubtitleRecognized(originalText, translatedText);
            });
        }
    }
    
    /**
     * 调试：保存 Bitmap 到文件
     */
    private void saveDebugBitmap(Bitmap bitmap, String filename) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "OcrDebug");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, filename);
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            Log.d(TAG, "Debug screenshot saved: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save debug screenshot", e);
        }
    }
}
