package com.orange.playerlibrary.component;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.orange.playerlibrary.OrangeVideoController;
import com.orange.playerlibrary.PlayerConstants;
import com.orange.playerlibrary.interfaces.ControlWrapper;
import com.orange.playerlibrary.interfaces.IControlComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import master.flame.danmaku.controller.DrawHandler;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.danmaku.model.IDanmakus;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.danmaku.model.android.Danmakus;
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser;
import master.flame.danmaku.ui.widget.DanmakuView;

/**
 * 弹幕视图
 * 显示弹幕和弹幕开关控制
 * 
 * Requirements: 3.7
 */
public class DanmaView extends DanmakuView implements IControlComponent {
    
    private static final String TAG = "DanmaView";
    private static boolean sDebug = false;
    
    private ControlWrapper mControlWrapper;
    private DanmakuContext mDanmakuContext;
    private BaseDanmakuParser mParser;
    private int mTextSize;
    
    // 弹幕总开关状态
    private boolean mDanmakuEnabled = true;
    // 弹幕速度因子
    private float mScrollSpeedFactor = 1.5f;
    
    // 弹幕数据缓存
    private List<DanmakuItem> mAllDanmakus = new ArrayList<>();
    private boolean mDanmakuPrepared = false;
    
    // 用户弹幕队列
    private final Queue<DanmakuItem> mUserDanmakuQueue = new LinkedBlockingQueue<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Object mLock = new Object();
    
    // 弹幕防重发机制
    private final Set<String> mSentDanmakuSet = new HashSet<>();
    private final Map<String, Long> mLastSentTimeMap = new HashMap<>();
    private static final long REPEAT_THRESHOLD = 500;
    
    // 实时弹幕匹配相关变量
    private int mLastCheckedIndex = 0;
    private final Set<String> mRealTimeAddedKeys = new HashSet<>();
    private long mLastPosition = 0;
    private static final long PROGRESS_BACKWARD_THRESHOLD = 2000;
    
    // 弹幕显示跟踪
    private final Set<String> mShownDanmakuSet = new HashSet<>();
    private boolean mIsDanmakuRolling = true;

    public DanmaView(@NonNull Context context) {
        super(context);
        init();
    }

    public DanmaView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DanmaView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mTextSize = sp2px(getContext(), 14);
        
        // 配置弹幕上下文
        createDanmakuContext();
        
        // 初始化解析器
        mParser = new BaseDanmakuParser() {
            @Override
            protected IDanmakus parse() {
                return new Danmakus();
            }
        };
        
        // 设置回调
        setCallback(new DrawHandler.Callback() {
            @Override
            public void prepared() {
                mDanmakuPrepared = true;
                if (mDanmakuEnabled) {
                    start();
                }
                if (!mUserDanmakuQueue.isEmpty() && mDanmakuEnabled) {
                    processUserDanmakuQueue();
                }
                clearSentDanmakuSet();
            }

            @Override
            public void updateTimer(DanmakuTimer timer) {
            }

            @Override
            public void danmakuShown(BaseDanmaku danmaku) {
                String key = generateDanmakuKey(danmaku);
                mShownDanmakuSet.add(key);
            }

            @Override
            public void drawingFinished() {
            }
        });
        
        enableDanmakuDrawingCache(true);
    }

    private void createDanmakuContext() {
        try {
            mDanmakuContext = DanmakuContext.create();
            if (mDanmakuContext != null) {
                Map<Integer, Integer> maxLinesMap = new HashMap<>();
                maxLinesMap.put(BaseDanmaku.TYPE_SCROLL_RL, 8);
                maxLinesMap.put(BaseDanmaku.TYPE_FIX_TOP, 1);
                maxLinesMap.put(BaseDanmaku.TYPE_FIX_BOTTOM, 1);
                
                Map<Integer, Boolean> overlappingMap = new HashMap<>();
                overlappingMap.put(BaseDanmaku.TYPE_SCROLL_RL, true);
                overlappingMap.put(BaseDanmaku.TYPE_FIX_TOP, false);
                overlappingMap.put(BaseDanmaku.TYPE_FIX_BOTTOM, false);
                
                mDanmakuContext.setDanmakuMargin(0)
                        .setScrollSpeedFactor(mScrollSpeedFactor)
                        .setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3)
                        .setDuplicateMergingEnabled(true)
                        .setScaleTextSize(1.0f)
                        .setMaximumLines(maxLinesMap)
                        .preventOverlapping(overlappingMap)
                        // 修复抽搐问题：设置弹幕最大显示时间，避免时间同步问题
                        .setMaximumVisibleSizeInScreen(50);
            }
        } catch (Exception e) {
            debug("创建弹幕上下文失败: " + e.getMessage());
        }
    }

    private String generateDanmakuKey(BaseDanmaku danmaku) {
        return danmaku.text + "|" + danmaku.getActualTime() + "|" + danmaku.textColor;
    }

    private String generateDanmakuKey(DanmakuItem item) {
        return item.getText() + "|" + item.getTimestamp() + "|" + item.getColor();
    }

    private void clearSentDanmakuSet() {
        mSentDanmakuSet.clear();
        mLastSentTimeMap.clear();
    }

    public void setOrangeVideoController(OrangeVideoController controller) {
        // 预留控制器关联
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
        if (isVisible && isPrepared() && isPaused() && mDanmakuEnabled) {
            resume();
        }
    }

    /**
     * 设置所有弹幕
     */
    public void setAllDanmakus(List<DanmakuItem> danmakuList) {
        if (danmakuList == null || danmakuList.isEmpty()) {
            debug("弹幕列表为空");
            return;
        }
        
        List<DanmakuItem> uniqueList = new ArrayList<>();
        Set<String> uniqueKeys = new HashSet<>();
        for (DanmakuItem item : danmakuList) {
            String key = generateDanmakuKey(item);
            if (uniqueKeys.add(key)) {
                uniqueList.add(item);
            }
        }
        
        Collections.sort(uniqueList, (o1, o2) -> Long.compare(o1.getTimestamp(), o2.getTimestamp()));
        
        synchronized (mLock) {
            mAllDanmakus = uniqueList;
            mLastCheckedIndex = 0;
            mRealTimeAddedKeys.clear();
            mLastPosition = 0;
        }
        
        if (!isPrepared() && mDanmakuContext != null) {
            prepare(mParser, mDanmakuContext);
        }
    }

    private void addRealTimeDanmakus(long currentPosition) {
        if (!mDanmakuEnabled || mAllDanmakus.isEmpty() || !isPrepared() || !mDanmakuPrepared) {
            return;
        }
        
        long startTime = currentPosition - 1000;
        long endTime = currentPosition + 1000;
        
        synchronized (mLock) {
            for (int i = mLastCheckedIndex; i < mAllDanmakus.size(); i++) {
                DanmakuItem item = mAllDanmakus.get(i);
                long itemTime = item.getTimestamp();
                
                if (itemTime > endTime) {
                    mLastCheckedIndex = i;
                    break;
                }
                
                if (itemTime >= startTime && itemTime <= endTime) {
                    String key = generateDanmakuKey(item);
                    if (!mRealTimeAddedKeys.contains(key)) {
                        addDanmakuItem(item);
                        mRealTimeAddedKeys.add(key);
                    }
                }
            }
        }
    }

    private void addDanmakuItem(DanmakuItem item) {
        if (!mDanmakuEnabled || !isPrepared() || mDanmakuContext == null) {
            return;
        }
        
        try {
            BaseDanmaku danmaku = mDanmakuContext.mDanmakuFactory.createDanmaku(BaseDanmaku.TYPE_SCROLL_RL);
            if (danmaku == null) {
                return;
            }
            
            danmaku.setTime(item.getTimestamp());
            danmaku.text = item.getText();
            danmaku.textColor = item.getColor();
            danmaku.textSize = mTextSize;
            danmaku.textShadowColor = Color.GRAY;
            danmaku.borderColor = item.isSelf() ? Color.GREEN : Color.TRANSPARENT;
            danmaku.isLive = false;
            danmaku.priority = 0;
            
            String key = generateDanmakuKey(item);
            if (mSentDanmakuSet.contains(key)) {
                return;
            }
            
            addDanmaku(danmaku);
            mSentDanmakuSet.add(key);
            mLastSentTimeMap.put(key, System.currentTimeMillis());
        } catch (Exception e) {
            debug("创建弹幕失败: " + e.getMessage());
        }
    }

    @Override
    public void onPlayStateChanged(int playState) {
        if (!mDanmakuEnabled) {
            return;
        }
        
        switch (playState) {
            case PlayerConstants.STATE_IDLE:
                // 在后台线程释放弹幕，避免主线程阻塞导致 ANR
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        release();
                    }
                }).start();
                mDanmakuPrepared = false;
                clearSentDanmakuSet();
                mRealTimeAddedKeys.clear();
                break;
                
            case PlayerConstants.STATE_PREPARING:
                // 在后台线程准备弹幕，避免主线程阻塞导致 ANR
                if (!isPrepared()) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            prepare(mParser, mDanmakuContext);
                            // 准备完成后，如果需要暂停，在主线程执行
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    if (isPrepared() && !isPaused()) {
                                        pause();
                                    }
                                }
                            });
                        }
                    }).start();
                } else if (isPrepared() && !isPaused()) {
                    pause();
                }
                break;
                
            case PlayerConstants.STATE_PLAYING:
            case PlayerConstants.STATE_BUFFERED:
                if (isPrepared() && isPaused()) {
                    resume();
                    mIsDanmakuRolling = true;
                }
                if (isPrepared()) {
                    mDanmakuPrepared = true;
                    processUserDanmakuQueue();
                }
                break;
                
            case PlayerConstants.STATE_BUFFERING:
                if (isPrepared() && !isPaused()) {
                    mIsDanmakuRolling = false;
                    pause();
                }
                break;
                
            case PlayerConstants.STATE_PAUSED:
                if (isPrepared() && !isPaused()) {
                    pause();
                    mIsDanmakuRolling = false;
                }
                break;
                
            case PlayerConstants.STATE_PLAYBACK_COMPLETED:
                if (isPrepared() && !isPaused()) {
                    pause();
                }
                break;
                
            default:
                if (isPrepared() && !isPaused()) {
                    pause();
                }
                break;
        }
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
        if (!mDanmakuEnabled) {
            return;
        }
        
        if (playerState == PlayerConstants.PLAYER_FULL_SCREEN 
                || playerState == PlayerConstants.PLAYER_NORMAL) {
            final long currentPosition = getCurrentTime();
            
            // 在后台线程执行 release 和 prepare 操作，避免阻塞主线程（修复 ANR）
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (isPrepared()) {
                        release();
                        mDanmakuPrepared = false;
                    }
                    
                    // 延迟后在后台线程准备弹幕
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    
                    if (!isPrepared() && mDanmakuContext != null) {
                        prepare(mParser, mDanmakuContext);
                        
                        // 准备完成后在主线程执行 seekTo
                        postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (isPrepared() && mDanmakuPrepared) {
                                    seekTo(currentPosition);
                                    mLastCheckedIndex = 0;
                                    mRealTimeAddedKeys.clear();
                                    mLastPosition = currentPosition;
                                }
                            }
                        }, 500);
                    }
                }
            }).start();
        }
    }

    @Override
    public void setProgress(int duration, int position) {
        long currentPosition = position;
        
        // 无条件日志，确认方法被调用
        Log.d(TAG, "setProgress called: duration=" + duration + ", position=" + position + 
                ", enabled=" + mDanmakuEnabled + ", prepared=" + isPrepared() + 
                ", danmakuPrepared=" + mDanmakuPrepared + ", allDanmakus=" + mAllDanmakus.size());
        
        if (!mDanmakuEnabled || !isPrepared() || !mDanmakuPrepared) {
            return;
        }
        
        long positionDiff = currentPosition - mLastPosition;
        
        // 进度回退超过阈值，重置匹配状态
        if (positionDiff < -PROGRESS_BACKWARD_THRESHOLD) {
            mLastCheckedIndex = 0;
            mRealTimeAddedKeys.clear();
            debug("进度回退，重置弹幕匹配状态");
        }
        
        // 大跳变处理（超过5秒才同步，避免频繁 seekTo 导致抽搐）
        long timeDiff = Math.abs(currentPosition - getCurrentTime());
        if (timeDiff > 5000) {
            seekTo(currentPosition);
            mLastCheckedIndex = 0;
            mRealTimeAddedKeys.clear();
            debug("进度跳变超过5秒，同步弹幕位置至：" + currentPosition + "ms");
        }
        
        // 实时匹配并添加弹幕
        addRealTimeDanmakus(currentPosition);
        mLastPosition = currentPosition;
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
        setEnabled(!isLocked && mDanmakuEnabled);
    }

    /**
     * 发送用户弹幕
     */
    public void addUserDanmaku(String text, int color, boolean isSelf) {
        addUserDanmaku(text, color, isSelf,
                com.orange.playerlibrary.interfaces.IDanmakuController.LOCATION_SCROLL);
    }

    public void addUserDanmaku(String text, int color, boolean isSelf, int location) {
        if (!mDanmakuEnabled) {
            return;
        }
        
        long showTime = getCurrentTime() + 1000;
        DanmakuItem danmakuItem = new DanmakuItem(text, color, showTime, isSelf, location);
        String key = generateDanmakuKey(danmakuItem);
        
        Long lastSentTime = mLastSentTimeMap.get(key);
        if (lastSentTime != null && (System.currentTimeMillis() - lastSentTime) < REPEAT_THRESHOLD) {
            return;
        }
        
        boolean sent = trySendDanmaku(danmakuItem);
        if (!sent) {
            mUserDanmakuQueue.offer(danmakuItem);
            if (!isPrepared() && mDanmakuContext != null) {
                prepare(mParser, mDanmakuContext);
            }
        }
    }

    private void processUserDanmakuQueue() {
        if (!mDanmakuEnabled) {
            return;
        }
        
        while (!mUserDanmakuQueue.isEmpty()) {
            DanmakuItem item = mUserDanmakuQueue.poll();
            if (!trySendDanmaku(item)) {
                mUserDanmakuQueue.offer(item);
                break;
            }
        }
    }

    /** 橘子的位置常量 → DanmakuFlameMaster 的弹幕类型。 */
    private static int danmakuTypeOf(int location) {
        switch (location) {
            case com.orange.playerlibrary.interfaces.IDanmakuController.LOCATION_TOP:
                return BaseDanmaku.TYPE_FIX_TOP;
            case com.orange.playerlibrary.interfaces.IDanmakuController.LOCATION_BOTTOM:
                return BaseDanmaku.TYPE_FIX_BOTTOM;
            default:
                return BaseDanmaku.TYPE_SCROLL_RL;
        }
    }

    private boolean trySendDanmaku(DanmakuItem item) {
        if (!mDanmakuEnabled || !isPrepared() || mDanmakuContext == null) {
            return false;
        }
        
        try {
            int danmakuType = danmakuTypeOf(item.getLocation());
            BaseDanmaku danmaku = mDanmakuContext.mDanmakuFactory.createDanmaku(danmakuType);
            if (danmaku == null) {
                return false;
            }
            
            danmaku.setTime(item.getTimestamp());
            danmaku.text = item.getText();
            danmaku.textColor = item.getColor();
            danmaku.textSize = mTextSize;
            danmaku.textShadowColor = Color.GRAY;
            danmaku.borderColor = item.isSelf() ? Color.GREEN : Color.TRANSPARENT;
            danmaku.priority = 1;
            
            String key = generateDanmakuKey(item);
            if (mSentDanmakuSet.contains(key)) {
                return false;
            }
            
            addDanmaku(danmaku);
            mSentDanmakuSet.add(key);
            mLastSentTimeMap.put(key, System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            debug("创建用户弹幕失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 清除所有弹幕
     */
    public void clearDanmakus() {
        try {
            if (isPrepared()) {
                clear();
                clearDanmakusOnScreen();
                clearSentDanmakuSet();
                mRealTimeAddedKeys.clear();
                mShownDanmakuSet.clear();
            }
        } catch (Exception e) {
            debug("清除弹幕失败: " + e.getMessage());
        }
    }

    /**
     * 设置弹幕字体大小
     */
    public void setDanmakuTextSize(float spSize) {
        mTextSize = sp2px(getContext(), spSize);
    }

    /**
     * 弹幕总开关控制
     */
    public void setDanmakuEnabled(boolean enabled) {
        if (mDanmakuEnabled == enabled) {
            return;
        }
        mDanmakuEnabled = enabled;
        
        if (enabled && !isPrepared() && mDanmakuContext != null && mParser != null) {
            prepare(mParser, mDanmakuContext);
        }
        
        if (!isPrepared()) {
            setEnabled(enabled);
            return;
        }
        
        if (enabled) {
            if (mControlWrapper != null && mControlWrapper.isPlaying()) {
                resume();
                mIsDanmakuRolling = true;
            } else {
                pause();
                mIsDanmakuRolling = false;
            }
            
            long currentVideoPosition = mControlWrapper != null ? mControlWrapper.getCurrentPosition() : 0;
            addRealTimeDanmakus(currentVideoPosition);
            
            if (isPrepared() && isPaused()) {
                resume();
                mIsDanmakuRolling = true;
            }
            if (isPrepared()) {
                mDanmakuPrepared = true;
                processUserDanmakuQueue();
            }
            show();
        } else {
            mIsDanmakuRolling = !isPaused();
            pause();
            clearDanmakusOnScreen();
            hide();
        }
        setEnabled(enabled);
    }

    public boolean isDanmakuEnabled() {
        return mDanmakuEnabled;
    }

    /**
     * 设置弹幕速度
     */
    public void setDanmakuSpeed(float speedFactor) {
        if (speedFactor <= 0) {
            return;
        }
        mScrollSpeedFactor = speedFactor;
        if (mDanmakuContext != null) {
            mDanmakuContext.setScrollSpeedFactor(speedFactor);
        }
    }

    /**
     * 设置弹幕边距
     */
    public void setDanmakuMargin(float dpSize) {
        int margin = dp2px(getContext(), dpSize);
        if (mDanmakuContext != null) {
            mDanmakuContext.setDanmakuMargin(margin);
        }
    }

    /**
     * 设置弹幕行数
     */
    public void setMaximumLines(Map<Integer, Integer> maxLinesMap) {
        if (mDanmakuContext != null) {
            mDanmakuContext.setMaximumLines(maxLinesMap);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        try {
            if (isPrepared()) {
                release();
            }
            mDanmakuPrepared = false;
            mUserDanmakuQueue.clear();
            mRealTimeAddedKeys.clear();
        } catch (Exception e) {
            debug("释放弹幕失败: " + e.getMessage());
        }
    }

    // ===== 辅助方法 =====

    private int sp2px(Context context, float spValue) {
        float fontScale = context.getResources().getDisplayMetrics().scaledDensity;
        return (int) (spValue * fontScale + 0.5f);
    }

    private int dp2px(Context context, float dpValue) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (dpValue * density + 0.5f);
    }

    private void debug(String message) {
        if (sDebug) {
            Log.d(TAG, message);
        }
    }

    public void setDebug(boolean debug) {
        sDebug = debug;
    }

    /**
     * 弹幕数据项
     */
    public static class DanmakuItem {
        private final String text;
        private final int color;
        private final long timestamp;
        private final boolean isSelf;
        /** 弹幕位置：IDanmakuController.LOCATION_*（滚动/顶部/底部）。 */
        private final int location;

        public DanmakuItem(String text, int color, long timestamp, boolean isSelf) {
            this(text, color, timestamp, isSelf, com.orange.playerlibrary.interfaces.IDanmakuController.LOCATION_SCROLL);
        }

        public DanmakuItem(String text, int color, long timestamp, boolean isSelf, int location) {
            this.text = text;
            this.color = color;
            this.timestamp = timestamp;
            this.isSelf = isSelf;
            this.location = location;
        }

        public int getLocation() {
            return location;
        }

        public String getText() {
            return text;
        }

        public int getColor() {
            return color;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public boolean isSelf() {
            return isSelf;
        }
    }
}
