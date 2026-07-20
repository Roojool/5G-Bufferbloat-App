package com.bufferbloatshaper.util

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*

/**
 * Battery overhead monitor (Phase 5, §8).
 *
 * Tracks CPU time consumed by the shaper service and estimates additional
 * battery drain. Per §8: "Additional battery overhead from the always-on
 * relay kept to a low single-digit percentage during active data use."
 *
 * Budget for CPU cost explicitly rather than discover it late.
 */
class BatteryMonitor(private val context: Context) {

    /** Rolling window of CPU usage samples. */
    private data class CpuSample(
        val cpuTimeMs: Long,
        val wallTimeMs: Long,
        val timestampMs: Long
    )

    private val samples = mutableListOf<CpuSample>()
    private val maxSamples = 60 // 5 minutes at 5-second intervals
    private var monitoringJob: Job? = null

    /** Last measured CPU usage ratio (0.0–1.0). */
    @Volatile
    var cpuUsageRatio: Double = 0.0
        private set

    /** Estimated battery drain percentage (additional, from shaper). */
    @Volatile
    var estimatedBatteryDrainPercent: Double = 0.0
        private set

    /** Whether monitoring is active. */
    @Volatile
    var isMonitoring = false
        private set

    /** Current battery level (0–100). */
    val currentBatteryLevel: Int
        get() {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        }

    /** Whether device is currently charging. */
    val isCharging: Boolean
        get() {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            return bm?.isCharging ?: false
        }

    /**
     * Start monitoring CPU usage of the shaper process.
     */
    fun start(scope: CoroutineScope) {
        if (isMonitoring) return
        isMonitoring = true

        monitoringJob = scope.launch(Dispatchers.IO) {
            var lastCpuTime = Process.getElapsedCpuTime()
            var lastWallTime = System.currentTimeMillis()

            while (isActive && isMonitoring) {
                delay(5000) // Sample every 5 seconds

                val currentCpuTime = Process.getElapsedCpuTime()
                val currentWallTime = System.currentTimeMillis()

                val cpuDelta = currentCpuTime - lastCpuTime
                val wallDelta = currentWallTime - lastWallTime

                if (wallDelta > 0) {
                    val sample = CpuSample(cpuDelta, wallDelta, currentWallTime)
                    samples.add(sample)
                    if (samples.size > maxSamples) samples.removeAt(0)

                    // Calculate rolling average CPU usage
                    val totalCpu = samples.sumOf { it.cpuTimeMs }
                    val totalWall = samples.sumOf { it.wallTimeMs }
                    cpuUsageRatio = if (totalWall > 0) {
                        totalCpu.toDouble() / totalWall
                    } else 0.0

                    // Rough battery drain estimate:
                    // Assume screen-on power ~3W baseline, CPU at 100% adds ~2W.
                    // So CPU ratio × 2W / 3W ≈ additional drain as fraction of baseline.
                    // Express as percentage.
                    estimatedBatteryDrainPercent = cpuUsageRatio * 100 * 0.67

                    // Warn if exceeding target
                    if (estimatedBatteryDrainPercent > 5.0) {
                        Log.w(TAG, "Battery overhead exceeding target: " +
                                "${estimatedBatteryDrainPercent.format(1)}% " +
                                "(CPU usage: ${(cpuUsageRatio * 100).format(1)}%)")
                    }
                }

                lastCpuTime = currentCpuTime
                lastWallTime = currentWallTime
            }
        }

        Log.d(TAG, "Battery monitoring started")
    }

    /**
     * Stop monitoring.
     */
    fun stop() {
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringJob = null
        samples.clear()
        Log.d(TAG, "Battery monitoring stopped")
    }

    /**
     * Get a summary for the UI.
     */
    data class BatteryStats(
        val cpuUsagePercent: Double,
        val estimatedAdditionalDrainPercent: Double,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val isWithinTarget: Boolean
    )

    fun getStats(): BatteryStats = BatteryStats(
        cpuUsagePercent = cpuUsageRatio * 100,
        estimatedAdditionalDrainPercent = estimatedBatteryDrainPercent,
        batteryLevel = currentBatteryLevel,
        isCharging = isCharging,
        isWithinTarget = estimatedBatteryDrainPercent < 5.0
    )

    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)

    companion object {
        private const val TAG = "BatteryMonitor"
    }
}
