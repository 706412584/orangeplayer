# 实施计划：AI 自动字幕生成（sherpa-onnx + SenseVoice）v3（计划审核后定稿）

> 依据 AI_ASR_SUBTITLE_DESIGN.md v2、3 代理设计审核 + 1 代理计划审核修正。分支 feature/ai-batch-translation。

## 0. 计划审核修正落点（对应意见）

| # | 计划审核意见 | 落点 |
|---|---|---|
| 阻塞-1 | **验收需 golden 样本**：4 语样本 wav + 期望文本 + 准确率阈值（日韩≥80%、中英≥90% 拟定，实测定） | M0 前置建立 |
| 阻塞-2 | **进度/取消双阶段**：VAD 预扫阶段不可中断 → token 由 UI 签发覆盖 init+VAD+解码三态；VAD 段报不确定进度 | M2 |
| 阻塞-3 | **getProxyUrl 仅单参**（代码事实）→ 改 ExternalProxyCacheManager 加公开 `getCachedFileForUrl(url)` | M3 |
| 建议-1 | 工厂/加载器并入 M1.2（asr_smoke 依赖）；keep 同批 | M1 |
| 建议-2 | overrideLibrary main+test 双 manifest；M1 含 CI 门禁验证（app unit test 链） | M1 |
| 建议-3 | 资源前置：AAR/模型/样本 URL+sha256 定清单；验 jni ABI fat 冲突 | M0 |
| 建议-4 | 标记清洗/srt 序列化加单测；sherpa 模块含 settings include + maven 发布段 | M2 |
| 建议-5 | palyerlibrary 实际 minSdk 14（设计误写 23）：接口纯 JDK 类型，运行时 API26 门禁（mpv 先例） | M1 记录 |

## M0：验证基建（前置，验收可测的前提）

1. **样本集**：4 语（中英日韩）各 2 段 wav（16k/mono，共 8 段，每段 10-30s）+ 期望文本 + 准确率阈值（英中≥90%、日韩≥80%，实测校准）
2. **资源清单**：AAR URL+sha256、SenseVoice int8 模型 URL+sha256、样本 URL；AAR aapt2 badging/javap/jni ABI 清单验 fat 冲突
3. 入库：样本与期望文本进 `orangeplayer-sherpa/src/test/` 或 assets（测试用）

## M1：模块骨架 + AAR 集成验证

1. M0 已完成 AAR 兼容三角验证（minSdk/字节码/jni ABI）→ 定模块 minSdk/JDK
2. **建模块 orangeplayer-sherpa**：
   - settings.gradle include + maven 发布段（照 orangeplayer-mpv）
   - palyerlibrary：BatchAsrEngine 接口（纯 JDK 类型）+ SherpaAvailabilityChecker（Class.forName）
   - orangeplayer-sherpa：AAR 依赖 + BatchAsrEngine 实现 + **引擎工厂 SherpaAsrEngineFactory**（加载器）+ consumer-rules keep
   - app：implementation project + manifest overrideLibrary（main+test 双份）
3. **真机冒烟**（asr_smoke 广播钩子）：4 语样本 → 准确率 + RTF 基准（1min 样本 4/8 线程外推）
   - 验收：准确率达 M0 阈值；release(R8 on) 构建过；**CI（app unit test 链）过**
4. 门禁：日韩样本识别正确（硬性）

## M2：批量 ASR 引擎 + 音频管线

1. **FFmpegKit.extractAudioWav16k**（-vn -ac 1 -ar 16000 -c:a pcm_s16le）+ stub 拦截
2. **SherpaBatchAsrEngine**：
   - 模型单例（进程内复用，参照 VoskModelCache）
   - transcribeFile(wav, listener, cancelToken)：VAD 预扫（报不确定进度+可查 token）→ 段集 → 逐段 decode → VAD 边界 (text,startMs,endMs)；标记清洗
   - **token 三态**：init/VAD/解码 每段间查；取消响应 ≤ 1 段时长
3. **单测**：标记清洗、srt 序列化、VAD 段边界样例（真机段边界人工审查 1 段）
4. **srt 注入**：SubtitleEntry → 临时 .srt → SubtitleManager.loadSubtitle(File)
5. **本地文件入口** VideoEventManager.generateSubtitlesFromFile（后台 + ProgressDialog）
   - 验收：4 语 mp4 出字幕；取消 ≤段长响应；临时文件清理断言

## M3：网络入口 + UI + 翻译接续

1. **ExternalProxyCacheManager.getCachedFileForUrl(url)**（公开方法，校验 exists）→ 同管线；HLS 提示下载
2. **模型下载管理**：orange-downloader 断点+校验+filesDir；内存 <4GB 入口隐藏
3. **UI 区块**："AI 语音生成字幕"（语言选择/双阶段进度/状态）；翻译 cacheKey 含 lang+model 版本
4. **翻译接续**：ASR 字幕 → AiTranslationEngine → 译文显示
   - 验收：网络 mp4 全链路；回归外挂字幕翻译 + Vosk 实时路径

## 风险登记

- M1 高：AAR 兼容三角 + 模型下载网络 + R8/CI
- M2 中高：SenseVoice 句级时间戳/VAD 边界假设（真机段边界人工审查兜底）
- M3 中：缓存 API + UI 生命周期
- app 默认单 arm64（orangeAppReleaseAbis）；HLS v1 不支持

