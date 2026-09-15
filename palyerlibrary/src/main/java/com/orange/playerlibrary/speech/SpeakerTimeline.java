package com.orange.playerlibrary.speech;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 说话人时间线（纯逻辑，可单测）。
 *
 * <p>离线说话人分离（diarization）产出的是「时间区间 → 说话人编号」的有序列表，
 * 而 ASR 产出的是「VAD 段边界 → 文本」。两者边界**不对齐**：diarization 的区间
 * 通常比 VAD 段细，一段识别文本可能横跨同一说话人的多个区间，也可能跨到另一个
 * 说话人（VAD 静音阈值与 diarization 切分阈值不同所致）。
 *
 * <p>因此归属判定用**区间重叠**而非「包含」：取与该段重叠最多的说话人。
 * 无任何重叠时返回 {@link #NO_SPEAKER}——宁可没有标签，也不要给错标签。
 */
public final class SpeakerTimeline {

    /** 无归属说话人（引擎未启用分离 / 该段无重叠 / 时间线为空） */
    public static final int NO_SPEAKER = -1;

    /** 一个说话人区间（毫秒） */
    public static final class Span {
        private final long startMs;
        private final long endMs;
        private final int speaker;

        public Span(long startMs, long endMs, int speaker) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.speaker = speaker;
        }

        public long getStartMs() {
            return startMs;
        }

        public long getEndMs() {
            return endMs;
        }

        public int getSpeaker() {
            return speaker;
        }

        @Override
        public String toString() {
            return "Span{" + startMs + "-" + endMs + " S" + speaker + "}";
        }
    }

    /** 空时间线（未启用 diarization 时用它代替 null，省去调用方判空） */
    public static final SpeakerTimeline EMPTY = new SpeakerTimeline(Collections.<Span>emptyList());

    private final List<Span> mSpans;

    private SpeakerTimeline(List<Span> spans) {
        mSpans = spans;
    }

    /**
     * 由 diarization 区间构建时间线。区间按起点排序并丢弃非法项
     * （end &lt;= start、speaker &lt; 0），使后续查询无需再校验。
     */
    public static SpeakerTimeline of(List<Span> spans) {
        if (spans == null || spans.isEmpty()) {
            return EMPTY;
        }
        List<Span> valid = new ArrayList<>(spans.size());
        for (Span s : spans) {
            if (s != null && s.endMs > s.startMs && s.speaker >= 0) {
                valid.add(s);
            }
        }
        if (valid.isEmpty()) {
            return EMPTY;
        }
        Collections.sort(valid, new Comparator<Span>() {
            @Override
            public int compare(Span a, Span b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });
        return new SpeakerTimeline(Collections.unmodifiableList(valid));
    }

    /**
     * 把任意说话人编号重映射为按**首次出现顺序**的 0..n-1。
     *
     * <p>sherpa 的聚类编号既不保证从 0 起、也不保证连续（真机实测首个说话人为 1；
     * 也可能只产出 0 与 2）。直接拿来当显示标签会出现「只有一个人却显示 S2」，
     * 故展示前统一压缩。
     *
     * @return 新时间线；本对象为空时原样返回
     */
    public SpeakerTimeline compact() {
        if (mSpans.isEmpty()) {
            return this;
        }
        java.util.Map<Integer, Integer> remap = new java.util.HashMap<>();
        List<Span> out = new ArrayList<>(mSpans.size());
        for (Span s : mSpans) {
            Integer mapped = remap.get(s.speaker);
            if (mapped == null) {
                mapped = remap.size();
                remap.put(s.speaker, mapped);
            }
            out.add(new Span(s.startMs, s.endMs, mapped));
        }
        return new SpeakerTimeline(Collections.unmodifiableList(out));
    }

    public boolean isEmpty() {
        return mSpans.isEmpty();
    }

    public int size() {
        return mSpans.size();
    }

    /**
     * 判定一段识别结果所属的说话人：取与该段重叠最多的那个。
     *
     * <p>重叠按说话人**累加**——同一说话人在该段内出现多个区间时合并计算，
     * 否则「说话人 A 说了 0-2s 和 4-6s、B 说了 2-4s」会被判成 B。
     *
     * @param startMs 段起点（毫秒）
     * @param endMs   段终点（毫秒）
     * @return 说话人编号（0 起）；无重叠或时间线为空时 {@link #NO_SPEAKER}
     */
    public int speakerAt(long startMs, long endMs) {
        if (mSpans.isEmpty() || endMs <= startMs) {
            return NO_SPEAKER;
        }
        // 编号未必连续（聚类可能给出 0/2），故用两条平行数组而非下标直接寻址
        int[] speakers = new int[mSpans.size()];
        long[] overlap = new long[mSpans.size()];
        int n = 0;

        for (Span s : mSpans) {
            // 已按起点排序：起点已越过段尾的区间及其后全部不可能重叠
            if (s.startMs >= endMs) {
                break;
            }
            long lo = Math.max(s.startMs, startMs);
            long hi = Math.min(s.endMs, endMs);
            if (hi <= lo) {
                continue;
            }
            long ov = hi - lo;
            int idx = indexOf(speakers, n, s.speaker);
            if (idx < 0) {
                speakers[n] = s.speaker;
                overlap[n] = ov;
                n++;
            } else {
                overlap[idx] += ov;
            }
        }

        int best = NO_SPEAKER;
        long bestOv = 0;
        for (int i = 0; i < n; i++) {
            // 严格大于：平票时保留先遇到的（时间更早的）说话人，保证结果稳定
            if (overlap[i] > bestOv) {
                bestOv = overlap[i];
                best = speakers[i];
            }
        }
        return best;
    }

    private static int indexOf(int[] arr, int len, int value) {
        for (int i = 0; i < len; i++) {
            if (arr[i] == value) {
                return i;
            }
        }
        return -1;
    }
}
