# 收这吧 · ProGuard 规则

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Kotlinx Serialization（如后续引入）
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# 数据模型：避免被混淆导致 JSON 解析失败
-keep class com.shouzhe.app.domain.model.** { *; }
-keep class com.shouzhe.app.model.schema.** { *; }

# OkHttp/WebView
-dontwarn okhttp3.**
-dontwarn okio.**