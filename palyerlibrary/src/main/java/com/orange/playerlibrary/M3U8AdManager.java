package com.orange.playerlibrary;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;

/**
 * M3U8去广告管理器
 * 
 * 集成到播放器流程：
 * 1. 播放前检测是否是HTTP m3u8
 * 2. 如果是，调用去广告处理
 * 3. 处理成功则播放本地清理后的m3u8
 * 4. 处理失败则播放原始URL
 */
public class M3U8AdManager {
    
    private static final String TAG = "M3U8AdManager";
    
    private static M3U8AdManager sInstance;
    private final M3U8AdRemover mRemover;
    private final Context mContext;
    
    // 是否启用去广告功能
    private boolean mEnabled = false;
    
    public static synchronized M3U8AdManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new M3U8AdManager(context.getApplicationContext());
        }
        return sInstance;
    }
    
    private M3U8AdManager(Context context) {
        mContext = context;
        mRemover = new M3U8AdRemover(context);
    }
    
    /**
     * 处理视频URL，返回可用于播放的URL
     * 
     * @param videoUrl 原始视频URL
     * @param callback 结果回调
     */
    public void processVideoUrl(String videoUrl, Callback callback) {
        if (!mEnabled) {
            // 未启用，直接返回原始URL
            callback.onResult(videoUrl, false, 0, false, "Ad removal disabled");
            return;
        }
        
        if (!M3U8AdRemover.isHttpM3U8(videoUrl)) {
            // 不是HTTP m3u8，直接返回原始URL
            callback.onResult(videoUrl, false, 0, false, "Not HTTP m3u8");
            return;
        }
        
        Log.d(TAG, "Processing m3u8 URL for ad removal: " + videoUrl);
        
        mRemover.processM3U8(videoUrl, new M3U8AdRemover.Callback() {
            @Override
            public void onResult(String playUrl, boolean isLocalFile, int adSegmentsRemoved, boolean hasPtsJump) {
                Log.d(TAG, "Ad removal complete: isLocalFile=" + isLocalFile + 
                      ", adSegmentsRemoved=" + adSegmentsRemoved + ", hasPtsJump=" + hasPtsJump);
                String message;
                if (hasPtsJump) {
                    message = "PTS jump detected, recommend using ExoPlayer";
                } else if (isLocalFile) {
                    message = "Ad removed successfully";
                } else {
                    message = "No ads found";
                }
                callback.onResult(playUrl, isLocalFile, adSegmentsRemoved, hasPtsJump, message);
            }
            
            @Override
            public void onError(String originalUrl, Exception e) {
                Log.w(TAG, "Ad removal failed, using original URL: " + e.getMessage());
                callback.onResult(originalUrl, false, 0, false, "Ad removal failed: " + e.getMessage());
            }
        });
    }
    
    public void checkDiscontinuity(String videoUrl, java.util.Map<String, String> headers,
            M3U8AdRemover.DiscontinuityCallback callback) {
        mRemover.checkDiscontinuity(videoUrl, headers, callback);
    }

    /**
     * 同步处理视频URL（阻塞调用，用于简单场景）
     * 
     * @param videoUrl 原始视频URL
     * @param timeoutMs 超时时间（毫秒）
     * @return 可用于播放的URL
     */
    public String processVideoUrlSync(String videoUrl, long timeoutMs) {
        if (!mEnabled || !M3U8AdRemover.isHttpM3U8(videoUrl)) {
            return videoUrl;
        }
        
        final String[] result = {videoUrl};
        final boolean[] finished = {false};
        
        mRemover.processM3U8(videoUrl, new M3U8AdRemover.Callback() {
            @Override
            public void onResult(String playUrl, boolean isLocalFile, int adSegmentsRemoved, boolean hasPtsJump) {
                synchronized (result) {
                    result[0] = playUrl;
                    finished[0] = true;
                    result.notifyAll();
                }
            }
            
            @Override
            public void onError(String originalUrl, Exception e) {
                synchronized (result) {
                    result[0] = originalUrl;
                    finished[0] = true;
                    result.notifyAll();
                }
            }
        });
        
        // 等待结果
        synchronized (result) {
            long startTime = System.currentTimeMillis();
            while (!finished[0]) {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed >= timeoutMs) {
                    Log.w(TAG, "processVideoUrlSync timeout");
                    break;
                }
                try {
                    result.wait(timeoutMs - elapsed);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        
        return result[0];
    }
    
    /**
     * 是否启用去广告功能
     */
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        Log.d(TAG, "Ad removal " + (enabled ? "enabled" : "disabled"));
    }
    
    public boolean isEnabled() {
        return mEnabled;
    }
    
    /**
     * 清除所有缓存
     */
    public void clearCache() {
        mRemover.clearCache();
    }
    
    /**
     * 清除特定URL的缓存（播放错误时调用）
     * @param originalUrl 原始m3u8 URL
     */
    public void clearCacheForUrl(String originalUrl) {
        mRemover.clearCacheForUrl(originalUrl);
    }
    
    /**
     * 释放资源
     */
    public void release() {
        mRemover.release();
        sInstance = null;
    }
    
    /**
     * 获取本地m3u8文件的URI（用于播放器）
     */
    public Uri getLocalFileUri(String localPath) {
        File file = new File(localPath);
        if (file.exists()) {
            return Uri.fromFile(file);
        }
        return null;
    }
    
    /**
     * 结果回调
     */
    public interface Callback {
        /**
         * @param playUrl 可用于播放的URL（可能是本地文件路径）
         * @param isLocalFile 是否是本地文件
         * @param adSegmentsRemoved 移除的广告片段数
         * @param hasPtsJump 是否检测到 PTS 跳变（建议切换到 ExoPlayer）
         * @param message 处理结果消息
         */
        void onResult(String playUrl, boolean isLocalFile, int adSegmentsRemoved, boolean hasPtsJump, String message);
    }
    
    // ===== TS白名单操作 =====
    
    /**
     * 添加TS片段到白名单
     * 白名单中的片段不会被当作广告过滤
     * 对当前播放器实例生效，重启失效
     * 
     * @param tsUrl TS片段的完整URL或URL关键字（包含该关键字的URL都不会被过滤）
     */
    public void addTsToWhitelist(String tsUrl) {
        mRemover.addToWhitelist(tsUrl);
    }
    
    /**
     * 批量添加TS片段到白名单
     * 
     * @param tsUrls TS片段URL列表
     */
    public void addTsToWhitelist(java.util.List<String> tsUrls) {
        mRemover.addToWhitelist(tsUrls);
    }
    
    /**
     * 从白名单移除TS片段
     * 
     * @param tsUrl 要移除的URL
     */
    public void removeTsFromWhitelist(String tsUrl) {
        mRemover.removeFromWhitelist(tsUrl);
    }
    
    /**
     * 清空TS白名单
     */
    public void clearTsWhitelist() {
        mRemover.clearWhitelist();
    }
    
    /**
     * 检查URL是否在白名单中
     * 
     * @param tsUrl 要检查的URL
     * @return 是否在白名单中
     */
    public boolean isTsInWhitelist(String tsUrl) {
        return mRemover.isInWhitelist(tsUrl);
    }
    
    /**
     * 获取TS白名单大小
     */
    public int getTsWhitelistSize() {
        return mRemover.getWhitelistSize();
    }
}
