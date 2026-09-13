package com.orange.playerlibrary.download;

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
 * 通用可续传文件下载器（多源 + HTTP Range 断点续传 + SHA-256 校验 + 原子落位）。
 *
 * 由 {@code AsrModelDownloader}（模型 228MB）与 {@code NativeLibManager}
 * （按需 native 组件包）共用；两类文件体积差一个数量级，但下载语义完全一致，
 * 各自维护一套续传/校验逻辑会持续分叉。
 *
 * 下载能力独立于 UI，UI 层订阅回调。
 */
public class ResumableFileDownloader {

    /** 单个待下载文件描述 */
    public static final class FileSpec {
        public final String name;
        /** 候选源，按序尝试；**各源必须提供同一份字节**（跨源续传会拼接前缀与后缀） */
        public final String[] urls;
        public final long expectedSize;
        /** 内容哈希（null 表示跳过校验）；跨源续传后用于确认拼接结果完整 */
        public final String sha256;

        /** 双源构造（主源 + 备用源） */
        public FileSpec(String name, String url, String fallbackUrl,
                        long expectedSize, String sha256) {
            this(name, fallbackUrl != null ? new String[]{url, fallbackUrl} : new String[]{url},
                    expectedSize, sha256);
        }

        /** 多源构造 */
        public FileSpec(String name, String[] urls, long expectedSize, String sha256) {
            this.name = name;
            this.urls = urls;
            this.expectedSize = expectedSize;
            this.sha256 = sha256;
        }
    }

    /** 全部文件下载完成后的最终确认（如「三件套是否齐全」），返回 false 视为失败 */
    public interface CompletionCheck {
        boolean isComplete();
    }

    public interface DownloadCallback {
        /** @param percent 0-100（全部文件总进度）；@param stage 当前阶段描述 */
        void onProgress(int percent, long downloaded, long total, String stage);

        void onSuccess();

        void onError(String error);
    }

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 128 * 1024;

    /** HTTP 416：Range 起点越界（Android 的 HttpURLConnection 无此常量） */
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;

    private final File mTargetDir;
    private final FileSpec[] mManifest;
    private final CompletionCheck mCompletionCheck;
    private final String mTag;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mDownloading = new AtomicBoolean(false);
    private volatile boolean mCancelled;

    /**
     * 当前下载的回调。存为字段（而非仅传参）是为了能在宿主销毁时解绑：
     * 回调通常捕获了 Activity 与其 View，大文件下载可能持续数分钟，
     * 不解绑会让已销毁的 Activity 一直被静态下载器持有。
     */
    private volatile DownloadCallback mCallback;

    public ResumableFileDownloader(File targetDir, FileSpec[] manifest,
                                   CompletionCheck completionCheck, String tag) {
        mTargetDir = targetDir;
        mManifest = manifest;
        mCompletionCheck = completionCheck;
        mTag = tag;
    }

    /** 是否正在下载（防止重复触发） */
    public boolean isDownloading() {
        return mDownloading.get();
    }

    /**
     * 解绑回调但**不中断下载**：宿主（Activity/面板）销毁时调用，
     * 释放对 Activity 与 View 的引用；下载在后台继续，完成后仍会落盘，
     * 下次进入面板会看到「已就绪」。
     */
    public void detachCallback() {
        mCallback = null;
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
            notifyError(mCallback, "下载已在进行中");
            return;
        }
        mCancelled = false;
        mCallback = callback;
        mExecutor.execute(() -> {
            try {
                runDownload();
            } catch (Exception e) {
                Log.e(mTag, "下载失败", e);
                notifyError(mCallback, e.getMessage() != null ? e.getMessage() : "下载失败");
            } finally {
                mDownloading.set(false);
            }
        });
    }

    private void runDownload() {
        if (!mTargetDir.exists() && !mTargetDir.mkdirs()) {
            notifyError(mCallback, "无法创建目录");
            return;
        }

        long totalBytes = getTotalBytes();
        long doneBytes = 0;   // 已完成文件（含已完整存在者）的字节
        for (FileSpec file : mManifest) {
            if (mCancelled) {
                notifyError(mCallback, "已取消");
                return;
            }
            File target = new File(mTargetDir, file.name);

            // 已完整存在 → 跳过。除体积外还验哈希：同尺寸的损坏文件（下载期拼接
            // 错误、磁盘错误）若只比体积会被永久跳过，模型一直不可用且无修复路径。
            // 用户点「下载」本身就是修复意图，此处多花约 1~2 秒（239MB）可接受。
            if (file.expectedSize > 0 && target.exists()
                    && target.length() == file.expectedSize
                    && verifyHash(target, file.sha256)) {
                Log.d(mTag, "跳过已存在: " + file.name + " (" + target.length() + " bytes)");
                doneBytes += file.expectedSize;
                notifyProgress(mCallback, doneBytes, totalBytes, "校验通过 " + file.name);
                continue;
            }
            if (target.exists()) {
                Log.w(mTag, "已存在文件校验不通过，重新下载: " + file.name
                        + " (" + target.length() + " bytes)");
            }

            if (!downloadFile(file, target, doneBytes, totalBytes)) {
                if (mCancelled) {
                    notifyError(mCallback, "已取消");
                } else {
                    notifyError(mCallback, "下载失败: " + file.name);
                }
                return;
            }
            doneBytes += file.expectedSize;
        }

        // 最终校验（调用方定义的完整性条件）
        if (mCompletionCheck != null && !mCompletionCheck.isComplete()) {
            notifyError(mCallback, "下载完成但文件不完整，请重试");
            return;
        }
        Log.d(mTag, "下载完成: " + mTargetDir.getAbsolutePath());
        notifyProgress(mCallback, totalBytes, totalBytes, "完成");
        DownloadCallback cb = mCallback;
        if (cb != null) {
            cb.onSuccess();
        }
    }

    /**
     * 下载单个文件（支持断点续传）。
     *
     * @return 成功返回 true；取消/失败返回 false
     */
    private boolean downloadFile(FileSpec file, File target,
                                 long doneBytes, long totalBytes) {
        // 按序尝试候选源
        for (int i = 0; i < file.urls.length; i++) {
            if (mCancelled) {
                return false;
            }
            String source = file.urls[i];
            try {
                if (fetchWithResume(file, target, source, doneBytes, totalBytes, true)) {
                    return true;
                }
                Log.w(mTag, "源下载未完成，尝试下一个: " + source);
            } catch (Exception e) {
                Log.w(mTag, "源失败 " + source + ": " + e.getMessage());
                if (mCancelled) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 单源下载（含续传与体积/哈希校验）。
     *
     * @param allowRetry 416（Range 越界）时是否允许清掉 .part 后重试一次
     */
    private boolean fetchWithResume(FileSpec file, File target, String source,
                                    long doneBytes, long totalBytes, boolean allowRetry)
            throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".part");
        long existing = temp.exists() ? temp.length() : 0;

        // .part 已达/超出目标体积：无法假定其中字节完整（可能是 rename 失败或
        // 进程被杀留下的）。先按哈希确认——通过则直接落位；否则丢弃重下。
        // 旧实现只处理「严格大于」，恰好等于时会发 Range: bytes=<total>- 收到
        // 416 并被当作普通 HTTP 错误换源，两个源都 416 → 每次重试必然复现，
        // 用户被永久卡死（且当时没有清除入口可自救）。
        if (file.expectedSize > 0 && existing >= file.expectedSize) {
            if (verifyHash(temp, file.sha256)) {
                Log.d(mTag, ".part 已完整且校验通过，直接落位: " + file.name);
                return promote(temp, target);
            }
            Log.w(mTag, ".part 达目标体积但校验不通过，丢弃重下: " + file.name);
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
            // 416：Range 起点超出服务端资源长度（.part 与服务端不一致）。
            // 清掉 .part 按全新重试一次；重试不带 Range，不会再 416。
            if (code == HTTP_RANGE_NOT_SATISFIABLE
                    && existing > 0 && allowRetry) {
                Log.w(mTag, "服务端 416（.part 与服务端不一致），丢弃后重下: " + file.name);
                conn.disconnect();
                temp.delete();
                return fetchWithResume(file, target, source, doneBytes, totalBytes, false);
            }
            boolean resumed = (code == HttpURLConnection.HTTP_PARTIAL);   // 206
            if (code != HttpURLConnection.HTTP_OK && !resumed) {
                throw new IOException("HTTP " + code);
            }
            if (existing > 0 && !resumed) {
                // 服务端不支持 Range：从头开始
                Log.d(mTag, "服务端不支持断点续传，重新下载: " + file.name);
                temp.delete();
                existing = 0;
            }

            long fileTotal = file.expectedSize;
            if (fileTotal <= 0) {
                long contentLength = conn.getContentLengthLong();
                fileTotal = resumed ? existing + contentLength : contentLength;
            }

            long downloaded = existing;
            Log.d(mTag, "开始下载 " + file.name + "（" + (existing > 0 ? "续传自 " + existing : "全新")
                    + "，目标 " + fileTotal + " 字节，源=" + source + "）");
            notifyProgress(mCallback, doneBytes + downloaded, totalBytes,
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
                            notifyProgress(mCallback, doneBytes + downloaded, totalBytes,
                                    "下载 " + file.name);
                        }
                    }
                }
            }

            // 体积校验：截断则保留已下部分供下次续传，本次视作失败
            long actual = temp.length();
            if (fileTotal > 0 && actual != fileTotal) {
                Log.w(mTag, "体积不符 " + file.name + ": " + actual + " / " + fileTotal);
                return false;
            }
            // 哈希校验：体积相同不代表字节正确（跨源续传可能拼接出等长的错误文件）
            if (!verifyHash(temp, file.sha256)) {
                Log.w(mTag, "哈希校验不通过，丢弃重下: " + file.name);
                temp.delete();
                return false;
            }

            return promote(temp, target);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 原子落位：删除旧目标后 rename；失败时抛出（.part 保留供下次续传） */
    private boolean promote(File temp, File target) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("无法覆盖目标文件");
        }
        if (!temp.renameTo(target)) {
            throw new IOException("无法保存文件");
        }
        Log.d(mTag, "下载完成: " + target.getName() + " (" + target.length() + " bytes)");
        return true;
    }

    /**
     * 校验文件 SHA-256（{@code expected} 为 null 时跳过并返回 true）。
     * 239MB 文件约 1~2 秒，仅在落位前调用一次。
     */
    public static boolean verifyHash(File file, String expected) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (file == null || !file.exists()) {
            return false;
        }
        try (java.io.InputStream in = new java.io.BufferedInputStream(
                new java.io.FileInputStream(file), BUFFER_SIZE)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            boolean ok = sb.toString().equalsIgnoreCase(expected);
            if (!ok) {
                Log.w("ResumableFileDownloader", "哈希不符 " + file.getName()
                        + ": " + sb + " != " + expected);
            }
            return ok;
        } catch (Exception e) {
            Log.w("ResumableFileDownloader", "哈希计算失败（按不通过处理）: " + file.getName(), e);
            return false;
        }
    }

    /** 已下载字节（用于 UI 展示 MB 数，含 .part 在途部分） */
    public long getDownloadedBytes() {
        long sum = 0;
        for (FileSpec f : mManifest) {
            File target = new File(mTargetDir, f.name);
            if (target.exists()) {
                sum += target.length();
            }
            File part = new File(mTargetDir, f.name + ".part");
            if (part.exists()) {
                sum += part.length();
            }
        }
        return sum;
    }

    /** 全部文件总字节数（UI 显示总量用） */
    public long getTotalBytes() {
        long sum = 0;
        for (FileSpec f : mManifest) {
            sum += f.expectedSize;
        }
        return sum;
    }

    /** 清理下载残留（.part 文件），供「重新下载」入口使用 */
    public void clearPartialFiles() {
        for (FileSpec f : mManifest) {
            File part = new File(mTargetDir, f.name + ".part");
            if (part.exists()) {
                part.delete();
            }
        }
    }

    /** 按文件名查期望体积（0 = 未知）；供完整性校验复用，避免尺寸表两处维护 */
    public static long expectedSizeOf(FileSpec[] manifest, String fileName) {
        for (FileSpec f : manifest) {
            if (f.name.equals(fileName)) {
                return f.expectedSize;
            }
        }
        return 0;
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
        Log.w(mTag, "下载错误: " + error);
        if (callback != null) {
            callback.onError(error);
        }
    }

    public void shutdown() {
        mExecutor.shutdown();
    }
}
