package com.orange.playerlibrary.ocr;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.orange.playerlibrary.R;

import java.util.List;

/**
 * 语言包管理对话框
 */
public class LanguagePackDialog {
    
    private final Context mContext;
    private final LanguagePackManager mManager;
    private final LanguagePackAdapter mAdapter;
    private Dialog mDialog;
    private TextView mTvInstalledInfo;
    private OnDismissListener mOnDismissListener;
    
    /**
     * 对话框关闭监听器
     */
    public interface OnDismissListener {
        void onDismiss();
    }
    
    public LanguagePackDialog(Context context) {
        mContext = context;
        mManager = new LanguagePackManager(context);
        mAdapter = new LanguagePackAdapter();
    }
    
    /**
     * 设置对话框关闭监听器
     */
    public void setOnDismissListener(OnDismissListener listener) {
        mOnDismissListener = listener;
    }
    
    public void show() {
        View view = LayoutInflater.from(mContext).inflate(R.layout.dialog_language_pack, null);
        
        mTvInstalledInfo = view.findViewById(R.id.tv_installed_info);
        RecyclerView rvLanguages = view.findViewById(R.id.rv_languages);
        View btnClose = view.findViewById(R.id.btn_close);
        
        // 设置 RecyclerView
        rvLanguages.setLayoutManager(new LinearLayoutManager(mContext));
        rvLanguages.setAdapter(mAdapter);
        
        // 加载语言列表
        List<LanguagePackManager.LanguagePack> languages = mManager.getAvailableLanguages();
        mAdapter.setLanguages(languages);
        updateInstalledInfo();
        
        // 设置操作监听
        mAdapter.setOnActionListener(new LanguagePackAdapter.OnActionListener() {
            @Override
            public void onDownload(LanguagePackManager.LanguagePack pack) {
                downloadLanguage(pack);
            }
            
            @Override
            public void onDelete(LanguagePackManager.LanguagePack pack) {
                deleteLanguage(pack);
            }
        });
        
        // 创建对话框
        mDialog = new AlertDialog.Builder(mContext)
                .setView(view)
                .create();
        
        btnClose.setOnClickListener(v -> mDialog.dismiss());
        
        // 设置对话框关闭监听
        mDialog.setOnDismissListener(dialog -> {
            if (mOnDismissListener != null) {
                mOnDismissListener.onDismiss();
            }
        });
        
        // 设置对话框属性
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.6f);
        }
        
        mDialog.show();
    }
    
    private void downloadLanguage(LanguagePackManager.LanguagePack pack) {
        // 点击即置下载态：不能等首个进度回调——首源连接/重定向可能耗时数秒，
        // 期间没有任何回调，按钮会一直显示「下载」，用户以为点击无反应。
        mAdapter.setDownloadProgress(pack.code, 0);
        mManager.downloadLanguage(pack.code, new LanguagePackManager.DownloadCallback() {
            @Override
            public void onProgress(int progress, long downloaded, long total) {
                mAdapter.setDownloadProgress(pack.code, progress);
            }
            
            @Override
            public void onSuccess() {
                mAdapter.setDownloadComplete(pack.code, true);
                updateInstalledInfo();
                Toast.makeText(mContext, pack.name + " 下载完成", Toast.LENGTH_SHORT).show();
            }
            
            @Override
            public void onError(String error) {
                mAdapter.setDownloadComplete(pack.code, false);
                Toast.makeText(mContext, "下载失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private void deleteLanguage(LanguagePackManager.LanguagePack pack) {
        new AlertDialog.Builder(mContext)
                .setTitle("删除语言包")
                .setMessage("确定要删除 " + pack.name + " 语言包吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    if (mManager.deleteLanguage(pack.code)) {
                        mAdapter.setDeleted(pack.code);
                        updateInstalledInfo();
                        Toast.makeText(mContext, pack.name + " 已删除", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    private void updateInstalledInfo() {
        List<String> installed = mManager.getInstalledLanguages();
        long size = mManager.getInstalledSize();
        
        String sizeText;
        if (size < 1024 * 1024) {
            sizeText = String.format("%.1f KB", size / 1024.0);
        } else {
            sizeText = String.format("%.1f MB", size / (1024.0 * 1024));
        }
        
        mTvInstalledInfo.setText("已安装: " + installed.size() + " 个语言包，共 " + sizeText);
    }
    
    public void dismiss() {
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
        }
        mManager.shutdown();
    }
}
