# 全屏重构方案：从 reparent 到零搬运

> 目标：根治「全屏/竖屏切换出现多实例」。
> 手段：借鉴 animeko 的全屏状态模型（单一真值 + 幂等入口 + 零 reparent），用 Java/XML 实现。
> 不变：现有组件式 UI（VodControlView 等）与其承载的全部业务逻辑。

---

## 0. 背景与结论

用户诉求：把 `E:\app\animeko` 的播放器迁移过来，并解决当前组件式 UI「全屏/竖屏切换容易多实例」的问题。

调研结论（详见 §1、§2）：

1. **animeko 播放器整体迁移不可行**，卡在两处硬门槛：
   - **许可证**：animeko 是 AGPL-3.0（逐文件头声明），我们是 Apache-2.0。并入即整体转 AGPL。
   - **技术栈**：animeko 播放器 UI 是 Compose Multiplatform（`@Composable`），底层是 MediaMP；我们是 Java + XML，项目未启用 Kotlin/Compose 编译插件。Java 无法直接调用 `@Composable`。
2. **「多实例」的真正根因不是组件式 UI**，而是两套全屏机制并存：
   - 机制 A（自研 reparent，活跃）：`CustomFullscreenHelper` 把整个 `OrangevideoView` 在容器与 DecorView 之间反复搬运。
   - 机制 B（GSY 克隆，死代码但脚手架仍在）：`startWindowFullscreen` 会 `new` 第二个播放器实例。
3. **animeko 值得借鉴的是架构，不是代码**：全屏是「单一真值 + 幂等目标态请求 + 就地改尺寸」，可完整用 Java/XML 复刻。

用户决策：

- 许可证：可接受转 AGPL-3.0（**但本方案按「不引入 AGPL 代码」设计，见 §1.4**）
- 旧 UI：**保留为默认**，只重写全屏逻辑
- P2：**变体 1 —— 零 reparent**

---

## 1. animeko 调研结论

### 1.1 播放器模块结构

`E:\app\animeko\app\shared\video-player`（Kotlin，10,278 行）：

| source set | 行数 | 内容 |
|---|---|---|
| `commonMain` | 6,646 | `VideoScaffold` / `VideoPlayer`(expect) / 手势 / 进度条 / 顶栏 |
| `androidMain` | 1,669 | `VideoPlayer.android`(actual) / 手势 / 进度预览 / 画质增强 |
| `desktopMain` | 450 | mpv 后端 |
| `iosMain` / `appleMain` / `skikoMain` | 212 | 其他平台 |

依赖（`app/shared/video-player/build.gradle.kts`）：

```
api(projects.app.shared.appPlatform)
api(projects.app.shared.uiFoundation)      // 主题/动画/layout 扩展，VideoScaffold 强依赖
api(projects.app.shared.videoPlayer.videoPlayerApi)
api(libs.mediamp.api)                      // org.openani.mediamp:mediamp-api:0.5.0
api(libs.mediamp.exoplayer)                // Android 后端
api(libs.libass.media)                     // io.github.peerless2012:ass-media:0.5.0-beta01
implementation(libs.androidx.media3.*)     // 1.9.0
```

### 1.2 渲染与全屏实现

- `VideoPlayer` 是 `expect/actual`（`commonMain/kotlin/ui/VideoPlayer.kt:28`），Android 侧落到 `ExoPlayerMediampPlayerSurface`（`androidMain/kotlin/ui/VideoPlayer.android.kt:41`）。
- 全屏**不是** View 搬家。`VideoScaffold`（`commonMain/kotlin/ui/VideoScaffold.kt:107`）只做：

  ```kotlin
  BoxWithConstraints(
      modifier.then(if (expanded) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()),
  )
  ```

  `expanded` 是宿主传入的单一布尔真值。全屏 = 同一个 composition node 就地改尺寸，无 reparent、无第二个实例。

- 全屏状态抽象 `PlayerFullscreenState`（`commonMain/kotlin/ui/PlayerFullscreenState.kt`）：

  ```kotlin
  interface PlayerFullscreenState {
      val isFullscreen: Boolean
      fun request(fullscreen: Boolean)   // 目标态 + 幂等
  }
  fun PlayerFullscreenState.toggle() { request(!isFullscreen) }
  ```

  设计要点（原文注释）：
  - 「当前是否全屏」与「切换全屏」**必须绑在同一个对象上**，拆成 `isFullscreen: Boolean` + 回调两个参数会出现「图标画的是退出、点下去却是进入」的状态错位。
  - 一律用**目标态** `request` 而非 `toggle`：手势本身是意图式的（上滑进入、下滑退出）。
  - 实现必须**幂等**：已处于目标态时不产生副作用。

- 宿主接线：`app/shared/src/commonMain/kotlin/ui/subject/episode/EpisodePage.kt:980` 的 `rememberEpisodeFullscreenState(vm)` 把 ViewModel 里的全屏状态接到 `PlayerFullscreenState`。

### 1.3 可借鉴清单（思想，非代码）

| animeko 组件 | 文件 | 可复刻到 |
|---|---|---|
| `PlayerFullscreenState` 单一真值 + 幂等 | `PlayerFullscreenState.kt` | `CustomFullscreenHelper` 重构（P1） |
| 全屏就地改尺寸（零 reparent） | `VideoScaffold.kt:107` | 全屏容器契约（P2） |
| 手势 seek 状态机 | `ui/gesture/SwipeSeekerState.kt` | `GestureView` 增强 |
| 长按倍速 | `ui/gesture/FastSkipState.kt` | `GestureView` 增强 |
| 进度条帧预览 | `ui/progress/MediaProgressFramePreview.kt` | `VodControlView` 进度条 |
| 竖屏全屏切换按钮 | `ui/gesture/SwipeToFullscreen.kt` | 已有（`rotation_button`），对齐语义 |

### 1.4 许可证边界（重要）

animeko 为 AGPL-3.0，本项目为 Apache-2.0。

- **本方案不复制 animeko 源码**，只借鉴其**架构思想**（状态机语义、零 reparent 策略、幂等约定）。思想与接口语义不受版权保护，不存在衍生作品问题。
- 用户已同意「必要时可转 AGPL-3.0」，但按本方案推进**无需触发该选项**。
- 若后续要逐行复刻 animeko 的 UI 组件（§1.3 后半部分），需单独评估：那时要么转 AGPL，要么独立重写。**本方案的 P0–P3 不涉及。**

---

## 2. 「多实例」根因（已定位）

### 2.1 两套全屏机制并存

**机制 A —— 自研 reparent（活跃路径）**

`CustomFullscreenHelper.startFullScreen()`（`palyerlibrary/.../CustomFullscreenHelper.java:193`）核心：

```java
// CustomFullscreenHelper.java:237-273
mOriginalParent = (ViewGroup) mVideoView.getParent();
mOriginalIndex = mOriginalParent.indexOfChild(mVideoView);
mOriginalLayoutParams = mVideoView.getLayoutParams();
mOriginalParent.removeView(mVideoView);          // :248 从原容器摘出
decorView.addView(mFullscreenBackground, bgParams); // :258
decorView.addView(mVideoView, fullParams);       // :265 塞进 DecorView
activity.setRequestedOrientation(mCurrentOrientation); // :273 再转屏
```

竖屏路径同构：`startPortraitFullScreen()`（`:599-626`）。

退出 `stopFullScreen()`（`:367`）反向还原。竖屏全屏 `startPortraitFullScreen()`（`:573`）/ `stopPortraitFullScreen()`（`:656`）是同一套逻辑的复制品。

> 补充：控制器组件（`VodControlView` / `TitleView` / `GestureView`）是 `OrangevideoView` 的**子 View**
> （`OrangevideoView.java:1070` `addView(mTitleView, ...)`、`:1078` `addView(mVodControlView, ...)`、
> `:4202` `addView(mGestureView, lp)`）。所以搬家时组件跟着壳一起走，**机制 A 本身不产生第二个实例**。

**机制 B —— GSY 克隆（死代码，脚手架仍在）**

`OrangevideoView.startWindowFullscreen()`（`OrangevideoView.java:4267`）调用 `super.startWindowFullscreen(...)`，GSY 会 `new` 一个**全新播放器实例**放进 DecorView，并标记 `mIfCurrentIsFullscreen = true`（`:4289`）。这才是字面意义的「第二个播放器」。

### 2.2 机制 B 的调用现状

| 项 | 位置 | 状态 |
|---|---|---|
| `startWindowFullscreen` override | `OrangevideoView.java:4267` | **无活跃调用方**（仅 `GSYVideoPlayer-source/app` 官方 demo 调用） |
| `getOrangeFullWindowPlayer()` | `OrangevideoView.java:4242` | 活跃：每次进度回调查 DecorView 找「全屏播放器」 |
| `mOrangeCheckoutTask` / `checkoutState()` | `OrangevideoView.java:4263` | 活跃：每 500ms post 一次 |
| `syncFullScreenProgress()` | `ComponentStateManager.java:206` | 活跃：进度回调里同步「全屏播放器」 |
| `ErrorRecoveryManager` 两处 `fullPlayer` 分支 | `ErrorRecoveryManager.java:388` / `:444` | 活跃：状态一致性检查 |

即：**一套活跃机制（A）+ 一套死机制的活跃脚手架（B 的探测/同步逻辑）**，语义互相冲突。

### 2.3 多实例的触发链

1. **三态共用散落标志**：`mIsFullscreen` + `mIsPortraitFullscreen` 同时承载「横屏全屏/竖屏全屏/正常」，四个入口各改各的（`CustomFullscreenHelper.java:779` `toggleFullScreen` 只读 `mIsFullscreen`，无法区分横/竖）。
2. **时序竞态**：`mFullscreenTransitioning` 标志 + 一串 `postDelayed`（`CustomFullscreenHelper.java:300` / `:317` / `:344` 为横屏路径的 800/1200ms 补丁，`:478` / `:497` 退出路径，`:639` / `:708` 竖屏路径）。旋转中途的第二次切换请求撞进 `mFullscreenTransitioning` 被吞掉，或与延迟任务交错 → `mOriginalParent` / `mOriginalIndex` 记录错位。
3. **结果**：退出时回到错误的容器或索引，表现即「多了一个播放器」/ 状态错乱。

> 佐证：`docs/CHANGELOG.md:414` 已有记录 ——「问题根源：多实例干扰，VodControlView 的 onPlayerStateChanged 未处理按钮可见性」。当时是打补丁，未触根因。

---

## 3. 目标设计

### 3.1 单一真值状态机

引入 `FullscreenMode` 三态，取代两个布尔标志：

```java
public final class FullscreenMode {
    public static final int NORMAL = 0;
    public static final int LANDSCAPE_FULLSCREEN = 1;
    public static final int PORTRAIT_FULLSCREEN = 2;
}
```

`CustomFullscreenHelper` 对外只暴露：

```java
public int getMode();                    // 唯一真值
public void request(int targetMode);     // 幂等：已处于目标态则直接 return
```

- `request` 幂等（对齐 `PlayerFullscreenState.request` 的约定）。
- `isFullscreen()` = `mode != NORMAL`；`isPortraitFullscreen()` = `mode == PORTRAIT_FULLSCREEN`。保留旧 API 作为**只读派生**，兼容既有调用方。
- 手势/按钮一律调 `request(target)`；确实需要「反一下」的入口（双击、F 键）调 `toggle()`，其内部读同一个 `getMode()`，不可能与 UI 显示方向相反。

### 3.2 零 reparent（变体 1）

**核心：播放器永不搬家。全屏 = 把宿主提供的全屏容器撑满并置顶。**

宿主布局约定（新增可选契约）：

```
Activity 根布局 (FrameLayout)
├── 普通内容（LinearLayout，播放器在其中的 220dp 槽位）
└── @id/player_fullscreen_container (FrameLayout, 默认 gone, match_parent)
```

库的行为：

| 阶段 | 动作 |
|---|---|
| 进入全屏 | ① 若宿主提供容器：`removeView(mVideoView)` → `container.addView(mVideoView, MATCH_PARENT)` → `container.setVisibility(VISIBLE)` → `bringToFront()`；② 宿主未提供：回退到旧 reparent 路径（见下） |
| 退出全屏 | `container.removeView(mVideoView)` → 原始父容器按 `mOriginalIndex` 还原 → `container.setVisibility(GONE)` |
| 朝向 | 仅 `setRequestedOrientation(...)`，不改 View 树 |

> 注意：变体 1 下播放器在「普通槽位」与「全屏容器」之间**仍然移动一次**（因为两者是不同父容器）。
> 相比现状的收益是：**移出/移回是可逆且幂等的**，且**不再往 DecorView 里塞**（DecorView 是系统窗口层，塞进去会与系统栏、
> 输入法、Dialog 层级互相干扰）。若宿主愿意把播放器**直接**放进全屏容器（容器即槽位，靠 LayoutParams 切换尺寸），
> 则移动次数为 **0** —— 这是 animeko 模型的最优形态，需宿主配合调整布局（见 §5 决策点）。

**回退路径（SDK 兼容，必须保留）**

`palyerlibrary` 是给外部宿主用的库，不能强制所有宿主改布局。约定：

- 新增 `OrangevideoView.setFullscreenContainer(FrameLayout)`。
- 未设置容器时，`CustomFullscreenHelper` 走旧 reparent 路径（但已由 P1 的幂等状态机保护，不再错乱）。
- 设置了容器时，走零搬运路径。
- **demo（`app`）改用容器契约**，作为参考实现与验收载体。

### 3.3 回调驱动取代时序补丁

删除 `postDelayed(800/1200/500)` 三处补丁，改为：

- `CustomFullscreenHelper` 内部已有 `OrientationEventListener`（`:61`）。收敛为**唯一**的朝向来源。
- 全屏切换的「完成」以**配置变化回调**为准（宿主 `onConfigurationChanged` 转发，或 `ViewTreeObserver` 尺寸稳定），而非固定延时。
- `updateExoSurfaceControlSize()` / `checkMpvSurfaceDrift()` 从「延时后补一次」改为「尺寸稳定后触发一次」。

---

## 4. 分阶段改动清单

> 每阶段可独立验证。建议按 P0 → P1 → P2 → P3 顺序推进，每阶段真机验收后再进下一步。

### P0：删除 GSY 克隆残留

**前置**：确认 `startWindowFullscreen` 无活跃调用方（已确认，仅 GSY demo 调用）。

| 文件 | 改动 |
|---|---|
| `palyerlibrary/.../OrangevideoView.java:4267` | 删除 `startWindowFullscreen` override（或改为抛 `UnsupportedOperationException` 提示走 `request`） |
| `OrangevideoView.java:4242` | 删除 `getOrangeFullWindowPlayer()` |
| `OrangevideoView.java:4258-4265` | 删除 `checkoutState()` override + `mOrangeCheckoutTask` |
| `OrangevideoView.java:4232` `getFullId()` | 若无其他引用则一并删除（注意 `clearFullscreenLayout` / `orangeBackToNormal` 仍在用，见下） |
| `ComponentStateManager.java:206-235` | 删除 `syncFullScreenProgress()` 及其调用点 |
| `ErrorRecoveryManager.java:388-400` / `:444-455` | 删除两处 `getOrangeFullWindowPlayer()` 分支 |

**风险**：`clearFullscreenLayout()`（`:4385`）/ `orangeBackToNormal()`（`:4424`）是 GSY 克隆的**退出**路径，与 `getFullId()` 耦合。需确认它们也不在活跃路径（机制 A 有自己的退出逻辑）。**若确认死代码，一并删除；若有隐藏调用方，保留但加注释标记。**

**验收**：`grep -rn "getOrangeFullWindowPlayer\|startWindowFullscreen\|mOrangeCheckoutTask" palyerlibrary/` 无结果；编译通过；全屏进出正常。

### P1：状态机收敛

| 文件 | 改动 |
|---|---|
| 新增 `palyerlibrary/.../FullscreenMode.java` | 三态常量 |
| `CustomFullscreenHelper.java` | 用 `mMode` 取代 `mIsFullscreen` + `mIsPortraitFullscreen`；四入口 `startFullScreen`/`stopFullScreen`/`startPortraitFullScreen`/`stopPortraitFullScreen` 收敛为 `request(int)`；`toggleFullScreen()` 读 `getMode()` |
| `OrangeVideoController.java:427/441/458/472/499` | 转发层改为 `request(mode)`，保留旧方法名做兼容包装 |
| `OrangevideoView.java:2441/2465/2476/2485/2494/2498` | 同上；`isFullScreen()` / `isPortraitFullScreen()` 改为派生只读 |
| `mIfCurrentIsFullscreen`（`OrangevideoView.java` 多处） | 收敛到 `mMode`（P0 删掉机制 B 后，它只剩 `backFromFull` 等少量用途，逐处确认） |

**验收**：真机反复切换「横屏全屏 ↔ 竖屏全屏 ↔ 正常」，状态与 UI 图标一致；旋转中途连点全屏按钮不出现状态错乱。

### P2：零 reparent

| 文件 | 改动 |
|---|---|
| `CustomFullscreenHelper.java` | 按 §3.2 实现容器路径 + reparent 回退路径 |
| `OrangevideoView.java` | 新增 `setFullscreenContainer(FrameLayout)` / `getFullscreenContainer()` |
| `app/src/main/res/layout/activity_main.xml` | 根布局包一层 `FrameLayout`，加 `@id/player_fullscreen_container` |
| `app/src/main/java/com/orange/player/MainActivity.java` | 初始化时把容器传给 `mVideoView` |

**验收**：真机连续 20 次全屏进出，`adb shell dumpsys` 或日志确认 DecorView 下**不再出现** `OrangevideoView`；`logcat` 中 `startFullScreen` 日志的 `VideoView: @xxx` 哈希**始终不变**（同一实例）。

### P3：回调驱动

| 文件 | 改动 |
|---|---|
| `CustomFullscreenHelper.java:300/317/344/478/497/639/708` | 删除 `postDelayed` 补丁，改为尺寸稳定回调 |
| `OrangevideoView.java` | `updateExoSurfaceControlSize` / `checkMpvSurfaceDrift` 触发点改到回调 |

**验收**：旋转过程中不出现画面只显示一小块、不出现黑屏；`logcat` 无 SurfaceTexture 漂移告警。

---

## 5. 影响面与不变项

### 不受影响（本方案一行不动）

- 组件式 UI：`VodControlView` / `TitleView` / `GestureView` / `LiveControlView` / `PrepareView` / `CompleteView` / `ErrorView` / `SniffingView`
- 业务逻辑：`VideoSniffing`(57KB) / `M3U8AdRemover`(74KB) / ASR 字幕 / 下载 / 投屏 / TV / 弹幕 / 历史
- 播放内核：GSY / ijk / ExoPlayer / 阿里 / mpv 的切换
- 控制器组件契约：`IControlComponent` / `ControlWrapper` 接口不变

### 受影响

- 全屏/朝向的实现（`CustomFullscreenHelper`）
- 全屏状态的对外 API（新增 `request(mode)`，旧方法保留兼容包装）
- demo 宿主布局（`activity_main.xml`）
- 若外部宿主想用零搬运路径，需实现容器契约（**可选**，不实现则走回退）

---

## 6. 验证计划

| 项 | 手段 |
|---|---|
| 编译 | `./gradlew :palyerlibrary:assembleDebug :app:assembleDebug` |
| 单实例 | 真机日志断言 `VideoView` 哈希在进出全屏前后一致 |
| 三态正确 | 真机手动：正常 ↔ 横屏全屏 ↔ 竖屏全屏，各方向切换 + 旋转中途连点 |
| 无残留 | `grep` 断言机制 B 符号清零 |
| 回归 | 嗅探 / 去广告 / 弹幕 / 字幕 / 投屏 / 下载 / TV 冒烟 |

真机验证方法见项目 memory `project_real_device_testing.md`（两台设备坐标、`TEST_CMD` 广播入口、面板 BACK 穿透、截图判读）。

---

## 7. 决策点（待确认）

1. **最优形态 vs 兼容形态**：是否让 demo 把播放器**直接**放进全屏容器（容器即槽位，移动次数 = 0，完全对齐 animeko）？还是保留「220dp 槽位 + 独立全屏容器」（移动一次但可逆）？
   - 前者需 demo 布局结构调整更大，但收益最大。
   - 后者对宿主侵入小，库层改动可控。
2. **P0 的 `clearFullscreenLayout` / `orangeBackToNormal`**：确认是否死代码。若不是，需保留并适配到新状态机。
3. **是否保留旧全屏 API 的兼容包装**：`startFullScreen()` / `stopFullScreen()` 等旧方法名是否继续暴露给外部宿主？

---

## 8. 实施记录（P0–P3 + U3）

> 本节记录实际落地情况，与上文方案对照。

### 8.1 已落地

| 阶段 | 内容 | 文件 |
|---|---|---|
| P0 | 删除机制 B 残留；`startWindowFullscreen` 改为委托 `startFullScreen()`（返回 `this`，不再克隆） | `OrangevideoView.java`、`ComponentStateManager.java`、`ErrorRecoveryManager.java` |
| P1 | 新增 `FullscreenMode`；`CustomFullscreenHelper` 重写为单一真值 `mMode` + 幂等 `request(int)` | `FullscreenMode.java`（新）、`CustomFullscreenHelper.java` |
| P2 | 容器契约：`setFullscreenContainer` + `moveIntoFullscreen`/`moveOutOfFullscreen`，未提供容器时回退 DecorView | `OrangevideoView.java`、`CustomFullscreenHelper.java` |
| P3 | `finalizeFullscreenTransition()` 回调驱动（`onConfigurationChanged` 转发）+ 兜底定时器 | `CustomFullscreenHelper.java`、`MainActivity.java` |
| demo | 根布局改 FrameLayout + `@id/player_fullscreen_container` | `activity_main.xml`、`MainActivity.java` |
| U3 | `FastSkipState` 接入长按倍速，取代布尔 `mIsLongPressing` | `VideoEventManager.java`、`ui/FastSkipState.java` |

### 8.2 UI 复刻的接入边界（重要）

复刻的三个纯逻辑类中，**只有 U3 已接入**：

| 类 | 状态 | 原因 |
|---|---|---|
| `ui/FastSkipState` (U3) | **已接入** `VideoEventManager` / `VodControlView` / `OrangevideoView` 三处长按 | 对现有长按逻辑是严格改进（同功能、更稳的并发语义），无行为变更风险 |
| `ui/PlayerControllerState` (U1) | **已接入** `OrangeStandardVideoController`（见 §10） | 取代散落的 `mIsShowing` 布尔与 `hide()` 里的拖动特判 |
| `ui/SwipeSeekerState` (U2) | **未接入** | 接它要替换 GSY 的 seek 数学（`OrangevideoView.java:4099`）。animeko 模型是「整屏宽度 = 固定 97 秒」（为番剧 op/ed 调），GSY 是「按视频时长成比例」，两者语义不同；短片用 animeko 模型会一划到底。属所有内核共用热路径，需真机对比后再定 |

U1/U2 保留为可单测的独立类，等待决策。

### 8.3 测试发现（已修复）

`FastSkipState.stopSkipping(0)` 在**从未长按**时也会匹配初始 ticket（`mTicket` 初值 0），触发 `onStop()`。
在 U3 集成语境下，这意味着**每次普通点击播放按钮都会把倍速重置为 1.0x**。

animeko 原实现同样只比较 ticket，靠调用方纪律（`onEnd` 只在 `onStart` 之后调用）规避。
本实现已加固：`stopSkipping` 增加「当前正在快进」前置条件（`FastSkipState.java:76`）。

### 8.4 验证结果

| 项 | 结果 |
|---|---|
| `:app:assembleDebug` | 通过（APK 27.8MB） |
| `:palyerlibrary:testDebugUnitTest` 全量 | **408 通过 / 0 失败** |
| 新增单测 | `PlayerControllerStateTest` 14、`SwipeSeekerStateTest` 14、`FastSkipStateTest` 8 |
| `:app-tv` / `:orange-player-session` 编译 | 通过 |
| 被删符号全仓 grep | 无残留引用 |

---

## 9. 独立审核发现与修复

对 P0–P3 做了一轮对抗性审核（静态路径推理 + 全仓 grep），发现的**真实缺陷**及修复：

### 9.1 Critical（已修）

**C1 — 跨全屏模式切换破坏还原目标，播放器永久消失**
`moveIntoFullscreen` 无条件重写 `mOriginalParent`。从横屏全屏直接切竖屏全屏时（`startWindowFullscreen` 现委托 `startFullScreen()`，或 demo 的「竖屏全屏」按钮），此时播放器已在容器里，`mOriginalParent` 被覆盖成容器本身 → 退出时把播放器放回**已 GONE 的容器** → 画面消失且无法恢复。
**修复**：新增 `saveOriginalPosition()`，只在 `NORMAL → 全屏` 时保存；`moveIntoFullscreen` 不再覆盖（只从当前父容器摘出）。

**C2 — 回退路径跨模式切换泄漏黑色遮罩，永久盖死整个 App**
DecorView 回退路径下，`mFullscreenBackground` 直接覆盖旧引用而未先 `removeView`，旧黑 View 永久留在 DecorView 顶层。
**影响面**：所有未调用 `setFullscreenContainer` 的宿主（`app-legacy`、`app-tv`、`orange-player-session` 及外部 SDK 用户）。
**修复**：`moveIntoFullscreen` 回退分支先移除旧遮罩再新建。

### 9.2 High（已修）

**H1 — `mOriginalSystemUiVisibility` 被覆盖成全屏态**
跨模式切换时保存的是「已隐藏系统栏」的值，退出后系统栏不恢复。与 C1 同源，一并修复（只在 NORMAL→全屏时保存）。

**H2 — `mIfCurrentIsFullscreen` 语义未迁移，TV BACK 等三处行为回归**
该 GSY 标志原本只在克隆路径里被置 true，单实例方案下永远为 false，导致：
TV 的 BACK 直接 finish 整个 Activity、全屏中解锁后标题栏不恢复、`backFromFull()` 永远返回 false。
**修复**：新增 `OrangevideoView.setIfCurrentIsFullscreenInternal(boolean)`，由 helper 在进入/退出全屏时维护。

**H3 — GSY 克隆退出路径仍是活的，与新状态机冲突**
`clearFullscreenLayout` / `orangeBackToNormal` / `orangeResolveNormalVideoShow` 未删除，`onBackFullscreen()` 仍指向它（H2 修复后可达）。该路径会执行 `setListener(lastListener)`（无克隆时为 null）、`addTextureView()` 等克隆专有操作，与 `mMode` 双写不一致。
**修复**：三个方法整体删除，`onBackFullscreen()` override 为委托 `stopFullScreen()`。

**H4 — 兜底定时器未取消，可能在下一轮过渡中提前解除 Surface 保护**
**修复**：引入单一 `mFinalizeRunnable`，每次切换前 `removeCallbacks`，收尾时也取消。
**追加修正**：审核另指出 `onConfigurationChanged` 在配置生效时触发，**早于 SurfaceView 重建完成**，若据此立即收尾会在 Surface 尚未重建时解除 `onSurfaceDestroyed`/`setDisplay(null)`/`releaseSurface` 的过渡保护。项目没有可用的 `onSurfaceCreated` 钩子，无法精确捕获重建完成，故改为**保守策略**：配置变化只**重新计时**（`removeCallbacks` + 重新 `postDelayed`），不立即收尾，让保护窗口覆盖随后的 Surface 重建。

**H5 — 注释近逐句翻译 animeko（AGPL-3.0）**
`SwipeSeekerState` 的「90 秒跳过 op/ed」、`dragStartY` 基准说明、`PlayerControllerState` 的 `InlineSliderOnly` 文档等，是 animeko 文案的直译。
**修复**：全部改写为描述本项目自身行为的表述，删除对 animeko 文案的逐句搬运。逻辑语义（算法、优先级、常量值）保留——思想不受版权保护。

### 9.3 Medium（已修）

- **M1**：`enterPortrait` 缺 `requestLayout()` → 已补，与 `enterLandscape` 对齐。
- **M2**：`request(int)` 无入参校验 → 已加非法值拒绝，避免误落 `enterLandscape()`。
- **M4**：两套长按倍速实现并存 → `VodControlView.setupLongPressSpeed` 与 `OrangevideoView` 的画面长按（`touchLongPress`/`onTouch`）**均**已统一到 `FastSkipState`。至此三处长按全部使用 ticket 幂等模型。

### 9.4 未修（记录在案）

- **M3**：View 脱离窗口时 `post` 被 RunQueue 延迟，过渡可能挂起（有兜底定时器，影响有限）。
- **M5**：容器契约由库改宿主 sibling 的 z-order/可见性，宿主若复用该容器需注意；退出后 `mFullscreenContainer` 不清理（这是宿主持有的引用，库不应清）。
- **Low**：`PlayerControllerState` 的 requester 用 `==` 比较（animeko 用 `equals`）。对默认 identity 的 requester 等价，此处为**有意分歧**，已在此标注。

### 9.5 审核结论与现状

审核的总体判断是「消除了 GSY 克隆这一来源，但自研 reparent 在模式切换下仍有确定性缺陷」。

**上述 C1/C2/H1/H2/H3 修复后**，跨模式切换的还原目标、系统栏、TV BACK 语义均已闭合，「切换出问题」的确定性缺陷已消除。剩余 M3/M5 属边界情形，不影响主路径。

> 仍未做真机验证。上表所有结论来自静态推理 + 编译 + 单元测试；真机验收（尤其旋转中途连点、TV BACK、跨模式切换）仍是必要的下一步。



---

## 10. U1 接入：控制器可见性状态机

### 10.1 接入方式

`OrangeStandardVideoController` 引入 `PlayerControllerState`（`mControllerState`），映射关系：

| 旧模型 | 新模型 |
|---|---|
| `mIsShowing` 布尔 | `mControllerState.setFullVisible(...)` |
| `hide()` 里「VodControlView 正在拖动则跳过隐藏」特判 | `VodControlView` 拖动期间登记 alwaysOn 请求（成对 `onStartTrackingTouch`/`onStopTrackingTouch`） |
| — | 新增 `setRequestControllerAlwaysOn(requester, boolean)` 对外接口 |

`show()` / `hide()` 改为设置 `fullVisible` 后调 `applyVisibility()`；`applyVisibility()` 按状态机算出的可见性驱动动画与组件通知，取代原先散落的布尔判断。`setShowing()` 同步 `fullVisible` 以免错位。

### 10.2 一个被否掉的映射（重要）

最初把 `stopFadeOut()` 映射为「登记 alwaysOn 请求」。**这是错的**，构建后自查发现：

`VideoEventManager.hideController()`（`VideoEventManager.java:3454/3462`）在 `hide()` **之后**调用 `stopFadeOut()`。若它登记 alwaysOn 请求，`hide()` 会被 alwaysOn 优先级推翻 → **控制器永远无法隐藏**。

根因：`stopFadeOut` 被当作「取消计时器」用，全仓没有保证配对的 `startFadeOut`。故：

- `stopFadeOut()` / `startFadeOut()` 保持原语义（只操作计时器），**不**触碰状态机；
- 「保持可见」改用显式的 `setRequestControllerAlwaysOn(requester, boolean)`，由调用方成对调用。

### 10.3 兼容性

- `ControlWrapper` 接口**未改动**（`stopFadeOut`/`startFadeOut` 签名与语义不变），外部实现不受影响。
- `VodControlView` 直接持有 `OrangeVideoController`（`mOrangeController`），故通过它访问新接口，无需扩展 `ControlWrapper`。
- `OrangevideoView.hideController()` 里保留的 `isDragging()` 早退属冗余防御（requester 已兜底），行为不变。

### 10.4 验证

`:app:assembleDebug` 通过；`:palyerlibrary` 全量单测 **408 / 0**；`:app-tv` / `:orange-player-session` 编译通过。

---

## 11. 真机验证与后续修复（2026-09-27）

设备：Black Shark SKW-A0 / Android 10。

### 11.1 真机结论

| 验证项 | 结果 |
|---|---|
| 进入全屏 | `request: target=LANDSCAPE_FULLSCREEN, current=NORMAL` + `使用容器契约，未搬运到 DecorView` |
| 实例唯一性 | 全程 `VideoView: @f18a33c`（与创建时一致） |
| **直接跨模式切换** | `PORTRAIT→LANDSCAPE` 与 `LANDSCAPE→PORTRAIT` 均成功（C1 修复生效） |
| 退出还原 | 回到 220dp 槽位，demo UI 完整 |
| **8 次快速连续切换** | 无崩溃、无黑屏、实例始终一个 |
| 证据 | `docs/evidence/device-fullscreen-*.logcat.txt` |

### 11.2 真机暴露并修复的三个缺陷

**R1 — demo 的 BACK 会直接退出 App 而非退出全屏**
`MainActivity.setupBackPressedHandler` 只检查 `GSYVideoManager.isFullState()`（那是 GSY 克隆态，单实例方案下永远 false）。
**修复**：改为优先读 `mVideoView.isFullScreen()`（单一真值）。真机验证：全屏时 BACK 正确退出全屏并恢复竖屏。

**R2 — 横屏全屏下切换方向按钮不显示**
两处原因叠加：
1. `VodControlView` 默认 `mDisplayMode = PORTRAIT_ONLY`（仅竖屏全屏显示）；
2. `OrangeStandardVideoController.getFullscreenHelper()` 依赖 `mControlWrapper`，而宿主路径下它为 `null` → 恒返回 null → 按钮被隐藏（`onRotationButtonClick` 同样被 null 卡死）。
**修复**：默认改 `ALWAYS`；`OrangeVideoController` 重写 `getFullscreenHelper()`，优先用自身持有的 `mVideoView` 解析；`onRotationButtonClick` 改用 `helper.request(...)` 且目标模式由物理方向决定。

**R3 — 存在两个 `rotation_button`，可见的是 VodControlView 那个**
`layout_orange_standard_controller.xml`（控制器布局）与 `orange_layout_vod_control_view.xml` 各有一个同名按钮；控制器那个 parent 尺寸为 0×0，实际可见的是后者。提示逻辑必须落在 VodControlView 的按钮上。

### 11.3 「切换全屏方向」提示（替代常驻旋转图标）

原常驻旋转箭头图标易被误解为「画面正在旋转」。改为**瞬时提示**：

- 用户转动手机（`OrientationEventListener` 判定物理方向变化）时，按**当前手持方向**显示匹配图标，3 秒后自动隐藏；
- 图标用 Material 官方字形对 `stay_current_portrait` / `stay_current_landscape`（不旋转拼凑，避免描边变形）；
- 点击切到「与图标一致」的模式（`helper.request(mode)`，幂等），因此不会出现方向反了的情况；
- 自动旋转开启时屏幕已跟随物理方向，提示无意义（但提示逻辑本身不依赖该开关）。

实现要点：`CustomFullscreenHelper` 持续记录 `mDeviceLandscape` 并支持**多监听者**（两处按钮布局都要能收到）；方向监听器的生命周期由整个全屏会话管理（进入启用、退出停止），不再随自动旋转开关。

### 11.4 临时探针

`MainActivity` 的 `fs_probe:<landscape|portrait|normal|status|hint>` 为验证用临时入口。存在原因：现有 UI 的旋转按钮经 `stopFullScreen()→100ms→startPortraitFullScreen()` **经 NORMAL 中转**，测不到 C1 的「直接跨模式」缺陷；且真机 adb 无法模拟加速度计旋转，需 `hint` 手动触发提示。**提交前应删除或明确保留为调试入口。**
