package com.orange.playerlibrary.cache;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HLS playlist 重写器：把 playlist 里所有资源 URI 映射为「本地可缓存地址」，
 * 使所有播放内核（ijk/mpv/阿里云/系统）都能经由本地代理复用磁盘缓存。
 *
 * 为什么必须重写：m3u8 内分片是相对 URI，直接交给本地代理后基准路径变为
 * 127.0.0.1，相对分片会 404。故需先按 playlist 的**最终 URL**（跟随重定向后）
 * 解析为绝对 URL，再交给调用方映射。
 *
 * 纯逻辑实现（不依赖 Android），便于单测覆盖 URI 解析边界。
 * 逐行改写而非结构化重建——保留 master 多码率、EXT-X-MEDIA、未知 tag 等，
 * 让播放器自行选流。
 */
public final class HlsPlaylistRewriter {

    /** 绝对 URL → 本地可缓存 URL */
    public interface UrlMapper {
        String map(String absoluteUrl);
    }

    /** EXT-X-KEY / EXT-X-MAP / EXT-X-MEDIA / I-FRAME 等 tag 内的 URI="..." */
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]*)\"");

    private HlsPlaylistRewriter() {
    }

    /**
     * 是否为 master 清单（含多码率变体声明）。master 的裸 URI 行指向子 playlist，
     * 需要继续重写；media 清单的裸 URI 行指向分片，直接映射到分片缓存。
     */
    public static boolean isMasterPlaylist(String content) {
        return content != null && content.contains("#EXT-X-STREAM-INF");
    }

    /**
     * 重写 playlist。
     *
     * @param content      原始 playlist 文本
     * @param baseUrl      playlist 的最终 URL（跟随重定向后），用于解析相对 URI
     * @param master       是否 master 清单
     * @param playlistMapper 子 playlist URI 的映射（master 的变体、EXT-X-MEDIA 等）
     * @param segmentMapper  分片/密钥/init 段 URI 的映射（media 的分片、EXT-X-KEY/MAP）
     * @return 重写后的文本；输入为空时返回 null
     */
    public static String rewrite(String content, String baseUrl, boolean master,
                                 UrlMapper playlistMapper, UrlMapper segmentMapper) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        String[] lines = content.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder(content.length() + 256);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-MAP:")) {
                // 密钥与 fMP4 init 段都是可分片缓存的普通资源
                out.append(mapUriAttr(line, baseUrl, segmentMapper)).append('\n');
            } else if (line.startsWith("#EXT-X-MEDIA:")
                    || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) {
                // 音频轨/iframe 轨 URI 指向子 playlist
                out.append(mapUriAttr(line, baseUrl, playlistMapper)).append('\n');
            } else if (line.startsWith("#")) {
                out.append(line).append('\n');
            } else {
                // 裸 URI 行：master → 子 playlist；media → 分片
                String abs = toAbsoluteUrl(baseUrl, line);
                UrlMapper mapper = master ? playlistMapper : segmentMapper;
                out.append(mapper != null ? mapper.map(abs) : abs).append('\n');
            }
        }
        return out.toString();
    }

    /** 重写 tag 行内的 URI="..." 属性 */
    private static String mapUriAttr(String line, String baseUrl, UrlMapper mapper) {
        if (mapper == null) {
            return line;
        }
        Matcher m = URI_ATTR.matcher(line);
        StringBuilder sb = null;
        int last = 0;
        while (m.find()) {
            String uri = m.group(1);
            if (uri == null || uri.isEmpty()) {
                continue;
            }
            String mapped = mapper.map(toAbsoluteUrl(baseUrl, uri));
            if (sb == null) {
                sb = new StringBuilder(line.length() + 64);
            }
            sb.append(line, last, m.start(1)).append(mapped);
            last = m.end(1);
        }
        if (sb == null) {
            return line;
        }
        sb.append(line, last, line.length());
        return sb.toString();
    }

    /**
     * 相对 URI → 绝对 URI（RFC 3986，基于 playlist 最终 URL）。
     * 覆盖 "//host/x"、"绝对路径"、"相对路径" 等；URI 含非法字符时退化为字符串拼接。
     */
    public static String toAbsoluteUrl(String baseUrl, String uri) {
        if (uri == null || uri.isEmpty()) {
            return uri;
        }
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            return uri;
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            return uri;
        }
        try {
            return URI.create(baseUrl).resolve(uri).toString();
        } catch (Exception ignored) {
            // 非法字符等情况：按路径拼接兜底
        }
        int lastSlash = baseUrl.lastIndexOf('/');
        if (uri.startsWith("/")) {
            int schemeEnd = baseUrl.indexOf("://");
            int hostEnd = schemeEnd < 0 ? -1 : baseUrl.indexOf('/', schemeEnd + 3);
            String origin = hostEnd < 0 ? baseUrl : baseUrl.substring(0, hostEnd);
            return origin + uri;
        }
        return lastSlash < 0 ? uri : baseUrl.substring(0, lastSlash + 1) + uri;
    }
}
