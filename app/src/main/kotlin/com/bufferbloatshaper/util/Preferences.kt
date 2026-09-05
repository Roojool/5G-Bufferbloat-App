package com.bufferbloatshaper.util

import android.content.Context
import android.content.SharedPreferences
import com.bufferbloatshaper.model.AppRoutingMode
import com.bufferbloatshaper.model.AppRoutingPolicy
import com.bufferbloatshaper.model.ShaperConfig
import com.bufferbloatshaper.model.ShaperProfile

/**
 * Thin wrapper around SharedPreferences for persisting shaper configuration.
 */
class Preferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveConfig(config: ShaperConfig) {
        prefs.edit().apply {
            putLong(KEY_EGRESS_RATE, config.egressRateBytesPerSec)
            putLong(KEY_INGRESS_RATE, config.ingressRateBytesPerSec)
            putBoolean(KEY_AUTO_CALIBRATION, config.autoCalibrationEnabled)
            putFloat(KEY_HEADROOM, config.headroomFactor.toFloat())
            putBoolean(KEY_SMART_MODE, config.smartModeEnabled)
            putLong(KEY_CODEL_TARGET, config.codelTargetMs)
            putLong(KEY_CODEL_INTERVAL, config.codelIntervalMs)
            putInt(KEY_FQ_BUCKETS, config.fqBuckets)
            putFloat(KEY_BURST_FRACTION, config.burstFraction.toFloat())
            putString(KEY_PROFILE, config.profile.name)
            putString(KEY_APP_ROUTING_MODE, config.appRoutingPolicy.mode.name)
            putStringSet(KEY_APP_ROUTING_PACKAGES, config.appRoutingPolicy.normalizedPackages())
            putInt(KEY_MTU, config.mtu)
            apply()
        }
    }

    fun loadConfig(): ShaperConfig {
        return ShaperConfig(
            egressRateBytesPerSec = prefs.getLong(KEY_EGRESS_RATE, 0L),
            ingressRateBytesPerSec = prefs.getLong(KEY_INGRESS_RATE, 0L),
            autoCalibrationEnabled = prefs.getBoolean(KEY_AUTO_CALIBRATION, false),
            headroomFactor = prefs.getFloat(KEY_HEADROOM, 0.85f).toDouble(),
            smartModeEnabled = prefs.getBoolean(KEY_SMART_MODE, false),
            codelTargetMs = prefs.getLong(KEY_CODEL_TARGET, 5L),
            codelIntervalMs = prefs.getLong(KEY_CODEL_INTERVAL, 100L),
            fqBuckets = prefs.getInt(KEY_FQ_BUCKETS, 1024),
            burstFraction = prefs.getFloat(KEY_BURST_FRACTION, 0.02f).toDouble(),
            profile = prefs.getEnum(KEY_PROFILE, ShaperProfile.BALANCED),
            appRoutingPolicy = AppRoutingPolicy(
                mode = prefs.getEnum(KEY_APP_ROUTING_MODE, AppRoutingMode.ALL_APPS),
                packageNames = prefs.getStringSet(KEY_APP_ROUTING_PACKAGES, emptySet()).orEmpty()
            ),
            mtu = prefs.getInt(KEY_MTU, ShaperConfig.DEFAULT_MTU)
        )
    }

    /** Store the last bufferbloat test results for before/after comparison. */
    fun saveTestResult(key: String, idleLatencyMs: Double, loadedLatencyMs: Double) {
        prefs.edit().apply {
            putFloat("${KEY_TEST_PREFIX}${key}_idle", idleLatencyMs.toFloat())
            putFloat("${KEY_TEST_PREFIX}${key}_loaded", loadedLatencyMs.toFloat())
            putLong("${KEY_TEST_PREFIX}${key}_time", System.currentTimeMillis())
            apply()
        }
    }

    fun getTestResult(key: String): Triple<Double, Double, Long>? {
        val time = prefs.getLong("${KEY_TEST_PREFIX}${key}_time", 0L)
        if (time == 0L) return null
        return Triple(
            prefs.getFloat("${KEY_TEST_PREFIX}${key}_idle", 0f).toDouble(),
            prefs.getFloat("${KEY_TEST_PREFIX}${key}_loaded", 0f).toDouble(),
            time
        )
    }

    companion object {
        private const val PREFS_NAME = "bufferbloat_shaper_prefs"
        private const val KEY_EGRESS_RATE = "egress_rate"
        private const val KEY_INGRESS_RATE = "ingress_rate"
        private const val KEY_AUTO_CALIBRATION = "auto_calibration"
        private const val KEY_HEADROOM = "headroom"
        private const val KEY_SMART_MODE = "smart_mode"
        private const val KEY_CODEL_TARGET = "codel_target"
        private const val KEY_CODEL_INTERVAL = "codel_interval"
        private const val KEY_FQ_BUCKETS = "fq_buckets"
        private const val KEY_BURST_FRACTION = "burst_fraction"
        private const val KEY_PROFILE = "profile"
        private const val KEY_APP_ROUTING_MODE = "app_routing_mode"
        private const val KEY_APP_ROUTING_PACKAGES = "app_routing_packages"
        private const val KEY_MTU = "mtu"
        private const val KEY_TEST_PREFIX = "test_"
    }
}

private inline fun <reified T : Enum<T>> SharedPreferences.getEnum(key: String, fallback: T): T =
    getString(key, fallback.name)?.let { stored ->
        enumValues<T>().firstOrNull { it.name == stored }
    } ?: fallback
