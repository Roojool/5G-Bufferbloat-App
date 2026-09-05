package com.bufferbloatshaper.model

/**
 * A conservative preset for the local shaper. A preset never discovers or
 * changes modem, carrier, band, or 5G settings; it only chooses local queue
 * settings.
 */
enum class ShaperProfile {
    SAFE,
    BALANCED,
    MAXIMUM_THROUGHPUT,
    CUSTOM
}

/**
 * Android VPN routing modes. Android permits either an allow-list or a
 * disallow-list, never both at once.
 */
enum class AppRoutingMode {
    ALL_APPS,
    ONLY_SELECTED_APPS,
    EXCLUDE_SELECTED_APPS
}

/** Local-only per-app routing policy, persisted as package names. */
data class AppRoutingPolicy(
    val mode: AppRoutingMode = AppRoutingMode.ALL_APPS,
    val packageNames: Set<String> = emptySet()
) {
    private fun enteredPackages(): Set<String> = packageNames
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun normalizedPackages(): Set<String> = packageNames
        .map { it.trim() }
        .filter { PACKAGE_NAME.matches(it) }
        .toSortedSet()

    fun validationError(): String? = when {
        enteredPackages().any { !PACKAGE_NAME.matches(it) } ->
            "Every app routing entry must be a valid Android package name."
        mode != AppRoutingMode.ALL_APPS && normalizedPackages().isEmpty() ->
            "Choose at least one valid Android package name for the app routing policy."
        else -> null
    }

    companion object {
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

/**
 * Central configuration for the traffic shaper.
 * All rates are in bytes per second internally; UI converts to/from Mbps.
 */
data class ShaperConfig(
    /** Upload rate limit in bytes/sec. 0 = unlimited (shaping disabled for egress). */
    val egressRateBytesPerSec: Long = 0L,

    /** Download rate limit in bytes/sec. 0 = unlimited (shaping disabled for ingress). */
    val ingressRateBytesPerSec: Long = 0L,

    /** Whether auto-calibration is enabled (Phase 2). When false, uses manual rates above. */
    val autoCalibrationEnabled: Boolean = false,

    /**
     * Headroom factor applied to calibrated rate. 0.85 = use 85% of estimated capacity.
     * Per §5: apply 80–90% headroom on top of the 20th–25th percentile estimate.
     */
    val headroomFactor: Double = 0.85,

    /** Whether adaptive smart mode is enabled (Phase 4). */
    val smartModeEnabled: Boolean = false,

    /** CoDel target sojourn time in milliseconds (RFC 8289 default: 5ms). */
    val codelTargetMs: Long = 5L,

    /** CoDel interval in milliseconds (RFC 8289 default: 100ms). */
    val codelIntervalMs: Long = 100L,

    /** Number of fair-queue buckets for per-flow scheduling. */
    val fqBuckets: Int = 1024,

    /** Token bucket burst allowance as a fraction of rate (e.g., 0.02 = 20ms worth). */
    val burstFraction: Double = 0.02,

    /** Whether the shaper VPN is currently active. */
    val isActive: Boolean = false,

    /** Safe preset selected by the user. */
    val profile: ShaperProfile = ShaperProfile.BALANCED,

    /** Which apps enter Android's VPN routing table. */
    val appRoutingPolicy: AppRoutingPolicy = AppRoutingPolicy(),

    /** Requested IPv4 TUN MTU. Kept conservative until native IPv6 support exists. */
    val mtu: Int = DEFAULT_MTU
) {
    /**
     * Validation performed before Android's VPN interface is established.
     * A failed validation must leave the device's normal network untouched.
     */
    fun validationErrors(): List<String> = buildList {
        if (egressRateBytesPerSec <= 0L) add("Set a positive upload limit before starting shaping.")
        if (ingressRateBytesPerSec <= 0L) add("Set a positive download limit before starting shaping.")
        if (headroomFactor !in MIN_HEADROOM..MAX_HEADROOM) add("Headroom must be between 70% and 95%.")
        if (codelTargetMs !in 1L..100L) add("AQM target must be between 1 and 100 ms.")
        if (codelIntervalMs !in 10L..1_000L) add("AQM interval must be between 10 and 1000 ms.")
        if (fqBuckets !in 1..4_096) add("Fair-queue bucket count is outside the supported range.")
        if (burstFraction !in 0.001..0.25) add("Burst fraction is outside the supported range.")
        if (mtu !in MIN_MTU..MAX_MTU) add("MTU must be between $MIN_MTU and $MAX_MTU bytes.")
        appRoutingPolicy.validationError()?.let(::add)
    }

    fun withProfile(newProfile: ShaperProfile): ShaperConfig = when (newProfile) {
        ShaperProfile.SAFE -> copy(
            profile = newProfile,
            headroomFactor = 0.80,
            codelTargetMs = 5L,
            codelIntervalMs = 100L,
            burstFraction = 0.01
        )
        ShaperProfile.BALANCED -> copy(
            profile = newProfile,
            headroomFactor = 0.85,
            codelTargetMs = 5L,
            codelIntervalMs = 100L,
            burstFraction = 0.02
        )
        ShaperProfile.MAXIMUM_THROUGHPUT -> copy(
            profile = newProfile,
            headroomFactor = 0.93,
            codelTargetMs = 10L,
            codelIntervalMs = 150L,
            burstFraction = 0.03
        )
        ShaperProfile.CUSTOM -> copy(profile = newProfile)
    }

    companion object {
        const val DEFAULT_MTU = 1_400
        const val MIN_MTU = 1_280
        const val MAX_MTU = 1_500
        const val MIN_HEADROOM = 0.70
        const val MAX_HEADROOM = 0.95

        /** Convert Mbps to bytes/sec */
        fun mbpsToBytesSec(mbps: Double): Long =
            (mbps.coerceAtLeast(0.0) * 1_000_000 / 8).toLong()

        /** Convert bytes/sec to Mbps */
        fun bytesSecToMbps(bytesSec: Long): Double = bytesSec * 8.0 / 1_000_000
    }
}
