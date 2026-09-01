# DSH Mobile ProGuard / R8 规则（release 混淆保留）
# 目标：在 R8 压缩+混淆下，kotlinx-serialization / Compose / OkHttp 正常运行。

# ---- kotlinx-serialization ----
# 保留 @Serializable 数据类及其生成的 $serializer / Companion.serializer()（R8 默认可能删）
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# 保留项目内所有序列化模型的 Companion + serializer
-keepclassmembers class dev.dshmobile.model.** { *** Companion; }
-keepclasseswithmembers class dev.dshmobile.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 保留编译器生成的 $serializer 内部类
-keep,includedescriptorclasses class dev.dshmobile.model.**$$serializer { *; }

# ---- OkHttp（自带 consumer rules，此处兜底 keep 核心） ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# ---- kotlinx.coroutines ----
-dontwarn kotlinx.coroutines.**

# ---- 通用：保留序列化 JSON 包 ----
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
