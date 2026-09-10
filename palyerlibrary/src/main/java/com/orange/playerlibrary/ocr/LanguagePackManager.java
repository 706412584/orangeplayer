package com.orange.playerlibrary.ocr;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OCR 语言包管理器
 * 支持在线下载和管理 Tesseract 语言包
 * 同时支持扫描 assets/tessdata 中的预置语言包
 */
public class LanguagePackManager {
    
    private static final String TAG = "LanguagePackManager";
    private static final String TESSDATA_DIR = "tessdata";
    private static final String ASSETS_TESSDATA_DIR = "tessdata";
    
    // GitHub 原地址（最终兜底）
    private static final String DOWNLOAD_BASE_URL =
        "https://github.com/tesseract-ocr/tessdata_fast/raw/main/";

    // 国内加速（2026-09 实测可用；返回真实文件与正确 Content-Length）
    private static final String CHINA_MIRROR_URL =
        "https://gh-proxy.com/https://github.com/tesseract-ocr/tessdata_fast/raw/main/";

    private static final String CHINA_MIRROR_URL_2 =
        "https://ghfast.top/https://github.com/tesseract-ocr/tessdata_fast/raw/main/";

    // jsDelivr CDN（文件经过压缩，体积与 GitHub 原文件不同，校验按比例放宽）
    private static final String JSDELIVR_URL =
        "https://cdn.jsdelivr.net/gh/tesseract-ocr/tessdata_fast@main/";

    /**
     * 下载内容有效性的体积下限比例（相对 estimatedSize）。
     * 用途：拒绝「HTTP 200 + 错误页正文」这类假成功——已失效的 ghproxy 会返回
     * 200 和约 1.8KB 的 HTML 落地页，只检查状态码会把 HTML 存成 .traineddata。
     */
    private static final double MIN_SIZE_RATIO = 0.35;

    /** 内容有效性的绝对体积下限（最小语言包约 600KB，1KB 级必为错误页） */
    private static final long MIN_VALID_BYTES = 64 * 1024;
    
    private final Context mContext;
    private final ExecutorService mExecutor;
    private final Handler mMainHandler;
    
    // 语言代码到显示名称的映射
    private static final Map<String, String> LANG_NAME_MAP = new HashMap<>();
    static {
        LANG_NAME_MAP.put("chi_sim", "简体中文");
        LANG_NAME_MAP.put("chi_tra", "繁体中文");
        LANG_NAME_MAP.put("eng", "英语");
        LANG_NAME_MAP.put("jpn", "日语");
        LANG_NAME_MAP.put("kor", "韩语");
        LANG_NAME_MAP.put("fra", "法语");
        LANG_NAME_MAP.put("deu", "德语");
        LANG_NAME_MAP.put("spa", "西班牙语");
        LANG_NAME_MAP.put("rus", "俄语");
        LANG_NAME_MAP.put("ara", "阿拉伯语");
        LANG_NAME_MAP.put("tha", "泰语");
        LANG_NAME_MAP.put("vie", "越南语");
        LANG_NAME_MAP.put("por", "葡萄牙语");
        LANG_NAME_MAP.put("ita", "意大利语");
        LANG_NAME_MAP.put("nld", "荷兰语");
        LANG_NAME_MAP.put("pol", "波兰语");
        LANG_NAME_MAP.put("tur", "土耳其语");
        LANG_NAME_MAP.put("hin", "印地语");
        LANG_NAME_MAP.put("ind", "印尼语");
        LANG_NAME_MAP.put("msa", "马来语");
    }
    
    /**
     * 获取语言显示名称
     */
    public static String getLanguageDisplayName(String langCode) {
        String name = LANG_NAME_MAP.get(langCode);
        return name != null ? name : langCode;
    }
    
    /**
     * 获取语言名称映射表
     */
    public static Map<String, String> getLanguageNameMap() {
        return new HashMap<>(LANG_NAME_MAP);
    }
    
    /**
     * 语言包信息
     */
    public static class LanguagePack {
        public final String code;        // 语言代码，如 "eng", "chi_sim"
        public final String name;        // 显示名称
        public final String description; // 描述
        public final long estimatedSize; // 预估大小（字节）
        public boolean installed;        // 是否已安装
        
        public LanguagePack(String code, String name, String description, long estimatedSize) {
            this.code = code;
            this.name = name;
            this.description = description;
            this.estimatedSize = estimatedSize;
            this.installed = false;
        }
        
        public String getFileName() {
            return code + ".traineddata";
        }
        
        public String getSizeText() {
            if (estimatedSize < 1024) {
                return estimatedSize + " B";
            } else if (estimatedSize < 1024 * 1024) {
                return String.format("%.1f KB", estimatedSize / 1024.0);
            } else {
                return String.format("%.1f MB", estimatedSize / (1024.0 * 1024));
            }
        }
    }
    
    /**
     * 下载回调
     */
    public interface DownloadCallback {
        void onProgress(int progress, long downloaded, long total);
        void onSuccess();
        void onError(String error);
    }
    
    public LanguagePackManager(Context context) {
        mContext = context.getApplicationContext();
        mExecutor = Executors.newSingleThreadExecutor();
        mMainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 获取常用语言包列表
     */
    public List<LanguagePack> getAvailableLanguages() {
        List<LanguagePack> languages = new ArrayList<>();
        
        // 常用语言包（使用 tessdata_fast 版本的大小）
        languages.add(new LanguagePack("chi_sim", "简体中文", "识别简体中文字幕", 2_500_000));
        languages.add(new LanguagePack("chi_tra", "繁体中文", "识别繁体中文字幕", 2_400_000));
        languages.add(new LanguagePack("eng", "英语", "识别英文字幕", 4_100_000));
        languages.add(new LanguagePack("jpn", "日语", "识别日文字幕", 2_300_000));
        languages.add(new LanguagePack("kor", "韩语", "识别韩文字幕", 1_500_000));
        languages.add(new LanguagePack("fra", "法语", "识别法文字幕", 1_300_000));
        languages.add(new LanguagePack("deu", "德语", "识别德文字幕", 1_200_000));
        languages.add(new LanguagePack("spa", "西班牙语", "识别西班牙文字幕", 1_200_000));
        languages.add(new LanguagePack("rus", "俄语", "识别俄文字幕", 1_400_000));
        languages.add(new LanguagePack("ara", "阿拉伯语", "识别阿拉伯文字幕", 1_100_000));
        languages.add(new LanguagePack("tha", "泰语", "识别泰文字幕", 1_000_000));
        languages.add(new LanguagePack("vie", "越南语", "识别越南文字幕", 600_000));
        
        // 检查已安装状态
        for (LanguagePack pack : languages) {
            pack.installed = isLanguageInstalled(pack.code);
        }
        
        return languages;
    }
    
    /**
     * 检查语言包是否已安装（包括下载的和 assets 中的）
     */
    public boolean isLanguageInstalled(String languageCode) {
        // 检查下载目录
        File tessDataDir = new File(mContext.getFilesDir(), TESSDATA_DIR);
        File trainedDataFile = new File(tessDataDir, languageCode + ".traineddata");
        if (trainedDataFile.exists() && trainedDataFile.length() > 0) {
            return true;
        }
        
        // 检查 assets
        return isLanguageInAssets(languageCode);
    }
    
    /**
     * 获取语言包文件
     */
    public File getLanguageFile(String languageCode) {
        File tessDataDir = new File(mContext.getFilesDir(), TESSDATA_DIR);
        return new File(tessDataDir, languageCode + ".traineddata");
    }
    
    /**
     * 下载语言包
     */
    public void downloadLanguage(String languageCode, DownloadCallback callback) {
        mExecutor.execute(() -> {
            try {
                downloadLanguageInternal(languageCode, callback);
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                postError(callback, "下载失败: " + e.getMessage());
            }
        });
    }

    
    private void downloadLanguageInternal(String languageCode, DownloadCallback callback) {
        String fileName = languageCode + ".traineddata";
        
        // 确保目录存在
        File tessDataDir = new File(mContext.getFilesDir(), TESSDATA_DIR);
        if (!tessDataDir.exists()) {
            tessDataDir.mkdirs();
        }
        
        File targetFile = new File(tessDataDir, fileName);
        File tempFile = new File(tessDataDir, fileName + ".tmp");
        
        // 尝试多个下载源（顺序：国内加速 → CDN → 原站）
        String[] urls = {
            CHINA_MIRROR_URL + fileName,      // 国内加速
            CHINA_MIRROR_URL_2 + fileName,    // 国内加速（备用）
            JSDELIVR_URL + fileName,          // jsDelivr CDN
            DOWNLOAD_BASE_URL + fileName      // GitHub 原地址
        };

        // 校验基准：预估体积（用于拒绝错误页/截断文件）
        long expectedSize = findExpectedSize(languageCode);

        Exception lastError = null;
        
        for (String downloadUrl : urls) {
            Log.d(TAG, "Trying download from: " + downloadUrl);
            
            HttpURLConnection connection = null;
            InputStream inputStream = null;
            FileOutputStream outputStream = null;
            
            try {
                URL url = new URL(downloadUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "OrangePlayer/1.0");
                connection.setInstanceFollowRedirects(true);
                
                int responseCode = connection.getResponseCode();
                
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "HTTP " + responseCode + " from " + downloadUrl);
                    connection.disconnect();
                    continue;
                }
                
                long totalSize = connection.getContentLength();
                Log.d(TAG, "File size: " + totalSize);

                // 服务器未给 Content-Length 时用预估体积做进度基准：否则整段下载
                // 期间进度恒为 0（真机表现「点了没反应」）
                final long progressBase = totalSize > 0 ? totalSize
                        : (expectedSize > 0 ? expectedSize : 0);
                // 立即上报一次起始进度：让 UI 马上进入「下载中」状态，
                // 不必等第一个数据块（首源连接/重定向可能耗时数秒）
                postProgress(callback, 0, 0, progressBase);

                inputStream = connection.getInputStream();
                outputStream = new FileOutputStream(tempFile);

                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int bytesRead;
                int lastProgress = 0;
                // 首块字节：用于识别错误页（HTML/JSON 正文）
                byte[] head = null;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    if (head == null) {
                        head = new byte[Math.min(64, bytesRead)];
                        System.arraycopy(buffer, 0, head, 0, head.length);
                    }
                    outputStream.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;

                    if (progressBase > 0) {
                        int progress = (int) (downloaded * 100 / progressBase);
                        if (progress > 99 && downloaded < progressBase) {
                            progress = 99;   // 预估偏小时不虚报 100%
                        }
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            postProgress(callback, progress, downloaded, progressBase);
                        }
                    }
                }

                outputStream.close();
                outputStream = null;

                // 内容校验：拒绝错误页/截断文件（只查状态码会放过 200+HTML 的假成功）
                String invalidReason = validateDownloaded(downloaded, expectedSize, head);
                if (invalidReason != null) {
                    Log.w(TAG, "Invalid content from " + downloadUrl + ": " + invalidReason
                            + " (bytes=" + downloaded + ", expected~" + expectedSize + ")");
                    lastError = new Exception(invalidReason);
                    if (tempFile.exists()) {
                        tempFile.delete();
                    }
                    continue;   // 换下一个源
                }

                // 重命名临时文件
                if (targetFile.exists()) {
                    targetFile.delete();
                }
                if (tempFile.renameTo(targetFile)) {
                    Log.d(TAG, "Download completed: " + targetFile.getAbsolutePath()
                            + " (" + downloaded + " bytes)");
                    postProgress(callback, 100, downloaded, downloaded);
                    postSuccess(callback);
                    return; // 成功，退出
                } else {
                    throw new Exception("无法保存文件");
                }
                
            } catch (Exception e) {
                Log.w(TAG, "Download failed from " + downloadUrl + ": " + e.getMessage());
                lastError = e;
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            } finally {
                try {
                    if (outputStream != null) outputStream.close();
                    if (inputStream != null) inputStream.close();
                    if (connection != null) connection.disconnect();
                } catch (Exception ignored) {}
            }
        }
        
        // 所有源都失败
        String errorMsg = lastError != null ? lastError.getMessage() : "下载失败";
        postError(callback, errorMsg + "\n如无法下载，请尝试使用代理");
    }

    /** 按语言代码查预估体积（查不到返回 0，此时体积校验放宽） */
    private long findExpectedSize(String languageCode) {
        for (LanguagePack pack : getAvailableLanguages()) {
            if (pack.code.equals(languageCode)) {
                return pack.estimatedSize;
            }
        }
        return 0;
    }

    /**
     * 校验下载内容是否是可用的语言包。
     *
     * 关键场景：部分（已停止服务的）GitHub 加速站会对任意路径返回
     * HTTP 200 + HTML 落地页，只检查状态码会把错误页存成 .traineddata，
     * 用户看到「下载完成」但 OCR 直接失败。
     *
     * @return null 表示有效；否则返回失败原因
     */
    static String validateDownloaded(long bytes, long expectedSize, byte[] head) {
        if (bytes < MIN_VALID_BYTES) {
            return "文件过小（" + bytes + " 字节），疑似下载到错误页";
        }
        if (expectedSize > 0 && bytes < expectedSize * MIN_SIZE_RATIO) {
            return "文件不完整（" + bytes + " / 约 " + expectedSize + " 字节）";
        }
        if (head != null && head.length > 0) {
            char first = (char) (head[0] & 0xFF);
            // HTML / XML / JSON 错误正文
            if (first == '<' || first == '{') {
                return "返回的是网页内容而非语言包（疑似镜像站失效）";
            }
        }
        return null;
    }
    
    /**
     * 删除语言包
     */
    public boolean deleteLanguage(String languageCode) {
        File file = getLanguageFile(languageCode);
        if (file.exists()) {
            boolean deleted = file.delete();
            Log.d(TAG, "Delete " + languageCode + ": " + deleted);
            return deleted;
        }
        return true;
    }
    
    /**
     * 获取已安装的语言包大小
     */
    public long getInstalledSize() {
        File tessDataDir = new File(mContext.getFilesDir(), TESSDATA_DIR);
        if (!tessDataDir.exists()) {
            return 0;
        }
        
        long totalSize = 0;
        File[] files = tessDataDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".traineddata")) {
                    totalSize += file.length();
                }
            }
        }
        return totalSize;
    }
    
    /**
     * 获取已安装的语言列表（包括 assets 和下载的）
     */
    public List<String> getInstalledLanguages() {
        Set<String> installed = new HashSet<>();
        
        // 1. 扫描下载目录
        File tessDataDir = new File(mContext.getFilesDir(), TESSDATA_DIR);
        if (tessDataDir.exists()) {
            File[] files = tessDataDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (name.endsWith(".traineddata")) {
                        installed.add(name.replace(".traineddata", ""));
                    }
                }
            }
        }
        
        // 2. 扫描 assets/tessdata 目录
        try {
            AssetManager assetManager = mContext.getAssets();
            String[] assetFiles = assetManager.list(ASSETS_TESSDATA_DIR);
            if (assetFiles != null) {
                for (String fileName : assetFiles) {
                    if (fileName.endsWith(".traineddata")) {
                        installed.add(fileName.replace(".traineddata", ""));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to scan assets tessdata: " + e.getMessage());
        }
        
        return new ArrayList<>(installed);
    }
    
    /**
     * 检查语言包是否在 assets 中
     */
    public boolean isLanguageInAssets(String languageCode) {
        try {
            AssetManager assetManager = mContext.getAssets();
            String[] assetFiles = assetManager.list(ASSETS_TESSDATA_DIR);
            if (assetFiles != null) {
                String targetFile = languageCode + ".traineddata";
                for (String fileName : assetFiles) {
                    if (fileName.equals(targetFile)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to check assets: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * 从 assets 复制语言包到内部存储
     */
    public boolean copyLanguageFromAssets(String languageCode) {
        String fileName = languageCode + ".traineddata";
        File tessDataDir = new File(mContext.getFilesDir(), TESSDATA_DIR);
        if (!tessDataDir.exists()) {
            tessDataDir.mkdirs();
        }
        
        File targetFile = new File(tessDataDir, fileName);
        if (targetFile.exists()) {
            return true; // 已存在
        }
        
        try {
            AssetManager assetManager = mContext.getAssets();
            InputStream inputStream = assetManager.open(ASSETS_TESSDATA_DIR + "/" + fileName);
            FileOutputStream outputStream = new FileOutputStream(targetFile);
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            
            outputStream.close();
            inputStream.close();
            
            Log.d(TAG, "Copied from assets: " + fileName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy from assets: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取语言包文件路径（优先使用下载的，其次使用 assets）
     * 如果语言包在 assets 中，会自动复制到内部存储
     */
    public File getLanguageFileWithAssetsFallback(String languageCode) {
        File tessDataDir = new File(mContext.getFilesDir(), TESSDATA_DIR);
        File downloadedFile = new File(tessDataDir, languageCode + ".traineddata");
        
        // 优先使用下载的文件
        if (downloadedFile.exists() && downloadedFile.length() > 0) {
            return downloadedFile;
        }
        
        // 尝试从 assets 复制
        if (isLanguageInAssets(languageCode)) {
            if (copyLanguageFromAssets(languageCode)) {
                return downloadedFile;
            }
        }
        
        return null;
    }
    
    private void postProgress(DownloadCallback callback, int progress, long downloaded, long total) {
        if (callback != null) {
            mMainHandler.post(() -> callback.onProgress(progress, downloaded, total));
        }
    }
    
    private void postSuccess(DownloadCallback callback) {
        if (callback != null) {
            mMainHandler.post(callback::onSuccess);
        }
    }
    
    private void postError(DownloadCallback callback, String error) {
        if (callback != null) {
            mMainHandler.post(() -> callback.onError(error));
        }
    }
    
    public void shutdown() {
        mExecutor.shutdown();
    }
}
