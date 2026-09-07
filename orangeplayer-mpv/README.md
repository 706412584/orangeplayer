# orangeplayer-mpv

> 状态：**可选独立工件**。宿主按需引入，默认不编译进主 SDK。

## 引入方式

```groovy
// 宿主 App（可选）
implementation project(':orangeplayer-mpv')
```

首次构建前需准备 libmpv 本地 maven 仓库（AAR 约 45MB，不入 git）：

```bash
# 手动方式：从 GitHub Release 下载
# https://github.com/jarnedemeulemeester/libmpv-android/releases
curl -L -o orangeplayer-mpv/mavenrepo/dev/jdtech/libmpv/1.0.0/libmpv-1.0.0.aar \
  https://github.com/jarnedemeulemeester/libmpv-android/releases/download/v1.0.0/libmpv-release.aar
```

## 构建要求

- 本模块编译需要 **JDK 21**（libmpv AAR 的 classes.jar 为 Java 21 字节码 v65）。
  默认 fork 路径 `D:/android/openjdk/jdk-21.0.11+10`，可通过环境变量
  `ORANGE_MPV_JDK21` 覆盖。
- minSdk 26（libmpv AAR 要求）。

## 许可证

- 本模块代码：Apache-2.0（同主仓）
- libmpv AAR：**LGPLv2.1+**（mpv `-Dgpl=false` 构建 + MIT JNI 层
  jarnedemeulemeester/libmpv-android）
- Anime4K shader（assets/shaders/）：MIT（bloc97/Anime4K）

分发义务（LGPL）：随 App 分发需保留许可声明与本仓库源码获取方式；
修改 LGPL 部分需开源修改。详见 experiments/orange-player-mpv/docs（S5 结论）。

## 能力

- 播放内核：与 Exo/IJK/阿里/系统并列的第五内核（设置 → 播放核心 → MPV）
- 画质增强 6 档（经 glsl-shaders 属性原子切换，播放中实时生效、可持久化）：
  关闭 / 标准增强（Anime4K Restore）/ 鲜艳 / 黑白 / 复古（GLSL 色彩矩阵）/
  Anime4K 超分（Mode A 链，放大低清源时触发，受 shader WHEN 条件约束）
- 画面比例：裁剪/拉伸走 mpv 原生 `keepaspect`/`video-zoom`（GSY TextureView
  尺寸机制对 mpv vo 无效，见 docs/CHANGELOG.md 1.4.0）

## 设计

- `MpvPlayerManager`：GSY IPlayerManager 实现，引擎注册"仅用户显式选择、
  永不自动回退到达"（纪律见 experiments/orange-player-mpv/README.md）
- `MpvMediaPlayer`：mpv 事件 → IMediaPlayer 语义桥
  （映射表：experiments/orange-player-mpv/docs/S4-event-mapping.md）
- `Anime4KShaderLoader`：assets → filesDir 释放（资产不一致自动重写）；
  shader 链整体 set `glsl-shaders` 属性（change-list 无 clear，会叠加残留；
  spike S2 实测约束：glsl-shaders 属性不支持分号串）
- 超分价值定位：低清源补偿（480P-720P 放大），对原生高清源无意义
