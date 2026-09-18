package com.orange.playerlibrary.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MacCMS（苹果 CMS）采集接口的 {@code vod_play_url} 解析器（纯函数）。
 *
 * <h3>数据格式</h3>
 * <pre>
 * vod_play_from = "liangzi$$$lzm3u8"           // 各线路的名字，$$$ 分隔
 * vod_play_url  = "第01集$<url1>#第02集$<url2>$$$第01集$<url3>#第02集$<url4>"
 *                  └────── 线路 0（liangzi）──────┘└── 线路 1（lzm3u8）──┘
 * </pre>
 * 即：{@code $$$} 分隔线路，{@code #} 分隔同一线路内的剧集，{@code $} 分隔「剧集名」与「播放地址」。
 *
 * <h3>为什么必须选线路</h3>
 * 不同线路的同一集地址完全不同，且**不是所有线路都是可播的直链**。实测同一个
 * 采集源里：{@code liangzi} 线路返回的是 HTML 播放页（{@code text/html}），
 * {@code lzm3u8} 线路才是真正的 m3u8 流。若把全部线路简单拼接，剧集会被重复
 * 计入且混入不可播地址；若固定取第一段，则可能整条线路都播不了。
 *
 * <p>本类的职责是「切分 + 按可播性挑线路」，不做网络探测——可播性用
 * {@link #looksPlayable(String)} 按地址形态判断。
 */
public final class VodPlayUrlParser {

    private static final String GROUP_SEP = "$$$";
    private static final String ITEM_SEP = "#";
    private static final String NAME_URL_SEP = "$";

    private VodPlayUrlParser() {
    }

    /** 一集：名称 + 播放地址。 */
    public static class Episode {
        public final String name;
        public final String url;

        public Episode(String name, String url) {
            this.name = name;
            this.url = url;
        }

        @Override
        public String toString() {
            return "Episode{" + name + " -> " + url + "}";
        }
    }

    /** 解析结果：选中的线路下标、线路名、剧集列表。 */
    public static class Result {
        public final int groupIndex;
        public final String groupName;
        public final List<Episode> episodes;

        Result(int groupIndex, String groupName, List<Episode> episodes) {
            this.groupIndex = groupIndex;
            this.groupName = groupName;
            this.episodes = episodes;
        }

        public boolean isEmpty() {
            return episodes.isEmpty();
        }
    }

    /**
     * 从采集接口返回的完整 JSON 文本里取出选集并挑选可播线路。
     *
     * <p>兼容两种 {@code list} 形态（实测同一批采集源两种都有）：
     * <ul>
     *   <li>{@code list} 是**对象**，剧集数组在 {@code list.data[0]}（苹果 CMS 标准形态）</li>
     *   <li>{@code list} 是**数组**，剧集对象在 {@code list[0]}（v3 项目实测就是这种）</li>
     * </ul>
     * 另外兼容 {@code vod_play_url} 直接放在根节点的极简形态。
     *
     * @param jsonStr 采集接口返回的完整 JSON 文本
     * @return 选中的线路与剧集；无法解析或无有效数据时返回空结果
     */
    public static Result parseJson(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return new Result(-1, null, Collections.<Episode>emptyList());
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(jsonStr);

            org.json.JSONObject item = null;

            // 形态 A：list 是对象，剧集在 list.data[0]
            org.json.JSONObject listObj = root.optJSONObject("list");
            if (listObj != null) {
                org.json.JSONArray data = listObj.optJSONArray("data");
                if (data != null && data.length() > 0) {
                    item = data.optJSONObject(0);
                }
            }

            // 形态 B：list 是数组，剧集在 list[0]
            if (item == null) {
                org.json.JSONArray listArr = root.optJSONArray("list");
                if (listArr != null && listArr.length() > 0) {
                    item = listArr.optJSONObject(0);
                }
            }

            // 形态 C：vod_play_url 直接在根节点
            if (item == null && root.has("vod_play_url")) {
                item = root;
            }

            if (item == null) {
                return new Result(-1, null, Collections.<Episode>emptyList());
            }
            return parse(item.optString("vod_play_url", null),
                    item.optString("vod_play_from", null));
        } catch (org.json.JSONException e) {
            return new Result(-1, null, Collections.<Episode>emptyList());
        }
    }

    /**
     * 解析并挑选可播线路。
     *
     * @param vodPlayUrl  {@code vod_play_url} 原文，可为 null
     * @param vodPlayFrom {@code vod_play_from} 原文（线路名，{@code $$$} 分隔），可为 null
     * @return 选中的线路与剧集；无有效数据时返回空结果（episodes 为空列表）
     */
    public static Result parse(String vodPlayUrl, String vodPlayFrom) {
        List<List<Episode>> groups = parseAllGroups(vodPlayUrl);
        if (groups.isEmpty()) {
            return new Result(-1, null, Collections.<Episode>emptyList());
        }

        String[] names = splitGroups(vodPlayFrom);

        int chosen = choosePlayableGroup(groups);
        if (chosen < 0) {
            // 全部线路都不可播：退回第一段，至少保留调用方原语义
            chosen = 0;
        }

        String name = (names != null && chosen < names.length) ? names[chosen] : null;
        return new Result(chosen, name, groups.get(chosen));
    }

    /**
     * 把所有线路切成剧集列表（不挑选）。空线路会被跳过。
     */
    public static List<List<Episode>> parseAllGroups(String vodPlayUrl) {
        List<List<Episode>> out = new ArrayList<>();
        if (vodPlayUrl == null || vodPlayUrl.isEmpty()) {
            return out;
        }
        for (String groupRaw : vodPlayUrl.split("\\$\\$\\$", -1)) {
            List<Episode> eps = parseOneGroup(groupRaw);
            if (!eps.isEmpty()) {
                out.add(eps);
            }
        }
        return out;
    }

    /**
     * 解析单个线路段的剧集列表。
     */
    public static List<Episode> parseOneGroup(String groupRaw) {
        List<Episode> out = new ArrayList<>();
        if (groupRaw == null || groupRaw.isEmpty()) {
            return out;
        }
        for (String itemRaw : groupRaw.split(ITEM_SEP, -1)) {
            if (itemRaw == null || itemRaw.isEmpty()) {
                continue;
            }
            // 只按第一个 $ 切：地址里理论上不含 $，但名字里可能含，split 不限次会切碎
            int sep = itemRaw.indexOf(NAME_URL_SEP);
            String name;
            String url;
            if (sep < 0) {
                // 没有 $：整段当地址（部分采集源省略剧集名）
                name = null;
                url = itemRaw.trim();
            } else {
                name = itemRaw.substring(0, sep).trim();
                url = itemRaw.substring(sep + 1).trim();
            }
            if (url.isEmpty()) {
                continue;
            }
            if (name == null || name.isEmpty() || "null".equals(name)) {
                name = null;
            }
            out.add(new Episode(name, url));
        }
        return out;
    }

    /**
     * 在多个线路里挑一条「看起来可播」的，优先返回下标最小的那条。
     *
     * 判据（按优先级，取第一条满足的线路）：
     * <ol>
     *   <li>该线路超过半数剧集的地址是流媒体直链（m3u8/mp4/…）</li>
     *   <li>否则退回第一条剧集数最多的线路</li>
     * </ol>
     *
     * @return 线路下标；groups 为空时返回 -1
     */
    public static int choosePlayableGroup(List<List<Episode>> groups) {
        if (groups == null || groups.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < groups.size(); i++) {
            List<Episode> g = groups.get(i);
            if (g.isEmpty()) {
                continue;
            }
            int playable = 0;
            for (Episode e : g) {
                if (looksPlayable(e.url)) {
                    playable++;
                }
            }
            if (playable * 2 > g.size()) {
                return i;
            }
        }
        // 都不像直链：取剧集最多的一条
        int best = -1;
        int bestSize = -1;
        for (int i = 0; i < groups.size(); i++) {
            int size = groups.get(i).size();
            if (size > bestSize) {
                bestSize = size;
                best = i;
            }
        }
        return best;
    }

    /**
     * 按地址形态判断是否像可播放的媒体直链。
     *
     * 明确排除：
     * <ul>
     *   <li>{@code .html}/{@code .htm}/{@code .php} 等网页（采集源的「分享页」线路
     *       返回的就是 HTML，直接丢给播放器必然失败）</li>
     *   <li>无扩展名的裸路径（多为网页路由）</li>
     * </ul>
     * 认可：m3u8 / mp4 / mkv / flv / ts / mov / avi / webm / m4v / 3gp / rmvb / wmv。
     */
    public static boolean looksPlayable(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String base = stripQuery(url).toLowerCase();
        // 去掉 fragment
        int hash = base.indexOf('#');
        if (hash >= 0) {
            base = base.substring(0, hash);
        }
        int dot = base.lastIndexOf('.');
        int slash = base.lastIndexOf('/');
        if (dot < 0 || dot < slash) {
            return false;   // 路径末段没有扩展名 → 当作网页路由
        }
        String ext = base.substring(dot + 1);
        switch (ext) {
            case "m3u8":
            case "mp4":
            case "mkv":
            case "flv":
            case "ts":
            case "mov":
            case "avi":
            case "webm":
            case "m4v":
            case "3gp":
            case "rmvb":
            case "wmv":
            case "m3u":
            case "mpd":
                return true;
            default:
                return false;
        }
    }

    /**
     * 按 {@code $$$} 切线路名。无内容时返回 null。
     */
    public static String[] splitGroups(String vodPlayFrom) {
        if (vodPlayFrom == null || vodPlayFrom.isEmpty()) {
            return null;
        }
        return vodPlayFrom.split("\\$\\$\\$", -1);
    }

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }
}
