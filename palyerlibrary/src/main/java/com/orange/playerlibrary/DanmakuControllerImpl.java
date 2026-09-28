package com.orange.playerlibrary;

import android.content.Context;
import android.view.ViewGroup;

import com.orange.playerlibrary.component.DanmaView;
import com.orange.playerlibrary.interfaces.IDanmakuController;

import java.util.ArrayList;
import java.util.List;

/**
 * 弹幕控制器实现类
 * 封装 DanmaView，提供统一的弹幕控制接口
 * 
 * 使用方式：
 * 1. 创建实例：new DanmakuControllerImpl(context)
 * 2. 附加到播放器：attachToContainer(videoView)
 * 3. 设置到控制器：controller.setDanmakuController(impl)
 * 4. 设置弹幕数据：setDanmakuData(list)
 */
public class DanmakuControllerImpl implements IDanmakuController {
    
    private static final String TAG = "DanmakuControllerImpl";
    
    private final Context mContext;
    private DanmaView mDanmaView;
    private boolean mIsAttached = false;
    
    public DanmakuControllerImpl(Context context) {
        mContext = context;
    }
    
    /**
     * 将弹幕视图附加到播放器容器
     * 
     * @param videoView 播放器视图
     */
    public void attachToContainer(OrangevideoView videoView) {
        if (videoView == null || mIsAttached) {
            return;
        }
        
        // 创建弹幕视图
        mDanmaView = new DanmaView(mContext);
        mDanmaView.setDebug(true); // 开启调试日志
        
        // 设置布局参数（全屏覆盖，放在视频层之上）
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        );
        
        // 直接添加到播放器视图（不通过控制组件，避免被控制器隐藏影响）
        videoView.addView(mDanmaView, params);
        
        // 获取控制器并设置关联
        if (videoView.getVideoController() instanceof OrangeVideoController) {
            OrangeVideoController controller = (OrangeVideoController) videoView.getVideoController();
            mDanmaView.setOrangeVideoController(controller);
            
            // 将 DanmaView 添加到控制组件列表（用于接收 setProgress 回调）
            // 但不添加到控制器的视图容器中
            controller.addControlComponentWithoutView(mDanmaView);
        }
        
        mIsAttached = true;
    }
    
    /**
     * 从容器分离弹幕视图
     */
    public void detachFromContainer() {
        if (mDanmaView != null && mDanmaView.getParent() != null) {
            ((ViewGroup) mDanmaView.getParent()).removeView(mDanmaView);
        }
        mIsAttached = false;
    }
    
    /**
     * 获取弹幕视图
     */
    public DanmaView getDanmaView() {
        return mDanmaView;
    }
    
    @Override
    public void setDanmakuData(List<DanmakuItem> danmakuList) {
        if (mDanmaView == null || danmakuList == null) {
            return;
        }
        
        // 转换为 DanmaView 的数据格式
        List<DanmaView.DanmakuItem> items = new ArrayList<>();
        for (DanmakuItem item : danmakuList) {
            items.add(new DanmaView.DanmakuItem(
                    item.getText(),
                    item.getColor(),
                    item.getTimestamp(),
                    item.isSelf()
            ));
        }
        
        mDanmaView.setAllDanmakus(items);
    }
    
    @Override
    public void sendDanmaku(String text, int color) {
        sendDanmaku(text, color, LOCATION_SCROLL);
    }

    @Override
    public void sendDanmaku(String text, int color, int location) {
        if (mDanmaView != null) {
            mDanmaView.addUserDanmaku(text, color, true, location);
        }
    }
    
    @Override
    public void setDanmakuEnabled(boolean enabled) {
        if (mDanmaView != null) {
            mDanmaView.setDanmakuEnabled(enabled);
        }
    }
    
    @Override
    public boolean isDanmakuEnabled() {
        return mDanmaView != null && mDanmaView.isDanmakuEnabled();
    }
    
    @Override
    public void setDanmakuTextSize(float spSize) {
        if (mDanmaView != null) {
            mDanmaView.setDanmakuTextSize(spSize);
        }
    }
    
    @Override
    public void setDanmakuSpeed(float speedFactor) {
        if (mDanmaView != null) {
            mDanmaView.setDanmakuSpeed(speedFactor);
        }
    }
    
    @Override
    public void setDanmakuAlpha(float alpha) {
        if (mDanmaView != null) {
            mDanmaView.setAlpha(alpha);
        }
    }
    
    @Override
    public void clearDanmakus() {
        if (mDanmaView != null) {
            mDanmaView.clearDanmakus();
        }
    }
    
    @Override
    public void pauseDanmaku() {
        if (mDanmaView != null && mDanmaView.isPrepared() && !mDanmaView.isPaused()) {
            mDanmaView.pause();
        }
    }
    
    @Override
    public void resumeDanmaku() {
        if (mDanmaView != null && mDanmaView.isPrepared() && mDanmaView.isPaused()) {
            mDanmaView.resume();
        }
    }
    
    @Override
    public void seekDanmakuTo(long position) {
        if (mDanmaView != null && mDanmaView.isPrepared()) {
            mDanmaView.seekTo(position);
        }
    }
    
    @Override
    public void releaseDanmaku() {
        if (mDanmaView != null) {
            if (mDanmaView.isPrepared()) {
                mDanmaView.release();
            }
            mDanmaView = null;
        }
        mIsAttached = false;
    }
}
