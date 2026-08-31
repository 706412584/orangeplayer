package com.orange.playerlibrary.sniffing;

import android.content.Context;
import android.util.Log;

import com.orange.playerlibrary.PlayerSettingsManager;
import com.orange.playerlibrary.VideoSniffing;

import java.util.List;
import java.util.Map;

/**
 * 视频嗅探代理类，将 OrangevideoView 中的嗅探逻辑下沉到此处。
 * OrangevideoView 只负责调用代理方法和处理 ViewCallback 中的 UI 更新。
 */
public class SniffingDelegate {

    private static final String TAG = "SniffingDelegate";

    public interface ViewCallback {
        void onSniffingStarted(boolean autoPlay);
        void onSniffingReceived(VideoSniffing.VideoInfo videoInfo);
        void onSniffingFinished(List<VideoSniffing.VideoInfo> videoList, int videoSize, boolean autoPlay);
        void onSniffingReceivedRaw(String contentType, java.util.HashMap<String, String> headers,
                                   String title, String url);
    }

    private final Context mContext;
    private ViewCallback mViewCallback;
    private boolean mIsSniffing = false;

    public SniffingDelegate(Context context) {
        mContext = context.getApplicationContext();
    }

    public void setViewCallback(ViewCallback callback) {
        mViewCallback = callback;
    }

    public boolean isSniffing() {
        return mIsSniffing;
    }

    public void setSniffing(boolean sniffing) {
        mIsSniffing = sniffing;
    }

    /**
     * 启动视频嗅探
     */
    public void startSniffing(String url, Map<String, String> headers) {
        if (url == null || url.isEmpty()) {
            return;
        }

        mIsSniffing = true;

        PlayerSettingsManager settingsManager = PlayerSettingsManager.getInstance(mContext);
        boolean autoPlay = settingsManager.isSniffingAutoPlayEnabled();

        if (mViewCallback != null) {
            mViewCallback.onSniffingStarted(autoPlay);
        }

        VideoSniffing.startSniffing(mContext, url, headers, new VideoSniffing.Call() {
            @Override
            public void received(String contentType, java.util.HashMap<String, String> respHeaders,
                                 String title, String videoUrl) {
                VideoSniffing.VideoInfo videoInfo = new VideoSniffing.VideoInfo(videoUrl, contentType, title,
                        respHeaders);
                if (mViewCallback != null) {
                    mViewCallback.onSniffingReceived(videoInfo);
                    mViewCallback.onSniffingReceivedRaw(contentType, respHeaders, title, videoUrl);
                }
            }

            @Override
            public void onFinish(List<VideoSniffing.VideoInfo> videoList, int videoSize) {
                mIsSniffing = false;

                PlayerSettingsManager sm = PlayerSettingsManager.getInstance(mContext);
                boolean autoPlayOnFinish = sm.isSniffingAutoPlayEnabled();

                if (mViewCallback != null) {
                    mViewCallback.onSniffingFinished(videoList, videoSize, autoPlayOnFinish);
                }
            }
        });
    }

    /**
     * 停止视频嗅探
     */
    public void stop() {
        mIsSniffing = false;
        VideoSniffing.stop(true);
    }
}
