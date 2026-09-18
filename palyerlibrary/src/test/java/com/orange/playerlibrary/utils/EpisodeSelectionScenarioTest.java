package com.orange.playerlibrary.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 端到端场景测试：v3 项目「点下一集提示已经是最后一集了」的完整复现与修复验证。
 *
 * <p>把两个真实环节串起来跑：
 * <ol>
 *   <li>采集接口 JSON → {@link VodPlayUrlParser#parseJson} 得到选集列表</li>
 *   <li>播放中地址被 M3U8 去广告改写 → {@link EpisodeUrlMatcher} 反查当前集下标</li>
 * </ol>
 *
 * <p>这个组合正是线上失效的路径：任一环出错都会表现为「下一集点不动」。
 */
public class EpisodeSelectionScenarioTest {

    private static final String ITEM_JSON = "maccms/item_v3_list_array.json";

    /** 去广告后实际在播的回环代理地址（取自真机日志）。 */
    private static final String PROXIED =
            "http://127.0.0.1:41167/cleaned/cf9e46088258afe374af6199a8b3fdf8.m3u8";

    private static String readResource(String name) throws Exception {
        InputStream in = EpisodeSelectionScenarioTest.class.getClassLoader()
                .getResourceAsStream(name);
        assertNotNull("测试资源缺失: " + name, in);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    /** 模拟 VideoEventManager.findCurrentEpisodeIndex 的查找。 */
    private static int findIndex(List<VodPlayUrlParser.Episode> eps,
                                 String sourceUrl, String playingUrl) {
        for (int i = 0; i < eps.size(); i++) {
            if (EpisodeUrlMatcher.matches(eps.get(i).url, sourceUrl, playingUrl)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void 场景_去广告改写地址后仍能定位当前集并前进() throws Exception {
        // 1) iyu 传入采集 JSON，SDK 解析出可播线路
        VodPlayUrlParser.Result r = VodPlayUrlParser.parseJson(readResource(ITEM_JSON));
        assertFalse("选集必须解析出来", r.isEmpty());
        List<VodPlayUrlParser.Episode> eps = r.episodes;
        assertEquals(3, eps.size());

        // 2) 第 1 集开始播放，调用方传入的源地址就是列表里的第 0 条
        String sourceUrl = eps.get(0).url;

        // 3) 播放中 M3U8 去广告把实际地址换成了回环代理地址
        //    旧实现只比 playingUrl → 失配 → 误报「已经是最后一集了」
        assertEquals(-1, findIndex(eps, null, PROXIED));
        assertEquals(0, findIndex(eps, sourceUrl, PROXIED));

        // 4) 当前集不是最后一集，应当能前进到下一集
        int idx = findIndex(eps, sourceUrl, PROXIED);
        assertTrue("应当存在下一集", idx >= 0 && idx < eps.size() - 1);
        assertEquals(eps.get(1).url, eps.get(idx + 1).url);
    }

    @Test
    public void 场景_切到最后一集时正确报告没有下一集() throws Exception {
        VodPlayUrlParser.Result r = VodPlayUrlParser.parseJson(readResource(ITEM_JSON));
        List<VodPlayUrlParser.Episode> eps = r.episodes;
        int last = eps.size() - 1;

        String sourceUrl = eps.get(last).url;
        int idx = findIndex(eps, sourceUrl, PROXIED);
        assertEquals(last, idx);
        // 最后一集确实没有下一集——此时的「已经是最后一集了」是正确行为
        assertFalse(idx >= 0 && idx < eps.size() - 1);
    }

    @Test
    public void 场景_选集列表只含可播线路_不含HTML分享页() throws Exception {
        VodPlayUrlParser.Result r = VodPlayUrlParser.parseJson(readResource(ITEM_JSON));
        for (VodPlayUrlParser.Episode e : r.episodes) {
            assertTrue("列表里不应有 HTML 分享页地址: " + e.url,
                    VodPlayUrlParser.looksPlayable(e.url));
            assertFalse("不应混入 /share/ 分享页: " + e.url, e.url.contains("/share/"));
        }
    }

    @Test
    public void 场景_逐集前进全程可定位() throws Exception {
        // 从第 1 集一路点到最后一集，每一步都必须能定位到当前集
        VodPlayUrlParser.Result r = VodPlayUrlParser.parseJson(readResource(ITEM_JSON));
        List<VodPlayUrlParser.Episode> eps = r.episodes;

        for (int i = 0; i < eps.size(); i++) {
            String sourceUrl = eps.get(i).url;
            int idx = findIndex(eps, sourceUrl, PROXIED);
            assertEquals("第 " + (i + 1) + " 集应能定位", i, idx);
        }
    }

    @Test
    public void 场景_未改写地址的普通MP4也能定位() throws Exception {
        // 不触发去广告的普通 mp4：sourceUrl 与 playingUrl 相同
        List<VodPlayUrlParser.Episode> eps = new ArrayList<>();
        eps.add(new VodPlayUrlParser.Episode("第1集", "https://a.com/1.mp4"));
        eps.add(new VodPlayUrlParser.Episode("第2集", "https://a.com/2.mp4"));

        assertEquals(0, findIndex(eps, "https://a.com/1.mp4", "https://a.com/1.mp4"));
        assertEquals(1, findIndex(eps, "https://a.com/2.mp4", "https://a.com/2.mp4"));
    }

    @Test
    public void 场景_已下载视频被改写成file路径也能定位() throws Exception {
        List<VodPlayUrlParser.Episode> eps = new ArrayList<>();
        eps.add(new VodPlayUrlParser.Episode("第1集", "https://a.com/1.mp4"));
        eps.add(new VodPlayUrlParser.Episode("第2集", "https://a.com/2.mp4"));

        int idx = findIndex(eps, "https://a.com/2.mp4",
                "file:///storage/emulated/0/Download/2.mp4");
        assertEquals(1, idx);
    }
}
