# MPVLib JNI 保持（Kotlin 类 + native 方法）
-keep class dev.jdtech.mpv.** { *; }

# IMediaPlayer 桥接层通过 GSY 反射/回调使用
-keep class com.orange.player.mpv.** { *; }
