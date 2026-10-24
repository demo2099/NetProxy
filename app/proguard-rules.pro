# Keep libbox (gomobile generated) API surface
-keep class io.nekohasekai.libbox.** { *; }
-dontwarn io.nekohasekai.libbox.**

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.interstellar.proxy.**$$serializer { *; }
-keepclassmembers class com.interstellar.proxy.** { *** Companion; }
-keepclasseswithmembers class com.interstellar.proxy.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# ZXing（扫码）：R8 会把没被直接引用的解码器裁掉，
# MultiFormatReader 内部按 hints 选解码器，属于「间接引用」的重灾区，整包保留最省事
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
