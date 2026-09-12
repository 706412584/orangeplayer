package com.orange.playerlibrary.speech;

import android.content.Context;
import android.util.Log;

import com.orange.playerlibrary.subtitle.SubtitleEntry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ASR 识别结果缓存（按视频 URL 落盘）。
 *
 * 解决两个问题：
 *  1. 同一视频重看时不再整片重新识别——命中即直接加载字幕；
 *  2. 边看边识别中断（换集/退出）后，已识别的块被记住，下次只补识别缺失段。
 *
 * 目录结构（{@code getExternalFilesDir/asr_cache/<urlMd5>/}）：
 * <pre>
 *   full.srt   整片识别结果——存在即视为该视频已识别完，直接用它
 *   part.srt   渐进识别已完成的块拼成的字幕
 *   blocks.txt 已识别块的起始毫秒列表（每行一个），与 part.srt 配套
 * </pre>
 *
 * blocks 只在 {@code part.srt} 里**连续存在**时才被采信：字幕条目是逐块追加的，
 * 若写入过程中断电/杀进程，part.srt 可能只包含前若干块的内容，此时按 blocks.txt
 * 记录的块数去预填「已完成」会让缺失的块永远不再识别。
 */
public final class AsrSubtitleCache {

    private static final String TAG = "AsrSubtitleCache";

    private static final String DIR_NAME = "asr_cache";
    private static final String FULL_SRT = "full.srt";
    private static final String PART_SRT = "part.srt";
    private static final String BLOCKS_TXT = "blocks.txt";

    /** 缓存保留时长：超期目录在启动清扫时删除 */
    private static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    /** 整片识别结果：时间轴 + 文本（含 blocks 供增量识别续接） */
    public static final class Snapshot {
        private final List<SubtitleEntry> mEntries;
        private final Set<Long> mDoneBlocks;
        private final boolean mComplete;

        Snapshot(List<SubtitleEntry> entries, Set<Long> doneBlocks, boolean complete) {
            mEntries = entries;
            mDoneBlocks = doneBlocks;
            mComplete = complete;
        }

        public List<SubtitleEntry> getEntries() {
            return mEntries;
        }

        /** 已识别块起始毫秒；{@link #isComplete()} 为 true 时无意义 */
        public Set<Long> getDoneBlocks() {
            return mDoneBlocks;
        }

        /** true=整片已识别完，可直接加载字幕、无需再启动识别 */
        public boolean isComplete() {
            return mComplete;
        }
    }

    private AsrSubtitleCache() {
    }

    private static File dirFor(Context context, String url) {
        return new File(new File(context.getExternalFilesDir(null), DIR_NAME), md5(url));
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // MD5 是 JDK 必备算法；退化为长度+哈希码，避免缓存功能整体失效
            return Integer.toHexString(s.hashCode()) + "_" + s.length();
        }
    }

    /**
     * 读取缓存。整片识别过时 {@code isComplete()} 为 true（{@code getDoneBlocks()} 为空集）；
     * 只有部分块识别过时返回已在 part.srt 中落盘的块与字幕。
     * 无缓存返回 null。
     */
    public static Snapshot load(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) {
            return null;
        }
        try {
            File dir = dirFor(context, url);
            File full = new File(dir, FULL_SRT);
            if (full.exists()) {
                List<SubtitleEntry> entries = parseSrt(full);
                if (!entries.isEmpty()) {
                    Log.d(TAG, "命中整片缓存: " + entries.size() + " 条");
                    return new Snapshot(entries, new HashSet<Long>(), true);
                }
            }
            File part = new File(dir, PART_SRT);
            if (!part.exists()) {
                return null;
            }
            List<SubtitleEntry> entries = parseSrt(part);
            Set<Long> blocks = readBlocks(new File(dir, BLOCKS_TXT));
            if (entries.isEmpty() || blocks.isEmpty()) {
                return null;
            }
            Log.d(TAG, "命中部分缓存: " + entries.size() + " 条 / " + blocks.size() + " 块");
            return new Snapshot(entries, blocks, false);
        } catch (Throwable t) {
            Log.w(TAG, "读取 ASR 缓存失败", t);
            return null;
        }
    }

    /** 整片缓存的 srt 文件；不存在时返回 null（供调用方直接交给字幕管理器加载） */
    public static File fullSrtFile(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) {
            return null;
        }
        File f = new File(dirFor(context, url), FULL_SRT);
        return f.exists() ? f : null;
    }

    /** 写入整片识别结果（完整版识别成功后调用） */
    public static void saveFull(Context context, String url, List<SubtitleEntry> entries) {
        if (context == null || url == null || url.isEmpty() || entries == null || entries.isEmpty()) {
            return;
        }
        try {
            File dir = dirFor(context, url);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            writeSrt(new File(dir, FULL_SRT), entries);
            new File(dir, BLOCKS_TXT).delete();   // 整片已覆盖，增量标记不再需要
            Log.d(TAG, "写入整片缓存: " + entries.size() + " 条");
        } catch (Throwable t) {
            Log.w(TAG, "写入整片缓存失败", t);
        }
    }

    /**
     * 追加一个已识别块的条目（渐进识别每块完成时调用）。
     *
     * part.srt 与 blocks.txt 必须同步推进：先写 blocks 后写 srt 会让「块已标记完成
     * 但字幕不存在」，故这里反过来——先落 srt，成功后再落 blocks。
     */
    public static void appendBlock(Context context, String url, long blockStartMs,
                                   List<SubtitleEntry> entries) {
        if (context == null || url == null || url.isEmpty()) {
            return;
        }
        try {
            File dir = dirFor(context, url);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File part = new File(dir, PART_SRT);
            List<SubtitleEntry> all = part.exists() ? parseSrt(part) : new ArrayList<>();
            if (entries != null && !entries.isEmpty()) {
                all.addAll(entries);
            }
            writeSrt(part, all);
            Set<Long> blocks = readBlocks(new File(dir, BLOCKS_TXT));
            blocks.add(blockStartMs);
            writeBlocks(new File(dir, BLOCKS_TXT), blocks);
        } catch (Throwable t) {
            Log.w(TAG, "追加块缓存失败", t);
        }
    }

    /**
     * 渐进识别覆盖到末尾时把 part 提升为整片缓存。
     * 调用方需确认已识别到视频结尾，否则会把「只看了前半段」误标为整片已识别。
     */
    public static void promoteToFull(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) {
            return;
        }
        try {
            File dir = dirFor(context, url);
            File part = new File(dir, PART_SRT);
            if (!part.exists()) {
                return;
            }
            java.nio.file.Files.copy(part.toPath(), new File(dir, FULL_SRT).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            new File(dir, BLOCKS_TXT).delete();
            Log.d(TAG, "增量缓存提升为整片缓存");
        } catch (Throwable t) {
            Log.w(TAG, "提升整片缓存失败", t);
        }
    }

    /** 清扫超期缓存目录（启动时调用一次，幂等） */
    public static void cleanupOrphanCache(Context context) {
        if (context == null) {
            return;
        }
        try {
            File root = new File(context.getExternalFilesDir(null), DIR_NAME);
            File[] dirs = root.listFiles();
            if (dirs == null) {
                return;
            }
            long now = System.currentTimeMillis();
            int deleted = 0;
            for (File d : dirs) {
                if (d != null && now - d.lastModified() > MAX_AGE_MS && deleteRecursively(d)) {
                    deleted++;
                }
            }
            if (deleted > 0) {
                Log.d(TAG, "清扫超期 ASR 缓存: " + deleted + " 个");
            }
        } catch (Throwable t) {
            Log.w(TAG, "清扫 ASR 缓存失败", t);
        }
    }

    private static boolean deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return false;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        return f.delete();
    }

    /** 写 SRT；文本中的换行会让解析错位，写入前压成单行 */
    static void writeSrt(File file, List<SubtitleEntry> entries) throws IOException {
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            for (int i = 0; i < entries.size(); i++) {
                SubtitleEntry e = entries.get(i);
                w.write((i + 1) + "\n");
                w.write(AsrSubtitleGenerator.formatSrtTime(e.getStartTime())
                        + " --> " + AsrSubtitleGenerator.formatSrtTime(e.getEndTime()) + "\n");
                w.write(flatten(e.getText()) + "\n\n");
            }
        }
    }

    /** 压成单行：正文里的换行会被解析成新条目，制表符/连续空白也会让时间轴错位 */
    private static String flatten(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /** 解析 SRT（仅需本类自写格式：序号 / 时间行 / 单行文本） */
    public static List<SubtitleEntry> parseSrt(File file) throws IOException {
        List<SubtitleEntry> out = new ArrayList<>();
        String content = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(new StringReader(content));
        String line;
        long start = -1;
        long end = -1;
        StringBuilder text = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (start >= 0 && text.length() > 0) {
                    out.add(new SubtitleEntry(start, end, text.toString()));
                }
                start = -1;
                text.setLength(0);
                continue;
            }
            int arrow = t.indexOf("-->");
            if (arrow >= 0) {
                long s = parseSrtTime(t.substring(0, arrow).trim());
                long e = parseSrtTime(t.substring(arrow + 3).trim());
                // 两侧都能解析成时间戳才算时间行——字幕正文本身可能含 "-->"
                if (s >= 0 && e >= 0) {
                    start = s;
                    end = e;
                    continue;
                }
            }
            if (start >= 0) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(t);
            }
            // start < 0 时是序号行，忽略
        }
        if (start >= 0 && text.length() > 0) {
            out.add(new SubtitleEntry(start, end, text.toString()));
        }
        return out;
    }

    /** SRT 时间戳 HH:MM:SS,mmm → 毫秒；格式不识别返回 -1 */
    private static long parseSrtTime(String s) {
        try {
            int c1 = s.indexOf(':');
            int c2 = s.indexOf(':', c1 + 1);
            int comma = s.indexOf(',');
            if (c1 < 0 || c2 < 0 || comma < 0) {
                return -1;
            }
            long h = Long.parseLong(s.substring(0, c1));
            long m = Long.parseLong(s.substring(c1 + 1, c2));
            long sec = Long.parseLong(s.substring(c2 + 1, comma));
            long ms = Long.parseLong(s.substring(comma + 1));
            return ((h * 60 + m) * 60 + sec) * 1000 + ms;
        } catch (Exception e) {
            return -1;
        }
    }

    private static Set<Long> readBlocks(File file) {
        Set<Long> out = new HashSet<>();
        if (!file.exists()) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    out.add(Long.parseLong(t));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取 blocks.txt 失败", t);
        }
        return out;
    }

    private static void writeBlocks(File file, Set<Long> blocks) throws IOException {
        List<Long> sorted = new ArrayList<>(blocks);
        java.util.Collections.sort(sorted);
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            for (Long b : sorted) {
                w.write(b + "\n");
            }
        }
    }
}
