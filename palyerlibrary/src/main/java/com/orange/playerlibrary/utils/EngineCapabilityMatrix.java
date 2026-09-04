package com.orange.playerlibrary.utils;

import com.orange.playerlibrary.PlayerConstants;
import com.orange.playerlibrary.utils.UrlProtocolClassifier.UrlInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 内核协议能力矩阵（纯函数）。
 * <p>
 * 修正历史误判：
 * - udp:// / tcp://（裸 TS 流）仅 IJK（FFmpeg demux），Exo 无 demuxer
 * - rtmp(s):// 阿里云首推、IJK 备选；Exo 需 media3-datasource-rtmp 之外不支持
 * - webrtc:// 无引擎支持（显式无能力，不再假装推荐）
 * - .mpd (DASH) 仅 Exo
 * - content:// / android.resource:// Exo/IJK/系统（阿里云不承诺）
 * - 回环代理流（127.0.0.1 去广告/种子）按普通 http 处理，但调用方应禁用 autoSelect
 */
public final class EngineCapabilityMatrix {

    private EngineCapabilityMatrix() {
    }

    /**
     * 判断引擎是否支持该 URL 类别
     */
    public static boolean supports(String engine, UrlInfo info) {
        if (engine == null || info == null) {
            return false;
        }
        String s = info.scheme;
        String ext = info.lastSegmentExt;

        switch (engine) {
            case PlayerConstants.ENGINE_EXO:
                // Exo: rtsp(经 rtsp 扩展)/http(s)/file/content/HLS/DASH/本地
                // 不支持：裸 udp/tcp、rtmp（除 rtmp 扩展场景）、webrtc
                if ("rtsp".equals(s) || "rtsps".equals(s)) return true;
                if ("udp".equals(s) || "tcp".equals(s)) return false;
                if ("rtmp".equals(s) || "rtmps".equals(s)
                        || "rtmpt".equals(s) || "rtmpe".equals(s)) return false;
                if ("webrtc".equals(s)) return false;
                if ("http".equals(s) || "https".equals(s)) return true;
                if ("content".equals(s) || "android.resource".equals(s)
                        || "asset".equals(s) || "data".equals(s)) return true;
                if (info.isLocalFile) return true;
                return false;

            case PlayerConstants.ENGINE_IJK:
                // IJK: FFmpeg 全协议白名单（file/http/https/tls/rtp/tcp/udp/concat/subfile/data）
                if ("webrtc".equals(s)) return false;
                if ("rtmpt".equals(s) || "rtmpe".equals(s)) return false;
                if (s == null) return info.isLocalFile || !ext.isEmpty();
                return true;

            case PlayerConstants.ENGINE_ALI:
                // 阿里云: http(s) 流（含 HLS/FLV/RTMP），不承诺本地/rtsp/udp/dash/webrtc/content
                if ("http".equals(s) || "https".equals(s)
                        || "rtmp".equals(s) || "rtmps".equals(s)) {
                    return !"mpd".equals(ext);
                }
                return false;

            case PlayerConstants.ENGINE_DEFAULT:
                // 系统播放器: http(s) 渐进式与本地；不承诺 HLS 自适应之外的流协议
                if ("http".equals(s) || "https".equals(s)) {
                    // m3u8 系统层多数可播但行为不稳定，保守返回 true（维持历史行为）
                    return true;
                }
                if ("file".equals(s) || "content".equals(s)) return true;
                if (info.isLocalFile) return true;
                return false;

            default:
                return false;
        }
    }

    /**
     * 按 URL 类别给出有序候选链（首推在前）。
     * 仅包含 supports() 为 true 的引擎。
     */
    public static List<String> candidates(UrlInfo info) {
        List<String> result = new ArrayList<>();
        if (info == null) {
            return result;
        }
        String s = info.scheme;
        String ext = info.lastSegmentExt;

        if ("rtsp".equals(s) || "rtsps".equals(s)) {
            addIf(result, PlayerConstants.ENGINE_EXO, info);
            addIf(result, PlayerConstants.ENGINE_IJK, info);
            return result;
        }
        if ("udp".equals(s) || "tcp".equals(s)) {
            addIf(result, PlayerConstants.ENGINE_IJK, info);
            return result;
        }
        if ("rtmp".equals(s) || "rtmps".equals(s)) {
            addIf(result, PlayerConstants.ENGINE_ALI, info);
            addIf(result, PlayerConstants.ENGINE_IJK, info);
            return result;
        }
        if ("rtmpt".equals(s) || "rtmpe".equals(s) || "webrtc".equals(s)) {
            // 无引擎支持
            return result;
        }
        if ("m3u8".equals(ext) && ("http".equals(s) || "https".equals(s))) {
            addIf(result, PlayerConstants.ENGINE_ALI, info);
            addIf(result, PlayerConstants.ENGINE_EXO, info);
            addIf(result, PlayerConstants.ENGINE_IJK, info);
            return result;
        }
        if ("flv".equals(ext) && ("http".equals(s) || "https".equals(s))) {
            addIf(result, PlayerConstants.ENGINE_ALI, info);
            addIf(result, PlayerConstants.ENGINE_IJK, info);
            return result;
        }
        if ("mpd".equals(ext)) {
            addIf(result, PlayerConstants.ENGINE_EXO, info);
            return result;
        }
        if ("http".equals(s) || "https".equals(s)) {
            addIf(result, PlayerConstants.ENGINE_EXO, info);
            addIf(result, PlayerConstants.ENGINE_IJK, info);
            addIf(result, PlayerConstants.ENGINE_ALI, info);
            addIf(result, PlayerConstants.ENGINE_DEFAULT, info);
            return result;
        }
        if ("content".equals(s) || "android.resource".equals(s) || "asset".equals(s)) {
            addIf(result, PlayerConstants.ENGINE_EXO, info);
            addIf(result, PlayerConstants.ENGINE_IJK, info);
            addIf(result, PlayerConstants.ENGINE_DEFAULT, info);
            return result;
        }
        if (info.isLocalFile) {
            addIf(result, PlayerConstants.ENGINE_EXO, info);
            addIf(result, PlayerConstants.ENGINE_IJK, info);
            addIf(result, PlayerConstants.ENGINE_DEFAULT, info);
            return result;
        }
        // 未知 scheme：IJK 最宽容
        addIf(result, PlayerConstants.ENGINE_IJK, info);
        addIf(result, PlayerConstants.ENGINE_EXO, info);
        return result;
    }

    private static void addIf(List<String> list, String engine, UrlInfo info) {
        if (supports(engine, info) && !list.contains(engine)) {
            list.add(engine);
        }
    }
}
