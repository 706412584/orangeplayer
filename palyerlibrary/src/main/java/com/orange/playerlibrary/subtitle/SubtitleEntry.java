package com.orange.playerlibrary.subtitle;

/**
 * 字幕条目
 */
public class SubtitleEntry {
    private long startTime;  // 开始时间（毫秒）
    private long endTime;    // 结束时间（毫秒）
    private String text;     // 字幕文本（AI 翻译后此处是译文）
    /**
     * 翻译前的原文；未翻译时与 {@link #text} 同引用。
     *
     * AI 批翻译是**覆盖式**回写（见 SubtitleManager.applyAiTranslationFrom），译文
     * 直接写进 text。若不单独留一份原文，切换翻译语言时就没有可译的源文本，只能
     * 重新加载字幕或重启播放——真机实测的「换语言不生效」即由此而来。
     */
    private String originalText;

    /**
     * 说话人编号（0 起，与显示标签 {@code [S1]} 差 1）；{@link #NO_SPEAKER} 表示无归属。
     *
     * <p>**独立字段，绝不拼进 {@link #text}**：text 同时是 AI 翻译的输入
     * （AiTranslationEngine/BatchPlanner 都取 getText()），把 {@code [S1]} 混进去
     * 会让译文里出现标记，也会污染翻译缓存键。
     */
    private int speaker = NO_SPEAKER;

    /** 无说话人归属 */
    public static final int NO_SPEAKER = -1;

    public SubtitleEntry(long startTime, long endTime, String text) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.text = text;
        this.originalText = text;
    }

    public int getSpeaker() {
        return speaker;
    }

    public void setSpeaker(int speaker) {
        this.speaker = speaker;
    }

    public boolean hasSpeaker() {
        return speaker >= 0;
    }

    /**
     * 显示/落盘用的说话人标签，如 {@code S1}（编号 0 → S1，与 MOSS 的 [S01] 同风格）。
     * 无归属时返回 null。
     */
    public String speakerLabel() {
        return speaker < 0 ? null : "S" + (speaker + 1);
    }

    /**
     * 从行首剥离说话人标记，如 {@code "[S1] 你好"} → {@code "你好"}。
     * 非标记开头时原样返回（含 null）。
     */
    public static String stripSpeakerPrefix(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (!t.startsWith("[")) {
            return text;
        }
        int close = t.indexOf(']');
        if (close < 3 || close > 5) {
            return text;
        }
        String inner = t.substring(1, close);
        if (inner.charAt(0) != 'S' && inner.charAt(0) != 's') {
            return text;
        }
        for (int i = 1; i < inner.length(); i++) {
            if (!Character.isDigit(inner.charAt(i))) {
                return text;
            }
        }
        return t.substring(close + 1).trim();
    }

    /**
     * 从行首解析说话人编号（0 起）。{@code "[S1] xxx"} → 0；无标记或格式不符 → {@link #NO_SPEAKER}。
     * 与 {@link #stripSpeakerPrefix} 的识别口径保持一致。
     */
    public static int parseSpeakerPrefix(String text) {
        if (text == null) {
            return NO_SPEAKER;
        }
        String t = text.trim();
        if (!t.startsWith("[")) {
            return NO_SPEAKER;
        }
        int close = t.indexOf(']');
        if (close < 3 || close > 5) {
            return NO_SPEAKER;
        }
        String inner = t.substring(1, close);
        if (inner.charAt(0) != 'S' && inner.charAt(0) != 's') {
            return NO_SPEAKER;
        }
        for (int i = 1; i < inner.length(); i++) {
            if (!Character.isDigit(inner.charAt(i))) {
                return NO_SPEAKER;
            }
        }
        try {
            int n = Integer.parseInt(inner.substring(1));
            return n >= 1 ? n - 1 : NO_SPEAKER;
        } catch (NumberFormatException e) {
            return NO_SPEAKER;
        }
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    /** 翻译前的原文（未翻译时即 {@link #getText()}） */
    public String getOriginalText() {
        return originalText;
    }

    public long getDuration() {
        return endTime - startTime;
    }

    /**
     * 显示用尾部标点（字幕惯例不留句末标点）：中英文句末与子句标点、成对符号的收尾。
     * 中间标点保留（如「服务好，功能强大」的逗号）。
     */
    private static final String TRAILING_PUNCT = "，。！？；：、,.!?;:…～~\"'）)】」』》>";

    /**
     * 去掉文本尾部的标点与空白，用于字幕显示。
     * 尾部空白与标点交替出现时一并跳过（如「结束 。 」→「结束」）；
     * 全部由标点组成时返回空串，调用方据此隐藏该条。
     */
    public static String stripTrailingPunctuation(String text) {
        if (text == null) {
            return null;
        }
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (TRAILING_PUNCT.indexOf(c) >= 0 || Character.isWhitespace(c)) {
                end--;
            } else {
                break;
            }
        }
        return text.substring(0, end);
    }

    @Override
    public String toString() {
        return "SubtitleEntry{" +
                "startTime=" + startTime +
                ", endTime=" + endTime +
                ", speaker=" + speaker +
                ", text='" + text + '\'' +
                '}';
    }
}
