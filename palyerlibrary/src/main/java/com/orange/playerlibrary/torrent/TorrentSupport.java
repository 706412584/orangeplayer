package com.orange.playerlibrary.torrent;

import android.content.Context;

import com.orange.playerlibrary.tool.NativeLibManager;

import org.libtorrent4j.LibTorrent;
import org.libtorrent4j.SessionManager;

import java.io.File;

public final class TorrentSupport {

    private TorrentSupport() {
    }

    /** 种子播放组件是否可用（类存在 && so 已按需下载安装） */
    public static boolean isTorrentPlayable() {
        return NativeLibManager.isInstalled(NativeLibManager.BUNDLE_TORRENT)
                && getJlibtorrentMissingReason() == null;
    }

    public static boolean isTorrentUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("magnet:")
                || lower.startsWith("torrent:")
                || lower.endsWith(".torrent")
                || lower.contains(".torrent?");
    }

    /**
     * Extract clean magnet URL from a potentially polluted URL string.
     * Some apps may paste clipboard content before the magnet link.
     */
    public static String extractMagnetUrl(String url) {
        if (url == null) return null;
        int idx = url.toLowerCase().indexOf("magnet:");
        if (idx >= 0) {
            return url.substring(idx);
        }
        return url;
    }

    public static boolean isJlibtorrentClassAvailable() {
        try {
            Class.forName("org.libtorrent4j.SessionManager");
            Class.forName("org.libtorrent4j.LibTorrent");
            android.util.Log.d("TorrentSupport", "libtorrent4j classes available");
            return true;
        } catch (Throwable t) {
            android.util.Log.e("TorrentSupport", "libtorrent4j classes NOT available: " + t);
            return false;
        }
    }

    public static boolean isJlibtorrentNativeAvailable() {
        try {
            // If native is missing, this will throw UnsatisfiedLinkError
            String version = LibTorrent.version();
            android.util.Log.d("TorrentSupport", "libtorrent4j native available, version=" + version);
            return true;
        } catch (Throwable t) {
            android.util.Log.e("TorrentSupport", "libtorrent4j native NOT available: " + t);
            return false;
        }
    }

    public static String getJlibtorrentMissingReason() {
        if (!isJlibtorrentClassAvailable()) {
            return "libtorrent4j classes not found. Please add dependencies org.libtorrent4j:libtorrent4j and ABI artifacts.";
        }
        // so 不再随 APK 分发：未下载时给出可操作的提示，而不是「ABI 不支持」
        if (!NativeLibManager.isInstalled(NativeLibManager.BUNDLE_TORRENT)) {
            return "种子播放组件未下载（约 4.3MB），请在「设置 → 扩展包管理」中下载";
        }
        if (!isJlibtorrentNativeAvailable()) {
            return "libtorrent4j native library not available for current ABI. Please add the correct libtorrent4j-android-* artifact.";
        }
        return null;
    }

    public static File defaultSaveDir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(base, "torrent");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
}
