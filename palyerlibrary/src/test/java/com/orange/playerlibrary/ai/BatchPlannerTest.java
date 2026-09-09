package com.orange.playerlibrary.ai;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class BatchPlannerTest {

    private List<SubtitleLine> lines(String... texts) {
        List<SubtitleLine> list = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            list.add(new SubtitleLine(i, texts[i]));
        }
        return list;
    }

    @Test
    public void singleBatchWhenUnderBudget() {
        BatchPlanner planner = new BatchPlanner();
        List<BatchPlanner.Batch> batches =
                planner.plan(lines("你好", "Hello world", "こんにちは"), 1500, 200);
        assertEquals(1, batches.size());
        assertEquals(3, batches.get(0).lines.size());
    }

    @Test
    public void splitsByCharBudget() {
        BatchPlanner planner = new BatchPlanner();
        // 每行 8 字符，预算 20 → 每批约 2 行
        List<SubtitleLine> many = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            many.add(new SubtitleLine(i, "abcdefgh"));
        }
        List<BatchPlanner.Batch> batches = planner.plan(many, 20, 200);
        assertEquals(3, batches.size());
        assertEquals(2, batches.get(0).lines.size());
        assertEquals(2, batches.get(1).lines.size());
        assertEquals(2, batches.get(2).lines.size());
    }

    @Test
    public void longLineOwnsBatch() {
        BatchPlanner planner = new BatchPlanner();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append('x');   // 500 字符 > 预算
        }
        List<BatchPlanner.Batch> batches = planner.plan(
                lines("short", sb.toString(), "tail"), 100, 200);
        // 预算 100：short(5) + 超长(500) 放不下 → 超长独占一批，tail 与超长也放不下
        assertEquals(3, batches.size());
    }

    @Test
    public void maxLinesCapSplitsEvenWhenUnderBudget() {
        BatchPlanner planner = new BatchPlanner();
        List<SubtitleLine> many = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            many.add(new SubtitleLine(i, "ab"));   // 每行 2 字符，总 10 < 预算
        }
        List<BatchPlanner.Batch> batches = planner.plan(many, 1500, 3);
        assertEquals(2, batches.size());   // 3 行 + 2 行
        assertEquals(3, batches.get(0).lines.size());
        assertEquals(2, batches.get(1).lines.size());
    }

    @Test
    public void blankLinesSkipped() {
        BatchPlanner planner = new BatchPlanner();
        List<BatchPlanner.Batch> batches = planner.plan(
                lines("a", "", "  ", "b"), 1500, 200);
        assertEquals(1, batches.size());
        assertEquals(2, batches.get(0).lines.size());
        assertEquals(0, batches.get(0).lines.get(0).getIdx());
        assertEquals(3, batches.get(0).lines.get(1).getIdx());
    }
}
