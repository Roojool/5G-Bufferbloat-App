# Proguard rules for BufferbloatShaper

# Keep VPN service
-keep class com.bufferbloatshaper.vpn.ShaperVpnService { *; }

# JNI symbol and callback names are part of the native-engine ABI. A real
# engine calls SocketProtector by method ID, so shrinking must not rename it.
-keep class com.bufferbloatshaper.nativeengine.NativeEngineBridge { *; }
-keep class com.bufferbloatshaper.nativeengine.SocketProtector { boolean protectSocket(int); }

# Keep data classes used for serialization
-keep class com.bufferbloatshaper.model.** { *; }
-keep class com.bufferbloatshaper.validation.TestResult { *; }
-keep class com.bufferbloatshaper.validation.TestResult$Grade { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { *; }

# AndroidX Compose
-dontwarn androidx.compose.**
