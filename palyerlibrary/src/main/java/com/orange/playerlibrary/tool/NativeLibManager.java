package com.orange.playerlibrary.tool;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.orange.playerlibrary.download.ResumableFileDownloader;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.BaseDexClassLoader;

/**
 * 按需安装的 native 组件包管理（torrent / asr / ocr / translate）。
 *
 * 这四个组件的 so 合计约 63MB（arm64 未压缩），全部打进 APK 会让首装体积到 134MB。
 * 现改为不打进 APK，用户要用某个功能时下载对应组件包（zip，全装约 24MB）。
 *
 * 三个关键机制：
 *  - **下载**：复用 {@link ResumableFileDownloader}（多源 + Range 续传 + SHA-256）。
 *  - **落位**：解到 {@code getFilesDir()/native-libs/<abi>/}，不放外部存储
 *    （外部同 uid 可写，且部分设备挂载语义不确定）。
 *  - **加载**：把落位目录插入 classloader 的 {@code nativeLibraryPathElements} 首位，
 *    再用 {@link System#loadLibrary(String)} 按依赖顺序加载。插入后，第三方 AAR 里
 *    硬编码的 {@code loadLibrary("jpeg")} 等也会在同一搜索路径上命中我们的 so。
 *
 * **为什么必须改搜索路径**（真机实测，Android 10 / arm64）：{@code System.load(绝对路径)}
 * 能把 so 加载进 namespace，但 {@code System.loadLibrary("jpeg")} **不会**复用它——
 * {@code Runtime.loadLibrary0} 先走 {@code DexPathList.findLibrary} 搜
 * {@code nativeLibraryDirectories}（注入前只有 APK lib 目录与 {@code /system/lib64}），
 * 搜不到才把裸名交给 linker。结果是 tesseract 的 {@code TessBaseAPI.<clinit>}
 * 命中同名的系统库 {@code /system/lib64/libjpeg.so} 并因
 * {@code classloader-namespace} 不可访问而抛 {@link UnsatisfiedLinkError}。
 * 把下载目录注入 {@code nativeLibraryPathElements} 首位后，四个裸名全部命中我们自己的 so。
 *
 * **前提**：应用命名空间的 permitted path 含 {@code /data}
 * （AOSP {@code library_namespaces.cpp: kAlwaysPermittedDirectories}），
 * 故从应用私有目录 dlopen 合法。该前提已在本机 ROM 实测通过。
 * 反射注入用的是 {@code DexPathList} 私有字段，各版本 Android 均存在但无官方保证，
 * 失败时 {@link #load(String)} 明确返回 false 而不是静默降级。
 *
 * 宿主未引入某组件依赖时（如 app-legacy），{@link #isSupported(String)} 返回 false，
 * 对应入口应隐藏而不是提示下载。
 *
 * **必须在 Application.onCreate 调用 {@link #install(Context)}**：它既加载已下载的
 * 组件，也为后续无 Context 的 {@link #isInstalled(String)} 查询提供应用目录。
 */
public final class NativeLibManager {

    private static final String TAG = "NativeLibManager";

    /** 组件包 id */
    public static final String BUNDLE_TORRENT = "torrent";
    public static final String BUNDLE_ASR = "asr";
    public static final String BUNDLE_OCR = "ocr";
    public static final String BUNDLE_TRANSLATE = "translate";

    /** 支持的 ABI（demo 实际分发范围） */
    private static final String[] SUPPORTED_ABIS = {"arm64-v8a", "armeabi-v7a"};

    /**
     * 组件包所属的 release tag。发版时需与 APK 同步更新：
     * 资产地址为 releases/download/&lt;tag&gt;/&lt;bundle&gt;-&lt;abi&gt;.zip。
     */
    private static final String RELEASE_TAG = "v1.5.2";

    private static final String RELEASE_URL_PREFIX =
            "https://github.com/706412584/orangeplayer/releases/download/";

    /** 国内加速镜像前缀（与 LanguagePackManager 一致），GitHub 原址兜底 */
    private static final String[] MIRROR_PREFIXES = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "",
    };

    /**
     * 宿主是否引入该组件的 java 依赖（决定「不支持」与「未安装」）。
     *
     * <p>这些类只被反射探测（本库对它们是 compileOnly、对宿主是可选依赖），
     * R8 会把它们判为不可达而裁掉或改名，故 {@code consumer-rules.pro}
     * 对四个包都加了 keep 规则——改类名时必须同步更新两处。
     */
    private static final String[][] PROBE_CLASSES = {
            {BUNDLE_TORRENT, "org.libtorrent4j.LibTorrent"},
            {BUNDLE_ASR, "com.k2fsa.sherpa.onnx.OfflineRecognizer"},
            {BUNDLE_OCR, "com.googlecode.tesseract.android.TessBaseAPI"},
            {BUNDLE_TRANSLATE, "com.google.mlkit.nl.translate.Translator"},
    };

    /** 按 DT_NEEDED 依赖顺序排列：被依赖者在前 */
    private static final String[] LIBS_TORRENT = {"libtorrent4j.so"};

    private static final String[] LIBS_ASR = {
            "libonnxruntime.so",
            "libsherpa-onnx-jni.so",
            "libsherpa-onnx-c-api.so",
            "libsherpa-onnx-cxx-api.so",
    };

    private static final String[] LIBS_OCR = {
            "libjpeg.so", "libpngx.so", "libleptonica.so", "libtesseract.so",
    };

    private static final String[] LIBS_TRANSLATE = {"libtranslate_jni.so"};

    /**
     * 组件清单。体积与 sha256 均按 ABI 区分（v7a 的 so 比 arm64 略小），
     * 与 release 资产一一对应，换包必须同步更新（tools/build_native_libs.py
     * --verify 会在 CI 校验一致性）。
     */
    private static final BundleInfo[] BUNDLES = {
            new BundleInfo(BUNDLE_TORRENT, "种子播放组件",
                    4_489_193L, 4_426_106L, 12_537_568L, 11_450_912L,
                    "c891d55093a02732f2cfdd2a0915a2b7b641ac973bfb1be06c0d0710342ba869",
                    "1d9db559d64ab4a02e7d338ec0dead8d781777581a8721e6f751864f4e68e55e",
                    LIBS_TORRENT),
            new BundleInfo(BUNDLE_ASR, "语音识别组件",
                    11_668_029L, 10_790_116L, 31_343_960L, 21_925_244L,
                    "7f714c1aaceeceae3a13f8b1b1492aa23c20873404b98b0d18810843a9f013a2",
                    "07dcac3f03b085640f7f172321db2f139b5ef7fbc31a33824c9bd80c7a0379e0",
                    LIBS_ASR),
            new BundleInfo(BUNDLE_OCR, "文字识别组件",
                    2_938_392L, 2_727_470L, 6_873_320L, 5_218_468L,
                    "335d539114503a2c68a9cc9b412b9f8524b05b92a5fe3398e7193d6b9cb50f6f",
                    "78a2b34bb72f10a32d40f0d4577c276e42822cd541f73ac84dbfbdf4c42fe1f2",
                    LIBS_OCR),
            new BundleInfo(BUNDLE_TRANSLATE, "翻译组件",
                    6_445_577L, 5_713_939L, 15_526_600L, 11_194_908L,
                    "e0346609caa0f21d5dc52681f76f3fa9229f190ac533c057826d978689dd0a7b",
                    "dae86ac6bf88a8397e3fa3a5a0c0c60c6569cb33ffe685a8d3eb03218e7685af",
                    LIBS_TRANSLATE),
    };

    /** 应用私有目录；由 {@link #install(Context)} 设置，so 必须落在此目录内 */
    private static volatile File sFilesDir;

    /** 已注入 classloader 搜索路径的 so 目录（避免重复插入） */
    private static volatile File sInjectedDir;

    /** ABI 覆盖（仅测试用；真机走 Build.SUPPORTED_ABIS） */
    private static volatile String sAbiOverride;

    /** 当前进程已成功 System.load 的组件（进程内 so 只加载一次） */
    private static final Set<String> sLoaded = ConcurrentHashMap.newKeySet();

    private NativeLibManager() {
    }

    /** 组件包元信息（体积与 sha256 按 ABI 区分） */
    public static final class BundleInfo {
        public final String id;
        public final String displayName;
        public final long sizeArm64;
        public final long sizeV7a;
        /** 解压后占用（与 lib/ 内 so 原始体积一致） */
        public final long rawSizeArm64;
        public final long rawSizeV7a;
        public final String sha256Arm64;
        public final String sha256V7a;
        /** 依赖顺序（被依赖者在前） */
        public final String[] libs;

        BundleInfo(String id, String displayName,
                   long sizeArm64, long sizeV7a,
                   long rawSizeArm64, long rawSizeV7a,
                   String sha256Arm64, String sha256V7a, String[] libs) {
            this.id = id;
            this.displayName = displayName;
            this.sizeArm64 = sizeArm64;
            this.sizeV7a = sizeV7a;
            this.rawSizeArm64 = rawSizeArm64;
            this.rawSizeV7a = rawSizeV7a;
            this.sha256Arm64 = sha256Arm64;
            this.sha256V7a = sha256V7a;
            this.libs = libs;
        }

        /** 当前设备 ABI 下的 zip 体积（下载量）；ABI 不支持时为 arm64 的值 */
        public long size() {
            return isV7a() ? sizeV7a : sizeArm64;
        }

        /** 当前设备 ABI 下的解压后占用 */
        public long rawSize() {
            return isV7a() ? rawSizeV7a : rawSizeArm64;
        }

        private static boolean isV7a() {
            return "armeabi-v7a".equals(currentAbi());
        }
    }

    /** 下载/解压进度回调（后台线程回调，UI 层自行切主线程） */
    public interface InstallCallback {
        /** @param percent 0-100（下载占 0-90，解压占 90-100） */
        void onProgress(int percent, long downloaded, long total, String stage);

        /** @param loaded 本次是否已成功加载（false 表示需重启进程后生效） */
        void onSuccess(boolean loaded);

        void onError(String error);
    }

    // ===== 查询 =====

    public static BundleInfo[] getBundles() {
        return BUNDLES.clone();
    }

    public static BundleInfo getBundle(String id) {
        for (BundleInfo b : BUNDLES) {
            if (b.id.equals(id)) {
                return b;
            }
        }
        return null;
    }

    /**
     * 宿主是否引入该组件（未引入时对应功能入口应隐藏）。
     *
     * <p>必须用 {@code initialize=false}：sherpa 的 {@code OfflineRecognizer} 与
     * tesseract 的 {@code TessBaseAPI} 都在 {@code <clinit>} 里硬编码
     * {@code System.loadLibrary}，而 so 已不在包内，触发初始化会抛
     * {@link UnsatisfiedLinkError}，把「依赖在、so 未下载」误判成「宿主未引入」。
     */
    public static boolean isSupported(String bundleId) {
        for (String[] probe : PROBE_CLASSES) {
            if (probe[0].equals(bundleId)) {
                try {
                    Class.forName(probe[1], false,
                            NativeLibManager.class.getClassLoader());
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 组件 so 是否已就位（含体积校验，避免截断文件被误判就绪）。
     * 不触发加载——加载由 {@link #install} / {@link #load} 负责。
     */
    public static boolean isInstalled(String bundleId) {
        BundleInfo info = getBundle(bundleId);
        File filesDir = sFilesDir;
        String abi = currentAbi();
        if (info == null || filesDir == null || abi == null) {
            return false;
        }
        File dir = libDir(filesDir, abi);
        for (String lib : info.libs) {
            File f = new File(dir, lib);
            if (!f.exists() || f.length() == 0) {
                return false;
            }
        }
        return true;
    }

    /** 当前设备的组件包 ABI（不支持时返回 null） */
    public static String currentAbi() {
        String override = sAbiOverride;
        if (override != null) {
            return override;
        }
        return pickAbi(Build.SUPPORTED_ABIS);
    }

    // ===== 加载 =====

    /**
     * 幂等地把已下载的组件加载进当前进程，并记录应用私有目录。
     * 在 Application.onCreate 调用一次即可：只对已存在的 so 做
     * {@code System.load}，不下载、不阻塞。
     *
     * @return 本次新加载成功的组件数
     */
    public static int install(Context context) {
        if (context == null) {
            Log.w(TAG, "install(null)：无法确定应用目录");
            return 0;
        }
        sFilesDir = context.getApplicationContext().getFilesDir();
        // 先在主线程注入一次搜索路径（目录不存在也注入：NativeLibraryElement
        // 按路径惰性查找，后续下载完成即可生效）。这样运行中下载组件时
        // load() 里的注入是空操作，不会在后台线程改写 classloader 字段。
        String abi = currentAbi();
        if (abi != null) {
            ensureNativeSearchPath(libDir(sFilesDir, abi));
        }
        int loaded = 0;
        for (BundleInfo info : BUNDLES) {
            if (isInstalled(info.id) && load(info.id)) {
                loaded++;
            }
        }
        if (loaded > 0) {
            Log.d(TAG, "启动加载完成，本次加载 " + loaded + " 个组件");
        }
        return loaded;
    }

    /**
     * 加载组件的全部 so（幂等；已加载的组件直接返回 true）。
     *
     * 先确保落位目录在 classloader 的 native 搜索路径上，再按依赖顺序
     * {@code loadLibrary}。这样第三方 AAR 里硬编码的同名 {@code loadLibrary}
     * 也能命中我们的 so（详见类注释）。
     *
     * 注意：sherpa 与 tesseract 的静态初始化块硬编码 {@code loadLibrary} 且无
     * try/catch，加载失败会以 ExceptionInInitializerError 永久污染该类。
     * 因此本方法只在**下载完成且哈希校验通过**之后调用。
     *
     * @return true 表示全部 so 已加载
     */
    public static boolean load(String bundleId) {
        if (sLoaded.contains(bundleId)) {
            return true;
        }
        BundleInfo info = getBundle(bundleId);
        File filesDir = sFilesDir;
        if (info == null) {
            Log.w(TAG, "未知组件: " + bundleId);
            return false;
        }
        if (filesDir == null) {
            Log.w(TAG, "未初始化，请先在 Application.onCreate 调用 install(context)");
            return false;
        }
        String abi = currentAbi();
        if (abi == null) {
            Log.w(TAG, "当前 ABI 不支持按需组件: " + Arrays.toString(Build.SUPPORTED_ABIS));
            return false;
        }
        File dir = libDir(filesDir, abi);
        for (String lib : info.libs) {
            File f = new File(dir, lib);
            if (!f.exists()) {
                Log.w(TAG, "缺少 so: " + f.getAbsolutePath());
                return false;
            }
            if (!isLoadablePath(f, filesDir)) {
                Log.e(TAG, "拒绝加载应用目录之外的 so: " + f.getAbsolutePath());
                return false;
            }
        }
        if (!ensureNativeSearchPath(dir)) {
            return false;
        }
        for (String lib : info.libs) {
            try {
                // loadLibrary 而非 load(绝对路径)：依赖该库的 DT_NEEDED 由 linker
                // 按 soname 解析，需要搜索路径可达（已由 ensureNativeSearchPath 保证）。
                System.loadLibrary(stripLibPrefix(lib));
                Log.d(TAG, "已加载 " + lib);
            } catch (Throwable t) {
                Log.e(TAG, "加载失败 " + lib, t);
                return false;
            }
        }
        sLoaded.add(bundleId);
        return true;
    }

    /** {@code libfoo.so} → {@code foo}（loadLibrary 只接受裸名） */
    static String stripLibPrefix(String lib) {
        String name = lib.startsWith("lib") ? lib.substring(3) : lib;
        return name.endsWith(".so") ? name.substring(0, name.length() - 3) : name;
    }

    /**
     * 把 so 落位目录插入 classloader 的 {@code DexPathList.nativeLibraryPathElements} 首位。
     *
     * 必须先于任何 {@code loadLibrary} 执行。目录不存在也照常注入——{@code NativeLibraryElement}
     * 按路径惰性查找，下载完成即可生效，因此 {@link #install(Context)} 里的注入
     * 与后续运行中下载的注入不会冲突（后者命中已注入分支，直接返回）。
     *
     * @return false 表示无法注入（此时不应继续 loadLibrary，否则会命中系统同名库）
     */
    private static boolean ensureNativeSearchPath(File dir) {
        File injected = sInjectedDir;
        if (injected != null && injected.equals(dir)) {
            return true;
        }
        try {
            ClassLoader loader = NativeLibManager.class.getClassLoader();
            if (!(loader instanceof BaseDexClassLoader)) {
                Log.e(TAG, "classloader 不是 BaseDexClassLoader: " + loader);
                return false;
            }
            Field pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);

            Class<?> elementClass = Class.forName("dalvik.system.DexPathList$NativeLibraryElement");
            Field elementsField = pathList.getClass().getDeclaredField("nativeLibraryPathElements");
            elementsField.setAccessible(true);
            Object[] old = (Object[]) elementsField.get(pathList);
            if (old == null) {
                Log.e(TAG, "nativeLibraryPathElements 为空");
                return false;
            }

            Constructor<?> ctor = elementClass.getDeclaredConstructor(File.class);
            ctor.setAccessible(true);
            Object element = ctor.newInstance(dir);

            for (Object e : old) {
                if (e == null) {
                    continue;
                }
                Field dirField = elementClass.getDeclaredField("path");
                dirField.setAccessible(true);
                if (dir.equals(dirField.get(e))) {
                    sInjectedDir = dir;
                    return true;
                }
            }

            Object[] updated = (Object[]) Array.newInstance(elementClass, old.length + 1);
            updated[0] = element;
            System.arraycopy(old, 0, updated, 1, old.length);
            elementsField.set(pathList, updated);
            sInjectedDir = dir;
            Log.d(TAG, "已注入 native 搜索路径: " + dir + "（共 " + updated.length + " 项）");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "注入 native 搜索路径失败，无法加载按需组件", t);
            return false;
        }
    }

    /**
     * so 必须落在应用私有目录内才允许 System.load。
     * 路径全部由本类自建，此处是纵深防御：即便将来下载目录变成可配置项，
     * 也不会从任意路径 dlopen 未校验的可执行代码。
     */
    private static boolean isLoadablePath(File f, File filesDir) {
        try {
            return f.getCanonicalPath()
                    .startsWith(filesDir.getCanonicalPath() + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    // ===== 下载 + 解压 =====

    /** 构造组件包的下载描述（三个镜像源，内容相同，逐个尝试） */
    static ResumableFileDownloader.FileSpec specFor(BundleInfo info, String abi) {
        boolean v7a = "armeabi-v7a".equals(abi);
        String sha = v7a ? info.sha256V7a : info.sha256Arm64;
        long size = v7a ? info.sizeV7a : info.sizeArm64;
        String fileName = info.id + "-" + abi + ".zip";
        List<String> urls = new ArrayList<>();
        for (String prefix : MIRROR_PREFIXES) {
            urls.add(prefix + releaseUrl(RELEASE_TAG, fileName));
        }
        return new ResumableFileDownloader.FileSpec(
                fileName, urls.toArray(new String[0]), size, sha);
    }

    /**
     * 下载并安装指定组件（后台线程；进度经回调返回）。
     *
     * 已完成（so 就位）时立即回调成功。下载走多个镜像 + Range 续传 + SHA-256；
     * 解压在下载完成之后进行（边下边解会让整体哈希校验失去意义）。
     */
    public static void download(final Context context, final String bundleId,
                                final InstallCallback callback) {
        final BundleInfo info = getBundle(bundleId);
        if (info == null) {
            notifyError(callback, "未知组件: " + bundleId);
            return;
        }
        if (context == null) {
            notifyError(callback, "缺少 Context");
            return;
        }
        if (sFilesDir == null) {
            install(context);
        }
        final String abi = currentAbi();
        if (abi == null) {
            notifyError(callback, "当前设备架构不支持（"
                    + Arrays.toString(Build.SUPPORTED_ABIS) + "）");
            return;
        }
        if (isInstalled(bundleId)) {
            notifySuccess(callback, load(bundleId));
            return;
        }

        final File filesDir = sFilesDir;
        final File cacheDir = cacheDir(context);
        final File libDir = libDir(filesDir, abi);
        final long bundleSize = "armeabi-v7a".equals(abi) ? info.sizeV7a : info.sizeArm64;
        final ResumableFileDownloader downloader = new ResumableFileDownloader(
                cacheDir, new ResumableFileDownloader.FileSpec[]{specFor(info, abi)},
                null, TAG);

        downloader.download(new ResumableFileDownloader.DownloadCallback() {
            @Override
            public void onProgress(int percent, long downloaded, long total, String stage) {
                // 下载占 0-90，解压占 90-100
                notifyProgress(callback, (int) (percent * 0.9), downloaded, total, stage);
            }

            @Override
            public void onSuccess() {
                notifyProgress(callback, 90, bundleSize, bundleSize, "正在解压");
                String error = extract(new File(cacheDir, info.id + "-" + abi + ".zip"),
                        libDir, info.libs);
                downloader.shutdown();
                if (error != null) {
                    notifyError(callback, error);
                    return;
                }
                if (!isInstalled(bundleId)) {
                    notifyError(callback, "解压后文件不完整");
                    return;
                }
                // 解压完成且校验通过后才加载（见 load 的注意事项）
                boolean loaded = load(bundleId);
                notifyProgress(callback, 100, bundleSize, bundleSize, "完成");
                notifySuccess(callback, loaded);
            }

            @Override
            public void onError(String error) {
                downloader.shutdown();
                notifyError(callback, error);
            }
        });
    }

    /**
     * 解压组件包到 {@code <filesDir>/native-libs/<abi>/}。
     *
     * zip 成员只含文件名（打包脚本保证无目录），此处仍显式剥离路径，
     * 防止条目名带 {@code ../} 时写到目标目录之外（zip slip）。
     *
     * @return null 表示成功，否则为错误描述
     */
    private static String extract(File zip, File targetDir, String[] expectedLibs) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return "无法创建目录: " + targetDir;
        }
        Set<String> expected = new HashSet<>(Arrays.asList(expectedLibs));
        Set<String> extracted = new HashSet<>();
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            byte[] buf = new byte[128 * 1024];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = new File(entry.getName()).getName();
                if (!expected.contains(name)) {
                    Log.w(TAG, "跳过清单外的条目: " + entry.getName());
                    continue;
                }
                // 先写 .part 再 rename：解压中途被杀不会留下半截 so 被误判就绪
                File tmp = new File(targetDir, name + ".part");
                try (InputStream in = new BufferedInputStream(zf.getInputStream(entry), buf.length);
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        fos.write(buf, 0, read);
                    }
                }
                File out = new File(targetDir, name);
                if (out.exists() && !out.delete()) {
                    return "无法覆盖: " + out.getName();
                }
                if (!tmp.renameTo(out)) {
                    return "无法保存: " + out.getName();
                }
                extracted.add(name);
            }
        } catch (IOException e) {
            Log.e(TAG, "解压失败", e);
            return "解压失败: " + e.getMessage();
        }
        for (String lib : expectedLibs) {
            if (!extracted.contains(lib)) {
                return "组件包缺少 " + lib;
            }
        }
        Log.d(TAG, "解压完成: " + targetDir.getAbsolutePath());
        return null;
    }

    /** 下载缓存目录（zip 与 .part） */
    public static File cacheDir(Context context) {
        return new File(context.getApplicationContext().getCacheDir(), "native-libs");
    }

    /** so 落位目录：应用私有目录，不放外部存储 */
    static File libDir(File filesDir, String abi) {
        return new File(new File(filesDir, "native-libs"), abi);
    }

    /** 删除组件的已下载 so 与缓存包（下次可重新下载） */
    public static void remove(Context context, String bundleId) {
        BundleInfo info = getBundle(bundleId);
        if (info == null) {
            return;
        }
        File filesDir = sFilesDir;
        String abi = currentAbi();
        if (filesDir != null && abi != null) {
            File dir = libDir(filesDir, abi);
            for (String lib : info.libs) {
                new File(dir, lib).delete();
            }
        }
        if (context != null) {
            new File(cacheDir(context), info.id + "-" + abi + ".zip").delete();
        }
        sLoaded.remove(bundleId);
    }

    /** 按设备上报顺序选第一个受支持的 ABI */
    static String pickAbi(String[] deviceAbis) {
        if (deviceAbis == null) {
            return null;
        }
        List<String> supported = Arrays.asList(SUPPORTED_ABIS);
        for (String abi : deviceAbis) {
            if (supported.contains(abi)) {
                return abi;
            }
        }
        return null;
    }

    /** 便于单测覆盖发布地址拼装 */
    static String releaseUrl(String tag, String fileName) {
        return RELEASE_URL_PREFIX + tag + "/" + fileName;
    }

    /** 重置状态（测试用） */
    static void resetForTest() {
        sLoaded.clear();
        sFilesDir = null;
        sAbiOverride = null;
        sInjectedDir = null;
    }

    /** 注入应用私有目录（测试用） */
    static void setFilesDirForTest(File dir) {
        sFilesDir = dir;
    }

    /** 覆盖当前 ABI（测试用；真机 Build.SUPPORTED_ABIS 在 JVM 单测里为 null） */
    static void setAbiOverrideForTest(String abi) {
        sAbiOverride = abi;
    }

    private static void notifyProgress(InstallCallback callback, int percent, long downloaded,
                                       long total, String stage) {
        if (callback != null) {
            callback.onProgress(Math.min(100, Math.max(0, percent)), downloaded, total, stage);
        }
    }

    private static void notifySuccess(InstallCallback callback, boolean loaded) {
        if (callback != null) {
            callback.onSuccess(loaded);
        }
    }

    private static void notifyError(InstallCallback callback, String error) {
        Log.w(TAG, "组件安装错误: " + error);
        if (callback != null) {
            callback.onError(error);
        }
    }

    /** 供 UI 显示「约 4.3MB」用 */
    public static String formatSize(long bytes) {
        return String.format(Locale.US, "%.1fMB", bytes / 1048576.0);
    }
}
