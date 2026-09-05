package com.bufferbloatshaper.calibration

import android.content.Context
import android.util.Log
import com.bufferbloatshaper.model.CalibrationState
import com.bufferbloatshaper.shaping.EgressShaper
import com.bufferbloatshaper.shaping.IngressController
import kotlinx.coroutines.CoroutineScope

/**
 * Auto-calibration engine — Phase 2, §5.
 *
 * Holds independently measured physical-network capacity samples. It does
 * not consume bytes observed after shaping: doing so feeds the app's own rate
 * limit back into calibration and can progressively collapse throughput.
 *
 * Rate calculation (§5):
 *   1. Take the 20th–25th percentile of the rolling window (not the mean)
 *   2. Apply 80–90% headroom factor on top of that
 *   Result: "safely below what I can realistically expect soon"
 *
 * "Two tests on the same phone, same location, same day, showed 92.7 vs
 * 20.3 Mbps down and 13.3 vs 16.2 Mbps up. A simple arithmetic mean across
 * samples that different produces a baseline that's either dangerously
 * optimistic or needlessly conservative."
 */
class CalibrationEngine(
    context: Context,
    private val scope: CoroutineScope
) {
    val passiveEstimator = PassiveEstimator()
    val networkMonitor = NetworkStateMonitor(context)

    /** The egress shaper to update with new rate targets. */
    var egressShaper: EgressShaper? = null

    /** The ingress controller to update with new rate targets. */
    var ingressController: IngressController? = null

    /** Target percentile for rate estimation (§5: 20th–25th). */
    var targetPercentile: Int = 25

    /** Headroom factor (§5: 80–90%, default 85%). */
    var headroomFactor: Double = 0.85

    /** Current calibration state (exposed for UI). */
    @Volatile
    var state = CalibrationState()
        private set

    /** Whether calibration is running. */
    @Volatile
    var isRunning = false
        private set

    /**
     * Start network-state monitoring only. Automatic probing is deliberately
     * disabled: a verified implementation must submit independent capacity
     * samples through [recordIndependentPhysicalProbe].
     */
    fun start() {
        if (isRunning) return
        isRunning = true

        networkMonitor.onNetworkStateChanged = { reason, networkType ->
            Log.d(TAG, "Network state changed: $reason ($networkType)")
            state = state.copy(networkType = networkType)

            // Clear old samples on major network change (WiFi ↔ cellular)
            if (reason.contains("Network type changed")) {
                passiveEstimator.clearSamples()
            }

        }

        networkMonitor.start()
        state = state.copy(
            networkType = networkMonitor.currentNetworkType,
            lastRecalibrationReason = "Awaiting an independent physical-network probe"
        )
        Log.d(TAG, "Calibration monitor started; automatic probing is disabled")
    }

    /**
     * Stop the calibration engine.
     */
    fun stop() {
        isRunning = false
        networkMonitor.stop()
        Log.d(TAG, "Calibration engine stopped")
    }

    /**
     * Accept an independently measured upload/download capacity sample.
     * This is intentionally the only path that can influence applied rates.
     */
    fun recordIndependentPhysicalProbe(
        uploadBytesSec: Long,
        downloadBytesSec: Long,
        reason: String,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        if (uploadBytesSec > 0L) {
            passiveEstimator.recordIndependentSample(
                uploadBytesSec,
                PassiveEstimator.Direction.UPLOAD,
                timestampMs
            )
        }
        if (downloadBytesSec > 0L) {
            passiveEstimator.recordIndependentSample(
                downloadBytesSec,
                PassiveEstimator.Direction.DOWNLOAD,
                timestampMs
            )
        }
        recalculateRates(reason)
    }

    /**
     * Recalculate shaping rates from the current passive estimator data (§5).
     *
     * Formula: rate = percentile(samples, 25th) × headroomFactor
     */
    private fun recalculateRates(reason: String) {
        val uploadPercentile = passiveEstimator.getPercentileBytesSec(
            targetPercentile, PassiveEstimator.Direction.UPLOAD
        )
        val downloadPercentile = passiveEstimator.getPercentileBytesSec(
            targetPercentile, PassiveEstimator.Direction.DOWNLOAD
        )

        if (uploadPercentile == 0L && downloadPercentile == 0L) {
            // Not enough data yet
            return
        }

        val uploadTarget = (uploadPercentile * headroomFactor).toLong()
        val downloadTarget = (downloadPercentile * headroomFactor).toLong()

        // Only update if values have meaningfully changed (>5% shift)
        val oldUpload = state.appliedUploadRateBytesSec
        val oldDownload = state.appliedDownloadRateBytesSec

        val uploadChanged = oldUpload == 0L ||
                kotlin.math.abs(uploadTarget - oldUpload).toDouble() / oldUpload > 0.05
        val downloadChanged = oldDownload == 0L ||
                kotlin.math.abs(downloadTarget - oldDownload).toDouble() / oldDownload > 0.05

        if (uploadChanged || downloadChanged) {
            state = state.copy(
                estimatedUploadBytesSec = uploadPercentile,
                estimatedDownloadBytesSec = downloadPercentile,
                appliedUploadRateBytesSec = uploadTarget,
                appliedDownloadRateBytesSec = downloadTarget,
                sampleCount = passiveEstimator.sampleCount(PassiveEstimator.Direction.UPLOAD) +
                        passiveEstimator.sampleCount(PassiveEstimator.Direction.DOWNLOAD),
                currentPercentile = targetPercentile,
                headroomFactor = headroomFactor,
                networkType = networkMonitor.currentNetworkType,
                lastUpdateMs = System.currentTimeMillis(),
                lastRecalibrationReason = reason
            )

            // Apply to shapers
            if (uploadTarget > 0) {
                egressShaper?.updateRate(uploadTarget.toDouble())
            }
            if (downloadTarget > 0) {
                ingressController?.setTargetRate(downloadTarget)
            }

            Log.d(TAG, "Rates recalculated ($reason): " +
                    "ul=${uploadTarget * 8 / 1_000_000.0} Mbps " +
                    "(${targetPercentile}th pctl=${uploadPercentile * 8 / 1_000_000.0} × $headroomFactor), " +
                    "dl=${downloadTarget * 8 / 1_000_000.0} Mbps " +
                    "(${targetPercentile}th pctl=${downloadPercentile * 8 / 1_000_000.0} × $headroomFactor)")
        }
    }

    companion object {
        private const val TAG = "CalibrationEngine"
    }
}
