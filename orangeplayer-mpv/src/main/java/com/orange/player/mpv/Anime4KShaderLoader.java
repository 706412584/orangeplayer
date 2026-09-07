package com.orange.player.mpv;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * mpv GLSL shader 资产释放器：assets → filesDir。
 * mpv 的 change-list glsl-shaders 需要真实文件路径（spike S2 实测）。
 * 承载 Anime4K 超分链与音色档 shader（鲜艳/黑白/复古），供 MpvPlayerManager
 * 按画质档位组装配路径。
 */
public final class Anime4KShaderLoader {

    /** Anime4K Mode A 链（MIT, bloc97） */
    private static final String[] SHADERS = {
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Upscale_CNN_x2_L.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
    };

    /** 色彩档 shader（自绘 RGB 矩阵，mpv hook 格式） */
    private static final String[] TONE_SHADERS = {
            "vivid.glsl",
            "bw.glsl",
            "sepia.glsl",
    };

    private Anime4KShaderLoader() {
    }

    /** 释放 Anime4K 超分链（Mode A 顺序）到 filesDir 并返回路径列表 */
    public static List<String> getAnime4KChain(Context context) {
        List<String> paths = new ArrayList<>();
        for (String name : SHADERS) {
            String p = release(context, name);
            if (p != null) paths.add(p);
        }
        return paths;
    }

    /** 释放单个增强档 shader（用于 Anime4K Restore 单档与色彩档） */
    public static String getShaderPath(Context context, String name) {
        return release(context, name);
    }

    /** 释放单个色彩档 shader（鲜艳/黑白/复古） */
    public static String getToneShaderPath(Context context, String name) {
        for (String n : TONE_SHADERS) {
            if (n.equals(name)) return release(context, name);
        }
        return null;
    }

    private static String release(Context context, String name) {
        File out = new File(context.getFilesDir(), name);
        long assetLen = -1;
        try (InputStream is = context.getAssets().open("shaders/" + name)) {
            assetLen = is.available();
        } catch (Exception e) {
            android.util.Log.e("Anime4KShader", "assets open failed: " + name, e);
            return null;
        }
        // 文件缺失或与资产不一致（旧版坏文件/资产更新）都强制重写，
        // 避免设备上残留失效 shader（mpv 解析失败静默跳过）。
        if (out.exists() && out.length() == assetLen) {
            return out.getAbsolutePath();
        }
        try (InputStream is = context.getAssets().open("shaders/" + name);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
        } catch (Exception e) {
            android.util.Log.e("Anime4KShader", "release failed: " + name, e);
            return null;
        }
        return out.getAbsolutePath();
    }
}
