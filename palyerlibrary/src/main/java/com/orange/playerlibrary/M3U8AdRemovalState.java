package com.orange.playerlibrary;

import java.util.Map;

/**
 * M3U8 去广告流程状态管理类。
 * 持有去广告流程中的所有状态标记，避免 OrangevideoView 中大量字段堆积。
 */
public class M3U8AdRemovalState {

    private String mOriginalUrl = null;
    /**
     * 调用方传入的播放源地址，未经任何内部改写。
     *
     * 与 mOriginalUrl 的区别：mOriginalUrl 是去广告重试路径的临时状态，会被
     * 每次 setUp 的 clear() 抹掉、且只在去广告时才有值。本字段在 setUp 入口
     * 无条件写入，供「按地址反查选集索引」这类场景使用——播放地址在内部会被
     * 改写（M3U8 去广告换成回环代理地址、已下载视频换成 file:// 本地路径），
     * 此时 getUrl() 不再等于调用方当初传入的地址，选集列表按 getUrl() 反查会失败。
     */
    private String mSourceUrl = null;
    private Map<String, String> mOriginalHeaders = null;
    private String mOriginalTitle = "";
    private boolean mOriginalCacheWithPlay = true;
    private boolean mIsPlayingAdRemoved = false;
    private boolean mHasRetriedOriginalUrl = false;
    private boolean mPendingAdRemoval = false;
    private boolean mPendingDiscontinuityCheck = false;
    private String mDiscontinuityCheckedUrl = null;
    private boolean mBypassOnce = false;
    private int mRequestToken = 0;
    private String mUserPreferredEngine = null;
    private boolean mSkipEngineRestore = false;

    /**
     * 重置所有状态
     */
    public void clear() {
        mRequestToken++;
        mPendingAdRemoval = false;
        mPendingDiscontinuityCheck = false;
        mDiscontinuityCheckedUrl = null;
        mBypassOnce = false;
        mOriginalUrl = null;
        mSourceUrl = null;
        mOriginalHeaders = null;
        mOriginalTitle = "";
        mOriginalCacheWithPlay = true;
        mIsPlayingAdRemoved = false;
        mHasRetriedOriginalUrl = false;
    }

    /**
     * 作废进行中的异步请求，但保留原始播放源供错误回退使用。
     */
    public void cancelPendingRequests() {
        mRequestToken++;
        mPendingAdRemoval = false;
        mPendingDiscontinuityCheck = false;
    }

    /**
     * 递增并返回新的请求 token（用于避免过期回调）
     */
    public int nextToken() {
        return ++mRequestToken;
    }

    public int getRequestToken() {
        return mRequestToken;
    }

    // --- Getters & Setters ---

    public String getOriginalUrl() {
        return mOriginalUrl;
    }

    public void setOriginalUrl(String url) {
        mOriginalUrl = url;
    }

    public String getSourceUrl() {
        return mSourceUrl;
    }

    public void setSourceUrl(String url) {
        mSourceUrl = url;
    }

    public Map<String, String> getOriginalHeaders() {
        return mOriginalHeaders;
    }

    public void setOriginalHeaders(Map<String, String> headers) {
        mOriginalHeaders = headers;
    }

    public String getOriginalTitle() {
        return mOriginalTitle;
    }

    public void setOriginalTitle(String title) {
        mOriginalTitle = title != null ? title : "";
    }

    public boolean isOriginalCacheWithPlay() {
        return mOriginalCacheWithPlay;
    }

    public void setOriginalCacheWithPlay(boolean cacheWithPlay) {
        mOriginalCacheWithPlay = cacheWithPlay;
    }

    public boolean isPlayingAdRemoved() {
        return mIsPlayingAdRemoved;
    }

    public void setPlayingAdRemoved(boolean playing) {
        mIsPlayingAdRemoved = playing;
    }

    public boolean hasRetriedOriginalUrl() {
        return mHasRetriedOriginalUrl;
    }

    public void setHasRetriedOriginalUrl(boolean retried) {
        mHasRetriedOriginalUrl = retried;
    }

    public boolean isPendingAdRemoval() {
        return mPendingAdRemoval;
    }

    public void setPendingAdRemoval(boolean pending) {
        mPendingAdRemoval = pending;
    }

    public boolean isPendingDiscontinuityCheck() {
        return mPendingDiscontinuityCheck;
    }

    public void setPendingDiscontinuityCheck(boolean pending) {
        mPendingDiscontinuityCheck = pending;
    }

    public String getDiscontinuityCheckedUrl() {
        return mDiscontinuityCheckedUrl;
    }

    public void setDiscontinuityCheckedUrl(String url) {
        mDiscontinuityCheckedUrl = url;
    }

    public boolean isBypassOnce() {
        return mBypassOnce;
    }

    public void setBypassOnce(boolean bypass) {
        mBypassOnce = bypass;
    }

    public String getUserPreferredEngine() {
        return mUserPreferredEngine;
    }

    public void setUserPreferredEngine(String engine) {
        mUserPreferredEngine = engine;
    }

    public boolean isSkipEngineRestore() {
        return mSkipEngineRestore;
    }

    public void setSkipEngineRestore(boolean skip) {
        mSkipEngineRestore = skip;
    }
}
