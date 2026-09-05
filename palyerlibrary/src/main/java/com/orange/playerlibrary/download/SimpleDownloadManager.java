package com.orange.playerlibrary.download;

import android.content.Context;
import android.widget.Toast;

/**
 * 简单的下载管理器 - 使用 VideoDownloader 统一处理所有格式
 * 
 * 支持格式：
 * - M3U8 视频（自动解析和合并 TS 文件为 MP4）
 * - MP4 视频
 * - FLV 视频
 * - 其他常见视频格式
 * 
 * 优点：
 * 1. 统一的下载接口
 * 2. 自动识别视频格式
 * 3. 支持断点续传
 * 4. 支持下载队列管理
 * 5. M3U8 自动合并为 MP4
 * 6. 应用退出后继续下载
 * 
 * 使用方式：
 * SimpleDownloadManager.getInstance(context).setDownloadPath(path);
 */
public class SimpleDownloadManager {
    
    private static volatile SimpleDownloadManager sInstance;
    
    private Context mContext;
    private VideoDownloaderWrapper mVideoDownloader;
    
    /**
     * 获取单例实例
     */
    public static SimpleDownloadManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (SimpleDownloadManager.class) {
                if (sInstance == null) {
                    sInstance = new SimpleDownloadManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }
    
    /**
     * 私有构造函数，防止外部 new
     */
    private SimpleDownloadManager(Context context) {
        mContext = context;
        mVideoDownloader = VideoDownloaderWrapper.getInstance(mContext);
    }
    
    /**
     * 开始下载（支持所有格式：M3U8、MP4、FLV 等）
     * @param url 下载地址
     * @param title 视频标题
     * @param description 描述（暂未使用）
     * @return 下载任务 ID（-1 表示使用 VideoDownloader）
     */
    public long startDownload(String url, String title, String description) {
        android.util.Log.d("SimpleDownloadManager", "startDownload() called");
        android.util.Log.d("SimpleDownloadManager", "URL: " + url);
        android.util.Log.d("SimpleDownloadManager", "Title: " + title);

        if (url == null || url.isEmpty()) {
            android.util.Log.e("SimpleDownloadManager", "URL is null or empty");
            Toast.makeText(mContext, "下载地址为空", Toast.LENGTH_SHORT).show();
            return -1;
        }

        // 去广告开启时改用无广告版 m3u8（广告段直接删除，无占位黑屏），
        // 否则下载产物与播放不一致（下载到带广告的原始源）。异步获取避免主线程网络请求。
        com.orange.playerlibrary.M3U8AdManager.getInstance(mContext).getDownloadUrlAsync(url,
                downloadUrl -> {
                    if (!url.equals(downloadUrl)) {
                        android.util.Log.d("SimpleDownloadManager",
                                "Ad-removal enabled, downloading ad-free m3u8: " + downloadUrl);
                    }
                    // 使用 VideoDownloader 统一处理所有格式
                    // VideoDownloader 会自动识别视频类型（M3U8、MP4、FLV 等）
                    mVideoDownloader.downloadM3U8(downloadUrl, title,
                            new VideoDownloaderWrapper.DownloadCallback() {
                                @Override
                                public void onProgress(int progress, String message) {
                                    android.util.Log.d("SimpleDownloadManager", "Download progress: " + message);
                                }

                                @Override
                                public void onSuccess(String filePath) {
                                    android.util.Log.d("SimpleDownloadManager", "Download success: " + filePath);
                                }

                                @Override
                                public void onError(String error) {
                                    android.util.Log.e("SimpleDownloadManager", "Download error: " + error);
                                    // 回调可能在下载线程，Toast 需主线程
                                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                            Toast.makeText(mContext, "下载失败: " + error, Toast.LENGTH_LONG).show());
                                }
                            });
                });

        return -1;  // VideoDownloader 使用自己的任务 ID 系统
    }
    
    /**
     * 格式化文件大小
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        // VideoDownloader 的清理由 VideoDownloaderWrapper 管理
    }
    
    /**
     * 检查视频是否已下载到本地
     * @param url 视频 URL
     * @return 已下载返回本地文件路径，否则返回 null
     */
    public String getLocalVideoPath(String url) {
        return mVideoDownloader.getLocalVideoPath(url);
    }
    
    /**
     * 检查视频是否正在下载中
     * @param url 视频 URL
     * @return 是否正在下载
     */
    public boolean isDownloading(String url) {
        return mVideoDownloader.isDownloading(url);
    }
    
    /**
     * 设置下载路径
     * @param path 下载目录路径
     */
    public void setDownloadPath(String path) {
        mVideoDownloader.setDownloadPath(path);
    }
    
    /**
     * 获取当前下载路径
     * @return 下载目录路径
     */
    public String getDownloadPath() {
        return mVideoDownloader.getDownloadPath();
    }
    
    /**
     * 开始下载前检查本地是否已下载
     * 如果已下载，直接返回本地路径；否则开始下载
     * @param url 下载地址
     * @param title 视频标题
     * @return 已下载返回本地路径，否则返回 null 并开始下载
     */
    public String startDownloadWithLocalCheck(String url, String title) {
        // 先检查本地是否已下载
        String localPath = getLocalVideoPath(url);
        if (localPath != null) {
            android.util.Log.d("SimpleDownloadManager", "Video already downloaded: " + localPath);
            Toast.makeText(mContext, "视频已下载\n位置: " + localPath, Toast.LENGTH_SHORT).show();
            return localPath;
        }
        
        // 检查是否正在下载
        if (isDownloading(url)) {
            android.util.Log.d("SimpleDownloadManager", "Video is downloading");
            Toast.makeText(mContext, "视频正在下载中", Toast.LENGTH_SHORT).show();
            return null;
        }
        
        // 开始下载
        startDownload(url, title, null);
        return null;
    }
}
