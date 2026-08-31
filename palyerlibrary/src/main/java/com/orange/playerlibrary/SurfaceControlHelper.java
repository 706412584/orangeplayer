package com.orange.playerlibrary;

import android.os.Build;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceView;

/**
 * ExoPlayer SurfaceControl 管理辅助类。
 * 负责 SurfaceControl 的创建、释放和 reparent 操作。
 * Android Q+ 专用。
 */
public class SurfaceControlHelper {

    private static final String SURFACE_CONTROL_NAME = "OrangeExoSurface";

    private SurfaceControl mSurfaceControl;
    private Surface mVideoSurface;
    private boolean mActive = false;

    /**
     * 是否已激活 SurfaceControl 模式
     */
    public boolean isActive() {
        return mActive;
    }

    /**
     * 获取 ExoPlayer 使用的 Surface（用于传给播放器）
     */
    public Surface getVideoSurface() {
        return mVideoSurface;
    }

    /**
     * 初始化 SurfaceControl（Android Q+ 调用）
     */
    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.Q)
    public void init() {
        if (mSurfaceControl != null) {
            return; // 已初始化
        }

        try {
            mSurfaceControl = new SurfaceControl.Builder()
                    .setName(SURFACE_CONTROL_NAME)
                    .setBufferSize(0, 0)
                    .build();
            mVideoSurface = new Surface(mSurfaceControl);
            mActive = true;
        } catch (Exception e) {
            mActive = false;
        }
    }

    /**
     * 释放 SurfaceControl 资源
     */
    public void release() {
        if (mVideoSurface != null) {
            mVideoSurface.release();
            mVideoSurface = null;
        }
        if (mSurfaceControl != null) {
            mSurfaceControl.release();
            mSurfaceControl = null;
        }
        mActive = false;
    }

    /**
     * 将 SurfaceControl reparent 到指定的 SurfaceView。
     * 如果 surfaceView 为 null，则隐藏视频。
     *
     * @param surfaceView 目标 SurfaceView，可为 null
     * @return true 表示操作成功，false 表示需要回退到普通方式
     */
    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.Q)
    public boolean reparent(SurfaceView surfaceView) {
        if (mSurfaceControl == null) {
            return false;
        }

        try {
            if (surfaceView == null) {
                new SurfaceControl.Transaction()
                        .reparent(mSurfaceControl, null)
                        .setBufferSize(mSurfaceControl, 0, 0)
                        .setVisibility(mSurfaceControl, false)
                        .apply();
            } else {
                SurfaceControl newParentSurfaceControl = surfaceView.getSurfaceControl();
                if (newParentSurfaceControl != null && newParentSurfaceControl.isValid()) {
                    new SurfaceControl.Transaction()
                            .reparent(mSurfaceControl, newParentSurfaceControl)
                            .setBufferSize(mSurfaceControl, surfaceView.getWidth(), surfaceView.getHeight())
                            .setVisibility(mSurfaceControl, true)
                            .apply();
                } else {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
