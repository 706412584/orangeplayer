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
         * 每识别完一个语音段（含语种与说话人编号）。
         *
         * <p>说话人由可选的离线说话人分离（diarization）产出，是**该段音频内的相对编号**
         * （0 起），不表示真实身份；引擎未启用说话人分离或该段无归属时为 -1。
         * 默认实现退化为 {@link #onSegmentWithLang}，未启用 diarization 的引擎不受影响。
         */
        default void onSegmentWithSpeaker(String text, long startMs, long endMs,
                                          String lang, int speaker) {
            onSegmentWithLang(text, startMs, endMs, lang);
        }

        /**
         * 进度（0-100）。**percent 是阶段内进度**：每个阶段都从 0 重新开始，
         * 阶段之间不连续、不可直接比较，{@code stage} 是区分它们的唯一依据。
         *
         * <p>已知阶段：{@code "语音分段"}（VAD 预扫，按已喂样本估算）、
         * {@code "说话人分离"}（按已算完的 embedding 块估算）、
         * {@code "语音识别"}（按已解码段数估算）。引擎新增阶段时 stage 会变，
         * 消费方务必按 stage 分段映射，**不要**对 percent 施加全局单调守卫——
         * 那会让后一阶段的低值被前一阶段的高值永久吞掉（真机症状：进度环冻结）。
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
     * <p>实现可选用 {@code modelDir/diarization/} 下的离线说话人分离模型；
     * 目录或模型文件缺失时须静默降级为「不含说话人」的识别，不得因此让 init 失败。
     *
     * @param modelDir  含 model.int8.onnx 与 tokens.txt 的目录
     * @param language  语种（auto/zh/en/ja/ko；auto 交给模型自带 lang2id）
     * @return 是否成功
     */
    boolean init(String modelDir, String language);

    /**
     * 是否启用说话人分离（须在 {@link #init} **之前**调用，之后调用无效）。
     *
     * <p>默认启用。**分块识别场景必须显式关掉**（见 ProgressiveAsrSession）：
     * 说话人分离是整段一次性推理，每块各跑一遍既成倍放大耗时，聚类编号还会
     * 在不同块间各自从 0 开始、无法跨块对应同一个说话人——不如不给标签。
     *
     * <p>未实现该能力的引擎无副作用（默认空实现）。
     */
    default void setDiarizationEnabled(boolean enabled) {
    }

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
