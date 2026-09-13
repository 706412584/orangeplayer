package com.orange.playerlibrary.utils;

import android.os.Build;

import com.orange.playerlibrary.PlayerConstants;
import com.orange.playerlibrary.tool.NativeLibManager;

/**
 * 播放内核可用性的单一事实来源。
 *
 * <p>存在的理由：so 改为按需下载后，「内核可用」不再是「Java 类存在」一件事。
 * 改造前四处判定各写各的，且全部只看类——so 被剔出 APK 后它们一律返回 true，
 * 结果是按钮照常显示、点下去静默回退到系统内核，用户以为在用自己的内核。
 * 更糟的是 {@code OrangevideoView.isEngineAvailable} 的 ijk 分支会**真去
 * loadLibrary**，在 so 未下载时抛错，而它被 {@link EngineFallbackTracker} 当探针
 * 高频调用，不该有加载副作用。
 *
 * <p>另一个必须收敛的点：判定过程绝不能触发内核类的 {@code <clinit>}。
 * ali 的 {@code NativeLoader.loadPlayer()} 在 try 之前就把 {@code playerLoaded}
 * 置 true 且只 catch {@code Exception}（{@code UnsatisfiedLinkError} 是
 * {@code Error}），一旦在 so 缺失时被触发，本进程内永不再重试；mpv 的
 * {@code MPVLib.<clinit>} 无 try/catch，失败会 {@code ExceptionInInitializerError}
 * 永久污染该类。所以探测一律走 {@link NativeLibManager#isSupported}（内部用
 * {@code Class.forName(name, false, loader)}）。
 */
public final class PlayerEngineAvailability {

    private PlayerEngineAvailability() {
    }

    /** 内核对应的按需组件 id；无原生依赖的内核（exo/default）返回 null */
    public static String bundleIdFor(String engine) {
        if (PlayerConstants.ENGINE_IJK.equals(engine)) {
            return NativeLibManager.BUNDLE_IJK;
        }
        if (PlayerConstants.ENGINE_ALI.equals(engine)) {
            return NativeLibManager.BUNDLE_ALI;
        }
        if (PlayerConstants.ENGINE_MPV.equals(engine)) {
            return NativeLibManager.BUNDLE_MPV;
        }
        return null;
    }

    /** 宿主是否引入了该内核（只看 Java 类，不碰 so） */
    public static boolean isSupported(String engine) {
        String bundleId = bundleIdFor(engine);
        if (bundleId == null) {
            // exo / default 无按需组件，类在即可用
            return isExoOrSystemPresent(engine);
        }
        return NativeLibManager.isSupported(bundleId);
    }

    /** 内核的 so 是否已下载就位 */
    public static boolean isInstalled(String engine) {
        String bundleId = bundleIdFor(engine);
        return bundleId != null && NativeLibManager.isInstalled(bundleId);
    }

    /**
     * 内核当前能否真正使用：宿主引入了、so 已就位、且满足平台约束。
     *
     * <p>这是 UI 与回退决策唯一该看的判定。
     */
    public static boolean isUsable(String engine) {
        if (!isSupported(engine)) {
            return false;
        }
        String bundleId = bundleIdFor(engine);
        if (bundleId != null && !NativeLibManager.isInstalled(bundleId)) {
            return false;
        }
        // mpv 的 AAR 要求 API 26+（minSdk 26），低版本即使 so 就位也用不了
        if (PlayerConstants.ENGINE_MPV.equals(engine)
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        return true;
    }

    /**
     * 内核已引入但 so 未下载——UI 据此显示「下载」入口而不是隐藏按钮。
     * 与 {@link #isUsable} 的区别：不把平台约束算进来（低版本设备仍可下载，
     * 只是当下用不了，交给调用方决定怎么提示）。
     *
     * <p>无 bundle 的内核（exo/default）恒为 false：它们没有可下载的东西，
     * 若按 {@code !isInstalled} 判断会因 isInstalled 恒 false 而误报需要下载。
     */
    public static boolean needsDownload(String engine) {
        String bundleId = bundleIdFor(engine);
        return bundleId != null && isSupported(engine) && !isInstalled(engine);
    }

    private static boolean isExoOrSystemPresent(String engine) {
        if (PlayerConstants.ENGINE_DEFAULT.equals(engine)) {
            return true;
        }
        if (PlayerConstants.ENGINE_EXO.equals(engine)) {
            try {
                Class.forName("com.orange.playerlibrary.exo.OrangeExoPlayerManager",
                        false, PlayerEngineAvailability.class.getClassLoader());
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }
}
