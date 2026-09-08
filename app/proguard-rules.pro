-dontwarn com.google.errorprone.annotations.**
-keep class com.google.errorprone.annotations.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class com.hermes.mobile.** { *; }
-keepclassmembers class com.hermes.mobile.** { *; }
-keep class dagger.hilt.** { *; }
-keep class androidx.hilt.** { *; }
-keep class androidx.lifecycle.ViewModel { *; }
-keep class androidx.lifecycle.** { *; }
-keep class com.hermes.mobile.ui.screens.voice.VoiceViewModel { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}
-keepattributes *Annotation*
-keep class javax.inject.** { *; }
-keep class dagger.** { *; }
-dontwarn javax.inject.**
-dontwarn dagger.**
-dontwarn androidx.hilt.**