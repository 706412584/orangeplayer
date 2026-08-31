package com.orange.playerlibrary;

import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import com.shuyu.gsyvideoplayer.GSYVideoManager;

/**
 * 网速计算和加载状态辅助类。
 * 负责网速采样计算与运行状态维护，定时调度和 UI 更新由使用方（OrangevideoView）驱动。
 */
public class LoadingStateHelper {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mLastRxBytes = 0;
    private long mLastSpeedTime = 0;
    private boolean mIsRunning = false;
    private String mCustomText = null;

    public boolean isRunning() {
        return mIsRunning;
    }

    /**
     * 开始网速采样，并调度给定的更新任务（每秒一次）
     */
    public void start(final Runnable updateTask) {
        if (mIsRunning) return;
        mIsRunning = true;
        mLastRxBytes = TrafficStats.getUidRxBytes(Process.myUid());
        if (mLastRxBytes == TrafficStats.UNSUPPORTED) {
            mLastRxBytes = 0;
        }
        mLastSpeedTime = System.currentTimeMillis();
        if (updateTask != null) {
            mHandler.post(updateTask);
        }
    }

    /**
     * 停止网速采样，并移除给定的更新任务
     */
    public void stop(Runnable updateTask) {
        if (!mIsRunning) return;
        mIsRunning = false;
        if (updateTask != null) {
            mHandler.removeCallbacks(updateTask);
        }
    }

    /**
     * 每秒调度一次更新任务（由更新任务自身在每次执行时调用）
     */
    public void scheduleNext(Runnable updateTask) {
        if (mIsRunning && updateTask != null) {
            mHandler.postDelayed(updateTask, 1000);
        }
    }

    /**
     * 设置自定义加载文本（用于磁力链接解析等场景）
     * @param text 自定义文本，null 表示恢复显示网速
     */
    public void setCustomText(String text) {
        mCustomText = text;
    }

    public String getCustomText() {
        return mCustomText;
    }

    /**
     * 计算当前网速（字节/秒）
     */
    public long calculateSpeed() {
        // 先尝试 GSY 的方法
        long gsySpeed = GSYVideoManager.instance().getNetSpeed();
        if (gsySpeed > 0) {
            return gsySpeed;
        }

        // GSY 返回 0，使用系统 API 计算
        long currentRxBytes = TrafficStats.getUidRxBytes(Process.myUid());
        long currentTime = System.currentTimeMillis();

        if (currentRxBytes == TrafficStats.UNSUPPORTED) {
            return 0;
        }

        if (mLastRxBytes == 0 || mLastSpeedTime == 0) {
            mLastRxBytes = currentRxBytes;
            mLastSpeedTime = currentTime;
            return 0;
        }

        long timeDiff = currentTime - mLastSpeedTime;
        if (timeDiff <= 0) {
            mLastSpeedTime = currentTime;
            return 0;
        }

        long bytesDiff = currentRxBytes - mLastRxBytes;
        long speed = (bytesDiff * 1000) / timeDiff;

        mLastRxBytes = currentRxBytes;
        mLastSpeedTime = currentTime;

        return Math.max(0, speed);
    }
}
