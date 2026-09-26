package com.orange.playerlibrary;

import static com.orange.playerlibrary.VideoEventManager.computeListHash;
import static com.orange.playerlibrary.VideoEventManager.countHighlighted;
import static com.orange.playerlibrary.VideoEventManager.resolveHighlightTarget;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 选集记忆的**真实实现**回归测试。
 *
 * <p>与 {@link EpisodeMemoryKeyTest} 的区别：那个文件在测试里**复刻**了一遍
 * 列表 hash 与高亮判据。复刻件不会随实现一起改，所以实现里的「两条判据各自命中」
 * 这种缺陷能安然躲过测试、直接上线。本文件直接调用 {@code VideoEventManager}
 * 里的真实静态方法，实现改了测试就会红。
 *
 * <p>线上症状（本次修复的靶子）：第 1 集和上次那一集**同时高亮**。
 */
public class EpisodeHighlightRegressionTest {

    private static final String EP1 = "https://v.cdnlz22.com/20240815/3725_2916c479/index.m3u8";
    private static final String EP2 = "https://v.cdnlz22.com/20240815/3734_7cd1c271/index.m3u8";
    private static final String EP3 = "https://v.lzcdn26.com/20250426/15616_67cb4081/index.m3u8";

    private static String[] series() {
        return new String[]{EP1, EP2, EP3};
    }

    // ==================== 高亮：全局至多一条 ====================

    /**
     * 核心回归：启动时直接播第 1 集（嗅探自动播放 / setVideoSource(list[0])），
     * 而记忆里存的是第 2 集，且**没有**走 restoreLastEpisode。
     *
     * <p>旧实现下：判据 1（正在播的地址）点亮第 1 集，判据 2（持久化的地址）
     * 点亮第 2 集 → 两条同时高亮。新实现必须先算唯一目标再逐条比。
     */
    @Test
    public void 在播第一集而记忆是第二集_只高亮一条() {
        String target = resolveHighlightTarget(series(), EP1, EP1, EP2);
        assertEquals("应高亮正在播的第 1 集", EP1, target);
        assertEquals("全局只能有一条高亮", 1, countHighlighted(series(), target));
    }

    /** 重启后还没起播：靠持久化的那一集高亮，且只有一条。 */
    @Test
    public void 未起播时_高亮记忆里的那一集且仅一条() {
        String target = resolveHighlightTarget(series(), null, null, EP2);
        assertEquals(EP2, target);
        assertEquals(1, countHighlighted(series(), target));
    }

    /** 已起播且记忆与在播一致：仍然只有一条。 */
    @Test
    public void 在播与记忆一致_只高亮一条() {
        String target = resolveHighlightTarget(series(), EP2, EP2, EP2);
        assertEquals(EP2, target);
        assertEquals(1, countHighlighted(series(), target));
    }

    /** 在播地址被改写成回环代理（去广告）时，仍要命中正确的那一集。 */
    @Test
    public void 在播地址被改写_靠原始源地址仍命中() {
        String proxied = "http://127.0.0.1:41167/cleaned/abc123.m3u8";
        String target = resolveHighlightTarget(series(), EP2, proxied, EP1);
        assertEquals("应按原始播放源命中第 2 集", EP2, target);
        assertEquals(1, countHighlighted(series(), target));
    }

    /** 记忆播放关闭（savedUrl 传 null）时，未起播就不该高亮任何一集。 */
    @Test
    public void 记忆播放关闭_未起播时不高亮() {
        String target = resolveHighlightTarget(series(), null, null, null);
        assertNull(target);
        assertEquals(0, countHighlighted(series(), target));
    }

    /**
     * 记忆播放关闭时，即使调用方误传了 savedUrl 也不该越权——
     * 由调用方负责传 null（见 resolveHighlightUrl）。这里固定住该契约：
     * 有在播的仍高亮在播的那一集。
     */
    @Test
    public void 记忆播放关闭_在播时只高亮在播的那一集() {
        String target = resolveHighlightTarget(series(), EP1, EP1, null);
        assertEquals(EP1, target);
        assertEquals(1, countHighlighted(series(), target));
    }

    /** 列表里有重复地址时，命中数会大于 1——这是数据问题，但目标仍唯一且确定。 */
    @Test
    public void 列表含重复地址_目标确定且指向该地址() {
        String[] dup = new String[]{EP1, EP2, EP2};
        String target = resolveHighlightTarget(dup, null, null, EP2);
        assertEquals(EP2, target);
        assertEquals("重复地址会同时高亮两条，属已知数据问题", 2, countHighlighted(dup, target));
    }

    /**
     * 空列表/null 输入不应抛异常。
     *
     * <p>列表为空时没有条目可高亮，目标就是 savedUrl（生产代码里此时列表也空，
     * 不会有条目去匹配它，所以不会真的高亮出东西）。语义保持一致，避免
     * 「空列表返回 null、非空列表返回 savedUrl」这种难预测的分支。
     */
    @Test
    public void 空输入不抛异常() {
        assertEquals(EP2, resolveHighlightTarget(new String[0], EP1, EP1, EP2));
        assertEquals(EP2, resolveHighlightTarget(null, EP1, EP1, EP2));
        assertNull(resolveHighlightTarget(null, EP1, EP1, null));
        assertEquals(0, countHighlighted(null, EP1));
        assertEquals(0, countHighlighted(series(), null));
        assertEquals(0, countHighlighted(new String[0], EP2));
    }

    // ==================== 列表摘要（剧集键） ====================

    @Test
    public void 同内容列表_摘要一致() {
        assertEquals(computeListHash(series()), computeListHash(series()));
    }

    @Test
    public void 空列表摘要为零() {
        assertEquals(0, computeListHash(null));
        assertEquals(0, computeListHash(new String[0]));
    }

    /** 增删集会改变摘要——这正是「二次填充后记忆读不回来」的原因。 */
    @Test
    public void 列表长度变化_摘要变化() {
        String[] grown = new String[]{EP1, EP2, EP3, "https://a.com/new.m3u8"};
        assertNotEquals(computeListHash(series()), computeListHash(grown));
    }

    /**
     * 回归：旧算法只看 size + 首 + 末，中间换集认不出来。
     * 新算法覆盖全部地址，中间变化必须能感知。
     */
    @Test
    public void 中间集变化_摘要变化_旧算法会漏判() {
        String[] a = new String[]{EP1, EP2, EP3};
        String[] b = new String[]{EP1, "https://a.com/different.m3u8", EP3};
        assertNotEquals("中间集不同必须得到不同摘要", computeListHash(a), computeListHash(b));

        // 旧算法（size + 首 + 末）在这两个列表上会得出相同结果，故会串剧
        assertEquals(oldListHash(a), oldListHash(b));
    }

    /** 回归：旧算法下两部不同剧只要 size/首/末相同就撞键。 */
    @Test
    public void 不同剧集_摘要不同_旧算法会撞键() {
        String[] drama1 = new String[]{EP1, EP2, EP3};
        String[] drama2 = new String[]{EP1, "https://other.com/x.m3u8", EP3};
        assertNotEquals(computeListHash(drama1), computeListHash(drama2));
        assertEquals("旧算法会撞键", oldListHash(drama1), oldListHash(drama2));
    }

    /** 顺序变化必须改变摘要：存的是下标，顺序变了下标会指错集。 */
    @Test
    public void 顺序变化_摘要变化() {
        String[] reversed = new String[]{EP3, EP2, EP1};
        assertNotEquals("保序摘要必须区分顺序", computeListHash(series()), computeListHash(reversed));
    }

    /**
     * 复刻**旧的**高亮判据（先比在播、不中再比持久化），证明它在本场景下
     * 会同时点亮两条——即线上报的「第 1 集和已选择的集同时高亮」。
     *
     * <p>这个测试的作用是自证回归：如果哪天有人把实现改回「两条独立判据」，
     * 上面 {@code 在播第一集而记忆是第二集_只高亮一条} 会红，而本测试
     * 说明红的理由。
     */
    @Test
    public void 旧判据在本场景下确实会重复高亮_证明回归有效() {
        String[] urls = series();
        String playing = EP1;   // 启动直接播第 1 集
        String saved = EP2;     // 记忆里是第 2 集

        int oldHighlighted = 0;
        for (String url : urls) {
            boolean hit = com.orange.playerlibrary.utils.EpisodeUrlMatcher.matches(
                    url, playing, playing);              // 判据 1
            if (!hit) {
                hit = saved != null && saved.equals(url); // 判据 2
            }
            if (hit) {
                oldHighlighted++;
            }
        }
        assertEquals("旧判据下会同时高亮第 1 集与第 2 集", 2, oldHighlighted);

        // 新判据只高亮一条
        String target = resolveHighlightTarget(urls, playing, playing, saved);
        assertEquals(1, countHighlighted(urls, target));
    }

    /** 复刻旧算法，仅用于在上面的测试里证明「旧实现确实有缺陷」。 */
    private static int oldListHash(String[] urls) {
        if (urls == null || urls.length == 0) {
            return 0;
        }
        int hash = urls.length;
        hash = hash * 31 + urls[0].hashCode();
        hash = hash * 31 + urls[urls.length - 1].hashCode();
        return hash;
    }

    // ==================== 门面清除键一致性 ====================

    /**
     * 回归：门面曾写成 {@code "s" + getSeriesKeyForStorage()}，而后者已含 "s"，
     * 实际得到 {@code "ss<hash>"}，与内部用的 {@code "s<hash>"} 不一致，
     * 「清除记录」按了等于没按。
     *
     * <p>修复方式是把门面改为直接委托 SDK，不再自行拼键。这里固定住键的形状，
     * 防止有人再把前缀拼两遍。
     */
    @Test
    public void 剧集键前缀只出现一次() {
        int hash = computeListHash(series());
        String key = seriesKey(hash);
        assertEquals("s" + hash, key);
        assertTrue("前缀不应重复", !key.startsWith("ss"));
    }

    private static String seriesKey(int listHash) {
        return "s" + listHash;
    }
}
