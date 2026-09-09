package com.orange.playerlibrary.ai;

/**
 * 待翻译字幕行。
 * idx 为输入顺序号（0 起），引擎按 idx 一一对应译文，
 * 缓存与断点续传均以 idx 为键，不受文本内容重复影响。
 */
public class SubtitleLine {

    private final int idx;
    private final String text;   // 原文
    private String translated;   // 译文，null 表示尚未翻译成功

    public SubtitleLine(int idx, String text) {
        this.idx = idx;
        this.text = text == null ? "" : text;
    }

    public int getIdx() {
        return idx;
    }

    public String getText() {
        return text;
    }

    public boolean hasTranslation() {
        return translated != null && !translated.trim().isEmpty();
    }

    public String getTranslated() {
        return translated;
    }

    public void setTranslated(String translated) {
        this.translated = translated;
    }
}
