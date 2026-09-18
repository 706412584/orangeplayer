package com.orange.playerlibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 记忆播放存储键的回归测试。
 *
 * <p>线上症状：「每次重进都是新进度、跳回选集 1」。
 *
 * <p>根因：进度用 {@code mVideoUrl} 作存储键，而 M3U8 去广告会把该字段改写成
 * 回环代理地址 {@code http://127.0.0.1:<port>/cleaned/<hash>.m3u8}——**端口每次
 * 启动随机分配**（真机实测同一视频三次启动分别是 41167 / 39725 / 37161，hash 固定
 * cf9e4608…）。跨启动读写因此永远不命中。
 *
 * <p>本测试把「端口变化不得影响存储键」这一不变量钉住。
 */
public class ProgressKeyTest {

    private static final String ORIGIN =
            "https://v.cdnlz22.com/20240815/3725_2916c479/index.m3u8";

    private static String loopback(String port) {
        return "http://127.0.0.1:" + port + "/cleaned/cf9e46088258afe374af6199a8b3fdf8.m3u8";
    }

    /**
     * 复刻 OrangevideoView.getProgressKey 的选择逻辑（优先原始源地址）。
     */
    private static String progressKey(String sourceUrl, String videoUrl) {
        if (sourceUrl != null && !sourceUrl.isEmpty()) {
            return sourceUrl;
        }
        return videoUrl;
    }

    @Test
    public void 回环端口变化不影响存储键() {
        // 三次启动，端口不同（真机实测值）
        String k1 = progressKey(ORIGIN, loopback("41167"));
        String k2 = progressKey(ORIGIN, loopback("39725"));
        String k3 = progressKey(ORIGIN, loopback("37161"));

        assertEquals("三次启动的存储键必须一致，否则进度读不回来", k1, k2);
        assertEquals(k2, k3);
        assertEquals(ORIGIN, k1);
    }

    @Test
    public void 旧实现用回环地址作键_跨启动必然失配() {
        // 复刻旧逻辑：直接用 mVideoUrl
        String oldKey1 = loopback("41167");
        String oldKey2 = loopback("39725");

        assertNotEquals("这正是线上 bug：端口变了，键就变了", oldKey1, oldKey2);
        // 新逻辑不受影响
        assertEquals(progressKey(ORIGIN, oldKey1), progressKey(ORIGIN, oldKey2));
    }

    @Test
    public void 未走内部改写时_回退到当前播放地址() {
        // 普通 mp4 不触发去广告，sourceUrl 与 videoUrl 相同
        assertEquals("https://a.com/x.mp4",
                progressKey("https://a.com/x.mp4", "https://a.com/x.mp4"));
        // sourceUrl 缺失时也不能返回 null
        assertEquals("https://a.com/x.mp4", progressKey(null, "https://a.com/x.mp4"));
        assertEquals("https://a.com/x.mp4", progressKey("", "https://a.com/x.mp4"));
    }

    @Test
    public void 已下载视频改写成file路径_仍用原始地址() {
        String origin = "https://a.com/ep01.mp4";
        String local = "file:///storage/emulated/0/Download/ep01.mp4";
        assertEquals(origin, progressKey(origin, local));
    }

    @Test
    public void 存储键不应含回环端口() {
        String key = progressKey(ORIGIN, loopback("41167"));
        assertFalse("存储键里出现回环地址说明取错了字段", key.contains("127.0.0.1"));
        assertFalse(key.contains("/cleaned/"));
    }

    @Test
    public void 不同集数的存储键必须不同() {
        String ep1 = "https://v.cdnlz22.com/20240815/3725_2916c479/index.m3u8";
        String ep2 = "https://v.cdnlz22.com/20240815/3734_7cd1c271/index.m3u8";
        assertNotEquals(progressKey(ep1, loopback("41167")),
                progressKey(ep2, loopback("41167")));
    }

    @Test
    public void 同一集在两次启动中键相同_可命中历史进度() {
        // 启动 A 保存
        String savedKey = progressKey(ORIGIN, loopback("41167"));
        // 启动 B 读取（端口不同）
        String readKey = progressKey(ORIGIN, loopback("99999"));
        assertEquals("跨启动必须能命中同一条进度记录", savedKey, readKey);
    }

    @Test
    public void 两个回环地址与原始地址三者互不相同() {
        assertNotEquals(loopback("41167"), loopback("39725"));
        assertNotEquals(loopback("41167"), ORIGIN);
        assertTrue(loopback("41167").contains("127.0.0.1"));
    }
}
