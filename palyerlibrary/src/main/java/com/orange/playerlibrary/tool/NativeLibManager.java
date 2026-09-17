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
    /** 播放内核（可选内核，按需下载） */
    public static final String BUNDLE_IJK = "ijk";
    public static final String BUNDLE_ALI = "ali";
    public static final String BUNDLE_MPV = "mpv";
    /** m3u8 合并用的 FFmpeg 精简版（仅下载合并需要） */
    public static final String BUNDLE_FFMPEG = "ffmpeg";

    /** 支持的 ABI（demo 实际分发范围） */
    private static final String[] SUPPORTED_ABIS = {"arm64-v8a", "armeabi-v7a"};

    /**
     * 组件包所属的 release tag。发版时需与 APK 同步更新：
     * 资产地址为 releases/download/&lt;tag&gt;/&lt;bundle&gt;-&lt;abi&gt;.zip。
     */
    private static final String RELEASE_TAG = "v1.5.5";

    private static final String RELEASE_URL_PREFIX =
            "https://github.com/706412584/orangeplayer/releases/download/";

    /** Gitee 镜像（国内直连最快，优先尝试） */
    private static final String GITEE_URL_PREFIX =
            "https://gitee.com/wu-yongchengsvip/orangeplayer/releases/download/";

    /**
     * 下载源前缀，按顺序尝试（同内容，逐个回退）。
     *
     * <p>顺序依据：Gitee 国内直连最快，但它有单文件 100MB 上限——组件 zip 都在
     * 11MB 内不受影响；APK 不在本类下载范围内。其后是两个 GitHub 加速镜像，
     * 最后是 GitHub 原址兜底（海外或前几个都挂时仍可用）。
     *
     * <p>每个源都支持 Range 断点续传，切换源时已下字节保留；但跨源续传要求
     * 各源提供**同一份字节**，故组件 zip 一旦上传就不再覆盖（换包必须换 tag）。
     */
    private static final String[] MIRROR_PREFIXES = {
            GITEE_URL_PREFIX,
            "https://gh-proxy.com/" + RELEASE_URL_PREFIX,
            "https://ghfast.top/" + RELEASE_URL_PREFIX,
            RELEASE_URL_PREFIX,
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
            {BUNDLE_IJK, "tv.danmaku.ijk.media.player.IjkMediaPlayer"},
            // 不能用 NativePlayerBase：它的 <clinit> 立即调 NativeLoader.loadPlayer()，
            // 而后者把 playerLoaded 置 true 后才 loadLibrary 且只 catch Exception，
            // so 缺失时状态位已置、本进程永不重试。AliPlayerFactory 的 <clinit> 不碰加载器。
            {BUNDLE_ALI, "com.aliyun.player.AliPlayerFactory"},
            {BUNDLE_MPV, "com.orange.player.mpv.MpvPlayerManager"},
            {BUNDLE_FFMPEG, "com.orange.ffmpeg.FFmpegKit"},
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
     * IJK 顺序与 {@code IjkMediaPlayer.loadLibrariesOnce()} 硬编码的一致；
     * libijksdl/libijkplayer 的 DT_NEEDED 都指向 libijkffmpeg，反了会直接 dlopen 失败。
     */
    private static final String[] LIBS_IJK = {
            "libijkffmpeg.so", "libijksdl.so", "libijkplayer.so",
    };

    /** 阿里云 SDK 的 NativeLoader.loadPlayer() 顺序，且 libsaasDownloader 依赖前两者 */
    private static final String[] LIBS_ALI = {
            "libalivcffmpeg.so", "libsaasCorePlayer.so", "libsaasDownloader.so",
    };

    /**
     * mpv 的 10 个 so 强互依赖：libmpv 依赖全部 av*，libplayer 依赖 libmpv。
     * 按 DT_NEEDED 拓扑序（被依赖者在前）；libc++_shared 只被 libmpv/libplayer 依赖。
     */
    private static final String[] LIBS_MPV = {
            "libavutil.so", "libswresample.so", "libswscale.so", "libavfilter.so",
            "libavcodec.so", "libavformat.so", "libavdevice.so",
            "libc++_shared.so", "libmpv.so", "libplayer.so",
    };

    private static final String[] LIBS_FFMPEG = {"liborangeffmpegkit.so"};

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
            // ===== 播放内核 =====
            new BundleInfo(BUNDLE_IJK, "IJK 播放内核",
                    3_052_344L, 2_741_769L, 7_145_712L, 5_604_944L,
                    "b441ca06be12fc74f437cc420b9214dfdb41c8f08a5bdb3908b785e9d139519f",
                    "3acf9985808316db9180baa2a2f0f87171e6ab5990f50613c89e1b91383e93c8",
                    LIBS_IJK),
            new BundleInfo(BUNDLE_ALI, "阿里云播放内核",
                    4_361_846L, 4_109_824L, 11_828_288L, 9_048_916L,
                    "9147e6d358ed09fc220b39d6eaffc5ec27216cee9c5f19755d376a2a97983063",
                    "2369aa253405ac279be162b9553032352fcc182893c66e66bc2fae4a2fe09021",
                    LIBS_ALI),
            new BundleInfo(BUNDLE_MPV, "MPV 播放内核",
                    11_558_670L, 11_124_397L, 24_951_312L, 22_638_892L,
                    "8cdc4ffda645238387155c24b914611543359ee9e3104abb4b66eff4042e5f77",
                    "4ce30d24c2a643369fafb748b48008863d78cd008b7805c734e5b20d3f9aa10e",
                    LIBS_MPV),
            new BundleInfo(BUNDLE_FFMPEG, "FFmpeg 合并组件",
                    2_447_803L, 2_520_273L, 5_250_696L, 5_391_500L,
                    "98ec6245dcd7e499a427638cbeedae1ee0c18428a3c63ceea6ba1634fcbf6c7f",
                    "cb9565c362bc1838912e1e2b0a3b58278833c4f8fcc9ba2cadcabfd7c770d2ce",
                    LIBS_FFMPEG),
    };

    /** 应用私有目录；由 {@link #install(Context)} 设置，so 必须落在此目录内 */
    private static volatile File sFilesDir;

    /**
     * 「APK 内已带」是否已探测过（见 {@link #install(Context)}）。
     * 探测有真实加载副作用，且必须在下载目录进入搜索路径**之前**完成，
     * 故整个进程只做一次。
     */
    private static volatile boolean sProbed;

    /** 已注入 classloader 搜索路径的 so 目录（避免重复插入） */
    private static volatile File sInjectedDir;

    /** ABI 覆盖（仅测试用；真机走 Build.SUPPORTED_ABIS） */
    private static volatile String sAbiOverride;

    /** 当前进程已成功 System.load 的组件（进程内 so 只加载一次） */
    private static final Set<String> sLoaded = ConcurrentHashMap.newKeySet();

    /**
     * 组件 so 的来源。
     *
     * <p>区分来源是必要的：宿主把 so 打进 APK 时（SDK 的可选依赖是 compileOnly，
     * 宿主用 implementation 引入即随 APK 分发），so 一样可用，但
     * {@link #remove} 删不掉它——面板据此显示「内置」而非「删除」。
     */
    public enum InstallSource {
        /** 下载目录与 APK 内都没有 */
        NONE,
        /** 已下载到应用私有目录，可删除 */
        DOWNLOADED,
        /** 随宿主 APK 分发（或已由宿主预加载），不可删除 */
        BUNDLED,
    }

    /**
     * 探测到的「APK 内已带」组件。
     *
     * <p>由 {@link #install(Context)} 在启动时探测一次并缓存：探测手段是
     * {@code System.loadLibrary}，它有真实加载副作用，不宜在 {@link #isInstalled}
     * 这类会被面板渲染/回退判定高频调用的路径上重复执行。
     *
     * <p>只探测 so、不碰 Java 类——内核类（ali 的 NativePlayerBase、mpv 的 MPVLib）
     * 的静态块会 loadLibrary，触发即污染，见 {@link #PROBE_CLASSES} 的说明。
     */
    private static final Set<String> sBundled = ConcurrentHashMap.newKeySet();

    /** 测试用：模拟「APK 内已带」的 so 裸名（如 "mpv"）；null 表示走真实探测 */
    private static volatile Set<String> sBundledOverride;

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

    // ===== 下载中的状态（供 UI 重新打开时恢复） =====

    /**
     * 某个组件正在下载时的进度快照。
     *
     * <p>存在的理由：面板关闭后下载仍在后台跑（这是有意设计），但重新打开面板时
     * 若只看 {@link #isInstalled}，会把「正在下载」误显示成「未安装」——
     * 用户看到进度凭空消失、按钮变回「下载」，再点一次还会被拒（"下载已在进行中"）。
     * 本快照让重开的 UI 能立刻恢复进度显示。
     */
    public static final class DownloadState {
        /** 0-100（下载占 0-90，解压占 90-100） */
        public final int percent;
        public final long downloaded;
        public final long total;
        public final String stage;

        DownloadState(int percent, long downloaded, long total, String stage) {
            this.percent = percent;
            this.downloaded = downloaded;
            this.total = total;
            this.stage = stage;
        }
    }

    /** bundleId -> 当前下载进度；仅在该组件下载期间存在 */
    private static final java.util.concurrent.ConcurrentHashMap<String, DownloadState>
            sDownloadStates = new java.util.concurrent.ConcurrentHashMap<>();

    /** bundleId -> 当前接管进度的回调（面板重开时替换） */
    private static final java.util.concurrent.ConcurrentHashMap<String, InstallCallback>
            sDownloadCallbacks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 该组件是否正在下载。
     */
    public static boolean isDownloading(String bundleId) {
        return sDownloadStates.containsKey(bundleId);
    }

    /**
     * 查询下载进度快照；未在下载时返回 null。
     */
    public static DownloadState getDownloadState(String bundleId) {
        return sDownloadStates.get(bundleId);
    }

    /**
     * 让重开的 UI 接管某个正在进行的下载的进度回调。
     *
     * <p>与 {@link #download} 不同，本方法不会重新发起下载，只替换回调目标；
     * 旧面板的回调被丢弃（它已随对话框销毁）。
     *
     * @return true 表示接管成功（该组件确实在下载）
     */
    public static boolean attachDownloadCallback(String bundleId, InstallCallback callback) {
        if (!sDownloadStates.containsKey(bundleId)) {
            return false;
        }
        sDownloadCallbacks.put(bundleId, callback);
        return true;
    }

    /**
     * 解绑回调但不中断下载（面板销毁时调用，避免持有已销毁的 View）。
     */
    public static void detachDownloadCallback(String bundleId) {
        sDownloadCallbacks.remove(bundleId);
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
     * 组件 so 是否可用（已下载 **或** 随宿主 APK 分发）。
     *
     * <p>无副作用，可被面板渲染 / 内核回退判定高频调用——「APK 内已带」的探测
     * 结果由 {@link #install(Context)} 预先算好缓存（见 {@link #sBundled}）。
     */
    public static boolean isInstalled(String bundleId) {
        return installSource(bundleId) != InstallSource.NONE;
    }

    /**
     * 组件 so 的来源。宿主把 so 打进 APK 时返回 {@link InstallSource#BUNDLED}，
     * 面板据此显示「内置」而非「删除」（{@link #remove} 删不掉 APK 内的 so）。
     *
     * <p>判定顺序为 **APK 优先**：APK 内已带时不再要求下载目录也有——否则宿主
     * 内置 so 后会被误判为「未安装」，既静默回退系统内核又让用户白下一份。
     */
    public static InstallSource installSource(String bundleId) {
        BundleInfo info = getBundle(bundleId);
        if (info == null) {
            return InstallSource.NONE;
        }
        if (isBundled(bundleId)) {
            return InstallSource.BUNDLED;
        }
        if (isDownloaded(info)) {
            return InstallSource.DOWNLOADED;
        }
        return InstallSource.NONE;
    }

    /** 该组件是否可删除（仅下载来的可删；APK 内置的删不掉） */
    public static boolean canRemove(String bundleId) {
        return installSource(bundleId) == InstallSource.DOWNLOADED;
    }

    /**
     * APK 内是否已带该组件的全部 so。
     *
     * <p>集合里存的是 **bundle id**（不是 lib 名）：只有该组件的每个 so 都
     * {@code loadLibrary} 成功，{@link #probeBundled()} 才记入 id——半带不带
     * （宿主只引入了部分 so）不算可用。
     */
    private static boolean isBundled(String bundleId) {
        Set<String> override = sBundledOverride;
        return (override != null ? override : sBundled).contains(bundleId);
    }

    /** 下载目录里是否已有该组件的全部 so（含体积校验，避免截断文件被误判就绪） */
    private static boolean isDownloaded(BundleInfo info) {
        File filesDir = sFilesDir;
        String abi = currentAbi();
        if (filesDir == null || abi == null) {
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
        // 必须在注入下载目录之前探测「APK 内已带」：注入后下载目录位于搜索路径
        // 首位，会遮蔽 APK 里的同名 so，届时探测到的是下载版本（来源被误判）。
        //
        // 且**只在首次**探测：install() 会被面板每次打开时重复调用，而首次之后
        // 下载目录已在搜索路径上，再探测时 loadLibrary 会命中下载来的 so，
        // 把「已下载」误判成「APK 内置」——真机表现是下载完成后重开面板，
        // 状态从「已安装 · 可删除」变成「已内置 · 随应用分发」且按钮置灰。
        //
        // 用独立标记而非 sInjectedDir：后者只在注入成功时才赋值，ABI 不受支持
        // 或注入失败时恒为 null，探测会反复执行（等于没修）。
        if (!sProbed) {
            probeBundled();
            sProbed = true;
        }
        // 再注入一次搜索路径（目录不存在也注入：NativeLibraryElement
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
     * 探测哪些组件的 so 是随宿主 APK 分发的，结果写入 {@link #sBundled}。
     *
     * <p>为什么用 {@code System.loadLibrary} 而不是查文件：{@code extractNativeLibs=false}
     * 时 APK 内的 so 不从 APK 解压出来（真机实测 {@code /data/app/<pkg>/lib/arm64/} 是空
     * 目录），直接从 APK mmap，文件系统上查不到。只有实际尝试加载才能判定。
     *
     * <p>只对 {@link #isSupported} 为真的组件探测——宿主没引入依赖时不会有 so，
     * 试也是白试。探测**不触发 Java 类初始化**（只 load so，不 Class.forName），
     * 故不会污染 ali/mpv 内核类的静态块。
     */
    private static void probeBundled() {
        if (sBundledOverride != null) {
            return; // 测试注入优先
        }
        sBundled.clear();
        for (BundleInfo info : BUNDLES) {
            if (!isSupported(info.id)) {
                continue;
            }
            boolean allPresent = true;
            for (String lib : info.libs) {
                try {
                    System.loadLibrary(stripLibPrefix(lib));
                } catch (Throwable t) {
                    allPresent = false;
                    break;
                }
            }
            if (allPresent) {
                sBundled.add(info.id);
                Log.d(TAG, "检测到 APK 内置组件: " + info.id);
            }
        }
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
        // APK 内已带的组件：so 在 APK 的 lib 目录里，本就在 classloader 的搜索路径上，
        // 直接 loadLibrary 即可。**不能**注入下载目录——那会把它插到搜索路径首位，
        // 遮蔽 APK 里的同名 so（如 libc++_shared.so），并让 isLoadablePath 误拒。
        if (isBundled(bundleId)) {
            return loadEach(bundleId, info, "内置");
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
        return loadEach(bundleId, info, "已下载");
    }

    /** 按依赖顺序 loadLibrary 该组件的全部 so；全部成功才标记已加载 */
    private static boolean loadEach(String bundleId, BundleInfo info, String source) {
        for (String lib : info.libs) {
            try {
                // loadLibrary 而非 load(绝对路径)：依赖该库的 DT_NEEDED 由 linker
                // 按 soname 解析，需要搜索路径可达（内置的走 APK lib 目录，
                // 下载的已由 ensureNativeSearchPath 保证）。
                System.loadLibrary(stripLibPrefix(lib));
                Log.d(TAG, "已加载 " + lib + "（" + source + "）");
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

    /**
     * 构造组件包的下载描述。
     *
     * <p>{@link #MIRROR_PREFIXES} 里每项已是「前缀 + release 路径」的完整前缀，
     * 这里只需再拼 tag 与文件名。逐个尝试，前一个失败自动切下一个。
     */
    static ResumableFileDownloader.FileSpec specFor(BundleInfo info, String abi) {
        boolean v7a = "armeabi-v7a".equals(abi);
        String sha = v7a ? info.sha256V7a : info.sha256Arm64;
        long size = v7a ? info.sizeV7a : info.sizeArm64;
        String fileName = info.id + "-" + abi + ".zip";
        List<String> urls = new ArrayList<>();
        for (String prefix : MIRROR_PREFIXES) {
            urls.add(prefix + RELEASE_TAG + "/" + fileName);
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

        // 同一组件已在下载：不重复发起，让本次调用接管进度显示。
        // 面板关闭后重开时会走到这里——旧回调已随对话框失效，必须换成新回调。
        if (isDownloading(bundleId)) {
            if (callback != null) {
                sDownloadCallbacks.put(bundleId, callback);
                DownloadState st = sDownloadStates.get(bundleId);
                if (st != null) {
                    callback.onProgress(st.percent, st.downloaded, st.total, st.stage);
                }
            }
            return;
        }

        final File filesDir = sFilesDir;
        final File cacheDir = cacheDir(context);
        final File libDir = libDir(filesDir, abi);
        final long bundleSize = "armeabi-v7a".equals(abi) ? info.sizeV7a : info.sizeArm64;

        Log.d(TAG, "下载 " + bundleId + ": abi=" + abi
                + " filesDir=" + filesDir
                + " cacheDir=" + cacheDir.getAbsolutePath()
                + " libDir=" + libDir.getAbsolutePath());

        // 先登记回调与初始状态，再发起下载：download() 是异步的，
        // 期间面板若重开必须能查到「正在下载」。
        if (callback != null) {
            sDownloadCallbacks.put(bundleId, callback);
        }
        sDownloadStates.put(bundleId, new DownloadState(0, 0, bundleSize, "准备下载"));

        final ResumableFileDownloader downloader = new ResumableFileDownloader(
                cacheDir, new ResumableFileDownloader.FileSpec[]{specFor(info, abi)},
                null, TAG);

        downloader.download(new ResumableFileDownloader.DownloadCallback() {
            @Override
            public void onProgress(int percent, long downloaded, long total, String stage) {
                // 下载占 0-90，解压占 90-100
                notifyProgress(bundleId, (int) (percent * 0.9), downloaded, total, stage);
            }

            @Override
            public void onSuccess() {
                notifyProgress(bundleId, 90, bundleSize, bundleSize, "正在解压");
                Log.d(TAG, "下载完成 " + bundleId + "，解压到 " + libDir.getAbsolutePath());
                String error = extract(new File(cacheDir, info.id + "-" + abi + ".zip"),
                        libDir, info.libs);
                downloader.shutdown();
                if (error != null) {
                    Log.e(TAG, "解压失败 " + bundleId + ": " + error);
                    clearDownloadState(bundleId);
                    notifyError(sDownloadCallbacks.get(bundleId), error);
                    return;
                }
                if (!isInstalled(bundleId)) {
                    Log.e(TAG, "解压后仍判未安装: " + bundleId
                            + " isDownloaded=" + isDownloaded(info)
                            + " isBundled=" + isBundled(bundleId)
                            + " source=" + installSource(bundleId));
                    clearDownloadState(bundleId);
                    notifyError(sDownloadCallbacks.get(bundleId), "解压后文件不完整");
                    return;
                }
                // 解压完成且校验通过后才加载（见 load 的注意事项）
                boolean loaded = load(bundleId);
                if (loaded) {
                    resetEngineAfterInstall(bundleId);
                }
                Log.d(TAG, "安装结束 " + bundleId + ": loaded=" + loaded
                        + " source=" + installSource(bundleId));
                notifyProgress(bundleId, 100, bundleSize, bundleSize, "完成");
                InstallCallback cb = sDownloadCallbacks.get(bundleId);
                clearDownloadState(bundleId);
                if (cb != null) {
                    cb.onSuccess(loaded);
                }
            }

            @Override
            public void onError(String error) {
                downloader.shutdown();
                InstallCallback cb = sDownloadCallbacks.get(bundleId);
                clearDownloadState(bundleId);
                notifyError(cb, error);
            }
        });
    }

    /**
     * 组件安装完成后，重置那些「失败即永久锁定」的引擎缓存。
     *
     * <p>目前只有 FFmpegKit：它的 {@code init()} 在 so 缺失时进入 stub 模式并置位
     * {@code sInitialized}，此后本进程内不再重试。下载完成后重置即可免重启生效。
     * 其余内核的加载走 {@code loadLibrary}，失败不会锁死，无需处理。
     */
    private static void resetEngineAfterInstall(String bundleId) {
        if (!BUNDLE_FFMPEG.equals(bundleId)) {
            return;
        }
        try {
            com.orange.ffmpeg.FFmpegKit.resetForRetry();
            Log.d(TAG, "已重置 FFmpegKit，下载的组件本次运行即可用");
        } catch (Throwable t) {
            // 宿主未引入 ffmpeg 模块时类不存在，属正常情况
            Log.d(TAG, "FFmpegKit 不可用，跳过重置: " + t.getMessage());
        }
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
        sBundled.clear();
        sBundledOverride = null;
        sFilesDir = null;
        sAbiOverride = null;
        sInjectedDir = null;
        sProbed = false;
    }

    /** 注入应用私有目录（测试用） */
    static void setFilesDirForTest(File dir) {
        sFilesDir = dir;
    }

    /** 覆盖当前 ABI（测试用；真机 Build.SUPPORTED_ABIS 在 JVM 单测里为 null） */
    static void setAbiOverrideForTest(String abi) {
        sAbiOverride = abi;
    }

    /**
     * 模拟「APK 内已带」的组件 id 集合（测试用）。
     *
     * <p>真机判定靠 {@code System.loadLibrary} 实测，JVM 单测里不可用（无 Android
     * linker），故用注入替代。传 null 恢复真实探测。
     */
    static void setBundledLibsForTest(Set<String> bundleIds) {
        sBundledOverride = bundleIds;
    }

    private static void notifyProgress(InstallCallback callback, int percent, long downloaded,
                                       long total, String stage) {
        int p = Math.min(100, Math.max(0, percent));
        if (callback != null) {
            callback.onProgress(p, downloaded, total, stage);
        }
    }

    /**
     * 带 bundleId 的进度通知：同时刷新状态表（供重开的 UI 查询）并转发给当前回调。
     */
    private static void notifyProgress(String bundleId, int percent, long downloaded,
                                       long total, String stage) {
        int p = Math.min(100, Math.max(0, percent));
        sDownloadStates.put(bundleId, new DownloadState(p, downloaded, total, stage));
        notifyProgress(sDownloadCallbacks.get(bundleId), p, downloaded, total, stage);
    }

    /** 下载结束（成功/失败/取消）时清理状态，避免面板一直显示「下载中」 */
    private static void clearDownloadState(String bundleId) {
        sDownloadStates.remove(bundleId);
        sDownloadCallbacks.remove(bundleId);
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
