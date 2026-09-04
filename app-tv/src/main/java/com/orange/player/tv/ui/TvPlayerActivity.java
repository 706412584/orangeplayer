package com.orange.player.tv.ui;

import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.orange.player.tv.R;
import com.orange.playerlibrary.OrangeVideoController;
import com.orange.playerlibrary.OrangevideoView;
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack;
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer;

/**
 * TV 播放器 Activity
 * 
 * 完全按照 app 模式：标准模式播放，点击全屏按钮进入全屏
 * TV 模式下自动隐藏不适合的 UI（投屏、小窗、弹幕区）
 */
public class TvPlayerActivity extends AppCompatActivity {

    private OrangevideoView videoPlayer;
    private OrangeVideoController controller;
    private com.orange.player.tv.util.TvFrameRateMatcher frameRateMatcher;

    private String videoUrl;
    private String videoTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_player);

        // 获取视频信息
        videoUrl = getIntent().getStringExtra("video_url");
        videoTitle = getIntent().getStringExtra("video_title");

        if (videoUrl == null || videoUrl.isEmpty()) {
            Toast.makeText(this, "视频地址为空", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        initPlayer();

        // 自动帧率匹配（API 23-29；API 30+ 由 ExoPlayer 的
        // Surface.setFrameRate 自动处理）。默认开启，可按需加设置开关。
        if (com.orange.player.tv.util.TvFrameRateMatcher.isApplicable()) {
            frameRateMatcher = new com.orange.player.tv.util.TvFrameRateMatcher(this);
            frameRateMatcher.match(videoUrl);
        }
    }
    
    private void initViews() {
        videoPlayer = findViewById(R.id.video_player);
    }
    
    /**
     * 初始化播放器 - 使用 OrangevideoView 自带的组件
     */
    private void initPlayer() {
        // OrangevideoView 在构造时已经创建了控制器和所有组件
        // 直接获取并使用，不要重新创建
        controller = videoPlayer.getVideoController();
        
        if (controller != null) {
            // 设置加载动画
            controller.setLoading(OrangeVideoController.IndicatorType.LINE_SCALE_PULSE_OUT);
        }
        
        // 关键：强制设置为标准模式（非全屏）
        // 通过反射设置 mIfCurrentIsFullscreen = false
        try {
            java.lang.reflect.Field field = com.shuyu.gsyvideoplayer.video.base.GSYVideoView.class
                .getDeclaredField("mIfCurrentIsFullscreen");
            field.setAccessible(true);
            field.setBoolean(videoPlayer, false);
        } catch (Exception e) {
            android.util.Log.e("TvPlayerActivity", "设置标准模式失败", e);
        }
        
        // 设置播放回调
        videoPlayer.setVideoAllCallBack(new GSYSampleCallBack() {
            @Override
            public void onPrepared(String url, Object... objects) {
                super.onPrepared(url, objects);
            }
            
            @Override
            public void onPlayError(String url, Object... objects) {
                super.onPlayError(url, objects);
                Toast.makeText(TvPlayerActivity.this, "播放出错，请检查网络连接", Toast.LENGTH_LONG).show();
            }
            
            @Override
            public void onAutoComplete(String url, Object... objects) {
                super.onAutoComplete(url, objects);
            }
        });
        
        // 关键：确保以标准模式启动，不要自动全屏
        // 设置视频（标准模式，false）
        videoPlayer.setUp(videoUrl, false, videoTitle);
        videoPlayer.setLooping(false);
        
        // TV 不需要自动旋转和全屏动画
        videoPlayer.setAutoRotateOnFullscreen(false);
        videoPlayer.setShowFullAnimation(false);
        videoPlayer.setRotateViewAuto(false);
        videoPlayer.setNeedLockFull(false);
        videoPlayer.setLockLand(false);
        videoPlayer.setRotateWithSystem(false);
        
        // 关键：禁用方向工具（防止自动全屏）
        videoPlayer.setNeedOrientationUtils(false);
        
        // 开始播放逻辑
        videoPlayer.startPlayLogic();
        
        // 设置标题
        if (videoPlayer.getTitleView() != null) {
            videoPlayer.getTitleView().setTitle(videoTitle);
        }
    }
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (videoPlayer.isIfCurrentIsFullscreen()) {
                    videoPlayer.onBackFullscreen();
                    return true;
                }
                finish();
                return true;
                
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayPause();
                return true;
                
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (videoPlayer.getCurrentState() != GSYVideoPlayer.CURRENT_STATE_PLAYING) {
                    videoPlayer.onVideoResume();
                }
                return true;
                
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (videoPlayer.getCurrentState() == GSYVideoPlayer.CURRENT_STATE_PLAYING) {
                    videoPlayer.onVideoPause();
                }
                return true;
        }
        
        return super.onKeyDown(keyCode, event);
    }
    
    private void togglePlayPause() {
        if (videoPlayer.getCurrentState() == GSYVideoPlayer.CURRENT_STATE_PLAYING) {
            videoPlayer.onVideoPause();
        } else {
            videoPlayer.onVideoResume();
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        videoPlayer.onVideoPause();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        videoPlayer.onVideoResume();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (frameRateMatcher != null) {
            frameRateMatcher.restore();
        }
        if (controller != null) {
            controller.releaseDanmaku();
        }
        videoPlayer.release();
    }
}
