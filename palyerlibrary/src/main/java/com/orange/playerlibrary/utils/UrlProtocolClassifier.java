package com.orange.playerlibrary.utils;

import android.net.Uri;

import java.util.Locale;

/**
 * URL 协议分类器（纯函数）。
 * <p>
 * 用 Uri 解析 + 路径末段扩展名判断，杜绝 contains() 误伤查询串/Referer。
 * JVM 单测环境（returnDefaultValues=true）下 Uri.parse 返回默认值，
 * 因此对 null/默认 Uri 回退到手工解析。
 */
public final class UrlProtocolClassifier {

    /** URL 分类结果 */
    public static class UrlInfo {
        public final String scheme;          // 小写 scheme，null 表示本地绝对路径
        public final String lastSegmentExt;  // 路径末段扩展名（小写，不含"."；可能为空串）
        public final boolean isLoopbackProxy; // 127.0.0.1/localhost 本地代理流（去广告/种子）
        public final boolean isHlsLike;      // 路径末段为 .m3u8
        public final boolean isLocalFile;    // file:// 或以 / 开头的绝对路径

        UrlInfo(String scheme, String lastSegmentExt, boolean isLoopbackProxy,
                boolean isHlsLike, boolean isLocalFile) {
            this.scheme = scheme;
            this.lastSegmentExt = lastSegmentExt;
            this.isLoopbackProxy = isLoopbackProxy;
            this.isHlsLike = isHlsLike;
            this.isLocalFile = isLocalFile;
        }
    }

    private UrlProtocolClassifier() {
    }

    public static UrlInfo parse(String url) {
        if (url == null || url.isEmpty()) {
            return new UrlInfo(null, "", false, false, false);
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        String scheme = null;
        String path = trimmed;

        int schemeEnd = lower.indexOf("://");
        if (schemeEnd > 0) {
            scheme = lower.substring(0, schemeEnd);
            path = trimmed.substring(schemeEnd + 3);
        } else if (lower.startsWith("magnet:") || lower.startsWith("torrent:")) {
            return new UrlInfo(schemeOf(lower, "magnet"), "", false, false, false);
        }

        boolean isLocalFile = "file".equals(scheme)
                || (scheme == null && trimmed.startsWith("/"));

        // 去除 query 与 fragment 后取路径末段
        String pathOnly = path;
        int cut = pathOnly.indexOf('?');
        if (cut >= 0) pathOnly = pathOnly.substring(0, cut);
        cut = pathOnly.indexOf('#');
        if (cut >= 0) pathOnly = pathOnly.substring(0, cut);

        String lastSegment = pathOnly;
        int slash = lastSegment.lastIndexOf('/');
        if (slash >= 0) lastSegment = lastSegment.substring(slash + 1);

        String ext = "";
        int dot = lastSegment.lastIndexOf('.');
        if (dot >= 0 && dot < lastSegment.length() - 1) {
            ext = lastSegment.substring(dot + 1).toLowerCase(Locale.ROOT);
        }

        // 本地回环代理：去广告清理流 / 种子边下边播
        boolean isLoopbackProxy = ("http".equals(scheme) || "https".equals(scheme))
                && (path.startsWith("127.0.0.1") || path.toLowerCase(Locale.ROOT).startsWith("localhost"));

        boolean isHlsLike = "m3u8".equals(ext);

        return new UrlInfo(scheme, ext, isLoopbackProxy, isHlsLike, isLocalFile);
    }

    private static String schemeOf(String lower, String fallback) {
        int colon = lower.indexOf(':');
        return colon > 0 ? lower.substring(0, colon) : fallback;
    }
}
