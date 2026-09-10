package com.orange.playerlibrary.speech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.orange.playerlibrary.subtitle.SubtitleEntry;

import org.junit.Test;

import java.util.List;

/**
 * AsrSegmentSplitter 单测：锁定「长段切分 + 段内时间插值 + 边界守恒」的契约。
 */
public class AsrSegmentSplitterTest {

    // ===== 基本契约 =====

    @Test
    public void nullOrEmptyTextYieldsNoEntries() {
        assertTrue(AsrSegmentSplitter.split(null, 0, 5000).isEmpty());
        assertTrue(AsrSegmentSplitter.split("   ", 0, 5000).isEmpty());
    }

    @Test
    public void shortSegmentStaysSingleEntry() {
        List<SubtitleEntry> r = AsrSegmentSplitter.split("你好世界", 1000, 3000);
        assertEquals(1, r.size());
        assertEquals(1000, r.get(0).getStartTime());
        assertEquals(3000, r.get(0).getEndTime());
        assertEquals("你好世界", r.get(0).getText());
    }

    @Test
    public void longSegmentIsSplitIntoMultipleEntries() {
        // 5s / 21 字 → 时长与字数都要求切分
        String text = "这是第一句话，这是第二句话，这是第三句。";
        List<SubtitleEntry> r = AsrSegmentSplitter.split(text, 0, 5000);
        assertTrue("应切成多条，实际 " + r.size(), r.size() >= 2);
        for (SubtitleEntry e : r) {
            assertTrue("单条时长应受控: " + e, e.getDuration() <= AsrSegmentSplitter.MAX_ENTRY_MS);
        }
    }

    // ===== 时间轴契约 =====

    @Test
    public void timesAreContiguousAndSpanPreserved() {
        String text = "占据百分之八十以上的支出，阿里云CDN以技术和规模优势，带给企业普惠科技价格。";
        List<SubtitleEntry> r = AsrSegmentSplitter.split(text, 10_000, 15_000);
        assertTrue(r.size() >= 2);
        assertEquals("首条起点应为段起点", 10_000, r.get(0).getStartTime());
        assertEquals("末条终点应为段终点", 15_000, r.get(r.size() - 1).getEndTime());
        for (int i = 1; i < r.size(); i++) {
            assertEquals("相邻条目应严格连续", r.get(i - 1).getEndTime(), r.get(i).getStartTime());
        }
    }

    @Test
    public void textIsPreservedInOrder() {
        String text = "第一句。第二句。第三句。";
        List<SubtitleEntry> r = AsrSegmentSplitter.split(text, 0, 6000);
        StringBuilder sb = new StringBuilder();
        for (SubtitleEntry e : r) {
            sb.append(e.getText());
        }
        assertEquals("切分不得丢字或改序", text, sb.toString());
    }

    // ===== 标点优先 =====

    @Test
    public void prefersSplittingAfterSentencePunctuation() {
        String text = "今天天气很好。我们出去走走吧。";
        List<SubtitleEntry> r = AsrSegmentSplitter.split(text, 0, 5000);
        assertTrue(r.size() >= 2);
        assertEquals("应优先在句号后切分", "今天天气很好。", r.get(0).getText());
    }

    @Test
    public void fallsBackToHardCutWhenNoPunctuation() {
        String text = "这是一段完全没有标点的连续长文本内容需要被强制切分处理";
        List<SubtitleEntry> r = AsrSegmentSplitter.split(text, 0, 5000);
        assertTrue(r.size() >= 2);
        assertEquals(text, join(r));
    }

    // ===== 过短条目合并 =====

    @Test
    public void noEntryShorterThanMinimum() {
        String text = "短。这是一句比较长的文字内容用来触发切分行为。";
        List<SubtitleEntry> r = AsrSegmentSplitter.split(text, 0, 5000);
        assertFalse(r.isEmpty());
        for (SubtitleEntry e : r) {
            assertTrue("不应出现一闪而过的条目: " + e,
                    e.getDuration() >= AsrSegmentSplitter.MIN_ENTRY_MS || r.size() == 1);
        }
        assertEquals(text, join(r));
    }

    // ===== 退化输入 =====

    @Test
    public void zeroDurationSegmentIsReturnedAsIs() {
        List<SubtitleEntry> r = AsrSegmentSplitter.split("内容", 5000, 5000);
        assertEquals(1, r.size());
        assertEquals(5000, r.get(0).getStartTime());
        assertEquals(5000, r.get(0).getEndTime());
    }

    @Test
    public void manyCharsWithShortDurationStillSplits() {
        // 时长仅 2s 但字很多：字数维度应触发切分
        String text = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十";
        List<SubtitleEntry> r = AsrSegmentSplitter.split(text, 0, 2000);
        assertTrue("字数过多也应切分", r.size() >= 2);
        assertEquals(text, join(r));
    }

    @Test
    public void noEntryExceedsMaximumDuration() {
        // 真机回归：标点落在窗口边缘时曾切出极短尾段，回吞后产生 4.9s 超长条目
        String[] texts = {
                "它不仅能判断视频场景，根据每个视频内容中的场景、动作、内容。",
                "阿里云CDN网络已遍布全球1000多个节点，覆盖六大洲。",
                "在ECS上可以瞬间发挥云计算的弹性特性，完美处理直播并发。",
                "这是一段很长的没有任何标点的连续文本需要按字数上限强制切分处理掉",
        };
        for (String text : texts) {
            for (long duration : new long[]{2000, 4000, 4887, 5000}) {
                List<SubtitleEntry> r = AsrSegmentSplitter.split(text, 0, duration);
                for (SubtitleEntry e : r) {
                    assertTrue("条目超上限 " + e.getDuration() + "ms: " + e,
                            e.getDuration() <= AsrSegmentSplitter.MAX_ENTRY_MS);
                }
                assertEquals("不得丢字", text, join(r));
            }
        }
    }

    /**
     * 真机回归：连续旁白无静音时 VAD 段可达 20s+（实际观测 22.5s/19.4s/12.4s），
     * 纯按字数比例分配会让字数偏多的条目超出上限。
     */
    @Test
    public void veryLongSegmentsStayUnderLimit() {
        String[] texts = {
                "确保业务永续，考虑到运营需求ua video还提供包括详细的日志文件、视频数、播放数、"
                        + "播放时间、卡顿率等专业的全景数据统计。",
                "多角度分析平台现状和客户画像描述，助力企业进行产品改进和业务拓展。服务好，功能强大",
                "占据80%以上的支出，阿里云CDN以技术和规模优势带给企业普惠科技价格只有传统CDN厂商的一半。"
                        + "而阿里云独有的窄带高清技术还能降低30%至40%的带宽和存储费用让您的平台真正赢在起跑线上。",
        };
        long[] durations = {12406, 19414, 22486, 7904, 11638, 5590};
        for (String text : texts) {
            for (long duration : durations) {
                List<SubtitleEntry> r = AsrSegmentSplitter.split(text, 1_000_000, 1_000_000 + duration);
                assertTrue("长段应切分: " + duration, r.size() >= 2);
                for (SubtitleEntry e : r) {
                    assertTrue("超上限 " + e.getDuration() + "ms (段长 " + duration + "): " + e,
                            e.getDuration() <= AsrSegmentSplitter.MAX_ENTRY_MS);
                }
                assertEquals("不得丢字", text, join(r));
                assertEquals("首条对齐段首", 1_000_000, r.get(0).getStartTime());
                assertEquals("末条对齐段尾", 1_000_000 + duration,
                        r.get(r.size() - 1).getEndTime());
            }
        }
    }

    private static String join(List<SubtitleEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (SubtitleEntry e : entries) {
            sb.append(e.getText());
        }
        return sb.toString();
    }
}
