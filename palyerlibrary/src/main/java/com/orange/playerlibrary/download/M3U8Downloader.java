package com.orange.playerlibrary.download;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M3U8 视频下载器
 * 支持下载 HLS 流媒体并合并为 MP4 文件
 */
public class M3U8Downloader {
    
    private static final String TAG = "M3U8Downloader";
    
    private Context mContext;
    private ExecutorService mExecutor;
    private Handler mMainHandler;
    
    public interface DownloadCallback {
        void onProgress(int progress, String message);
        void onSuccess(String filePath);
        void onError(String error);
    }

    /** HLS 分片（含时长与在时间轴上的累积起点） */
    public static class Segment {
        public final String url;
        /** #EXTINF 时长（毫秒）；清单未声明时为 0 */
        public final long durationMs;
        /** 该分片在整条流时间轴上的起点（毫秒） */
        public final long startMs;

        public Segment(String url, long durationMs, long startMs) {
            this.url = url;
            this.durationMs = durationMs;
            this.startMs = startMs;
        }
    }

    public M3U8Downloader(Context context) {
        mContext = context;
        mExecutor = Executors.newFixedThreadPool(3);
        mMainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 开始下载 M3U8 视频
     */
    public void download(String m3u8Url, String title, DownloadCallback callback) {
        mExecutor.execute(() -> {
            try {
                postProgress(callback, 0, "正在解析 M3U8...");
                
                // 1. 下载并解析 M3U8 文件
                List<String> tsUrls = parseM3U8(m3u8Url);
                if (tsUrls.isEmpty()) {
                    postError(callback, "M3U8 文件解析失败");
                    return;
                }
                
                Log.d(TAG, "Found " + tsUrls.size() + " TS segments");
                postProgress(callback, 5, "找到 " + tsUrls.size() + " 个视频分片");
                
                // 2. 创建临时目录
                File tempDir = new File(mContext.getCacheDir(), "m3u8_temp_" + System.currentTimeMillis());
                if (!tempDir.exists()) {
                    tempDir.mkdirs();
                }
                
                // 3. 下载所有 TS 分片
                List<File> tsFiles = new ArrayList<>();
                AtomicInteger downloadedCount = new AtomicInteger(0);
                
                for (int i = 0; i < tsUrls.size(); i++) {
                    String tsUrl = tsUrls.get(i);
                    File tsFile = new File(tempDir, "segment_" + i + ".ts");
                    
                    if (downloadTsSegment(tsUrl, tsFile)) {
                        tsFiles.add(tsFile);
                        int count = downloadedCount.incrementAndGet();
                        int progress = 5 + (count * 85 / tsUrls.size());
                        postProgress(callback, progress, "下载分片 " + count + "/" + tsUrls.size());
                    } else {
                        Log.e(TAG, "Failed to download segment: " + tsUrl);
                    }
                }
                
                if (tsFiles.isEmpty()) {
                    postError(callback, "所有分片下载失败");
                    deleteTempDir(tempDir);
                    return;
                }
                
                postProgress(callback, 90, "正在合并视频...");
                
                // 4. 合并 TS 文件到应用私有目录（避免权限问题）
                File outputDir = new File(mContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "orangeplayer");
                if (!outputDir.exists()) {
                    outputDir.mkdirs();
                }
                
                String fileName = generateFileName(title);
                File outputFile = new File(outputDir, fileName);
                
                // 如果文件已存在，添加时间戳避免冲突
                if (outputFile.exists()) {
                    String nameWithoutExt = fileName.substring(0, fileName.lastIndexOf('.'));
                    String ext = fileName.substring(fileName.lastIndexOf('.'));
                    fileName = nameWithoutExt + "_" + System.currentTimeMillis() + ext;
                    outputFile = new File(outputDir, fileName);
                }
                
                Log.d(TAG, "Output file: " + outputFile.getAbsolutePath());
                
                if (mergeTsFiles(tsFiles, outputFile)) {
                    postProgress(callback, 100, "下载完成");
                    postSuccess(callback, outputFile.getAbsolutePath());
                } else {
                    postError(callback, "视频合并失败");
                }
                
                // 5. 清理临时文件
                deleteTempDir(tempDir);
                
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                postError(callback, "下载失败: " + e.getMessage());
            }
        });
    }
    
    /**
     * 解析 M3U8 文件，获取所有 TS 分片 URL。
     * 支持 master（多码率变体）清单：遇 #EXT-X-STREAM-INF 自动选最低码率的
     * 视频变体递归解析；普通单层清单直接收集 TS 分片。
     */
    private List<String> parseM3U8(String m3u8Url) throws Exception {
        List<String> tsUrls = new ArrayList<>();
        for (Segment s : parseSegments(m3u8Url)) {
            tsUrls.add(s.url);
        }
        return tsUrls;
    }

    /**
     * 解析 M3U8 为带时长的分片列表（供按区间定位/复用播放器缓存）。
     * master（多码率变体）清单自动选最低码率变体递归——对 ASR 常命中纯音频变体。
     * 分片 startMs 为按 #EXTINF 累积的时间轴起点。
     */
    public List<Segment> parseSegments(String m3u8Url) throws Exception {
        String content = fetchUrl(m3u8Url);
        if (content == null || content.isEmpty()) {
            throw new Exception("M3U8 清单为空");
        }
        String baseUrl = getBaseUrl(m3u8Url);

        // master 清单：包含变体流声明 → 选最低码率视频变体递归
        if (content.contains("#EXT-X-STREAM-INF:")) {
            String chosenVariant = null;
            String chosenBandwidth = null;
            BufferedReader reader = new BufferedReader(new StringReader(content));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    String bw = null;
                    int idx = line.indexOf("BANDWIDTH=");
                    if (idx >= 0) {
                        int end = line.indexOf(',', idx);
                        bw = end > idx ? line.substring(idx + 10, end).trim()
                                : line.substring(idx + 10).trim();
                    }
                    String variantUrl = reader.readLine();
                    if (variantUrl != null) {
                        variantUrl = variantUrl.trim();
                        if (chosenVariant == null || (bw != null && (chosenBandwidth == null
                                || Long.parseLong(bw) < Long.parseLong(chosenBandwidth)))) {
                            chosenVariant = variantUrl;
                            chosenBandwidth = bw;
                        }
                    }
                }
            }
            if (chosenVariant != null) {
                String abs = chosenVariant.startsWith("http")
                        ? chosenVariant : baseUrl + chosenVariant;
                Log.d(TAG, "Master 清单选变体: " + abs + " (bw=" + chosenBandwidth + ")");
                return parseSegments(abs);   // 递归解析所选变体
            }
            throw new Exception("Master 清单无可用变体");
        }

        // 普通清单：收集分片 + EXTINF 时长
        List<Segment> segments = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new StringReader(content));
        String line;
        long pendingDurationMs = -1;
        long accMs = 0;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#EXTINF:")) {
                int idx = line.indexOf(':');
                int comma = line.indexOf(',', idx);
                String num = comma > idx ? line.substring(idx + 1, comma) : line.substring(idx + 1);
                try {
                    pendingDurationMs = (long) (Double.parseDouble(num.trim()) * 1000);
                } catch (NumberFormatException ignored) {
                    pendingDurationMs = -1;
                }
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String tsUrl = (line.startsWith("http://") || line.startsWith("https://"))
                    ? line : baseUrl + line;
            long dur = pendingDurationMs > 0 ? pendingDurationMs : 0;
            segments.add(new Segment(tsUrl, dur, accMs));
            accMs += dur;
            pendingDurationMs = -1;
        }
        return segments;
    }

    /** 下载 URL 文本内容（跟随重定向） */
    private String fetchUrl(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setInstanceFollowRedirects(true);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
    
    /**
     * 下载单个 TS 分片
     */
    private boolean downloadTsSegment(String tsUrl, File outputFile) {
        try {
            URL url = new URL(tsUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(outputFile)) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                
                return true;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to download TS segment: " + tsUrl, e);
            return false;
        }
    }
    
    /**
     * 合并所有 TS 文件（简单拼接，保存为 TS 格式）
     */
    private boolean mergeTsFiles(List<File> tsFiles, File outputFile) {
        FileOutputStream out = null;
        try {
            // 如果输出文件已存在，先删除
            if (outputFile.exists()) {
                Log.d(TAG, "Output file exists, deleting: " + outputFile.getAbsolutePath());
                boolean deleted = outputFile.delete();
                if (!deleted) {
                    Log.w(TAG, "Failed to delete existing file");
                }
                // 等待一下确保文件系统完成删除
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
            
            // 确保父目录存在
            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                boolean created = parentDir.mkdirs();
                Log.d(TAG, "Parent directory created: " + created);
            }
            
            Log.d(TAG, "Creating output file: " + outputFile.getAbsolutePath());
            out = new FileOutputStream(outputFile);
            byte[] buffer = new byte[8192];
            
            int mergedCount = 0;
            for (File tsFile : tsFiles) {
                if (!tsFile.exists()) {
                    Log.w(TAG, "TS file not found: " + tsFile.getAbsolutePath());
                    continue;
                }
                
                InputStream in = null;
                try {
                    in = new java.io.FileInputStream(tsFile);
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    mergedCount++;
                } catch (Exception e) {
                    Log.e(TAG, "Error reading TS file: " + tsFile.getName(), e);
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (Exception e) {
                            // Ignore
                        }
                    }
                }
            }
            
            Log.d(TAG, "Merged " + mergedCount + " TS files successfully");
            return mergedCount > 0;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to merge TS files", e);
            return false;
        } finally {
            if (out != null) {
                try {
                    out.flush();
                    out.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing output stream", e);
                }
            }
        }
    }
    
    /**
     * 获取基础 URL（用于拼接相对路径）
     */
    private String getBaseUrl(String m3u8Url) {
        int lastSlash = m3u8Url.lastIndexOf('/');
        if (lastSlash != -1) {
            return m3u8Url.substring(0, lastSlash + 1);
        }
        return m3u8Url;
    }
    
    /**
     * 生成文件名
     */
    private String generateFileName(String title) {
        if (title == null || title.isEmpty()) {
            title = "video_" + System.currentTimeMillis();
        }
        
        // 移除非法字符
        title = title.replaceAll("[\\\\/:*?\"<>|]", "_");
        
        // 添加扩展名（保存为 TS 格式，可以直接播放）
        if (!title.toLowerCase().endsWith(".ts") && !title.toLowerCase().endsWith(".mp4")) {
            title += ".ts";
        }
        
        return title;
    }
    
    /**
     * 删除临时目录
     */
    private void deleteTempDir(File dir) {
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            dir.delete();
        }
    }
    
    private void postProgress(DownloadCallback callback, int progress, String message) {
        mMainHandler.post(() -> callback.onProgress(progress, message));
    }
    
    private void postSuccess(DownloadCallback callback, String filePath) {
        mMainHandler.post(() -> callback.onSuccess(filePath));
    }
    
    private void postError(DownloadCallback callback, String error) {
        mMainHandler.post(() -> callback.onError(error));
    }
    
    public void shutdown() {
        if (mExecutor != null) {
            mExecutor.shutdown();
        }
    }
}
