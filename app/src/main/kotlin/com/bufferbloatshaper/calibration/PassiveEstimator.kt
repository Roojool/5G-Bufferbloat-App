package com.bufferbloatshaper.calibration

import android.util.Log
import java.util.LinkedList

/**
 * Rolling capacity-estimate store (§5).
 *
 * Samples are submitted only by an independent physical-network probe. This
 * class deliberately does not observe relay traffic because shaped throughput
 * is not a valid estimate of the underlying network's capacity.
 */
class PassiveEstimator {

    /** A throughput measurement sample. */
    data class ThroughputSample(
        val bytesSec: Long,
        val timestampMs: Long,
        val direction: Direction
    )

    enum class Direction { UPLOAD, DOWNLOAD }

    /** Rolling window of upload throughput samples. */
    private val uploadSamples = LinkedList<ThroughputSample>()

    /** Rolling window of download throughput samples. */
    private val downloadSamples = LinkedList<ThroughputSample>()

    /** Duration of the rolling window in milliseconds (default: 20 minutes per §5). */
    var windowDurationMs: Long = 20 * 60 * 1000L

    /** Maximum samples to keep (prevents unbounded memory). */
    private val maxSamples = 500

    /**
     * Records a capacity sample obtained outside the shaper's own traffic
     * path. Callers are responsible for proving that a sample is an
     * independent physical-network measurement; shaped relay bytes must not
     * be passed here because that creates a rate-collapse feedback loop.
     */
    @Synchronized
    fun recordIndependentSample(
        bytesSec: Long,
        direction: Direction,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        require(bytesSec > 0L) { "Capacity samples must be positive." }
        val samples = when (direction) {
            Direction.UPLOAD -> uploadSamples
            Direction.DOWNLOAD -> downloadSamples
        }
        addSample(ThroughputSample(bytesSec, timestampMs, direction), samples)
    }

    private fun addSample(sample: ThroughputSample, samples: LinkedList<ThroughputSample>) {
        samples.addLast(sample)
        // Trim old samples outside the window
        val cutoff = System.currentTimeMillis() - windowDurationMs
        while (samples.isNotEmpty() && samples.first.timestampMs < cutoff) {
            samples.removeFirst()
        }
        // Also trim if too many samples
        while (samples.size > maxSamples) {
            samples.removeFirst()
        }
    }

    /**
     * Get the Nth percentile of the rolling window (§5).
     *
     * Per the plan: "Target the ~20th–25th percentile of your last N samples,
     * not the average. The goal is 'safely below what I can realistically
     * expect soon.'"
     *
     * @param percentile The target percentile (e.g., 25 for 25th percentile).
     * @param direction Upload or download.
     * @return Estimated throughput in bytes/sec at the given percentile,
     *         or 0 if not enough samples.
     */
    @Synchronized
    fun getPercentileBytesSec(percentile: Int, direction: Direction): Long {
        val samples = when (direction) {
            Direction.UPLOAD -> uploadSamples
            Direction.DOWNLOAD -> downloadSamples
        }

        if (samples.size < 3) return 0L // Not enough data

        val sorted = samples.map { it.bytesSec }.sorted()
        val index = ((percentile / 100.0) * (sorted.size - 1)).toInt()
            .coerceIn(0, sorted.size - 1)

        return sorted[index]
    }

    /**
     * Get the current mean throughput estimate.
     */
    @Synchronized
    fun getMeanBytesSec(direction: Direction): Long {
        val samples = when (direction) {
            Direction.UPLOAD -> uploadSamples
            Direction.DOWNLOAD -> downloadSamples
        }

        if (samples.isEmpty()) return 0L
        return samples.map { it.bytesSec }.average().toLong()
    }

    /** Number of samples in the rolling window. */
    @Synchronized
    fun sampleCount(direction: Direction): Int {
        return when (direction) {
            Direction.UPLOAD -> uploadSamples.size
            Direction.DOWNLOAD -> downloadSamples.size
        }
    }

    /**
     * Clear all samples. Called when network state changes significantly
     * and historical data is no longer relevant.
     */
    @Synchronized
    fun clearSamples() {
        uploadSamples.clear()
        downloadSamples.clear()
        Log.d(TAG, "Passive estimator samples cleared")
    }

    companion object {
        private const val TAG = "PassiveEstimator"
    }
}
