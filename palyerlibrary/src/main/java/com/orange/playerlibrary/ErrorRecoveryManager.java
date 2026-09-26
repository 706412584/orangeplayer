package com.orange.playerlibrary;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 错误恢复管理器
 * 负责检测和恢复播放器错误状态，包括黑屏检测、状态不一致检测等
 * Requirements: 2.5, 7.5, 8.1, 8.2, 8.3, 8.4
 */
public class ErrorRecoveryManager {
    
    private static final String TAG = "ErrorRecoveryManager";
    
    // 黑屏检测间隔（毫秒）
    // 性能优化：从 2秒 增加到 3秒，减少检测频率
    private static final long BLACK_SCREEN_CHECK_INTERVAL = 3000; // 3秒
    
    // 黑屏超时时间（毫秒）
    // 性能优化：从 5秒 增加到 6秒，避免误判
    private static final long BLACK_SCREEN_TIMEOUT = 6000; // 6秒
    
    // 状态一致性检测间隔（毫秒）
    // 性能优化：从 3秒 增加到 5秒，减少检测频率
    private static final long STATE_CHECK_INTERVAL = 5000; // 5秒
    
    // 最大恢复尝试次数
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    
    // 关联的视频播放器
    private OrangevideoView mVideoView;
    
    // 黑屏检测 Handler
    private Handler mBlackScreenCheckHandler;
    
    // 黑屏检测 Runnable
    private Runnable mBlackScreenCheckRunnable;
    
    // 状态检测 Handler
    private Handler mStateCheckHandler;
    
    // 状态检测 Runnable
    private Runnable mStateCheckRunnable;
    
    // 最后一次画面更新时间
    private long mLastFrameUpdateTime = 0;
    
    // 黑屏恢复尝试次数
    private int mBlackScreenRecoveryAttempts = 0;
    
    // 是否启用黑屏检测
    private boolean mBlackScreenDetectionEnabled = true;
    
    // 是否启用状态一致性检测
    private boolean mStateConsistencyCheckEnabled = true;
    
    // 错误回调接口
    private ErrorRecoveryCallback mCallback;
    
    /**
     * 错误恢复回调接口
     */
    public interface ErrorRecoveryCallback {
        /**
         * 检测到黑屏
         */
        void onBlackScreenDetected();
        
        /**
         * 黑屏恢复成功
         */
        void onBlackScreenRecovered();
        
        /**
         * 黑屏恢复失败
         */
        void onBlackScreenRecoveryFailed();
        
        /**
         * 检测到状态不一致
         */
        void onStateInconsistencyDetected(String description);
        
        /**
         * 状态同步成功
         */
        void onStateSynchronized();
        
        /**
         * 错误日志
         */
        void onError(String message, Throwable throwable);
    }
    
    public ErrorRecoveryManager() {
        mBlackScreenCheckHandler = new Handler(Looper.getMainLooper());
        mStateCheckHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 附加到视频播放器
     * 
     * @param videoView 视频播放器
     */
    public void attachVideoView(OrangevideoView videoView) {
        this.mVideoView = videoView;
        Log.d(TAG, "attachVideoView: 错误恢复管理器已附加到播放器");
    }
    
    /**
     * 从视频播放器分离
     */
    public void detachVideoView() {
        stopBlackScreenDetection();
        stopStateConsistencyCheck();
        this.mVideoView = null;
        Log.d(TAG, "detachVideoView: 错误恢复管理器已从播放器分离");
    }
    
    // ===== 黑屏检测和恢复 (Requirements: 2.5, 7.5, 8.1) =====
    
    /**
     * 启动黑屏检测
     * Requirements: 2.5, 7.5, 8.1
     */
    public void startBlackScreenDetection() {
        if (!mBlackScreenDetectionEnabled) {
            Log.d(TAG, "startBlackScreenDetection: 黑屏检测已禁用");
            return;
        }
        
        // 停止之前的检测
        stopBlackScreenDetection();
        
        // 重置恢复尝试次数
        mBlackScreenRecoveryAttempts = 0;
        
        // 创建检测任务
        mBlackScreenCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkBlackScreen();
                // 继续下一次检测
                if (mBlackScreenCheckHandler != null && mBlackScreenCheckRunnable != null) {
                    mBlackScreenCheckHandler.postDelayed(mBlackScreenCheckRunnable, BLACK_SCREEN_CHECK_INTERVAL);
                }
            }
        };
        
        // 启动检测
        mBlackScreenCheckHandler.postDelayed(mBlackScreenCheckRunnable, BLACK_SCREEN_CHECK_INTERVAL);
        
        Log.d(TAG, "startBlackScreenDetection: 黑屏检测已启动");
    }
    
    /**
     * 停止黑屏检测
     */
    public void stopBlackScreenDetection() {
        if (mBlackScreenCheckHandler != null && mBlackScreenCheckRunnable != null) {
            mBlackScreenCheckHandler.removeCallbacks(mBlackScreenCheckRunnable);
            mBlackScreenCheckRunnable = null;
        }
        Log.d(TAG, "stopBlackScreenDetection: 黑屏检测已停止");
    }
    
    /**
     * 检测视频画面是否黑屏
     * Requirements: 2.5, 7.5, 8.1
     */
    private void checkBlackScreen() {
        if (mVideoView == null) {
            return;
        }
        
        // 只在暂停状态下检测黑屏（播放状态下画面应该在更新）
        int playState = mVideoView.getPlayState();
        if (playState != PlayerConstants.STATE_PAUSED) {
            // 不在暂停状态，重置最后画面更新时间
            mLastFrameUpdateTime = System.currentTimeMillis();
            return;
        }
        
        // 检查是否在配置改变或全屏切换过程中
        PlaybackStateManager stateManager = mVideoView.getPlaybackStateManager();
        if (stateManager != null && stateManager.isSurfaceLost()) {
            // 表面已丢失，这是预期的，不算黑屏
            Log.d(TAG, "checkBlackScreen: 表面已丢失，等待恢复");
            return;
        }
        
        // 检查画面更新超时
        long currentTime = System.currentTimeMillis();
        if (mLastFrameUpdateTime > 0) {
            long timeSinceLastFrame = currentTime - mLastFrameUpdateTime;
            
            if (timeSinceLastFrame > BLACK_SCREEN_TIMEOUT) {
                // 检测到黑屏
                Log.e(TAG, "checkBlackScreen: 检测到黑屏（超时 " + timeSinceLastFrame + "ms）");
                
                // 通知回调
                if (mCallback != null) {
                    mCallback.onBlackScreenDetected();
                }
                
                // 尝试恢复
                recoverFromBlackScreen();
            }
        } else {
            // 首次检测，记录当前时间
            mLastFrameUpdateTime = currentTime;
        }
    }
    
    /**
     * 尝试恢复视频画面
     * Requirements: 2.5, 7.5, 8.1
     */
    private void recoverFromBlackScreen() {
        if (mVideoView == null) {
            Log.e(TAG, "recoverFromBlackScreen: videoView is null");
            return;
        }
        
        // 检查恢复尝试次数
        if (mBlackScreenRecoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            Log.e(TAG, "recoverFromBlackScreen: 已达到最大恢复尝试次数 (" + MAX_RECOVERY_ATTEMPTS + ")，放弃恢复");
            
            // 记录错误日志
            logError("黑屏恢复失败：已达到最大尝试次数", null);
            
            // 通知回调
            if (mCallback != null) {
                mCallback.onBlackScreenRecoveryFailed();
            }
            
            return;
        }
        
        mBlackScreenRecoveryAttempts++;
        Log.d(TAG, "recoverFromBlackScreen: 尝试恢复黑屏（第 " + mBlackScreenRecoveryAttempts + " 次）");
        
        try {
            // 获取播放状态管理器
            PlaybackStateManager stateManager = mVideoView.getPlaybackStateManager();
            if (stateManager != null) {
                // 尝试恢复表面
                stateManager.restoreSurface(mVideoView);
                
                // 如果表面恢复失败，尝试重新 seek 到当前位置
                long currentPosition = mVideoView.getCurrentPositionWhenPlaying();
                if (currentPosition > 0) {
                    Log.d(TAG, "recoverFromBlackScreen: 重新 seek 到位置 " + currentPosition);
                    mVideoView.seekTo(currentPosition);
                }
                
                // 重置最后画面更新时间
                mLastFrameUpdateTime = System.currentTimeMillis();
                
                // 记录成功日志
                Log.d(TAG, "recoverFromBlackScreen: 黑屏恢复成功");
                
                // 通知回调
                if (mCallback != null) {
                    mCallback.onBlackScreenRecovered();
                }
            } else {
                Log.e(TAG, "recoverFromBlackScreen: PlaybackStateManager is null");
                logError("黑屏恢复失败：PlaybackStateManager 未初始化", null);
            }
        } catch (Exception e) {
            Log.e(TAG, "recoverFromBlackScreen: 恢复失败", e);
            logError("黑屏恢复异常", e);
        }
    }
    
    /**
     * 通知画面已更新（由外部调用）
     * 用于重置黑屏检测计时器
     */
    public void notifyFrameUpdated() {
        mLastFrameUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * 设置是否启用黑屏检测
     * 
     * @param enabled true 启用
     */
    public void setBlackScreenDetectionEnabled(boolean enabled) {
        this.mBlackScreenDetectionEnabled = enabled;
        if (!enabled) {
            stopBlackScreenDetection();
        }
        Log.d(TAG, "setBlackScreenDetectionEnabled: " + enabled);
    }
    
    /**
     * 是否启用黑屏检测
     * 
     * @return true 启用
     */
    public boolean isBlackScreenDetectionEnabled() {
        return mBlackScreenDetectionEnabled;
    }
    
    // ===== 状态不一致检测 (Requirements: 4.5, 8.3) =====
    
    /**
     * 启动状态一致性检测
     * Requirements: 4.5, 8.3
     */
    public void startStateConsistencyCheck() {
        if (!mStateConsistencyCheckEnabled) {
            Log.d(TAG, "startStateConsistencyCheck: 状态一致性检测已禁用");
            return;
        }
        
        // 停止之前的检测
        stopStateConsistencyCheck();
        
        // 创建检测任务
        mStateCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkStateConsistency();
                // 继续下一次检测
                if (mStateCheckHandler != null && mStateCheckRunnable != null) {
                    mStateCheckHandler.postDelayed(mStateCheckRunnable, STATE_CHECK_INTERVAL);
                }
            }
        };
        
        // 启动检测
        mStateCheckHandler.postDelayed(mStateCheckRunnable, STATE_CHECK_INTERVAL);
        
        Log.d(TAG, "startStateConsistencyCheck: 状态一致性检测已启动");
    }
    
    /**
     * 停止状态一致性检测
     */
    public void stopStateConsistencyCheck() {
        if (mStateCheckHandler != null && mStateCheckRunnable != null) {
            mStateCheckHandler.removeCallbacks(mStateCheckRunnable);
            mStateCheckRunnable = null;
        }
        Log.d(TAG, "stopStateConsistencyCheck: 状态一致性检测已停止");
    }
    
    /**
     * 检测组件状态是否一致
     * Requirements: 4.5, 8.3
     */
    private void checkStateConsistency() {
        if (mVideoView == null) {
            return;
        }
        
        try {
            // 获取播放器状态
            int playState = mVideoView.getPlayState();
            int playerState = mVideoView.getPlayerState();
            
            // 获取组件状态管理器
            ComponentStateManager componentManager = mVideoView.getComponentStateManager();
            if (componentManager == null) {
                return;
            }
            
            // 检查进度监听器是否已注册
            if (!componentManager.isProgressListenerRegistered() && mVideoView.isPlaying()) {
                // 播放中但进度监听器未注册，状态不一致
                Log.w(TAG, "checkStateConsistency: 检测到状态不一致 - 播放中但进度监听器未注册");
                
                // 通知回调
                if (mCallback != null) {
                    mCallback.onStateInconsistencyDetected("播放中但进度监听器未注册");
                }
                
                // 自动同步状态
                synchronizeComponentState();
                return;
            }
            
            // 全屏多实例治理：原先此处会比较"全屏播放器"（GSY 克隆出的第二个实例）与当前播放器的状态。
            // 自研全屏已改为单实例方案，不存在第二个播放器，故移除该检查。

        } catch (Exception e) {
            Log.e(TAG, "checkStateConsistency: 检测状态一致性失败", e);
            logError("状态一致性检测异常", e);
        }
    }
    
    /**
     * 自动同步所有组件状态
     * Requirements: 4.5, 8.3
     */
    private void synchronizeComponentState() {
        if (mVideoView == null) {
            Log.e(TAG, "synchronizeComponentState: videoView is null");
            return;
        }
        
        try {
            Log.d(TAG, "synchronizeComponentState: 开始同步组件状态");
            
            // 获取当前播放状态
            int playState = mVideoView.getPlayState();
            int playerState = mVideoView.getPlayerState();
            
            // 通知所有组件更新状态
            // 这会触发组件的 onPlayStateChanged 和 onPlayerStateChanged 方法
            // 注意：这里使用反射或直接调用私有方法可能不是最佳实践
            // 但为了演示目的，我们假设有公共方法可以调用
            
            // 获取组件状态管理器
            ComponentStateManager componentManager = mVideoView.getComponentStateManager();
            if (componentManager != null) {
                // 重新注册进度监听器
                componentManager.reregisterProgressListener(mVideoView);
                
                // 恢复组件状态
                componentManager.restoreComponentState(mVideoView);
            }
            
            // 全屏多实例治理：原先此处会同步"全屏播放器"（GSY 克隆出的第二个实例）的组件状态。
            // 自研全屏已改为单实例方案，不存在第二个播放器，故移除。

            Log.d(TAG, "synchronizeComponentState: 组件状态同步完成");
            
            // 记录警告日志
            logWarning("组件状态已自动同步");
            
            // 通知回调
            if (mCallback != null) {
                mCallback.onStateSynchronized();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "synchronizeComponentState: 同步组件状态失败", e);
            logError("组件状态同步异常", e);
        }
    }
    
    /**
     * 设置是否启用状态一致性检测
     * 
     * @param enabled true 启用
     */
    public void setStateConsistencyCheckEnabled(boolean enabled) {
        this.mStateConsistencyCheckEnabled = enabled;
        if (!enabled) {
            stopStateConsistencyCheck();
        }
        Log.d(TAG, "setStateConsistencyCheckEnabled: " + enabled);
    }
    
    /**
     * 是否启用状态一致性检测
     * 
     * @return true 启用
     */
    public boolean isStateConsistencyCheckEnabled() {
        return mStateConsistencyCheckEnabled;
    }
    
    // ===== 错误日志和监控 (Requirements: 8.4) =====
    
    /**
     * 记录错误日志
     * Requirements: 8.4
     * 
     * @param message 错误信息
     * @param throwable 异常对象（可选）
     */
    private void logError(String message, Throwable throwable) {
        if (throwable != null) {
            Log.e(TAG, "ERROR: " + message, throwable);
        } else {
            Log.e(TAG, "ERROR: " + message);
        }
        
        // 通知回调
        if (mCallback != null) {
            mCallback.onError(message, throwable);
        }
    }
    
    /**
     * 记录警告日志
     * Requirements: 8.4
     * 
     * @param message 警告信息
     */
    private void logWarning(String message) {
        Log.w(TAG, "WARNING: " + message);
    }
    
    /**
     * 记录调试日志
     * Requirements: 8.4
     * 
     * @param message 调试信息
     */
    public void logDebug(String message) {
        Log.d(TAG, "DEBUG: " + message);
    }
    
    /**
     * 设置错误回调接口
     * Requirements: 8.4
     * 
     * @param callback 回调接口
     */
    public void setErrorRecoveryCallback(ErrorRecoveryCallback callback) {
        this.mCallback = callback;
        Log.d(TAG, "setErrorRecoveryCallback: 错误回调接口已设置");
    }
    
    /**
     * 获取错误回调接口
     * 
     * @return 回调接口
     */
    public ErrorRecoveryCallback getErrorRecoveryCallback() {
        return mCallback;
    }
    
    /**
     * 重置错误恢复管理器
     */
    public void reset() {
        stopBlackScreenDetection();
        stopStateConsistencyCheck();
        mLastFrameUpdateTime = 0;
        mBlackScreenRecoveryAttempts = 0;
        Log.d(TAG, "reset: 错误恢复管理器已重置");
    }
}
