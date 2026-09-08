# OrangePlayer 橘子播放器

<p align="center">
  <strong>功能完整的 Android 视频播放器 SDK</strong>
</p>

<p align="center">
  基于 <a href="https://github.com/CarGuo/GSYVideoPlayer">GSYVideoPlayer</a> 的增强视频播放器库
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.706412584/orangeplayer"><img src="https://img.shields.io/maven-central/v/io.github.706412584/orangeplayer.svg" alt="Maven Central"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
  <a href="https://android-arsenal.com/api?level=14"><img src="https://img.shields.io/badge/API-14%2B-brightgreen.svg?style=flat" alt="API"></a>
</p>

## 功能特性

- 🎬 **多播放内核**：系统/ExoPlayer/IJK/阿里云/MPV，运行时切换
- ✨ **画质增强**：GL 滤镜（Exo/IJK/系统）+ Anime4K 超分与色彩档（MPV 内核，6 档实时切换）
- 🖼️ **画面比例**：MPV 内核原生裁剪/拉伸（keepaspect/video-zoom），其余内核矩阵适配
- 🔄 **内核稳定性**：MPV 内核 Android 16 全屏/切内核 surface 时序自愈（vo fatal 三层恢复、vid=no 主动退场、死 SurfaceTexture 防线）
- 📝 **字幕系统**：SRT/ASS/VTT 格式支持
- 🔍 **视频嗅探**：自动检测网页中的视频资源
- 🔤 **OCR 识别**：硬字幕识别 + ML Kit 翻译
- 🎤 **语音识别**：Vosk 离线语音识别，实时字幕生成
- 💬 **弹幕功能**：大小/速度/透明度可调
- 🎛️ **倍速播放**：0.35x - 10x，长按倍速
- 📺 **投屏支持**：DLNA 投屏
- 🖼️ **画中画**：PiP 小窗模式
- 📱 **多平台**：手机、平板、Android TV 全平台支持
- 🎯 **M3U8去广告**：自动识别并跳过M3U8中的广告片段
- 🌊 **种子播放**：支持 .torrent 文件和磁力链接边下边播

---

## 快速开始

### 系统要求

- **Android 4.0+ (API 14+)** - 从 v1.1.0+ 开始支持 Android 4.0 及以上版本
- **Android 5.0+ (API 21+)** - 推荐使用，支持所有功能（包括 ExoPlayer 和 AI 功能）
- **Android 8.0+ (API 26+)** - MPV 内核（orangeplayer-mpv + libmpv）要求



### 添加依赖

**⚠️ 重要更新：我们已从 JitPack 迁移到 Maven Central**

在 `app/build.gradle` 中添加：

```gradle
dependencies {
    // OrangePlayer 核心库（Maven Central）- 请使用最新版本
    implementation 'io.github.706412584:orangeplayer:+'

    //以下均为可选功能
    // 播放器内核（默认ijk,需要引入ijk架构支持gsyVideoPlayer-ex_so，全架构或者指定架构）
    implementation 'io.github.706412584:gsyVideoPlayer-java:+'    // IJK 播放器
    implementation 'io.github.706412584:gsyVideoPlayer-ex_so:+'
    //EXOPlayer播放器支持
    implementation 'io.github.706412584:gsyVideoPlayer-exo_player2:+' // ExoPlayer
    // 阿里云播放器支持（排除内置版本，避免授权问题）- 请使用最新版本
    implementation('io.github.706412584:gsyVideoPlayer-aliplay:+') {
        exclude group: 'com.aliyun.sdk.android', module: 'AliyunPlayer'
        exclude group: 'com.alivc.conan', module: 'AlivcConan'
    }
    // 使用 5.4.7.1 版本（无需授权）
    implementation 'com.aliyun.sdk.android:AliyunPlayer:5.4.7.1-full'
    // 可选依赖（按需添加）
    implementation 'com.github.bilibili:DanmakuFlameMaster:0.9.25'  // 弹幕功能
    implementation 'cz.adaptech.tesseract4android:tesseract4android:4.7.0'  // OCR 识别
    implementation 'com.google.mlkit:translate:17.0.2'  // ML Kit 翻译
    implementation 'com.alphacephei:vosk-android:0.3.47'  // 语音识别
    implementation 'androidx.media3:media3-decoder-ffmpeg:1.5.0'  // FFmpeg 解码器(可选)

    // MPV 内核（可选第五内核，Anime4K 超分/画质增强/画面比例；Android 8.0+）
    implementation 'io.github.706412584:orangeplayer-mpv:+'
    // libmpv 运行库（orangeplayer-mpv 的必带依赖，Android 8.0+）
    implementation 'io.github.706412584:libmpv:1.0.0'

    // 下载与合并（按需启用）
    implementation 'io.github.706412584:orange-downloader:+'//视频下载库
    implementation 'io.github.706412584:orange-ffmpeg:+'//m3u8合并mp4（可选）
}
```

> 📦 **可用的播放器内核组件**（所有组件请使用最新版本）：
> - `gsyVideoPlayer-java` - IJK 播放器（推荐，支持更多格式）
> - `gsyVideoPlayer-exo_player2` - ExoPlayer（性能好，RTSP 支持完整）
> - `gsyVideoPlayer-aliplay` - 阿里云播放器（商业级，RTMP 延迟低）
> - `orangeplayer-mpv` - MPV 内核（可选，Anime4K 超分与色彩档画质增强，Android 8.0+，需搭配 `io.github.706412584:libmpv:1.0.0` 运行库）
> - `gsyVideoPlayer-base` - 播放器基础库
> - `gsyVideoPlayer-proxy_cache` - 代理缓存支持
> - `gsyVideoPlayer-armv7a` - ARMv7a 架构 so 库
> - `gsyVideoPlayer-armv64` - ARM64 架构 so 库
> - `gsyVideoPlayer-x86` - x86 架构 so 库
> - `gsyVideoPlayer-x86_64` - x86_64 架构 so 库
> - `gsyVideoPlayer-ex_so` - IJK 加密支持 so 库（全架构，支持 HLS AES-128 等加密视频）
> - `orange-downloader` - 下载管理与 M3U8 合并
> - `orange-ffmpeg` - 精简 FFmpeg 合并能力（配合下载使用）
>
> ⚠️ **重要提示**：
> - `gsyVideoPlayer-ex_so` 和标准 so 库（armv7a/armv64/x86/x86_64）**不能同时使用**，会导致 SO 库冲突
> - 如需播放加密视频，使用 `gsyVideoPlayer-ex_so` 替代标准 so 库
> - `ex_so` 包含所有架构，体积较大（约增加 20-30MB）
>
> 💡 **版本说明**：
> - 使用 `+` 可自动获取最新版本
> - 或访问 [Maven Central](https://central.sonatype.com/artifact/io.github.706412584/orangeplayer) 查看最新版本号
> - 当前最新版本：[![Maven Central](https://img.shields.io/maven-central/v/io.github.706412584/orangeplayer.svg)](https://central.sonatype.com/artifact/io.github.706412584/orangeplayer)


> - **可选功能**：弹幕、OCR、语音识别、FFmpeg 解码器等按需添加
> - 完整的依赖配置请查看 [安装指南](docs/INSTALLATION.md)

###

### 基本使用

#### 1. 配置 AndroidManifest.xml

在使用播放器的 Activity 中添加以下配置（**必需**）：

```xml
<activity
    android:name=".YourActivity"
    android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|keyboardHidden"
    android:screenOrientation="portrait"
    android:supportsPictureInPicture="true"
    android:resizeableActivity="true">
</activity>
```

**配置说明：**
- `configChanges` - 防止横竖屏切换时 Activity 重建（**必需**）
- `screenOrientation` - 设置屏幕方向（portrait/landscape/unspecified）
- `supportsPictureInPicture` - 启用画中画模式（可选）
- `resizeableActivity` - 允许调整窗口大小（可选）

#### 2. 布局文件

```xml
<com.orange.playerlibrary.OrangevideoView
    android:id="@+id/video_player"
    android:layout_width="match_parent"
    android:layout_height="200dp" />
```

#### 3. Activity 代码

```java
OrangevideoView videoView = findViewById(R.id.video_player);
videoView.setUp("https://example.com/video.mp4", true, "视频标题");
videoView.startPlayLogic();
```

### 磁力链接和种子播放功能

OrangePlayer 支持磁力链接（magnet）和 .torrent 文件的边下边播功能，基于 libtorrent4j 实现 P2P 下载。

#### 添加依赖

```gradle
dependencies {
    // OrangePlayer 核心库
    implementation 'io.github.706412584:orangeplayer:+'
    
    // 磁力链接/种子播放支持（必需）
    implementation 'org.libtorrent4j:libtorrent4j:2.0.6-26'
    //根据需要，选择一个架构即可
    implementation 'org.libtorrent4j:libtorrent4j-android-arm:2.0.6-26'
    implementation 'org.libtorrent4j:libtorrent4j-android-arm64:2.0.6-26'
    implementation 'org.libtorrent4j:libtorrent4j-android-x86:2.0.6-26'
    implementation 'org.libtorrent4j:libtorrent4j-android-x86_64:2.0.6-26'
}
```

#### 使用示例

```java
import com.orange.playerlibrary.torrent.TorrentPlayerManager;

// ===== 方式 1: 使用 OrangevideoView 统一接口（推荐） =====

// 播放磁力链接（直接传入 magnet URI）
String magnetUri = "magnet:?xt=urn:btih:78a03f3b0623fb39aa91ae98a12ab90873d5c441";
videoView.setUp(magnetUri, true, "磁力视频");
videoView.startPlayLogic();

// 播放 .torrent 文件（直接传入文件路径字符串）
String torrentPath = "/sdcard/Download/movie.torrent";
videoView.setUp(torrentPath, true, "种子视频");
videoView.startPlayLogic();

// 播放 .torrent 文件（传入 File 对象也支持）
File torrentFile = new File("/sdcard/Download/movie.torrent");
videoView.setUp(torrentFile.getAbsolutePath(), true, "种子视频");
videoView.startPlayLogic();

// ===== 方式 2: 使用 TorrentPlayerManager 手动管理（高级用法） =====

TorrentPlayerManager manager = TorrentPlayerManager.getInstance(context);
File saveDir = new File(context.getCacheDir(), "torrents");

// 播放磁力链接
manager.loadMagnet(magnetUri, saveDir, new TorrentPlayerManager.TorrentCallback() {
    @Override
    public void onReady(String proxyUrl, String fileName, long fileSize) {
        // 文件已准备好，开始播放
        videoView.setUp(proxyUrl, true, fileName);
        videoView.startPlayLogic();
    }
    
    @Override
    public void onMagnetResolving(int elapsedSeconds, int totalSeconds) {
        // 磁力链接解析进度（每秒回调一次）
        int progress = (int) (elapsedSeconds * 100.0 / totalSeconds);
        Log.d(TAG, "解析磁力链接中 " + elapsedSeconds + "/" + totalSeconds + "s (" + progress + "%)");
    }
    
    @Override
    public void onBufferProgress(int bufferedPieces, int totalPieces, long bufferedBytes) {
        // 缓冲进度更新
        int progress = (int) (bufferedPieces * 100.0 / totalPieces);
        Log.d(TAG, "缓冲进度: " + progress + "%");
    }
    
    @Override
    public void onDownloadProgress(int progress, long downloadSpeed, long uploadSpeed) {
        // 下载进度更新
        Log.d(TAG, "下载进度: " + progress + "%, 速度: " + downloadSpeed + " B/s");
    }
    
    @Override
    public void onError(String error) {
        Log.e(TAG, "错误: " + error);
        Toast.makeText(context, "播放失败: " + error, Toast.LENGTH_SHORT).show();
    }
});

// 播放 .torrent 文件
File torrentFile = new File("/path/to/file.torrent");
manager.loadTorrent(torrentFile, saveDir, callback);

// 停止播放和下载
manager.stop();
```

#### 磁力链接播放特性

- ✅ **P2P 下载**：基于 BitTorrent 协议，从多个节点下载
- ✅ **边下边播**：顺序下载，缓冲足够后即可播放
- ✅ **DHT 支持**：配置了 5 个主流 DHT 引导节点，加速元数据获取
- ✅ **公共 Tracker**：自动添加 12 个公共 tracker 服务器
- ✅ **进度回调**：实时获取缓冲和下载进度
- ✅ **防止杀进程**：使用 WakeLock 防止系统在获取元数据时杀死进程
- ✅ **本地代理**：通过 HTTP 代理服务器播放，兼容所有播放器内核

#### 常见问题

**Q1: 磁力链接获取元数据超时？**

**A:** 磁力链接需要通过 DHT 网络和 Tracker 服务器获取元数据，可能需要 10-60 秒。如果超时：
- 检查网络连接是否正常
- 确认防火墙/路由器没有阻止 P2P 连接
- 尝试使用 VPN 或更换网络环境
- 确认磁力链接是否有效（可在其他 BT 客户端测试）

**Q2: 为什么百度网盘、迅雷等软件可以快速解析磁力链接？**

**A:** 商业 BT 客户端通常有以下优势：

1. **独立服务器解析**
   - 百度网盘、迅雷等有自己的元数据服务器
   - 用户上传的种子会被缓存到服务器
   - 解析时直接从服务器获取，无需 DHT 查询

2. **中转加速**
   - 使用自己的 Tracker 服务器和 DHT 节点
   - 拥有大量活跃节点，连接速度快
   - 可能使用离线下载技术（服务器先下载，用户再从服务器获取）

3. **资源库优势**
   - 热门资源已被缓存
   - 拥有大量用户共享的节点信息
   - 可以快速找到高速节点

**OrangePlayer 的磁力链接播放是纯 P2P 方式**：
- ✅ 完全开源，无需服务器
- ✅ 隐私保护，不上传数据到第三方
- ✅ 免费使用，无需会员
- ❌ 首次解析可能较慢（取决于 DHT 网络和 Tracker 响应）
- ❌ 冷门资源可能找不到节点

**Q3: 如何提高磁力链接解析速度？**

**A:** 
1. 使用热门资源（节点多，解析快）
2. 保持应用在前台运行（避免被系统杀死）
3. 使用稳定的网络环境（WiFi 优于移动网络）
4. 如果有 .torrent 文件，优先使用 `loadTorrent()` 而不是 `loadMagnet()`

**Q4: 磁力链接播放消耗流量吗？**

**A:** 是的，磁力链接播放会消耗流量：
- 下载视频数据会消耗下行流量
- P2P 上传会消耗上行流量（可通过 libtorrent 配置限制）
- 建议在 WiFi 环境下使用

**Q5: 支持哪些磁力链接格式？**

**A:** 支持标准的 magnet URI 格式：
```
magnet:?xt=urn:btih:<info-hash>&dn=<name>&tr=<tracker-url>
```
- `xt` - 必需，BitTorrent info hash
- `dn` - 可选，显示名称
- `tr` - 可选，Tracker 服务器（会自动添加公共 tracker）

#### 技术细节

**DHT 引导节点配置：**
```
dht.libtorrent.org:25401
router.bittorrent.com:6881
router.utorrent.com:6881
dht.transmissionbt.com:6881
dht.aelitis.com:6881
```

**公共 Tracker 服务器：**
```
udp://tracker.opentrackr.org:1337/announce
udp://open.tracker.cl:1337/announce
udp://tracker.openbittorrent.com:6969/announce
udp://tracker.torrent.eu.org:451/announce
... 等 12 个公共 tracker
```

**超时设置：**
- 元数据获取超时：60 秒
- WakeLock 超时：90 秒（防止系统杀进程）

**本地代理服务器：**
- 自动启动 HTTP 代理服务器
- 端口：随机分配
- 支持 Range 请求（支持 seek）
- 自动管理缓冲区

### 智能全屏功能

点击全屏按钮时，播放器会根据视频宽高比自动选择最佳全屏模式：
- **横屏视频**（宽 > 高）→ 自动进入横屏全屏
- **竖屏视频**（高 > 宽）→ 自动进入竖屏全屏

```java
// 智能全屏默认开启，无需额外配置
// 用户点击全屏按钮时会自动根据视频比例选择全屏模式

// 如需手动控制：
videoView.setSmartFullscreenEnabled(true);   // 启用智能全屏（默认）
videoView.setSmartFullscreenEnabled(false);  // 禁用智能全屏（使用传统横屏全屏）

// 查询状态
boolean isEnabled = videoView.isSmartFullscreenEnabled();
```

**智能全屏特性：**
- ✅ 点击全屏按钮时自动检测视频宽高比
- ✅ 默认开启，可手动关闭
- ✅ 禁用后使用传统横屏全屏
- ✅ 支持横屏/竖屏两种全屏模式
- ✅ 视频尺寸无效时自动降级到横屏全屏

### 视频嗅探功能

自动检测网页中的视频资源，支持 MP4、M3U8、FLV 等格式。

```java
import com.orange.playerlibrary.VideoSniffing;

// 开始嗅探
VideoSniffing.startSniffing(context, "https://example.com/page", new VideoSniffing.Call() {
    @Override
    public void received(String contentType, HashMap<String, String> headers, 
                        String title, String url) {
        // 发现视频资源
        Log.d(TAG, "发现视频: " + url);
        Log.d(TAG, "类型: " + contentType);
        Log.d(TAG, "标题: " + title);
    }
    
    @Override
    public void onFinish(List<VideoSniffing.VideoInfo> videoList, int videoSize) {
        // 嗅探完成
        Log.d(TAG, "共发现 " + videoSize + " 个视频");
        
        // 播放第一个视频
        if (videoSize > 0) {
            VideoSniffing.VideoInfo video = videoList.get(0);
            videoView.setUp(video.url, true, video.title);
            videoView.startPlayLogic();
        }
    }
});

// 停止嗅探
VideoSniffing.stop(true);
```

**嗅探功能特性：**
- ✅ 自动检测视频资源（MP4、M3U8、FLV、AVI、MOV、MKV）
- ✅ 支持自定义请求头
- ✅ 智能过滤非视频资源（图片、CSS、JS、广告等）
- ✅ 并发控制，避免 ANR
- ✅ URL 去重，避免重复检测
- ✅ 调试模式，方便排查问题
- ✅ 自动播放第一个视频（可选）

**嗅探自动播放设置：**

```java
// 启用嗅探自动播放（默认关闭）
PlayerSettingsManager.getInstance(context).setSniffingAutoPlayEnabled(true);

// 启用后，嗅探完成时会自动播放第一个视频并隐藏嗅探组件
videoView.startSniffing();

// 查询状态
boolean autoPlay = PlayerSettingsManager.getInstance(context).isSniffingAutoPlayEnabled();
```

**带自定义请求头的嗅探：**

```java
// 添加自定义请求头（如 User-Agent、Referer 等）
Map<String, String> headers = new HashMap<>();
headers.put("User-Agent", "Mozilla/5.0 ...");
headers.put("Referer", "https://example.com");

VideoSniffing.startSniffing(context, url, headers, callback);
```

**调试模式：**

```java
// 启用调试日志（查看嗅探过程）
VideoSniffing.isDebug = true;

// 查看 logcat 日志（标签：VideoSniffing）
// 会输出：检查 URL、响应码、Content-Type、视频发现等信息
```

---
### M3U8去广告功能

自动识别并跳过M3U8播放列表中的广告片段，提供流畅的观看体验。

```java
import com.orange.playerlibrary.m3u8.M3U8AdManager;

// 开启M3U8去广告功能（默认关闭）
M3U8AdManager.getInstance(context).setEnabled(true);

// 查询状态
boolean isEnabled = M3U8AdManager.getInstance(context).isEnabled();

// 关闭功能
M3U8AdManager.getInstance(context).setEnabled(false);
```

**去广告特性：**
- ✅ 自动识别广告片段（基于 DISCONTINUITY 标签和时长特征）
- ✅ 保留 AES 加密密钥，支持加密视频
- ✅ 修复去广告后 seek 时间轴不准确问题
- ✅ 默认关闭，需要手动开启
## 更多功能

查看完整文档了解更多功能：

- [更新日志](docs/CHANGELOG.md) - 版本更新历史
- [播放内核切换](docs/PLAYER_ENGINES.md) - 系统/ExoPlayer/IJK/阿里云/MPV
- [OCR 字幕翻译](docs/OCR_GUIDE.md) - 硬字幕识别与翻译
- [语音识别字幕](docs/SPEECH_RECOGNITION.md) - 实时语音转字幕
- [投屏功能](docs/CAST_GUIDE.md) - DLNA 投屏配置
- [Android TV 适配](docs/TV_QUICK_START.md) - TV 平台快速集成
- [API 文档](docs/API.md) - 完整的 API 参考
- [常见问题](docs/FAQ.md) - 问题排查和解决方案

---

## 混淆配置

```proguard
# GSYVideoPlayer
-keep class com.shuyu.gsyvideoplayer.** { *; }
-keep class tv.danmaku.ijk.** { *; }

# OrangePlayer
-keep class com.orange.playerlibrary.** { *; }

# Tesseract OCR
-keep class com.googlecode.tesseract.android.** { *; }

# ML Kit Translation
-keep class com.google.mlkit.** { *; }

# Vosk 语音识别
-keep class org.vosk.** { *; }

# 阿里云播放器
-keep class com.aliyun.player.** { *; }
-keep class com.cicada.player.** { *; }

# DLNA 投屏
-keep class com.uaoanlao.tv.** { *; }
```

---

## License

Apache License 2.0

---

## 作者

**QQ: 706412584**
**QQ群 :630713969**

如有问题或建议，欢迎联系交流。

## 致谢

- [GSYVideoPlayer](https://github.com/CarGuo/GSYVideoPlayer)
- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android)
- [DanmakuFlameMaster](https://github.com/bilibili/DanmakuFlameMaster)
- [UaoanDLNA](https://github.com/uaoan/UaoanDLNA)
