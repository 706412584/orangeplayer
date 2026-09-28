package com.orange.playerlibrary.interfaces;

import java.util.List;

/**
 * 弹幕控制器接口
 * SDK 层定义接口，App 层实现具体功能
 * 
 * 使用方式：
 * 1. App 层实现此接口
 * 2. 通过 OrangeVideoController.setDanmakuController() 设置
 * 3. SDK 会在适当时机调用接口方法
 */
public interface IDanmakuController {
    
    /**
     * 弹幕数据项
     */
    class DanmakuItem {
        private final String text;
        private final int color;
        private final long timestamp;
        private final boolean isSelf;
        
        public DanmakuItem(String text, int color, long timestamp, boolean isSelf) {
            this.text = text;
            this.color = color;
            this.timestamp = timestamp;
            this.isSelf = isSelf;
        }
        
        public String getText() { return text; }
        public int getColor() { return color; }
        public long getTimestamp() { return timestamp; }
        public boolean isSelf() { return isSelf; }
    }
    
    /**
     * 设置弹幕数据
     * @param danmakuList 弹幕列表
     */
    void setDanmakuData(List<DanmakuItem> danmakuList);
    
    /**
     * 发送用户弹幕
     * @param text 弹幕文本
     * @param color 弹幕颜色
     */
    /** 滚动弹幕（默认）。 */
    int LOCATION_SCROLL = 1;
    /** 顶部固定弹幕。 */
    int LOCATION_TOP = 4;
    /** 底部固定弹幕。 */
    int LOCATION_BOTTOM = 5;

    void sendDanmaku(String text, int color);

    /**
     * 发送带位置（滚动/顶部/底部）的弹幕。
     *
     * <p>默认实现退化为 {@link #sendDanmaku(String, int)}，即忽略位置；
     * 支持的实现（{@code DanmakuControllerImpl}）会按 {@code location} 选择弹幕类型。</p>
     */
    default void sendDanmaku(String text, int color, int location) {
        sendDanmaku(text, color);
    }
    
    /**
     * 开启/关闭弹幕
     * @param enabled 是否开启
     */
    void setDanmakuEnabled(boolean enabled);
    
    /**
     * 弹幕是否开启
     */
    boolean isDanmakuEnabled();
    
    /**
     * 设置弹幕文字大小
     * @param spSize 字体大小(sp)
     */
    void setDanmakuTextSize(float spSize);
    
    /**
     * 设置弹幕速度
     * @param speedFactor 速度因子 (0.5-3.0)
     */
    void setDanmakuSpeed(float speedFactor);
    
    /**
     * 设置弹幕透明度
     * @param alpha 透明度 (0.0-1.0)
     */
    void setDanmakuAlpha(float alpha);
    
    /**
     * 清除所有弹幕
     */
    void clearDanmakus();
    
    /**
     * 暂停弹幕
     */
    void pauseDanmaku();
    
    /**
     * 恢复弹幕
     */
    void resumeDanmaku();
    
    /**
     * 跳转弹幕到指定位置
     * @param position 视频位置(毫秒)
     */
    void seekDanmakuTo(long position);
    
    /**
     * 释放弹幕资源
     */
    void releaseDanmaku();
}
