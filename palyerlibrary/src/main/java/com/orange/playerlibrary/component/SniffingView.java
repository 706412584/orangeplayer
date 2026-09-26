package com.orange.playerlibrary.component;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.orange.playerlibrary.OrangeVideoController;
import com.orange.playerlibrary.R;
import com.orange.playerlibrary.VideoSniffing;
import com.orange.playerlibrary.interfaces.ControlWrapper;
import com.orange.playerlibrary.interfaces.IControlComponent;
import com.orange.playerlibrary.sniffing.SniffingResultManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频嗅探组件
 * 显示嗅探进度和嗅探结果，用户可以选择嗅探到的视频进行播放
 * 
 * 功能特性:
 * - 显示嗅探进度（进度条 + 状态文本）
 * - 显示嗅探结果列表（RecyclerView）
 * - 支持选择视频播放
 * - 支持删除单个结果
 * - 支持清空所有结果
 * - 结果持久化存储
 * - 符合现有组件主题风格
 */
public class SniffingView extends FrameLayout implements IControlComponent {
    
    private static final String TAG = "SniffingView";
    
    // 控制器包装类
    private ControlWrapper mControlWrapper;
    
    // UI 组件
    private View mRootView;
    private TextView mTvStatus;
    private ProgressBar mProgressBar;
    private RecyclerView mRecyclerView;
    private TextView mTvEmpty;
    private ImageView mBtnClose;
    private ImageView mBtnClear;
    
    // 数据
    private SniffingResultAdapter mAdapter;
    private List<VideoSniffing.VideoInfo> mVideoList = new ArrayList<>();
    /** 录制模式开关控件 */
    private TextView mBtnRecordToggle;
    private TextView mTvRecordHint;
    private android.os.Handler mRecordTickHandler;
    // 移除持久化管理器，改为使用私有变量（app 重启失效）
    
    // 回调
    private OnVideoSelectedListener mOnVideoSelectedListener;

    public SniffingView(@NonNull Context context) {
        super(context);
        initView(context);
    }

    public SniffingView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initView(context);
    }

    public SniffingView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView(context);
    }

    /**
     * 初始化布局和控件
     */
    private void initView(Context context) {
        setVisibility(GONE);
        setClickable(true);
        
        mRootView = LayoutInflater.from(context).inflate(R.layout.orange_layout_sniffing_view, this, true);
        
        // 初始化控件引用
        mTvStatus = findViewById(R.id.tv_sniffing_status);
        mProgressBar = findViewById(R.id.progress_sniffing);
        mRecyclerView = findViewById(R.id.rv_sniffing_results);
        mTvEmpty = findViewById(R.id.tv_empty);
        mBtnClose = findViewById(R.id.btn_close);
        mBtnClear = findViewById(R.id.btn_clear);
        mBtnRecordToggle = findViewById(R.id.btn_record_toggle);
        mTvRecordHint = findViewById(R.id.tv_record_hint);
        
        // 移除持久化管理器初始化
        
        // 设置 RecyclerView
        mRecyclerView.setLayoutManager(new LinearLayoutManager(context));
        mAdapter = new SniffingResultAdapter(mVideoList);
        mRecyclerView.setAdapter(mAdapter);
        
        // 设置适配器回调
        mAdapter.setOnItemClickListener(new SniffingResultAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(VideoSniffing.VideoInfo videoInfo) {
                // 选择视频播放
                if (mOnVideoSelectedListener != null) {
                    mOnVideoSelectedListener.onVideoSelected(videoInfo);
                }
                hide();
            }

            @Override
            public void onDownloadClick(VideoSniffing.VideoInfo videoInfo) {
                startDownload(videoInfo);
            }

            @Override
            public void onDeleteClick(VideoSniffing.VideoInfo videoInfo, int position) {
                // 删除单个结果（仅从内存中删除）
                mVideoList.remove(position);
                mAdapter.notifyItemRemoved(position);
                updateEmptyView();
            }
        });
        
        // 关闭按钮
        mBtnClose.setOnClickListener(v -> hide());
        
        // 清空按钮
        mBtnClear.setOnClickListener(v -> clearAllResults());

        // 录制模式开关
        if (mBtnRecordToggle != null) {
            mBtnRecordToggle.setOnClickListener(v -> toggleRecording());
        }
        updateRecordUi();
        
        // 移除加载持久化结果的调用
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
        // 空实现
    }

    @Override
    public void onPlayStateChanged(int playState) {
        // 空实现
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
        // 空实现
    }

    @Override
    public void setProgress(int duration, int position) {
        // 空实现
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
        // 空实现
    }

    // ===== 公共方法 =====

    /**
     * 显示嗅探视图
     */
    public void show() {
        setVisibility(VISIBLE);
        bringToFront();
    }

    /**
     * 隐藏嗅探视图
     */
    public void hide() {
        setVisibility(GONE);
    }

    /**
     * 是否正在显示
     */
    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    /**
     * 开始嗅探
     */
    public void startSniffing() {
        mTvStatus.setText("正在嗅探视频...");
        mProgressBar.setVisibility(VISIBLE);
        mBtnClear.setVisibility(GONE);
    }

    /**
     * 嗅探完成
     */
    public void finishSniffing(int count) {
        mProgressBar.setVisibility(GONE);
        mBtnClear.setVisibility(VISIBLE);
        
        if (count > 0) {
            mTvStatus.setText("嗅探完成，共发现 " + count + " 个视频");
        } else {
            mTvStatus.setText("嗅探完成，未发现视频");
        }
    }

    /**
     * 添加嗅探结果（仅存储在内存中）
     */
    public void addSniffingResult(VideoSniffing.VideoInfo videoInfo) {
        // 去重检查
        for (VideoSniffing.VideoInfo info : mVideoList) {
            if (info.url.equals(videoInfo.url)) {
                return;
            }
        }
        
        mVideoList.add(videoInfo);
        mAdapter.notifyItemInserted(mVideoList.size() - 1);
        
        updateEmptyView();
    }

    /**
     * 设置嗅探结果列表（仅存储在内存中）
     */
    public void setSniffingResults(List<VideoSniffing.VideoInfo> results) {
        mVideoList.clear();
        if (results != null) {
            mVideoList.addAll(results);
        }
        mAdapter.notifyDataSetChanged();
        
        updateEmptyView();
    }

    /**
     * 清空所有结果（仅清空内存）
     */
    public void clearAllResults() {
        mVideoList.clear();
        mAdapter.notifyDataSetChanged();
        updateEmptyView();
        mTvStatus.setText("已清空所有嗅探结果");
    }

    /**
     * 获取嗅探结果数量
     */
    public int getResultCount() {
        return mVideoList.size();
    }

    /**
     * 更新空视图显示
     */
    private void updateEmptyView() {
        if (mVideoList.isEmpty()) {
            mTvEmpty.setVisibility(VISIBLE);
            mRecyclerView.setVisibility(GONE);
        } else {
            mTvEmpty.setVisibility(GONE);
            mRecyclerView.setVisibility(VISIBLE);
        }
    }

    /**
     * 把嗅探到的资源交给下载模块。
     *
     * <p>嗅探与下载此前完全没打通——结果只能播，不能存。这里接上
     * {@link SimpleDownloadManager}：m3u8 会自动解析分片并合并，mp4 直接下载。
     *
     * @param videoInfo 嗅探结果
     */
    private void startDownload(VideoSniffing.VideoInfo videoInfo) {
        if (videoInfo == null || videoInfo.url == null || videoInfo.url.isEmpty()) {
            return;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        String title = videoInfo.title;
        if (title == null || title.isEmpty() || "null".equals(title)) {
            title = "嗅探视频";
        }

        // 嗅探存的是**响应头**，不能直接当请求头用：响应里的 Content-Length /
        // Content-Type 等一旦被复制进请求会干扰下载。只挑与防盗链相关的字段。
        java.util.Map<String, String> reqHeaders = new java.util.HashMap<>();
        if (videoInfo.headers != null) {
            for (java.util.Map.Entry<String, String> e : videoInfo.headers.entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null) continue;
                String lower = k.toLowerCase();
                if (lower.equals("referer") || lower.equals("origin")
                        || lower.equals("cookie") || lower.equals("user-agent")) {
                    reqHeaders.put(k, e.getValue());
                }
            }
        }

        com.orange.playerlibrary.download.SimpleDownloadManager
                .getInstance(ctx)
                .startDownload(videoInfo.url, title, reqHeaders.isEmpty() ? null : reqHeaders);

        android.widget.Toast.makeText(ctx, "已加入下载：" + title,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * 切换录制模式。
     *
     * <p>开启后**需要重新加载页面**：MSE 的 hook 必须赶在播放器调用
     * {@code addSourceBuffer} 之前完成，页面已初始化过就包不住了。
     * 这里直接给出提示，不假装能对已加载的页面生效。
     */
    private void toggleRecording() {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        if (VideoSniffing.isRecording()) {
            List<java.io.File> files = VideoSniffing.stopRecording();
            stopRecordTicker();
            updateRecordUi();
            if (files == null || files.isEmpty()) {
                toast("录制结束，但没有捕获到数据（页面未播放或不是 MSE 播放）");
            } else {
                StringBuilder sb = new StringBuilder("录制结束，产物 " + files.size() + " 个：\n");
                for (java.io.File f : files) {
                    sb.append(f.getName()).append("（")
                            .append(formatSize(f.length())).append("）\n");
                }
                if (files.size() > 1) {
                    sb.append("注意：音视频是分开的，需要合并才能正常播放");
                }
                toast(sb.toString());
            }
        } else {
            java.io.File dir = new java.io.File(
                    ctx.getExternalFilesDir(null), "Download/recorded");
            VideoSniffing.startRecording(ctx, dir);
            updateRecordUi();
            startRecordTicker();
            toast("已开启录制，请重新加载页面并播放，边播边录");
        }
    }

    private void startRecordTicker() {
        stopRecordTicker();
        mRecordTickHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mRecordTickHandler.post(new Runnable() {
            @Override
            public void run() {
                updateRecordUi();
                if (VideoSniffing.isRecording() && mRecordTickHandler != null) {
                    mRecordTickHandler.postDelayed(this, 1000);
                }
            }
        });
    }

    private void stopRecordTicker() {
        if (mRecordTickHandler != null) {
            mRecordTickHandler.removeCallbacksAndMessages(null);
            mRecordTickHandler = null;
        }
    }

    /** 刷新录制开关的文案与提示 */
    private void updateRecordUi() {
        if (mBtnRecordToggle == null) {
            return;
        }
        boolean recording = VideoSniffing.isRecording();
        mBtnRecordToggle.setText(recording ? "录制中：停" : "录制模式：关");
        if (mTvRecordHint != null) {
            if (recording) {
                mTvRecordHint.setText("已录 " + formatSize(VideoSniffing.getRecordedBytes())
                        + " · 边播边录，可点停止");
            } else {
                mTvRecordHint.setText("blob: 场景用录制；开启后需重载页面");
            }
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void toast(String msg) {
        Context ctx = getContext();
        if (ctx != null) {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopRecordTicker();
    }

    /**
     * 设置视频选择监听器
     */
    public void setOnVideoSelectedListener(OnVideoSelectedListener listener) {
        mOnVideoSelectedListener = listener;
    }

    /**
     * 视频选择监听器接口
     */
    public interface OnVideoSelectedListener {
        void onVideoSelected(VideoSniffing.VideoInfo videoInfo);
    }

    // ===== 适配器 =====

    private static class SniffingResultAdapter extends RecyclerView.Adapter<SniffingResultAdapter.ViewHolder> {

        private final List<VideoSniffing.VideoInfo> mList;
        private OnItemClickListener mListener;

        interface OnItemClickListener {
            void onItemClick(VideoSniffing.VideoInfo videoInfo);
            void onDownloadClick(VideoSniffing.VideoInfo videoInfo);
            void onDeleteClick(VideoSniffing.VideoInfo videoInfo, int position);
        }

        SniffingResultAdapter(List<VideoSniffing.VideoInfo> list) {
            mList = list;
        }

        void setOnItemClickListener(OnItemClickListener listener) {
            mListener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.orange_item_sniffing_result, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            VideoSniffing.VideoInfo videoInfo = mList.get(position);
            holder.bind(videoInfo, mListener, position);
        }

        @Override
        public int getItemCount() {
            return mList.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvUrl, tvType;
            ImageView btnDownload, btnDelete;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(R.id.tv_video_title);
                tvUrl = itemView.findViewById(R.id.tv_video_url);
                tvType = itemView.findViewById(R.id.tv_video_type);
                btnDownload = itemView.findViewById(R.id.btn_download);
                btnDelete = itemView.findViewById(R.id.btn_delete);
            }

            void bind(VideoSniffing.VideoInfo videoInfo, OnItemClickListener listener, int position) {
                // 标题
                String title = videoInfo.title;
                if (title == null || title.isEmpty() || title.equals("null")) {
                    title = "视频 " + (position + 1);
                }
                tvTitle.setText(title);

                // URL（截断显示）
                String url = videoInfo.url;
                if (url.length() > 60) {
                    url = url.substring(0, 57) + "...";
                }
                tvUrl.setText(url);

                // 类型
                String type = videoInfo.contentType;
                if (type != null && !type.isEmpty()) {
                    tvType.setText(type);
                    tvType.setVisibility(View.VISIBLE);
                } else {
                    tvType.setVisibility(View.GONE);
                }

                // 点击播放
                itemView.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onItemClick(videoInfo);
                    }
                });

                // 下载按钮
                btnDownload.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onDownloadClick(videoInfo);
                    }
                });

                // 删除按钮
                btnDelete.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onDeleteClick(videoInfo, position);
                    }
                });
            }
        }
    }
}
