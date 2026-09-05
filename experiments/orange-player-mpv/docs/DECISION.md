# Spike: 评估与决策模板

> 每个 Spike 完成后填写对应小节；全部完成后填写最终决策。

## S4 事件映射

- 结论：适配层状态机可映射，暂未发现不可映射事件；native 路径待 S2 验证。
- 验证记录：2026-09-05 运行独立工程 `test`，Java 桩 5/5 通过；详见
  `S4-event-mapping.md`。该结果不代表 libmpv JNI 或真机 Surface 已验证。

## S2 渲染

- SurfaceView/TextureView 双路径：
- 旋转/前后台：
- OCR 截帧兼容性：
- 软解性能（1080p/4K CPU 占用、耗电）：

## S3 字幕

- libass 内渲染 vs 外部层决策：
- OCR 链路影响：

## S1 体积/共存

- 各 ABI so 增量（arm64-v8a 基线对比）：
- 三 FFmpeg 共存实验（IJK ex_so + Jellyfin decoder + libmpv）：

## S6 CI

- 独立 workflow 状态：

## 最终决策

- [ ] GO：进入独立工件 orangeplayer-mpv 产品化
- [ ] NO-GO：存档本目录，理由如下
- 理由：
