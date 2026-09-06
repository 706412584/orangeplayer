# Spike: 评估与决策模板

> 每个 Spike 完成后填写对应小节；全部完成后填写最终决策。

## S4 事件映射

- 结论：适配层状态机可映射，暂未发现不可映射事件；native 路径待 S2 验证。
- 验证记录：2026-09-05 运行独立工程 `test`，Java 桩 5/5 通过；详见
  `S4-event-mapping.md`。该结果不代表 libmpv JNI 或真机 Surface 已验证。

## S2 渲染（✅ 2026-09-07 真机验证通过）

- **验证方式**：spike app（`app/` 模块，独立 APK 25.9MB arm64），
  libmpv AAR = jarnedemeulester/libmpv-android v1.0.0（mpv 0.41.0 / FFmpeg 8.1）。
  设备 9c18cb30。
- **SurfaceView 路径**：✅ 通过。1920x1080 硬解（nv12）渲染清晰无花屏，
  GPU VO（rgba16f FBO）正常。关键实现细节（与官方 BaseMPVView 对齐）：
  1. `MPVLib.init()` 必须在 create 后、loadfile 前调用（漏掉则事件循环不启动）
  2. `surfaceCreated` 即 attach + `force-window=yes`
  3. `surfaceChanged` 设 `android-surface-size` 属性
  4. `idle=once` + 延迟 loadfile（S4 结论成立）
- **事件映射真机验证**：✅ FILE_LOADED→onPrepared、END_FILE→onCompletion、
  PLAYBACK_RESTART→seek 完成、time-pos/duration 属性推送全部实测正常。
- **暂停/seek**：✅ pause 属性翻转、seek 精确跳转。
  踩坑：seek 命令第三参 flags 只接受 `relative`/`absolute` 等单一值，
  组合值会解析失败且被静默丢弃。
- **旋转**：✅ configChanges 不重建，横竖屏 surfaceChanged（1080x1602↔2210x472）
  无黑屏残留。
- **网络**：✅ HTTP 拉流正常（需 INTERNET 权限，spike 初版漏权限导致 DNS 失败）。
- **本地文件**：✅ 注意 scoped storage——/sdcard/Download 不可读
  （Permission denied），须用 App 私有目录 `/sdcard/Android/data/<pkg>/files/`。
- **Anime4K 实时超分（S2 附加，✅ A/B 对照验证）**：
  - Mode A 四 shader 链（Restore_CNN_M + Upscale_CNN_x2_L +
    AutoDownscalePre_x2 + Upscale_CNN_x2_M，MIT）打包进 assets，
    释放到 filesDir 后用 `change-list glsl-shaders add` 逐个挂载。
  - 踩坑：此 libmpv 构建的 `glsl-shaders` 属性不支持分号分隔多文件
    （整串被当单一路径打开失败），必须用 change-list 命令。
  - 1080P 源：Upscale 段空转，仅 Restore 轻度锐化，肉眼难辨（符合设计预期）。
  - 480x270 低清源：**A/B 对照确认效果显著**——对角线锯齿大幅减少、
    色块边界干净锐利、棋盘格图案清晰，双线性放大的模糊/阶梯感消失。
  - 性能：1168 帧渲染 Janky 仅 0.77%，GPU 无压力。
  - 结论：**Anime4K 价值定位 = 低清源补偿**（老番/低码率），对高清源无意义。
- **TextureView 路径**：⬜ 未验（mpv 官方走 SurfaceView；转正时按需补验）。
- **OCR 截帧兼容性**：⬜ 未验（依赖 PixelCopy 兜底路径，转正时验证）。
- **软解性能**：⬜ 未测（硬解已通过，软解极端场景转正时补测）。
- **APK 体积**：spike 单 APK 25.9MB（含 libmpv so + minimal UI），与 S1 测算一致。

## S3 字幕

- libass 内渲染 vs 外部层决策：⬜ 待 S2 转正阶段（spike 已确认 libass so 可加载）
- OCR 链路影响：⬜

## S1 体积/共存（✅ 2026-09-07 实测）

- 各 ABI so 增量：arm64-v8a 单 ABI **+23.8MB**（实测解包 AAR）。
  详见 `S1-size-and-source.md`。
- 三 FFmpeg 共存实验（IJK ex_so + Jellyfin decoder + libmpv）：⬜ 未验
  （spike 为独立进程；转正时并入主 App 验证符号冲突）。

## S6 CI

- 独立 workflow 状态：⬜ 未建（spike 构建已本地验证：JDK21 + AGP 8.12 + Gradle 8.13）
  - 注意：libmpv AAR 的 classes.jar 为 Java 21 字节码（v65），主仓 JDK17 编译时
    需升级或显式依赖注入策略。spike 工程已用 D:\android\openjdk\jdk-21.0.11+10。

## 最终决策

- [ ] GO：进入独立工件 orangeplayer-mpv 产品化
- [ ] NO-GO：存档本目录，理由如下
- 理由：（待补：三 FFmpeg 共存实验 + minSdk 26 降级路径确认后定）
