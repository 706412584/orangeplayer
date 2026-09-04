package com.orange.player.session;

import android.content.Context;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.session.MediaSession;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import com.orange.playerlibrary.OrangevideoView;
import com.orange.playerlibrary.PlayerConstants;
import com.orange.playerlibrary.interfaces.OnStateChangeListener;

import java.util.Collections;
import java.util.List;

/**
 * MediaSession 适配器（app 层，Activity 内会话，仅系统控制入口）。
 * <p>
 * 职责：耳机/蓝牙按键、锁屏、TV 遥控、Assistant 的播放控制与状态同步。
 * 不做后台播放（保持 onStop 暂停语义），不引入前台服务。
 * <p>
 * 用 media3 SimpleBasePlayer 包装 OrangevideoView（GSY 播放栈），
 * palyerlibrary 不感知 MediaSession。
 */
public class OrangePlayerSessionHelper {

    private static final String TAG = "OrangeSession";

    private final Context context;
    private final OrangevideoView videoView;
    @Nullable
    private MediaSession mediaSession;
    @Nullable
    private OrangeBasePlayer playerAdapter;

    public OrangePlayerSessionHelper(Context context, OrangevideoView videoView) {
        this.context = context.getApplicationContext();
        this.videoView = videoView;
    }

    /** Activity/Fragment onStart 时创建会话 */
    public void start(String mediaUrl, String title) {
        if (mediaSession != null) {
            playerAdapter.updateMediaItem(mediaUrl, title);
            return;
        }
        try {
            playerAdapter = new OrangeBasePlayer(Looper.getMainLooper());
            playerAdapter.updateMediaItem(mediaUrl, title);
            mediaSession = new MediaSession.Builder(context, playerAdapter).build();
            videoView.addOnStateChangeListener(stateListener);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "MediaSession start failed", t);
            releaseQuietly();
        }
    }

    /** Activity/Fragment onStop 时释放会话 */
    public void stop() {
        try {
            videoView.removeOnStateChangeListener(stateListener);
        } catch (Throwable ignored) {
        }
        releaseQuietly();
    }

    private void releaseQuietly() {
        try {
            if (mediaSession != null) {
                mediaSession.release();
            }
            if (playerAdapter != null) {
                playerAdapter.release();
            }
        } catch (Throwable ignored) {
        }
        mediaSession = null;
        playerAdapter = null;
    }

    /** Orange 状态变化 → 通知 session 刷新 */
    private final OnStateChangeListener stateListener = new OnStateChangeListener() {
        @Override
        public void onPlayerStateChanged(int playerState) {
        }

        @Override
        public void onPlayStateChanged(int playState) {
            if (playerAdapter != null) {
                playerAdapter.refreshState();
            }
        }
    };

    /** OrangevideoView → media3 Player 的薄适配（SimpleBasePlayer） */
    private class OrangeBasePlayer extends SimpleBasePlayer {

        private volatile MediaItem currentMediaItem = MediaItem.EMPTY;

        OrangeBasePlayer(Looper looper) {
            super(looper);
        }

        void updateMediaItem(String url, String title) {
            if (url == null) {
                return;
            }
            currentMediaItem = new MediaItem.Builder()
                    .setUri(android.net.Uri.parse(url))
                    .setMediaMetadata(new MediaMetadata.Builder()
                            .setTitle(title != null ? title : "OrangePlayer")
                            .build())
                    .build();
            invalidateState();
        }

        /** 供外部触发的状态刷新（invalidateState 为 protected final） */
        void refreshState() {
            invalidateState();
        }

        /** Orange 状态 → Player 状态映射（自定义状态按 BUFFERING 语义） */
        private int mapPlaybackState() {
            switch (videoView.getPlayState()) {
                case PlayerConstants.STATE_PREPARING:
                case PlayerConstants.STATE_BUFFERING:
                case PlayerConstants.STATE_M3U8_AD_REMOVAL:
                case PlayerConstants.STATE_M3U8_AD_REMOVAL_END:
                case PlayerConstants.STATE_STARTSNIFFING:
                case PlayerConstants.STATE_ENDSNIFFING:
                    return STATE_BUFFERING;
                case PlayerConstants.STATE_PREPARED:
                case PlayerConstants.STATE_PLAYING:
                case PlayerConstants.STATE_PAUSED:
                case PlayerConstants.STATE_BUFFERED:
                    return STATE_READY;
                case PlayerConstants.STATE_PLAYBACK_COMPLETED:
                    return STATE_ENDED;
                default:
                    return STATE_IDLE;
            }
        }

        @Override
        protected State getState() {
            long duration = safeDuration();
            return new State.Builder()
                    .setAvailableCommands(new Commands.Builder()
                            .addAll(COMMAND_PLAY_PAUSE, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                                    COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD,
                                    COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE,
                                    COMMAND_GET_METADATA, COMMAND_SET_MEDIA_ITEM)
                            .build())
                    .setPlayWhenReady(videoView.isPlaying(),
                            PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    .setPlaybackState(mapPlaybackState())
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build())
                    .setContentPositionMs(videoView.getCurrentPosition())
                    .setContentBufferedPositionMs(PositionSupplier.getConstant(safeBuffered()))
                    .setTotalBufferedDurationMs(PositionSupplier.getConstant(safeBuffered()))
                    .setCurrentMediaItemIndex(0)
                    .setPlaylist(Collections.singletonList(
                            new MediaItemData.Builder(/* uid= */ 0)
                                    .setMediaItem(currentMediaItem)
                                    .setDurationUs(duration > 0 ? duration * 1000 : C.TIME_UNSET)
                                    .setIsSeekable(duration > 0)
                                    .build()))
                    .build();
        }

        @Override
        protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
            if (playWhenReady) {
                videoView.onVideoResume();
            } else {
                videoView.onVideoPause();
            }
            return Futures.immediateVoidFuture();
        }

        @Override
        protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommandName) {
            videoView.seekTo(positionMs);
            return Futures.immediateVoidFuture();
        }

        @Override
        protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> mediaItems,
                                                          int startIndex, long startPositionMs) {
            if (!mediaItems.isEmpty()) {
                currentMediaItem = mediaItems.get(0);
            }
            return Futures.immediateVoidFuture();
        }

        private long safeDuration() {
            try {
                long d = videoView.getDuration();
                return d > 0 ? d : C.TIME_UNSET;
            } catch (Throwable t) {
                return C.TIME_UNSET;
            }
        }

        private long safeBuffered() {
            // OrangevideoView 无缓冲位置公开 API，用 0 占位（不影响控制功能）
            return 0;
        }
    }
}
