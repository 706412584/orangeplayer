package com.orange.playerlibrary.utils;

/**
 * 选集地址匹配器（纯函数）。
 * <p>
 * 背景：播放器内部会改写实际播放地址，而调用方传入的播放源地址保持不变。
 * <ul>
 *   <li>M3U8 去广告后：{@code https://v.example.com/a/index.m3u8}
 *       → {@code http://127.0.0.1:41167/cleaned/<hash>.m3u8}</li>
 *   <li>命中已下载视频：原地址 → {@code file:///storage/.../xxx.mp4}</li>
 * </ul>
 * 若只用「当前播放地址」去选集列表里反查下标，必然失配（下标为 -1），
 * 表现为「点下一集没反应」或误报「已经是最后一集了」。
 * <p>
 * 因此按「原始地址 → 当前地址 → 忽略查询串」三级回退匹配。全部为精确匹配
 * （或去 query 后精确匹配），不做前缀/包含匹配——不同集的地址可能互为前缀
 * （如 {@code .../1.mp4} 与 {@code .../10.mp4}），包含匹配会串集。
 */
public final class EpisodeUrlMatcher {

    private EpisodeUrlMatcher() {
    }

    /**
     * 判断选集条目地址是否对应当前播放的某一集。
     *
     * @param itemUrl   选集条目里的地址
     * @param sourceUrl 调用方最初传入的播放源地址（未经内部改写），可为 null
     * @param playingUrl 当前实际播放地址（可能已被改写成回环代理/本地路径），可为 null
     * @return 是否匹配
     */
    public static boolean matches(String itemUrl, String sourceUrl, String playingUrl) {
        if (isBlank(itemUrl)) {
            return false;
        }
        // 1) 精确匹配：原始地址优先，其次当前地址
        if (itemUrl.equals(sourceUrl) || itemUrl.equals(playingUrl)) {
            return true;
        }
        // 2) 忽略查询串后精确匹配（部分源站播放中会改写 query）
        String itemBase = stripQuery(itemUrl);
        if (itemBase == null) {
            return false;
        }
        return itemBase.equals(stripQuery(sourceUrl)) || itemBase.equals(stripQuery(playingUrl));
    }

    /**
     * 去掉 URL 的查询串。无查询串时返回原串；入参为空时返回 null。
     */
    public static String stripQuery(String url) {
        if (isBlank(url)) {
            return null;
        }
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
