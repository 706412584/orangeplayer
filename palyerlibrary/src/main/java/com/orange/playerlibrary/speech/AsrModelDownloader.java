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
 * 下载能力独立于 UI，UI 层订阅回调。
 */
public class AsrModelDownloader {

    private static final String TAG = "AsrModelDownloader";

    /**
     * HuggingFace 仓库 revision（main 的 commit）。
     * 固定 revision 而非 resolve/main：跨源续传时备用源必须提供**同一份字节**，
     * 否则会出现「A 的前缀 + B 的后缀」拼接（体积校验通不过，但能过长度校验）。
     * 该仓库 main 自 2024-07 未变动。
     */
    private static final String HF_REVISION = "2365baeacb507f821a0c8120fcee3d484dba7a07";

    private static final String HF_REPO_BASE =
            "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17";

    /** HuggingFace 国内镜像（首选；实测可达且支持 Range） */
    private static final String HF_MIRROR_BASE =
            "https://hf-mirror.com/" + HF_REPO_BASE + "/resolve/" + HF_REVISION + "/";

    /** HuggingFace 官方（兜底） */
    private static final String HF_OFFICIAL_BASE =
            "https://huggingface.co/" + HF_REPO_BASE + "/resolve/" + HF_REVISION + "/";

    /** silero VAD 来自 sherpa-onnx 的 GitHub Releases（与模型仓库不同源） */
    private static final String VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 128 * 1024;

    /** HTTP 416：Range 起点越界（Android 的 HttpURLConnection 无此常量） */
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;

    /** 模型文件清单：文件名 / 下载地址 / 备用地址 / 预期字节数（0 = 不校验）/ SHA-256 */
    private static final class ModelFile {
        final String name;
        final String url;
        /** 备用地址（HuggingFace 官方），null 表示无备用 */
        final String fallbackUrl;
        final long expectedSize;
        /** 内容哈希（null 表示跳过校验）；跨源续传后用于确认拼接结果完整 */
        final String sha256;

        ModelFile(String name, String url, String fallbackUrl, long expectedSize, String sha256) {
            this.name = name;
            this.url = url;
            this.fallbackUrl = fallbackUrl;
            this.expectedSize = expectedSize;
            this.sha256 = sha256;
        }
    }

    /** 清单（static final：ModelFile 无状态，避免每次调用重建） */
    private static final ModelFile[] MANIFEST = {
            new ModelFile("model.int8.onnx",
                    HF_MIRROR_BASE + "model.int8.onnx",
                    HF_OFFICIAL_BASE + "model.int8.onnx",
                    239_233_841L,
                    "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
            new ModelFile("tokens.txt",
                    HF_MIRROR_BASE + "tokens.txt",
                    HF_OFFICIAL_BASE + "tokens.txt",
                    315_894L,
                    "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
            new ModelFile("silero_vad.onnx", VAD_URL, null, 643_854L,
                    "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
    };

    /** 期望体积（0 = 未知）；供完整性校验复用，避免尺寸表两处维护 */
    public static long expectedSizeOf(String fileName) {
        for (ModelFile f : MANIFEST) {
            if (f.name.equals(fileName)) {
                return f.expectedSize;
            }
        }
        return 0;
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

    /**
     * 当前下载的回调。存为字段（而非仅传参）是为了能在宿主销毁时解绑：
     * 回调通常捕获了 Activity 与其 View，228MB 下载可能持续数分钟，
     * 不解绑会让已销毁的 Activity 一直被静态下载器持有。
     */
    private volatile DownloadCallback mCallback;

    public AsrModelDownloader(Context context) {
        mContext = context.getApplicationContext();
    }

    /** 是否正在下载（防止重复触发） */
    public boolean isDownloading() {
        return mDownloading.get();
    }

    /**
     * 解绑回调但**不中断下载**：宿主（Activity/面板）销毁时调用，
     * 释放对 Activity 与 View 的引用；下载在后台继续，完成后仍会落盘，
     * 下次进入设置面板会看到「模型已就绪」。
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
                Log.e(TAG, "下载失败", e);
                notifyError(mCallback, e.getMessage() != null ? e.getMessage() : "下载失败");
            } finally {
                mDownloading.set(false);
            }
        });
    }

    private void runDownload() {
        File modelDir = AsrSubtitleGenerator.getModelDir(mContext);
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            notifyError(mCallback, "无法创建模型目录");
            return;
        }

        ModelFile[] files = MANIFEST;
        long totalBytes = 0;
        for (ModelFile f : files) {
            totalBytes += f.expectedSize;
        }

        long doneBytes = 0;   // 已完成文件（含已完整存在者）的字节
        for (ModelFile file : files) {
            if (mCancelled) {
                notifyError(mCallback, "已取消");
                return;
            }
            File target = new File(modelDir, file.name);

            // 已完整存在 → 跳过。除体积外还验哈希：同尺寸的损坏文件（下载期拼接
            // 错误、磁盘错误）若只比体积会被永久跳过，模型一直不可用且无修复路径。
            // 用户点「下载模型」本身就是修复意图，此处多花约 1~2 秒（239MB）可接受。
            if (file.expectedSize > 0 && target.exists()
                    && target.length() == file.expectedSize
                    && verifyHash(target, file.sha256)) {
                Log.d(TAG, "跳过已存在: " + file.name + " (" + target.length() + " bytes)");
                doneBytes += file.expectedSize;
                notifyProgress(mCallback, doneBytes, totalBytes, "校验通过 " + file.name);
                continue;
            }
            if (target.exists()) {
                Log.w(TAG, "已存在文件校验不通过，重新下载: " + file.name
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

        // 最终校验（三文件齐全）
        if (!AsrSubtitleGenerator.isModelReady(mContext)) {
            notifyError(mCallback, "下载完成但模型文件不完整，请重试");
            return;
        }
        Log.d(TAG, "模型下载完成: " + modelDir.getAbsolutePath());
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
    private boolean downloadFile(ModelFile file, File target,
                                 long doneBytes, long totalBytes) {
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
                if (fetchWithResume(file, target, source, doneBytes, totalBytes, true)) {
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

    /**
     * 单源下载（含续传与体积/哈希校验）。
     *
     * @param allowRetry 416（Range 越界）时是否允许清掉 .part 后重试一次
     */
    private boolean fetchWithResume(ModelFile file, File target, String source,
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
                Log.d(TAG, ".part 已完整且校验通过，直接落位: " + file.name);
                return promote(temp, target);
            }
            Log.w(TAG, ".part 达目标体积但校验不通过，丢弃重下: " + file.name);
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
                Log.w(TAG, "服务端 416（.part 与服务端不一致），丢弃后重下: " + file.name);
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
                Log.w(TAG, "体积不符 " + file.name + ": " + actual + " / " + fileTotal);
                return false;
            }
            // 哈希校验：体积相同不代表字节正确（跨源续传可能拼接出等长的错误文件）
            if (!verifyHash(temp, file.sha256)) {
                Log.w(TAG, "哈希校验不通过，丢弃重下: " + file.name);
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
        Log.d(TAG, "下载完成: " + target.getName() + " (" + target.length() + " bytes)");
        return true;
    }

    /**
     * 校验文件 SHA-256（{@code expected} 为 null 时跳过并返回 true）。
     * 239MB 文件约 1~2 秒，仅在落位前调用一次。
     */
    static boolean verifyHash(File file, String expected) {
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
                Log.w(TAG, "哈希不符 " + file.getName() + ": " + sb + " != " + expected);
            }
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "哈希计算失败（按不通过处理）: " + file.getName(), e);
            return false;
        }
    }

    /** 已下载字节（用于 UI 展示 MB 数，含 .part 在途部分） */
    public long getDownloadedBytes() {
        File dir = AsrSubtitleGenerator.getModelDir(mContext);
        long sum = 0;
        for (ModelFile f : MANIFEST) {
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
        for (ModelFile f : MANIFEST) {
            sum += f.expectedSize;
        }
        return sum;
    }

    /** 清理下载残留（.part 文件），供「重新下载」入口使用 */
    public void clearPartialFiles() {
        File dir = AsrSubtitleGenerator.getModelDir(mContext);
        for (ModelFile f : MANIFEST) {
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
