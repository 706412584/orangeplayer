package com.orange.playerlibrary.speech;

import android.util.Log;

/**
 * sherpa-onnx 批量 ASR 可用性检查（与 OcrAvailabilityChecker/VoskAvailabilityChecker 同模式）。
 * 宿主 app 未依赖 orangeplayer-sherpa 模块时返回 false，功能入口自动隐藏。
 */
public class SherpaAvailabilityChecker {

    private static final String TAG = "SherpaChecker";

    private static Boolean sAvailable = null;

    /** sherpa-onnx 的离线识别器类（模块存在即视为可用） */
    private static final String SHERPA_OFFLINE_RECOGNIZER =
            "com.k2fsa.sherpa.onnx.OfflineRecognizer";

    /**
     * sherpa-onnx 是否可用（orangeplayer-sherpa 模块已依赖）
     */
    public static boolean isSherpaAvailable() {
        if (sAvailable == null) {
            try {
                Class.forName(SHERPA_OFFLINE_RECOGNIZER);
                sAvailable = true;
            } catch (ClassNotFoundException e) {
                sAvailable = false;
            } catch (Throwable t) {
                Log.w(TAG, "检查 sherpa 可用性异常", t);
                sAvailable = false;
            }
        }
        return sAvailable;
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

    /** 重置缓存（测试用） */
    public static void resetCache() {
        sAvailable = null;
    }
}
