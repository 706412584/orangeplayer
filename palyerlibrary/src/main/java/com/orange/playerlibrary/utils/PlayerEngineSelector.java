package com.orange.playerlibrary.utils;

import android.util.Log;

import com.orange.playerlibrary.PlayerConstants;
import com.orange.playerlibrary.utils.UrlProtocolClassifier.UrlInfo;

import java.util.List;

/**
 * 播放器内核智能选择器
 *
 * 根据视频 URL 协议自动选择最合适的播放器内核。
 * 判定逻辑下沉到 {@link UrlProtocolClassifier}（协议分类）与
 * {@link EngineCapabilityMatrix}（能力矩阵），本类保留为兼容薄封装。
 *
 * 使用示例：
 * <pre>
 * String engine = PlayerEngineSelector.selectEngine(url);
 * videoView.selectPlayerFactory(engine);
 * videoView.setUp(url, true, title);
 * videoView.startPlayLogic();
 * </pre>
 *
 * @author OrangePlayer
 */
public class PlayerEngineSelector {

    private static final String TAG = "PlayerEngineSelector";

    /**
     * 根据 URL 自动选择最合适的播放器内核
     *
     * @param url 视频 URL
     * @return 推荐的播放器内核常量（String）
     */
    public static String selectEngine(String url) {
        return selectEngine(url, PlayerConstants.ENGINE_EXO);
    }

    /**
     * 根据 URL 自动选择最合适的播放器内核
     *
     * @param url 视频 URL
     * @param defaultEngine 默认内核（当无候选引擎时使用）
     * @return 推荐的播放器内核常量（String）
     */
    public static String selectEngine(String url, String defaultEngine) {
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "URL 为空，使用默认内核: " + getEngineName(defaultEngine));
            return defaultEngine;
        }

        UrlInfo info = UrlProtocolClassifier.parse(url);
        List<String> candidates = EngineCapabilityMatrix.candidates(info);

        if (candidates.isEmpty()) {
            Log.w(TAG, "无引擎支持该协议（" + info.scheme + "），使用默认内核: "
                    + getEngineName(defaultEngine));
            return defaultEngine;
        }
        String first = candidates.get(0);
        Log.i(TAG, "协议 " + getProtocolType(url) + "，候选链 " + candidates
                + "，推荐 " + getEngineName(first));
        return first;
    }

    /**
     * 检查指定内核是否支持该 URL
     *
     * @param url 视频 URL
     * @param engine 播放器内核（String）
     * @return true 表示支持，false 表示不支持
     */
    public static boolean isEngineSupported(String url, String engine) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        return EngineCapabilityMatrix.supports(engine, UrlProtocolClassifier.parse(url));
    }

    /**
     * 获取内核名称
     */
    public static String getEngineName(String engine) {
        if (PlayerConstants.ENGINE_EXO.equals(engine)) {
            return "ExoPlayer";
        } else if (PlayerConstants.ENGINE_IJK.equals(engine)) {
            return "IJK";
        } else if (PlayerConstants.ENGINE_ALI.equals(engine)) {
            return "阿里云";
        } else if (PlayerConstants.ENGINE_DEFAULT.equals(engine)) {
            return "系统播放器";
        } else {
            return "未知";
        }
    }

    /**
     * 获取协议类型描述
     */
    public static String getProtocolType(String url) {
        if (url == null || url.isEmpty()) {
            return "未知";
        }
        UrlInfo info = UrlProtocolClassifier.parse(url);
        String s = info.scheme;
        if ("rtsp".equals(s) || "rtsps".equals(s)) return "RTSP 直播";
        if ("rtmp".equals(s) || "rtmps".equals(s)) return "RTMP 直播";
        if ("rtmpt".equals(s) || "rtmpe".equals(s)) return "RTMP 变体（无引擎支持）";
        if ("udp".equals(s)) return "UDP 流";
        if ("tcp".equals(s)) return "TCP 流";
        if ("webrtc".equals(s)) return "WebRTC（无引擎支持）";
        if ("magnet".equals(s) || "torrent".equals(s)) return "种子";
        if (info.isHlsLike) return "HLS";
        if ("flv".equals(info.lastSegmentExt)) return "FLV 直播";
        if ("mpd".equals(info.lastSegmentExt)) return "DASH";
        if ("http".equals(s) || "https".equals(s)) {
            return info.isLoopbackProxy ? "本地代理流" : "HTTP 点播";
        }
        if (info.isLocalFile) return "本地文件";
        if ("content".equals(s)) return "Content Uri";
        return "未知协议";
    }

    /**
     * 打印内核支持情况
     */
    public static void printEngineSupportInfo(String url) {
        Log.i(TAG, "========== 播放器内核支持情况 ==========");
        Log.i(TAG, "URL: " + url);
        Log.i(TAG, "协议类型: " + getProtocolType(url));
        Log.i(TAG, "ExoPlayer: " + (isEngineSupported(url, PlayerConstants.ENGINE_EXO) ? "✅ 支持" : "❌ 不支持"));
        Log.i(TAG, "IJK: " + (isEngineSupported(url, PlayerConstants.ENGINE_IJK) ? "✅ 支持" : "❌ 不支持"));
        Log.i(TAG, "阿里云: " + (isEngineSupported(url, PlayerConstants.ENGINE_ALI) ? "✅ 支持" : "❌ 不支持"));
        Log.i(TAG, "系统播放器: " + (isEngineSupported(url, PlayerConstants.ENGINE_DEFAULT) ? "✅ 支持" : "❌ 不支持"));
        Log.i(TAG, "推荐内核: " + getEngineName(selectEngine(url)));
        Log.i(TAG, "======================================");
    }
}
