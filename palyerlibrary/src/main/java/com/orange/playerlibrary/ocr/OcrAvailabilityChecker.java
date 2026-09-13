package com.orange.playerlibrary.ocr;

import com.orange.playerlibrary.tool.NativeLibManager;

/**
 * OCR 功能可用性检查器
 *
 * 需要同时满足两个条件：宿主引入了 java 依赖（类存在），且对应的 native so
 * 已按需下载安装。so 不再随 APK 分发（见 NativeLibManager），
 * 因此「类存在」不再等价于「功能可用」。
 */
public class OcrAvailabilityChecker {

    private static Boolean sTesseractAvailable = null;
    private static Boolean sMlKitTranslateAvailable = null;

    /**
     * 检查 Tesseract OCR 是否可用（类存在 && 文字识别组件已安装）
     */
    public static boolean isTesseractAvailable() {
        if (sTesseractAvailable == null) {
            try {
                // initialize=false：TessBaseAPI 的 <clinit> 硬编码 loadLibrary，
                // so 未下载时会抛 UnsatisfiedLinkError；在此触发初始化会污染类
                // 并把「so 未下载」误判成「模块未引入」。
                Class.forName("com.googlecode.tesseract.android.TessBaseAPI", false,
                        OcrAvailabilityChecker.class.getClassLoader());
                sTesseractAvailable = true;
            } catch (ClassNotFoundException e) {
                sTesseractAvailable = false;
            }
        }
        return sTesseractAvailable && NativeLibManager.isInstalled(NativeLibManager.BUNDLE_OCR);
    }

    /**
     * 检查 ML Kit Translation 是否可用（类存在 && 翻译组件已安装）
     */
    public static boolean isMlKitTranslateAvailable() {
        if (sMlKitTranslateAvailable == null) {
            try {
                Class.forName("com.google.mlkit.nl.translate.Translator", false,
                        OcrAvailabilityChecker.class.getClassLoader());
                sMlKitTranslateAvailable = true;
            } catch (ClassNotFoundException e) {
                sMlKitTranslateAvailable = false;
            }
        }
        return sMlKitTranslateAvailable
                && NativeLibManager.isInstalled(NativeLibManager.BUNDLE_TRANSLATE);
    }

    /**
     * 检查 OCR 翻译功能是否完全可用
     */
    public static boolean isOcrTranslateAvailable() {
        return isTesseractAvailable() && isMlKitTranslateAvailable();
    }

    /**
     * 获取缺失依赖的提示信息
     *
     * 分两种情况：宿主没引依赖（SDK 使用者需自己加 gradle 依赖），
     * 以及依赖在但 so 未下载（终端用户在「扩展包管理」里下载即可）。
     */
    public static String getMissingDependenciesMessage() {
        boolean ocrClass = NativeLibManager.isSupported(NativeLibManager.BUNDLE_OCR);
        boolean translateClass = NativeLibManager.isSupported(NativeLibManager.BUNDLE_TRANSLATE);
        StringBuilder sb = new StringBuilder();
        sb.append("OCR 翻译功能需要以下组件：\n\n");
        if (!ocrClass) {
            sb.append("· 文字识别（Tesseract）\n");
            sb.append("  请在 app/build.gradle 添加：\n");
            sb.append("  implementation 'cz.adaptech.tesseract4android:tesseract4android:4.7.0'\n\n");
        } else if (!isTesseractAvailable()) {
            sb.append("· 文字识别组件未下载（")
                    .append(NativeLibManager.formatSize(
                            NativeLibManager.getBundle(NativeLibManager.BUNDLE_OCR).size()))
                    .append("）\n");
        }
        if (!translateClass) {
            sb.append("· 文字翻译（ML Kit）\n");
            sb.append("  请在 app/build.gradle 添加：\n");
            sb.append("  implementation 'com.google.mlkit:translate:17.0.2'\n");
        } else if (!isMlKitTranslateAvailable()) {
            sb.append("· 翻译组件未下载（")
                    .append(NativeLibManager.formatSize(
                            NativeLibManager.getBundle(NativeLibManager.BUNDLE_TRANSLATE).size()))
                    .append("）\n");
        }
        if (ocrClass && translateClass) {
            sb.append("\n在「设置 → 扩展包管理」中下载后即可使用。");
        }
        return sb.toString();
    }

    /**
     * 重置缓存（用于测试）
     */
    public static void resetCache() {
        sTesseractAvailable = null;
        sMlKitTranslateAvailable = null;
    }
}
