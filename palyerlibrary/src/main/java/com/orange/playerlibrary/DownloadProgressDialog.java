package com.orange.playerlibrary;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 下载进度对话框：圆环 + 百分比 + 状态文字。
 *
 * 只显示真实进度（{@link #showWithRealProgress} + {@link #setProgress}）。早期版本
 * 内置「模拟百分比」（每 100ms 自增、封顶 90%），数字与实际下载无关、纯粹是假进度，
 * 已删除——调用方需自己提供真实进度来源（如 MLKit 模型目录字节数轮询）。
 */
public class DownloadProgressDialog {

    private Dialog mDialog;
    private ProgressBar mProgressBar;
    private TextView mProgressText;
    private TextView mTitleText;
    private TextView mHintText;
    private Handler mHandler;
    private int mCurrentProgress = 0;

    public DownloadProgressDialog(Context context) {
        mHandler = new Handler(Looper.getMainLooper());
        createDialog(context);
    }

    private void createDialog(Context context) {
        mDialog = new Dialog(context);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setCancelable(false);
        mDialog.setCanceledOnTouchOutside(false);

        // 使用自定义布局
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_download_progress, null);
        mTitleText = view.findViewById(R.id.tv_title);
        mProgressBar = view.findViewById(R.id.progress_bar);
        mProgressText = view.findViewById(R.id.tv_progress);
        mHintText = view.findViewById(R.id.tv_hint);

        mDialog.setContentView(view);

        // 设置对话框样式
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.CENTER;
            params.dimAmount = 0.6f;
            window.setAttributes(params);
        }
    }

    /**
     * 显示对话框，使用真实进度
     */
    public void showWithRealProgress(String title) {
        showWithRealProgress(title, null);
    }

    public void showWithRealProgress(String title, String hint) {
        if (mDialog == null || mDialog.isShowing()) {
            return;
        }
        if (mTitleText != null) mTitleText.setText(title);
        if (mHintText != null) mHintText.setText(hint == null ? "首次使用需要下载" : hint);
        mCurrentProgress = 0;
        updateProgress(0);
        mDialog.show();
    }

    /**
     * 设置真实进度
     */
    public void setProgress(int progress) {
        mCurrentProgress = progress;
        updateProgress(progress);
    }

    /**
     * 设置进度和状态文字
     */
    public void setProgress(int progress, String status) {
        setProgress(progress);
        if (mHintText != null && status != null) {
            mHandler.post(() -> mHintText.setText(status));
        }
    }

    private void updateProgress(int progress) {
        mHandler.post(() -> {
            if (mProgressBar != null) {
                mProgressBar.setProgress(progress);
            }
            if (mProgressText != null) {
                mProgressText.setText(progress + "%");
            }
        });
    }

    /**
     * 完成下载，显示 100% 并关闭
     */
    public void complete() {
        mHandler.post(() -> {
            updateProgress(100);
            if (mHintText != null) mHintText.setText("下载完成");
            mHandler.postDelayed(this::dismiss, 500);
        });
    }

    /**
     * 下载失败
     */
    public void fail(String error) {
        dismiss();
    }

    /**
     * 关闭对话框
     */
    public void dismiss() {
        if (mDialog != null && mDialog.isShowing()) {
            try {
                mDialog.dismiss();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    public boolean isShowing() {
        return mDialog != null && mDialog.isShowing();
    }
}
