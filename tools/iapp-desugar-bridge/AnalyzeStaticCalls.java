import org.objectweb.asm.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * 自检：扫描目录下全部 aar/jar，统计「跨文件的静态接口方法调用」剩余缺口。
 *
 * <p>与 {@link AnalyzeGaps}（default 方法桥接）配套：iApp 一文件一 dex、
 * min-api&lt;24，接口的 static 方法被脱糖到 {@code Iface$-CC}。调用方与接口
 * 分属不同文件时 dexer 不改写 → 运行期 NoSuchMethodError。
 * {@link PatchAar} 负责改写，本类负责验证改写是否彻底。
 *
 * <p>退出码：缺口为 0 时 0，否则 1。末行固定输出 {@code TOTAL STATIC GAPS: n}。
 *
 * 用法：java -cp asm.jar:. AnalyzeStaticCalls <dirWithAarsOrJars>
 */
public class AnalyzeStaticCalls {

    static final Map<String, Set<String>> ifaceStatic = new LinkedHashMap<>();
    static final Map<String, String> ifaceFile = new LinkedHashMap<>();
    static int gaps = 0;

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".aar") || n.endsWith(".jar"));
        if (files == null) {
            System.out.println("目录不存在或为空: " + args[0]);
            System.out.println("TOTAL STATIC GAPS: 0");
            return;
        }
        Arrays.sort(files);

        // pass 1: 索引接口及其 static 方法
        for (File f : files) {
            for (Map.Entry<String, byte[]> e : classesOf(f).entrySet()) {
                ClassReader cr = new ClassReader(e.getValue());
                if ((cr.getAccess() & Opcodes.ACC_INTERFACE) == 0) continue;
                String name = cr.getClassName();
                ifaceFile.putIfAbsent(name, f.getName());
                Set<String> ms = ifaceStatic.computeIfAbsent(name, k -> new HashSet<>());
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int a, String mn, String md,
                                                               String s, String[] ex) {
                        if ((a & Opcodes.ACC_STATIC) != 0
                                && (a & Opcodes.ACC_PRIVATE) == 0
                                && !mn.startsWith("<")) {
                            ms.add(mn + md);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }

        // pass 2: 找未被改写的跨文件调用点
        System.out.println("=== 跨文件静态接口调用缺口 ===");
        for (File f : files) {
            for (Map.Entry<String, byte[]> e : classesOf(f).entrySet()) {
                ClassReader cr = new ClassReader(e.getValue());
                String cls = cr.getClassName();
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int a, String mn, String md,
                                                               String s, String[] ex) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int op, String owner,
                                                                  String name, String desc,
                                                                  boolean itf) {
                                if (op != Opcodes.INVOKESTATIC || !itf) return;
                                Set<String> st = ifaceStatic.get(owner);
                                String ownerFile = ifaceFile.get(owner);
                                if (st == null || ownerFile == null) return;
                                if (ownerFile.equals(f.getName())) return;   // 同文件，d8 会自己补
                                if (!st.contains(name + desc)) return;
                                gaps++;
                                System.out.println("  [缺口] " + f.getName() + " :: "
                                        + cls + "." + mn + "  ->  " + owner + "." + name + desc
                                        + "   (接口在 " + ownerFile + ")");
                            }
                        };
                    }
                }, 0);
            }
        }
        if (gaps == 0) System.out.println("(none)");
        System.out.println("TOTAL STATIC GAPS: " + gaps);
        System.exit(gaps == 0 ? 0 : 1);
    }

    static Map<String, byte[]> classesOf(File f) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(f)) {
            ZipEntry cj = zf.getEntry("classes.jar");
            if (cj != null) {
                try (ZipInputStream zis = new ZipInputStream(zf.getInputStream(cj))) {
                    readZip(zis, out);
                }
            } else if (f.getName().endsWith(".jar")) {
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(f))) {
                    readZip(zis, out);
                }
            }
        }
        return out;
    }

    static void readZip(ZipInputStream zis, Map<String, byte[]> out) throws IOException {
        ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zis.getNextEntry()) != null) {
            if (!e.getName().endsWith(".class")) continue;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int n;
            while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
            out.put(e.getName(), bos.toByteArray());
        }
    }
}
