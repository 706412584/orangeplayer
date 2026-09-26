package com.orange.playerlibrary;

import static com.orange.playerlibrary.VideoSniffing.DEFER_IN_FLIGHT;
import static com.orange.playerlibrary.VideoSniffing.DEFER_NONE;
import static com.orange.playerlibrary.VideoSniffing.DEFER_PAGE_LOADING;
import static com.orange.playerlibrary.VideoSniffing.decideDeferral;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 嗅探收尾判定单元测试。
 *
 * <p>回归背景（审查发现，两处真实缺陷）：
 * <ol>
 *   <li>{@code startSniffing} 不排首次收尾检查，链只在媒体命中或 onPageFinished
 *       时才启动 → 主文档不触发该回调且无媒体命中时，链根本没启动，
 *       面板一直「正在嗅探」、WebView 与 Activity 常驻不释放。</li>
 *   <li>60 秒兜底写在空闲链里，而每次媒体命中都会 {@code removeCallbacks} 重排
 *       3 秒 → 结果持续到达时兜底**永不执行**，嗅探永不结束。</li>
 * </ol>
 * 修复要点：硬截止优先于一切顺延条件（elapsedMs 到上限即无条件收尾）。
 */
public class SniffFinishDecisionTest {

    private static final long MAX = 60_000L;

    /** 正常情况：页面已加载完、无在途校验 → 可以收尾。 */
    @Test
    public void 页面已加载且无在途_应收尾() {
        assertEquals(DEFER_NONE, decideDeferral(true, 0, 5_000));
    }

    /** 页面未加载完 → 顺延（否则会腰斩页面，播放器起不来）。 */
    @Test
    public void 页面未加载完_顺延() {
        assertEquals(DEFER_PAGE_LOADING, decideDeferral(false, 0, 5_000));
    }

    /** 有在途校验 → 顺延（否则会 disconnect 掉还没上报的慢校验）。 */
    @Test
    public void 有在途校验_顺延() {
        assertEquals(DEFER_IN_FLIGHT, decideDeferral(true, 2, 5_000));
    }

    /** 页面未加载完优先于在途（两者都成立时报告页面原因，便于日志定位）。 */
    @Test
    public void 两者都成立_报告页面原因() {
        assertEquals(DEFER_PAGE_LOADING, decideDeferral(false, 3, 5_000));
    }

    // ===== 硬截止优先：这是「嗅探永不结束」的核心修复 =====

    /** 回归：到硬截止时，即使页面永不加载完也必须收尾。 */
    @Test
    public void 到硬截止_页面未加载完也要收尾() {
        assertEquals("硬截止必须压过「页面未加载完」",
                DEFER_NONE, decideDeferral(false, 0, MAX));
    }

    /** 回归：到硬截止时，即使校验一直挂在途也必须收尾。 */
    @Test
    public void 到硬截止_有在途校验也要收尾() {
        assertEquals("硬截止必须压过「校验在途」",
                DEFER_NONE, decideDeferral(true, 5, MAX));
    }

    /** 回归：到硬截止且两个条件都成立，仍要收尾。 */
    @Test
    public void 到硬截止_两个条件都成立也要收尾() {
        assertEquals(DEFER_NONE, decideDeferral(false, 9, MAX));
    }

    /** 恰好到达上限即算到点（边界包含）。 */
    @Test
    public void 恰好到达上限_收尾() {
        assertEquals(DEFER_NONE, decideDeferral(false, 3, MAX));
        assertEquals("差 1ms 未到上限，仍按条件顺延",
                DEFER_PAGE_LOADING, decideDeferral(false, 3, MAX - 1));
    }

    /** 超过上限同样收尾。 */
    @Test
    public void 超过上限_收尾() {
        assertEquals(DEFER_NONE, decideDeferral(false, 0, MAX + 30_000));
    }

    /** 起始时刻（elapsed=0）不应被误判为到点。 */
    @Test
    public void 刚启动_按条件判定() {
        assertEquals(DEFER_PAGE_LOADING, decideDeferral(false, 0, 0));
        assertEquals(DEFER_NONE, decideDeferral(true, 0, 0));
    }
}
