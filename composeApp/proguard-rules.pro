# ValhallaApp Android Release R8/ProGuard 规则（v1.6.0）
#
# Compose / Kotlin 协程 / Kotlinx 序列化相关的保守保留项。
# 规则宁可略宽，也不要让 release 包在运行时出现 ClassNotFound / NoSuchMethod。

# 保留 Kotlin 元数据与注解，供反射与序列化使用
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# Compose Multiplatform：保留 Compose 运行时与组合函数入口
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn androidx.compose.**

# Kotlin 协程：调度器与挂起恢复依赖类名/反射
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    *;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Kotlinx 序列化：当前未直接依赖，保留规则以便后续引入 @Serializable 模型
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# 关闭 release 的调试日志输出：R8 直接移除 Log.v/d/i 调用
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# 保留枚举 name()/valueOf() 语义（协议命令名与导出格式依赖枚举名）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
