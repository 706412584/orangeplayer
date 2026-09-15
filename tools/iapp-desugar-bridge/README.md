# iApp 侧 aar 脱糖桥接补丁

修复 iApp 项目（`sdk/` 下放本地 aar 的构建方式）运行时的 `AbstractMethodError`。

## 问题

iApp 对 `sdk/` 下**每个依赖文件独立 dex**——实测一个 aar 恰好对应一个 dex，
54 个 dex 无任何混装。同时它的 dexer 以 `min-api < 24` 运行，于是接口的
default 方法被脱糖成「抽象方法 + `接口$-CC.$default$xxx`」。

实现类与接口分属不同 dex 时，dexer 看不到接口的 default 信息，**不会给实现类
补转发方法** → 运行时接口方法为 abstract、实现类又没覆盖 → `AbstractMethodError`。

d8 的警告直接点名了这一点：

```
Type `androidx.media3.exoplayer.analytics.AnalyticsListener` was not found,
it is required for default or static interface methods desugaring of
`tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer`
```

崩溃长这样：

```
java.lang.AbstractMethodError: abstract method
  "void androidx.media3.common.Player$Listener.onTimelineChanged(Timeline, int)"
  on receiver tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
```

### 缺口有多大

ASM 字节码级扫描（media3 + GSY + OrangePlayer 共 4179 个类）出 **18 组跨 aar
缺口、共 1207 处**，不止 `IjkExo2MediaPlayer` 一处：

| 来源 aar | 目标 aar | 缺口数 |
|---|---|---|
| gsyVideoPlayer-exo_player2 | media3-exoplayer | 74 |
| gsyVideoPlayer-exo_player2 | media3-common | 50 |
| media3-ui | media3-common | 173 |
| media3-session | media3-common | 45 |
| media3-exoplayer | media3-common | 34 |
| media3-extractor | media3-common | 33 |
| … | | |

media3 家族内部也互相踩，任何一条回调路径被走到都会崩。

### 为什么换 maven 依赖治不了

`gsyVideoPlayer-exo_player2` 等本地 aar **没有对应的 1.5.4 远程坐标**
（Maven Central 上 `io.github.706412584` 只发到 1.4.2，实测 404）。它永远是
本地文件、永远单独 dex。`androidx.media3:*:1.11.0` 虽然 Google Maven 上有，
但换不掉 exo_player2 这一侧，跨 aar 缺口依旧。

> 长期解法是把 `gsyVideoPlayer-exo_player2` 也发到 Maven Central，让它和
> media3 进同一个 dex 批次；在那之前用本补丁。

## 用法

```bash
# 打补丁 + 自检（跨 aar 缺口必须为 0）
python tools/iapp-desugar-bridge/patch_aars.py \
    --in  D:/download/iapp_aars \
    --out D:/download/patched \
    --verify

# 只分析不改（排查用，会打印全部缺口）
python tools/iapp-desugar-bridge/patch_aars.py --analyze-only --in D:/download/iapp_aars

# 只重打某几个 aar（classpath 仍用目录下全部 aar，别缩减）
python tools/iapp-desugar-bridge/patch_aars.py --in <dir> --out <dir> \
    --only gsyVideoPlayer-exo_player2-1.5.4.aar,media3-common-1.11.0.aar
```

打完把产物推到设备 `sdk/`，在 iApp 里重新编译安装。

**`--in` 必须指向含全部依赖的目录**，不能只给单个 aar：classpath 不全时接口
信息残缺，补丁数会偏少甚至为 0（脚本对单文件输入会警告）。

## 重要：每次更新 aar 后都要重打

补丁是**对产物的一次性改写**，不是构建期钩子。任何一次替换 `sdk/` 下的 aar
（换版本、重新 strip so、重新编译模块）都会让补丁失效，立刻退回崩溃状态。

流程固定为：

```
更新 aar → 跑 patch_aars.py → 推送产物到设备 → iApp 重新编译安装
```

## 依赖

- **JDK 11+**（要 `javac`）。脚本按 `JAVA_HOME` → 常见 Android Studio / JBR
  路径 → `PATH` 顺序查找，找不到会报错并提示。
- **ASM 9.9**：自动从 Gradle 缓存找；找不到则从阿里云 / Maven Central 下载，
  并校验 sha256（写死在脚本里）。也可 `--asm <path>` 指定。

## 实现要点

`PatchAar.java` 按 d8 的脱糖约定，给「实现了跨文件接口、但未声明其 default
方法」的类补转发方法（与 d8 产物逐字节同形）：

```java
public synthetic void onXxx(args) {
    Iface$-CC.$default$onXxx(this, args);
}
```

以下 d8 语义均已实测对照，改工具时别破坏：

1. **default 方法要沿接口继承链收集**。`class C implements B`、而 default 声明
   在 B 的父接口 A 上时，d8 同样给 C 补桥接。只查直接接口会漏。
2. **static / private 接口方法不补**。它们不进实现类（static 的作为 `s` 留在
   `接口$-CC` 里）。误补会给实现类塞一个不该有的方法。
3. **只要类已声明该方法就绝不重插**，哪怕是把 default 重新声明为 `abstract`。
   否则产生重复方法 = 非法 class 文件，ART 抛 `ClassFormatError`。抽象类由
   子类负责实现，不是「缺失」。
4. **同 aar 内的缺口不用管**：这些类会一起 dex，d8 自己会补。`AnalyzeGaps`
   会把它们一并列出，属正常，只看「跨 aar」那部分。

## 验证过的检查项

| 检查 | 结果 |
|---|---|
| 缺口分析（含接口继承链） | 补丁前 1207 → 补丁后 **0** |
| 类数量 | 4179 → 4179（无增减） |
| res 文件 | 25 个 aar 全部 0 差异 |
| zip 完整性 | 25 个全部通过 |
| 端到端 dex 模拟 | 补丁后单独 dex，桥接保留且正确指向 `$-CC` |
| 边界用例 | 抽象类重声明 default / static 方法 / 已实现 —— 均与 d8 基准一致 |

补丁规模：207 类 / +1207 桥接方法。
