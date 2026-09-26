package com.orange.playerlibrary.download;

import android.content.Context;
import android.os.Environment;
import android.widget.Toast;

import com.orange.downloader.VideoDownloadConfig;
import com.orange.downloader.VideoDownloadManager;
import com.orange.downloader.listener.DownloadListener;
import com.orange.downloader.merge.MergeFeatureToggle;
import com.orange.downloader.model.VideoTaskItem;


import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VideoDownloader 包装类
 * 用于下载 M3U8、MP4、FLV 等视频并自动合并
 */
public class VideoDownloaderWrapper {
    
    private Context mContext;
    private static VideoDownloaderWrapper sInstance;
    private Map<String, DownloadCallback> mCallbackMap = new ConcurrentHashMap<>();
    private boolean mInitialized = false;
    private String mCustomDownloadPath;  // 自定义下载路径
    
    private VideoDownloaderWrapper(Context context) {
        mContext = context.getApplicationContext();
    }
    
    public static VideoDownloaderWrapper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (VideoDownloaderWrapper.class) {
                if (sInstance == null) {
                    sInstance = new VideoDownloaderWrapper(context);
                }
            }
        }
        return sInstance;
    }
    
    /**
     * 初始化 VideoDownloader
     * 在 Application.onCreate() 中调用
     */
    public void init() {
        android.util.Log.d("VideoDownloaderWrapper", "========== init() START ==========");

        // 幂等守卫：downloadM3U8() 在未初始化时会自行补调 init()，
        // 而宿主 App 通常已在 Application.onCreate() 里调过一次。
        // 重复执行会重建下载管理器内部状态，白跑一遍配置。
        if (mInitialized) {
            android.util.Log.d("VideoDownloaderWrapper", "Already initialized, skip");
            return;
        }

        // 优先使用自定义下载路径，否则使用应用私有目录
        File cacheDir;
        if (mCustomDownloadPath != null) {
            cacheDir = new File(mCustomDownloadPath);
            android.util.Log.d("VideoDownloaderWrapper", "Using custom download path: " + mCustomDownloadPath);
        } else {
            cacheDir = new File(mContext.getExternalFilesDir(null), "Download");
            android.util.Log.d("VideoDownloaderWrapper", "Using default private directory: " + cacheDir.getAbsolutePath());
        }
        
        android.util.Log.d("VideoDownloaderWrapper", "Cache directory: " + cacheDir.getAbsolutePath());
        
        // 确保目录存在
        if (!cacheDir.exists()) {
            boolean created = cacheDir.mkdirs();
            android.util.Log.d("VideoDownloaderWrapper", "Cache directory created: " + created);
            if (!created) {
                android.util.Log.e("VideoDownloaderWrapper", "FAILED to create cache directory!");
            }
        } else {
            android.util.Log.d("VideoDownloaderWrapper", "Cache directory already exists");
        }
        
        // 验证目录权限
        android.util.Log.d("VideoDownloaderWrapper", "Cache dir exists: " + cacheDir.exists());
        android.util.Log.d("VideoDownloaderWrapper", "Cache dir canWrite: " + cacheDir.canWrite());
        android.util.Log.d("VideoDownloaderWrapper", "Cache dir canRead: " + cacheDir.canRead());
        
        try {
            MergeFeatureToggle.initialize(mContext);
            boolean mergeEnabled = MergeFeatureToggle.isFfmpegMergeEnabled() || MergeFeatureToggle.isJavaFallbackEnabled();

            VideoDownloadConfig config = new VideoDownloadManager.Build(mContext)
                .setCacheRoot(cacheDir.getAbsolutePath())                    // 缓存目录：/storage/emulated/0/Download/orangeplayer
                .setTimeOut(30 * 1000, 30 * 1000)                           // 超时设置
                .setConcurrentCount(3)                                       // 并发下载数
                .setIgnoreCertErrors(true)                                   // 忽略证书错误
                .setShouldM3U8Merged(mergeEnabled)                           // 合并能力由开关控制
                .buildConfig();

                
            android.util.Log.d("VideoDownloaderWrapper", "VideoDownloadConfig created");
            
            VideoDownloadManager manager = VideoDownloadManager.getInstance();
            android.util.Log.d("VideoDownloaderWrapper", "VideoDownloadManager instance: " + manager);
            
            manager.initConfig(config);
            android.util.Log.d("VideoDownloaderWrapper", "VideoDownloadManager.initConfig() called");
            
            // 验证配置
            VideoDownloadConfig verifyConfig = manager.downloadConfig();
            android.util.Log.d("VideoDownloaderWrapper", "Config verification:");
            android.util.Log.d("VideoDownloaderWrapper", "  CacheRoot: " + (verifyConfig != null ? verifyConfig.getCacheRoot() : "null"));
            android.util.Log.d("VideoDownloaderWrapper", "  ConcurrentCount: " + (verifyConfig != null ? verifyConfig.getConcurrentCount() : "null"));
            
            // 设置全局下载监听器
            manager.setGlobalDownloadListener(new DownloadListener() {
                @Override
                public void onDownloadDefault(VideoTaskItem item) {
                    android.util.Log.d("VideoDownloaderWrapper", "Download default: " + item.getUrl());
                }
                
                @Override
                public void onDownloadPending(VideoTaskItem item) {
                    android.util.Log.d("VideoDownloaderWrapper", "Download pending: " + item.getUrl());
                    notifyCallback(item, 0, "等待下载...");
                }
                
                @Override
                public void onDownloadPrepare(VideoTaskItem item) {
                    android.util.Log.d("VideoDownloaderWrapper", "Download prepare: " + item.getUrl());
                    notifyCallback(item, 0, "准备下载...");
                }
                
                @Override
                public void onDownloadStart(VideoTaskItem item) {
                    android.util.Log.d("VideoDownloaderWrapper", "Download start: " + item.getUrl());
                    notifyCallback(item, 0, "开始下载...");
                }
                
                @Override
                public void onDownloadProgress(VideoTaskItem item) {
                    int progress = (int) item.getPercent();
                    String message = String.format("下载中: %.1f%% (%s/%s)", 
                        item.getPercent(),
                        SimpleDownloadManager.formatFileSize(item.getDownloadSize()),
                        SimpleDownloadManager.formatFileSize(item.getTotalSize()));
                    notifyCallback(item, progress, message);
                }
                
                @Override
                public void onDownloadSpeed(VideoTaskItem item) {
                    // 可选：显示下载速度
                }
                
                @Override
                public void onDownloadPause(VideoTaskItem item) {
                    android.util.Log.d("VideoDownloaderWrapper", "Download paused: " + item.getUrl());
                    notifyCallback(item, -1, "下载已暂停");
                }
                
                @Override
                public void onDownloadError(VideoTaskItem item) {
                    String errorMsg = "错误码: " + item.getErrorCode();
                    android.util.Log.e("VideoDownloaderWrapper", "========== Download ERROR ==========");
                    android.util.Log.e("VideoDownloaderWrapper", "Error: " + errorMsg);
                    android.util.Log.e("VideoDownloaderWrapper", "URL: " + item.getUrl());
                    android.util.Log.e("VideoDownloaderWrapper", "FinalUrl: " + item.getFinalUrl());
                    android.util.Log.e("VideoDownloaderWrapper", "FilePath: " + item.getFilePath());
                    android.util.Log.e("VideoDownloaderWrapper", "FileName: " + item.getFileName());
                    android.util.Log.e("VideoDownloaderWrapper", "SaveDir: " + item.getSaveDir());
                    android.util.Log.e("VideoDownloaderWrapper", "TotalSize: " + item.getTotalSize());
                    android.util.Log.e("VideoDownloaderWrapper", "DownloadSize: " + item.getDownloadSize());
                    android.util.Log.e("VideoDownloaderWrapper", "MimeType: " + item.getMimeType());
                    android.util.Log.e("VideoDownloaderWrapper", "VideoType: " + item.getVideoType());
                    
                    // 尝试直接访问 URL 测试连接
                    testUrlConnection(item.getUrl());
                    
                    notifyCallbackError(item, "下载失败: " + errorMsg);
                }
                
                @Override
                public void onDownloadSuccess(VideoTaskItem item) {
                    String filePath = item.getFilePath();
                    android.util.Log.d("VideoDownloaderWrapper", "========== Download SUCCESS ==========");
                    android.util.Log.d("VideoDownloaderWrapper", "FilePath: " + filePath);
                    android.util.Log.d("VideoDownloaderWrapper", "Title: " + item.getTitle());
                    android.util.Log.d("VideoDownloaderWrapper", "FileName: " + item.getFileName());
                    android.util.Log.d("VideoDownloaderWrapper", "FileHash: " + item.getFileHash());
                    
                    // 重命名文件为用户设置的标题
                    String renamedPath = renameFileToTitle(item);
                    if (renamedPath != null) {
                        filePath = renamedPath;
                    }
                    
                    notifyCallbackSuccess(item, filePath);
                }
            });
            
            mInitialized = true;
            android.util.Log.d("VideoDownloaderWrapper", "VideoDownloader initialized successfully");
            android.util.Log.d("VideoDownloaderWrapper", "========== init() END ==========");
        } catch (Exception e) {
            android.util.Log.e("VideoDownloaderWrapper", "Failed to initialize VideoDownloader", e);
            mInitialized = false;
        }
    }
    
    /**
     * 检查 VideoDownloader 是否可用
     */
    public boolean isAvailable() {
        try {
            Class.forName("com.orange.downloader.VideoDownloadManager");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    /**
     * 下载视频（支持 M3U8、MP4、FLV 等所有格式）
     * 
     * @param url 视频 URL
     * @param title 视频标题
     * @param callback 下载回调
     */
    public void downloadM3U8(String url, String title, DownloadCallback callback) {
        downloadM3U8(url, title, null, callback);
    }

    /**
     * 下载视频（可带请求头）。
     *
     * <p>防盗链站点要求清单与分片请求带上 Referer/Cookie 等头，否则分片会 403。
     * 嗅探结果里存的是响应头，不适用于此处；调用方按需传入真正要发的请求头。
     *
     * @param headers 附加到清单/分片请求上的头，可为 null
     */
    public void downloadM3U8(String url, String title, Map<String, String> headers,
                             DownloadCallback callback) {
        android.util.Log.d("VideoDownloaderWrapper", "========== downloadM3U8() START ==========");
        android.util.Log.d("VideoDownloaderWrapper", "URL: " + url);
        android.util.Log.d("VideoDownloaderWrapper", "Title: " + title);
        android.util.Log.d("VideoDownloaderWrapper", "Initialized: " + mInitialized);
        
        if (!isAvailable()) {
            android.util.Log.e("VideoDownloaderWrapper", "VideoDownloader not available (class not found)");
            if (callback != null) {
                callback.onError("VideoDownloader 未初始化");
            }
            return;
        }
        
        if (!mInitialized) {
            android.util.Log.e("VideoDownloaderWrapper", "VideoDownloader not initialized! Calling init()...");
            init();
            if (!mInitialized) {
                android.util.Log.e("VideoDownloaderWrapper", "Failed to initialize VideoDownloader");
                if (callback != null) {
                    callback.onError("VideoDownloader 初始化失败");
                }
                return;
            }
        }
        
        // 检查 VideoDownloadManager 实例
        VideoDownloadManager manager = VideoDownloadManager.getInstance();
        if (manager == null) {
            android.util.Log.e("VideoDownloaderWrapper", "VideoDownloadManager instance is null!");
            if (callback != null) {
                callback.onError("VideoDownloadManager 未初始化");
            }
            return;
        }
        android.util.Log.d("VideoDownloaderWrapper", "VideoDownloadManager instance OK");
        
        String fileName = (title != null && !title.isEmpty()) 
            ? title.replaceAll("[\\\\/:*?\"<>|]", "_")
            : "video_" + System.currentTimeMillis();
        
        android.util.Log.d("VideoDownloaderWrapper", "FileName: " + fileName);
        
        // 创建下载任务
        // 构造函数参数：(url, coverUrl, title, groupName)
        VideoTaskItem taskItem = new VideoTaskItem(url, "", title, "orangeplayer");
        // 单独设置文件名
        taskItem.setFileName(fileName);
        
        android.util.Log.d("VideoDownloaderWrapper", "VideoTaskItem created:");
        android.util.Log.d("VideoDownloaderWrapper", "  URL: " + taskItem.getUrl());
        android.util.Log.d("VideoDownloaderWrapper", "  Title: " + taskItem.getTitle());
        android.util.Log.d("VideoDownloaderWrapper", "  FileName: " + taskItem.getFileName());
        android.util.Log.d("VideoDownloaderWrapper", "  GroupName: " + taskItem.getGroupName());
        
        // 保存回调
        if (callback != null) {
            mCallbackMap.put(url, callback);
            android.util.Log.d("VideoDownloaderWrapper", "Callback registered for URL");
        }
        
        // 开始下载
        try {
            android.util.Log.d("VideoDownloaderWrapper", "Calling VideoDownloadManager.startDownload()...");
            if (headers != null && !headers.isEmpty()) {
                manager.startDownload(taskItem, headers);
            } else {
                manager.startDownload(taskItem);
            }
            android.util.Log.d("VideoDownloaderWrapper", "startDownload() called successfully");
            android.util.Log.d("VideoDownloaderWrapper", "========== downloadM3U8() END ==========");
            // Toast由事件管理器统一处理，此处不再显示
        } catch (Exception e) {
            android.util.Log.e("VideoDownloaderWrapper", "Exception in startDownload()", e);
            if (callback != null) {
                callback.onError("启动下载失败: " + e.getMessage());
            }
        }
    }
    
    private void notifyCallback(VideoTaskItem item, int progress, String message) {
        DownloadCallback callback = mCallbackMap.get(item.getUrl());
        if (callback != null) {
            callback.onProgress(progress, message);
        }
    }
    
    private void notifyCallbackError(VideoTaskItem item, String error) {
        DownloadCallback callback = mCallbackMap.get(item.getUrl());
        if (callback != null) {
            callback.onError(error);
            mCallbackMap.remove(item.getUrl());
        }
    }
    
    private void notifyCallbackSuccess(VideoTaskItem item, String filePath) {
        DownloadCallback callback = mCallbackMap.get(item.getUrl());
        if (callback != null) {
            callback.onSuccess(filePath);
            mCallbackMap.remove(item.getUrl());
        }
    }
    
    /**
     * 下载回调接口
     */
    public interface DownloadCallback {
        void onProgress(int progress, String message);
        void onSuccess(String filePath);
        void onError(String error);
    }
    
    /**
     * 测试 URL 连接（用于诊断）
     */
    private void testUrlConnection(final String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.URL testUrl = new java.net.URL(url);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) testUrl.openConnection();
                    conn.setRequestMethod("HEAD");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    
                    int responseCode = conn.getResponseCode();
                    long contentLength = conn.getContentLengthLong();
                    String contentType = conn.getContentType();
                    
                    android.util.Log.d("VideoDownloaderWrapper", "========== URL Test Result ==========");
                    android.util.Log.d("VideoDownloaderWrapper", "Response code: " + responseCode);
                    android.util.Log.d("VideoDownloaderWrapper", "Content-Length: " + contentLength);
                    android.util.Log.d("VideoDownloaderWrapper", "Content-Type: " + contentType);
                    
                    conn.disconnect();
                } catch (Exception e) {
                    android.util.Log.e("VideoDownloaderWrapper", "URL test failed", e);
                }
            }
        }).start();
    }
    
    /**
     * 下载完成后重命名文件为用户设置的标题
     */
    private String renameFileToTitle(VideoTaskItem item) {
        try {
            String originalPath = item.getFilePath();
            if (originalPath == null || originalPath.isEmpty()) {
                android.util.Log.w("VideoDownloaderWrapper", "[RENAME] filePath is null");
                return null;
            }
            
            File originalFile = new File(originalPath);
            if (!originalFile.exists()) {
                android.util.Log.w("VideoDownloaderWrapper", "[RENAME] File not found: " + originalPath);
                return null;
            }
            
            // 获取标题作为新文件名
            String title = item.getTitle();
            if (title == null || title.isEmpty()) {
                android.util.Log.w("VideoDownloaderWrapper", "[RENAME] Title is null, keep original filename");
                return null;
            }
            
            // 移除非法字符
            String safeName = title.replaceAll("[\\\\/:*?\"<>|]", "_");
            
            // 获取文件扩展名
            String extension = getFileExtension(originalPath);
            String newFileName = safeName + extension;
            
            File parentDir = originalFile.getParentFile();
            File newFile = new File(parentDir, newFileName);
            
            // 如果目标文件已存在，添加序号
            int counter = 1;
            while (newFile.exists()) {
                newFileName = safeName + "_" + counter + extension;
                newFile = new File(parentDir, newFileName);
                counter++;
            }
            
            // 重命名文件
            boolean renamed = originalFile.renameTo(newFile);
            if (renamed) {
                String newPath = newFile.getAbsolutePath();
                android.util.Log.i("VideoDownloaderWrapper", "[RENAME] Success: " + originalPath + " -> " + newPath);
                
                // 更新 item 的文件路径
                item.setFileName(newFileName);
                item.setFilePath(newPath);
                
                return newPath;
            } else {
                android.util.Log.w("VideoDownloaderWrapper", "[RENAME] Failed to rename file");
                return null;
            }
        } catch (Exception e) {
            android.util.Log.e("VideoDownloaderWrapper", "[RENAME] Error: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filePath) {
        if (filePath == null) return ".mp4";
        int lastDot = filePath.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filePath.length() - 1) {
            String ext = filePath.substring(lastDot);
            // 移除查询参数
            if (ext.contains("?")) {
                ext = ext.substring(0, ext.indexOf('?'));
            }
            return ext;
        }
        return ".mp4";
    }
    
    /**
     * 检查视频是否已下载
     * @param url 视频 URL
     * @return 已下载返回本地文件路径，否则返回 null
     */
    public String getLocalVideoPath(String url) {
        if (url == null || url.isEmpty()) return null;
        
        try {
            // 通过底层下载管理器直接获取任务状态，这是最准确的做法
            VideoDownloadManager manager = VideoDownloadManager.getInstance();
            if (manager != null) {
                com.orange.downloader.model.VideoTaskItem taskItem = manager.getDownloadTaskItem(url);
                if (taskItem != null && taskItem.isCompleted() && taskItem.getTaskState() == com.orange.downloader.model.VideoTaskState.SUCCESS) {
                    String filePath = taskItem.getFilePath();
                    if (filePath != null) {
                        java.io.File file = new java.io.File(filePath);
                        if (file.exists() && file.length() > 0) {
                            android.util.Log.d("VideoDownloaderWrapper", "[LOCAL] Task is SUCCESS, found valid file: " + filePath);
                            return filePath;
                        } else {
                            android.util.Log.w("VideoDownloaderWrapper", "[LOCAL] Task marked SUCCESS but file missing/empty: " + filePath);
                        }
                    }
                }
            }
            
            // 如果管理器里没查到，或者还未初始化，作为兜底策略再扫描一遍文件系统
            String folderHash = computeMD5(url);
            File downloadDir = getDownloadDirectory();
            if (downloadDir == null || !downloadDir.exists()) return null;
            
            File videoDir = new File(downloadDir, folderHash);
            if (!videoDir.exists() || !videoDir.isDirectory()) return null;
            
            File[] files = videoDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().toLowerCase();
                    if ((name.endsWith(".mp4") || name.endsWith(".video") || name.endsWith(".ts")) && file.length() > 0) {
                        boolean hasTsFiles = false;
                        for (File f : files) {
                            if (f.getName().toLowerCase().endsWith(".ts") && !f.getName().equals(file.getName())) {
                                hasTsFiles = true;
                                break;
                            }
                        }
                        if (hasTsFiles) {
                            return null;
                        }
                        return file.getAbsolutePath();
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("VideoDownloaderWrapper", "[LOCAL] Error checking local video: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 检查视频是否正在下载中
     */
    public boolean isDownloading(String url) {
        if (url == null) return false;
        return mCallbackMap.containsKey(url);
    }
    
    /**
     * 获取当前下载目录
     */
    public File getDownloadDirectory() {
        if (mCustomDownloadPath != null) {
            File dir = new File(mCustomDownloadPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        }
        // 默认目录
        return new File(mContext.getExternalFilesDir(null), "Download");
    }
    
    /**
     * 设置自定义下载路径
     * @param path 下载目录路径
     */
    public void setDownloadPath(String path) {
        mCustomDownloadPath = path;
        android.util.Log.d("VideoDownloaderWrapper", "Download path set to: " + path);
        
        // 如果 VideoDownloader 已初始化，更新配置
        if (mInitialized) {
            try {
                File cacheDir = new File(path);
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs();
                }
                // 重新初始化 VideoDownloader
                VideoDownloadManager manager = VideoDownloadManager.getInstance();
                if (manager != null && manager.downloadConfig() != null) {
                    manager.downloadConfig().setCacheRoot(path);
                    android.util.Log.d("VideoDownloaderWrapper", "VideoDownloader cache root updated: " + path);
                }
            } catch (Exception e) {
                android.util.Log.e("VideoDownloaderWrapper", "Failed to update download path: " + e.getMessage());
            }
        }
    }
    
    /**
     * 获取当前下载路径设置
     */
    public String getDownloadPath() {
        File dir = getDownloadDirectory();
        return dir != null ? dir.getAbsolutePath() : null;
    }

    public boolean isFfmpegMergeEnabled() {
        return MergeFeatureToggle.isFfmpegMergeEnabled();
    }

    public boolean isJavaMergeFallbackEnabled() {
        return MergeFeatureToggle.isJavaFallbackEnabled();
    }

    public void setFfmpegMergeEnabled(boolean enabled) {
        MergeFeatureToggle.setFfmpegMergeEnabled(mContext, enabled);
        if (mInitialized) {
            VideoDownloadManager manager = VideoDownloadManager.getInstance();
            if (manager != null && manager.downloadConfig() != null) {
                manager.setShouldM3U8Merged(enabled || MergeFeatureToggle.isJavaFallbackEnabled());
            }
        }
    }

    public void setJavaMergeFallbackEnabled(boolean enabled) {
        MergeFeatureToggle.setJavaFallbackEnabled(mContext, enabled);
        if (mInitialized) {
            VideoDownloadManager manager = VideoDownloadManager.getInstance();
            if (manager != null && manager.downloadConfig() != null) {
                manager.setShouldM3U8Merged(enabled || MergeFeatureToggle.isFfmpegMergeEnabled());
            }
        }
    }

    
    /**
     * 计算 MD5 哈希
     */
    private String computeMD5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
