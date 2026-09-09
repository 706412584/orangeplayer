package com.orange.playerlibrary.ai;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 翻译缓存持久化：每批成功即落盘，断点续传跳过已译行。
 * 自写行格式（零依赖、纯 JVM 可测）：
 *   v1
 *   I:<idx>:<译文>        每行一条译文（idx 从 0 起，严格递增）
 *   G:<原名> -> <译文>     术语表行（可多条）
 * 原文不入盘——缓存键 (videoUri|src|target|model) 已锁定输入；
 * 译文文本中的换行以 \n 字面量转义（单条字幕无换行属常态，转义兜底）。
 */
public class TranslationCacheStore {

    private final File cacheDir;
    private final String cacheKey;

    public TranslationCacheStore(File cacheDir, String cacheKey) {
        this.cacheDir = cacheDir;
        this.cacheKey = cacheKey;
    }

    /** 缓存文件路径（key 已 hash 化，外部传入的 key 可为任意视频标识） */
    public File getCacheFile() {
        return new File(cacheDir, "subtitle_trans_" + Integer.toHexString(cacheKey.hashCode()) + ".txt");
    }

    /** 译文数组（下标 = idx），null 表示该行未翻译 */
    public String[] load() {
        File f = getCacheFile();
        if (!f.exists()) {
            return null;
        }
        try {
            String content = readFile(f);
            String[] lines = content.split("\n");
            String[] translations = null;
            int maxIdx = -1;

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.equals("v1")) {
                    continue;
                }
                if (line.startsWith("I:")) {
                    int c1 = line.indexOf(':', 2);
                    if (c1 < 0) {
                        continue;
                    }
                    try {
                        int idx = Integer.parseInt(line.substring(2, c1).trim());
                        String text = unescape(line.substring(c1 + 1));
                        if (idx > maxIdx) {
                            maxIdx = idx;
                        }
                        if (translations == null) {
                            translations = new String[maxIdx + 1];
                        } else if (idx >= translations.length) {
                            String[] grown = new String[idx + 1];
                            System.arraycopy(translations, 0, grown, 0, translations.length);
                            translations = grown;
                        }
                        translations[idx] = text;
                    } catch (NumberFormatException ignored) {
                        // 损坏行忽略
                    }
                }
            }
            return translations;
        } catch (IOException e) {
            return null;   // 读失败视为无缓存，下次重译
        }
    }

    /** 全量写盘（引擎每批完成后调用） */
    public void save(String[] translations, Glossary glossary) throws IOException {        File dir = cacheDir;
        File f = getCacheFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建缓存目录: " + dir);
        }
        StringBuilder sb = new StringBuilder("v1\n");
        if (translations != null) {
            for (int i = 0; i < translations.length; i++) {
                String t = translations[i];
                if (t != null) {
                    sb.append("I:").append(i).append(':').append(escape(t)).append('\n');
                }
            }
        }
        if (glossary != null) {
            for (Map.Entry<String, String> e : glossary.getEntries().entrySet()) {
                sb.append("G:").append(e.getKey()).append(" -> ").append(e.getValue()).append('\n');
            }
        }
        writeFile(f, sb.toString());
    }

    public boolean exists() {
        return getCacheFile().exists();
    }

    /** 读取缓存内累积的术语表（无则返回空表） */
    public Glossary loadGlossary() {
        Glossary g = new Glossary();
        File f = getCacheFile();
        if (!f.exists()) {
            return g;
        }
        try {
            String content = readFile(f);
            String[] lines = content.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.startsWith("G:")) {
                    continue;
                }
                String body = line.substring(2);
                int arrow = body.indexOf("->");
                if (arrow > 0) {
                    g.put(body.substring(0, arrow).trim(), body.substring(arrow + 2).trim());
                }
            }
        } catch (IOException ignored) {
        }
        return g;
    }

    public void delete() {
        File f = getCacheFile();
        if (f.exists()) {
            f.delete();
        }
    }

    // ===== 转义：译文可能含 \n 与字面 "\\n" =====

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n");
    }

    static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == 'n') {
                    sb.append('\n');
                    i++;
                    continue;
                }
                if (next == '\\') {
                    sb.append('\\');
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private String readFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 4 * 1024 * 1024)];
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private void writeFile(File f, String content) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }
}
