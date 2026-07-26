# Hermes Mobile ProGuard Rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Hermes network models
-keep class com.hermes.mobile.data.model.** { *; }
-keep class com.hermes.mobile.network.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
