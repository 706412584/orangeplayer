package com.orange.playerlibrary.subtitle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ASS/SSA 字幕的纯文本降级解析器。
 * <p>
 * 提取 Dialogue 行的文本与时间轴，剥离 {\tags} 覆写标签；
 * 识别 [V4+ Styles] 段的 Style 行与 [Events] 段的 Dialogue 行。
 * 不渲染样式/定位/卡拉OK效果（Exo 引擎请走 Media3 Cue 管线获得完整样式）。
 * <p>
 * 时间格式: H:MM:SS.cc（centiseconds）
 */
public final class AssParser {

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(\\d+):(\\d{2}):(\\d{2})[.](\\d{2})");

    private static final Pattern DIALOGUE_PATTERN = Pattern.compile(
            "^Dialogue:\\s*([^,]*),([^,]*),([^,]*),([^,]*),([^,]*),([^,]*),([^,]*),([^,]*),([^,]*),(.*)$");

    private AssParser() {
    }

    /**
     * 检测内容是否为 ASS/SSA 字幕
     */
    public static boolean isAssContent(String content) {
        if (content == null) {
            return false;
        }
        // ASS/SSA 头部标记：[Script Info] 或 [V4+ Styles] 或 [Events] 段
        // 检查前几行足够（部分文件前部可能有 BOM/空行）
        String head = content.length() > 2048 ? content.substring(0, 2048) : content;
        return head.contains("[Script Info]")
                || head.contains("[V4+ Styles]")
                || head.contains("[V4 Styles]")
                || head.contains("[Events]");
    }

    /**
     * 解析 ASS/SSA 内容为纯文本字幕条目
     *
     * @return 条目列表；无法解析出任何 Dialogue 时返回空列表（调用方应视为失败）
     */
    public static List<SubtitleEntry> parse(String content) {
        List<SubtitleEntry> entries = new ArrayList<>();
        if (content == null) {
            return entries;
        }

        String format = "Text"; // [Events] Format 默认最后一列为 Text
        for (String rawLine : content.split("\n")) {
            String line = rawLine.replace("\r", "").trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("Format:") && line.contains("Layer")) {
                // 例如: Format: Layer, Start, End, Style, Name, MarginL, ...
                format = line;
                continue;
            }
            if (!line.startsWith("Dialogue:")) {
                continue;
            }

            // 固定 9 字段 + Text（ASS 标准）；部分变体字段数不同则跳过
            String body = line.substring("Dialogue:".length()).trim();
            String[] parts = splitCsv(body, 10);
            if (parts.length < 10) {
                continue;
            }
            long start = parseAssTime(parts[1]);
            long end = parseAssTime(parts[2]);
            if (start < 0 || end < 0 || end <= start) {
                continue;
            }
            String text = stripOverrideTags(parts[9]);
            if (text.isEmpty()) {
                continue;
            }
            entries.add(new SubtitleEntry(start, end, text));
        }
        return entries;
    }

    /**
     * 解析 ASS Style 段（供后续样式增强使用，本版本仅校验段存在）
     */
    public static Map<String, String> parseStyles(String content) {
        Map<String, String> styles = new HashMap<>();
        if (content == null) {
            return styles;
        }
        boolean inStyles = false;
        for (String rawLine : content.split("\n")) {
            String line = rawLine.replace("\r", "").trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                inStyles = line.contains("Styles");
                continue;
            }
            if (inStyles && line.startsWith("Style:")) {
                styles.put(line, line);
            }
        }
        return styles;
    }

    /**
     * 剥离 {\...} 覆写标签；\N 与 \n 转换为换行
     */
    static String stripOverrideTags(String text) {
        if (text == null) {
            return "";
        }
        String result = text.replaceAll("\\{[^}]*\\}", "");
        result = result.replace("\\N", "\n").replace("\\n", "\n");
        // 多行折叠首尾空白
        return result.trim();
    }

    /**
     * 解析 H:MM:SS.cc 为毫秒；非法返回 -1
     */
    static long parseAssTime(String time) {
        if (time == null) {
            return -1;
        }
        Matcher m = TIME_PATTERN.matcher(time.trim());
        if (!m.find()) {
            return -1;
        }
        try {
            long hours = Long.parseLong(m.group(1));
            long minutes = Long.parseLong(m.group(2));
            long seconds = Long.parseLong(m.group(3));
            long centis = Long.parseLong(m.group(4));
            return hours * 3600000 + minutes * 60000 + seconds * 1000 + centis * 10;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 按逗号切分为至多 maxParts 份（最后一份保留剩余内容，含逗号）
     */
    private static String[] splitCsv(String line, int maxParts) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < line.length() && parts.size() < maxParts - 1; i++) {
            if (line.charAt(i) == ',') {
                parts.add(line.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(line.substring(start));
        return parts.toArray(new String[0]);
    }
}
