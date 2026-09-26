package com.orange.playerlibrary.utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * 宿主传入的选集 JSON 数组解析器（纯函数，无 Android 依赖）。
 *
 * <h3>为什么要有这个类</h3>
 * 宿主的选集数组**不保证每个槽位都是有效对象**。真机证据（PJA110，
 * 包 {@code com.oragnevideo.play2}，两次独立运行）：
 *
 * <pre>
 * 2026-09-20 01:48   addVideosFromJsonArray: 第 16 条解析失败，跳过：Value at 15 is null.
 *                    （共 48 条 null 槽位，最大下标 69）
 * 2026-09-20 12:15   addVideosFromJsonArray: 第 17 条解析失败，跳过：Value at 16 is null.
 *                    （共 35 条 null 槽位，最大下标 59）
 * </pre>
 *
 * <p>原始 logcat 存于 {@code docs/evidence/device-v3-episodes-20260920*.logcat.txt}。
 *
 * <p><b>数组长度不是固定值</b>：两次运行分别是 69 与 59 个槽位（有效 21 与 25 条）。
 * 所以**不要**把它当常量写进测试或断言——本类只依赖「数组里可能有 null 槽位」
 * 这一条，对长度不做任何假设。
 *
 * <p><b>那些槽位是 Java {@code null}，不是 {@code JSONObject.NULL}</b>——两者的
 * 异常消息不同，可据此区分。依据是 **AOSP 源码**（本地 SDK 内即有：
 * {@code $ANDROID_HOME/sources/android-28/org/json/JSON.java:96-104}）：
 *
 * <pre>
 * public static JSONException typeMismatch(Object indexOrName, Object actual, String requiredType) {
 *     if (actual == null) {
 *         throw new JSONException("Value at " + indexOrName + " is null.");
 *     } else {
 *         throw new JSONException("Value " + actual + " at " + indexOrName
 *                 + " of type " + actual.getClass().getName() + " cannot be converted to " + requiredType);
 *     }
 * }
 * </pre>
 *
 * <p>{@code JSONObject.NULL} 的 {@code toString()} 是字面量 {@code "null"}（所以消息里
 * 出现 {@code "Value null at ..."}），而 Java null 走第一条分支——日志里是后者，
 * 故宿主塞的是 Java null。这与设备探针实测一致，且**不必信任探针**：直接看 AOSP 源码即可复核。
 *
 * <p><b>别用 Maven 的 {@code org.json:json} 去反驳这条</b>：它是另一套实现，
 * 消息格式完全不同（{@code JSONArray[N] is not a JSONObject (...)}），
 * 而设备上跑的是 Android 内置的那套。
 *
 * <p>好消息是 {@code array.isNull(i)} 对**两者都**返回 true，所以一个判据就能同时
 * 覆盖。依据同样是 AOSP 源码（{@code sources/android-28/org/json/JSONArray.java:273-276}）：
 *
 * <pre>
 * public boolean isNull(int index) {
 *     Object value = opt(index);
 *     return value == null || value == JSONObject.NULL;   // 两种 null 都算
 * }
 * </pre>
 *
 * <p><b>已证实与未证实</b>（写清楚以免后人误信——这里踩过坑，请照实读）：
 * <ul>
 *   <li><b>已证实</b>：宿主传进来的数组**确实含 Java null 槽位**，数量在两次运行中
 *       分别为 48 与 35 条。依据：真机日志 + AOSP 源码（见上）。</li>
 *   <li><b>已证实</b>：null 槽位会让 {@code getJSONObject(i)} 抛异常，因此
 *       **任何「整批一个 try/catch」的写法都会在中途丢集**。依据：demo 的 A/B 对照
 *       （同一数组：整批写法 15 集 / 逐条写法 21 集）。</li>
 *   <li><b>已证实</b>：01:48 那次真机运行**没有中止**——它的日志是逐条格式
 *       （{@code 第 N 条解析失败，跳过}），而整批写法只会打一条
 *       {@code 解析JSON数组失败}。所以那次跑的是逐条容错版本。</li>
 *   <li><b>未证实</b>：用户报的「丢集」是否由整批中止造成。整批写法确实会丢集
 *       （上一条已证），但**没有任何真机日志记录过那个版本**——01:48 已是逐条版本
 *       （且那一版不打印列表长度），12:15 也是逐条版本（拿到 25 集）。
 *       这是一个**合理解释，但没有直接证据**。</li>
 *   <li><b>未证实</b>：数组长度为何在两次运行间不同（69 / 59），以及宿主为什么塞 null。
 *       宿主侧构造逻辑未拿到；曾推测「{@code $$$} 残渣」，但按 {@code #} 切分只得 51 段、
 *       {@code $$$} 只出现 1 次，解释不通。**不要再沿用这个推测**。</li>
 * </ul>
 *
 * <p>好在本类不依赖上述任何未证实项：它只要求「容忍 null 槽位」，而这一点由
 * AOSP 源码 + 真机日志独立成立。
 *
 * <h3>两种失败模式（都必须容忍）</h3>
 * <ol>
 *   <li><b>null 槽位</b>：{@code array.getJSONObject(i)} 直接抛
 *       {@code JSONException}。若把整批包在一个 try/catch 里，第一个 null 就会
 *       中止整轮解析 → 列表只剩前若干条（真机表现为「只显示前若干集」）。</li>
 *   <li><b>缺 name / name 为空</b>：{@code optString("name")} 对值为 JSON null
 *       的键返回**字面量** {@code "null"}（真机实测，见下），对缺失键返回
 *       {@code ""}。两者都不是可显示标题，直接入库就是「空白标题选集」。
 *       这里统一回退成「第N集」。</li>
 * </ol>
 *
 * <p>解析规则：跳过 null 槽位与无效条目，**保留有效条目的相对顺序**。
 * 回退标题用的序号是「有效条目中的第几条」而非原数组下标——数组里夹着
 * null 槽位时，原下标会让标题数字跳号。
 *
 * <p><b>回退序号不保证与既有标题不重复</b>：若数组里已有名为「第2集」的条目、
 * 另有一条缺名，回退也会得到「第2集」，于是出现两条同名。这是**有意**的取舍——
 * 标题只是展示用，去重与选集记忆都按 url 比对；为避重而重新编号会让标题与
 * 宿主原本的集号错位，反而更难对。
 */
public final class EpisodeJsonParser {

    private EpisodeJsonParser() {
    }

    /** 解析结果：有效条目 + 各类跳过计数（便于宿主与日志定位）。 */
    public static class Result {
        /** 可用的选集条目（每项含 name / url，必要时含 dlsp）。 */
        public final ArrayList<HashMap<String, Object>> items = new ArrayList<>();
        /** 原数组长度。 */
        public int total;
        /** 槽位为 null 的个数。 */
        public int skippedNull;
        /** 是对象但 url 缺失/为空/字面量 "null" 的个数。 */
        public int skippedEmptyUrl;
        /** 非对象（如字符串、数字）或抛异常的个数。 */
        public int skippedBadEntry;

        public boolean isEmpty() {
            return items.isEmpty();
        }

        /** 被跳过的总数。 */
        public int skipped() {
            return skippedNull + skippedEmptyUrl + skippedBadEntry;
        }
    }

    /**
     * 解析选集数组。
     *
     * @param array 宿主传入的数组；为 null 时返回空结果（不抛异常）
     * @return 解析结果，永不为 null
     */
    public static Result parse(JSONArray array) {
        Result r = new Result();
        if (array == null) {
            return r;
        }
        r.total = array.length();
        for (int i = 0; i < r.total; i++) {
            // isNull(i) 对 **Java null 与 JSONObject.NULL 两种槽位都**返回 true
            // （真机探针实测），是这里唯一可靠的判空手段：getJSONObject 对两者
            // 都会直接抛异常。
            if (array.isNull(i)) {
                r.skippedNull++;
                continue;
            }
            JSONObject o;
            try {
                o = array.getJSONObject(i);
            } catch (JSONException e) {
                // 非对象元素（字符串/数字/数组）
                r.skippedBadEntry++;
                continue;
            }
            String url = normalize(o.optString("url"));
            if (url.isEmpty()) {
                r.skippedEmptyUrl++;
                continue;
            }
            HashMap<String, Object> item = new HashMap<>();
            String name = normalize(o.optString("name"));
            // 缺标题 / 标题是字面量 "null" → 回退「第N集」。
            // 不这么做就会在选集面板里显示空白项（真机症状「空白标题选集」）。
            item.put("name", name.isEmpty()
                    ? "第" + (r.items.size() + 1) + "集" : name);
            item.put("url", url);
            if (o.optBoolean("isSpecial", false)) {
                item.put("dlsp", "独立");
            }
            r.items.add(item);
        }
        return r;
    }

    /**
     * 把 {@code optString} 的几种「空」统一成 {@code ""}。
     *
     * <p>键缺失时 {@code optString} 返回 {@code ""}；但键的值是 JSON {@code null}
     * 时**两套实现行为不同**（真机实测）：
     * <ul>
     *   <li><b>Android 内置 org.json</b>（设备上跑的就是它）返回**字面量**
     *       {@code "null"}——不特判就会把字符串 "null" 当标题/地址入库；</li>
     *   <li>Maven {@code org.json:json}（单测环境用的是它）返回 {@code ""}。</li>
     * </ul>
     * 本方法对两种返回值都归成 {@code ""}，所以两边行为一致。
     *
     * <p>Android 侧为什么是 {@code "null"}，AOSP 源码链条很清楚（可自行复核）：
     * {@code JSONObject.optString} → {@code JSON.toString(object)} →
     * 值非 null 则 {@code String.valueOf(value)} → {@code JSONObject.NULL} 是匿名类，
     * 其 {@code toString()} 返回 {@code "null"}（{@code JSONObject.java:100-111}）。
     * 而 {@code put(name, null)}（Java null）**会删除该键**（同文件 :71-73 注释），
     * 于是落到「键缺失」分支返回 {@code ""}——这解释了为什么「值 JSON null」与
     * 「Java null / 键缺失」是两条不同路径。
     *
     * <p><b>必须先 trim 再比 "null"</b>：宿主拼串常带 {@code \r}，若先比字面量
     * 再 trim，{@code " null "} 会先漏过比较、trim 后变成 {@code "null"} 当标题显示。
     *
     * <p>注意「含 null 子串」的正常值不能被误伤（{@code "null集"} 是合法标题）。
     */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return "null".equals(t) ? "" : t;
    }
}
