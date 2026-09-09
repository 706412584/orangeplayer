# 设计方案：AI 自动字幕生成（ASR）——sherpa-onnx + SenseVoice 集成

> 分支：feature/ai-batch-translation | 日期：2026-09-10 | 状态：已过 3 代理审核（架构/Android 工程/语音技术），v2 修正版

## 1. 背景与目标

OrangePlayer 已完成"外挂字幕 AI 批翻译"（AiTranslationEngine，分批 LLM + 术语表 + 断点缓存，真机验证通过）。
本方案补上**无外挂字幕视频**的自动字幕生成：本地/网络视频 → 抽取音轨 → 离线 ASR → 字幕条目 →（可选）现有 AI 批翻译 → 显示。

**目标语种**：中文、英文、日文、韩文（用户主诉），SenseVoice 五语合一覆盖（中英日韩粤）。

**现状差距**：
- 现有 VoskSpeechEngine：实时识别路径（内录 AudioPlaybackCapture 逐句），无批量/文件输入；vosk-small 模型识别率差、项目停滞
- 无"整段音频 → 带时间戳文本"的离线批量 ASR
- orange-ffmpeg 已有 `extractAudio(input, output, toMp3)`（-vn 抽轨，copy/mp3）
- 视频缓存：ExternalProxyCacheManager（danikula HttpProxyCacheServer），网络 mp4 全缓存后可拿本地文件

## 2. 技术选型（已调研 2026-09）

| 项 | 选择 | 依据 |
|---|---|---|
| ASR 运行时 | **sherpa-onnx 1.13.7**（k2-fsa，14.7k★） | 官方 Android AAR 直下（无需编 so）、词级时间戳、silero-vad、活跃维护 |
| 模型 | **SenseVoice int8**（csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue） | 五语合一覆盖目标语种；int8 239MB；官方钦定中英日韩粤方案；带情感/笑声标记 |
| 集成方式 | AAR 依赖 + **模型可选下载**（首次用提示下载，非随包） | AAR 单 arm64 ~35MB 可随 app；模型 239MB 不可随包 |
| 音频输入 | 本地文件：FFmpegKit.extractAudio 转 wav 16k 单声道；网络：缓存文件同路径 | 复用现有 orange-ffmpeg |
| 字幕回写 | SubtitleEntry + SubtitleManager.applyAiTranslation 同族 | 已有 |

**候选备选**（若 SenseVoice 239MB 不可接受，均同 AAR 运行时）：
- Whisper-tiny.en int8 ~40MB（仅英文）
- Paraformer-zh int8 ~30-60MB（仅中文）
- Zipformer 语种单模型 ~100MB+
结论：五语合一在"中英日韩"需求下总占用最优；单语种拆分反而更大且切换复杂。

## 3. 架构设计

### 3.1 模块划分（新增 sherpa 集成独立模块，遵循 palyerlibrary 反射解耦哲学）

```
新模块 orangeplayer-sherpa (android library)
  ├── 依赖 sherpa-onnx AAR（compileOnly 或 implementation？见 §5 风险）
  └── 对外接口全部在 palyerlibrary（新增，反射加载，同 Vosk/MlKit 模式）

palyerlibrary/speech 扩展现有接口族：
  SpeechEngine（现有，实时流）→ 保留 Vosk 实现
  BatchAsrEngine（新增接口）：
      init(context, modelPath, tokensPath, language)
      transcribeFile(wavPath, callback)  → 回调 onSegment(text, startMs, endMs)
      transcribeBuffer(...)
      release()
```

**为什么保留 Vosk**：实时逐句场景（用户开着播放器看，内录实时出字幕）与批量场景（整片处理）需求不同；Vosk 实时仍可用，SenseVoice 主打批量高质量。两套并存，UI 上语音字幕入口可选"实时(Vosk) / 批量(sherpa)"。**或**：Phase 2 若 sherpa 实时体验达标（官方有 streaming zipformer + VAD Android demo）再切换实时通道。

### 3.2 批处理管线（核心流程）

```
触发（视频 URL / 本地文件 / 播放器"生成字幕"按钮）
  │
  ├─ 输入解析
  │    ├─ 本地文件 / content:// → File
  │    └─ 网络 URL：
  │         ├─ 非 HLS：HttpProxyCacheServer.isCached? 取缓存文件 : 提示"先完整缓存/下载"
  │         ├─ HLS：当前代理缓存不支持 → 提示用 orange-downloader 下载后处理
  │         └─ （v1 边界：网络视频需先缓存完成，见 §5 风险 R1）
  │
  ├─ 音频抽取（orange-ffmpeg）
  │    ffmpeg -i input -vn -ac 1 -ar 16000 -c:a pcm_s16le output.wav
  │    （extractAudio 现为 copy/mp3，需新增 wav pcm16k 变体或扩展参数）
  │    ⚠ FFmpegKit 仅 arm64 真机可用（stub 模式其他架构）——校验可用性
  │
  ├─ 批 ASR（sherpa-onnx 新线程，非 UI 线程）
  │    SenseVoice 非流式整段 + silero-vad 分句 → onSegment 回调
  │    （可选并行：VAD 分句后多线程，v1 不并行，先保证正确）
  │
  ├─ 段聚合 → SubtitleEntry(startMs, endMs, text) 列表
  │    短段合并（<500ms 与邻段合并）→ 长句按 6s 上限切分？v1 保持 VAD 分段结果
  │
  ├─ 结果持久化：srt/ass 导出 or 内存列表？v1 内存 + 可选导出
  │
  └─ 可选接现有 AiTranslationEngine 批翻译 → SubtitleManager 回写显示
```

### 3.3 线程与生命周期

- BatchAsrEngine 调用在专用单线程 Executor（避免阻塞播放器线程）
- 进度回调（处理中 0-100%）→ UI ProgressDialog（复用 startAiTranslate 模式）
- Activity 重建/退出：任务取消（sherpa OfflineRecognizer 无 cancel？→ 靠线程中断 + 忽略结果）
- 大文件（1h 视频 ~600MB wav？不——wav 16k 单声道 = 16k*2B*3600s = 115MB；可接受临时文件）
- 临时文件放 cacheDir，用完删

### 3.4 UI 入口

字幕设置面板新增区块（现有 subtitle_dialog 的 AI 批量翻译下方）：
- "AI 语音生成字幕"按钮 → 语言选择（自动检测 or 中/英/日/韩）→ 开始
- 状态：抽取音频 x% → 识别中 y% → 完成 N 条 / 失败原因
- 完成后：直接注入 SubtitleManager 显示（同外挂字幕路径），可选接翻译

## 4. 复用资产清单

| 现有资产 | 用途 |
|---|---|
| AiTranslationEngine + ai/ 包 | ASR 后翻译 |
| SubtitleManager/SubtitleEntry/applyAiTranslation | 显示/回写（已修 alpha/闪烁/背景条 bug） |
| orange-ffmpeg FFmpegKit.extractAudio | 抽音轨（需扩 pcm16k wav） |
| ExternalProxyCacheManager → HttpProxyCacheServer.isCached/getProxyUrl | 网络缓存定位 |
| VideoEventManager 测试广播钩子 | adb 自动化测试 |
| SpeechEngine/SpeechSubtitleManager（Vosk） | 实时路径保留 |

## 5. 风险与边界（需决策）

- **R1 网络视频输入**：HLS 无本地文件、非 HLS 需先缓存完。v1 建议仅支持"本地文件/已完整缓存 mp4"，HLS 提示先下载。**审核重点**
- **R2 模型体积**：SenseVoice int8 239MB 下载，用户体验/存储。备选分语种小模型。**审核重点**
- **R3 AAR 依赖策略**：sherpa AAR 35MB 随 app 进包 or 独立可选模块（同 orangeplayer-mpv 模式，app 才依赖）。palyerlibrary 哲学是 compileOnly+反射 → 建议 orangeplayer-sherpa 独立模块，app 显式依赖，palyerlibrary 反射。
- **R4 minSdk**：palyerlibrary minSdk 23；sherpa AAR 要求？onnxruntime 通常 minSdk 21+；AudioPlaybackCapture 29+ 但批量不需要（文件输入）→ 批量路径无权限门槛。**验证 AAR minSdk**
- **R5 内存/耗时**：SenseVoice 非流式整段内存占用；239MB int8 加载~几百 MB RSS；1h 音频 CPU 耗时需真机测（预期 < 实时）
- **R6 词级时间戳 vs VAD 分段**：SenseVoice 输出词级时间戳需 offline-recognizer 的"句级"？—— sherpa OfflineRecognizer 返回句子级时间戳需配置 returnTimestamps，SenseVoice 支持句级 via VAD。**落地细节验证**
- **R7 语种自动检测**：sherpa 有 SpokenLanguageIdentification demo；SenseVoice 无自动检测，v1 用户手选 or 先跑一遍检测模型（~30MB 额外）

## 6. 验证计划（每阶段验收）

1. **AAR 编译验证**：新模块空工程 + sherpa AAR 编译通过；跑官方 demo 思路（Java 调 OfflineRecognizer）真机识别 test.wav 出字
2. **模型下载与加载**：SenseVoice int8 下载 → 真机加载 → 识别中文/英文/日文/韩文各一段样本，记录准确率与耗时
3. **管线集成**：本地 mp4 → extractAudio wav → sherpa → SubtitleEntry → SubtitleManager 显示（复用测试广播钩子验证）
4. **翻译接续**：ASR 字幕 → AiTranslationEngine → 显示译文（复用已验链路）
5. **回归**：外挂字幕翻译不受影响；Vosk 实时路径不受影响

## 7. 里程碑（建议，最终以审核后计划为准）

- M1：orangeplayer-sherpa 模块骨架 + AAR 集成 + 真机识别验证（R3/R4 决策落地）
- M2：批量 ASR 引擎（文件输入 → 分段字幕）+ 音频抽取扩展
- M3：UI 接入 + 网络/本地入口 + 进度
- M4：翻译接续 + 全链路真机验证 + 回归
