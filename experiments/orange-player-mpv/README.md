# orange-player-mpv 原型（P7 Spike）

> 状态：**仅验证，永不发布**。本目录是独立 Gradle 工程，不被主仓
> `settings.gradle` include，不 apply 任何 publish 脚本，主 SDK
> （palyerlibrary/app/app-legacy/app-tv）零改动。

## 目标

验证 libmpv 作为 OrangePlayer 第 5 播放内核的技术可行性，产出
**go/no-go 决策报告**。不做正式集成、不做性能调优、不上 Maven Central。

## 隔离纪律（CI 门禁强制）

- 主仓 `settings.gradle` / `palyerlibrary/build.gradle` / 发布脚本
  中 grep 不到 `mpv`
- 开发分支：`spike/mpv`；唯一允许合并回 main 的是本目录的文档
- 若未来转正：作为独立可选工件 `orangeplayer-mpv`（compileOnly 接线、
  默认关闭、引擎注册表标记"仅用户显式选择、永不自动回退到达"），
  绝不进 `orangeplayer` 主工件

## Spike 顺序与验收口径

| # | Spike | 内容 | 验收（go 条件） | 状态 |
|---|---|---|---|---|
| S5 | 许可证 | mpv LGPL 构建路径与仓库许可边界 | 书面结论 | ✅ 见下文 |
| S4 | 事件映射 | mpv 事件 → `IMediaPlayer` 回调 + GSY Surface 生命周期 | 映射表评审通过 + 桩验证时序 | ⬜ |
| S2 | 渲染 | SurfaceView/TextureView 双路径、旋转、OCR 截帧、软解性能 | 旋转不黑屏 + 性能数字 | ⬜ |
| S3 | 字幕 | libass 内渲染 vs 外部 SubtitleView 层 | 决策（倾向禁用 mpv 字幕沿用外部层） | ⬜ |
| S1 | 体积/共存 | 各 ABI 增量；同进程三 FFmpeg 冲突实验 | 体积数字 + 无冲突或隔离方案 | ⬜ |
| S6 | CI | 独立 workflow 构建 demo APK + artifact | 绿灯且不动 release-apk.yml | ⬜ |

## S5 许可证结论（2026-09-05）

**结论：可行（LGPL 路径），不构成否决项。**

事实（已核实上游仓库）：
1. mpv 本体默认 GPLv2+，但提供 `-Dgpl=false` 构建开关，排除 GPL-only
   文件后为 **LGPLv2.1+**（`Copyright` 文件明确此双许可结构）。
   - 代价：部分功能被排除（GPL-only 组件清单见 mpv `Copyright` 文件），
     需在 spike 中确认核心播放链路（demux/decode/render）不依赖被排除
     组件。
2. mpv-android 的 JNI 封装层（`MPVLib` 等）为 **MIT**，可自由采用。
3. FFmpeg 需配套 **LGPL 构建**（`--disable-gpl --disable-nonfree`），
   与仓库现状（IJK 的 LGPL so）同级别，无新增传染风险。
4. 预编译产物溯源：优先自建（jellyfin-androidx-media 模式）或采用
   明示 LGPL 构建的可信源；纯 GPL 构建的产物**不可**进入任何
   分发渠道。

仓库边界：
- 主 LICENSE 为 Apache-2.0，已含 LGPL（IJK so）与专有（阿里云 SDK）。
- LGPL 的义务（修改 LGPL 部分需开源）通过"独立模块 + 独立 LICENSE/NOTICE
  + 源码提供链接"满足；不与 Apache 主工件混合打包分发。
- Demo APK（GitHub Releases 分发）携带 LGPL so 是允许的（LGPL 允许
  随闭源应用分发，需提供源码获取方式与许可声明）。

## 集成路径（已定）

采用**预编译 libmpv AAR**（等价仓库现有 GSY so 模块与 Jellyfin
decoder 的消费模式）→ 新模块内实现 `MpvPlayerManager implements
IPlayerManager`（fork 内已有 4 个先例）。源码自建仅在验证通过且
需要 LGPL 裁剪时启动。

## 技术参考

- mpv-android `MPVLib.kt` / `BaseMPVView.kt`（MIT）：JNI 桥接与
  Surface 生命周期蓝本
- `IjkExo2MediaPlayer`（本仓库 GSY fork）：`IMediaPlayer` 语义适配先例
- S4 最大风险：mpv 的属性轮询/事件模型 → GSY 的
  notifyOnPrepared/notifyOnCompletion/notifyOnError 时序
