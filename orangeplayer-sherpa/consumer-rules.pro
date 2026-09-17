# sherpa-onnx Java API 走 JNI 直绑，混淆会断 native 绑定——整包 keep
-keep class com.k2fsa.sherpa.onnx.** { *; }
# 工厂类（palyerlibrary 反射加载）
-keep class com.orange.player.sherpa.SherpaAsrEngineFactory { *; }

# 说话人分离进度回调：native 用 GetMethodID 按字面签名
# (IIJ)Ljava/lang/Integer; 查找 invoke。R8 会做签名多态化
# （把 Integer 参数降成 int），改名/去桥都会让 native 回调静默失效——
# 不崩，只是分离进度不再上报，正是本次要修的症状，故显式 keep 且禁止优化。
-keep class com.orange.player.sherpa.SherpaBatchAsrEngine$DiarProgressCallback { *; }
