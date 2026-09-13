package com.orange.playerlibrary.speech;

import android.content.Context;

import com.orange.playerlibrary.download.ResumableFileDownloader;

import java.io.File;

/**
 * sherpa ASR 模型下载器（SenseVoice 中/英/日/韩/粤）。
 *
 * 模型共三个文件（合计约 240MB），此前需手动 adb push 到
 * {@code getExternalFilesDir(null)/asr_model}，终端用户装完 APK 无法使用。
 * 本类提供应用内下载：多源（HuggingFace 国内镜像优先，官方站兜底）、
 * 断点续传、按三文件总字节汇总的进度、SHA-256 校验。
 *
 * 下载机制已下沉到 {@link ResumableFileDownloader}（与按需 native 组件共用）；
 * 本类只负责清单与目标目录。
 */
public class AsrModelDownloader {

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

    /** 清单（static final：FileSpec 无状态，避免每次调用重建） */
    private static final ResumableFileDownloader.FileSpec[] MANIFEST = {
            new ResumableFileDownloader.FileSpec("model.int8.onnx",
                    HF_MIRROR_BASE + "model.int8.onnx",
                    HF_OFFICIAL_BASE + "model.int8.onnx",
                    239_233_841L,
                    "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
            new ResumableFileDownloader.FileSpec("tokens.txt",
                    HF_MIRROR_BASE + "tokens.txt",
                    HF_OFFICIAL_BASE + "tokens.txt",
                    315_894L,
                    "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
            new ResumableFileDownloader.FileSpec("silero_vad.onnx", VAD_URL, null, 643_854L,
                    "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
    };

    /** 期望体积（0 = 未知）；供完整性校验复用，避免尺寸表两处维护 */
    public static long expectedSizeOf(String fileName) {
        return ResumableFileDownloader.expectedSizeOf(MANIFEST, fileName);
    }

    /** 模型总字节数（UI 显示总量用） */
    public static long getTotalBytes() {
        long sum = 0;
        for (ResumableFileDownloader.FileSpec f : MANIFEST) {
            sum += f.expectedSize;
        }
        return sum;
    }

    /** 校验文件 SHA-256（{@code expected} 为 null 时跳过并返回 true） */
    static boolean verifyHash(File file, String expected) {
        return ResumableFileDownloader.verifyHash(file, expected);
    }

    /** 与通用下载器同一套回调（别名，保持本包 API 自洽） */
    public interface DownloadCallback extends ResumableFileDownloader.DownloadCallback {
    }

    private final ResumableFileDownloader mDownloader;

    public AsrModelDownloader(Context context) {
        Context appContext = context.getApplicationContext();
        mDownloader = new ResumableFileDownloader(
                AsrSubtitleGenerator.getModelDir(appContext), MANIFEST,
                () -> AsrSubtitleGenerator.isModelReady(appContext),
                "AsrModelDownloader");
    }

    /** 是否正在下载（防止重复触发） */
    public boolean isDownloading() {
        return mDownloader.isDownloading();
    }

    /**
     * 解绑回调但**不中断下载**：宿主（Activity/面板）销毁时调用，
     * 释放对 Activity 与 View 的引用；下载在后台继续，完成后仍会落盘，
     * 下次进入设置面板会看到「模型已就绪」。
     */
    public void detachCallback() {
        mDownloader.detachCallback();
    }

    /** 取消当前下载（已下字节保留，下次可续传） */
    public void cancel() {
        mDownloader.cancel();
    }

    /**
     * 开始下载（后台线程；重复调用会被忽略）。
     * 已存在的完整文件会跳过，只下缺失/不完整的部分。
     */
    public void download(DownloadCallback callback) {
        mDownloader.download(callback);
    }

    /** 已下载字节（用于 UI 展示 MB 数，含 .part 在途部分） */
    public long getDownloadedBytes() {
        return mDownloader.getDownloadedBytes();
    }

    /** 清理下载残留（.part 文件），供「重新下载」入口使用 */
    public void clearPartialFiles() {
        mDownloader.clearPartialFiles();
    }

    public void shutdown() {
        mDownloader.shutdown();
    }
}
