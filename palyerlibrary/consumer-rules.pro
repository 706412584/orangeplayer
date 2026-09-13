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
