# sherpa-onnx Java API 走 JNI 直绑，混淆会断 native 绑定——整包 keep
-keep class com.k2fsa.sherpa.onnx.** { *; }
# 工厂类（palyerlibrary 反射加载）
-keep class com.orange.player.sherpa.SherpaAsrEngineFactory { *; }
