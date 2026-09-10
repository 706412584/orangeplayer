package com.orange.playerlibrary;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 悬浮环形进度（绿色圆环 + 百分比），挂在播放器上。
 *
 * 与模态 ProgressDialog 的区别：不弹窗、不调暗画面、不拦截触摸，
 * 适合自动触发的后台任务（ASR 识别 / HLS 下载）展示进度。
 * 圆环 drawable 复用 progress_circle_bg / progress_circle_fg。
 */
public class FloatingRingProgress {

    private final Context mContext;
    private final View mRoot;
    private final ProgressBar mFg;
    private final TextView mText;
    private final TextView mHint;

    public FloatingRingProgress(Context context) {
        mContext = context;
        mRoot = LayoutInflater.from(context).inflate(R.layout.layout_asr_ring_progress, null);
        mFg = mRoot.findViewById(R.id.asr_ring_fg);
        mText = mRoot.findViewById(R.id.asr_ring_text);
        mHint = mRoot.findViewById(R.id.asr_ring_hint);
        // 不拦截播放器触摸（手势/控制栏仍可用）
        mRoot.setClickable(false);
        mRoot.setFocusable(false);
    }

    /**
     * 挂到播放器容器右侧中部（避开顶部标题栏与底部字幕/控制栏）。
     * 重复调用会先摘除再挂载，避免重复添加。
     */
    public void attach(ViewGroup anchor) {
        if (anchor == null) {
            return;
        }
        try {
            if (mRoot.getParent() instanceof ViewGroup) {
                ((ViewGroup) mRoot.getParent()).removeView(mRoot);
            }
        } catch (Exception ignored) {
            // 旧容器可能已随 Activity 销毁
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        int margin = (int) (12 * mContext.getResources().getDisplayMetrics().density);
        lp.rightMargin = margin;
        anchor.addView(mRoot, lp);
    }

    public void show() {
        mRoot.setVisibility(View.VISIBLE);
    }

    public void setProgress(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        if (mFg != null) {
            mFg.setProgress(p);
        }
        if (mText != null) {
            mText.setText(p + "%");
        }
    }

    public void setHint(String hint) {
        if (mHint != null && hint != null) {
            mHint.setText(hint);
        }
    }

    public void dismiss() {
        if (mRoot.getParent() instanceof ViewGroup) {
            ((ViewGroup) mRoot.getParent()).removeView(mRoot);
        }
    }
}
