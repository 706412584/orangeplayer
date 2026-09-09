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

## 实施中须记录

- sha256：AAR / 模型下载后补录（M1 下载时）
- M1 真机 RTF 基准（4/8 线程 1min 样本计时外推）
