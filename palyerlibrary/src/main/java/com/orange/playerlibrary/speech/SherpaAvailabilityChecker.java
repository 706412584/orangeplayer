package com.orange.playerlibrary.speech;

import android.util.Log;

import com.orange.playerlibrary.tool.NativeLibManager;

/**
 * sherpa-onnx 批量 ASR 可用性检查（与 OcrAvailabilityChecker 同模式）。
 *
 * 需要同时满足两个条件：宿主引入了 orangeplayer-sherpa 模块（类存在），
 * 且语音识别组件（sherpa + onnxruntime 的 so）已按需下载安装。
 * so 不再随 APK 分发（见 NativeLibManager），因此「类存在」不再等价于「功能可用」。
 */
public class SherpaAvailabilityChecker {

    private static final String TAG = "SherpaChecker";

    private static Boolean sAvailable = null;

    /** sherpa-onnx 的离线识别器类（模块存在即视为支持） */
    private static final String SHERPA_OFFLINE_RECOGNIZER =
            "com.k2fsa.sherpa.onnx.OfflineRecognizer";

    /**
     * sherpa-onnx 是否可用（模块已依赖 && 语音识别组件已安装）
     */
    public static boolean isSherpaAvailable() {
        if (sAvailable == null) {
            try {
                // initialize=false：OfflineRecognizer 的 <clinit> 里硬编码
                // System.loadLibrary，so 未下载时会抛 UnsatisfiedLinkError。
                // 若在此触发初始化，不仅会把「so 未下载」误判成「模块未引入」并
                // 永久缓存，还会因 ExceptionInInitializerError 污染整个类。
                Class.forName(SHERPA_OFFLINE_RECOGNIZER, false,
                        SherpaAvailabilityChecker.class.getClassLoader());
                sAvailable = true;
            } catch (ClassNotFoundException e) {
                sAvailable = false;
            } catch (Throwable t) {
                Log.w(TAG, "检查 sherpa 可用性异常", t);
                sAvailable = false;
            }
        }
        return sAvailable && NativeLibManager.isInstalled(NativeLibManager.BUNDLE_ASR);
    }

    /**
     * 获取 BatchAsrEngine 实现（反射加载，宿主未引模块时返回 null）
     */
    public static BatchAsrEngine createEngine() {
        if (!isSherpaAvailable()) {
            return null;
        }
        try {
            Class<?> factory = Class.forName("com.orange.player.sherpa.SherpaAsrEngineFactory");
            return (BatchAsrEngine) factory.getMethod("create").invoke(null);
        } catch (Throwable t) {
            Log.e(TAG, "加载 BatchAsrEngine 失败", t);
            return null;
        }
    }

    /**
     * 获取缺失依赖的提示信息（与 {@code OcrAvailabilityChecker} 同模式）。
     *
     * <p>分两种情况：宿主没引依赖（SDK 使用者需自己加 gradle 依赖），
     * 以及依赖在但 so 未下载（终端用户在「扩展包管理」里下载即可）。
     * 两者的操作完全不同，合成一句话会让用户无从下手。
     */
    public static String getMissingDependenciesMessage() {
        boolean classPresent = NativeLibManager.isSupported(NativeLibManager.BUNDLE_ASR);
        StringBuilder sb = new StringBuilder();
        sb.append("AI 语音生成字幕需要以下组件：\n\n");
        if (!classPresent) {
            sb.append("· sherpa-onnx 语音识别模块\n");
            sb.append("  请在 app/build.gradle 添加：\n");
            sb.append("  implementation project(':orangeplayer-sherpa')\n");
        } else {
            sb.append("· 语音识别组件未下载（")
                    .append(NativeLibManager.formatSize(
                            NativeLibManager.getBundle(NativeLibManager.BUNDLE_ASR).size()))
                    .append("，含 sherpa-onnx 与 onnxruntime 运行库）\n");
            sb.append("\n在「设置 → 扩展包管理」中下载后即可使用。");
        }
        return sb.toString();
    }

    /** 重置缓存（测试用） */
    public static void resetCache() {
        sAvailable = null;
    }
}
