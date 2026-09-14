package com.orange.playerlibrary.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 说话人标签与 SRT 标记的单元测试。
 *
 * 核心不变式：**{@code getText()} 永远是纯文本**。标记只存在于 SRT 行首与显示串，
 * 因为翻译引擎取的就是 getText()，混入 {@code [S1]} 会让译文带上标记。
 */
public class SubtitleEntrySpeakerTest {

    @Test
    public void defaultEntryHasNoSpeaker() {
        SubtitleEntry e = new SubtitleEntry(0, 1000, "hello");
        assertFalse(e.hasSpeaker());
        assertEquals(SubtitleEntry.NO_SPEAKER, e.getSpeaker());
        assertNull(e.speakerLabel());
    }

    @Test
    public void speakerZeroDisplaysAsS1() {
        SubtitleEntry e = new SubtitleEntry(0, 1000, "hello");
        e.setSpeaker(0);
        assertTrue(e.hasSpeaker());
        assertEquals("S1", e.speakerLabel());
    }

    @Test
    public void speakerOneDisplaysAsS2() {
        SubtitleEntry e = new SubtitleEntry(0, 1000, "hello");
        e.setSpeaker(1);
        assertEquals("S2", e.speakerLabel());
    }

    @Test
    public void negativeSpeakerMeansNoLabel() {
        SubtitleEntry e = new SubtitleEntry(0, 1000, "hello");
        e.setSpeaker(-1);
        assertFalse(e.hasSpeaker());
        assertNull(e.speakerLabel());
    }

    // ===== 标记解析 =====

    @Test
    public void parseSpeakerPrefix_readsLabel() {
        assertEquals(0, SubtitleEntry.parseSpeakerPrefix("[S1] hello"));
        assertEquals(1, SubtitleEntry.parseSpeakerPrefix("[S2] hello"));
        assertEquals(11, SubtitleEntry.parseSpeakerPrefix("[S12] hello"));
    }

    @Test
    public void parseSpeakerPrefix_toleratesLowercaseAndLeadingSpace() {
        assertEquals(0, SubtitleEntry.parseSpeakerPrefix("  [s1] hello"));
    }

    @Test
    public void parseSpeakerPrefix_rejectsNonLabels() {
        assertEquals(SubtitleEntry.NO_SPEAKER, SubtitleEntry.parseSpeakerPrefix("hello"));
        assertEquals(SubtitleEntry.NO_SPEAKER, SubtitleEntry.parseSpeakerPrefix(null));
        // 方括号但不是说话人标记：不能误吃正文
        assertEquals(SubtitleEntry.NO_SPEAKER, SubtitleEntry.parseSpeakerPrefix("[音乐] hello"));
        assertEquals(SubtitleEntry.NO_SPEAKER, SubtitleEntry.parseSpeakerPrefix("[S] hello"));
        assertEquals(SubtitleEntry.NO_SPEAKER, SubtitleEntry.parseSpeakerPrefix("[S1a] hello"));
        // S0 不是合法标签（编号从 1 起）
        assertEquals(SubtitleEntry.NO_SPEAKER, SubtitleEntry.parseSpeakerPrefix("[S0] hello"));
    }

    @Test
    public void stripSpeakerPrefix_removesLabel() {
        assertEquals("hello", SubtitleEntry.stripSpeakerPrefix("[S1] hello"));
        assertEquals("hello", SubtitleEntry.stripSpeakerPrefix("[S12]   hello"));
    }

    @Test
    public void stripSpeakerPrefix_keepsPlainText() {
        assertEquals("hello", SubtitleEntry.stripSpeakerPrefix("hello"));
        assertEquals("[音乐] hello", SubtitleEntry.stripSpeakerPrefix("[音乐] hello"));
        assertNull(SubtitleEntry.stripSpeakerPrefix(null));
    }

    @Test
    public void stripSpeakerPrefix_leavesEmptyWhenOnlyLabel() {
        assertEquals("", SubtitleEntry.stripSpeakerPrefix("[S1]"));
    }

    @Test
    public void parseAndStrip_areConsistent() {
        // 两者必须同一口径：能解析出编号的，必须能被剥离
        String[] samples = {"[S1] a", "[S2] b", "[S12] c", "plain", "[音乐] d", "[S] e"};
        for (String s : samples) {
            boolean parsed = SubtitleEntry.parseSpeakerPrefix(s) != SubtitleEntry.NO_SPEAKER;
            boolean stripped = !s.equals(SubtitleEntry.stripSpeakerPrefix(s));
            assertEquals("口径不一致: " + s, parsed, stripped);
        }
    }

    @Test
    public void originalText_staysCleanWhenSpeakerSet() {
        // 说话人是独立字段，设置它不得触碰 text/originalText
        SubtitleEntry e = new SubtitleEntry(0, 1000, "纯文本");
        e.setSpeaker(2);
        assertEquals("纯文本", e.getText());
        assertEquals("纯文本", e.getOriginalText());
    }
}
