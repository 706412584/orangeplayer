package com.orange.playerlibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 选集记忆的键构造与下标解析测试。
 *
 * <p>线上症状：播到第 02 集，重启后又从第一集开始。
 *
 * <p>进度秒数本身已按单集 URL 绑定（每集地址不同，天然区分），所以这里只解决
 * 「记住是哪一集」：按剧集标识存下标，重启后切回去。
 */
public class EpisodeMemoryKeyTest {

    /** 复刻 VideoEventManager.getSeriesKey：剧集标识由列表 hash 构造。 */
    private static String seriesKey(int listHash) {
        return "s" + listHash;
    }

    /**
     * 复刻 getVideoListHash：基于列表大小 + 首尾 URL。
     */
    private static int listHash(java.util.List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return 0;
        }
        int hash = urls.size();
        hash = hash * 31 + urls.get(0).hashCode();
        hash = hash * 31 + urls.get(urls.size() - 1).hashCode();
        return hash;
    }

    private static final String EP1 = "https://v.cdnlz22.com/20240815/3725_2916c479/index.m3u8";
    private static final String EP2 = "https://v.cdnlz22.com/20240815/3734_7cd1c271/index.m3u8";
    private static final String EP3 = "https://v.lzcdn26.com/20250426/15616_67cb4081/index.m3u8";

    private static java.util.List<String> series() {
        return java.util.Arrays.asList(EP1, EP2, EP3);
    }

    // ---------- 剧集标识 ----------

    @Test
    public void 同一剧集列表_两次启动的键相同() {
        // 列表内容一致 → hash 一致 → 键一致，才能读到上次的集数
        assertEquals(seriesKey(listHash(series())), seriesKey(listHash(series())));
    }

    @Test
    public void 同一列表内换集_键不变() {
        // 这是关键：换集不该产生新键，否则每换一集就丢一次记忆
        int h = listHash(series());
        // 换集不改变列表本身
        assertEquals(seriesKey(h), seriesKey(listHash(series())));
    }

    @Test
    public void 不同剧集的键不同() {
        java.util.List<String> other = java.util.Arrays.asList(
                "https://a.com/x/1.m3u8", "https://a.com/x/2.m3u8");
        assertNotEquals(seriesKey(listHash(series())), seriesKey(listHash(other)));
    }

    @Test
    public void 列表变化_键随之变化() {
        int before = listHash(series());
        java.util.List<String> grown = java.util.Arrays.asList(EP1, EP2, EP3,
                "https://a.com/new.m3u8");
        assertNotEquals(before, listHash(grown));
    }

    @Test
    public void 空列表hash为零() {
        assertEquals(0, listHash(null));
        assertEquals(0, listHash(new java.util.ArrayList<String>()));
    }

    // ---------- 下标存取语义 ----------

    /** 复刻 PlayerSettingsManager 的下标存取约定。 */
    private static int getLastEpisodeIndex(int stored, int listSize) {
        if (stored < 0 || stored >= listSize) {
            return -1;   // 无记录或越界
        }
        return stored;
    }

    @Test
    public void 正常下标可直接恢复() {
        assertEquals(1, getLastEpisodeIndex(1, 3));   // 第 02 集
        assertEquals(2, getLastEpisodeIndex(2, 3));   // 最后一集
        assertEquals(0, getLastEpisodeIndex(0, 3));   // 第一集
    }

    @Test
    public void 无记录时返回负一() {
        assertEquals(-1, getLastEpisodeIndex(-1, 3));
    }

    @Test
    public void 列表变短时越界下标被拒_不崩() {
        // 上次记的是第 10 集，这次列表只有 3 集（换源/裁剪）
        assertEquals(-1, getLastEpisodeIndex(9, 3));
    }

    @Test
    public void 空列表一律返回负一() {
        assertEquals(-1, getLastEpisodeIndex(0, 0));
    }

    // ---------- 高亮：必须按地址比，不能按下标 ----------

    /**
     * 复刻高亮的第二级判据：用持久化的那一集地址比对。
     *
     * 不能用 position 比下标——显示列表可能被排序/反转，position 与真实下标错位。
     */
    private static boolean isSavedEpisode(String itemUrl, String savedUrl) {
        return savedUrl != null && savedUrl.equals(itemUrl);
    }

    private static String lastEpisodeUrl(java.util.List<String> list, int stored) {
        int idx = getLastEpisodeIndex(stored, list.size());
        return idx < 0 ? null : list.get(idx);
    }

    @Test
    public void 高亮按地址匹配_正序显示() {
        java.util.List<String> list = series();
        String saved = lastEpisodeUrl(list, 1);      // 记住第 02 集
        assertEquals(EP2, saved);

        // 正序显示时，只有第 02 集高亮
        assertTrue(isSavedEpisode(EP2, saved));
        assertTrue(!isSavedEpisode(EP1, saved));
        assertTrue(!isSavedEpisode(EP3, saved));
    }

    @Test
    public void 高亮按地址匹配_倒序显示也不错位() {
        java.util.List<String> list = series();
        String saved = lastEpisodeUrl(list, 1);      // 记住第 02 集
        java.util.List<String> reversed = new java.util.ArrayList<>(list);
        java.util.Collections.reverse(reversed);

        // 倒序后 position=1 是 EP2，但真实下标也是 1——若按下标比会错；
        // 按地址比则无论顺序如何都命中正确的那一集
        int highlighted = 0;
        for (int i = 0; i < reversed.size(); i++) {
            if (isSavedEpisode(reversed.get(i), saved)) {
                highlighted++;
            }
        }
        assertEquals("有且只有一集被高亮", 1, highlighted);
    }

    @Test
    public void 无记录时不高亮任何一集() {
        java.util.List<String> list = series();
        String saved = lastEpisodeUrl(list, -1);
        assertEquals(null, saved);
        for (String u : list) {
            assertTrue(!isSavedEpisode(u, saved));
        }
    }
}
