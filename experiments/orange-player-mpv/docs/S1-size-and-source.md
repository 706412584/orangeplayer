# mpv 第五内核评估报告（Spike S1 体积/来源 + 汇总）

> 任务 #23 / #25 产出。基于 P7 原型（experiments/orange-player-mpv）已完成的 S4/S5 结论，
> 本轮补齐 S1（体积/预编译来源）实测数据。

## 一、预编译产物来源（S1 核心问题）

**推荐源：jarnedemeulemeester/libmpv-android（68★）**
- 基于 mpv-android 官方 buildscripts（同源维护，版本跟进：mpv 0.41.0 / FFmpeg 8.1 / libass 0.17.4）
- 直接产出 **libmpv-release.aar**（v1.0.0），GitHub Releases 分发
- 包含完整 MIT Java 层（MPVLib，namespace dev.jdtech.mpv）
- 支持 MPVLib.create(context) 多实例（v1.0.0 起）
- minSdk 26 / compileSdk 36 —— 与我们 app minSdk 23 冲突！见风险 R1

mpv-android 官方明确表态不提供库形态（README "not a library"），此仓库即社区标准答案，
Jellyfin 生态亦采用同模式。

## 二、体积实测（S1）

当前 APK（arm64 debug）：**92.9 MB**，其中 so 合计 66.5 MB（IJK 三件套 6.8MB、
阿里 11.3MB、translate_jni 14.8MB、torrent 11.9MB、vosk 8.5MB、tesseract 6.1MB、orangeffmpegkit 5.0MB）。

libmpv AAR 实测解包：

| ABI | so 合计 |
|---|---|
| arm64-v8a | **23.8 MB**（libmpv 6.2 + libavcodec 11.4 + libavformat 2.8 + 其余 3.4） |
| armeabi-v7a | 20.7 MB |
| x86_64 | 26.5 MB |
| x86 | 27.2 MB |

**结论：arm64 单 ABI 增量 +23.8MB**（APK 92.9 → 约 116MB，+26%）。
当前 app 默认 abiFilters = arm64-v8a only，若维持单 ABI 分发，增量可控但显著。

**减重路径（转正时必做其一）**：
1. ABI 拆分分发（App Bundle / 多 APK）——各 ABI 只带自己的 so
2. 动态特性模块（Dynamic Feature Module）：mpv 内核按需下载，不选 mpv 的用户零成本
3. 自建裁剪（去掉 dav1d 之外的 av1/x86 解码器、裁 lua 等）——buildscripts 可控

## 三、许可证（S5，P7 已结论，维持）

- libmpv 走 **-Dgpl=false LGPL 构建**；该 AAR 为社区构建，**需在转正前核实其构建参数
  是否 LGPL**（仓库无明示 GPL 排除声明 → 记为风险 R2，动作：联系作者或自建验证）
- mpv-android JNI 层（MPVLib）MIT，可自由采用
- 分发模式：独立工件 orangeplayer-mpv + 独立 LICENSE/NOTICE + 源码链接，与 IJK LGPL so
  同级别，无新增传染

## 四、技术风险清单

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | 该 AAR minSdk 26 > 我们 app 23 / palyerlibrary 14 | 高 | 方案 a：独立模块声明 minSdk26，palyerlibrary compileOnly，运行时检测；方案 b：fork 其 buildscripts 自建 minSdk 降级版 |
| R2 | AAR 构建参数未明示 LGPL | 中 | issues 问询作者 / 自建（buildscripts 全开源） |
| R3 | 三 FFmpeg 符号冲突（ijkffmpeg/libavcodec/libffmpegJNI 同进程） | 中 | Android so 按 namespace 隔离，理论无冲突，需真机验证（原 S1 计划项） |
| R4 | mpv 属性轮询模型 → GSY IMediaPlayer 语义 | 低 | S4 已桩验证 5/5，native 待验 |
| R5 | OCR/截图链路（TextureView 依赖） | 中 | mpv 走 SurfaceView 渲染，OcrSubtitleManager 已有 PixelCopy 兜底 |

## 五、能力收益（对应任务 #24）

1. **Anime4K 实时超分**：libmpv 原生 glsl-shader 链加载（mpv-android 同款），GPU 跑
2. 原生 ASS 字幕全特效（现在 Exo 路线的 ASS 支持是简化版）
3. libplacebo 高质量缩放/去带/HDR tone mapping
4. RIFE 类插帧社区方案兼容

## 六、go/no-go 建议

**建议：有条件 GO**——条件：
1. 先做 S2 真机渲染 spike（用该 AAR 跑 demo APK：播放/旋转/截图/OCR/软解性能）
2. R1 minSdk 路径落地（compileOnly + 运行时检测，或自建降版）
3. R2 许可证实锤

**实施形态**（转正时）：独立可选工件 `orangeplayer-mpv`，引擎注册表标记
"仅用户显式选择、永不自动回退到达"（与 P7 原型纪律一致），不进主工件。

## 七、任务 #24 的路线决策依据

若 mpv 转 NO-GO：Anime4K 走 GL 管线路线（移植 shader 进 GSYVideoGLView
ShaderInterface，与现有画质增强滤镜同构）——该路线无 so 依赖、无许可证风险，
但 shader 移植工作量约 3-5 个 pass，且无 libplacebo 缩放加持。
