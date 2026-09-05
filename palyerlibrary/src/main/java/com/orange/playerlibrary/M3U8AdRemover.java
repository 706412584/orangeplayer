package com.orange.playerlibrary;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * M3U8去广告处理器
 * 
 * 功能：
 * 1. 检测m3u8中的广告片段
 * 2. 移除广告片段生成干净的m3u8
 * 3. 缓存处理结果避免重复请求
 * 4. 请求失败时返回原始URL
 */
public class M3U8AdRemover {
    
    private static final String TAG = "M3U8AdRemover";
    
    // 广告检测超时时间（毫秒）
    private static final long AD_REMOVAL_TIMEOUT_MS = 10000; // 10 秒
    private static final String CACHE_DIR = "m3u8_cache";
    private static final String CACHE_INDEX_FILE = "cache_index.txt";
    
    private final Context mContext;
    private final File mCacheDir;
    private final ExecutorService mExecutor;

    private volatile String mPlaceholderTsUrl;
    
    // TS白名单（实例级别，重启失效）
    private Set<String> mTsWhitelist = new HashSet<>();
    
    // 广告检测回调
    public interface Callback {
        void onResult(String playUrl, boolean isLocalFile, int adSegmentsRemoved, boolean hasPtsJump);
        void onError(String originalUrl, Exception e);
    }

    public interface DiscontinuityCallback {
        void onResult(boolean hasDiscontinuity);
        void onError(Exception e);
    }
    
    public M3U8AdRemover(Context context) {
        mContext = context.getApplicationContext();
        mCacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!mCacheDir.exists()) {
            mCacheDir.mkdirs();
        }
        mExecutor = Executors.newSingleThreadExecutor();
    }
    
    // ===== TS白名单操作 =====
    
    /**
     * 添加TS片段到白名单
     * 白名单中的片段不会被当作广告过滤
     * 
     * @param tsUrl TS片段的完整URL或URL关键字（包含该关键字的URL都不会被过滤）
     */
    public void addToWhitelist(String tsUrl) {
        if (tsUrl != null && !tsUrl.isEmpty()) {
            mTsWhitelist.add(tsUrl);
            Log.d(TAG, "Added to whitelist: " + tsUrl);
        }
    }
    
    /**
     * 批量添加TS片段到白名单
     * 
     * @param tsUrls TS片段URL列表
     */
    public void addToWhitelist(List<String> tsUrls) {
        if (tsUrls != null) {
            for (String url : tsUrls) {
                addToWhitelist(url);
            }
        }
    }
    
    /**
     * 从白名单移除TS片段
     * 
     * @param tsUrl 要移除的URL
     */
    public void removeFromWhitelist(String tsUrl) {
        if (tsUrl != null) {
            mTsWhitelist.remove(tsUrl);
            Log.d(TAG, "Removed from whitelist: " + tsUrl);
        }
    }
    
    /**
     * 清空白名单
     */
    public void clearWhitelist() {
        mTsWhitelist.clear();
        Log.d(TAG, "Whitelist cleared");
    }
    
    /**
     * 检查URL是否在白名单中
     * 支持完整URL匹配和关键字匹配
     * 
     * @param tsUrl 要检查的URL
     * @return 是否在白名单中
     */
    public boolean isInWhitelist(String tsUrl) {
        if (tsUrl == null || mTsWhitelist.isEmpty()) {
            return false;
        }
        
        // 完整匹配
        if (mTsWhitelist.contains(tsUrl)) {
            return true;
        }
        
        // 关键字匹配（白名单项作为子串）
        for (String whitelistItem : mTsWhitelist) {
            if (tsUrl.contains(whitelistItem)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 获取白名单大小
     */
    public int getWhitelistSize() {
        return mTsWhitelist.size();
    }
    
    /**
     * 处理 m3u8 URL，移除广告片段
     * 
     * @param m3u8Url 原始 m3u8 URL
     * @param callback 结果回调
     */
    public void processM3U8(String m3u8Url, Callback callback) {
        Log.d(TAG, "processM3U8 started: " + m3u8Url);
        final long startTime = System.currentTimeMillis();
            
        mExecutor.execute(() -> {
            boolean callbackExecuted = false;
            try {
                Log.d(TAG, "processM3U8 executing on thread: " + Thread.currentThread().getName());
                processM3U8Internal(m3u8Url, callback);
                callbackExecuted = true;
                long elapsed = System.currentTimeMillis() - startTime;
                Log.d(TAG, "processM3U8 completed successfully in " + elapsed + "ms");
            } catch (Exception e) {
                Log.e(TAG, "processM3U8 error after " + (System.currentTimeMillis() - startTime) + "ms", e);
                try {
                    callback.onError(m3u8Url, e);
                    callbackExecuted = true;
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to call onError callback", ex);
                }
            } finally {
                if (!callbackExecuted) {
                    Log.wtf(TAG, "CRITICAL: Callback was not executed! Forcing error callback.");
                    try {
                        callback.onError(m3u8Url, new Exception("Callback not executed due to unknown error"));
                    } catch (Exception ex) {
                        Log.e(TAG, "Failed to force error callback", ex);
                    }
                }
            }
        });
    }
    
    private void processM3U8Internal(String m3u8Url, Callback callback) throws Exception {
        final long methodStartTime = System.currentTimeMillis();
        Log.d(TAG, "processM3U8Internal started for: " + m3u8Url);
        
        // 1. 检查缓存
        String cacheKey = getCacheKey(m3u8Url);
        File cachedFile = new File(mCacheDir, cacheKey + ".m3u8");
        
        if (cachedFile.exists()) {
            Log.d(TAG, "Cache file exists: " + cachedFile.getAbsolutePath());
            // 检查缓存索引获取广告片段数和 PTS 跳变信息
            CacheIndexEntry cacheEntry = getCacheIndexEntry(cacheKey);
            int adCount = cacheEntry != null ? cacheEntry.adCount : 0;
            boolean hasPtsJump = cacheEntry != null ? cacheEntry.hasPtsJump : false;
            Log.d(TAG, "Using cached m3u8, ad segments removed: " + adCount + ", hasPtsJump: " + hasPtsJump + ", file=" + cachedFile.getAbsolutePath());
            // 缓存文件也需要通过 HTTP URL 返回，确保 server 设置了文件
            String httpPlayUrl = M3U8PlaceholderServer.getInstance(mContext).getCleanedM3u8Url(cachedFile);
            Log.d(TAG, "Cache hit, returning URL in " + (System.currentTimeMillis() - methodStartTime) + "ms");
            // 本地 cleaned m3u8，标记 isLocalFile=true 便于播放器回退
            callback.onResult(httpPlayUrl, true, adCount, hasPtsJump);
            return;

        }
        
        Log.d(TAG, "Cache miss, fetching m3u8 content...");
        
        // 2. 请求 m3u8 内容
        String m3u8Content = fetchM3U8Content(m3u8Url, null);
        Log.d(TAG, "fetchM3U8Content completed in " + (System.currentTimeMillis() - methodStartTime) + "ms, content length: " + 
              (m3u8Content != null ? m3u8Content.length() : 0));
        if (m3u8Content == null || m3u8Content.isEmpty()) {
            Log.w(TAG, "Failed to fetch m3u8 content, using original URL");
            callback.onError(m3u8Url, new Exception("Failed to fetch m3u8 content"));
            return;
        }
        
        // 2.5 检测是否为Master Playlist（嵌套m3u8）
        String subM3U8Url = extractSubM3U8Url(m3u8Content, m3u8Url);
        if (subM3U8Url != null) {
            Log.d(TAG, "Detected Master Playlist, fetching sub playlist: " + subM3U8Url);
            m3u8Content = fetchM3U8Content(subM3U8Url, null);
            Log.d(TAG, "fetchSubM3U8Content completed in " + (System.currentTimeMillis() - methodStartTime) + "ms");
            if (m3u8Content == null || m3u8Content.isEmpty()) {
                Log.w(TAG, "Failed to fetch sub m3u8 content, using original URL");
                callback.onError(m3u8Url, new Exception("Failed to fetch sub m3u8 content"));
                return;
            }
            // 更新URL为子播放列表URL
            m3u8Url = subM3U8Url;
        }
        
        Log.d(TAG, "Starting parseAndRemoveAds at " + (System.currentTimeMillis() - methodStartTime) + "ms...");
        
        // 3. 解析并移除广告片段
        M3U8ParseResult result = parseAndRemoveAds(m3u8Content, m3u8Url);
        
        Log.d(TAG, "parseAndRemoveAds completed in " + (System.currentTimeMillis() - methodStartTime) + 
              "ms, ads removed: " + result.adSegmentsRemoved);
        
        if (result.adSegmentsRemoved == 0) {
            // 没有广告，但可能有 PTS 跳变
            if (result.hasPtsJump) {
                Log.w(TAG, "No ads but PTS jump detected, recommend using ExoPlayer");
            } else {
                Log.d(TAG, "No ad segments found and PTS continuous, using original URL");
            }
            callback.onResult(m3u8Url, false, 0, result.hasPtsJump);
            return;
        }
        
        // 4. 保存处理后的m3u8文件
        String cleanedContent = result.cleanedContent;
        Log.d(TAG, "Writing cleaned m3u8 to cache file: " + cachedFile.getAbsolutePath());
        FileOutputStream fos = new FileOutputStream(cachedFile);
        fos.write(cleanedContent.getBytes("UTF-8"));
        fos.close();
        Log.d(TAG, "File write completed in " + (System.currentTimeMillis() - methodStartTime) + "ms");
        
        // 5. 更新缓存索引
        updateCacheIndex(cacheKey, result.adSegmentsRemoved, result.hasPtsJump);
        
        Log.d(TAG, "Saved cleaned m3u8 to cache, ad segments removed: " + result.adSegmentsRemoved + ", hasPtsJump: " + result.hasPtsJump);
        String httpPlayUrl = M3U8PlaceholderServer.getInstance(mContext).getCleanedM3u8Url(cachedFile);
        Log.d(TAG, "Serving cleaned m3u8 via local http: " + httpPlayUrl);
        Log.d(TAG, "processM3U8Internal completed successfully in " + (System.currentTimeMillis() - methodStartTime) + "ms");
        // 本地 cleaned m3u8，标记 isLocalFile=true 便于播放器回退
        callback.onResult(httpPlayUrl, true, result.adSegmentsRemoved, result.hasPtsJump);

        // 6. 清理旧缓存
        cleanOldCache();

    }
    
    /**
     * 只读检测 HLS 播放列表是否包含 discontinuity，不生成清理文件。
     *
     * @param headers 播放请求头（Referer/Cookie 等），null 时仅使用默认 UA
     */
    public void checkDiscontinuity(String m3u8Url, Map<String, String> headers,
            DiscontinuityCallback callback) {
        mExecutor.execute(() -> {
            try {
                String content = fetchM3U8Content(m3u8Url, headers);
                if (content == null || content.isEmpty()) {
                    callback.onError(new Exception("Failed to fetch m3u8 content"));
                    return;
                }
                String mediaUrl = extractSubM3U8Url(content, m3u8Url);
                if (mediaUrl != null) {
                    content = fetchM3U8Content(mediaUrl, headers);
                    if (content == null || content.isEmpty()) {
                        callback.onError(new Exception("Failed to fetch media playlist"));
                        return;
                    }
                }
                callback.onResult(containsDiscontinuity(content));
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    static boolean containsDiscontinuity(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            if ("#EXT-X-DISCONTINUITY".equals(line.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取m3u8内容（带自定义请求头，过滤 GSY 控制字段）
     */
    private String fetchM3U8Content(String m3u8Url, Map<String, String> headers) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(m3u8Url);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    String key = entry.getKey();
                    // allowCrossProtocolRedirects 是 GSY 控制字段而非 HTTP 头
                    if (key != null && !key.isEmpty()
                            && !"allowCrossProtocolRedirects".equals(key)) {
                        connection.setRequestProperty(key, entry.getValue());
                    }
                }
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP response code: " + responseCode);
                return null;
            }

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();

            // 不记录 manifest 原文：可能包含带签名的分片地址、密钥 URI 等敏感信息
            return sb.toString();

        } catch (Exception e) {
            Log.e(TAG, "fetchM3U8Content error", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * 解析m3u8并移除广告片段
     */
    private M3U8ParseResult parseAndRemoveAds(String content, String baseUrl) {
        M3U8ParseResult result = new M3U8ParseResult();
        
        // 提取基础URL用于转换相对路径
        String baseUrlPath = extractBaseUrlPath(baseUrl);
        Log.d(TAG, "Base URL path: " + baseUrlPath);
        
        // PTS 连续性检查标记
        boolean hasPtsJump = false;
        
        String[] lines = content.split("\n");
        StringBuilder cleaned = new StringBuilder();
        
        List<SegmentInfo> segments = new ArrayList<>();
        List<String> headerLines = new ArrayList<>();
        boolean hasOpeningDiscontinuity = false; // 标记开头是否有DISCONTINUITY
        
        // 解析所有片段
        int currentIndex = 0;
        boolean inHeader = true;
        String currentEncryptionKey = null; // 当前有效的加密密钥
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            
            if (line.startsWith("#EXT-X-")) {
                if (line.startsWith("#EXT-X-TARGETDURATION") || 
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE") ||
                    line.startsWith("#EXT-X-VERSION") ||
                    line.startsWith("#EXT-X-PLAYLIST-TYPE") ||
                    line.startsWith("#EXTM3U")) {
                    headerLines.add(line);
                }
                
                // 处理加密密钥标签
                if (line.startsWith("#EXT-X-KEY:")) {
                    // 提取密钥信息（去掉 #EXT-X-KEY: 前缀）并将 URI 绝对化，避免本地清洗后取 key 失败
                    String rawKeyAttrs = line.substring("#EXT-X-KEY:".length());
                    currentEncryptionKey = rewriteKeyUri(rawKeyAttrs, baseUrlPath);
                    Log.d(TAG, "Found encryption key: " + currentEncryptionKey);
                }

                
                if (line.equals("#EXT-X-DISCONTINUITY")) {
                    // 标记流切换点
                    if (!segments.isEmpty()) {
                        segments.get(segments.size() - 1).isDiscontinuity = true;
                    } else {
                        // 开头DISCONTINUITY，标记下一个片段
                        hasOpeningDiscontinuity = true;
                        inHeader = false;
                    }
                }
            } else if (line.startsWith("#EXTINF:")) {
                // #EXTINF 不是以 #EXT-X- 开头，需要单独处理
                inHeader = false;
                // 解析片段时长
                double duration = parseExtinfDuration(line);
                
                // 获取下一个非空行作为URL
                String segmentUrl = null;
                for (int j = i + 1; j < lines.length; j++) {
                    String nextLine = lines[j].trim();
                    if (!nextLine.isEmpty() && !nextLine.startsWith("#")) {
                        segmentUrl = nextLine;
                        break;
                    } else if (nextLine.startsWith("#EXT-X-DISCONTINUITY")) {
                        // DISCONTINUITY标记
                        break;
                    }
                }
                
                if (segmentUrl != null) {
                    SegmentInfo info = new SegmentInfo();
                    info.index = currentIndex++;
                    info.duration = duration;
                    info.url = segmentUrl;
                    info.lineNumber = i;
                    info.needsDiscontinuity = false; // 初始化
                    info.encryptionKey = currentEncryptionKey; // 保存当前加密密钥
                    segments.add(info);
                }
            }
        }
        
        // 设置needsDiscontinuity标记
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).isDiscontinuity && i + 1 < segments.size()) {
                segments.get(i + 1).needsDiscontinuity = true;
            }
        }
        
        // 如果开头有DISCONTINUITY，第一个片段需要标记
        if (hasOpeningDiscontinuity && !segments.isEmpty()) {
            segments.get(0).needsDiscontinuity = false; // 第一个片段前不需要DISCONTINUITY
        }
        
        // 检测广告片段
        Log.d(TAG, "Total segments parsed: " + segments.size() + ", hasOpeningDiscontinuity=" + hasOpeningDiscontinuity);
        for (int i = 0; i < Math.min(5, segments.size()); i++) {
            Log.d(TAG, "Segment " + i + ": " + segments.get(i).url + ", duration=" + segments.get(i).duration + ", isDiscontinuity=" + segments.get(i).isDiscontinuity);
        }
        
        List<SegmentInfo> adSegments = detectAdSegments(segments, hasOpeningDiscontinuity);
        result.adSegmentsRemoved = adSegments.size();

        Log.d(TAG, "detectAdSegments finished, adSegmentsRemoved=" + result.adSegmentsRemoved);
        
        // PTS 连续性检查：
        // 1. 如果开头就有 DISCONTINUITY，检查第一个片段是否是广告
        //    - 如果第一个片段是广告，这是正常的（广告插入），不需要切换播放器
        //    - 如果第一个片段是正片，检测实际的 PTS 值来判断是否有时间轴问题
        // 2. 只检测正片内部的 PTS 跳变，忽略广告片段
        if (hasOpeningDiscontinuity && segments.size() > 0) {
            // 检查第一个片段是否是广告
            boolean firstSegmentIsAd = segments.get(0).isAd;
            
            if (firstSegmentIsAd) {
                // 第一个片段是广告，这是正常的广告插入，不需要检查 PTS
                Log.d(TAG, "Opening DISCONTINUITY detected, but first segment is ad (normal ad insertion), skip PTS check");
            } else {
                // 第一个片段是正片，检测实际的 PTS 值
                Log.w(TAG, "Opening DISCONTINUITY detected with content segment, checking actual PTS values");
                
                String firstUrl = toAbsoluteUrl(segments.get(0).url, baseUrlPath);
                TsPtsChecker.EncryptionInfo firstEncryption = parseEncryptionInfo(segments.get(0).encryptionKey);
                
                TsPtsChecker.PtsCheckResult firstPtsResult = TsPtsChecker.checkFirstSegmentPts(firstUrl, firstEncryption);
                if (firstPtsResult.success) {
                    if (firstPtsResult.hasPtsJump) {
                        // 第一个片段的 PTS 不从 0 开始，说明有 PTS 偏移问题
                        Log.w(TAG, String.format("First segment PTS is %.2fs (expected ~0s), PTS timeline issue detected", firstPtsResult.beforePts));
                        Log.w(TAG, "This indicates m3u8 timeline and TS PTS are misaligned, will cause seek jump issues");
                        hasPtsJump = true;
                    } else {
                        Log.d(TAG, String.format("First segment PTS is %.2fs, no significant offset detected", firstPtsResult.beforePts));
                    }
                } else {
                    // PTS 检测失败，为了安全起见，假设有问题
                    Log.w(TAG, "Failed to check first segment PTS, assuming PTS timeline issue for safety");
                    hasPtsJump = true;
                }
            }
        }
        
        // 检测正片内部的 PTS 跳变（跳过广告边界）
        for (int i = 0; i < segments.size() - 1; i++) {
            if (segments.get(i).isDiscontinuity) {
                // 跳过广告片段的 DISCONTINUITY
                boolean beforeIsAd = segments.get(i).isAd;
                boolean afterIsAd = segments.get(i + 1).isAd;
                
                if (beforeIsAd || afterIsAd) {
                    Log.d(TAG, "Skipping PTS check at ad boundary (position " + i + 
                          "): beforeIsAd=" + beforeIsAd + ", afterIsAd=" + afterIsAd);
                    continue;
                }
                
                // 只检查正片内部的 DISCONTINUITY
                String urlBefore = toAbsoluteUrl(segments.get(i).url, baseUrlPath);
                String urlAfter = toAbsoluteUrl(segments.get(i + 1).url, baseUrlPath);
                
                // 解析加密信息
                TsPtsChecker.EncryptionInfo encryptionBefore = parseEncryptionInfo(segments.get(i).encryptionKey);
                TsPtsChecker.EncryptionInfo encryptionAfter = parseEncryptionInfo(segments.get(i + 1).encryptionKey);
                
                Log.d(TAG, "Checking PTS at DISCONTINUITY position " + i + " (both segments are content)");
                TsPtsChecker.PtsCheckResult ptsResult = TsPtsChecker.checkPtsContinuity(
                    urlBefore, urlAfter, encryptionBefore, encryptionAfter);
                
                if (ptsResult.success && ptsResult.hasPtsJump) {
                    Log.w(TAG, "PTS jump detected in content at position " + i + ": " + ptsResult.message);
                    hasPtsJump = true;
                    segments.get(i).hasPtsJump = true;
                }
            }
        }
        
        result.hasPtsJump = hasPtsJump;
        
        // 构建清理后的m3u8
        // 添加header
        cleaned.append("#EXTM3U\n");
        cleaned.append("#EXT-X-VERSION:3\n");
        cleaned.append("#EXT-X-TARGETDURATION:8\n");
        
        // 计算广告片段的总时长（用于日志）
        double totalAdDuration = 0;
        for (SegmentInfo segment : segments) {
            if (segment.isAd) {
                totalAdDuration += segment.duration;
            }
        }
        Log.d(TAG, "Total ad duration: " + totalAdDuration + "s, segments: " + adSegments.size());
        
        int leadingAdCount = 0;
        while (leadingAdCount < segments.size() && segments.get(leadingAdCount).isAd) {
            leadingAdCount++;
        }
        int trailingAdStart = segments.size();
        while (trailingAdStart > leadingAdCount && segments.get(trailingAdStart - 1).isAd) {
            trailingAdStart--;
        }

        // 添加所有片段：
        // - 开头广告：直接移除，避免播放器从占位片段启动导致解码失败
        // - 结尾广告：直接移除，避免无意义尾部占位
        // - 中间广告：仍用占位片段替换，尽量保持时间轴
        boolean firstSegment = true;
        String lastEncryptionKey = null; // 跟踪上一个输出的加密密钥，避免重复输出

        String placeholderUrl = null;
        
        Log.d(TAG, "Starting to process " + segments.size() + " segments...");
        for (SegmentInfo segment : segments) {
            if (segment.index < leadingAdCount || segment.index >= trailingAdStart) {
                continue;
            }
            if (firstSegment) {
                firstSegment = false;
            } else if (segment.needsDiscontinuity) {
                cleaned.append("#EXT-X-DISCONTINUITY\n");
                // DISCONTINUITY后需要重新输出加密密钥（如果有）
                lastEncryptionKey = null;
            }
            
            // 转换为绝对URL
            String absoluteUrl = toAbsoluteUrl(segment.url, baseUrlPath);
            
            // 输出加密密钥标签（如果有且与上一个不同）
            if (segment.encryptionKey != null && !segment.encryptionKey.equals(lastEncryptionKey)) {
                cleaned.append("#EXT-X-KEY:").append(segment.encryptionKey).append("\n");
                lastEncryptionKey = segment.encryptionKey;
                Log.d(TAG, "Output encryption key for segment " + segment.index + ": " + segment.encryptionKey);
            }
            
            if (segment.isAd) {
                // 广告片段：保留原始时长，用本地占位TS文件替换
                // 若当前处于加密态，先关闭加密，避免占位片段被误解密
                if (lastEncryptionKey != null) {
                    cleaned.append("#EXT-X-KEY:METHOD=NONE\n");
                    lastEncryptionKey = null;
                }
                if (placeholderUrl == null) {
                    placeholderUrl = getOrCreatePlaceholderTsUrl();
                    Log.d(TAG, "Using placeholder TS: " + placeholderUrl);
                }
                cleaned.append("#EXTINF:").append(String.format("%.6f", segment.duration)).append(",\n");
                cleaned.append(placeholderUrl).append("\n");
            } else {
                // 正常片段
                cleaned.append("#EXTINF:").append(String.format("%.6f", segment.duration)).append(",\n");
                cleaned.append(absoluteUrl).append("\n");
            }

        }
        
        Log.d(TAG, "Finished processing segments, appending ENDLIST...");
        cleaned.append("#EXT-X-ENDLIST\n");
        
        result.cleanedContent = cleaned.toString();
        Log.d(TAG, "parseAndRemoveAds completed, cleaned content length: " + result.cleanedContent.length());
        return result;
    }
    
    /**
     * 检测广告片段
     * 支持：开头广告、中间广告、结尾广告
     */
    private List<SegmentInfo> detectAdSegments(List<SegmentInfo> segments, boolean hasOpeningDiscontinuity) {
        List<SegmentInfo> adSegments = new ArrayList<>();
        
        if (segments.size() < 2) {
            return adSegments;
        }
        
        Log.d(TAG, "detectAdSegments: segments=" + segments.size() + ", hasOpeningDiscontinuity=" + hasOpeningDiscontinuity);
        
        // 仅当检测到 DISCONTINUITY 时才执行路径模式检测，避免误判
        List<Integer> discontinuityPositions = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).isDiscontinuity) {
                discontinuityPositions.add(i);
            }
        }
        boolean hasDiscontinuity = hasOpeningDiscontinuity || !discontinuityPositions.isEmpty();
        if (hasDiscontinuity) {
            // 统计所有路径模式的出现次数
            java.util.Map<String, Integer> pathCounts = new java.util.HashMap<>();
            for (SegmentInfo segment : segments) {
                String pathPattern = extractPathPattern(segment.url);
                pathCounts.put(pathPattern, pathCounts.getOrDefault(pathPattern, 0) + 1);
            }
            
            // 找出出现次数最多的路径模式作为主路径（正片）
            String mainPathPattern = null;
            int mainPathCount = 0;
            for (java.util.Map.Entry<String, Integer> entry : pathCounts.entrySet()) {
                if (entry.getValue() > mainPathCount) {
                    mainPathPattern = entry.getKey();
                    mainPathCount = entry.getValue();
                }
            }
            
            Log.d(TAG, "Path patterns: " + pathCounts.toString());
            Log.d(TAG, "Main path pattern: " + mainPathPattern + " (count=" + mainPathCount + ")");
            
            // 方法1: 按前缀长度分组检测广告
            // 统计各前缀长度的出现次数
            java.util.Map<Integer, Integer> lengthCounts = new java.util.HashMap<>();
            for (SegmentInfo segment : segments) {
                String pathPattern = extractPathPattern(segment.url);
                int len = pathPattern.length();
                lengthCounts.put(len, lengthCounts.getOrDefault(len, 0) + 1);
            }
            
            // 找出出现次数最多的前缀长度作为主长度
            int mainLength = 0;
            int mainLengthCount = 0;
            for (java.util.Map.Entry<Integer, Integer> entry : lengthCounts.entrySet()) {
                if (entry.getValue() > mainLengthCount) {
                    mainLength = entry.getKey();
                    mainLengthCount = entry.getValue();
                }
            }
            
            Log.d(TAG, "Prefix length counts: " + lengthCounts.toString());
            Log.d(TAG, "Main prefix length: " + mainLength + " (count=" + mainLengthCount + ")");
            
            // 标记前缀长度不同于主长度的片段为广告
            if (lengthCounts.size() > 1 && mainLengthCount > segments.size() * 0.3) {
                for (SegmentInfo segment : segments) {
                    String pathPattern = extractPathPattern(segment.url);
                    if (pathPattern.length() != mainLength) {
                        // 检查白名单
                        if (isInWhitelist(segment.url)) {
                            Log.d(TAG, "Segment in whitelist, skipping ad detection: " + segment.url);
                            continue;
                        }
                        segment.isAd = true;
                        adSegments.add(segment);
                        Log.d(TAG, "Ad detected by prefix length at position " + segment.index + 
                              ": " + segment.url + ", length=" + pathPattern.length());
                    }
                }
            }
            
            // 方法2: 如果有多个路径模式，标记非主路径的片段为广告
            if (adSegments.isEmpty() && pathCounts.size() > 1 && mainPathCount > segments.size() * 0.1) {
                for (SegmentInfo segment : segments) {
                    String pathPattern = extractPathPattern(segment.url);
                    if (!pathPattern.equals(mainPathPattern)) {
                        // 检查白名单
                        if (isInWhitelist(segment.url)) {
                            Log.d(TAG, "Segment in whitelist, skipping ad detection: " + segment.url);
                            continue;
                        }
                        segment.isAd = true;
                        adSegments.add(segment);
                        Log.d(TAG, "Ad detected by path pattern at position " + segment.index + ": " + segment.url);
                    }
                }
            }
        } else {
            Log.d(TAG, "No DISCONTINUITY markers, skip path pattern detection");
        }
        
        // 方法 2: 检测 DISCONTINUITY 标记的广告片段组（开头广告）
        // 即使方法 1 检测到了广告，也要检测开头广告
        {
            // 如果开头有DISCONTINUITY，检测开头广告
            if (hasOpeningDiscontinuity && !segments.isEmpty()) {
                // 开头广告：第一个片段到第一个DISCONTINUITY标记的片段
                int firstDiscontinuity = discontinuityPositions.isEmpty() ? segments.size() - 1 : discontinuityPositions.get(0);
                double totalDuration = 0;
                for (int j = 0; j <= firstDiscontinuity; j++) {
                    totalDuration += segments.get(j).duration;
                }
                Log.d(TAG, "Opening ad check: totalDuration=" + totalDuration + "s, firstDiscontinuity=" + firstDiscontinuity);
                // 如果开头片段组时长<120秒，可能是开头广告
                if (totalDuration < 120 && totalDuration > 0) {
                    for (int j = 0; j <= firstDiscontinuity; j++) {
                        // 检查白名单
                        if (isInWhitelist(segments.get(j).url)) {
                            Log.d(TAG, "Segment in whitelist, skipping opening ad detection: " + segments.get(j).url);
                            continue;
                        }
                        // 检查是否已经被标记为广告
                        if (segments.get(j).isAd) {
                            continue;
                        }
                        segments.get(j).isAd = true;
                        adSegments.add(segments.get(j));
                        Log.d(TAG, "Opening ad detected at position " + j + ": " + segments.get(j).url);
                    }
                }
            }
        }
        
        // 方法 3: 检测中间广告（仅在方法 1 和 2 未检测到广告时执行，避免误判）
        if (adSegments.isEmpty()) {
            Log.d(TAG, "Method 3: Found " + discontinuityPositions.size() + " DISCONTINUITY markers");
            
            // 防止过度检测：如果 DISCONTINUITY 太多，说明这不是普通视频流，跳过中间广告检测
            if (discontinuityPositions.size() > 50) {
                Log.w(TAG, "Too many DISCONTINUITY markers (" + discontinuityPositions.size() + "), this appears to be a live stream or special format, skipping mid-roll ad detection");
            } else if (discontinuityPositions.size() >= 1) {
                // 检测中间广告（两个DISCONTINUITY之间的片段）
                for (int d = 0; d < discontinuityPositions.size() - 1; d++) {
                    int startPos = discontinuityPositions.get(d) + 1;
                    int endPos = discontinuityPositions.get(d + 1);
                    
                    if (endPos > startPos) {
                        double totalDuration = 0;
                        for (int j = startPos; j <= endPos; j++) {
                            totalDuration += segments.get(j).duration;
                        }
                        // 减少日志输出，只输出检测到广告时的汇总信息
                        if (totalDuration < 60 && totalDuration > 5) {
                            int adCountInGroup = 0;
                            for (int j = startPos; j <= endPos; j++) {
                                // 检查白名单
                                if (isInWhitelist(segments.get(j).url)) {
                                    continue;
                                }
                                // 检查是否已经被标记为广告
                                if (segments.get(j).isAd) {
                                    continue;
                                }
                                segments.get(j).isAd = true;
                                adSegments.add(segments.get(j));
                                adCountInGroup++;
                            }
                            // 只在检测到广告时输出日志
                            if (adCountInGroup > 0) {
                                Log.d(TAG, "Mid-roll ad detected: positions " + startPos + "-" + endPos + ", count=" + adCountInGroup + ", duration=" + totalDuration + "s");
                            }
                        }
                    }
                }
                
                // 检测结尾广告（最后一个DISCONTINUITY之后的片段）
                int lastDiscontinuity = discontinuityPositions.get(discontinuityPositions.size() - 1);
                if (lastDiscontinuity > segments.size() * 2 / 3) {
                    double totalDuration = 0;
                    for (int j = lastDiscontinuity + 1; j < segments.size(); j++) {
                        totalDuration += segments.get(j).duration;
                    }
                    // 如果结尾片段组时长<60秒，可能是结尾广告
                    if (totalDuration < 60 && totalDuration > 0) {
                        for (int j = lastDiscontinuity + 1; j < segments.size(); j++) {
                            // 检查白名单
                            if (isInWhitelist(segments.get(j).url)) {
                                Log.d(TAG, "Segment in whitelist, skipping post-roll ad detection: " + segments.get(j).url);
                                continue;
                            }
                            // 检查是否已经被标记为广告
                            if (segments.get(j).isAd) {
                                continue;
                            }
                            segments.get(j).isAd = true;
                            adSegments.add(segments.get(j));
                            Log.d(TAG, "Post-roll ad detected at position " + j);
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "Method 3: Skipping DISCONTINUITY-based ad detection since other methods already found " + adSegments.size() + " ad segments");
        }
        
        // 方法3: 检测文件名数字序列突变（广告片段的数字通常突变）
        if (adSegments.isEmpty()) {
            // 提取所有片段的数字序列
            List<Long> segmentNumbers = new ArrayList<>();
            for (SegmentInfo segment : segments) {
                long num = extractSegmentNumber(segment.url);
                segmentNumbers.add(num);
            }
            
            // 检测数字突变位置
            List<int[]> jumpRanges = new ArrayList<>();  // [start, end] 突变范围
            int jumpStart = -1;
            
            for (int i = 1; i < segmentNumbers.size(); i++) {
                long prev = segmentNumbers.get(i - 1);
                long curr = segmentNumbers.get(i);
                
                // 如果数字跳跃超过1000，认为是突变
                if (Math.abs(curr - prev) > 1000) {
                    if (jumpStart == -1) {
                        jumpStart = i;
                    }
                } else {
                    // 序列恢复正常，标记突变范围
                    if (jumpStart != -1) {
                        jumpRanges.add(new int[]{jumpStart, i - 1});
                        jumpStart = -1;
                    }
                }
            }
            
            // 处理末尾的突变
            if (jumpStart != -1) {
                jumpRanges.add(new int[]{jumpStart, segmentNumbers.size() - 1});
            }
            
            // 标记突变范围内的片段为广告
            for (int[] range : jumpRanges) {
                int start = range[0];
                int end = range[1];
                
                // 计算突变片段组的总时长
                double totalDuration = 0;
                for (int j = start; j <= end; j++) {
                    totalDuration += segments.get(j).duration;
                }
                
                // 如果突变片段组时长<120秒，很可能是广告
                if (totalDuration < 120 && totalDuration > 0) {
                    for (int j = start; j <= end; j++) {
                        // 检查白名单
                        if (isInWhitelist(segments.get(j).url)) {
                            Log.d(TAG, "Segment in whitelist, skipping number jump ad detection: " + segments.get(j).url);
                            continue;
                        }
                        segments.get(j).isAd = true;
                        adSegments.add(segments.get(j));
                        Log.d(TAG, "Number jump ad detected at position " + j + 
                              ", number=" + segmentNumbers.get(j) + 
                              ", duration=" + segments.get(j).duration);
                    }
                }
            }
        }
        
        // 方法4: 检测异常短片段组（广告通常时长较短且连续）
        if (adSegments.isEmpty()) {
            int consecutiveShortCount = 0;
            int shortStartIndex = -1;
            
            for (int i = 0; i < segments.size(); i++) {
                if (segments.get(i).duration < 1.0) {
                    if (shortStartIndex == -1) {
                        shortStartIndex = i;
                    }
                    consecutiveShortCount++;
                } else {
                    // 如果连续短片段数>=3且总时长<30秒，可能是广告
                    if (consecutiveShortCount >= 3) {
                        double totalDuration = 0;
                        for (int j = shortStartIndex; j < i; j++) {
                            totalDuration += segments.get(j).duration;
                        }
                        if (totalDuration < 30) {
                            for (int j = shortStartIndex; j < i; j++) {
                                // 检查白名单
                                if (isInWhitelist(segments.get(j).url)) {
                                    Log.d(TAG, "Segment in whitelist, skipping short segment ad detection: " + segments.get(j).url);
                                    continue;
                                }
                                segments.get(j).isAd = true;
                                adSegments.add(segments.get(j));
                                Log.d(TAG, "Short segment ad detected at position " + j);
                            }
                        }
                    }
                    consecutiveShortCount = 0;
                    shortStartIndex = -1;
                }
            }
        }
        
        return adSegments;
    }
    
    /**
     * 提取路径模式（用于识别不同来源的片段）
     */
    private String extractPathPattern(String url) {
        if (url == null) return "";
        
        try {
            // 如果是相对路径（只有文件名），提取文件名前缀
            // 例如: e57566bf8e4000073.ts -> e57566bf8e4000
            //       e57566bf8e40715435.ts -> e57566bf8e40 (不同前缀)
            if (!url.contains("/")) {
                int dotPos = url.lastIndexOf('.');
                String filename = dotPos > 0 ? url.substring(0, dotPos) : url;
                if (filename.matches("\\d+")) {
                    return "NUMERIC_SEGMENT";
                }
                
                // 提取文件名的数字前缀部分（去掉末尾的数字序列）
                // e57566bf8e4000073 -> e57566bf8e4000
                int lastDigitStart = -1;
                for (int i = filename.length() - 1; i >= 0; i--) {
                    if (Character.isDigit(filename.charAt(i))) {
                        if (lastDigitStart == -1) {
                            lastDigitStart = i;
                        }
                    } else {
                        break;
                    }
                }
                
                if (lastDigitStart > 0) {
                    return filename.substring(0, lastDigitStart);
                }
                return filename;
            }
            
            // 有目录路径的情况，提取第一级目录
            int slashCount = 0;
            int secondSlash = -1;
            
            for (int i = 0; i < url.length(); i++) {
                if (url.charAt(i) == '/') {
                    slashCount++;
                    if (slashCount == 2) {
                        secondSlash = i;
                    } else if (slashCount == 3) {
                        return url.substring(secondSlash, i + 1);
                    }
                }
            }
            
            return url;
        } catch (Exception e) {
            return url;
        }
    }
    
    /**
     * 检测是否为Master Playlist并提取子播放列表URL
     * Master Playlist包含#EXT-X-STREAM-INF标签，指向子播放列表
     */
    private String extractSubM3U8Url(String content, String masterUrl) {
        if (content == null || content.isEmpty()) return null;
        
        try {
            String[] lines = content.split("\n");
            String subUrl = null;
            int maxBandwidth = 0;
            
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                
                // 检测#EXT-X-STREAM-INF标签（Master Playlist特征）
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    // 提取BANDWIDTH
                    int bandwidth = 0;
                    String[] parts = line.split(",");
                    for (String part : parts) {
                        if (part.startsWith("BANDWIDTH=")) {
                            bandwidth = Integer.parseInt(part.substring(10));
                            break;
                        }
                    }
                    
                    // 获取下一行的子播放列表URL
                    if (i + 1 < lines.length) {
                        String nextLine = lines[i + 1].trim();
                        if (!nextLine.isEmpty() && !nextLine.startsWith("#")) {
                            // 选择最高带宽的流
                            if (bandwidth > maxBandwidth) {
                                maxBandwidth = bandwidth;
                                subUrl = nextLine;
                            }
                        }
                    }
                }
            }
            
            if (subUrl != null) {
                // 转换为绝对URL
                String baseUrlPath = extractBaseUrlPath(masterUrl);
                return toAbsoluteUrl(subUrl, baseUrlPath);
            }
        } catch (Exception e) {
            Log.e(TAG, "extractSubM3U8Url error", e);
        }
        return null;
    }
    
    /**
     * 提取片段文件名中的数字序列
     * 例如: e57566bf8e4000073.ts -> 73, e57566bf8e40715435.ts -> 715435
     */
    private long extractSegmentNumber(String url) {
        if (url == null) return -1;
        
        try {
            // 提取文件名（不含扩展名）
            int lastSlash = url.lastIndexOf('/');
            String filename = url.substring(lastSlash + 1);
            int dotPos = filename.lastIndexOf('.');
            if (dotPos > 0) {
                filename = filename.substring(0, dotPos);
            }
            
            // 提取末尾的数字部分
            StringBuilder numStr = new StringBuilder();
            for (int i = filename.length() - 1; i >= 0; i--) {
                char c = filename.charAt(i);
                if (Character.isDigit(c)) {
                    numStr.insert(0, c);
                } else {
                    break;  // 遇到非数字字符停止
                }
            }
            
            if (numStr.length() > 0) {
                return Long.parseLong(numStr.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "extractSegmentNumber error: " + url, e);
        }
        return -1;
    }
    
    /**
     * 解析#EXTINF行获取时长
     */
    private double parseExtinfDuration(String line) {
        try {
            // #EXTINF:2.000000,
            int colon = line.indexOf(':');
            int comma = line.indexOf(',');
            if (colon > 0 && comma > colon) {
                return Double.parseDouble(line.substring(colon + 1, comma));
            }
        } catch (Exception e) {
            Log.e(TAG, "parseExtinfDuration error: " + line, e);
        }
        return 0;
    }
    
    /**
     * 提取m3u8的基础URL路径（用于转换相对路径）
     * 例如: https://example.com/path/to/index.m3u8 -> https://example.com/path/to/
     */
    private String extractBaseUrlPath(String m3u8Url) {
        if (m3u8Url == null) return "";
        
        int lastSlash = m3u8Url.lastIndexOf('/');
        if (lastSlash > 0) {
            return m3u8Url.substring(0, lastSlash + 1);
        }
        return m3u8Url;
    }
    
    /**
     * 将相对URL转换为绝对URL
     */
    private String toAbsoluteUrl(String segmentUrl, String baseUrlPath) {
        if (segmentUrl == null) return "";
        
        // 已经是绝对URL
        if (segmentUrl.startsWith("http://") || segmentUrl.startsWith("https://")) {
            return segmentUrl;
        }
        
        // 相对URL，拼接基础路径
        if (segmentUrl.startsWith("/")) {
            // 绝对路径，需要提取域名
            try {
                java.net.URL url = new java.net.URL(baseUrlPath);
                String domain = url.getProtocol() + "://" + url.getHost();
                if (url.getPort() != -1) {
                    domain += ":" + url.getPort();
                }
                return domain + segmentUrl;
            } catch (Exception e) {
                return baseUrlPath + segmentUrl.substring(1);
            }
        } else {
            // 相对路径
            return baseUrlPath + segmentUrl;
        }
    }
    
    /**
     * 将 #EXT-X-KEY 行中的 URI 绝对化
     */
    private String rewriteKeyUri(String keyAttributes, String baseUrlPath) {
        if (keyAttributes == null) return null;
        try {
            String marker = "URI=\"";
            int idx = keyAttributes.indexOf(marker);
            if (idx < 0) {
                return keyAttributes;
            }
            int start = idx + marker.length();
            int end = keyAttributes.indexOf('"', start);
            if (end < 0) {
                return keyAttributes;
            }
            String uri = keyAttributes.substring(start, end);
            String absoluteUri = toAbsoluteUrl(uri, baseUrlPath);
            return keyAttributes.substring(0, start) + absoluteUri + keyAttributes.substring(end);
        } catch (Exception e) {
            Log.e(TAG, "rewriteKeyUri error", e);
            return keyAttributes;
        }
    }
    
    /**
     * 解析加密信息字符串为 EncryptionInfo 对象
     * 
     * @param encryptionKey 加密密钥字符串（例如：METHOD=AES-128,URI="https://...",IV=0x...）
     * @return EncryptionInfo 对象，如果没有加密返回 null
     */
    private TsPtsChecker.EncryptionInfo parseEncryptionInfo(String encryptionKey) {
        if (encryptionKey == null || encryptionKey.isEmpty()) {
            return null;
        }
        
        try {
            TsPtsChecker.EncryptionInfo info = new TsPtsChecker.EncryptionInfo();
            
            // 解析 METHOD
            String methodMarker = "METHOD=";
            int methodIdx = encryptionKey.indexOf(methodMarker);
            if (methodIdx >= 0) {
                int methodStart = methodIdx + methodMarker.length();
                int methodEnd = encryptionKey.indexOf(',', methodStart);
                if (methodEnd < 0) methodEnd = encryptionKey.length();
                info.method = encryptionKey.substring(methodStart, methodEnd).trim();
            }
            
            // 如果是 NONE，直接返回
            if ("NONE".equalsIgnoreCase(info.method)) {
                return null;
            }
            
            // 解析 URI
            String uriMarker = "URI=\"";
            int uriIdx = encryptionKey.indexOf(uriMarker);
            if (uriIdx >= 0) {
                int uriStart = uriIdx + uriMarker.length();
                int uriEnd = encryptionKey.indexOf('"', uriStart);
                if (uriEnd > uriStart) {
                    info.keyUri = encryptionKey.substring(uriStart, uriEnd);
                }
            }
            
            // 解析 IV
            String ivMarker = "IV=";
            int ivIdx = encryptionKey.indexOf(ivMarker);
            if (ivIdx >= 0) {
                int ivStart = ivIdx + ivMarker.length();
                int ivEnd = encryptionKey.indexOf(',', ivStart);
                if (ivEnd < 0) ivEnd = encryptionKey.length();
                info.iv = encryptionKey.substring(ivStart, ivEnd).trim();
            }
            
            return info;
            
        } catch (Exception e) {
            Log.e(TAG, "parseEncryptionInfo error: " + encryptionKey, e);
            return null;
        }
    }
    
    /**
     * 生成缓存key
     */
    private String getCacheKey(String url) {

        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(("v2:" + url).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(url.hashCode());
        }
    }
    
    /**
     * 更新缓存索引
     */
    private void updateCacheIndex(String cacheKey, int adCount, boolean hasPtsJump) {
        try {
            File indexFile = new File(mCacheDir, CACHE_INDEX_FILE);
            FileOutputStream fos = new FileOutputStream(indexFile, true);
            String entry = cacheKey + "|" + adCount + "|" + (hasPtsJump ? "1" : "0") + "\n";
            fos.write(entry.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "updateCacheIndex error", e);
        }
    }
    
    /**
     * 缓存索引条目
     */
    private static class CacheIndexEntry {
        int adCount;
        boolean hasPtsJump;
    }
    
    /**
     * 从缓存索引获取条目
     */
    private CacheIndexEntry getCacheIndexEntry(String cacheKey) {
        try {
            File indexFile = new File(mCacheDir, CACHE_INDEX_FILE);
            if (!indexFile.exists()) return null;
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(indexFile), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length >= 2 && parts[0].equals(cacheKey)) {
                    CacheIndexEntry entry = new CacheIndexEntry();
                    entry.adCount = Integer.parseInt(parts[1]);
                    entry.hasPtsJump = parts.length >= 3 && "1".equals(parts[2]);
                    reader.close();
                    return entry;
                }
            }
            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "getCacheIndexEntry error", e);
        }
        return null;
    }
    
    /**
     * 从缓存索引获取广告片段数（兼容旧版本）
     */
    private int getAdCountFromCacheIndex(String cacheKey) {
        CacheIndexEntry entry = getCacheIndexEntry(cacheKey);
        return entry != null ? entry.adCount : 0;
    }
    
    /**
     * 清理旧缓存（保留最近100个文件）
     */
    private void cleanOldCache() {
        File[] files = mCacheDir.listFiles();
        if (files == null || files.length <= 100) return;
        
        // 按修改时间排序，删除最旧的
        java.util.Arrays.sort(files, (a, b) -> 
            Long.compare(a.lastModified(), b.lastModified()));
        
        // 获取当前正在使用的 cleaned m3u8 文件路径（从 server 获取）
        String currentCleanedPath = M3U8PlaceholderServer.getInstance(mContext).getCurrentCleanedM3u8Path();
        
        int toDelete = files.length - 100;
        int deleted = 0;
        for (int i = 0; i < files.length && deleted < toDelete; i++) {
            String name = files[i].getName();
            // 保护：索引文件、placeholder.ts、当前正在播放的 cleaned m3u8
            if (!name.equals(CACHE_INDEX_FILE) && 
                !name.equals("placeholder.ts") &&
                !(currentCleanedPath != null && files[i].getAbsolutePath().equals(currentCleanedPath))) {
                files[i].delete();
                deleted++;
            }
        }
        Log.d(TAG, "Cleaned " + deleted + " old cache files");
    }
    
    /**
     * 清除所有缓存
     */
    public void clearCache() {
        File[] files = mCacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        Log.d(TAG, "Cache cleared");
    }
    
    /**
     * 清除特定URL的缓存
     * @param originalUrl 原始m3u8 URL
     */
    public void clearCacheForUrl(String originalUrl) {
        if (originalUrl == null) return;
        
        String cacheKey = getCacheKey(originalUrl);
        File cachedFile = new File(mCacheDir, cacheKey + ".m3u8");
        if (cachedFile.exists()) {
            boolean deleted = cachedFile.delete();
            Log.d(TAG, "Cleared cache for URL: " + originalUrl + ", deleted=" + deleted);
        }
        
        // 从索引文件中移除
        removeFromCacheIndex(cacheKey);
    }
    
    /**
     * 从缓存索引中移除
     */
    private void removeFromCacheIndex(String cacheKey) {
        try {
            File indexFile = new File(mCacheDir, CACHE_INDEX_FILE);
            if (!indexFile.exists()) return;
            
            // 读取所有行
            java.util.List<String> lines = new java.util.ArrayList<>();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(indexFile), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length == 2 && !parts[0].equals(cacheKey)) {
                    lines.add(line);
                }
            }
            reader.close();
            
            // 重写文件
            PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(indexFile), "UTF-8"));
            for (String l : lines) {
                writer.println(l);
            }
            writer.close();
        } catch (Exception e) {
            Log.e(TAG, "removeFromCacheIndex error", e);
        }
    }
    
    /**
     * 创建占位TS文件（空白视频片段）
     * 用于替换广告片段，保持时间轴不变
     */
    private String createPlaceholderTs() {
        try {
            // 占位TS文件路径
            File placeholderFile = new File(mCacheDir, "placeholder.ts");
            
            // 如果已存在，直接返回
            if (placeholderFile.exists()) {
                return M3U8PlaceholderServer.getInstance(mContext).getPlaceholderUrl();
            }
            
            // 创建一个最小的空白TS文件
            // TS文件最小结构：PAT + PMT + 空白PES包
            byte[] minimalTs = createMinimalTsContent();
            
            FileOutputStream fos = new FileOutputStream(placeholderFile);
            fos.write(minimalTs);
            fos.close();
            
            Log.d(TAG, "Created placeholder TS file: " + placeholderFile.getAbsolutePath());
            return M3U8PlaceholderServer.getInstance(mContext).getPlaceholderUrl();
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create placeholder TS", e);
            throw new RuntimeException("Failed to create placeholder TS", e);
        }
    }

    private String getOrCreatePlaceholderTsUrl() {
        if (mPlaceholderTsUrl != null && !mPlaceholderTsUrl.isEmpty()) {
            return mPlaceholderTsUrl;
        }
        synchronized (this) {
            if (mPlaceholderTsUrl != null && !mPlaceholderTsUrl.isEmpty()) {
                return mPlaceholderTsUrl;
            }
            mPlaceholderTsUrl = createPlaceholderTs();
            return mPlaceholderTsUrl;
        }
    }
    
    /**
     * 创建最小的TS文件内容
     * 包含PAT、PMT和一个空白视频PES包
     */
    private byte[] createMinimalTsContent() {
        // 最小的有效TS文件：188字节的TS包
        // 这里创建一个包含PAT的TS包
        byte[] tsPacket = new byte[188];
        
        // TS包同步字节
        tsPacket[0] = 0x47; // 同步字节
        
        // PID 0x0000 (PAT)
        tsPacket[1] = 0x40; // TEI=0, PUSI=1, PID高5位=0
        tsPacket[2] = 0x00; // PID低8位=0
        tsPacket[3] = 0x10; // CC=0, 适配字段=0, 负载=1
        
        // PAT表内容（简化版）
        tsPacket[4] = 0x00; // pointer field
        tsPacket[5] = 0x00; // table_id
        tsPacket[6] = (byte) 0xB0; // section_syntax_indicator
        tsPacket[7] = 0x0D; // section_length
        tsPacket[8] = 0x00; tsPacket[9] = 0x01; // transport_stream_id
        tsPacket[10] = (byte) 0xC1; // version_number
        tsPacket[11] = 0x00; // section_number
        tsPacket[12] = 0x00; // last_section_number
        // PMT PID = 0x1000
        tsPacket[13] = 0x00; tsPacket[14] = 0x01; // program_number
        tsPacket[15] = (byte) 0xE1; tsPacket[16] = 0x00; // PMT PID
        
        // 填充剩余字节
        for (int i = 17; i < 188; i++) {
            tsPacket[i] = (byte) 0xFF;
        }
        
        return tsPacket;
    }
    
    /**
     * 检查URL是否是HTTP的m3u8
     */
    public static boolean isHttpM3U8(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        
        // 允许带参数的 m3u8 URL，例如 xxx.m3u8?token=xxx 或者虽然没有 .m3u8 后缀但可能是 M3U8 流的情况
        boolean isHttp = lower.startsWith("http://") || lower.startsWith("https://");
        boolean hasM3u8Keyword = lower.contains(".m3u8") || lower.contains("m3u8");
        
        Log.d(TAG, "isHttpM3U8 check for url: " + url + " -> " + (isHttp && hasM3u8Keyword));
        return isHttp && hasM3u8Keyword;
    }
    
    /**
     * 释放资源
     */
    public void release() {
        mExecutor.shutdown();
    }
    
    // 内部类：片段信息
    private static class SegmentInfo {
        int index;
        double duration;
        String url;
        int lineNumber;
        boolean isDiscontinuity;  // 此片段后是否有DISCONTINUITY
        boolean isAd;             // 是否是广告片段
        boolean needsDiscontinuity; // 输出时是否需要DISCONTINUITY标记
        String encryptionKey;     // 加密密钥信息（#EXT-X-KEY标签内容，如：METHOD=AES-128,URI="...",IV=...）
        boolean hasPtsJump;       // 此 DISCONTINUITY 位置是否有 PTS 跳变
    }
    
    // 内部类：解析结果
    private static class M3U8ParseResult {
        String cleanedContent;
        int adSegmentsRemoved;
        boolean hasPtsJump;       // 是否检测到 PTS 跳变
    }
}
