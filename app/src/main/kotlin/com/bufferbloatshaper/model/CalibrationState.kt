package com.bufferbloatshaper.model

/**
 * State of the auto-calibration engine (Phase 2).
 * Tracks throughput estimation, probe history, and current targets.
 */
data class CalibrationState(
    /** Current estimated upload capacity in bytes/sec. */
    val estimatedUploadBytesSec: Long = 0L,

    /** Current estimated download capacity in bytes/sec. */
    val estimatedDownloadBytesSec: Long = 0L,

    /** Applied upload shaping rate (after headroom) in bytes/sec. */
    val appliedUploadRateBytesSec: Long = 0L,

    /** Applied download shaping rate (after headroom) in bytes/sec. */
    val appliedDownloadRateBytesSec: Long = 0L,

    /** Number of passive throughput samples in the rolling window. */
    val sampleCount: Int = 0,

    /** Current percentile value being used (e.g., 25th percentile). */
    val currentPercentile: Int = 25,

    /** Current headroom factor being applied. */
    val headroomFactor: Double = 0.85,

    /** Current network type (e.g., "5G", "LTE", "WiFi"). */
    val networkType: String = "Unknown",

    /** Whether an active probe is currently running. */
    val activeProbeRunning: Boolean = false,

    /** Timestamp of last calibration update. */
    val lastUpdateMs: Long = 0L,

    /** Reason for last recalibration (e.g., "RAT change", "passive estimate drift"). */
    val lastRecalibrationReason: String = ""
)
