package com.orange.playerlibrary.speech;

import android.content.Context;
import android.util.Log;

import com.orange.playerlibrary.cache.ExternalProxyCacheManager;

import java.io.File;
import java.util.Collections;
import java.util.Map;

/**
 * 网络视频的块音频来源：按时间区间从 http(s) 源抽取音频。
 *
 * 为什么不是「等整片缓存完」：danikula 是随播随下的，整片缓存要等到快播完，
 * 那样「边看边识别」在网络剧集上等于不可用（真机症状：切集后悬浮环一直不出现）。
 *
 * 关键点：数据源指向 danikula 的**本地代理**而不是原始 URL——
 * 已缓存的部分直接返回，缺失的按 byte range 向源站补下，所以
 *  - 不必先下完整片，播放到哪就能识别到哪；
 *  - 不会把片子下第二遍（与播放器共用同一份缓存）。
 * MediaExtractor 的 http 数据源本身就是按 range 读取的。
 */
public class HttpBlockAudioSource implements BlockAudioSource {

    private static final String TAG = "HttpBlockAudioSource";

    private final Context mContext;
    private final String mUrl;
    private final Map<String, String> mHeaders;
    private final long mDurationMs;

    public HttpBlockAudioSource(Context context, String url, Map<String, String> headers,
                                long durationMs) {
        mContext = context.getApplicationContext();
        mUrl = url;
        mHeaders = headers == null ? null : Collections.unmodifiableMap(headers);
        mDurationMs = durationMs;
    }

    @Override
    public long extractBlockWav(long startMs, long endMs, File outWav) throws Exception {
        String source = ExternalProxyCacheManager.getProxyUrl(mContext, mUrl);
        if (source == null) {
            // 代理不可用（非网络地址/端口起不来）：退回原始 URL 直连，
            // 代价是这一段要重新下载，但功能不至于整个失效
            Log.w(TAG, "本地代理不可用，直连源站: " + mUrl);
            source = mUrl;
        }
        File pcm = new File(outWav.getParentFile(),
                outWav.getName() + "_" + startMs + ".pcm");
        try {
            int srcRate = AudioTrackExtractor.extractPcm(source, mHeaders, pcm, 0, null,
                    startMs * 1000L, endMs * 1000L);
            if (!pcm.exists() || pcm.length() == 0) {
                return -1;
            }
            BlockAudioUtil.pcmToWav(pcm, srcRate, outWav);
            // 与本地源一致：seek 到区间起点，返回的偏移即块起点
            return startMs;
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
