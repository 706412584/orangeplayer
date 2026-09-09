# ASR 验证资源清单（M0 产物）

> 分支 feature/ai-batch-translation | 2026-09-10

## AAR 兼容三角（已验证 ✅）

| 项 | 结果 |
|---|---|
| sherpa-onnx 版本 | 1.13.7（GitHub Release v1.13.7） |
| AAR URL | https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-1.13.7.aar |
| AAR 体积 | 49.1MB（4 架构）；单 arm64 约 35MB（onnxruntime 21.7 + sherpa so ~13.6） |
| **minSdk** | **21**（manifest 明文 `minSdkVersion="21"`）→ 项目无冲突，无需 overrideLibrary |
| **字节码** | **Java 8**（major version 52）→ 无 JDK21 fork 需求（对比 libmpv 教训） |
| jni ABI | arm64-v8a / armeabi-v7a / x86 / x86_64 全 4 架构 |
| 打包策略 | app 默认单 arm64（orangeAppReleaseAbis 属性），gradle abiFilters 自动裁 |

## 模型

| 项 | 值 |
|---|---|
| 模型 | sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17（int8） |
| HF URL | https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17 |
| 文件 | model.int8.onnx（239.2MB）+ tokens.txt（0.3MB）+ model.onnx（937MB 不下载） |
| 语种 | zh/en/ja/ko/yue（lang2id 含 auto） |

## Golden 样本（4 语，来自模型仓库 test_wavs）

本地路径：docs/asr_samples/{zh,en,ja,ko}.wav
来源：https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/test_wavs/{lang}.wav
格式：16k 单声道 wav（各 ~150-230KB，官方标准测试句）

**期望文本**：官方仓库未附 golden 文本 → M1 真机冒烟时人工核验（标准短句）。
**准确率阈值**（拟定）：中英 ≥90%，日韩 ≥80%（SenseVoice 日韩为覆盖级，实测校准）。

## M1 真机验证结果（SKW-A0 / Android 10 / arm64）

**4 语识别（SenseVoice int8 auto 模式，VAD 分段 + 逐段解码）**：

| 语种 | 样本时长 | 识别文本 | init+总耗时 |
|---|---|---|---|
| 中文 | 5.6s | 派放时间早上9点至下午5点。 | ~2.6s |
| 英文 | 7.2s | The tribal chieftain called for the boy. / And presented him with 50 pieces of code.（VAD 自动分 2 句） | ~2.8s |
| 日文 | 7.2s | うちの中学は弁当制で持っていけない場合は五十円の学校販売のパンを買う。 | ~2.8s |
| 韩文 | 4.6s | 금만 생각을 하면서 살면 훨씬 편할 거야. | ~2.4s |

- 模型加载（init）：~2.0s（239MB int8）
- **解码 RTF ≈ 0.08-0.1（约 10x 实时）**：7s 音频解码 <0.7s → 1h 视频音频预计 <10min
- 英文样本 VAD 正确断 2 句（句子边界=字幕时间轴验证通过）

**过程中修复**：FeatureConfig featureDim 必须 80（fbank 维），传 1 时 decode 恒空（首日最大坑）。

**sha256**：
- model.int8.onnx: c71f0ce00bec95b0...
- tokens.txt: f449eb28dc567533...
- silero_vad.onnx: 9e2449e1087496d8...（https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx）
