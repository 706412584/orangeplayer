package com.orange.playerlibrary.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 术语表：批间累积的专名/术语映射（原文 -> 译文），保证全片人名、专名一致。
 * 顺序稳定（插入序），序列化为行格式 "原名 -> 译名"，与缓存/响应解析同构。
 */
public class Glossary {

    private final LinkedHashMap<String, String> entries = new LinkedHashMap<>();

    /** 记录一条术语（已存在时覆盖译文并保持原位置） */
    public void put(String source, String target) {
        String s = source == null ? "" : source.trim();
        String t = target == null ? "" : target.trim();
        if (s.isEmpty() || t.isEmpty() || s.equalsIgnoreCase(t)) {
            return;
        }
        // LinkedHashMap 覆盖值不改变插入位置
        entries.put(s, t);
    }

    public Map<String, String> getEntries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public String get(String source) {
        return entries.get(source);
    }

    /** 序列化为缓存/消息中的多行文本（每行 "原名 -> 译名"） */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            sb.append(e.getKey()).append(" -> ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** 从多行文本装载（反向于 toText），供缓存恢复 */
    public void loadFromText(String text) {
        entries.clear();
        if (text == null || text.isEmpty()) {
            return;
        }
        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int arrow = line.indexOf("->");
            if (arrow > 0) {
                String src = line.substring(0, arrow).trim();
                String dst = line.substring(arrow + 2).trim();
                if (!src.isEmpty() && !dst.isEmpty()) {
                    entries.put(src, dst);
                }
            }
        }
    }
}
