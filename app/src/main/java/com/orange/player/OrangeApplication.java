package com.orange.player;

import android.app.Application;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.Nullable;

import com.orange.playerlibrary.cache.ExternalProxyCacheManager;
import com.orange.playerlibrary.OrangePlayerConfig;
import com.orange.playerlibrary.download.VideoDownloaderWrapper;
import com.orange.playerlibrary.utils.TvUtils;
import com.shuyu.gsyvideoplayer.cache.CacheFactory;

import java.io.File;

/**
 * 应用程序类
 * 负责全局初始化
 */
public class OrangeApplication extends Application {
    
    private static final String TAG = "OrangeApplication";
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        // 自动检测 TV 模式
        boolean isTvDevice = TvUtils.isTvDevice(this);
        OrangePlayerConfig.setTvMode(isTvDevice);
        
        if (isTvDevice) {
            Log.d(TAG, "TV device detected, TV mode enabled");
            // TV设备禁用缓存（避免网络问题）
            CacheFactory.setCacheManager(null);
        } else {
            Log.d(TAG, "Mobile/Tablet device detected, standard mode enabled");
            // 配置边看边存缓存
            initPlayCache();
        }
        
        // 初始化 VideoDownloader（用于 M3U8、MP4 等视频下载）
        try {
            VideoDownloaderWrapper downloaderWrapper = VideoDownloaderWrapper.getInstance(this);
            downloaderWrapper.init();
            Log.d(TAG, "VideoDownloader initialized successfully");
            Log.d(TAG, "FFmpeg merge enabled=" + downloaderWrapper.isFfmpegMergeEnabled()
                    + ", java fallback enabled=" + downloaderWrapper.isJavaMergeFallbackEnabled());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize VideoDownloader", e);
        }

        // P6: 注册 Exo OkHttp DataSource 拦截器（经 OrangePlayerConfig
        // 全局开关控制，默认关闭；关闭时 getHttpDataSourceFactory 返回
        // null，ExoSourceManager 回退默认 DefaultHttpDataSource）
        com.orange.playerlibrary.OrangePlayerConfig.setOkHttpDataSourceEnabled(true);
        try {
            tv.danmaku.ijk.media.exo2.ExoSourceManager.setExoMediaSourceInterceptListener(
                    new tv.danmaku.ijk.media.exo2.ExoMediaSourceInterceptListener() {
                        private final okhttp3.OkHttpClient sharedClient =
                                new okhttp3.OkHttpClient.Builder().build();

                        @Nullable
                        @Override
                        public androidx.media3.exoplayer.source.MediaSource getMediaSource(
                                String dataSource, boolean preview, boolean cacheEnable,
                                boolean isLooping, java.io.File cacheDir) {
                            return null; // 不替换 MediaSource
                        }

                        @Nullable
                        @Override
                        public androidx.media3.datasource.DataSource.Factory getHttpDataSourceFactory(
                                String userAgent,
                                androidx.media3.datasource.TransferListener listener,
                                int connectTimeoutMillis, int readTimeoutMillis,
                                java.util.Map<String, String> mapHeadData,
                                boolean allowCrossProtocolRedirects) {
                            if (!com.orange.playerlibrary.OrangePlayerConfig.isOkHttpDataSourceEnabled()) {
                                return null; // → ExoSourceManager 默认 DefaultHttpDataSource
                            }
                            if (mapHeadData == null) {
                                mapHeadData = new java.util.HashMap<>();
                            }
                            okhttp3.OkHttpClient requestClient = sharedClient.newBuilder()
                                    .connectTimeout(connectTimeoutMillis,
                                            java.util.concurrent.TimeUnit.MILLISECONDS)
                                    .readTimeout(readTimeoutMillis,
                                            java.util.concurrent.TimeUnit.MILLISECONDS)
                                    .followSslRedirects(allowCrossProtocolRedirects)
                                    .build();
                            Object factory = com.orange.playerlibrary.utils.OkHttpDataSourceHelper
                                    .createFactory(requestClient, mapHeadData);
                            if (!(factory instanceof androidx.media3.datasource.okhttp.OkHttpDataSource.Factory)) {
                                return null;
                            }
                            androidx.media3.datasource.okhttp.OkHttpDataSource.Factory okHttpFactory =
                                    (androidx.media3.datasource.okhttp.OkHttpDataSource.Factory) factory;
                            if (userAgent != null) {
                                okHttpFactory.setUserAgent(userAgent);
                            }
                            if (listener != null) {
                                okHttpFactory.setTransferListener(listener);
                            }
                            return okHttpFactory;
                        }

                        @Nullable
                        @Override
                        public androidx.media3.datasource.DataSink.Factory cacheWriteDataSinkFactory(
                                String cachePath, String url) {
                            return null;
                        }
                    });
            Log.d(TAG, "Exo OkHttp DataSource interceptor registered");
        } catch (Throwable t) {
            Log.w(TAG, "OkHttp DataSource 拦截器注册失败（回退默认网络栈）", t);
        }

        // 注意：播放器核心的初始化不在这里进行
        // 因为 GSYVideoManager 需要 Activity Context
        // 播放核心会在 OrangevideoView 第一次初始化时设置
    }
    
    /**
     * 初始化边看边存缓存
     * 缓存路径：/storage/emulated/0/Android/data/com.orange.player/cache/video-cache/
     */
    private void initPlayCache() {
        try {
            Log.d(TAG, "========== initPlayCache START ==========");
            
            // 使用外部存储目录（用户可见）
            File externalCacheDir = getExternalCacheDir();
            File videoCacheDir = new File(externalCacheDir, "video-cache");
            
            // 确保目录存在
            if (!videoCacheDir.exists()) {
                boolean created = videoCacheDir.mkdirs();
                Log.d(TAG, "Video cache directory created: " + created);
            }
            
            Log.d(TAG, "Video cache directory: " + videoCacheDir.getAbsolutePath());
            
            // 设置自定义缓存管理器类
            CacheFactory.setCacheManager(ExternalProxyCacheManager.class);
            
            // 静态设置缓存目录（供ExternalProxyCacheManager使用）
            ExternalProxyCacheManager.setCacheDirectory(videoCacheDir);
            
            Log.d(TAG, "Play cache initialized: " + videoCacheDir.getAbsolutePath());
            Log.d(TAG, "CacheFactory.getCacheManager() = " + CacheFactory.getCacheManager());
            Log.d(TAG, "========== initPlayCache END ==========");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize play cache", e);
        }
    }
}

