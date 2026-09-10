package com.orange.playerlibrary.speech;

import android.content.Context;
import android.util.Log;

import com.orange.playerlibrary.download.M3U8Downloader;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HLS 的块音频来源：按区间取 m3u8 分片 → 拼接 TS → 解码 → 16k wav。
 *
 * 关键：分片字节**优先复用播放器的 media3 缓存**（走框架现成的
 * {@code Media3CacheExportUtils.export}——内部用 CacheDataSource + acquireCacheSingleInstance，
 * 命中缓存零网络，未命中自动下载并写回同一份缓存，反向惠及播放）。
 * 因此「边看边识别」不会重复下载已播过的分片。
 */
public class HlsCachedBlockSource implements BlockAudioSource {

    private static final String TAG = "HlsCachedBlockSource";
    private static final long SEGMENT_TIMEOUT_SEC = 60;

    private final Context mContext;
    private final String mM3u8Url;
    private final File mWorkDir;

    private List<M3U8Downloader.Segment> mSegments;
    private long mDurationMs;

    public HlsCachedBlockSource(Context context, String m3u8Url, File workDir) {
        mContext = context.getApplicationContext();
        mM3u8Url = m3u8Url;
        mWorkDir = workDir;
    }

    private synchronized List<M3U8Downloader.Segment> segments() throws Exception {
        if (mSegments == null) {
            mSegments = new M3U8Downloader(mContext).parseSegments(mM3u8Url);
            long total = 0;
            for (M3U8Downloader.Segment s : mSegments) {
                total += s.durationMs;
            }
            mDurationMs = total;
            Log.d(TAG, "HLS 分片: " + mSegments.size() + " 个, 总时长 " + mDurationMs + "ms");
        }
        return mSegments;
    }

    @Override
    public long extractBlockWav(long startMs, long endMs, File outWav) throws Exception {
        List<M3U8Downloader.Segment> segs = segments();
        if (segs.isEmpty()) {
            return -1;
        }
        // 定位覆盖 [startMs,endMs) 的分片范围
        int i0 = -1;
        int i1 = -1;
        for (int i = 0; i < segs.size(); i++) {
            M3U8Downloader.Segment s = segs.get(i);
            long sEnd = s.startMs + Math.max(s.durationMs, 1);
            if (sEnd <= startMs) {
                continue;
            }
            if (s.startMs >= endMs) {
                break;
            }
            if (i0 < 0) {
                i0 = i;
            }
            i1 = i;
        }
        if (i0 < 0) {
            return -1;
        }
        final long actualStartMs = segs.get(i0).startMs;

        // 逐分片取字节（优先缓存）并拼接成临时 TS
        File ts = new File(mWorkDir, outWav.getName() + ".ts");
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(ts), 256 * 1024)) {
                for (int i = i0; i <= i1; i++) {
                    File part = new File(mWorkDir, outWav.getName() + "_seg" + i);
                    try {
                        if (exportSegment(segs.get(i).url, part)) {
                            java.nio.file.Files.copy(part.toPath(), out);
                        } else {
                            Log.w(TAG, "分片不可用: " + segs.get(i).url);
                        }
                    } finally {
                        BlockAudioUtil.deleteQuiet(part);
                    }
                }
            }
            if (ts.length() == 0) {
                return -1;
            }
            // TS（MPEG-TS）→ 16k 单声道 wav
            File pcm = new File(mWorkDir, outWav.getName() + ".pcm");
            try {
                int srcRate = AudioTrackExtractor.extractPcm(ts, pcm, 0, null);
                if (!pcm.exists() || pcm.length() == 0) {
                    return -1;
                }
                BlockAudioUtil.pcmToWav(pcm, srcRate, outWav);
            } finally {
                BlockAudioUtil.deleteQuiet(pcm);
            }
            return actualStartMs;
        } finally {
            BlockAudioUtil.deleteQuiet(ts);
        }
    }

    /**
     * 取单个分片字节到 target：优先播放器 media3 缓存，未命中经 CacheDataSource
     * 自动下载并写入同一缓存。export 为异步回调，这里转同步等待。
     */
    private boolean exportSegment(String segUrl, File target) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<File> ok = new AtomicReference<>();
        tv.danmaku.ijk.media.exo2.Media3CacheExportUtils.export(mContext, segUrl, target,
                new tv.danmaku.ijk.media.exo2.Media3CacheExportUtils.ExportCallback() {
                    @Override
                    public void onSuccess(File file) {
                        ok.set(file);
                        latch.countDown();
                    }

                    @Override
                    public void onProgress(float progress) {
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.w(TAG, "分片导出失败: " + segUrl, e);
                        latch.countDown();
                    }
                });
        if (!latch.await(SEGMENT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            Log.w(TAG, "分片导出超时: " + segUrl);
            return false;
        }
        File f = ok.get();
        return f != null && f.exists() && f.length() > 0;
    }

    @Override
    public long getDurationMs() {
        if (mDurationMs <= 0) {
            try {
                segments();
            } catch (Exception e) {
                Log.w(TAG, "解析 HLS 时长失败", e);
            }
        }
        return mDurationMs;
    }

    @Override
    public void release() {
    }
}
