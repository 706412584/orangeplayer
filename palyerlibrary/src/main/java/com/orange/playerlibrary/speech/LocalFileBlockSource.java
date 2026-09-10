package com.orange.playerlibrary.speech;

import java.io.File;

/**
 * 本地/已缓存 mp4 的块音频来源：MediaExtractor seek 到区间起点解码，截到区间终点。
 * 复用 AudioTrackExtractor 区间抽取 + BlockAudioUtil（重采样 + wav 头）。
 */
public class LocalFileBlockSource implements BlockAudioSource {

    private final File mVideoFile;
    private final long mDurationMs;

    public LocalFileBlockSource(File videoFile, long durationMs) {
        mVideoFile = videoFile;
        mDurationMs = durationMs;
    }

    @Override
    public long extractBlockWav(long startMs, long endMs, File outWav) throws Exception {
        File pcm = new File(outWav.getParentFile(),
                outWav.getName() + "_" + startMs + ".pcm");
        try {
            int srcRate = AudioTrackExtractor.extractPcm(mVideoFile, pcm, 0, null,
                    startMs * 1000L, endMs * 1000L);
            if (!pcm.exists() || pcm.length() == 0) {
                return -1;
            }
            BlockAudioUtil.pcmToWav(pcm, srcRate, outWav);
            return startMs;   // 本地 seek 起点即该块的绝对起点
        } finally {
            BlockAudioUtil.deleteQuiet(pcm);
        }
    }

    @Override
    public long getDurationMs() {
        return mDurationMs;
    }

    @Override
    public void release() {
    }
}
