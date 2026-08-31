package com.orange.playerlibrary;

import java.util.Map;

/**
 * M3U8 去广告流程状态管理类。
 * 持有去广告流程中的所有状态标记，避免 OrangevideoView 中大量字段堆积。
 */
public class M3U8AdRemovalState {

    private String mOriginalUrl = null;
    private Map<String, String> mOriginalHeaders = null;
    private String mOriginalTitle = "";
    private boolean mOriginalCacheWithPlay = true;
    private boolean mIsPlayingAdRemoved = false;
    private boolean mHasRetriedOriginalUrl = false;
    private boolean mPendingAdRemoval = false;
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
        mBypassOnce = false;
        mOriginalUrl = null;
        mOriginalHeaders = null;
        mOriginalTitle = "";
        mOriginalCacheWithPlay = true;
        mIsPlayingAdRemoved = false;
        mHasRetriedOriginalUrl = false;
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
