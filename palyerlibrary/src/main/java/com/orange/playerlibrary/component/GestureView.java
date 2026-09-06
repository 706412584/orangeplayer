package com.orange.playerlibrary.component;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.orange.playerlibrary.OrangePlayerConfig;
import com.orange.playerlibrary.OrangeVideoController;
import com.orange.playerlibrary.R;
import com.orange.playerlibrary.interfaces.ControlWrapper;
import com.orange.playerlibrary.interfaces.IControlComponent;
import com.shuyu.gsyvideoplayer.utils.CommonUtil;

/**
 * 手势提示视图
 * 显示音量、亮度、进度调节的提示
 */
public class GestureView extends FrameLayout implements IControlComponent {
    
    private static final String TAG = "GestureView";
    private static boolean sDebug = false;
    
    private ControlWrapper mControlWrapper;
    private final LinearLayout mCenterContainer;
    private final ImageView mIcon;
    private final ProgressBar mProgressPercent;
    private final TextView mTextPercent;
    
    public GestureView(@NonNull Context context) {
        this(context, null);
    }
    
    public GestureView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }
    
    public GestureView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        
        // 不要隐藏整个 GestureView，让它始终可见，由内部的 mCenterContainer 控制显示
        LayoutInflater.from(getContext()).inflate(R.layout.orange_layout_gesture_view, this, true);
        
        mIcon = findViewById(R.id.iv_icon);
        mProgressPercent = findViewById(R.id.pro_percent);
        mTextPercent = findViewById(R.id.tv_percent);
        mCenterContainer = findViewById(R.id.center_container);
    }
    
    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
        mControlWrapper = controlWrapper;
    }
    
    @Override
    public View getView() {
        return this;
    }
    
    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {
        // 不需要处理
    }
    
    @Override
    public void onPlayerStateChanged(int playerState) {
        // 不需要处理
    }
    
    @Override
    public void onPlayStateChanged(int playState) {
        // GestureView 本身保持可见，由内部 mCenterContainer 控制手势提示的显示
        // 不需要根据播放状态隐藏整个视图
    }
    
    @Override
    public void setProgress(int duration, int position) {
        // 不需要处理
    }
    
    @Override
    public void onLockStateChanged(boolean isLocked) {
        // 不需要处理
    }
    
    // ===== 手势提示方法 =====
    
    /**
     * 开始滑动
     */
    public void onStartSlide() {
        if (mCenterContainer == null) {
            return;
        }
        if (mControlWrapper != null) {
            mControlWrapper.hide();
        }
        mCenterContainer.setVisibility(VISIBLE);
        mCenterContainer.setAlpha(1.0f);
    }
    
    /**
     * 停止滑动
     */
    public void onStopSlide() {
        if (mCenterContainer == null) {
            return;
        }
        mCenterContainer.animate()
                .alpha(0.0f)
                .setDuration(300L)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        mCenterContainer.setVisibility(GONE);
                    }
                })
                .start();
    }
    
    /**
     * 进度改变
     * @param seekPosition 目标位置（毫秒）
     * @param currentPosition 当前位置（毫秒）
     * @param duration 总时长（毫秒）
     */
    public void onPositionChange(int seekPosition, int currentPosition, int duration) {
        if (mProgressPercent == null || mIcon == null || mTextPercent == null) {
            return;
        }
        mProgressPercent.setVisibility(GONE);
        
        if (seekPosition > currentPosition) {
            mIcon.setImageResource(R.drawable.op_ic_fast_forward);
        } else {
            mIcon.setImageResource(R.drawable.op_ic_fast_rewind);
        }
        
        mTextPercent.setText(String.format("%s/%s", 
                stringForTime(seekPosition), 
                stringForTime(duration)));
    }
    
    /**
     * 亮度改变
     * @param percent 亮度百分比 (0-100)
     */
    public void onBrightnessChange(int percent) {
        if (mProgressPercent == null || mIcon == null || mTextPercent == null) {
            return;
        }
        mProgressPercent.setVisibility(VISIBLE);
        mIcon.setImageResource(R.drawable.op_ic_brightness);
        mTextPercent.setText(percent + "%");
        mProgressPercent.setProgress(percent);
    }
    
    /**
     * 音量改变
     * @param percent 音量百分比 (0-100)
     */
    public void onVolumeChange(int percent) {
        if (mProgressPercent == null || mIcon == null || mTextPercent == null) {
            return;
        }
        mProgressPercent.setVisibility(VISIBLE);
        
        if (percent <= 0) {
            mIcon.setImageResource(R.drawable.op_ic_volume_off);
        } else {
            mIcon.setImageResource(R.drawable.op_ic_volume_up);
        }
        
        mTextPercent.setText(percent + "%");
        mProgressPercent.setProgress(percent);
    }
    
    /**
     * 显示倍速提示
     * @param speed 当前速度
     * @param isLongPress true=长按加速中，false=恢复正常
     */
    public void showSpeedHint(float speed, boolean isLongPress) {
        mProgressPercent.setVisibility(GONE);
        mIcon.setImageResource(R.drawable.op_ic_fast_forward);
        
        if (isLongPress) {
            mTextPercent.setText(speed + "x 加速中");
        } else {
            mTextPercent.setText(speed + "x");
        }
    }
    
    // ===== 辅助方法 =====
    
    /**
     * 格式化时间
     */
    private String stringForTime(int timeMs) {
        return CommonUtil.stringForTime(timeMs);
    }
    
    /**
     * 调试日志
     */
    private void debug(Object message) {
        if (sDebug && OrangeVideoController.isdebug()) {
        }
    }
}
