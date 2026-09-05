# S4 事件映射表：libmpv → IMediaPlayer/GSY（评审稿）

> 目标：证明 mpv 事件模型能映射到 GSY 的 `IMediaPlayer` 语义。
> 任一行被判定"不可映射且无替代"→ 整体 no-go。

## 生命周期

| mpv 事件/属性 | IMediaPlayer 回调 | 时序约束 | 可行性 |
|---|---|---|---|
| `MPV_EVENT_START_FILE` | （无对应） | — | 忽略即可 |
| `MPV_EVENT_FILE_LOADED` | `notifyOnPrepared()` | 首帧可渲染前必须完成 Surface attach | ✅ 直映射 |
| `MPV_EVENT_END_FILE`(reason=eof) | `notifyOnCompletion()` | 区分 eof/stop/error | ✅ 按 reason 分派 |
| `MPV_EVENT_END_FILE`(reason=error) | `notifyOnError(what, extra)` | what 用 `MEDIA_ERROR_UNKNOWN` + mpv error code | ✅ |
| `MPV_EVENT_IDLE` | （无对应） | 置空态，不通知 | ✅ 忽略 |

## 状态轮询（mpv 属性观察 → GSY 拉取）

GSY 拉取式 API 与 mpv 推送式属性不冲突——把属性缓存为字段即可：

| mpv 属性 | IMediaPlayer 方法 | 缓存策略 |
|---|---|---|
| `pause` | `isPlaying()`/`start()`/`pause()` | observe + 直写命令 |
| `time-pos`(秒) | `getCurrentPosition()`(ms) | observe，格式转换 |
| `duration` | `getDuration()` | observe |
| `percent-pos`/`cache-buffering-state` | `getBufferedPercentage()` | observe 计算 |
| `seeking` | seek 完成后 `notifyOnSeekComplete()` | 监听 `MPV_EVENT_SEEK` + `MPV_EVENT_PLAYBACK_RESTART`（seek 结束信号） |
| `speed` | `setSpeed()/getSpeed()` | 直写属性 |

## 渲染与尺寸

| mpv | IMediaPlayer/GSY | 备注 |
|---|---|---|
| `android-surface-size` 属性 + `attachSurface` | `setDisplay()/setSurface()` | mpv-android `BaseMPVView` 已验证模式 |
| `MPV_EVENT_VIDEO_RECONFIG` → 读 `width/height/dwidth/dheight` | `notifyOnVideoSizeChanged(w,h,sarNum,sarDen)` | SAR 从 `video-params/sar` 属性换算 |

## 已识别风险

1. **prepareAsync 语义**：GSY 要求 `prepareAsync` 异步且随后回调
   onPrepared。mpv 无 prepare 概念（`loadfile` 即全流程）。
   方案：`prepareAsync()` 内部只缓存 URL，把 `loadfile` 延迟到
   `start()` 或 GSY 首次 `setSurface` 后（与 `BaseMPVView.playFile`
   的延迟 loadfile 模式一致）。**需桩验证**。
2. **seek 精度**：mpv 默认 keyframe seek；GSY 无精确 seek 契约，
   无冲突。`hr-seek=yes` 可选。
3. **网速**：`getNetSpeed()` 可从 GSY 层 TrafficStats 取（现行为），
   无需 mpv 参与。
4. **`MPV_EVENT_SHUTDOWN`**：release 路径须先 `detachSurface` 再
   `destroy`，避免 use-after-free（`BaseMPVView` 同款次序）。

## 桩验证记录（2026-09-05）

运行命令：

```bash
./gradlew -p experiments/orange-player-mpv test
```

结果：5/5 通过。

- [x] loadfile 延迟到 Surface attach 后，FILE_LOADED 才触发 prepared
- [x] time-pos observe 在暂停时不推进、恢复后继续
- [x] END_FILE(error) 只触发 ErrorView 语义，不触发 CompleteView
- [x] Surface 销毁/重建期间不重复 loadfile，seek 完成事件不丢失
- [x] release 严格先 detachSurface 再 destroy，且重复 release 幂等

实现位于独立纯 Java 工程的 `S4EventAdapter`，不加载 libmpv，也不接入主构建。
该结果只验证适配层状态机；JNI 事件来源、真实旋转稳定性和 native
release 安全性仍须在 S2 真机原型中验证。
