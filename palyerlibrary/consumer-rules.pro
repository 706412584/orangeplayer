# Consumer rules for playerlibrary
#
# 按需安装的 native 组件（见 NativeLibManager）在运行时按类名加载/探测这些类。
# 它们对本库是 compileOnly、对宿主是可选依赖，代码里只有反射引用，R8 会把它们
# 判为不可达而裁掉或改名——实测 release 包中 org.libtorrent4j.LibTorrent 变成
# R8$$REMOVED$$CLASS$$502，导致种子组件被误报「本版本未集成该组件」。
#
# 这些类名同时出现在 NativeLibManager.PROBE_CLASSES 与各 AvailabilityChecker 中，
# 改动类名时必须同步更新，否则门禁会静默失效。

# 种子播放：反射探测 LibTorrent/SessionManager，且库内部另有 JNI 反射
-keep class org.libtorrent4j.** { *; }

# 语音识别：SherpaAvailabilityChecker 探测 OfflineRecognizer，
# 并通过反射加载 com.orange.player.sherpa.SherpaAsrEngineFactory
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.orange.player.sherpa.** { *; }

# 文字识别：OcrAvailabilityChecker 探测 TessBaseAPI，TesseractOcrEngine 全程反射调用
-keep class com.googlecode.tesseract.android.** { *; }

# 文字翻译：OcrAvailabilityChecker 探测 Translator
-keep class com.google.mlkit.nl.translate.** { *; }

# DLNA 投屏：DLNACastManager 全程反射调用 com.uaoanlao.tv.Screen
# （Class.forName + newInstance + getMethod("setStaerActivity"/"setName"/"setUrl"/
#  "setImageUrl"/"show")）。UaoanDLNA 的 AAR 里 proguard.txt 是 0 字节，没有自带
# consumer 规则，宿主又是 implementation 直接引入——R8 会把这个只在反射字符串里
# 出现的类判为不可达并删除/改名。真机实测（Android 16 / slim 包）：Screen 与
# DeviceListAdapter 被整类删除，forName 被 R8 的字符串重写导向了别的混淆类
# （l4.g），newInstance 抛 InstantiationException，投屏必失败且 isDLNAAvailable()
# 仍返回 true（按钮可点，点了只报错）。
-keep class com.uaoanlao.tv.** { *; }

# ===== 播放内核（同样只被反射探测，so 改为按需下载）=====
# 这些类只出现在 NativeLibManager.PROBE_CLASSES / PlayerEngineAvailability 里，
# 代码中没有静态引用，R8 会判为不可达而裁掉或改名——一旦被裁，「依赖在、so 未下载」
# 就会被误判成「宿主未引入」，引擎按钮直接消失，用户无从下载。
# 注意：ali 的探针是 AliPlayerFactory 而非 NativePlayerBase（后者 <clinit> 会触发
# NativeLoader.loadPlayer()，那一步失败会把 playerLoaded 永久置位、进程内不再重试）。
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep class com.aliyun.player.AliPlayerFactory { *; }
-keep class com.orange.player.mpv.MpvPlayerManager { *; }
-keep class com.orange.ffmpeg.FFmpegKit { *; }
