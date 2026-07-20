package com.bufferbloatshaper.model

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
    val isActive: Boolean = false
) {
    companion object {
        /** Convert Mbps to bytes/sec */
        fun mbpsToBytesSec(mbps: Double): Long = (mbps * 1_000_000 / 8).toLong()

        /** Convert bytes/sec to Mbps */
        fun bytesSecToMbps(bytesSec: Long): Double = bytesSec * 8.0 / 1_000_000
    }
}
