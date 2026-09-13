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

    public SubtitleEntry(long startTime, long endTime, String text) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.text = text;
        this.originalText = text;
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
                ", text='" + text + '\'' +
                '}';
    }
}
