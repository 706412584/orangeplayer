package com.orange.playerlibrary.speech;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * sherpa ASR 模型下载器（SenseVoice 中/英/日/韩/粤）。
 *
 * 模型共三个文件（合计约 240MB），此前需手动 adb push 到
 * {@code getExternalFilesDir(null)/asr_model}，终端用户装完 APK 无法使用。
 * 本类提供应用内下载：
 *  - 多源：HuggingFace 国内镜像（hf-mirror.com）优先，官方站兜底
 *  - 断点续传：239MB 大文件中断后从已下载字节继续（HTTP Range）
 *  - 进度：按三文件总字节汇总，回调含已下载/总量（便于显示 MB 数）
 *  - 校验：按服务端 Content-Length 比对，截断文件自动重下
 *
 * 与 {@link VoskModelManager} 同模式（下载能力独立于 UI，UI 层订阅回调）。
 */
public class AsrModelDownloader {

    private static final String TAG = "AsrModelDownloader";

    /** HuggingFace 国内镜像（首选；实测可达且支持 Range） */
    private static final String HF_MIRROR_BASE =
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/";

    /** HuggingFace 官方（兜底） */
    private static final String HF_OFFICIAL_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/";

    /** silero VAD 来自 sherpa-onnx 的 GitHub Releases（与模型仓库不同源） */
    private static final String VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 128 * 1024;

    /** 模型文件清单：文件名 / 相对路径 / 预期字节数（0 = 不校验） */
    private static final class ModelFile {
        final String name;
        final String url;
        /** 备用地址（HuggingFace 官方），null 表示无备用 */
        final String fallbackUrl;
        final long expectedSize;

        ModelFile(String name, String url, String fallbackUrl, long expectedSize) {
            this.name = name;
            this.url = url;
            this.fallbackUrl = fallbackUrl;
            this.expectedSize = expectedSize;
        }
    }

    /** 三文件按「大文件优先」排序：先下主体，进度条前段就有明显推进 */
    private static ModelFile[] buildManifest() {
        return new ModelFile[]{
                new ModelFile("model.int8.onnx",
                        HF_MIRROR_BASE + "model.int8.onnx",
                        HF_OFFICIAL_BASE + "model.int8.onnx",
                        239_233_841L),
                new ModelFile("tokens.txt",
                        HF_MIRROR_BASE + "tokens.txt",
                        HF_OFFICIAL_BASE + "tokens.txt",
                        315_894L),
                new ModelFile("silero_vad.onnx", VAD_URL, null, 643_854L),
        };
    }

    public interface DownloadCallback {
        /** @param percent 0-100（三文件总进度）；@param stage 当前阶段描述 */
        void onProgress(int percent, long downloaded, long total, String stage);

        void onSuccess();

        void onError(String error);
    }

    private final Context mContext;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mDownloading = new AtomicBoolean(false);
    private volatile boolean mCancelled;

    public AsrModelDownloader(Context context) {
        mContext = context.getApplicationContext();
    }

    /** 是否正在下载（防止重复触发） */
    public boolean isDownloading() {
        return mDownloading.get();
    }

    /** 取消当前下载（已下字节保留，下次可续传） */
    public void cancel() {
        mCancelled = true;
    }

    /**
     * 开始下载（后台线程；重复调用会被忽略）。
     * 已存在的完整文件会跳过，只下缺失/不完整的部分。
     */
    public void download(DownloadCallback callback) {
        if (!mDownloading.compareAndSet(false, true)) {
            notifyError(callback, "下载已在进行中");
            return;
        }
        mCancelled = false;
        mExecutor.execute(() -> {
            try {
                runDownload(callback);
            } catch (Exception e) {
                Log.e(TAG, "下载失败", e);
                notifyError(callback, e.getMessage() != null ? e.getMessage() : "下载失败");
            } finally {
                mDownloading.set(false);
            }
        });
    }

    private void runDownload(DownloadCallback callback) {
        File modelDir = AsrSubtitleGenerator.getModelDir(mContext);
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            notifyError(callback, "无法创建模型目录");
            return;
        }

        ModelFile[] files = buildManifest();
        long totalBytes = 0;
        for (ModelFile f : files) {
            totalBytes += f.expectedSize;
        }

        long doneBytes = 0;   // 已完成文件（含已完整存在者）的字节
        for (ModelFile file : files) {
            if (mCancelled) {
                notifyError(callback, "已取消");
                return;
            }
            File target = new File(modelDir, file.name);

            // 已完整存在（按体积判断）→ 跳过
            if (file.expectedSize > 0 && target.exists()
                    && target.length() == file.expectedSize) {
                Log.d(TAG, "跳过已存在: " + file.name + " (" + target.length() + " bytes)");
                doneBytes += file.expectedSize;
                notifyProgress(callback, doneBytes, totalBytes, "已完成 " + file.name);
                continue;
            }

            if (!downloadFile(file, target, doneBytes, totalBytes, callback)) {
                if (mCancelled) {
                    notifyError(callback, "已取消");
                } else {
                    notifyError(callback, "下载失败: " + file.name);
                }
                return;
            }
            doneBytes += file.expectedSize;
        }

        // 最终校验（三文件齐全）
        if (!AsrSubtitleGenerator.isModelReady(mContext)) {
            notifyError(callback, "下载完成但模型文件不完整，请重试");
            return;
        }
        Log.d(TAG, "模型下载完成: " + modelDir.getAbsolutePath());
        notifyProgress(callback, totalBytes, totalBytes, "完成");
        if (callback != null) {
            callback.onSuccess();
        }
    }

    /**
     * 下载单个文件（支持断点续传）。
     *
     * @return 成功返回 true；取消/失败返回 false
     */
    private boolean downloadFile(ModelFile file, File target,
                                 long doneBytes, long totalBytes,
                                 DownloadCallback callback) {
        // 先试主源，失败换备用源
        String[] sources = file.fallbackUrl != null
                ? new String[]{file.url, file.fallbackUrl}
                : new String[]{file.url};

        for (int i = 0; i < sources.length; i++) {
            if (mCancelled) {
                return false;
            }
            String source = sources[i];
            try {
                if (fetchWithResume(file, target, source, doneBytes, totalBytes, callback)) {
                    return true;
                }
                Log.w(TAG, "源下载未完成，尝试下一个: " + source);
            } catch (Exception e) {
                Log.w(TAG, "源失败 " + source + ": " + e.getMessage());
                if (mCancelled) {
                    return false;
                }
            }
        }
        return false;
    }

    /** 单源下载（含续传与体积校验） */
    private boolean fetchWithResume(ModelFile file, File target, String source,
                                    long doneBytes, long totalBytes,
                                    DownloadCallback callback) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".part");
        long existing = temp.exists() ? temp.length() : 0;

        // 已下超出预期（源换了/异常）→ 重来
        if (file.expectedSize > 0 && existing > file.expectedSize) {
            temp.delete();
            existing = 0;
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(source).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "OrangePlayer/1.0");
            conn.setInstanceFollowRedirects(true);
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=" + existing + "-");
            }

            int code = conn.getResponseCode();
            boolean resumed = (code == HttpURLConnection.HTTP_PARTIAL);   // 206
            if (code != HttpURLConnection.HTTP_OK && !resumed) {
                throw new IOException("HTTP " + code);
            }
            if (existing > 0 && !resumed) {
                // 服务端不支持 Range：从头开始
                Log.d(TAG, "服务端不支持断点续传，重新下载: " + file.name);
                temp.delete();
                existing = 0;
            }

            long fileTotal = file.expectedSize;
            if (fileTotal <= 0) {
                long contentLength = conn.getContentLengthLong();
                fileTotal = resumed ? existing + contentLength : contentLength;
            }

            long downloaded = existing;
            Log.d(TAG, "开始下载 " + file.name + "（" + (existing > 0 ? "续传自 " + existing : "全新")
                    + "，目标 " + fileTotal + " 字节，源=" + source + "）");
            notifyProgress(callback, doneBytes + downloaded, totalBytes,
                    "下载 " + file.name + (existing > 0 ? "（续传）" : ""));

            byte[] buffer = new byte[BUFFER_SIZE];
            int lastPercent = -1;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(temp, existing > 0)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (mCancelled) {
                        return false;
                    }
                    out.write(buffer, 0, read);
                    downloaded += read;

                    if (totalBytes > 0) {
                        int percent = (int) ((doneBytes + downloaded) * 100 / totalBytes);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            notifyProgress(callback, doneBytes + downloaded, totalBytes,
                                    "下载 " + file.name);
                        }
                    }
                }
            }

            // 体积校验：截断则保留已下部分供下次续传，本次视作失败
            long actual = temp.length();
            if (fileTotal > 0 && actual != fileTotal) {
                Log.w(TAG, "体积不符 " + file.name + ": " + actual + " / " + fileTotal);
                return false;
            }

            // 原子落位
            if (target.exists() && !target.delete()) {
                throw new IOException("无法覆盖目标文件");
            }
            if (!temp.renameTo(target)) {
                throw new IOException("无法保存文件");
            }
            Log.d(TAG, "下载完成: " + file.name + " (" + actual + " bytes)");
            return true;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 已下载字节（用于 UI 展示 MB 数，含 .part 在途部分） */
    public long getDownloadedBytes() {
        File dir = AsrSubtitleGenerator.getModelDir(mContext);
        long sum = 0;
        for (ModelFile f : buildManifest()) {
            File target = new File(dir, f.name);
            if (target.exists()) {
                sum += target.length();
            }
            File part = new File(dir, f.name + ".part");
            if (part.exists()) {
                sum += part.length();
            }
        }
        return sum;
    }

    /** 模型总字节数（UI 显示总量用） */
    public static long getTotalBytes() {
        long sum = 0;
        for (ModelFile f : buildManifest()) {
            sum += f.expectedSize;
        }
        return sum;
    }

    /** 清理下载残留（.part 文件），供「重新下载」入口使用 */
    public void clearPartialFiles() {
        File dir = AsrSubtitleGenerator.getModelDir(mContext);
        for (ModelFile f : buildManifest()) {
            File part = new File(dir, f.name + ".part");
            if (part.exists()) {
                part.delete();
            }
        }
    }

    private void notifyProgress(DownloadCallback callback, long downloaded, long total,
                                String stage) {
        if (callback == null) {
            return;
        }
        int percent = total > 0 ? (int) Math.min(99, downloaded * 100 / total) : 0;
        if (downloaded >= total && total > 0) {
            percent = 100;
        }
        callback.onProgress(percent, downloaded, total, stage);
    }

    private void notifyError(DownloadCallback callback, String error) {
        Log.w(TAG, "下载错误: " + error);
        if (callback != null) {
            callback.onError(error);
        }
    }

    public void shutdown() {
        mExecutor.shutdown();
    }
}
