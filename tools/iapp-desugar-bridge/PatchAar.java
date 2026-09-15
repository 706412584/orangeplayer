import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * 给 aar 中的类补上「接口 default 方法的转发桥接」，修复 iApp 侧构建的
 * AbstractMethodError。
 *
 * == 问题 ==
 * iApp 对 sdk/ 下每个依赖文件【独立 dex】（实测：一个 aar 恰好对应一个 dex，
 * 54 个 dex 无任何混装），且其 dexer 以 min-api < 24 运行，于是接口的
 * default 方法被脱糖成「抽象方法 + 接口$-CC.$default$xxx」。实现类与接口
 * 分属不同 dex 时，dexer 看不到接口的 default 信息，不会给实现类补转发
 * 方法 —— 运行时接口方法为 abstract、实现类又没覆盖，抛 AbstractMethodError。
 *
 * 实测证据（d8 警告直接点名）：
 *   Type `androidx.media3.exoplayer.analytics.AnalyticsListener` was not found,
 *   it is required for default or static interface methods desugaring of
 *   `tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer`
 *
 * 为什么换 maven 依赖治不了：exo_player2 等本地 aar 没有对应的 1.5.4 远程
 * 坐标（Maven Central 上 io.github.706412584 只发到 1.4.2），它永远是本地
 * 文件、永远单独 dex。
 *
 * == 做法 ==
 * 按 d8 的脱糖约定，为「实现了跨文件接口、但未覆盖其 default 方法」的类补
 * 转发方法（与 d8 产物逐字节同形）：
 *     public synthetic void onXxx(args) {
 *         Iface$-CC.$default$onXxx(this, args);
 *     }
 * 每个 dex 由此自给自足，不再依赖 dexer 是否合并处理。
 *
 * == 必须遵守的 d8 语义（均已实测对照）==
 *  1. default 方法要【沿接口继承链】收集。class C implements B、而 default
 *     声明在 B 的父接口 A 上时，d8 同样给 C 补桥接。
 *  2. static 接口方法【不】进实现类（它作为 s 留在 接口$-CC 里），
 *     private 接口方法同理 —— 都不能补。
 *  3. 只要类【已声明】该方法（哪怕是把 default 重新声明为 abstract），
 *     就绝不能再插一个 —— 否则重复方法 = 非法 class 文件，ART 抛
 *     ClassFormatError。抽象类由子类负责实现，不是「缺失」。
 *  4. 同 aar 内的缺口不用管：这些类会一起 dex，d8 自己会补。
 *
 * == 用法 ==
 *   java -cp asm.jar:. PatchAar <inDirOfAars> <outDir> [aarName...]
 * 不给 aarName 则处理 inDir 下全部 aar。通常经由 patch_aars.py 调用。
 */
public class PatchAar {

    static class Info {
        String name, sup;
        int access;
        List<String> ifaces = new ArrayList<>();
        Set<String> methods = new HashSet<>();
        Set<String> concrete = new HashSet<>();                 // 有方法体（非 abstract）
        Map<String, String> defaults = new LinkedHashMap<>();   // 自身声明的 default: name+desc -> name
        String artifact;
    }

    static Map<String, Info> lib = new LinkedHashMap<>();
    static int totalBridges = 0, totalClasses = 0;

    /**
     * 沿接口继承链收集全部 default 方法 -> 声明它的接口。
     * 必须做这一步：class C implements B、而 default 方法声明在 B 的父接口 A 上时，
     * d8 同样会给 C 补桥接（实测），只查直接接口会漏。
     */
    static Map<String, String> collectDefaults(String iface) {
        Map<String, String> out = new LinkedHashMap<>();   // key -> 声明接口
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(iface);
        while (!q.isEmpty()) {
            String cur = q.poll();
            if (!seen.add(cur)) continue;
            Info i = lib.get(cur);
            if (i == null) continue;
            for (String key : i.defaults.keySet()) {
                out.putIfAbsent(key, cur);
            }
            for (String p : i.ifaces) q.add(p);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        String inDir = args[0];
        String outDir = args[1];
        Set<String> only = new HashSet<>();
        for (int i = 2; i < args.length; i++) only.add(args[i]);

        // 1) 索引所有 aar 的类（作为「全局 classpath」用于解析接口与父类链）
        List<File> aars = new ArrayList<>();
        for (File f : Objects.requireNonNull(new File(inDir).listFiles())) {
            if (f.getName().endsWith(".aar")) {
                aars.add(f);
                loadJar(jarOf(f), f.getName());
            }
        }
        Collections.sort(aars);
        System.out.println("lib classes: " + lib.size() + "  from aars: " + aars.size());

        new File(outDir).mkdirs();

        for (File aar : aars) {
            if (!only.isEmpty() && !only.contains(aar.getName())) continue;
            Map<String, byte[]> entries = readZip(aar);
            byte[] cj = entries.get("classes.jar");
            if (cj == null) {
                System.out.println("[skip] " + aar.getName() + " (no classes.jar)");
                copy(aar, new File(outDir, aar.getName()));
                continue;
            }
            Map<String, byte[]> cls = readZip(cj);
            // 目标 aar 自身的类信息
            Map<String, Info> scope = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> e : cls.entrySet()) {
                if (!e.getKey().endsWith(".class")) continue;
                Info i = parse(e.getValue());
                if (i != null && i.name != null) scope.put(i.name, i);
            }

            int patched = 0, bridges = 0;
            for (Info c : scope.values()) {
                if ((c.access & Opcodes.ACC_INTERFACE) != 0) continue;
                List<String> missing = new ArrayList<>();
                for (String iface : c.ifaces) {
                    // 沿接口继承链收集（含父接口声明的 default）
                    Map<String, String> dms = collectDefaults(iface);
                    if (dms.isEmpty()) continue;
                    for (Map.Entry<String, String> dm : dms.entrySet()) {
                        String key = dm.getKey();
                        String owner = dm.getValue();      // 真正声明 default 的接口
                        if (!hasMethod(c.name, key, scope)) {
                            missing.add(owner + "\u0000" + key);
                        }
                    }
                }
                if (missing.isEmpty()) continue;

                String entry = c.name + ".class";
                byte[] orig = cls.get(entry);
                if (orig == null) continue;

                // iface+key -> (ifaceOwner, $-CC owner, key)
                Map<String, String[]> impl = new LinkedHashMap<>();
                for (String spec : missing) {
                    String[] p = spec.split("\u0000", 2);
                    String iface = p[0], key = p[1];
                    impl.put(key, new String[]{iface, iface + "$-CC", key});
                }

                ClassReader cr = new ClassReader(orig);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                final String self = c.name;
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override public void visitEnd() {
                        for (Map.Entry<String, String[]> b : impl.entrySet()) {
                            String key = b.getKey();
                            String iface = b.getValue()[0];
                            String ccOwner = b.getValue()[1];
                            int p = key.indexOf('(');
                            String mName = key.substring(0, p);
                            String mDesc = key.substring(p);

                            MethodVisitor mv = super.visitMethod(
                                    Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                                    mName, mDesc, null, null);
                            mv.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            Type[] at = Type.getArgumentTypes(mDesc);
                            int slot = 1;
                            for (Type t : at) {
                                mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
                                slot += t.getSize();
                            }
                            String ccDesc = "(L" + iface + ";" + mDesc.substring(1);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, ccOwner,
                                    "$default$" + mName, ccDesc, false);
                            Type rt = Type.getReturnType(mDesc);
                            mv.visitInsn(rt.getOpcode(Opcodes.IRETURN));
                            mv.visitMaxs(0, 0);
                            mv.visitEnd();
                        }
                        super.visitEnd();
                    }
                }, 0);

                cls.put(entry, cw.toByteArray());
                patched++;
                bridges += impl.size();
            }

            totalBridges += bridges;
            totalClasses += patched;
            if (patched > 0) {
                System.out.printf("%-46s patched %3d classes, +%4d bridges%n",
                        aar.getName(), patched, bridges);
            }
            // 写回 aar
            entries.put("classes.jar", writeZip(cls));
            writeZipToFile(entries, new File(outDir, aar.getName()));
        }
        System.out.println();
        System.out.println("TOTAL: patched " + totalClasses + " classes, added "
                           + totalBridges + " bridge methods");
    }

    /**
     * 判断该类是否「已声明」该方法（含 abstract 声明）。
     *
     * 关键：只要方法名+签名已存在就返回 true，绝不能再插一个同名方法 ——
     * 否则会产生重复方法（如 abstract void m() 与 void m() 并存），
     * 产出非法 class 文件，ART 加载时抛 ClassFormatError。
     *
     * 典型场景：class D implements A { public abstract void m(); }
     * A 的 m 是 default，D 把它重新声明为 abstract —— 这不是「缺失」，
     * D 是抽象类，由子类负责实现。
     */
    static boolean hasMethod(String cls, String key, Map<String, Info> scope) {
        Set<String> seen = new HashSet<>();
        String cur = cls;
        while (cur != null && seen.add(cur)) {
            Info c = scope.get(cur);
            if (c == null) c = lib.get(cur);
            if (c == null) return false;
            if (c.methods.contains(key)) return true;
            cur = c.sup;
        }
        return false;
    }

    static Info parse(byte[] data) {
        Info i = new Info();
        new ClassReader(data).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int access, String name, String sig, String sup, String[] ifs) {
                i.access = access; i.name = name; i.sup = sup;
                if (ifs != null) i.ifaces.addAll(Arrays.asList(ifs));
            }
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                String key = name + desc;
                i.methods.add(key);
                if ((access & Opcodes.ACC_ABSTRACT) == 0) {
                    i.concrete.add(key);
                    // 只有「非 static、非 private 的实例 default 方法」才需要给实现类补桥接。
                    // static 接口方法留在 接口$-CC 里（名为 s），不被实现类继承 —— d8 实测如此。
                    if (!name.startsWith("<")
                            && (access & Opcodes.ACC_STATIC) == 0
                            && (access & Opcodes.ACC_PRIVATE) == 0) {
                        i.defaults.put(key, name);
                    }
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return i;
    }

    static byte[] jarOf(File aar) throws IOException {
        Map<String, byte[]> e = readZip(aar);
        byte[] cj = e.get("classes.jar");
        return cj == null ? null : cj;
    }

    static void loadJar(byte[] jar, String tag) {
        if (jar == null) return;
        try (JarInputStream jis = new JarInputStream(new ByteArrayInputStream(jar))) {
            JarEntry je;
            while ((je = jis.getNextJarEntry()) != null) {
                if (je.isDirectory() || !je.getName().endsWith(".class")) continue;
                byte[] data = readAll(jis);
                Info i = parse(data);
                if (i != null && i.name != null) lib.putIfAbsent(i.name, i);
            }
        } catch (IOException ignored) {}
    }

    static Map<String, byte[]> readZip(File f) throws IOException {
        return readZip(Files.readAllBytes(f.toPath()));
    }

    static Map<String, byte[]> readZip(byte[] data) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(new ByteArrayInputStream(data))) {
            java.util.zip.ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (ze.isDirectory()) continue;
                out.put(ze.getName(), readAll(zis));
            }
        }
        return out;
    }

    static byte[] writeZip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bo)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(e.getKey());
                ze.setTime(0L);
                zos.putNextEntry(ze);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bo.toByteArray();
    }

    static void writeZipToFile(Map<String, byte[]> entries, File f) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(writeZip(entries));
        }
    }

    static void copy(File src, File dst) throws IOException {
        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
