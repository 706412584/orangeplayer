# 播放内核切换指南

OrangePlayer 支持 5 种播放内核，可根据需求选择或在运行时动态切换。

## 支持的播放内核

| 内核 | 优点 | 缺点 | 适用场景 |
|------|------|------|---------|
| **ExoPlayer** | 功能完整、更新频繁、支持多种格式 | 包体积较大 | 推荐首选 |
| **IJK** | 轻量级、支持 RTMP/HLS | 维护停止、兼容性问题 | 特定格式需求 |
| **系统播放器** | 最轻量、系统集成 | 功能有限、兼容性差 | 简单场景 |
| **阿里云** | 专业级、DRM 支持 | 需要阿里云账户、包体积大 | 企业应用 |
| **MPV** | 高品质渲染（libplacebo）、Anime4K 超分与色彩档画质增强 | 独立 AAR 约 46MB、需 Android 8.0+ | 画质优先、超分需求 |

## 快速开始

### 1. 添加依赖

在 `app/build.gradle` 中添加所需的播放内核依赖：

```gradle
dependencies {
    // OrangePlayer 核心库
    implementation 'com.github.706412584:orangeplayer:1.0.7'
    
    // GSY 基础库（必需）
    implementation 'io.github.706412584:gsyVideoPlayer-java:1.1.0'
    
    // ExoPlayer 内核（推荐）
    implementation 'io.github.706412584:gsyVideoPlayer-exo_player2:1.1.0'
    
    // IJK 内核（可选）
    // implementation 'io.github.706412584:gsyVideoPlayer-java:1.1.0'
    
    // 阿里云内核（可选）
    // implementation 'io.github.706412584:gsyVideoPlayer-aliplay:1.1.0'
}
```

### 2. 在代码中切换

```java
import com.orange.playerlibrary.OrangevideoView;
import com.shuyu.gsyvideoplayer.player.PlayerConstants;

public class MainActivity extends AppCompatActivity {
    private OrangevideoView mVideoView;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mVideoView = findViewById(R.id.video_player);
        
        // 选择播放内核（在 setUp 之前调用）
        selectPlayerEngine(PlayerConstants.ENGINE_EXO);
        
        // 设置视频
        mVideoView.setUp("https://example.com/video.mp4", true, "示例视频");
        mVideoView.startPlayLogic();
    }
    
    private void selectPlayerEngine(int engine) {
        switch (engine) {
            case PlayerConstants.ENGINE_EXO:
                mVideoView.selectPlayerFactory(PlayerConstants.ENGINE_EXO);
                break;
            case PlayerConstants.ENGINE_IJK:
                mVideoView.selectPlayerFactory(PlayerConstants.ENGINE_IJK);
                break;
            case PlayerConstants.ENGINE_DEFAULT:
                mVideoView.selectPlayerFactory(PlayerConstants.ENGINE_DEFAULT);
                break;
            case PlayerConstants.ENGINE_ALI:
                mVideoView.selectPlayerFactory(PlayerConstants.ENGINE_ALI);
                break;
        }
    }
}
```

## 各内核详细配置

### ExoPlayer（推荐）

**优点：**
- 功能最完整
- 官方持续维护
- 支持 HLS、DASH、SmoothStreaming 等多种格式
- 支持 DRM 保护
- 性能优异

**依赖配置：**

```gradle
dependencies {
    implementation 'io.github.706412584:gsyVideoPlayer-exo_player2:1.1.0'
}
```

**使用示例：**

```java
// 切换到 ExoPlayer
mVideoView.selectPlayerFactory(PlayerConstants.ENGINE_EXO);

// 设置视频
mVideoView.setUp("https://example.com/video.mp4", true, "示例视频");
mVideoView.startPlayLogic();
```

**混淆配置：**

```proguard
-keep class com.google.android.exoplayer2.** { *; }
-keep interface com.google.android.exoplayer2.** { *; }
```

### IJK 播放器

**优点：**
- 轻量级
- 支持 RTMP、HLS 等流媒体格式
- 跨平台支持

**缺点：**
- 项目已停止维护
- 某些新格式支持不足

**依赖配置：**

```gradle
dependencies {
    implementation 'io.github.706412584:gsyVideoPlayer-java:1.1.0'
}
```

**使用示例：**

```java
// 切换到 IJK
mVideoView.selectPlayerFactory(PlayerConstants.ENGINE_IJK);

// 设置视频
mVideoView.setUp("rtmp://example.com/live/stream", true, "直播流");
mVideoView.startPlayLogic();
```

**混淆配置：**

```proguard
-keep class tv.danmaku.ijk.** { *; }
-keep interface tv.danmaku.ijk.** { *; }
```

### 系统播放器

**优点：**
- 最轻量级
- 无需额外依赖
- 系统集成度高

**缺点：**
- 功能有限
- 不同 Android 版本表现不一致
- 不支持某些高级格式

**使用示例：**

```java
// 切换到系统播放器
mVideoView.selectPlayerFactory(PlayerConstants.ENGINE_DEFAULT);

// 设置视频
mVideoView.setUp("https://example.com/video.mp4", true, "示例视频");
mVideoView.startPlayLogic();
```

### 阿里云播放器

**优点：**
- 专业级播放器
- 支持 DRM 保护
- 支持点播和直播
- 支持加密内容

**缺点：**
- 需要阿里云账户
- 包体积较大
- 需要额外配置

**依赖配置：**

```gradle
dependencies {
    implementation 'io.github.706412584:gsyVideoPlayer-aliplay:1.1.0'
}
```

**使用示例：**

```java
// 切换到阿里云播放器
mVideoView.selectPlayerFactory(PlayerConstants.ENGINE_ALI);

// 设置视频
mVideoView.setUp("https://example.com/video.mp4", true, "示例视频");
mVideoView.startPlayLogic();
```

**混淆配置：**

```proguard
-keep class com.aliyun.player.** { *; }
-keep class com.cicada.player.** { *; }
```

## 运行时切换内核

可以在应用运行时动态切换播放内核：

```java
public class PlayerEngineSelector {
    
    public static void switchEngine(OrangevideoView videoView, int engine) {
        // 停止当前播放
        videoView.onVideoPause();
        
        // 切换内核
        videoView.selectPlayerFactory(engine);
        
        // 重新加载视频
        videoView.startPlayLogic();
    }
}
```

## 常见问题

### Q: 如何判断当前使用的是哪个播放内核？

A: 可以通过以下方式获取：

```java
int currentEngine = mVideoView.getPlayerFactory().getPlayerType();
```

### Q: 切换内核后需要重新加载视频吗？

A: 是的，切换内核后需要调用 `startPlayLogic()` 重新加载视频。

### Q: 能否同时使用多个播放内核？

A: 可以，但建议只在一个播放器实例中使用一个内核，以避免资源冲突。

### Q: ExoPlayer 和 IJK 哪个更好？

A: ExoPlayer 是官方推荐，功能更完整、更新频繁。IJK 已停止维护，仅在需要特定格式支持时使用。

### Q: 如何处理播放内核不支持的格式？

A: 可以尝试切换到其他内核，或使用转码服务将视频转换为支持的格式。

## 性能对比

| 指标 | ExoPlayer | IJK | 系统播放器 | 阿里云 |
|------|-----------|-----|----------|--------|
| 包体积 | 中等 | 小 | 无 | 大 |
| 功能完整度 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐⭐ |
| 维护状态 | ✅ 活跃 | ❌ 停止 | ✅ 系统 | ✅ 活跃 |
| 格式支持 | 丰富 | 中等 | 基础 | 丰富 |
| DRM 支持 | ✅ | ❌ | ❌ | ✅ |

## 推荐方案

- **通用应用**：使用 ExoPlayer（默认推荐）
- **轻量级应用**：使用系统播放器
- **直播应用**：使用 IJK 或 ExoPlayer
- **企业应用**：使用阿里云播放器
- **多格式支持**：使用 ExoPlayer + 备用方案

