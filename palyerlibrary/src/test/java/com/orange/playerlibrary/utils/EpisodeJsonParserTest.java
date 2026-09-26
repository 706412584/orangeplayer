package com.orange.playerlibrary.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;

/**
 * 宿主选集数组解析单元测试。
 *
 * <p>回归背景（真机日志定位）：
 *
 * <ol>
 *   <li><b>null 槽位必须逐条容忍</b>。宿主传进来的 {@code JSONArray} 里混着 Java null
 *       槽位（真机日志两次运行分别 48 / 35 条），{@code getJSONObject(i)} 对它会抛
 *       异常，所以任何「整批一个 try/catch」的写法都会在中途丢集。
 *       真机日志（原件 {@code docs/evidence/device-v3-episodes-20260920*.logcat.txt}）：
 *       <pre>
 *       addVideosFromJsonArray: 第 16 条解析失败，跳过：Value at 15 is null.
 *       </pre>
 *       <b>注意区分</b>：真机上是否**确实**因整批中止而丢集**没有直接证据**
 *       （01:48 那版不打印列表长度）；「整批写法会丢集」这一点是由 demo 的
 *       A/B 对照证明的（同数组：整批 15 集 / 逐条 21 集）。数组长度在两次运行间
 *       不同（69 / 59），**不是固定值**，测试不得依赖具体长度。</li>
 *
 *   <li><b>缺 name 变成空白标题</b>。{@code optString("name")} 对值为 JSON null 的键
 *       返回字面量 {@code "null"}（Android 内置 org.json；Maven 版返回 {@code ""}），
 *       对缺失键返回 {@code ""}；两者入库就是空白选集。**注意**：真机上宿主源码
 *       本身会跳过空名条目，所以「空白标题」是否真由此产生未经证实——这里测的是
 *       解析器自身的防御行为。</li>
 * </ol>
 */
public class EpisodeJsonParserTest {

    private static JSONArray arr(Object... objs) {
        JSONArray a = new JSONArray();
        for (Object o : objs) {
            a.put(o);
        }
        return a;
    }

    private static JSONObject ep(String name, String url) {
        JSONObject o = new JSONObject();
        try {
            if (name != null) {
                o.put("name", name);
            }
            if (url != null) {
                o.put("url", url);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    // ===== 核心回归：null 槽位不能中止整批 =====

    /**
     * 回归：数组里夹着 null 槽位时，**后续有效条目仍要被解析出来**。
     * 这正是真机「26 集只显示 0-9 集」的成因。
     */
    @Test
    public void null槽位不中止整批_后续条目仍被解析() {
        JSONArray a = new JSONArray();
        a.put(ep("第01集", "http://a/1.m3u8"));
        a.put(JSONObject.NULL);
        a.put(JSONObject.NULL);
        a.put(ep("第02集", "http://a/2.m3u8"));
        a.put(JSONObject.NULL);
        a.put(ep("第03集", "http://a/3.m3u8"));

        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(a);

        assertEquals("三条有效条目都要在", 3, r.items.size());
        assertEquals(3, r.skippedNull);
        assertEquals("第01集", r.items.get(0).get("name"));
        assertEquals("第02集", r.items.get(1).get("name"));
        assertEquals("第03集", r.items.get(2).get("name"));
        assertEquals("http://a/3.m3u8", r.items.get(2).get("url"));
    }

    /**
     * 回归：复刻真机形状——前 15 个有效、中间一大段 null、末尾零散有效。
     * 有效条目必须一条不少（旧实现在第 16 个槽位就整批中止，只剩 15 条）。
     */
    @Test
    public void 复刻真机形状_有效条目一条不少() {
        JSONArray a = new JSONArray();
        // 0-14 有效
        for (int i = 0; i < 15; i++) {
            a.put(ep("第" + (i + 1) + "集", "http://a/" + i + ".m3u8"));
        }
        // 15-25 null
        for (int i = 0; i < 11; i++) {
            a.put(JSONObject.NULL);
        }
        // 26 有效
        a.put(ep("第16集", "http://a/15.m3u8"));
        // 27-32 null
        for (int i = 0; i < 6; i++) {
            a.put(JSONObject.NULL);
        }
        // 33 有效
        a.put(ep("第17集", "http://a/16.m3u8"));

        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(a);

        assertEquals("有效条目 = 15 + 2", 17, r.items.size());
        assertEquals(17, r.skippedNull);
        assertEquals(34, r.total);
        // 顺序保持
        assertEquals("第1集", r.items.get(0).get("name"));
        assertEquals("第15集", r.items.get(14).get("name"));
        assertEquals("第16集", r.items.get(15).get("name"));
        assertEquals("第17集", r.items.get(16).get("name"));
    }

    /** 全是 null 槽位 → 空结果，不抛异常。 */
    @Test
    public void 全null槽位_返回空不抛异常() {
        JSONArray a = arr(JSONObject.NULL, JSONObject.NULL, JSONObject.NULL);
        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(a);
        assertTrue(r.isEmpty());
        assertEquals(3, r.skippedNull);
        assertEquals(0, r.items.size());
    }

    // ===== 空白标题回归 =====

    /** 回归：name 缺失 / 为空 → 回退「第N集」，不能让面板出现空白项。 */
    @Test
    public void name缺失或为空_回退第N集() {
        JSONArray a = new JSONArray();
        a.put(ep(null, "http://a/1.m3u8"));   // 键缺失
        a.put(ep("", "http://a/2.m3u8"));     // 空串
        a.put(ep("   ", "http://a/3.m3u8"));  // 全空白
        a.put(ep("真实标题", "http://a/4.m3u8"));

        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(a);

        assertEquals(4, r.items.size());
        assertEquals("第1集", r.items.get(0).get("name"));
        assertEquals("第2集", r.items.get(1).get("name"));
        assertEquals("第3集", r.items.get(2).get("name"));
        assertEquals("真实标题", r.items.get(3).get("name"));
    }

    /**
     * name 的**值**是 JSON null → 必须回退成「第N集」。
     *
     * <p><b>本用例在单测环境里走的是「空串」分支，不是「字面量 null」分支</b>：
     * 单测依赖 Maven {@code org.json:json:20240303}，它的 {@code optString}
     * 对 JSON null 返回 {@code ""}；而设备上的 **Android 内置 org.json** 返回
     * 字面量 {@code "null"}。两条分支在解析器里都归到「回退」，所以断言仍成立，
     * 但**真机的字面量 null 分支没有单测覆盖**——它由下面
     * {@link #name字符串null_回退第N集} 以显式字符串间接覆盖，并在真机
     * （demo 的 {@code json_probe}）上实测确认过。
     */
    @Test
    public void name值为JSONnull_回退第N集() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", JSONObject.NULL);
            o.put("url", "http://a/1.m3u8");
        } catch (Exception ignored) {
        }
        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(arr(o));
        assertEquals(1, r.items.size());
        assertEquals("第1集", r.items.get(0).get("name"));
    }

    /** name 恰是字符串 "null"（宿主把 JSON null 转成字符串）同样回退。 */
    @Test
    public void name字符串null_回退第N集() {
        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(arr(ep("null", "http://a/1.m3u8")));
        assertEquals("第1集", r.items.get(0).get("name"));
    }

    /**
     * 回退序号按「有效条目序号」而非原数组下标：
     * 数组里夹着 null 槽位时，用原下标会让标题数字跳号（第1集、第3集）。
     */
    @Test
    public void 回退序号不因null槽位跳号() {
        JSONArray a = new JSONArray();
        a.put(ep(null, "http://a/1.m3u8"));
        a.put(JSONObject.NULL);
        a.put(JSONObject.NULL);
        a.put(ep(null, "http://a/2.m3u8"));

        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(a);

        assertEquals(2, r.items.size());
        assertEquals("第1集", r.items.get(0).get("name"));
        assertEquals("第2集", r.items.get(1).get("name"));
    }

    /** 键缺失（不是 JSON null）同样回退。 */
    @Test
    public void 缺name键_回退第N集() {
        JSONObject o = new JSONObject();
        try {
            o.put("url", "http://a/1.m3u8");
        } catch (Exception ignored) {
        }
        JSONArray a = arr(o);
        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(a);
        assertEquals("第1集", r.items.get(0).get("name"));
    }

    /** 标题两端空白要裁掉（宿主拼串常带 \r）。 */
    @Test
    public void 标题与地址去两端空白() {
        JSONArray a = arr(ep("  第01集\r", "  http://a/1.m3u8\n "));
        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(a);
        assertEquals("第01集", r.items.get(0).get("name"));
        assertEquals("http://a/1.m3u8", r.items.get(0).get("url"));
    }

    // ===== url 判空 =====

    /** url 为 JSON null / 空串 / 键缺失 → 跳过并计数（不能入库，否则点播报错）。 */
    @Test
    public void url无效_跳过并计数() {
        JSONArray a = new JSONArray();
        a.put(ep("第1集", null));      // 键缺失（helper 对 null 不 put）
        a.put(ep("第2集", ""));        // 空串
        a.put(ep("第3集", "  "));      // 全空白（trim 后为空）
        JSONObject noUrl = new JSONObject();
        try {
            noUrl.put("name", "第4集");
        } catch (Exception ignored) {
        }
        a.put(noUrl);                  // 键缺失
        a.put(ep("第5集", "http://a/5.m3u8"));

        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(a);

        assertEquals("只有第5条有效", 1, r.items.size());
        assertEquals("http://a/5.m3u8", r.items.get(0).get("url"));
        assertEquals(4, r.skippedEmptyUrl);
    }

    /**
     * url 的**值**是 JSON null（不是键缺失）→ 必须判为无效。
     *
     * <p>与 {@link #url无效_跳过并计数} 测的是不同分支（键在、值 null）。
     * 同 {@link #name值为JSONnull_回退第N集}：Maven 版返回 {@code ""}、
     * Android 版返回字面量 {@code "null"}，两条都归到「跳过」，断言成立；
     * 真机分支由 {@link #url字面量null字符串_跳过} 间接覆盖。
     */
    @Test
    public void url值为JSONnull_判为无效() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", "第1集");
            o.put("url", JSONObject.NULL);   // 键在，值是 JSON null
        } catch (Exception ignored) {
        }
        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(arr(o));
        assertTrue("值为 JSON null 的 url 必须判无效", r.isEmpty());
        assertEquals(1, r.skippedEmptyUrl);
    }

    /** url 恰好是字符串 "null"（宿主把 JSON null 当字符串传）也要判无效。 */
    @Test
    public void url字面量null字符串_跳过() {
        JSONArray a = arr(ep("第1集", "null"));
        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(a);
        assertTrue(r.isEmpty());
        assertEquals(1, r.skippedEmptyUrl);
    }

    // ===== 非对象元素 =====

    /** 数组里混入字符串/数字/嵌套数组 → 计为非对象，不中止整批。 */
    @Test
    public void 非对象元素_跳过不中止() {
        JSONArray a = new JSONArray();
        a.put(ep("第1集", "http://a/1.m3u8"));
        a.put("我是个字符串");
        a.put(42);
        a.put(new JSONArray());
        a.put(ep("第2集", "http://a/2.m3u8"));

        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(a);

        assertEquals(2, r.items.size());
        assertEquals(3, r.skippedBadEntry);
        assertEquals("第1集", r.items.get(0).get("name"));
        assertEquals("第2集", r.items.get(1).get("name"));
    }

    // ===== isSpecial / 边界 =====

    @Test
    public void isSpecial为true_带独立标记() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", "独立源");
            o.put("url", "http://a/x.m3u8");
            o.put("isSpecial", true);
        } catch (Exception ignored) {
        }
        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(arr(o));
        assertEquals("独立", r.items.get(0).get("dlsp"));
    }

    @Test
    public void isSpecial为false_无独立标记() {
        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(arr(ep("第1集", "http://a/1.m3u8")));
        assertFalse(r.items.get(0).containsKey("dlsp"));
    }

    /** 每条都带 headers（下游 setUrl 依赖，缺失会 NPE）。 */
    @Test
    public void 未指定headers_不预置由上层补() {
        // 解析器不负责补 headers（setVideoList 会统一补），这里只保证不塞脏值
        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(arr(ep("第1集", "http://a/1.m3u8")));
        HashMap<String, Object> item = r.items.get(0);
        assertFalse(item.containsKey("headers"));
    }

    @Test
    public void null数组_返回空结果() {
        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(null);
        assertTrue(r.isEmpty());
        assertEquals(0, r.total);
        assertEquals(0, r.skipped());
    }

    @Test
    public void 空数组_返回空结果() {
        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(new JSONArray());
        assertTrue(r.isEmpty());
        assertEquals(0, r.total);
    }

    /** skipped() 是三项之和，日志汇总依赖它。 */
    @Test
    public void skipped汇总三类() {
        JSONArray a = new JSONArray();
        a.put(JSONObject.NULL);          // null 槽位
        a.put("字符串");                  // 非对象
        a.put(ep("无地址", ""));          // 空 url
        a.put(ep("第4集", "http://a/4.m3u8"));

        EpisodeJsonParser.Result r = EpisodeJsonParser.parse(a);

        assertEquals(1, r.skippedNull);
        assertEquals(1, r.skippedBadEntry);
        assertEquals(1, r.skippedEmptyUrl);
        assertEquals(3, r.skipped());
        assertEquals(1, r.items.size());
    }

    // ===== normalize =====

    @Test
    public void normalize处理空与字面量null() {
        assertEquals("", EpisodeJsonParser.normalize(null));
        assertEquals("", EpisodeJsonParser.normalize("null"));
        assertEquals("", EpisodeJsonParser.normalize("   "));
        assertEquals("abc", EpisodeJsonParser.normalize("  abc  "));
        // 只是含 "null" 子串的正常值不能被误伤
        assertEquals("null集", EpisodeJsonParser.normalize("null集"));
    }

    /**
     * 回归：必须先 trim 再比 "null"。
     * 若先比字面量再 trim，{@code " null "} 会漏过比较、trim 后变成 "null" 当标题显示。
     */
    @Test
    public void normalize先trim再比null() {
        assertEquals("", EpisodeJsonParser.normalize(" null "));
        assertEquals("", EpisodeJsonParser.normalize("\rnull"));
        assertEquals("", EpisodeJsonParser.normalize("null\n"));
        assertEquals("", EpisodeJsonParser.normalize("\t null \r\n"));
    }
}
