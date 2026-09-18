package com.orange.playerlibrary;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.shuyu.gsyvideoplayer.listener.GSYVideoProgressListener;

/**
 * 组件状态管理器
 * 负责保存和恢复组件状态，管理进度监听器的注册
 * Requirements: 3.1, 3.2, 3.3, 4.1, 4.2
 */
public class ComponentStateManager {
    
    private static final String TAG = "ComponentStateManager";
    
    // 进度更新检测间隔（毫秒）
    // 性能优化：从 3秒 增加到 5秒，减少检测频率
    private static final long PROGRESS_CHECK_INTERVAL = 5000; // 5秒
    
    // 进度更新超时时间（毫秒）
    // 性能优化：从 5秒 增加到 8秒，避免误判
    private static final long PROGRESS_TIMEOUT = 8000; // 8秒
    
    // 最后的视频时长（毫秒）
    private int mLastDuration = 0;
    
    // 最后的播放位置（毫秒）
    private int mLastPosition = 0;
    
    // 进度监听器是否已注册
    private boolean mProgressListenerRegistered = false;
    
    // 保存的进度监听器引用
    private GSYVideoProgressListener mProgressListener;
    
    // 最后一次进度更新的时间戳
    private long mLastProgressUpdateTime = 0;
    
    // 进度检测 Handler
    private Handler mProgressCheckHandler;
    
    // 进度检测 Runnable
    private Runnable mProgressCheckRunnable;
    
    // 关联的视频播放器
    private OrangevideoView mVideoView;
    
    public ComponentStateManager() {
        mProgressCheckHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 保存组件状态
     * Requirements: 4.1
     * 
     * 性能优化：
     * - 添加节流机制，避免过于频繁的状态保存
     * - 只在状态真正改变时才保存
     * 
     * @param duration 视频总时长（毫秒）
     * @param position 当前播放位置（毫秒）
     */
    public void saveComponentState(int duration, int position) {
        // 性能优化：只在状态真正改变时才保存（避免重复保存相同状态）
        if (mLastDuration == duration && mLastPosition == position) {
            return; // 状态未改变，跳过保存
        }
        
        mLastDuration = duration;
        mLastPosition = position;
        
        // 更新最后一次进度更新时间
        mLastProgressUpdateTime = System.currentTimeMillis();
        
        // 性能优化：减少日志输出频率（只在位置变化超过1秒时输出）
        if (Math.abs(position - mLastPosition) > 1000) {
            Log.d(TAG, "saveComponentState: duration=" + duration + ", position=" + position);
        }
    }
    
    /**
     * 恢复组件状态
     * Requirements: 4.2
     * 
     * @param videoView 视频播放器
     */
    public void restoreComponentState(OrangevideoView videoView) {
        if (videoView == null) {
            Log.w(TAG, "restoreComponentState: videoView is null");
            return;
        }
        
        Log.d(TAG, "restoreComponentState: duration=" + mLastDuration + ", position=" + mLastPosition);
        
        // 恢复组件进度显示
        if (mLastDuration > 0) {
            videoView.updateComponentsProgress(mLastDuration, mLastPosition);
        }
    }
    
    /**
     * 重新注册进度监听器
     * Requirements: 3.2, 3.3, 6.3, 6.4
     * 
     * @param videoView 视频播放器
     */
    public void reregisterProgressListener(OrangevideoView videoView) {
        if (videoView == null) {
            Log.w(TAG, "reregisterProgressListener: videoView is null");
            return;
        }
        
        // 保存视频播放器引用
        mVideoView = videoView;
        
        // 如果已经注册过，先注销
        if (mProgressListenerRegistered && mProgressListener != null) {
            Log.d(TAG, "reregisterProgressListener: unregistering old listener");
            // GSYVideoPlayer 不提供注销方法，直接覆盖即可
        }
        
        // 创建新的进度监听器
        mProgressListener = new GSYVideoProgressListener() {
            @Override
            public void onProgress(long progress, long secProgress, long currentPosition, long duration) {
                // 保存当前进度
                saveComponentState((int) duration, (int) currentPosition);
                
                // 更新当前播放器的组件进度
                updatePlayerProgress(videoView, (int) duration, (int) currentPosition);
                
                // 同步全屏播放器的进度
                syncFullScreenProgress(videoView, (int) duration, (int) currentPosition);
                
                // 实时写入记忆播放和历史进度
                saveRealTimeProgress(videoView, (int) duration, (int) currentPosition);
            }
        };
        
        // 注册进度监听器
        videoView.setGSYVideoProgressListener(mProgressListener);
        mProgressListenerRegistered = true;
        
        // 启动进度更新检测
        startProgressCheck();
        
        Log.d(TAG, "reregisterProgressListener: listener registered");
    }
    
    /**
     * 更新播放器进度
     * Requirements: 3.4, 3.5, 6.4
     * 
     * 性能优化：
     * - 确保在主线程更新 UI
     * - 添加空指针检查
     * - 捕获异常避免崩溃
     * 
     * @param videoView 视频播放器
     * @param duration 视频总时长
     * @param position 当前播放位置
     */
    private void updatePlayerProgress(OrangevideoView videoView, int duration, int position) {
        if (videoView == null) {
            return;
        }
        
        // 性能优化：确保在主线程更新 UI
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // 不在主线程，切换到主线程
            new Handler(Looper.getMainLooper()).post(() -> {
                updatePlayerProgressInternal(videoView, duration, position);
            });
        } else {
            // 已在主线程，直接更新
            updatePlayerProgressInternal(videoView, duration, position);
        }
    }
    
    /**
     * 内部方法：更新播放器进度
     */
    private void updatePlayerProgressInternal(OrangevideoView videoView, int duration, int position) {
        try {
            videoView.updateComponentsProgress(duration, position);
        } catch (Exception e) {
            Log.e(TAG, "updatePlayerProgress: 更新进度失败", e);
        }
    }
    
    /**
     * 同步全屏播放器的进度
     * Requirements: 3.4, 3.5, 6.4
     * 
     * 优化说明：
     * - 确保全屏播放器的进度正确更新
     * - 处理全屏切换时的进度同步
     * - 避免进度更新冲突（通过检查播放器实例避免重复更新）
     * 
     * @param videoView 当前视频播放器
     * @param duration 视频总时长
     * @param position 当前播放位置
     */
    private void syncFullScreenProgress(OrangevideoView videoView, int duration, int position) {
        if (videoView == null) {
            return;
        }
        
        try {
            // 获取全屏播放器
            OrangevideoView fullPlayer = videoView.getOrangeFullWindowPlayer();
            
            Log.d(TAG, "syncFullScreenProgress: videoView=" + videoView.hashCode() 
                + ", fullPlayer=" + (fullPlayer != null ? fullPlayer.hashCode() : "null")
                + ", isFullScreen=" + videoView.isIfCurrentIsFullscreen());
            
            // 检查全屏播放器是否存在且不是当前播放器（避免重复更新）
            if (fullPlayer != null && fullPlayer != videoView) {
                // 检查全屏播放器是否已初始化组件
                if (fullPlayer.getVodControlView() != null || fullPlayer.getLiveControlView() != null) {
                    // 更新全屏播放器的进度
                    fullPlayer.updateComponentsProgress(duration, position);
                    
                    Log.d(TAG, "syncFullScreenProgress: 全屏播放器进度已同步 - duration=" + duration + ", position=" + position);
                } else {
                    Log.w(TAG, "syncFullScreenProgress: 全屏播放器组件未初始化，跳过同步");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "syncFullScreenProgress: 同步全屏播放器进度失败", e);
        }
    }

    private long mLastRealTimeSaveTime = 0;

    /**
     * 实时写入记忆播放和历史进度
     */
    private void saveRealTimeProgress(OrangevideoView videoView, int duration, int position) {
        if (videoView == null || !videoView.isKeepVideoPlaying()) {
            return;
        }

        // 用统一的存储键（优先原始播放源）。不能用 getVideoUrl()——去广告后它会变成
        // 回环代理地址，端口每次启动随机，会导致保存的进度跨启动读不回来。
        String url = videoView.getProgressStorageKey();
        if (url == null || url.isEmpty()) {
            return;
        }

        // 检查全局记忆播放开关
        com.orange.playerlibrary.PlayerSettingsManager settingsManager = 
            com.orange.playerlibrary.PlayerSettingsManager.getInstance(videoView.getContext());
        if (settingsManager == null || !settingsManager.isMemoryPlayEnabled()) {
            return;
        }

        // 过滤条件：进度大于1分钟且距离结尾大于1分钟才触发记忆写入
        if (position >= 60000 && duration > 0 && (duration - position) >= 60000) {
            // 降低数据库写入频率，每 1 秒写入一次
            long currentTime = System.currentTimeMillis();
            if (currentTime - mLastRealTimeSaveTime > 1000) {
                mLastRealTimeSaveTime = currentTime;
                
                // 1. 保存到 SharedPreferences (用于下一次恢复)
                PlaybackProgressManager.getInstance(videoView.getContext())
                        .saveProgress(url, position, duration);
                
                // 2. 保存到历史数据库
                String title = "";
                if (videoView.getVideoController() != null) {
                    title = videoView.getVideoController().getVideoTitle();
                }
                com.orange.playerlibrary.history.PlayHistoryManager.getInstance(videoView.getContext())
                        .saveProgress(url, title, duration, position);
            }
        }
    }
    
    /**
     * 启动进度更新检测
     * Requirements: 6.1, 6.2, 6.3, 8.2
     */
    private void startProgressCheck() {
        // 停止之前的检测
        stopProgressCheck();
        
        // 创建检测任务
        mProgressCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkProgressUpdate();
                // 继续下一次检测
                if (mProgressCheckHandler != null && mProgressCheckRunnable != null) {
                    mProgressCheckHandler.postDelayed(mProgressCheckRunnable, PROGRESS_CHECK_INTERVAL);
                }
            }
        };
        
        // 启动检测
        mProgressCheckHandler.postDelayed(mProgressCheckRunnable, PROGRESS_CHECK_INTERVAL);
        
        Log.d(TAG, "startProgressCheck: 进度更新检测已启动");
    }
    
    /**
     * 停止进度更新检测
     */
    private void stopProgressCheck() {
        if (mProgressCheckHandler != null && mProgressCheckRunnable != null) {
            mProgressCheckHandler.removeCallbacks(mProgressCheckRunnable);
            mProgressCheckRunnable = null;
        }
    }
    
    /**
     * 检测进度更新是否停止
     * Requirements: 6.1, 6.2, 6.3, 8.2
     */
    private void checkProgressUpdate() {
        if (mVideoView == null) {
            return;
        }
        
        // 检查播放器是否正在播放
        if (!mVideoView.isPlaying()) {
            // 不在播放状态，不需要检测
            return;
        }
        
        // 检查进度更新是否超时
        long currentTime = System.currentTimeMillis();
        long timeSinceLastUpdate = currentTime - mLastProgressUpdateTime;
        
        if (mLastProgressUpdateTime > 0 && timeSinceLastUpdate > PROGRESS_TIMEOUT) {
            // 进度更新已停止，尝试重新启动
            Log.e(TAG, "checkProgressUpdate: 检测到进度更新停止（超时 " + timeSinceLastUpdate + "ms），尝试重新注册监听器");
            
            // 重新注册进度监听器
            reregisterProgressListener(mVideoView);
        }
    }
    
    /**
     * 获取最后的视频时长
     * 
     * @return 视频时长（毫秒）
     */
    public int getLastDuration() {
        return mLastDuration;
    }
    
    /**
     * 获取最后的播放位置
     * 
     * @return 播放位置（毫秒）
     */
    public int getLastPosition() {
        return mLastPosition;
    }
    
    /**
     * 进度监听器是否已注册
     * 
     * @return true 已注册
     */
    public boolean isProgressListenerRegistered() {
        return mProgressListenerRegistered;
    }
    
    /**
     * 重置状态
     */
    public void reset() {
        mLastDuration = 0;
        mLastPosition = 0;
        mProgressListenerRegistered = false;
        mProgressListener = null;
        mLastProgressUpdateTime = 0;
        mVideoView = null;
        
        // 停止进度检测
        stopProgressCheck();
        
        Log.d(TAG, "reset: state reset");
    }
}
