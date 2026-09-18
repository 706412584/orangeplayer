package com.orange.playerlibrary.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * MacCMS {@code vod_play_url} 解析器单测。
 *
 * 数据取自 v3 项目真实 {@code item.json}（剑来，26 集 × 2 条线路）。
 */
public class VodPlayUrlParserTest {

    /** 真实采集源的两条线路名。 */
    private static final String FROM = "liangzi$$$lzm3u8";

    /**
     * 真实 vod_play_url 的形态（为可读性只保留每线路 3 集，结构与线上一致）：
     * 线路 0 = liangzi，地址是 HTML 分享页；线路 1 = lzm3u8，地址是真 m3u8。
     */
    private static final String URL =
            "第01集$https://v.cdnlz22.com/share/94ef7214c4a90790186e255304f8fd1f"
                    + "#第02集$https://v.cdnlz22.com/share/9d752cb08ef466fc480fba981cfa44a1"
                    + "#第03集$https://v.lzcdn26.com/share/25dd30ae03e63faa6b82d5a6a8dadff4"
                    + "$$$"
                    + "第01集$https://v.cdnlz22.com/20240815/3725_2916c479/index.m3u8"
                    + "#第02集$https://v.cdnlz22.com/20240816/3725_aaaa/index.m3u8"
                    + "#第03集$https://v.cdnlz22.com/20240817/3725_bbbb/index.m3u8";

    // ---------- 核心：必须跳过 HTML 分享页线路 ----------

    @Test
    public void 选中可播线路_跳过HTML分享页线路() {
        VodPlayUrlParser.Result r = VodPlayUrlParser.parse(URL, FROM);
        // 线路 0 全是 .html 分享页，必须选线路 1
        assertEquals(1, r.groupIndex);
        assertEquals("lzm3u8", r.groupName);
        assertEquals(3, r.episodes.size());
    }

    @Test
    public void 选中线路的地址全部是可播直链() {
        VodPlayUrlParser.Result r = VodPlayUrlParser.parse(URL, FROM);
        for (VodPlayUrlParser.Episode e : r.episodes) {
            assertTrue("应为可播直链: " + e.url, VodPlayUrlParser.looksPlayable(e.url));
        }
    }

    @Test
    public void 第一条线路就是直链时_不再往后找() {
        String u = "第01集$https://a.com/1.m3u8#第02集$https://a.com/2.m3u8"
                + "$$$第01集$https://b.com/1.m3u8";
        VodPlayUrlParser.Result r = VodPlayUrlParser.parse(u, "A$$$B");
        assertEquals(0, r.groupIndex);
        assertEquals("A", r.groupName);
    }

    // ---------- 剧集名与地址 ----------

    @Test
    public void 剧集名与地址正确切分() {
        VodPlayUrlParser.Result r = VodPlayUrlParser.parse(URL, FROM);
        assertEquals("第01集", r.episodes.get(0).name);
        assertEquals("https://v.cdnlz22.com/20240815/3725_2916c479/index.m3u8",
                r.episodes.get(0).url);
        assertEquals("第03集", r.episodes.get(2).name);
    }

    @Test
    public void 地址含查询串时_只按第一个美元符切分() {
        String u = "第01集$https://a.com/x.m3u8?auth=abc$def";
        List<VodPlayUrlParser.Episode> eps = VodPlayUrlParser.parseOneGroup(u);
        assertEquals(1, eps.size());
        assertEquals("第01集", eps.get(0).name);
        assertEquals("https://a.com/x.m3u8?auth=abc$def", eps.get(0).url);
    }

    @Test
    public void 省略剧集名时_整段当地址() {
        List<VodPlayUrlParser.Episode> eps =
                VodPlayUrlParser.parseOneGroup("https://a.com/1.m3u8");
        assertEquals(1, eps.size());
        assertNull(eps.get(0).name);
        assertEquals("https://a.com/1.m3u8", eps.get(0).url);
    }

    @Test
    public void 名字为null或空_归一化为null() {
        List<VodPlayUrlParser.Episode> eps =
                VodPlayUrlParser.parseOneGroup("null$https://a.com/1.m3u8");
        assertEquals(1, eps.size());
        assertNull(eps.get(0).name);
    }

    // ---------- 线路切分 ----------

    @Test
    public void 两条线路都被切出来() {
        List<List<VodPlayUrlParser.Episode>> g = VodPlayUrlParser.parseAllGroups(URL);
        assertEquals(2, g.size());
        assertEquals(3, g.get(0).size());
        assertEquals(3, g.get(1).size());
    }

    @Test
    public void 空线路被跳过() {
        List<List<VodPlayUrlParser.Episode>> g =
                VodPlayUrlParser.parseAllGroups("$$$第01集$https://a.com/1.m3u8");
        assertEquals(1, g.size());
    }

    @Test
    public void splitGroups_按美元三连切() {
        String[] n = VodPlayUrlParser.splitGroups(FROM);
        assertNotNull(n);
        assertEquals(2, n.length);
        assertEquals("liangzi", n[0]);
        assertEquals("lzm3u8", n[1]);
        assertNull(VodPlayUrlParser.splitGroups(null));
        assertNull(VodPlayUrlParser.splitGroups(""));
    }

    // ---------- 可播性判据 ----------

    @Test
    public void looksPlayable_认可常见媒体扩展名() {
        assertTrue(VodPlayUrlParser.looksPlayable("https://a.com/x.m3u8"));
        assertTrue(VodPlayUrlParser.looksPlayable("https://a.com/x.mp4"));
        assertTrue(VodPlayUrlParser.looksPlayable("https://a.com/x.m3u8?a=1&b=2"));
        assertTrue(VodPlayUrlParser.looksPlayable("http://a.com/x.M3U8"));
        assertTrue(VodPlayUrlParser.looksPlayable("https://a.com/x.flv"));
        assertTrue(VodPlayUrlParser.looksPlayable("https://a.com/x.mkv"));
    }

    @Test
    public void looksPlayable_排除网页与无扩展名路径() {
        assertFalse(VodPlayUrlParser.looksPlayable(
                "https://v.cdnlz22.com/share/94ef7214c4a90790186e255304f8fd1f"));
        assertFalse(VodPlayUrlParser.looksPlayable("https://a.com/play.html"));
        assertFalse(VodPlayUrlParser.looksPlayable("https://a.com/index.php"));
        assertFalse(VodPlayUrlParser.looksPlayable("https://a.com/"));
        assertFalse(VodPlayUrlParser.looksPlayable(""));
        assertFalse(VodPlayUrlParser.looksPlayable(null));
    }

    // ---------- 兜底 ----------

    @Test
    public void 全部线路都不可播时_退回第一段() {
        String u = "第01集$https://a.com/share/xxx$$$第01集$https://b.com/share/yyy";
        VodPlayUrlParser.Result r = VodPlayUrlParser.parse(u, "A$$$B");
        assertEquals(0, r.groupIndex);
        assertEquals(1, r.episodes.size());
    }

    @Test
    public void 空输入返回空结果() {
        assertTrue(VodPlayUrlParser.parse(null, null).isEmpty());
        assertTrue(VodPlayUrlParser.parse("", FROM).isEmpty());
        assertEquals(-1, VodPlayUrlParser.parse("", FROM).groupIndex);
    }

    @Test
    public void 线路名缺失时_groupName为null但不崩() {
        VodPlayUrlParser.Result r = VodPlayUrlParser.parse(URL, null);
        assertNull(r.groupName);
        assertEquals(1, r.groupIndex);   // 选线路逻辑不依赖名字
    }

    @Test
    public void choosePlayableGroup_空列表返回负一() {
        assertEquals(-1, VodPlayUrlParser.choosePlayableGroup(null));
        assertEquals(-1, VodPlayUrlParser.choosePlayableGroup(
                new java.util.ArrayList<List<VodPlayUrlParser.Episode>>()));
    }

    // ---------- parseJson：从完整接口 JSON 提取（含 list 两种形态）----------

    /** 读测试资源（真实采集接口响应的忠实副本）。 */
    private static String readResource(String name) throws Exception {
        java.io.InputStream in = VodPlayUrlParserTest.class.getClassLoader()
                .getResourceAsStream("maccms/" + name);
        assertNotNull("测试资源缺失: " + name, in);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    @Test
    public void parseJson_v3真实形态_list是数组() throws Exception {
        // 这是 v3 项目 item.json 的真实结构，最初的实现只处理了 list 为对象的形态，
        // 会解析出 0 集（表现为「暂无选集」）。本测试把该形态钉住。
        String json = readResource("item_v3_list_array.json");
        VodPlayUrlParser.Result r = VodPlayUrlParser.parseJson(json);
        assertFalse("list 为数组时也必须解析出剧集", r.isEmpty());
        assertEquals(1, r.groupIndex);
        assertEquals("lzm3u8", r.groupName);
        assertEquals(3, r.episodes.size());
        assertEquals("第01集", r.episodes.get(0).name);
        assertEquals("https://v.cdnlz22.com/20240815/3725_2916c479/index.m3u8",
                r.episodes.get(0).url);
    }

    @Test
    public void parseJson_标准苹果CMS形态_list是对象() throws Exception {
        String json = readResource("item_standard_list_object.json");
        VodPlayUrlParser.Result r = VodPlayUrlParser.parseJson(json);
        assertFalse(r.isEmpty());
        assertEquals(1, r.groupIndex);
        assertEquals(2, r.episodes.size());
    }

    @Test
    public void parseJson_vod_play_url在根节点也兼容() {
        String json = "{\"vod_play_from\":\"A\","
                + "\"vod_play_url\":\"第01集$https://a.com/1.m3u8\"}";
        VodPlayUrlParser.Result r = VodPlayUrlParser.parseJson(json);
        assertEquals(1, r.episodes.size());
        assertEquals("https://a.com/1.m3u8", r.episodes.get(0).url);
    }

    @Test
    public void parseJson_坏输入不抛异常_返回空结果() {
        assertTrue(VodPlayUrlParser.parseJson(null).isEmpty());
        assertTrue(VodPlayUrlParser.parseJson("").isEmpty());
        assertTrue(VodPlayUrlParser.parseJson("not json at all").isEmpty());
        assertTrue(VodPlayUrlParser.parseJson("{}").isEmpty());
        assertTrue(VodPlayUrlParser.parseJson("{\"list\":[]}").isEmpty());
        assertTrue(VodPlayUrlParser.parseJson("{\"list\":{\"data\":[]}}").isEmpty());
    }

    @Test
    public void parseJson_两形态结果一致() throws Exception {
        // 同一份数据分别用 list 数组 / list 对象包装，结果应等价
        String arr = readResource("item_v3_list_array.json");
        VodPlayUrlParser.Result a = VodPlayUrlParser.parseJson(arr);
        VodPlayUrlParser.Result b = VodPlayUrlParser.parse(
                "第01集$https://v.cdnlz22.com/share/94ef7214c4a90790186e255304f8fd1f"
                        + "#第02集$https://v.cdnlz22.com/share/9d752cb08ef466fc480fba981cfa44a1"
                        + "#第03集$https://v.lzcdn26.com/share/25dd30ae03e63faa6b82d5a6a8dadff4"
                        + "$$$第01集$https://v.cdnlz22.com/20240815/3725_2916c479/index.m3u8"
                        + "#第02集$https://v.cdnlz22.com/20240815/3734_7cd1c271/index.m3u8"
                        + "#第03集$https://v.lzcdn26.com/20250426/15616_67cb4081/index.m3u8",
                FROM);
        assertEquals(b.groupIndex, a.groupIndex);
        assertEquals(b.episodes.size(), a.episodes.size());
        assertEquals(b.episodes.get(0).url, a.episodes.get(0).url);
    }
}
