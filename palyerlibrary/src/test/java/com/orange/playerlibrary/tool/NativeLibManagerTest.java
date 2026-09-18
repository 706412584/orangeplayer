package com.orange.playerlibrary.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.orange.playerlibrary.download.ResumableFileDownloader;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/**
 * NativeLibManager 清单与就位判定的单测。
 *
 * 覆盖不依赖真机/网络的部分：ABI 选择、发布地址拼装、下载描述、so 就位判定。
 * so 的实际 dlopen 只能在真机验证（见计划 §验证）。
 */
public class NativeLibManagerTest {

    @Rule
    public TemporaryFolder mTemp = new TemporaryFolder();

    @After
    public void tearDown() {
        NativeLibManager.resetForTest();
    }

    @Test
    public void pickAbiPrefersFirstSupportedInDeviceOrder() {
        assertEquals("arm64-v8a", NativeLibManager.pickAbi(new String[]{"arm64-v8a"}));
        assertEquals("armeabi-v7a", NativeLibManager.pickAbi(new String[]{"armeabi-v7a"}));
        // 设备上报顺序优先：armeabi-v7a 在前的设备不会误选 arm64
        assertEquals("armeabi-v7a",
                NativeLibManager.pickAbi(new String[]{"armeabi-v7a", "arm64-v8a"}));
        assertEquals("arm64-v8a",
                NativeLibManager.pickAbi(new String[]{"arm64-v8a", "armeabi-v7a"}));
    }

    @Test
    public void pickAbiReturnsNullForUnsupportedDevice() {
        // x86_64 模拟器：不提供组件包，入口应报「架构不支持」而不是下载失败
        assertNull(NativeLibManager.pickAbi(new String[]{"x86_64", "x86"}));
        assertNull(NativeLibManager.pickAbi(new String[]{}));
    }

    @Test
    public void releaseUrlPointsAtGithubReleaseAsset() {
        assertEquals("https://github.com/706412584/orangeplayer/releases/download/v1.5.2/"
                        + "asr-arm64-v8a.zip",
                NativeLibManager.releaseUrl("v1.5.2", "asr-arm64-v8a.zip"));
    }

    @Test
    public void bundlesCoverFourComponentsWithBothAbis() {
        NativeLibManager.BundleInfo[] bundles = NativeLibManager.getBundles();
        assertEquals(8, bundles.length);
        for (NativeLibManager.BundleInfo b : bundles) {
            assertNotNull(b.id + " 缺少显示名", b.displayName);
            assertTrue(b.id + " arm64 体积应为正", b.sizeArm64 > 0);
            assertTrue(b.id + " v7a 体积应为正", b.sizeV7a > 0);
            assertTrue(b.id + " arm64 解压后体积应不小于包体积", b.rawSizeArm64 >= b.sizeArm64);
            assertTrue(b.id + " v7a 解压后体积应不小于包体积", b.rawSizeV7a >= b.sizeV7a);
            assertTrue(b.id + " 缺少 arm64 sha256", b.sha256Arm64.matches("[0-9a-f]{64}"));
            assertTrue(b.id + " 缺少 v7a sha256", b.sha256V7a.matches("[0-9a-f]{64}"));
            assertTrue(b.id + " 无 so 清单", b.libs.length > 0);
        }
        assertNotNull(NativeLibManager.getBundle(NativeLibManager.BUNDLE_ASR));
        assertNotNull(NativeLibManager.getBundle(NativeLibManager.BUNDLE_TORRENT));
        assertNotNull(NativeLibManager.getBundle(NativeLibManager.BUNDLE_OCR));
        assertNotNull(NativeLibManager.getBundle(NativeLibManager.BUNDLE_TRANSLATE));
        assertNotNull(NativeLibManager.getBundle(NativeLibManager.BUNDLE_IJK));
        assertNotNull(NativeLibManager.getBundle(NativeLibManager.BUNDLE_ALI));
        assertNotNull(NativeLibManager.getBundle(NativeLibManager.BUNDLE_MPV));
        assertNotNull(NativeLibManager.getBundle(NativeLibManager.BUNDLE_FFMPEG));
        assertNull(NativeLibManager.getBundle("nope"));
    }

    @Test
    public void ijkLibsAreOrderedByDependency() {
        // IjkMediaPlayer.loadLibrariesOnce 的硬编码顺序：ffmpeg → sdl → player；
        // libijksdl/libijkplayer 的 DT_NEEDED 都指向 libijkffmpeg，反了会 dlopen 失败
        String[] libs = NativeLibManager.getBundle(NativeLibManager.BUNDLE_IJK).libs;
        assertEquals("libijkffmpeg.so", libs[0]);
        assertEquals("libijkplayer.so", libs[libs.length - 1]);
    }

    @Test
    public void aliLibsAreOrderedByDependency() {
        // NativeLoader.loadPlayer 顺序；libsaasDownloader 依赖前两者
        String[] libs = NativeLibManager.getBundle(NativeLibManager.BUNDLE_ALI).libs;
        assertEquals("libalivcffmpeg.so", libs[0]);
        assertEquals("libsaasCorePlayer.so", libs[1]);
    }

    @Test
    public void mpvLibsPutAvCodecBeforeMpvAndPlayerLast() {
        // libmpv 依赖全部 av*；libplayer 依赖 libmpv
        String[] libs = NativeLibManager.getBundle(NativeLibManager.BUNDLE_MPV).libs;
        java.util.List<String> list = Arrays.asList(libs);
        assertTrue("libavcodec 必须在 libmpv 之前",
                list.indexOf("libavcodec.so") < list.indexOf("libmpv.so"));
        assertTrue("libavutil 必须在 libavcodec 之前",
                list.indexOf("libavutil.so") < list.indexOf("libavcodec.so"));
        assertEquals("libplayer.so", libs[libs.length - 1]);
        assertEquals(10, libs.length);
    }

    @Test
    public void asrLibsAreOrderedByDependency() {
        // libsherpa-onnx-* 依赖 libonnxruntime，加载顺序反了会直接 dlopen 失败
        String[] libs = NativeLibManager.getBundle(NativeLibManager.BUNDLE_ASR).libs;
        assertEquals("libonnxruntime.so", libs[0]);
    }

    @Test
    public void ocrLibsAreOrderedByDependency() {
        // libtesseract -> libleptonica -> libjpeg/libpngx
        String[] libs = NativeLibManager.getBundle(NativeLibManager.BUNDLE_OCR).libs;
        int jpeg = Arrays.asList(libs).indexOf("libjpeg.so");
        int pngx = Arrays.asList(libs).indexOf("libpngx.so");
        int lept = Arrays.asList(libs).indexOf("libleptonica.so");
        int tess = Arrays.asList(libs).indexOf("libtesseract.so");
        assertTrue(jpeg >= 0 && pngx >= 0 && lept >= 0 && tess >= 0);
        assertTrue("leptonica 必须在 tesseract 之前", lept < tess);
        assertTrue("jpeg 必须在 leptonica 之前", jpeg < lept);
        assertTrue("pngx 必须在 leptonica 之前", pngx < lept);
    }

    @Test
    public void specCarriesAllMirrorsAndAbiSpecificHash() {
        NativeLibManager.BundleInfo asr = NativeLibManager.getBundle(NativeLibManager.BUNDLE_ASR);
        ResumableFileDownloader.FileSpec arm64 = NativeLibManager.specFor(asr, "arm64-v8a");
        assertEquals("asr-arm64-v8a.zip", arm64.name);
        assertEquals(asr.sizeArm64, arm64.expectedSize);
        assertEquals(asr.sha256Arm64, arm64.sha256);
        // 镜像链：Gitee -> gh-proxy -> ghfast -> GitHub 原址，四者必须指向同一个资产
        assertEquals(4, arm64.urls.length);
        for (String url : arm64.urls) {
            assertTrue(url, url.endsWith("/asr-arm64-v8a.zip"));
            // 每个源都必须带 tag，否则会 404（组件 zip 按 tag 分目录存放）。
            // 不硬编码具体版本号——否则每次发版都要改测试。这里只校验形态，
            // 再单独断言四个源的 tag 一致（混用会让跨源续传拿到不同字节）。
            assertTrue("URL 缺少版本段: " + url,
                    url.matches(".*/v\\d+\\.\\d+\\.\\d+/asr-arm64-v8a\\.zip"));
        }
        String tag = extractTag(arm64.urls[0]);
        for (String url : arm64.urls) {
            assertEquals("各镜像源必须指向同一 tag", tag, extractTag(url));
        }
        // 顺序即回退优先级：国内直连的 Gitee 优先，GitHub 原址兜底
        assertTrue(arm64.urls[0].startsWith("https://gitee.com/"));
        assertTrue(arm64.urls[3].startsWith("https://github.com/"));

        // v7a 是另一份资产，体积与哈希都必须跟着 ABI 走（否则校验必然失败）
        ResumableFileDownloader.FileSpec v7a = NativeLibManager.specFor(asr, "armeabi-v7a");
        assertEquals("asr-armeabi-v7a.zip", v7a.name);
        assertEquals(asr.sizeV7a, v7a.expectedSize);
        assertEquals(asr.sha256V7a, v7a.sha256);
        // 两个 ABI 的包内容不同，哈希不能复用
        assertFalse(arm64.sha256.equals(v7a.sha256));
    }

    @Test
    public void isInstalledIsFalseBeforeInit() {
        // 未调用 install(context) 时不能误判为已就位（否则会跳过下载直接加载）
        assertFalse(NativeLibManager.isInstalled(NativeLibManager.BUNDLE_OCR));
    }

    @Test
    public void isInstalledRequiresEveryLibPresent() throws Exception {
        File filesDir = mTemp.newFolder("files");
        NativeLibManager.setFilesDirForTest(filesDir);
        NativeLibManager.setAbiOverrideForTest("arm64-v8a");
        String[] libs = NativeLibManager.getBundle(NativeLibManager.BUNDLE_OCR).libs;
        File dir = NativeLibManager.libDir(filesDir, "arm64-v8a");

        assertFalse("目录为空时不应判定就位", NativeLibManager.isInstalled(
                NativeLibManager.BUNDLE_OCR));

        for (int i = 0; i < libs.length - 1; i++) {
            write(new File(dir, libs[i]), "x");
        }
        assertFalse("缺最后一个 so 时不应判定就位", NativeLibManager.isInstalled(
                NativeLibManager.BUNDLE_OCR));

        write(new File(dir, libs[libs.length - 1]), "x");
        assertTrue("全部 so 就位后应判定已安装", NativeLibManager.isInstalled(
                NativeLibManager.BUNDLE_OCR));
    }

    @Test
    public void isInstalledRejectsZeroLengthSo() throws Exception {
        // 解压中途被杀留下的空文件不能被判为就绪
        File filesDir = mTemp.newFolder("files2");
        NativeLibManager.setFilesDirForTest(filesDir);
        NativeLibManager.setAbiOverrideForTest("arm64-v8a");
        File dir = NativeLibManager.libDir(filesDir, "arm64-v8a");
        for (String lib : NativeLibManager.getBundle(NativeLibManager.BUNDLE_TORRENT).libs) {
            write(new File(dir, lib), "");
        }
        assertFalse(NativeLibManager.isInstalled(NativeLibManager.BUNDLE_TORRENT));
    }

    @Test
    public void isInstalledIgnoresOtherAbiDirectory() throws Exception {
        // 只有 v7a 目录有 so 时，arm64 设备不应判定为已安装
        File filesDir = mTemp.newFolder("files3");
        NativeLibManager.setFilesDirForTest(filesDir);
        NativeLibManager.setAbiOverrideForTest("arm64-v8a");
        File v7a = NativeLibManager.libDir(filesDir, "armeabi-v7a");
        for (String lib : NativeLibManager.getBundle(NativeLibManager.BUNDLE_OCR).libs) {
            write(new File(v7a, lib), "x");
        }
        assertFalse(NativeLibManager.isInstalled(NativeLibManager.BUNDLE_OCR));
    }

    @Test
    public void bundledSoCountsAsInstalledAndIsNotRemovable() {
        // 宿主把 so 打进 APK（完整引入）时，so 一样可用，但删不掉——
        // 若仍显示「删除」，用户点了会没反应（remove 只删下载目录）。
        NativeLibManager.setBundledLibsForTest(new HashSet<>(
                Collections.singletonList(NativeLibManager.BUNDLE_TORRENT)));
        try {
            assertEquals(NativeLibManager.InstallSource.BUNDLED,
                    NativeLibManager.installSource(NativeLibManager.BUNDLE_TORRENT));
            assertTrue(NativeLibManager.isInstalled(NativeLibManager.BUNDLE_TORRENT));
            assertFalse("内置的 so 删不掉",
                    NativeLibManager.canRemove(NativeLibManager.BUNDLE_TORRENT));
        } finally {
            NativeLibManager.setBundledLibsForTest(null);
        }
    }

    @Test
    public void downloadedSoIsRemovableUnlikeBundled() throws Exception {
        // 下载来的组件才是可删的——这是 BUNDLED 与 DOWNLOADED 的关键差别
        File filesDir = mTemp.newFolder("files-dl");
        NativeLibManager.setFilesDirForTest(filesDir);
        NativeLibManager.setAbiOverrideForTest("arm64-v8a");
        File dir = NativeLibManager.libDir(filesDir, "arm64-v8a");
        for (String lib : NativeLibManager.getBundle(NativeLibManager.BUNDLE_OCR).libs) {
            write(new File(dir, lib), "x");
        }
        assertEquals(NativeLibManager.InstallSource.DOWNLOADED,
                NativeLibManager.installSource(NativeLibManager.BUNDLE_OCR));
        assertTrue(NativeLibManager.canRemove(NativeLibManager.BUNDLE_OCR));
    }

    @Test
    public void isSupportedReflectsHostDependencies() {
        // 「本版本未集成」的判定：宿主没引依赖时类不存在 → false，
        // 面板据此显示「不可用」而不是给一个装了也没用的下载入口。
        // 单测 classpath 里没有任何可选组件的类，故全部应为 false。
        for (String bundleId : new String[]{
                NativeLibManager.BUNDLE_ALI, NativeLibManager.BUNDLE_MPV,
                NativeLibManager.BUNDLE_TORRENT, NativeLibManager.BUNDLE_OCR,
                NativeLibManager.BUNDLE_TRANSLATE, NativeLibManager.BUNDLE_ASR}) {
            assertFalse(bundleId + " 未引入依赖时不该判为支持",
                    NativeLibManager.isSupported(bundleId));
        }
        // 未知组件 id 同样返回 false 而不是抛异常
        assertFalse(NativeLibManager.isSupported("nonexistent"));
    }

    @Test
    public void unsupportedBundleIsNotInstalledEvenWithSoPresent() throws Exception {
        // 纵深防御：即便 so 都在（下载目录 + 内置），宿主没引 Java 类也用不了。
        // isInstalled 只管 so 是否就位，isSupported 管类是否存在——两者分工不同，
        // 面板同时看这两个（先 isSupported 再 installSource）。
        File filesDir = mTemp.newFolder("files-unsup");
        NativeLibManager.setFilesDirForTest(filesDir);
        NativeLibManager.setAbiOverrideForTest("arm64-v8a");
        File dir = NativeLibManager.libDir(filesDir, "arm64-v8a");
        for (String lib : NativeLibManager.getBundle(NativeLibManager.BUNDLE_ALI).libs) {
            write(new File(dir, lib), "x");
        }
        // so 就位 → isInstalled 为真（它不负责判类）
        assertTrue(NativeLibManager.isInstalled(NativeLibManager.BUNDLE_ALI));
        // 但宿主未引入该类 → 面板不会走到「已安装」，而是显示「本版本未集成」
        assertFalse(NativeLibManager.isSupported(NativeLibManager.BUNDLE_ALI));
    }

    @Test
    public void bundledTakesPrecedenceOverDownloaded() throws Exception {
        // 两边都有时按 APK 优先：APK 内的 so 才是实际被加载的那个，
        // 且它删不掉，来源必须报 BUNDLED 而不是 DOWNLOADED。
        File filesDir = mTemp.newFolder("files-both");
        NativeLibManager.setFilesDirForTest(filesDir);
        NativeLibManager.setAbiOverrideForTest("arm64-v8a");
        File dir = NativeLibManager.libDir(filesDir, "arm64-v8a");
        for (String lib : NativeLibManager.getBundle(NativeLibManager.BUNDLE_MPV).libs) {
            write(new File(dir, lib), "x");
        }
        NativeLibManager.setBundledLibsForTest(new HashSet<>(
                Collections.singletonList(NativeLibManager.BUNDLE_MPV)));
        try {
            assertEquals(NativeLibManager.InstallSource.BUNDLED,
                    NativeLibManager.installSource(NativeLibManager.BUNDLE_MPV));
        } finally {
            NativeLibManager.setBundledLibsForTest(null);
        }
    }

    @Test
    public void loadFailsWhenNotInitialized() {
        // 未初始化时必须明确失败，而不是从任意路径加载
        NativeLibManager.setAbiOverrideForTest("arm64-v8a");
        assertFalse(NativeLibManager.load(NativeLibManager.BUNDLE_TORRENT));
    }

    @Test
    public void loadRefusesSoOutsideAppDir() throws Exception {
        // 纵深防御：即便清单被改成外部路径，也不允许从应用目录之外 dlopen
        File filesDir = mTemp.newFolder("appfiles");
        File outside = mTemp.newFolder("outside");
        NativeLibManager.setFilesDirForTest(filesDir);
        NativeLibManager.setAbiOverrideForTest("arm64-v8a");
        write(new File(outside, "libtorrent4j.so"), "not-a-real-so");

        // 就位判定走的是应用目录（不存在），故 load 必须先失败
        assertFalse(NativeLibManager.isInstalled(NativeLibManager.BUNDLE_TORRENT));
        assertFalse(NativeLibManager.load(NativeLibManager.BUNDLE_TORRENT));
    }

    @Test
    public void stripLibPrefixYieldsBareLoadLibraryName() {
        // loadLibrary 只接受裸名；第三方 AAR 里硬编码的正是 "jpeg"/"tesseract" 这类
        assertEquals("jpeg", NativeLibManager.stripLibPrefix("libjpeg.so"));
        assertEquals("tesseract", NativeLibManager.stripLibPrefix("libtesseract.so"));
        assertEquals("onnxruntime", NativeLibManager.stripLibPrefix("libonnxruntime.so"));
        // 不带 lib 前缀或后缀时原样返回
        assertEquals("jpeg", NativeLibManager.stripLibPrefix("jpeg"));
    }

    @Test
    public void formatSizeIsHumanReadable() {
        assertEquals("4.3MB", NativeLibManager.formatSize(4_489_193L));
        assertEquals("11.1MB", NativeLibManager.formatSize(11_668_029L));
    }

    private static void write(File f, String content) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes("UTF-8"));
        }
    }

    /** 从组件下载 URL 里取出 tag 段（如 "v1.5.6"）；取不到返回 null。 */
    private static String extractTag(String url) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("/(v\\d+\\.\\d+\\.\\d+)/").matcher(url);
        return m.find() ? m.group(1) : null;
    }
}
