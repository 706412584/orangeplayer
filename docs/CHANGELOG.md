# OrangePlayer 更新日志
## [1.4.2] - 2026-09-08

### 🛡️ MPV 内核稳定性（Android 16 全屏/切内核专项）

- **vo fatal 三层自愈体系**：`MPV_EVENT_SHUTDOWN` 标记核心终止 → `initVideoPlayer` 销毁重建；视频轨丢失（fatal 后 mpv deselect track 但不进 idle）经 playback-restart 校验后分层恢复——`vid=auto` 快速恢复（demuxer 缓存仍在，无网络重载）→ 连续 fatal 才 `loadfile replace` 全量重载兜底
- **全屏切换时序纪律（mpvRx PR#329 模式）**：surface 销毁回调主动 `vid=no` 摘视频轨再 detach（mpv 全程无视频输出诉求，杜绝 wid 空窗 fatal）；新 surface available 立即重绑 + `vid=auto` 恢复（lavf 自动 refresh seek 定位关键帧，解码器不销毁、音频不断）。实测 PJA110/Android 16 全屏切换黑屏从 1s+ 降至 ~500ms，vo fatal 归零
- **切内核 EGL fatal 无限循环修复**：切内核期间渲染 View 重建导致旧 SurfaceTexture 被 release，但包装 Surface 的 `isValid()` 仍为 true——`showDisplay` 增加 `TextureView.isAvailable()` 死源防线，死 texture 不再进入 mpv；连续 fatal（vid=auto 已试）标记核心不可恢复（EGL 状态损坏）走销毁重建，终止循环
- **SurfaceTexture 漂移检测修正**：`Surface.toString()` 含 `mNativeObject` 指针而 `SurfaceTexture.toString()` 不含，整串比对永远不等——改用 `@` 后 hex 身份段比对，消除同源误判引发的重复重绑与 vid no/auto 翻转（二次黑屏）
- **配套修复**：`loadfile` 的 `start=` 选项在本构建仅接受整数秒，进度回跳改至 `FILE_LOADED` 后 `seek absolute`；`loadfile replace` 的旧文件 `END_FILE` 经 `recovering` 标志静默消化（此前误报 completed 触发 GSY 完成界面）；`checkMpvSurfaceDrift` 挂载全部四条全屏路径（横/竖屏进出）并同步 GSYTextureView 保留纹理

---

## [1.4.0] - 2026-09-07

### 🎬 新增 MPV 第五播放内核（可选工件 orangeplayer-mpv）

- **MPV 内核（libmpv 0.41 / FFmpeg n8.1 / libplacebo v7）**：与 ExoPlayer / IJK / 阿里云 / 系统内核并列的可选第五内核，仅用户显式选择，永不自动回退
- **渲染与时序**：TextureView 渲染、SurfaceTexture 同源幂等 attach、prepare 前预创建/重建渲染 View、渲染尺寸经 `android-surface-size` 属性显式下发（修复全屏/切内核黑屏、二次切换报错）
- **GSY 子模块兼容补丁**：`prepareAsync` 前对已有效 surface 主动补推（对 Exo/IJK/Ali/系统四内核幂等，向下兼容）
- **MPV 画质增强 6 档**：关闭 / 标准增强（Anime4K Restore）/ 鲜艳 / 黑白 / 复古（GLSL 色彩矩阵 shader）/ Anime4K 超分（Mode A 4-shader 链）；档位持久化、会话自动恢复、经 `glsl-shaders` 属性原子切换（不重建渲染层、播放中实时生效）
- **画面比例**：裁剪/拉伸走 mpv 原生渲染选项（`keepaspect` / `video-zoom`），修复 GSY TextureView 尺寸机制对 mpv 无效导致的"短暂生效被拉回"
- **体积策略**：libmpv AAR（约 46MB）不入库，经本地仓库 + README 下载指引获取

### 🎨 UI 重构（品牌橙设计语言 + Material Symbols）

- **第一阶段**：品牌橙设计语言统一——Design Token（op_tokens）+ 夜间主题实质差异 + 首页能力门面重做（真机验证通过）
- **第二阶段**：Material Symbols 图标库统一（顶栏/底栏/快捷面板全部控制图标）+ 控制器排版收敛（真机多轮验收）
- 弹幕发送面板重构为右侧滑入面板（与设置/选集面板形态统一）
- 播放/暂停按钮图标微调（内边距 9→11dp，点击热区不变）
- 画质增强 GL 滤镜（Exo/IJK/系统内核）+ 滤镜持久化与会话恢复；阿里云内核渲染管线不兼容 GL 滤镜，自动禁用并提示

### 🔧 内核能力与容错（P2–P7 批次）

- **P2** 统一轨道枚举与选择 API；**P3** 外挂字幕断链修复、延迟 API 与真实 ASS 双路径支持（Exo 端到端）
- **P4** 内核能力矩阵与有界自动回退（K=1）；**P5** MediaSession 系统控制（锁屏/通知）与 TV 自动帧率匹配
- **P6** 可选 OkHttp DataSource；**P7** orange-player-mpv 原型隔离骨架与 CI 门禁；MediaSession 共享模块收尾
- Exo 坏段跳段自愈、下载去广告与锁定诊断日志（真机三问题收口）；退出全屏锁定残留修复
- M3U8 去广告开关改为持久化设置（去掉 demo 写死开启）
- Media3 升级至 1.11.0

### 📦 工程与发布基建

- 凭据出库，入库无凭据的 Maven 发布脚本（本地 gradle.properties / CI secret 注入）
- CI：修复 Maven 发布工作流、新增单元测试门禁、移除 app-legacy 单测任务（规避 DLNA manifest 冲突）、修复 app 单测 manifest 合并与主题解析
- gradle.properties 重新入库仅含构建配置；Jellyfin 解码器升至 1.9.0+1

---

## [1.3.2] - 2026-04-02

### 📦 依赖升级

#### ExoPlayer (Media3) 升级到 1.10.0
- **升级内容**：从 Media3 1.9.1 升级到 1.10.0（2026年3月26日发布）
- **HLS 相关改进**：
  - ✅ HLS 播放列表解析性能优化（缓存正则表达式匹配器）
  - ✅ 支持 HLS 插播广告的 `X-PLAYOUT-LIMIT` 标签
  - ✅ 支持 `#EXT-X-DEFINE` 的 `QUERYPARAM` 属性
  - ✅ HLS 冗余流支持：遇到加载错误时自动回退到备用位置
  - ✅ 在音频 renditions 中暴露 ID3 (EMSG) 元数据轨道
  - ✅ 修复 X-SNAP 行为，正确计算插播广告的开始和恢复位置
- **其他改进**：
  - 🎵 修复通话期间请求播放时不请求延迟音频焦点的问题
  - 🎬 新增 Dolby Vision Profile 10 支持
  - 📹 MP4 容器支持 Versatile Video Coding (VVC) 轨道
  - 🔄 修复从点播到直播转换时可能导致重新缓冲的问题
  - 🎨 新增 Material3 风格的播放控制 UI 组件
- **兼容性**：向后兼容，无破坏性 API 变更
- **测试结果**：✅ 编译成功，安装测试通过，功能正常

**相关文件**：
- `gradle/dependencies.gradle` - 更新 `mediaVersion` 从 `1.9.1` 到 `1.10.0`

**技术参考**：
- [Media3 1.10.0 Release Notes](https://github.com/androidx/media/releases/tag/1.10.0)
- [Android Developers - Media3](https://developer.android.com/jetpack/androidx/releases/media3)

---

###  HLS Discontinuity 智能检测与自动切换（重大更新）

#### 问题背景
- IJK 播放器基于 FFmpeg，而 FFmpeg 完全不支持 HLS 的 `#EXT-X-DISCONTINUITY` 标记
- 当视频开头就有 DISCONTINUITY 且存在 PTS 时间轴偏移时，会导致 seek 跳转错误
- 典型症状：seek 到 1 分钟跳到 2 分钟，seek 到 4 分钟跳到 8 分钟

#### 解决方案
- **新增 `TsPtsChecker` 类**：实现 TS 片段的 PTS（Presentation Time Stamp）检测
  - 支持加密/非加密 TS 的 PTS 提取
  - 使用 Java 自带的 `javax.crypto` 进行 AES-128 解密
  - 检测第一个片段的 PTS 偏移（开头 DISCONTINUITY 问题）
  - 检测相邻片段的 PTS 跳变（正片内部 DISCONTINUITY 问题）

- **优化 `M3U8AdRemover` 的 PTS 检测逻辑**
  - 区分"广告插入导致的 DISCONTINUITY"和"正片本身的 PTS 跳变"
  - 开头有 DISCONTINUITY 且第一个片段是正片时，下载并检测实际 PTS 值
  - 只检测正片内部的 PTS 跳变，跳过广告边界的 DISCONTINUITY
  - PTS 跳变阈值：正片内部 10 秒，第一个片段偏移 1 秒

- **`OrangevideoView` 自动切换播放器**
  - 检测到 `hasPtsJump=true` 时自动切换到 ExoPlayer
  - 带回退机制：ExoPlayer > AliPlayer > SystemPlayer
  - 用户无感知，自动选择最佳播放器内核

#### 相关文件
- `palyerlibrary/src/main/java/com/orange/playerlibrary/TsPtsChecker.java` - PTS 检测核心类（新增）
- `palyerlibrary/src/main/java/com/orange/playerlibrary/M3U8AdRemover.java` - 集成 PTS 检测
- `palyerlibrary/src/main/java/com/orange/playerlibrary/M3U8AdManager.java` - 传递 hasPtsJump 标志
- `palyerlibrary/src/main/java/com/orange/playerlibrary/OrangevideoView.java` - 自动切换播放器

#### 技术参考
- [FFmpeg Trac Ticket #5419](https://trac.ffmpeg.org/ticket/5419) - HLS EXT-X-DISCONTINUITY tag is not supported (open since 2016)
- [HLS RFC 8216](https://tools.ietf.org/html/rfc8216) - HLS 标准规范
- [ExoPlayer TimestampAdjuster](https://github.com/androidx/media/blob/release/libraries/common/src/main/java/androidx/media3/common/util/TimestampAdjuster.java) - ExoPlayer 的时间戳调整机制

---

### 🎮 播放器 UI 改进

#### 横竖屏切换按钮显示策略增强（新增）
- **新增横竖屏切换按钮显示模式枚举**
  - `RotationButtonDisplayMode.ALWAYS` - 全屏模式下始终显示
  - `RotationButtonDisplayMode.PORTRAIT_ONLY` - 仅竖屏全屏时显示（默认）
  - `RotationButtonDisplayMode.LANDSCAPE_ONLY` - 仅横屏全屏时显示
  - `RotationButtonDisplayMode.NEVER` - 始终隐藏
  
- **新增 API 方法**
  - `setRotationButtonDisplayMode(RotationButtonDisplayMode mode)` - 设置显示模式
  - `getRotationButtonDisplayMode()` - 获取当前显示模式
  - `@Deprecated setRotationButtonVisible(boolean visible)` - 旧 API，已标记为过时
  - `@Deprecated isRotationButtonEnabled()` - 旧 API，已标记为过时
  
- **默认行为优化**
  - 默认模式：`PORTRAIT_ONLY`（仅竖屏全屏时显示）
  - 横屏全屏时自动隐藏横竖屏切换按钮
  - 竖屏全屏时自动显示横竖屏切换按钮
  - 锁屏状态下隐藏按钮
  
- **智能判断逻辑**
  - 根据 `isFullscreen()`、`isPortraitFullscreen()`、显示模式、锁屏状态综合判断
  - 修复了 `onPlayerStateChanged()` 中直接设置可见性覆盖新模式逻辑的问题
  - 增强了诊断日志，方便调试

#### 使用示例
```java
// 获取 VodControlView 实例
VodControlView vodControlView = ...;

// 默认就是 PORTRAIT_ONLY，无需设置
// 进入竖屏全屏时会自动显示按钮，横屏全屏时自动隐藏

// 如果想改为其他模式：

// 1. 始终显示（全屏模式下）
vodControlView.setRotationButtonDisplayMode(
    VodControlView.RotationButtonDisplayMode.ALWAYS
);

// 2. 仅横屏全屏时显示
vodControlView.setRotationButtonDisplayMode(
    VodControlView.RotationButtonDisplayMode.LANDSCAPE_ONLY
);

// 3. 始终隐藏
vodControlView.setRotationButtonDisplayMode(
    VodControlView.RotationButtonDisplayMode.NEVER
);

// 4. 恢复默认（仅竖屏全屏显示）
vodControlView.setRotationButtonDisplayMode(
    VodControlView.RotationButtonDisplayMode.PORTRAIT_ONLY
);
```

#### 技术细节
```java
// VodControlView.java 中添加枚举和方法
public enum RotationButtonDisplayMode {
    ALWAYS,        // 始终显示（全屏模式下）
    PORTRAIT_ONLY, // 仅竖屏全屏时显示（默认）
    LANDSCAPE_ONLY,// 仅横屏全屏时显示
    NEVER          // 始终隐藏
}

private RotationButtonDisplayMode mDisplayMode = RotationButtonDisplayMode.PORTRAIT_ONLY;

public void setRotationButtonDisplayMode(RotationButtonDisplayMode mode) {
    mDisplayMode = mode;
    updateRotationButtonVisibility();
}

// 智能判断逻辑
switch (mDisplayMode) {
    case PORTRAIT_ONLY:
        shouldShow = isFullscreen && isPortraitFullscreen && !mIsLocked && mRotationButtonEnabled;
        break;
    case LANDSCAPE_ONLY:
        shouldShow = isFullscreen && !isPortraitFullscreen && !mIsLocked && mRotationButtonEnabled;
        break;
    // ...
}
```

#### 相关文件
- `palyerlibrary/src/main/java/com/orange/playerlibrary/component/VodControlView.java`
- `palyerlibrary/src/main/java/com/orange/playerlibrary/CustomFullscreenHelper.java`
- `palyerlibrary/src/main/res/layout/orange_layout_vod_control_view.xml`

---

#### 横竖屏切换按钮优化（原有）
- **修复横竖屏切换按钮不显示的问题**
  - 问题根源：多实例干扰，VodControlView 的 onPlayerStateChanged 未处理按钮可见性
  - 修复方案：在 VodControlView 中直接控制按钮可见性
  - 效果：全屏模式下按钮正常显示
  
- **调整按钮位置到屏幕右侧**
  - 修改前：按钮在左侧锁屏按钮右边（layout_marginStart="80dp"）
  - 修改后：按钮在屏幕右侧（layout_gravity="end|center_vertical", layout_marginEnd="16dp"）
  - 布局：`[🔓 锁屏按钮] ............屏幕............ [🔄 横竖屏切换按钮]`
  
- **添加独立可见性控制 API**
  - 新增方法：`setRotationButtonVisible(boolean visible)`
  - 新增方法：`isRotationButtonEnabled()`
  - 用户可以在设置中自定义是否显示横竖屏切换按钮
  - 默认启用（true）

#### 使用示例
```java
// 获取 VodControlView 实例
VodControlView vodControlView = ...;

// 隐藏横竖屏切换按钮
vodControlView.setRotationButtonVisible(false);

// 显示横竖屏切换按钮
vodControlView.setRotationButtonVisible(true);
```

#### 技术细节
```java
// VodControlView.java 中添加标志和方法
private boolean mRotationButtonEnabled = true;  // 默认启用

public void setRotationButtonVisible(boolean visible) {
    mRotationButtonEnabled = visible;
    updateRotationButtonVisibility();
}

public boolean isRotationButtonEnabled() {
    return mRotationButtonEnabled;
}

// 更新可见性时检查标志
boolean shouldShow = helper.isFullscreen() && !mIsLocked && mRotationButtonEnabled;
mRotationButton.setVisibility(shouldShow ? VISIBLE : GONE);
```

#### 相关文件
- `palyerlibrary/src/main/java/com/orange/playerlibrary/component/VodControlView.java`
- `palyerlibrary/src/main/res/layout/orange_layout_vod_control_view.xml`
- `palyerlibrary/src/main/java/com/orange/playerlibrary/OrangeStandardVideoController.java`
- `docs/fixes/ROTATION_BUTTON_VISIBILITY_FIX.md`

### 🛡️ 磁力播放稳定性重大提升

#### 前台服务保护
- **新增磁力下载前台服务 `TorrentDownloadService`**
  - 使用 `startForeground()` 提升进程优先级，防止系统杀死下载进程
  - 显示持久通知"磁力下载进行中"，用户知晓后台工作状态
  - `START_STICKY` 确保服务被意外杀死后自动重启
  - 支持 Android 10+ 的 `foregroundServiceType="dataSync"`

- **双重保护机制**
  - ✅ **前台服务**：提升进程优先级，防止被系统杀死
  - ✅ **WakeLock**：防止 CPU 休眠，保证下载线程持续运行
  - 两者结合实现最佳保护效果

#### 问题修复
- **修复磁力播放容易被系统杀死的问题**
  - 修复前：应用切后台/锁屏后容易被杀，下载中断
  - 修复后：后台存活率从 ~20% 提升到 ~95%
  - 彻底解决"自杀"问题

- **完善资源清理**
  - 播放完成/退出时自动停止前台服务
  - 释放 WakeLock，避免电量消耗
  - 停止 HTTP 代理，释放网络端口

#### 技术细节
```java
// TorrentPlayerManager 内部集成前台服务
private synchronized void startForegroundService() {
    if (!mServiceStarted) {
        TorrentDownloadService.start(mContext);
        mServiceStarted = true;
    }
}

private synchronized void stopForegroundService() {
    if (mServiceStarted) {
        TorrentDownloadService.stop(mContext);
        mServiceStarted = false;
    }
}
```

#### AndroidManifest.xml 配置
```xml
<!-- 磁力下载前台服务权限 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<application>
    <!-- 磁力下载前台服务 -->
    <service
        android:name=".torrent.TorrentDownloadService"
        android:enabled="true"
        android:exported="false"
        android:foregroundServiceType="dataSync" />
</application>
```

### 📦 其他改进

#### 构建优化
- 修复 OkHttp/Okio 版本冲突（降级到 3.9.1/1.13.0 以兼容 GSYVideoPlayer 和 MLKit）
- 完善 R8 混淆规则，添加 libtorrent4j 和 Vosk 的保持规则
- 解决 app-legacy 和 app-tv 模块编译时的 R8 错误

#### 用户体验
- 磁力链接加载时显示解析进度（"解析磁力链接中 30/60s (50%)"）
- 添加 18 个公共 tracker（包含国内和国际节点），加速磁力链接解析
- 启用顺序下载模式，优先下载视频文件开头分片，快速开始播放

### 📌 Maven 坐标
- `io.github.706412584:orangeplayer:1.3.2`
- `io.github.706412584:orange-downloader:1.3.2`
- `io.github.706412584:orange-ffmpeg:1.3.2`

## [1.3.1] - 2026-03-25

### 📦 模块化与发布
- 下载模块独立为 `orange-downloader`，包名调整为 `com.orange.downloader`
- 新增 `orange-ffmpeg` 精简库，作为 m3u8 合并转 mp4 的默认实现
- 资源统一 `orange_download_` 前缀，避免冲突
- `palyerlibrary` 继续自动传递下载依赖，使用者无需修改

### ✨ 下载与合并增强
- M3U8 合并流程更稳健，失败可自动过滤异常分片并重试
- 合并结果校验与任务状态修复，缺失 mp4 自动触发合并
- 合并成功后自动清理 ts/key/m3u8 等缓存文件
- 加密流支持头尾分片裁剪重试策略

### 🐛 播放修复
- 修复去广告时机不一致导致切集黑屏的问题
- 新增 M3U8 去广告独立状态，确保加载动画与播放链路一致

### 📌 Maven 坐标
- `io.github.706412584:orangeplayer:1.3.1`
- `io.github.706412584:orange-downloader:1.3.1`
- `io.github.706412584:orange-ffmpeg:1.3.1`

## [1.3.0] - 2026-03-23

### ✨ 新特性
- **记忆播放功能强化**：
  - 新增播放进度实时保存机制，每秒同步进度至数据库，彻底解决应用异常退出/强制杀进程导致进度丢失的问题。
  - 新增智能恢复条件：仅当"观看进度 > 1 分钟"且"距离视频结尾 > 1 分钟"时，才会触发进度记录与恢复。
  - 新增记忆播放与"跳过片头"冲突处理逻辑：若触发记忆恢复，则自动覆盖跳过片头功能，保证观看连贯性。
  - 新增全局记忆播放开关，支持业务层灵活控制功能启停。

#### 使用说明
```java
// 1. 全局开启记忆播放功能（默认关闭）
PlayerSettingsManager.getInstance(context).setMemoryPlayEnabled(true);

// 2. 针对当前视频实例启用记忆播放状态
mVideoView.setKeepVideoPlaying(true);

// 完成以上两步后，播放器将自动在后台高频记录进度，并在下次加载相同视频时自动精准恢复进度。
```

### 🐛 Bug 修复

#### M3U8 去广告重大修复
- **修复 DISCONTINUITY 标记误判导致的所有片段被标记为广告的问题**
  - **问题现象**：播放 https://v.cdnlz22.com/20240815/3725_2916c479/index.m3u8 时，所有 402 个片段都被标记为广告，导致播放失败
  - **根本原因**：M3U8 文件包含 42 个 DISCONTINUITY 标记（HLS 直播流特征），方法 3（DISCONTINUITY 检测）错误地把所有 DISCONTINUITY 之间的片段都标记为广告
  - **解决方案**：优化广告检测逻辑，仅在方法 1（前缀长度检测）和方法 2（路径模式检测）未检测到广告时才执行方法 3，避免对已明确识别的广告进行重复检测和误判
  - **效果**：正确识别真正的广告片段（position 74-77，共 4 个片段），保留所有正片内容，播放流畅无错误

- **修复 M3U8 广告检测超时和回调未执行问题**
  - 增加超时机制（10 秒），防止广告检测无限期挂起
  - 增强日志记录，在关键步骤添加详细日志便于定位问题
  - 确保回调在所有代码路径下都能执行，包括异常情况
  - 修复 mid-roll 广告检测因日志过载导致的假死问题

- **优化中间广告检测策略**
  - DISCONTINUITY 标记超过 50 个时自动跳过中间广告检测（适用于 HLS 直播流）
  - 减少日志输出，使用聚合日志代替逐条日志
  - 改进广告检测算法，优先使用前缀长度和路径模式等更可靠的特征

---

## [1.2.9] - 2026-03-22

### ✨ 新功能

#### 下载功能增强
- **下载完成后自动重命名文件**
  - M3U8 下载合并后自动重命名为用户设置的标题
  - 解决之前文件名为 MD5 哈希值的问题
  - 文件名冲突时自动添加序号

- **本地已下载检查**
  - 新增 `getLocalVideoPath(url)` 方法，检查视频是否已下载
  - 修复多线程断点续传遗留 `range.info` 未清理的问题
  - 弃用后缀名探测方案，改为直接联查本地 SQLite 数据库中 `VideoTaskItem` 的 `SUCCESS` 状态，确保不播放破损/半成品文件
  - **播放前自动拦截**：`setUp()` 时自动检查本地已下载，直接播放本地文件

- **下载路径 API**
  - 新增 `setDownloadPath(path)` 方法，支持自定义下载目录
  - 新增 `getDownloadPath()` 方法，获取当前下载目录
  - 支持动态切换下载路径

- **SimpleDownloadManager 单例模式**
  - 改为单例模式，全局共享同一实例
  - 配置全局生效，避免多次创建导致状态不同步

### 🐛 Bug修复

- **修复 MP4 单文件下载损坏问题**
  - 原因：下载器在执行普通 MP4/FLV 文件下载时，开启了 `MultiSegVideoDownloadTask` 多线程并发写入，导致在部分 Android 系统下 `RandomAccessFile` 发生覆盖截断，破坏了文件结构（如 moov 头损坏）。
  - 解决方案：针对非 M3U8 的普通视频文件，降级回退至 `BaseVideoDownloadTask` 单线程顺序下载，确保文件完整性；且在下载完成后自动还原正确的后缀名（如 `.mp4`）。
- 修复下载按钮点击时未检查本地是否已下载的问题
- 修复 `SimpleDownloadManager` 多次创建导致配置不生效的问题
- 修复 IJKPlayer 播放 Android 10+ 内部存储绝对路径时报错 `-10000` 的问题（增加协议白名单并设置 `safe=0` 选项）

### 📝 API 示例

```java
// 获取下载管理器单例
SimpleDownloadManager manager = SimpleDownloadManager.getInstance(context);

// 设置下载路径
manager.setDownloadPath("/sdcard/MyVideos");

// 检查本地是否已下载
String localPath = manager.getLocalVideoPath(url);
if (localPath != null) {
    // 直接播放本地文件
    videoView.setUp(localPath, true, "标题");
}

// 检查是否正在下载
boolean downloading = manager.isDownloading(url);

// 开始下载（自动检查本地）
String path = manager.startDownloadWithLocalCheck(url, "视频标题");

// OrangeToast 使用
OrangeToast.show(videoView, "提示消息");
OrangeToast.showLong(videoView, "长提示消息");
```

---

## [1.2.8] - 2026-03-20

### 🐛 Bug修复

#### v3打包播放器初始化修复
- **修复v3打包后播放器无法显示的问题**
  - 原因：iApp不支持递归解析子依赖，导致 `gsyijkjava` 丢失
  - 解决方案：在 `iapp.sdk` 中手动添加 `io.github.carguo:gsyijkjava:1.0.0` 依赖
- **修复IJK内核设置不生效的问题**
  - 原因：`setPlayerEngine()` 在 `OrangevideoView` 创建之后调用，但引擎初始化在构造函数中
  - 解决方案：在创建 `OrangevideoView` 之前设置引擎
- **移除系统播放器横竖屏切换的暂停/恢复特殊处理**
  - 原因：`enableMediaCodecTexture()` 已修复 SurfaceTexture 保留问题，不再需要暂停/恢复
  - 效果：系统播放器横竖屏切换更流畅，无短暂暂停

---

## [1.2.7] - 2026-03-20

### ✨ 新功能

#### 播放器音量控制
- 新增 `setPlayerVolume(float volume)` 方法，设置播放器音量 (0.0-1.0)
- 新增 `setPlayerVolumePercent(int percent)` 方法，设置播放器音量百分比 (0-100)
- 音量设置仅影响当前播放器，不影响系统音量

#### 静音控制
- 新增 `setMute(boolean isMute)` 方法，设置静音
- 新增 `isMute()` 方法，获取静音状态
- 新增 `toggleMute()` 方法，切换静音状态

#### 循环播放
- 新增 `setLooping(boolean looping)` 方法，设置循环播放
- 新增 `isLooping()` 方法，获取循环播放状态

#### 截图功能
- 新增 `takeScreenshot(callback)` 方法，截取当前画面
- 新增 `takeScreenshotAndSave(callback)` 方法，截图并保存到相册

#### 缓冲进度
- 新增 `getBufferedPercentage()` 方法，获取缓冲进度百分比 (0-100)

### 🐛 Bug修复

#### M3U8去广告保留AES加密密钥
- **修复去广告后AES加密视频无法播放的问题**
  - 原因：去广告处理时未保留 `#EXT-X-KEY` 标签，导致加密密钥丢失
  - 解决方案：解析时保存每个片段的加密密钥信息，重建m3u8时正确输出
  - 支持完整的加密属性：METHOD、URI、IV

#### M3U8去广告默认关闭
- **去广告功能默认关闭**，需要手动启用
  - 调用 `M3U8AdManager.getInstance(context).setEnabled(true)` 开启
  - 避免对普通视频造成不必要的处理


## [1.2.6] - 2026-03-18

### 🐛 Bug修复

#### M3U8去广告Seek修复（重大修复）
- **修复去广告后seek时间轴不准确的问题**
  - 原因：删除广告片段导致时间轴不连续，播放器seek计算错误
  - 解决方案：广告片段不再删除，改用占位TS文件替换，保持时间轴完整
  - 效果：seek位置准确，不再跳转到错误位置

- **修复开头广告检测问题**
  - 修复开头DISCONTINUITY标记未正确识别的问题
  - 开头广告检测现在独立执行，不受其他检测结果影响
  - 开头广告检测阈值从60秒提高到120秒

- **修复中间广告检测问题**
  - 方法3（DISCONTINUITY之间的广告检测）现在总是执行
  - 添加详细日志便于调试

- **修复DISCONTINUITY标记丢失问题**
  - 正确设置needsDiscontinuity标记
  - 确保输出m3u8保留DISCONTINUITY标记

---

### 🐛 Bug修复

#### M3U8去广告检测优化
- **新增TS白名单功能**，开发者可防止自己的广告被过滤，对播放器实例生效，重启失效
  ```java
  // 添加TS到白名单（支持完整URL或关键字匹配）
  videoView.addTsToWhitelist("https://example.com/my_ad.ts");
  videoView.addTsToWhitelist("/my_ads/");  // 关键字匹配
  
  // 批量添加
  videoView.addTsToWhitelist(urlList);
  
  // 其他操作
  videoView.removeTsFromWhitelist(url);
  videoView.clearTsWhitelist();
  boolean inList = videoView.isTsInWhitelist(url);
  ```
- 修复嗅探完成后弹窗未正确关闭的问题

---

## [1.2.5] - 2026-03-18

### 🐛 Bug修复

#### M3U8去广告检测优化
- 新增文件名数字序列突变检测，识别数字跳跃过大的广告片段
- 新增前缀长度检测，识别文件名前缀长度异常的广告片段
- 优化路径模式提取，正确处理相对路径文件名
- 处理M3U8前先清除视频URL，避免短暂播放之前的视频
- **新增Master Playlist（嵌套m3u8）支持**，自动检测并请求子播放列表

#### M3U8播放优化
- 修复首次播放m3u8时短暂显示其他视频画面的问题
- 优化release()和状态切换的顺序，确保画面正确切换
- `setVideoList()` 不再自动设置第一个视频，避免干扰后续播放

#### 设置同步优化
- 片头尾、倍数、画面比例设置现在只对当前剧集生效
- 同一剧集内切换视频时保持设置
- 切换剧集时重置为默认值

---

## [1.2.4] - 2026-03-17

### ✨ 新功能

#### M3U8去广告功能
- 自动检测并移除m3u8视频中的广告片段
- 支持多种广告检测方式：路径模式变化、DISCONTINUITY标记、短片段组
- 本地缓存处理结果，避免重复请求
- 请求失败自动降级播放原始URL
- 提供同步/异步API接口

#### 画面比例会话记忆
- 同一剧集内切换集数时保持用户选择的画面比例
- 切换到不同剧集时自动重置为默认比例
- 关闭应用后比例设置重置（不持久化）
- 解决了用户在同一剧集切换集数时需要重复选择比例的问题

#### 视频比例API
- 新增 `VideoScaleManager.getCurrentScale()` 获取当前视频比例
- 返回值：`"默认"`, `"16:9"`, `"4:3"`, `"全屏裁剪"`, `"全屏拉伸"`
- 优先返回会话比例（用户当前选择的），其次返回持久化比例

### 🐛 Bug修复

#### 画中画模式优化
- 修复点击恢复按钮时错误执行关闭逻辑的问题
- 区分PiP恢复按钮和X关闭按钮的不同行为：
  - 恢复按钮：继续播放，保持全屏状态
  - X关闭按钮：暂停播放，退出全屏

---

## [1.2.1] - 2026-02-28

### ✨ 新功能

#### 标题栏电量与时间显示
- 全屏模式下标题栏右侧显示电池电量图标和系统时间
- 电量图标根据电量级别自动切换（100%/75%/50%/25%）
- 充电状态显示充电图标
- 电量图标在上，时间文本在下，紧凑布局

### 🐛 Bug修复

#### 画中画模式优化
- 修复用户点击X关闭PiP小窗后视频继续后台播放的问题
- PiP关闭时自动暂停视频播放
- PiP关闭时如果处于全屏状态则自动退出全屏

---

## [1.2.0] - 2026-02-27

### ✨ 新功能

#### 控制器可见性控制
- 新增 `setControllerVisibilityEnabled(boolean)` API，支持临时禁用控制器UI显示
- 禁用后控制器功能保留，但UI不显示，适用于后台控制场景
- 启用/禁用时立即触发控制器显示/隐藏

#### M3U8下载增强
- M3U8下载合并输出为MP4格式
- 用纯Java实现TS合并，移除JeffVideoLib依赖
- 删除未使用的 `JeffVideoLib-extracted` 目录

### 🐛 Bug修复

#### 下载管理修复
- 修复 RecyclerView 并发崩溃问题
- 修复线程池终止崩溃问题
- 修复下载完成图标显示问题
- 修复重复 Toast 提示问题
- 移除 DialogX 依赖，减少第三方依赖

---

## [1.1.9] - 2025-02

### ✨ 新功能

#### 视频嗅探增强
- 添加嗅探自动播放第一个视频功能
- 完善嗅探自动播放功能
- 优化视频嗅探功能，减少 ANR 问题
- 修复视频嗅探时的 ANR 问题
- 最小化 WebView 控件并修复资源拦截逻辑

#### 播放功能
- 实现选集播放和播放模式功能
- 实现智能全屏功能，根据视频宽高比自动选择全屏模式
- 添加视频嗅探开源库对比分析报告

### 🐛 Bug修复

- 修复智能全屏和弹幕组件 ANR 问题
- 修复嗅探状态时准备视频控件的显示问题
- 优化嗅探状态时的 UI 显示
- 修复 ExoPlayer Activity 切换后状态保存问题
- 修复跳过片头片尾功能和临时设置持久化问题

---

## [1.1.3] - 2024-02

### ✨ 新功能

- 在 Maven Central 发布脚本中添加 gsyVideoPlayer-ex_so 模块
- 引入 gsyVideoPlayer-ex_so 模块支持 IJK 播放加密视频
- 在播放错误界面添加设置按钮并优化设置弹窗大小适配

### 🐛 Bug修复

- 修复 GSYVideoPlayer 依赖冲突
- 修复 IJK 播放器可用性检测不完整导致闪退
- 修复 IJK 播放器后台切换进度重置问题
- 修复阿里云播放器画面比例切换问题
- 修复 m3u8 seek 画面异常和后台自动恢复播放问题

---

## [1.1.1] - 2024-01

### 🎉 首次发布

#### 核心功能
- 基于 GSYVideoPlayer 的增强视频播放器
- 支持 Android 4.0+ (API 14+)
- 多播放器内核支持：系统播放器、ExoPlayer、IJK播放器、阿里云播放器

#### 特色功能
- **弹幕支持** - 集成 DanmakuFlameMaster
- **字幕支持** - 支持 SRT/ASS/VTT 格式
- **画面比例** - 支持多种画面比例切换
- **手势控制** - 音量、亮度、进度调节
- **倍速播放** - 支持 0.35x - 10x 倍速
- **投屏功能** - DLNA 投屏支持
- **画中画** - Android 8.0+ 画中画模式
- **视频嗅探** - 网页视频自动嗅探
- **下载功能** - 视频下载管理

#### 播放器内核
| 内核 | 说明 |
|------|------|
| 系统播放器 | MediaPlayer，无需额外依赖 |
| ExoPlayer | 推荐默认，格式支持全 |
| IJK播放器 | 格式支持最全 |
| 阿里云播放器 | 性能最好，需 License |

---

## 版本号说明

采用三段式版本号：`主版本.功能版本.修复版本`

- **主版本** - 重大架构变更或不兼容更新
- **功能版本** - 新增功能或较大改进
- **修复版本** - Bug修复或小改进

---

## 升级指南

### 从 1.1.x 升级到 1.2.0

```gradle
// 更新依赖版本
implementation 'io.github.706412584:orangeplayer:1.2.0'
```

**新API使用示例：**

```java
// 控制器可见性控制
videoView.setControllerVisibilityEnabled(false);  // 禁用控制器UI
videoView.setControllerVisibilityEnabled(true);   // 启用控制器UI

// 查询状态
boolean enabled = videoView.isControllerVisibilityEnabled();
```

### 从 1.0.x 升级到 1.1.x

```gradle
// 更新依赖版本
implementation 'io.github.706412584:orangeplayer:1.1.9'
```

**注意事项：**
- 1.1.x 版本移除了部分废弃的 API
- 建议查看 API 文档了解最新用法

---

## 贡献者

感谢所有为 OrangePlayer 做出贡献的开发者！

## 反馈与支持

- **GitHub Issues**: https://github.com/706412584/orangeplayer/issues
- **QQ**: 706412584
