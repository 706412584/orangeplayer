package com.orange.playerlibrary.track;

/**
 * 轨道信息（引擎无关的统一 DTO）
 */
public class TrackInfo {

    /** 轨道类型常量 */
    public static final int TYPE_VIDEO = 0;
    public static final int TYPE_AUDIO = 1;
    public static final int TYPE_TEXT = 2;
    public static final int TYPE_UNKNOWN = -1;

    private final int id;
    private final int type;
    private final String language;
    private final String label;
    private final boolean selected;

    public TrackInfo(int id, int type, String language, String label, boolean selected) {
        this.id = id;
        this.type = type;
        this.language = language;
        this.label = label;
        this.selected = selected;
    }

    public int getId() {
        return id;
    }

    /** {@link #TYPE_VIDEO} / {@link #TYPE_AUDIO} / {@link #TYPE_TEXT} / {@link #TYPE_UNKNOWN} */
    public int getType() {
        return type;
    }

    public String getLanguage() {
        return language;
    }

    public String getLabel() {
        return label;
    }

    public boolean isSelected() {
        return selected;
    }

    public static String typeToString(int type) {
        switch (type) {
            case TYPE_VIDEO: return "video";
            case TYPE_AUDIO: return "audio";
            case TYPE_TEXT: return "text";
            default: return "unknown";
        }
    }

    @Override
    public String toString() {
        return "TrackInfo{id=" + id + ", type=" + typeToString(type)
                + ", language=" + language + ", label=" + label + ", selected=" + selected + "}";
    }
}
