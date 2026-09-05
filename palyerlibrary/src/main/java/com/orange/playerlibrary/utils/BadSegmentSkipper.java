package com.orange.playerlibrary.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exo 坏段跳段自愈（纯逻辑，无 Android 依赖便于单测）。
 * <p>
 * 场景：m3u8 中部分 TS 段指向不可解析域名（NXDOMAIN）或返回 4xx/5xx，
 * Media3 的 LoadErrorHandlingPolicy 只能重试或放弃，无法像 FFmpeg 一样
 * 静默跳过坏段。本类解析 m3u8 的 EXTINF 累计时间轴，从错误位置向后
 * 找到第一个健康段，返回其起始时间用于重新 prepare + seek。
 */
public final class BadSegmentSkipper {

    /** 最多向后检查的段数（防止坏段过多时长时间阻塞） */
    private static final int MAX_CHECK_SEGMENTS = 20;

    /** 解析后的段：起始时间（毫秒）与可检查的健康状态 */
    public static class Segment {
        public final long startTimeMs;
        public final long durationMs;
        public final String url;
        public String host; // http(s) 段才有

        Segment(long startTimeMs, long durationMs, String url) {
            this.startTimeMs = startTimeMs;
            this.durationMs = durationMs;
            this.url = url;
        }
    }

    /** 跳段结果 */
    public static class SkipResult {
        public final boolean success;
        /** 成功时：健康段起始时间（毫秒）；失败时为 -1 */
        public final long seekPositionMs;
        /** 跳过的段数（诊断用） */
        public final int skippedSegments;

        SkipResult(boolean success, long seekPositionMs, int skippedSegments) {
            this.success = success;
            this.seekPositionMs = seekPositionMs;
            this.skippedSegments = skippedSegments;
        }

        static SkipResult failure() {
            return new SkipResult(false, -1L, 0);
        }
    }

    private BadSegmentSkipper() {
    }

    /**
     * 解析 m3u8 文本为段列表（含每段起始累计时间）。
     * 仅处理媒体播放列表（含 EXTINF）；master playlist 返回空。
     */
    public static List<Segment> parseSegments(String m3u8Content, String m3u8Url) {
        List<Segment> segments = new ArrayList<>();
        if (m3u8Content == null || m3u8Content.isEmpty()) {
            return segments;
        }
        String baseUrlPath = extractBaseUrlPath(m3u8Url);
        String[] lines = m3u8Content.split("\\r?\\n");
        long cumulativeMs = 0;
        double pendingDuration = -1;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                if (line.startsWith("#EXTINF:")) {
                    pendingDuration = parseExtinfDuration(line);
                }
                continue;
            }
            // 非 # 开头 = 段 URL
            double duration = pendingDuration >= 0 ? pendingDuration : 0;
            pendingDuration = -1;
            String absoluteUrl = toAbsoluteUrl(line, baseUrlPath);
            Segment seg = new Segment(cumulativeMs, (long) (duration * 1000), absoluteUrl);
            seg.host = extractHost(absoluteUrl);
            segments.add(seg);
            cumulativeMs += (long) (duration * 1000);
        }
        return segments;
    }

    /**
     * 从出错位置向后找第一个健康段。
     * 健康 = 域名可解析；坏 = 域名确定不存在（NXDOMAIN，DNS 立即否定应答）。
     * DNS 超时/临时失败视为"未知"，保守判健康（不跳段，放行上层换核），
     * 避免网络抖动把好源误判为坏源导致误跳。
     * <p>
     * 调用方应已在后台线程（含 m3u8 下载），串行检查各 host（通常仅 1-3 个）。
     *
     * @param fromPositionMs 出错时的播放位置（毫秒）
     */
    public static SkipResult findNextHealthySegment(List<Segment> segments,
            long fromPositionMs, ExecutorService executor) {
        if (segments == null || segments.isEmpty()) {
            return SkipResult.failure();
        }
        // 定位出错段：起始时间 <= position 的最后一段（即当前正在加载/播放的段）
        int startIndex = 0;
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).startTimeMs <= fromPositionMs) {
                startIndex = i;
            } else {
                break;
            }
        }
        int checkEnd = Math.min(segments.size(), startIndex + 1 + MAX_CHECK_SEGMENTS);

        // 串行检查范围内各 host（去重），只有确定 NXDOMAIN 才判坏
        Set<String> checkedHosts = new HashSet<>();
        Set<String> badHosts = new HashSet<>();
        for (int i = startIndex; i < checkEnd; i++) {
            Segment seg = segments.get(i);
            if (seg.host == null || !checkedHosts.add(seg.host)) {
                continue;
            }
            if (isHostNxdomain(seg.host)) {
                badHosts.add(seg.host);
            }
        }
        if (badHosts.isEmpty()) {
            return SkipResult.failure(); // 无确定坏 host：不是段级问题，放行换核
        }

        // 从 startIndex 起找第一个 host 健康且起始时间晚于出错位置的段
        for (int i = startIndex; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            if (seg.host != null && badHosts.contains(seg.host)) {
                continue;
            }
            // 起始时间必须晚于出错位置（跳过当前坏段区间）
            if (seg.startTimeMs <= fromPositionMs) {
                continue;
            }
            int skipped = countBadUntil(segments, i, badHosts, fromPositionMs);
            return new SkipResult(true, seg.startTimeMs, skipped);
        }
        return SkipResult.failure();
    }

    /** 统计 [fromPositionMs, healthyIndex) 区间内坏段数 */
    private static int countBadUntil(List<Segment> segments, int healthyIndex,
            Set<String> badHosts, long fromPositionMs) {
        int count = 0;
        for (int i = 0; i < healthyIndex; i++) {
            Segment seg = segments.get(i);
            if (seg.startTimeMs > fromPositionMs && seg.host != null && badHosts.contains(seg.host)) {
                count++;
            }
        }
        return count;
    }

    /** host 可解析（含超时等临时失败——临时失败不算坏，见 isHostNxdomain） */
    static boolean isHostHealthy(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            return addresses.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * host 是否确定不存在（NXDOMAIN）。
     * UnknownHostException 且非本地网络瞬断：Java 层无法直接区分 NXDOMAIN 与超时，
     * 用"重试一次仍失败"近似——NXDOMAIN 是 DNS 立即否定应答，两次都失败大概率是域名不存在；
     * 网络瞬断通常第二次能成功（且此前 m3u8 本身刚下载成功，网络是通的）。
     */
    static boolean isHostNxdomain(String host) {
        for (int i = 0; i < 2; i++) {
            try {
                InetAddress.getAllByName(host);
                return false; // 可解析 → 健康
            } catch (Exception e) {
                if (i == 0) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false; // 中断按健康处理，放行换核
                    }
                }
            }
        }
        return true;
    }

    /** 下载 m3u8 文本（复用 M3U8AdRemover 的下载约定） */
    public static String fetchM3U8(String m3u8Url, Map<String, String> headers) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(m3u8Url);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    String key = entry.getKey();
                    if (key != null && !key.isEmpty()
                            && !"allowCrossProtocolRedirects".equals(key)) {
                        connection.setRequestProperty(key, entry.getValue());
                    }
                }
            }
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return null;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static String extractBaseUrlPath(String m3u8Url) {
        if (m3u8Url == null) {
            return "";
        }
        int lastSlash = m3u8Url.lastIndexOf('/');
        if (lastSlash > 0) {
            return m3u8Url.substring(0, lastSlash + 1);
        }
        return m3u8Url;
    }

    static String toAbsoluteUrl(String segmentUrl, String baseUrlPath) {
        if (segmentUrl == null) {
            return "";
        }
        if (segmentUrl.startsWith("http://") || segmentUrl.startsWith("https://")) {
            return segmentUrl;
        }
        if (segmentUrl.startsWith("/")) {
            try {
                java.net.URL url = new java.net.URL(baseUrlPath);
                String domain = url.getProtocol() + "://" + url.getHost();
                if (url.getPort() != -1) {
                    domain += ":" + url.getPort();
                }
                return domain + segmentUrl;
            } catch (Exception e) {
                return baseUrlPath + segmentUrl.substring(1);
            }
        }
        return baseUrlPath + segmentUrl;
    }

    static String extractHost(String url) {
        if (url == null) {
            return null;
        }
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    static double parseExtinfDuration(String line) {
        try {
            int colon = line.indexOf(':');
            int comma = line.indexOf(',');
            if (colon > 0 && comma > colon) {
                return Double.parseDouble(line.substring(colon + 1, comma));
            }
            // 无逗号形式: #EXTINF:4.000
            if (colon > 0) {
                return Double.parseDouble(line.substring(colon + 1));
            }
        } catch (Exception ignored) {
        }
        return 0;
    }
}
