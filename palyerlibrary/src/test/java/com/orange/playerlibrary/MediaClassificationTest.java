package com.orange.playerlibrary;

import static com.orange.playerlibrary.VideoSniffing.MEDIA_CONTAINER;
import static com.orange.playerlibrary.VideoSniffing.MEDIA_MANIFEST;
import static com.orange.playerlibrary.VideoSniffing.MEDIA_NONE;
import static com.orange.playerlibrary.VideoSniffing.MEDIA_SEGMENT;
import static com.orange.playerlibrary.VideoSniffing.classifyMedia;
import static com.orange.playerlibrary.VideoSniffing.classifyMediaByUrl;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 嗅探媒体判定单元测试。
 *
 * <p>覆盖两个真实踩过的坑（2026-09-19，17c.tw 实测）：
 * <ol>
 *   <li>网页 URL 路径含 {@code /video/} 被误判成视频，而且它请求快、先完成，
 *       会抢先启动收尾倒计时，把仍在途的 m3u8 校验 disconnect 掉
 *       —— PJA110 上结果因此只剩那条 .html。</li>
 *   <li>VOD 站点上千个 .ts 分片与 ts.key 被逐个收进结果列表
 *       —— 黑鲨上 11 条结果里只有 1 条 m3u8 有用。</li>
 * </ol>
 */
public class MediaClassificationTest {

    // ===== 内容类型优先：网页必须判为非媒体，哪怕 URL 里带 /video/ =====

    @Test
    public void 网页路径含video子串_不应判成视频() {
        // 回归：旧实现 isVideoSource(url) 的 contains("video/") 命中这里
        assertEquals(MEDIA_NONE,
                classifyMedia("text/html; charset=utf-8", "https://17c.tw/video/play/162043.html"));
    }

    @Test
    public void 网页即使无内容类型_按后缀也不应判成视频() {
        assertEquals(MEDIA_NONE, classifyMediaByUrl("https://17c.tw/video/play/162043.html"));
        assertEquals(MEDIA_NONE, classifyMedia(null, "https://host/video/play/162043.html"));
    }

    @Test
    public void 脚本样式图片_均非媒体() {
        assertEquals(MEDIA_NONE, classifyMedia("application/javascript", "https://h/a.js"));
        assertEquals(MEDIA_NONE, classifyMedia("text/css", "https://h/a.css"));
        assertEquals(MEDIA_NONE, classifyMedia("image/webp", "https://h/a.webp"));
        assertEquals(MEDIA_NONE, classifyMedia("application/json", "https://h/api"));
        assertEquals(MEDIA_NONE, classifyMedia("font/woff2", "https://h/a.woff2"));
    }

    // ===== 分片与密钥：不进结果列表 =====

    @Test
    public void ts分片_判为分片而非容器() {
        assertEquals(MEDIA_SEGMENT,
                classifyMedia("video/mp2t", "https://video.18j2026.com/stream/202609/13/x/index0.ts"));
        assertEquals(MEDIA_SEGMENT,
                classifyMedia(null, "https://video.18j2026.com/videos/202607/08/x/index0.ts"));
    }

    @Test
    public void fmp4分片_判为分片() {
        assertEquals(MEDIA_SEGMENT, classifyMedia("video/iso.segment", "https://h/seg1.m4s"));
        assertEquals(MEDIA_SEGMENT, classifyMedia(null, "https://h/seg1.m4s"));
    }

    @Test
    public void aes密钥文件_不应判成视频() {
        // 回归：旧实现把 application/octet-stream 直接当视频
        assertEquals(MEDIA_SEGMENT,
                classifyMedia("application/octet-stream",
                        "https://m3u8.cdn202608.com/videos/202607/08/x/ts.key"));
    }

    // ===== 真正的媒体：必须收进结果 =====

    @Test
    public void m3u8清单_判为清单() {
        assertEquals(MEDIA_MANIFEST,
                classifyMedia("application/vnd.apple.mpegurl",
                        "https://m3u8.cdn202608.com/videos/202607/08/x/c778a5/index.m3u8"));
        assertEquals(MEDIA_MANIFEST,
                classifyMedia("application/x-mpegURL", "https://h/index.m3u8"));
        assertEquals(MEDIA_MANIFEST, classifyMediaByUrl("https://h/path/index.m3u8?token=abc"));
    }

    @Test
    public void dash清单_判为清单() {
        assertEquals(MEDIA_MANIFEST, classifyMedia("application/dash+xml", "https://h/manifest.mpd"));
    }

    @Test
    public void mp4容器_判为容器() {
        assertEquals(MEDIA_CONTAINER, classifyMedia("video/mp4", "https://h/a.mp4"));
        assertEquals(MEDIA_CONTAINER, classifyMediaByUrl("https://h/a.mp4"));
        assertEquals(MEDIA_CONTAINER, classifyMediaByUrl("https://h/a.webm"));
        assertEquals(MEDIA_CONTAINER, classifyMediaByUrl("https://h/a.flv"));
        assertEquals(MEDIA_CONTAINER, classifyMediaByUrl("https://h/a.mkv"));
    }

    // ===== 内容类型无信息时才回退 URL =====

    @Test
    public void octet_stream_回退看URL后缀() {
        assertEquals(MEDIA_MANIFEST,
                classifyMedia("application/octet-stream", "https://h/index.m3u8"));
        assertEquals(MEDIA_CONTAINER,
                classifyMedia("application/octet-stream", "https://h/movie.mp4"));
        assertEquals(MEDIA_NONE, classifyMedia("application/octet-stream", "https://h/page.html"));
    }

    @Test
    public void 无扩展名且无内容类型_判为非媒体() {
        assertEquals(MEDIA_NONE, classifyMedia(null, "https://h/opaque/abc123"));
        assertEquals(MEDIA_NONE, classifyMedia("", "https://h/opaque/abc123"));
    }

    // ===== 回归：内容类型优先不能绕过 URL 的分片排除 =====

    /**
     * 回归（审查发现）：fMP4 分片常以 {@code video/mp4} 提供——内容类型与「可播的
     * 整段 mp4」无法区分，只有 URL 后缀能区分。此前 video/* 分支直接返回
     * CONTAINER，把 `.m4s` 的排除逻辑绕过了，于是分片重新淹没结果列表
     * （「11 条只有 1 条有用」换个壳复现）。
     */
    @Test
    public void video_mp4的fMP4分片_应按后缀判为分片() {
        assertEquals(MEDIA_SEGMENT,
                classifyMedia("video/mp4", "https://h/path/seg1.m4s"));
        assertEquals(MEDIA_SEGMENT,
                classifyMedia("video/mp4", "https://h/path/index0.ts"));
    }

    /** 真正的整段 mp4（非分片后缀）仍应是容器。 */
    @Test
    public void video_mp4的整段文件_仍判为容器() {
        assertEquals(MEDIA_CONTAINER, classifyMedia("video/mp4", "https://h/movie.mp4"));
        assertEquals(MEDIA_CONTAINER, classifyMedia("video/mp4", "https://h/clip.m4v"));
    }

    /** application/mp2t 是 TS 分片的另一种写法（不在 video/* 里，容易漏判）。 */
    @Test
    public void application_mp2t_判为分片() {
        assertEquals(MEDIA_SEGMENT, classifyMedia("application/mp2t", "https://h/seg.ts"));
        assertEquals(MEDIA_SEGMENT, classifyMedia("application/mp2t", "https://h/opaque"));
    }

    /**
     * 回归：部分 CDN 用 text/plain 提供 m3u8（伪装成文本绕开网关规则）。
     * 必须在 text/* 的非媒体规则之前按后缀识别，否则整条播放源被丢掉。
     */
    @Test
    public void text_plain提供的m3u8_判为清单() {
        assertEquals(MEDIA_MANIFEST,
                classifyMedia("text/plain", "https://h/path/index.m3u8"));
        assertEquals(MEDIA_MANIFEST,
                classifyMedia("text/plain; charset=utf-8", "https://h/index.m3u8?a=1"));
    }

    /** text/plain 提供的普通文本仍是非媒体（别把伪装识别变成放行一切）。 */
    @Test
    public void text_plain的非清单文件_仍非媒体() {
        assertEquals(MEDIA_NONE, classifyMedia("text/plain", "https://h/readme.txt"));
        assertEquals(MEDIA_NONE, classifyMedia("text/plain", "https://h/page.html"));
    }

    @Test
    public void 查询串不应干扰后缀判定() {
        assertEquals(MEDIA_MANIFEST, classifyMediaByUrl("https://h/index.m3u8?a=1&b=2"));
        assertEquals(MEDIA_NONE, classifyMediaByUrl("https://h/page.html?u=/video/x.mp4"));
    }
}
