package com.orange.playerlibrary.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 分批规划：按字符预算把输入行切分为若干批。
 * 纯 JVM 可测；规则：优先满足字符预算，超长单行独占一批，另有最大行数双保险。
 */
public class BatchPlanner {

    public static class Batch {
        public final List<SubtitleLine> lines;

        Batch(List<SubtitleLine> lines) {
            this.lines = lines;
        }
    }

    /**
     * @param lines        按 idx 升序的输入（可能含空行，空行会被跳过不进任何批）
     * @param charBudget   每批最大字符预算
     * @param maxLines     每批最大行数（防超长单行场景）
     */
    public List<Batch> plan(List<SubtitleLine> lines, int charBudget, int maxLines) {
        List<Batch> batches = new ArrayList<>();
        List<SubtitleLine> current = new ArrayList<>();
        int currentChars = 0;

        for (SubtitleLine line : lines) {
            String text = line.getText();
            if (text == null || text.trim().isEmpty()) {
                continue;   // 空行不进 LLM（引擎对空行直接保留空译文）
            }
            int len = text.length();
            if (!current.isEmpty() && (currentChars + len > charBudget || current.size() >= maxLines)) {
                batches.add(new Batch(new ArrayList<>(current)));
                current.clear();
                currentChars = 0;
            }
            // 单行超过预算：独占一批（宁可超预算也不截断行）
            current.add(line);
            currentChars += len;
        }
        if (!current.isEmpty()) {
            batches.add(new Batch(new ArrayList<>(current)));
        }
        return batches;
    }
}
