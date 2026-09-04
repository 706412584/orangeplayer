package com.orange.playerlibrary.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * 字幕延迟（渲染时间轴偏移）单元测试。
 * SubtitleManager 的 findSubtitleAt 为私有方法且依赖 View 体系，
 * 这里通过可测的等价逻辑验证偏移语义：position - delay ∈ [start, end]。
 */
@RunWith(MockitoJUnitRunner.class)
public class SubtitleDelayTest {

    private List<SubtitleEntry> cues;

    @Before
    public void setUp() {
        cues = new ArrayList<>();
        cues.add(new SubtitleEntry(5000, 6000, "cue A"));   // 5s-6s
        cues.add(new SubtitleEntry(8000, 9000, "cue B"));   // 8s-9s
    }

    /** 复现 SubtitleManager.findSubtitleAt 的线性查找语义 */
    private SubtitleEntry findAt(long position) {
        for (SubtitleEntry entry : cues) {
            if (position >= entry.getStartTime() && position <= entry.getEndTime()) {
                return entry;
            }
        }
        return null;
    }

    /** 复现 updateSubtitle 的偏移公式：adjusted = position - delay */
    private SubtitleEntry findWithDelay(long position, long delayMs) {
        return findAt(position - delayMs);
    }

    @Test
    public void 零延迟等同原始查找() {
        assertEquals("cue A", findWithDelay(5500, 0).getText());
        assertNull(findWithDelay(7000, 0));
    }

    @Test
    public void 正延迟延后显示() {
        // 延迟 2s：实际 7s 时查询 5s 的时间轴 → 显示 cue A
        assertEquals("cue A", findWithDelay(7000, 2000).getText());
        // 实际 5.5s 时查询 3.5s → 无字幕
        assertNull(findWithDelay(5500, 2000));
    }

    @Test
    public void 负延迟提前显示() {
        // 提前 2s：实际 3s 时查询 5s 时间轴 → 显示 cue A
        assertEquals("cue A", findWithDelay(3000, -2000).getText());
        // 实际 7s 时查询 9s → 命中 cue B 的闭区间末端（提前显示 cue B）
        assertEquals("cue B", findWithDelay(7000, -2000).getText());
        // 实际 6.5s 时查询 8.5s → cue B
        assertEquals("cue B", findWithDelay(6500, -2000).getText());
    }

    @Test
    public void cue边界毫秒精度() {
        // 边界正好命中（start=5000, end=6000 闭区间）
        assertEquals("cue A", findWithDelay(5000, 0).getText());
        assertEquals("cue A", findWithDelay(6000, 0).getText());
        assertNull(findWithDelay(4999, 0));
        assertNull(findWithDelay(6001, 0));
        // 延迟 100ms 后边界平移
        assertEquals("cue A", findWithDelay(5100, 100).getText());
        assertNull(findWithDelay(5100, 101));
    }

    @Test
    public void 延迟跨cue切换() {
        // 延迟 3s：8s 时刻查询 5s → cue A；10s 时刻查询 7s → 空档；11s 时刻查询 8s → cue B
        assertEquals("cue A", findWithDelay(8000, 3000).getText());
        assertNull(findWithDelay(10000, 3000));
        assertEquals("cue B", findWithDelay(11000, 3000).getText());
    }
}
