# Hermes Mobile ProGuard Rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Hermes network models
-keep class com.hermes.mobile.data.model.** { *; }
-keep class com.hermes.mobile.network.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# security-crypto (Tink): errorprone annotations are compile-time only
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Room
-keep class * extends androidx.room.RoomDatabase
