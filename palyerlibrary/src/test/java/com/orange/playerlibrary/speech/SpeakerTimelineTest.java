package com.orange.playerlibrary.speech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.orange.playerlibrary.speech.SpeakerTimeline.Span;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link SpeakerTimeline} 单元测试。
 *
 * 重点覆盖 ASR 段边界与 diarization 区间**不对齐**的各类情形——这正是
 * 归属判定用「最大重叠」而非「包含」的原因。
 */
public class SpeakerTimelineTest {

    private static Span span(long start, long end, int speaker) {
        return new Span(start, end, speaker);
    }

    @Test
    public void emptyTimeline_returnsNoSpeaker() {
        assertEquals(SpeakerTimeline.NO_SPEAKER,
                SpeakerTimeline.EMPTY.speakerAt(0, 1000));
        assertTrue(SpeakerTimeline.EMPTY.isEmpty());
        assertEquals(0, SpeakerTimeline.EMPTY.size());
    }

    @Test
    public void nullOrEmptyInput_yieldsEmptyTimeline() {
        assertTrue(SpeakerTimeline.of(null).isEmpty());
        assertTrue(SpeakerTimeline.of(Collections.<Span>emptyList()).isEmpty());
    }

    @Test
    public void exactContainment_returnsThatSpeaker() {
        SpeakerTimeline t = SpeakerTimeline.of(Arrays.asList(
                span(0, 2000, 0),
                span(2000, 4000, 1)));
        assertEquals(0, t.speakerAt(200, 1800));
        assertEquals(1, t.speakerAt(2200, 3800));
    }

    @Test
    public void segmentStraddlingTwoSpeakers_picksLargerOverlap() {
        // 段 0-1000：A 占 0-400（400ms），B 占 400-1000（600ms）→ 判给 B
        SpeakerTimeline t = SpeakerTimeline.of(Arrays.asList(
                span(0, 400, 0),
                span(400, 1000, 1)));
        assertEquals(1, t.speakerAt(0, 1000));
    }

    @Test
    public void straddle_picksEarlierSpeakerOnTie() {
        // 各占一半：严格大于比较 → 保留时间更早的说话人，结果稳定可预期
        SpeakerTimeline t = SpeakerTimeline.of(Arrays.asList(
                span(0, 500, 7),
                span(500, 1000, 3)));
        assertEquals(7, t.speakerAt(0, 1000));
    }

    @Test
    public void sameSpeakerAcrossMultipleSpans_isAccumulated() {
        // A 占 0-2000 与 4000-6000（共 4000ms），B 占 2000-4000（2000ms）
        // 段取 0-6000：若按单区间取最大会误判给 A 的单段 2000，累加后 A=4000 > B=2000
        SpeakerTimeline t = SpeakerTimeline.of(Arrays.asList(
                span(0, 2000, 0),
                span(2000, 4000, 1),
                span(4000, 6000, 0)));
        assertEquals(0, t.speakerAt(0, 6000));
    }

    @Test
    public void accumulationFlipsResultComparedToSingleSpanMax() {
        // A：0-1000 + 2000-3000（累加 2000ms）；B：1000-2000（单段 1000ms）
        // 单段最大会选 A 的 1000 与 B 的 1000 平票，累加后 A 明确胜出
        SpeakerTimeline t = SpeakerTimeline.of(Arrays.asList(
                span(0, 1000, 0),
                span(1000, 2000, 1),
                span(2000, 3000, 0)));
        assertEquals(0, t.speakerAt(0, 3000));
    }

    @Test
    public void noOverlap_returnsNoSpeaker() {
        // 段落在两个区间之间的空隙
        SpeakerTimeline t = SpeakerTimeline.of(Arrays.asList(
                span(0, 1000, 0),
                span(3000, 4000, 1)));
        assertEquals(SpeakerTimeline.NO_SPEAKER, t.speakerAt(1500, 2500));
    }

    @Test
    public void segmentBeforeAllSpans_returnsNoSpeaker() {
        SpeakerTimeline t = SpeakerTimeline.of(Collections.singletonList(span(5000, 6000, 0)));
        assertEquals(SpeakerTimeline.NO_SPEAKER, t.speakerAt(0, 1000));
    }

    @Test
    public void segmentAfterAllSpans_returnsNoSpeaker() {
        SpeakerTimeline t = SpeakerTimeline.of(Collections.singletonList(span(0, 1000, 0)));
        assertEquals(SpeakerTimeline.NO_SPEAKER, t.speakerAt(2000, 3000));
    }

    @Test
    public void touchingBoundaryIsNotOverlap() {
        // 段 [1000,2000) 与区间 [0,1000) 相接但不重叠
        SpeakerTimeline t = SpeakerTimeline.of(Arrays.asList(
                span(0, 1000, 0),
                span(2000, 3000, 1)));
        assertEquals(SpeakerTimeline.NO_SPEAKER, t.speakerAt(1000, 2000));
    }

    @Test
    public void zeroOrNegativeLengthSegment_returnsNoSpeaker() {
        SpeakerTimeline t = SpeakerTimeline.of(Collections.singletonList(span(0, 1000, 0)));
        assertEquals(SpeakerTimeline.NO_SPEAKER, t.speakerAt(500, 500));
        assertEquals(SpeakerTimeline.NO_SPEAKER, t.speakerAt(600, 500));
    }

    @Test
    public void nonContiguousSpeakerIds_areHandled() {
        // 聚类编号未必连续（可能只产出 0 与 2）
        SpeakerTimeline t = SpeakerTimeline.of(Arrays.asList(
                span(0, 1000, 0),
                span(1000, 2000, 2)));
        assertEquals(0, t.speakerAt(0, 900));
        assertEquals(2, t.speakerAt(1100, 2000));
    }

    @Test
    public void invalidSpans_areDropped() {
        List<Span> raw = new ArrayList<>();
        raw.add(null);
        raw.add(span(1000, 1000, 0));   // 零长度
        raw.add(span(2000, 1500, 0));   // 终点早于起点
        raw.add(span(3000, 4000, -1));  // 非法说话人
        raw.add(span(5000, 6000, 0));   // 唯一合法项

        SpeakerTimeline t = SpeakerTimeline.of(raw);
        assertEquals(1, t.size());
        assertFalse(t.isEmpty());
        assertEquals(0, t.speakerAt(5000, 6000));
        assertEquals(SpeakerTimeline.NO_SPEAKER, t.speakerAt(1000, 1000));
    }

    @Test
    public void unsortedInput_isSortedBeforeQuery() {
        // 查询依赖「起点已越过段尾即提前 break」，未排序会让后置区间被漏掉
        SpeakerTimeline t = SpeakerTimeline.of(Arrays.asList(
                span(4000, 5000, 1),
                span(0, 1000, 0),
                span(2000, 3000, 2)));
        assertEquals(0, t.speakerAt(0, 1000));
        assertEquals(2, t.speakerAt(2000, 3000));
        assertEquals(1, t.speakerAt(4000, 5000));
    }

    @Test
    public void singleSpeaker_alwaysReturnsItWhenOverlapping() {
        SpeakerTimeline t = SpeakerTimeline.of(Collections.singletonList(span(0, 60000, 4)));
        assertEquals(4, t.speakerAt(0, 5000));
        assertEquals(4, t.speakerAt(55000, 60000));
        assertEquals(4, t.speakerAt(30000, 31000));
    }

    @Test
    public void partialOverlapAtSegmentEdges_stillAttributes() {
        // 识别段比说话人区间长（VAD 段通常更粗），首尾各有未覆盖部分
        SpeakerTimeline t = SpeakerTimeline.of(Collections.singletonList(span(1000, 2000, 3)));
        assertEquals(3, t.speakerAt(500, 2500));
    }

    @Test
    public void overlappingSpansFromDifferentSpeakers_picksGreaterOverlap() {
        // 说话人区间本身可交叠（同时说话的声学残留），按重叠量决胜
        SpeakerTimeline t = SpeakerTimeline.of(Arrays.asList(
                span(0, 1000, 0),
                span(800, 1200, 1)));
        // 段 0-2000：S0 重叠 1000ms，S1 重叠 400ms
        assertEquals(0, t.speakerAt(0, 2000));
        // 段 900-1200：S0 重叠 100ms，S1 重叠 300ms
        assertEquals(1, t.speakerAt(900, 1200));
    }

    // ===== compact：把任意编号压成 0..n-1（真机首个说话人是 1，不能直接用） =====

    @Test
    public void compact_remapsOneBasedIdsToZeroBased() {
        // 真机实测：单人时 sherpa 给的是 1，显示标签应为 S1 而非 S2
        SpeakerTimeline t = SpeakerTimeline.of(Collections.singletonList(span(0, 1000, 1)))
                .compact();
        assertEquals(0, t.speakerAt(0, 1000));
    }

    @Test
    public void compact_ordersByFirstAppearance() {
        // 先出现 5 后出现 2 → 5→0, 2→1（不是按编号大小排）
        SpeakerTimeline t = SpeakerTimeline.of(Arrays.asList(
                span(0, 1000, 5),
                span(1000, 2000, 2))).compact();
        assertEquals(0, t.speakerAt(0, 1000));
        assertEquals(1, t.speakerAt(1000, 2000));
    }

    @Test
    public void compact_collapsesNonContiguousIds() {
        // 0 与 2 两个说话人 → 压成 0 与 1，避免显示 S1/S3 跳号
        SpeakerTimeline t = SpeakerTimeline.of(Arrays.asList(
                span(0, 1000, 0),
                span(1000, 2000, 2))).compact();
        assertEquals(0, t.speakerAt(0, 1000));
        assertEquals(1, t.speakerAt(1000, 2000));
    }

    @Test
    public void compact_keepsSameSpeakerConsistentAcrossSpans() {
        // 同一说话人跨多个区间必须映射到同一编号
        SpeakerTimeline t = SpeakerTimeline.of(Arrays.asList(
                span(0, 1000, 1),
                span(1000, 2000, 0),
                span(2000, 3000, 1))).compact();
        assertEquals(0, t.speakerAt(0, 1000));
        assertEquals(1, t.speakerAt(1000, 2000));
        assertEquals(0, t.speakerAt(2000, 3000));
    }

    @Test
    public void compact_onEmptyTimeline_isSafe() {
        assertTrue(SpeakerTimeline.EMPTY.compact().isEmpty());
    }
}
