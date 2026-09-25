# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Keep model classes
-keep class com.motointercom.domain.model.** { *; }
-keep class com.motointercom.data.** { *; }
-keep class com.motointercom.service.** { *; }

# Keep Bluetooth and audio classes
-keep class android.bluetooth.** { *; }
-keep class android.media.audiofx.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
