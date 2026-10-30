# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK proguard-android.txt

# Keep domain models
-keep class com.motointercom.domain.model.** { *; }

# Keep Android Service components declared in Manifest
-keep class com.motointercom.service.IntercomService { *; }

# Keep Bluetooth and audio FX callbacks
-keep class android.bluetooth.** { *; }
-keep class android.media.audiofx.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
