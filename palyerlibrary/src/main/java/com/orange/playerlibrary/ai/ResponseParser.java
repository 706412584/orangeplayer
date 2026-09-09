package com.orange.playerlibrary.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 LLM 翻译输出（自定义行格式，纯文本零 JSON 依赖）：
 *   译文行   "3|译文"          —— idx|译文，分隔符容忍 | : ： . 、 空格
 *   术语行   "## 原名 -> 译名"  —— 可选，供引擎累积术语表
 * 容错：剥离 markdown 代码围栏；无法识别行忽略并计数。
 */
public class ResponseParser {

    /** "3|译文" / "3：译文" / "3. 译文" / "3 译文" */
    private static final Pattern TRANSLATION_LINE =
            Pattern.compile("^\\s*(\\d+)\\s*[|:：.、\\s]\\s*(.*)$");
    private static final Pattern GLOSSARY_LINE =
            Pattern.compile("^##\\s*(.+?)\\s*->\\s*(.+?)\\s*$");

    public static class ParseResult {
        /** idx -> 译文（仅含解析成功的行） */
        public final List<Translation> translations = new ArrayList<>();
        /** 术语增量（原名 -> 译文） */
        public final Glossary glossary = new Glossary();
        /** 无法解析的行数（超限可告警） */
        public int skippedLines;
        /** 输出中声明了译文的行数（用于评估覆盖率） */
        public int declaredCount;

        public static class Translation {
            public final int idx;
            public final String text;

            Translation(int idx, String text) {
                this.idx = idx;
                this.text = text;
            }
        }
    }

    public ParseResult parse(String rawOutput) {
        ParseResult result = new ParseResult();
        if (rawOutput == null || rawOutput.trim().isEmpty()) {
            return result;
        }
        String text = stripCodeFence(rawOutput);
        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher g = GLOSSARY_LINE.matcher(line);
            if (g.matches()) {
                result.glossary.put(g.group(1), g.group(2));
                continue;
            }
            Matcher t = TRANSLATION_LINE.matcher(line);
            if (t.matches()) {
                result.declaredCount++;
                result.translations.add(new ParseResult.Translation(
                        Integer.parseInt(t.group(1)), t.group(2).trim()));
            } else {
                result.skippedLines++;
            }
        }
        return result;
    }

    /** 剥离 ``` 代码围栏（LLM 有时会包一层 markdown） */
    private String stripCodeFence(String text) {
        String s = text.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) {
                s = s.substring(nl + 1);
            } else {
                s = "";
            }
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s;
    }
}
