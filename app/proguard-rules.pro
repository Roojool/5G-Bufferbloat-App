# Proguard rules for BufferbloatShaper

# Keep VPN service
-keep class com.bufferbloatshaper.vpn.ShaperVpnService { *; }

# Keep data classes used for serialization
-keep class com.bufferbloatshaper.model.** { *; }
-keep class com.bufferbloatshaper.validation.TestResult { *; }
-keep class com.bufferbloatshaper.validation.TestResult$Grade { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { *; }

# AndroidX Compose
-dontwarn androidx.compose.**
