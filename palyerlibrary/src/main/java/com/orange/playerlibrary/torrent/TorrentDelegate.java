package com.orange.playerlibrary.torrent;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.Map;

/**
 * 种子播放代理类，将 OrangevideoView 中的种子相关逻辑下沉到此处。
 * OrangevideoView 只负责 UI 状态 和 setUp/startPlayLogic 的调用。
 */
public class TorrentDelegate {

    private static final String TAG = "TorrentDelegate";

    /**
     * View 层需要实现的回调接口，用于通知 View 进行 UI 操作
     */
    public interface ViewCallback {
        void onTorrentReady(String proxyUrl, String fileName, long fileSize);
        void onTorrentError(String error);
        void onMagnetResolvingProgress(int elapsedSeconds, int totalSeconds);
        void onTorrentLoadingProgress(int elapsedSeconds, int totalSeconds);
        void onTorrentBufferProgress(int bufferedPieces, int totalPieces, long bufferedBytes);
        void onTorrentDownloadProgress(int progress, long downloadSpeed, long uploadSpeed);
    }

    private final Context mContext;
    private ViewCallback mViewCallback;
    private boolean mPendingLoad = false;

    public TorrentDelegate(Context context) {
        mContext = context.getApplicationContext();
    }

    public void setViewCallback(ViewCallback callback) {
        mViewCallback = callback;
    }

    /**
     * 是否正在异步加载种子（防止外部 startPlayLogic 提前触发）
     */
    public boolean isPendingLoad() {
        return mPendingLoad;
    }

    /**
     * 检查 URL 是否是种子/磁力链接，如果是则启动加载流程
     * @return true 表示是种子 URL 并已开始加载，false 表示不是种子 URL
     */
    public boolean handleIfTorrentUrl(String url) {
        boolean isTorrent = TorrentSupport.isTorrentUrl(url);
        Log.d(TAG, "handleIfTorrentUrl: isTorrent=" + isTorrent + " for url=" + url);
        if (!isTorrent) {
            return false;
        }

        String reason = TorrentSupport.getJlibtorrentMissingReason();
        Log.d(TAG, "handleIfTorrentUrl: missingReason=" + reason);
        if (reason != null) {
            Log.e(TAG, "Torrent playback unavailable: " + reason);
            return false;
        }

        mPendingLoad = true;

        File saveDir = TorrentSupport.defaultSaveDir(mContext);
        String cleanUrl = TorrentSupport.extractMagnetUrl(url);
        if (cleanUrl != null && cleanUrl.toLowerCase().startsWith("magnet:")) {
            loadMagnet(cleanUrl, saveDir, null);
        } else {
            loadTorrent(new File(url), saveDir, null);
        }
        return true;
    }

    /**
     * 加载本地种子文件
     */
    public void loadTorrent(File torrentFile, File saveDir,
                            TorrentPlayerManager.TorrentCallback externalCallback) {
        Log.d(TAG, "loadTorrent() called with torrentFile=" + torrentFile + ", exists=" + torrentFile.exists());
        TorrentPlayerManager manager = TorrentPlayerManager.getInstance(mContext);

        if (!manager.isAvailable()) {
            String reason = TorrentSupport.getJlibtorrentMissingReason();
            mPendingLoad = false;
            if (externalCallback != null) {
                externalCallback.onError(reason != null ? reason : "Torrent playback unavailable");
            }
            if (mViewCallback != null) {
                mViewCallback.onTorrentError(reason != null ? reason : "Torrent playback unavailable");
            }
            return;
        }

        File dir = saveDir != null ? saveDir : TorrentSupport.defaultSaveDir(mContext);

        manager.loadTorrent(torrentFile, dir,
                new TorrentPlayerManager.TorrentCallback() {
                    @Override
                    public void onReady(String proxyUrl, String fileName, long fileSize) {
                        mPendingLoad = false;
                        if (mViewCallback != null) {
                            mViewCallback.onTorrentReady(proxyUrl, fileName, fileSize);
                        }
                        if (externalCallback != null) {
                            externalCallback.onReady(proxyUrl, fileName, fileSize);
                        }
                    }

                    @Override
                    public void onBufferProgress(int bufferedPieces, int totalPieces, long bufferedBytes) {
                        if (mViewCallback != null) {
                            mViewCallback.onTorrentBufferProgress(bufferedPieces, totalPieces, bufferedBytes);
                        }
                        if (externalCallback != null) {
                            externalCallback.onBufferProgress(bufferedPieces, totalPieces, bufferedBytes);
                        }
                    }

                    @Override
                    public void onDownloadProgress(int progress, long downloadSpeed, long uploadSpeed) {
                        if (mViewCallback != null) {
                            mViewCallback.onTorrentDownloadProgress(progress, downloadSpeed, uploadSpeed);
                        }
                        if (externalCallback != null) {
                            externalCallback.onDownloadProgress(progress, downloadSpeed, uploadSpeed);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        mPendingLoad = false;
                        if (mViewCallback != null) {
                            mViewCallback.onTorrentError(error);
                        }
                        if (externalCallback != null) {
                            externalCallback.onError(error);
                        }
                    }

                    @Override
                    public void onTorrentLoading(int elapsedSeconds, int totalSeconds) {
                        if (mViewCallback != null) {
                            mViewCallback.onTorrentLoadingProgress(elapsedSeconds, totalSeconds);
                        }
                        if (externalCallback != null) {
                            externalCallback.onTorrentLoading(elapsedSeconds, totalSeconds);
                        }
                    }
                });
    }

    /**
     * 加载磁力链接
     */
    public void loadMagnet(String magnetUri, File saveDir,
                           TorrentPlayerManager.TorrentCallback externalCallback) {
        TorrentPlayerManager manager = TorrentPlayerManager.getInstance(mContext);

        if (!manager.isAvailable()) {
            String reason = TorrentSupport.getJlibtorrentMissingReason();
            mPendingLoad = false;
            if (externalCallback != null) {
                externalCallback.onError(reason != null ? reason : "Torrent playback unavailable");
            }
            if (mViewCallback != null) {
                mViewCallback.onTorrentError(reason != null ? reason : "Torrent playback unavailable");
            }
            return;
        }

        File dir = saveDir != null ? saveDir : TorrentSupport.defaultSaveDir(mContext);

        manager.loadMagnet(magnetUri, dir, new TorrentPlayerManager.TorrentCallback() {
            @Override
            public void onReady(String proxyUrl, String fileName, long fileSize) {
                mPendingLoad = false;
                if (mViewCallback != null) {
                    mViewCallback.onTorrentReady(proxyUrl, fileName, fileSize);
                }
                if (externalCallback != null) {
                    externalCallback.onReady(proxyUrl, fileName, fileSize);
                }
            }

            @Override
            public void onBufferProgress(int bufferedPieces, int totalPieces, long bufferedBytes) {
                if (mViewCallback != null) {
                    mViewCallback.onTorrentBufferProgress(bufferedPieces, totalPieces, bufferedBytes);
                }
                if (externalCallback != null) {
                    externalCallback.onBufferProgress(bufferedPieces, totalPieces, bufferedBytes);
                }
            }

            @Override
            public void onDownloadProgress(int progress, long downloadSpeed, long uploadSpeed) {
                if (mViewCallback != null) {
                    mViewCallback.onTorrentDownloadProgress(progress, downloadSpeed, uploadSpeed);
                }
                if (externalCallback != null) {
                    externalCallback.onDownloadProgress(progress, downloadSpeed, uploadSpeed);
                }
            }

            @Override
            public void onError(String error) {
                mPendingLoad = false;
                if (mViewCallback != null) {
                    mViewCallback.onTorrentError(error);
                }
                if (externalCallback != null) {
                    externalCallback.onError(error);
                }
            }

            @Override
            public void onMagnetResolving(int elapsedSeconds, int totalSeconds) {
                if (mViewCallback != null) {
                    mViewCallback.onMagnetResolvingProgress(elapsedSeconds, totalSeconds);
                }
                if (externalCallback != null) {
                    externalCallback.onMagnetResolving(elapsedSeconds, totalSeconds);
                }
            }
        });
    }

    /**
     * 停止种子下载和代理
     */
    public void stop() {
        mPendingLoad = false;
        TorrentPlayerManager.getInstance(mContext).stop();
    }
}
