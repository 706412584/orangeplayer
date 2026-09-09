package com.orange.ffmpeg;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FFmpegKit {

    private static final String TAG = "FFmpegKit";

    public static final int RESULT_OK = 0;
    public static final int RESULT_NOT_INITIALIZED = -1;
    public static final int RESULT_LIBRARY_LOAD_FAILED = -2;
    public static final int RESULT_EXECUTE_FAILED = -3;
    public static final int RESULT_CANCELLED = -4;

    private static final AtomicBoolean sLibraryLoaded = new AtomicBoolean(false);
    private static final AtomicBoolean sInitialized = new AtomicBoolean(false);
    private static final AtomicBoolean sCancelled = new AtomicBoolean(false);
    private static final AtomicBoolean sStubMode = new AtomicBoolean(false);


    private FFmpegKit() {
    }

    public static synchronized int init() {
        if (sInitialized.get()) {
            return RESULT_OK;
        }
        boolean loaded = ensureLibraryLoaded();
        if (!loaded) {
            Log.w(TAG, "native library not found, switch to stub mode");
            sStubMode.set(true);
            sInitialized.set(true);
            sCancelled.set(false);
            return RESULT_OK;
        }
        try {
            int ret = nativeInit();
            if (ret == 0) {
                sInitialized.set(true);
                sCancelled.set(false);
                sStubMode.set(false);
                return RESULT_OK;
            }
            return ret;
        } catch (Throwable t) {
            Log.e(TAG, "init failed", t);
            return RESULT_EXECUTE_FAILED;
        }
    }


    public static int execute(String[] args) {
        if (!sInitialized.get()) {
            return RESULT_NOT_INITIALIZED;
        }
        if (sCancelled.get()) {
            return RESULT_CANCELLED;
        }
        if (sStubMode.get()) {
            return executeInStubMode(args);
        }
        try {
            return nativeExecute(args);
        } catch (Throwable t) {
            Log.e(TAG, "execute failed", t);
            return RESULT_EXECUTE_FAILED;
        }
    }


    public static void executeAsync(final String[] args, final FFmpegCallback callback) {
        new Thread(() -> {
            int code = execute(args);
            if (callback != null) {
                callback.onComplete(code, code == RESULT_OK ? "success" : "failed:" + code);
            }
        }, "orange-ffmpeg-exec").start();
    }

    public static void cancel() {
        sCancelled.set(true);
        if (!sInitialized.get()) {
            return;
        }
        try {
            nativeCancel();
        } catch (Throwable t) {
            Log.w(TAG, "cancel ignored", t);
        }
    }

    public static String getVersion() {
        if (!sInitialized.get()) {
            return "not_initialized";
        }
        if (sStubMode.get()) {
            return "stub-0.1";
        }
        try {
            String version = nativeGetVersion();
            return version == null ? "unknown" : version;
        } catch (Throwable t) {
            Log.w(TAG, "getVersion failed", t);
            return "unknown";
        }
    }


    public static boolean isInitialized() {
        return sInitialized.get();
    }

    public static boolean isStubMode() {
        return sStubMode.get();
    }

    public static int trim(String inputPath, String outputPath, String start, String end) {
        if (isEmpty(inputPath) || isEmpty(outputPath)) {
            return RESULT_EXECUTE_FAILED;
        }
        if (isEmpty(start) && isEmpty(end)) {
            return RESULT_EXECUTE_FAILED;
        }
        List<String> args = new ArrayList<>();
        if (!isEmpty(start)) {
            args.add("-ss");
            args.add(start);
        }
        if (!isEmpty(end)) {
            args.add("-to");
            args.add(end);
        }
        args.add("-i");
        args.add(inputPath);
        args.add("-c");
        args.add("copy");
        args.add(outputPath);
        return execute(args.toArray(new String[0]));
    }

    public static int concat(String concatListPath, String outputPath) {
        if (isEmpty(concatListPath) || isEmpty(outputPath)) {
            return RESULT_EXECUTE_FAILED;
        }
        File listFile = new File(concatListPath);
        if (!listFile.exists()) {
            return RESULT_EXECUTE_FAILED;
        }
        String[] args = new String[]{
                "-f", "concat",
                "-safe", "0",
                "-i", concatListPath,
                "-c", "copy",
                outputPath
        };
        return execute(args);
    }

    public static int watermark(String inputPath, String watermarkPath, String outputPath, int x, int y) {
        if (isEmpty(inputPath) || isEmpty(watermarkPath) || isEmpty(outputPath)) {
            return RESULT_EXECUTE_FAILED;
        }
        String[] args = new String[]{
                "-i", inputPath,
                "-i", watermarkPath,
                "-filter_complex", "overlay=" + x + ":" + y,
                "-c:v", "mpeg4",
                "-c:a", "copy",
                outputPath
        };
        return execute(args);
    }

    public static int thumbnail(String inputPath, String outputPath, String timestamp) {
        if (isEmpty(inputPath) || isEmpty(outputPath)) {
            return RESULT_EXECUTE_FAILED;
        }
        List<String> args = new ArrayList<>();
        if (!isEmpty(timestamp)) {
            args.add("-ss");
            args.add(timestamp);
        }
        args.add("-i");
        args.add(inputPath);
        args.add("-frames:v");
        args.add("1");
        args.add("-q:v");
        args.add("2");
        args.add(outputPath);
        return execute(args.toArray(new String[0]));
    }

    public static int extractAudio(String inputPath, String outputPath, boolean toMp3) {
        if (isEmpty(inputPath) || isEmpty(outputPath)) {
            return RESULT_EXECUTE_FAILED;
        }
        List<String> args = new ArrayList<>();
        args.add("-i");
        args.add(inputPath);
        args.add("-vn");
        if (toMp3) {
            args.add("-c:a");
            args.add("mp3");
        } else {
            args.add("-c:a");
            args.add("copy");
        }
        args.add(outputPath);
        return execute(args.toArray(new String[0]));
    }

    /**
     * 抽取 16k 单声道 PCM wav（ASR 输入标准格式，SenseVoice/VAD 硬性要求 16k）。
     * -vn 去视频；-ac 1 混单声道（比取单声道更稳）；-ar 16000 重采样。
     */
    public static int extractAudioWav16k(String inputPath, String outputPath) {
        if (isEmpty(inputPath) || isEmpty(outputPath)) {
            return RESULT_EXECUTE_FAILED;
        }
        List<String> args = new ArrayList<>();
        args.add("-y");
        args.add("-i");
        args.add(inputPath);
        args.add("-vn");
        args.add("-ac");
        args.add("1");
        args.add("-ar");
        args.add("16000");
        // 裸 PCM 流（16k/单声道/s16le）——native 库对 wav 容器封装支持不稳
        // （实测输出 ftyp 头即原容器），裸流只需 demux+重采样，兼容性最稳。
        // 调用方需自行包 RIFF 头或按裸 PCM 读取。
        args.add("-f");
        args.add("s16le");
        args.add(outputPath);
        return execute(args.toArray(new String[0]));
    }

    private static int executeInStubMode(String[] args) {
        if (args == null || args.length == 0) {
            return RESULT_EXECUTE_FAILED;
        }
        for (String arg : args) {
            if ("-version".equals(arg)) {
                return RESULT_OK;
            }
        }
        return RESULT_EXECUTE_FAILED;
    }

    private static boolean ensureLibraryLoaded() {

        if (sLibraryLoaded.get()) {
            return true;
        }
        try {
            System.loadLibrary("orangeffmpegkit");
            sLibraryLoaded.set(true);
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "load library failed", e);
            return false;
        }
    }

    private static native int nativeInit();

    private static native int nativeExecute(String[] args);

    private static native void nativeCancel();

    private static native String nativeGetVersion();

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
