import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 缺口分析：找出「实现了其他 aar 中带 default 方法的接口、但自身未声明该方法」
 * 的类 —— 即 PatchAar 需要补桥接的对象。
 *
 * 用途：打完补丁后跑一遍，确认输出 TOTAL GAPS: 0（跨 aar 缺口应完全消除；
 * 同 aar 缺口由 d8 自己处理，不是问题）。
 *
 * 判定规则与 PatchAar 保持一致（否则会误报）：
 *   - default 方法沿【接口继承链】收集；
 *   - 排除 static / private 接口方法（它们不属实现类的责任）；
 *   - 「已声明该方法」即算已实现（含 abstract 重新声明），沿类继承链查找。
 */
public class AnalyzeGaps {

    static class Info {
        String name, sup, artifact;
        int access;
        List<String> ifaces = new ArrayList<>();
        Set<String> concrete = new HashSet<>();
        Set<String> declaredNonAbstract = new HashSet<>();   // 接口自身声明的 default 方法
    }

    static Map<String, Info> all = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        String base = args[0];
        File[] arts = new File(base).listFiles(File::isDirectory);
        Arrays.sort(arts);
        for (File art : arts) {
            Files.walk(art.toPath())
                 .filter(p -> p.toString().endsWith(".class"))
                 .forEach(p -> {
                     try {
                         Info i = read(Files.readAllBytes(p));
                         if (i != null && i.name != null) {
                             i.artifact = art.getName();
                             all.putIfAbsent(i.name, i);
                         }
                     } catch (Exception ignored) {}
                 });
        }
        System.out.println("parsed classes: " + all.size());

        int ifaceCount = 0, withDefaults = 0;
        for (Info i : all.values()) {
            if ((i.access & Opcodes.ACC_INTERFACE) != 0) {
                ifaceCount++;
                if (!collectDefaults(i.name).isEmpty()) withDefaults++;
            }
        }
        System.out.println("interfaces: " + ifaceCount + ", with (inherited) defaults: " + withDefaults);

        // 每个类：收集全部（含继承）default 方法，逐个检查是否有具体实现
        Map<String, List<String>> gaps = new LinkedHashMap<>();
        for (Info c : all.values()) {
            if ((c.access & Opcodes.ACC_INTERFACE) != 0) continue;
            for (String iface : c.ifaces) {
                Info ii = all.get(iface);
                if (ii == null) continue;
                for (String dm : collectDefaults(iface)) {
                    if (!hasConcrete(c.name, dm)) {
                        String owner = ownerOfDefault(iface, dm);
                        String key = c.artifact + "  ->  " + (owner == null ? "?" : all.get(owner).artifact);
                        gaps.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(c.name.replace('/', '.') + "  :  "
                                 + (owner == null ? iface : owner).replace('/', '.') + "." + dm);
                    }
                }
            }
        }

        System.out.println();
        System.out.println("=== cross-aar default method gaps (incl. interface inheritance) ===");
        if (gaps.isEmpty()) System.out.println("(none)");
        int tot = 0;
        for (Map.Entry<String, List<String>> e : gaps.entrySet()) {
            // 去重
            List<String> uniq = new ArrayList<>(new LinkedHashSet<>(e.getValue()));
            tot += uniq.size();
            System.out.println(e.getKey() + "   (" + uniq.size() + " gaps)");
            for (String s : uniq) System.out.println("        " + s);
        }
        System.out.println("TOTAL GAPS: " + tot);
    }

    /** 沿接口继承链收集所有 default 方法 */
    static Set<String> collectDefaults(String iface) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(iface);
        while (!q.isEmpty()) {
            String cur = q.poll();
            if (!seen.add(cur)) continue;
            Info i = all.get(cur);
            if (i == null) continue;
            // 该接口自身声明的、非 abstract 的方法 = default
            for (String m : i.declaredNonAbstract) out.add(m);
            for (String p : i.ifaces) q.add(p);
        }
        return out;
    }

    /** 找出真正声明该 default 方法的接口 */
    static String ownerOfDefault(String iface, String key) {
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(iface);
        while (!q.isEmpty()) {
            String cur = q.poll();
            if (!seen.add(cur)) continue;
            Info i = all.get(cur);
            if (i == null) continue;
            if (i.declaredNonAbstract.contains(key)) return cur;
            for (String p : i.ifaces) q.add(p);
        }
        return null;
    }

    /** 具体实现（非 abstract）——沿类继承链 */
    static boolean hasConcrete(String cls, String key) {
        Set<String> seen = new HashSet<>();
        String cur = cls;
        while (cur != null && seen.add(cur)) {
            Info c = all.get(cur);
            if (c == null) return false;
            if (c.concrete.contains(key)) return true;
            cur = c.sup;
        }
        return false;
    }

    static Info read(byte[] data) {
        Info i = new Info();
        new ClassReader(data).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int access, String name, String sig, String sup, String[] ifs) {
                i.access = access; i.name = name; i.sup = sup;
                if (ifs != null) i.ifaces.addAll(Arrays.asList(ifs));
            }
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                String key = name + desc;
                if ((access & Opcodes.ACC_ABSTRACT) == 0 && !name.startsWith("<")) {
                    i.concrete.add(key);
                    // 只有非 static、非 private 的实例 default 方法才需要实现类补桥接；
                    // static 接口方法留在 接口$-CC 中，不被实现类继承（d8 实测）。
                    if ((i.access & Opcodes.ACC_INTERFACE) != 0
                            && (access & Opcodes.ACC_STATIC) == 0
                            && (access & Opcodes.ACC_PRIVATE) == 0) {
                        i.declaredNonAbstract.add(key);
                    }
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return i;
    }

}
