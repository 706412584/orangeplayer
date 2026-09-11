package com.orange.playerlibrary.speech;

/**
 * 批量语音识别引擎接口（整段音频 → 带时间戳的字幕段）。
 *
 * 实现位于独立模块 orangeplayer-sherpa（直引 sherpa-onnx AAR），
 * 本接口只用纯 JDK/Android 类型——palyerlibrary 编译期零依赖，
 * 运行期经 SherpaAvailabilityChecker 门禁 + 工厂反射加载，宿主未引模块时无感。
 */
public interface BatchAsrEngine {

    /**
     * 识别回调（后台线程调用，需自行切 UI 线程）
     */
    interface BatchAsrCallback {
        /** 引擎加载完成、可开始 */
        void onReady();

        /** 每识别完一个语音段 */
        void onSegment(String text, long startMs, long endMs);

        /**
         * 每识别完一个语音段（含引擎检测到的语种，可为 null）。
         * SenseVoice 在 auto 模式下会回报语种（如 "zh"/"en"），本地翻译兜底
         * 需要它作为源语言。默认实现退化为 {@link #onSegment}，未实现的引擎不受影响。
         */
        default void onSegmentWithLang(String text, long startMs, long endMs, String lang) {
            onSegment(text, startMs, endMs);
        }

        /**
         * 进度（0-100）。VAD 预扫阶段与解码阶段合并口径：
         * 预扫阶段按已扫/总时长估算，解码阶段按已处理段数估算。
         */
        void onProgress(int percent, String stage);

        /** 全部完成 */
        void onCompleted(int segmentCount);

        /** 失败（错误码 + 消息） */
        void onError(int errorCode, String errorMessage);
    }

    /**
     * 初始化识别引擎。
     *
     * @param modelDir  含 model.int8.onnx 与 tokens.txt 的目录
     * @param language  语种（auto/zh/en/ja/ko；auto 交给模型自带 lang2id）
     * @return 是否成功
     */
    boolean init(String modelDir, String language);

    /**
     * 识别整段 wav 文件（16k 单声道 pcm_s16le）。
     * 内部：silero-vad 切段 → 逐段解码 → VAD 边界即字幕时间轴。
     * 阻塞调用，须放后台线程。
     *
     * @param wavPath   输入 wav 路径
     * @param callback  回调
     * @param cancelToken 取消令牌；非 null 且 isCancelled()==true 时尽快中止
     */
    void transcribeFile(String wavPath, BatchAsrCallback callback, CancelToken cancelToken);

    /** 是否已初始化 */
    boolean isInitialized();

    /** 释放模型资源 */
    void release();

    /**
     * 取消令牌（UI 层签发；覆盖 init/VAD/解码三阶段检查点）
     */
    interface CancelToken {
        boolean isCancelled();
    }
}
