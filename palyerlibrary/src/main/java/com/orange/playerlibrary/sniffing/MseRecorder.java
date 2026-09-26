package com.orange.playerlibrary.sniffing;

import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MSE 录制器：把播放器喂给 {@code SourceBuffer.appendBuffer} 的字节落盘。
 *
 * <p>用途：现代站点常用 {@code <video src="blob:...">} + MediaSource，真实媒体地址
 * 藏在 JS 里，网络层只能看到无后缀分片或加密请求，嗅探结果为空。此时改吃
 * 「播放器已经拿到的数据」——不再解析协议，直接捕获 appendBuffer 的内容。
 *
 * <p>为什么不需要 mux：MSE 喂进来的 fMP4 分片本身就是合法的 MP4 片段
 * （{@code moof+mdat}），初始化段含 {@code ftyp+moov}。把 init 段与后续媒体段
 * 按顺序拼接即得到标准 fragmented MP4，主流播放器可直接播。这也是本项目
 * 在 ffmpeg 组件缺失时唯一可行的路径（见 {@code NativeLibManager} 的
 * {@code liborangeffmpegkit.so}）。
 *
 * <p>已知边界（不掩盖）：
 * <ul>
 *   <li>video / audio 是两条独立 SourceBuffer，各自落成独立文件。**合并音视频
 *       需要 mux**，本类不做，调用方需明确告知用户产物是分离的。</li>
 *   <li>MSE 分片常为 TS→fMP4 转封装后的产物，时长依赖播放进度；不播就没数据。</li>
 *   <li>仅捕获**已解密**的样本（EME/Widevine 的加密样本仍不可用）。</li>
 * </ul>
 */
public class MseRecorder {

    private static final String TAG = "MseRecorder";

    /** 每个 SourceBuffer 一条轨道 */
    private final Map<String, Track> mTracks = new LinkedHashMap<>();

    private final File mOutDir;
    private volatile boolean mRecording = false;
    private volatile boolean mStopped = false;

    /** 已落盘字节总数，用于 UI 展示与上限保护 */
    private final AtomicLong mTotalBytes = new AtomicLong(0);

    /**
     * 单次录制的字节上限。捕获的数据要经 base64 过 JS 桥（字符串），
     * 不设上限会在长视频上把内存吃光。超限后停止接收并明确记录原因。
     */
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    public MseRecorder(File outDir) {
        mOutDir = outDir;
    }

    /** 一条 SourceBuffer 对应的轨道 */
    private static class Track {
        final String id;
        final String mime;
        FileOutputStream out;
        File file;
        long bytes;
        /** 收到的首个分片视为 init 段（含 ftyp/moov），单独记录便于诊断 */
        boolean sawInitSegment;

        Track(String id, String mime) {
            this.id = id;
            this.mime = mime;
        }
    }

    public void start() {
        mRecording = true;
        mStopped = false;
        mTotalBytes.set(0);
        synchronized (mTracks) {
            mTracks.clear();
        }
        if (!mOutDir.exists()) {
            mOutDir.mkdirs();
        }
        Log.i(TAG, "recording started, dir=" + mOutDir);
    }

    public boolean isRecording() {
        return mRecording && !mStopped;
    }

    public long getTotalBytes() {
        return mTotalBytes.get();
    }

    /** 已捕获的轨道数（video/audio） */
    public int getTrackCount() {
        synchronized (mTracks) {
            return mTracks.size();
        }
    }

    /**
     * 接收一个 appendBuffer 分片（来自 JS 桥，base64 传输）。
     *
     * @param sourceBufferId 来源 SourceBuffer 的实例编号（页面侧生成）
     * @param mime           SourceBuffer 的 mime，如 {@code video/mp4; codecs="avc1.64001f"}
     * @param base64Data     分片字节的 base64（JS 桥只能传字符串）
     * @param isInit         该分片是否为 init 段
     */
    public void onChunk(String sourceBufferId, String mime, String base64Data, boolean isInit) {
        if (base64Data == null || base64Data.isEmpty()) {
            return;
        }
        byte[] data;
        try {
            data = Base64.decode(base64Data, Base64.DEFAULT);
        } catch (Exception e) {
            Log.w(TAG, "decode chunk failed: " + e.getMessage());
            return;
        }
        onChunkBytes(sourceBufferId, mime, data, isInit);
    }

    /**
     * 接收已解码的分片字节。
     *
     * <p>与 {@link #onChunk} 分开是为了让核心的「按顺序追加 / 按实例分轨」逻辑
     * 能在纯 JVM 单测里直接验证（base64 只是 JS 桥的传输细节）。
     *
     * @param data 分片字节，可为 null/空（会被忽略）
     */
    void onChunkBytes(String sourceBufferId, String mime, byte[] data, boolean isInit) {
        if (!isRecording() || data == null || data.length == 0) {
            return;
        }
        if (mTotalBytes.get() >= MAX_TOTAL_BYTES) {
            if (!mStopped) {
                mStopped = true;
                Log.w(TAG, "reached size cap " + MAX_TOTAL_BYTES + " bytes, stop accepting");
            }
            return;
        }

        String key = (sourceBufferId == null || sourceBufferId.isEmpty())
                ? normalizeMime(mime) : sourceBufferId;
        synchronized (mTracks) {
            Track track = mTracks.get(key);
            if (track == null) {
                track = new Track(key, normalizeMime(mime));
                if (!openTrack(track)) {
                    return;
                }
                mTracks.put(key, track);
            }
            if (isInit && !track.sawInitSegment) {
                track.sawInitSegment = true;
            }
            try {
                track.out.write(data);
                track.out.flush();
                track.bytes += data.length;
                mTotalBytes.addAndGet(data.length);
            } catch (IOException e) {
                Log.w(TAG, "write chunk failed: " + e.getMessage());
            }
        }
    }

    private boolean openTrack(Track track) {
        try {
            // 目录可能在中途被外部删掉（清缓存、用户手动删），每次开轨都确保存在；
            // 否则 appendBuffer 的数据会静默写失败，录制看起来"开着"却没有产物。
            if (!mOutDir.exists() && !mOutDir.mkdirs() && !mOutDir.exists()) {
                Log.e(TAG, "cannot create output dir: " + mOutDir);
                return false;
            }
            boolean audio = track.mime.startsWith("audio/");
            String ext = audio ? ".m4a" : ".mp4";
            // 文件名带实例编号与序号：同一类型出现多个 SourceBuffer（换集/换码率）
            // 时必须落成不同文件，否则片段会交叉写入导致产物损坏。
            int seq;
            synchronized (mTracks) {
                seq = mTracks.size() + 1;
            }
            String name = (audio ? "recorded_audio_" : "recorded_video_")
                    + seq + "_" + System.currentTimeMillis() + ext;
            track.file = new File(mOutDir, name);
            track.out = new FileOutputStream(track.file);
            Log.i(TAG, "track opened: " + track.file.getName()
                    + " (id=" + track.id + ", mime=" + track.mime + ")");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "open track failed for " + track.mime, e);
            return false;
        }
    }

    /**
     * 结束录制并返回产物。
     *
     * @return 落盘的文件列表（可能为空，表示没捕获到任何数据）
     */
    public List<File> stop() {
        mRecording = false;
        List<File> results = new ArrayList<>();
        synchronized (mTracks) {
            for (Track track : mTracks.values()) {
                if (track.out != null) {
                    try {
                        track.out.flush();
                        track.out.close();
                    } catch (Exception ignored) {
                    }
                    track.out = null;
                }
                if (track.file != null && track.file.exists() && track.file.length() > 0) {
                    results.add(track.file);
                    Log.i(TAG, "track finished: " + track.file.getName()
                            + " bytes=" + track.file.length()
                            + " initSegmentSeen=" + track.sawInitSegment);
                } else if (track.file != null) {
                    // 没有数据的空文件不要留给用户
                    track.file.delete();
                }
            }
        }
        Log.i(TAG, "recording stopped, files=" + results.size()
                + ", totalBytes=" + mTotalBytes.get());
        return results;
    }

    /**
     * 归一化 mime：去掉 codecs 参数。
     * {@code video/mp4; codecs="avc1.64001f"} 与 {@code video/mp4; codecs="avc1.4d401f"}
     * 是同一路视频，若按完整串分桶会切成多个文件、破坏片段连续性。
     */
    private static String normalizeMime(String mime) {
        if (mime == null || mime.isEmpty()) {
            return "application/octet-stream";
        }
        int cut = mime.indexOf(';');
        String base = (cut > 0 ? mime.substring(0, cut) : mime).trim().toLowerCase();
        // 音频轨常见 audio/mp4；video 轨 video/mp4
        return base.isEmpty() ? "application/octet-stream" : base;
    }
}
