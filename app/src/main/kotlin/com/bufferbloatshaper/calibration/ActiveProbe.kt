package com.bufferbloatshaper.calibration

import android.util.Log
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * Active throughput probe — triggered by network state changes (§5).
 *
 * Performs a short, lightweight measurement burst to estimate current
 * network capacity. Triggered on real state-change signals — RAT change,
 * cell handover, or a large passive-estimate swing — rather than on a
 * fixed timer.
 *
 * "This mirrors exactly the workflow you were already doing manually
 * (re-running Waveform after conditions changed), just automated."
 */
class ActiveProbe {

    /** Result of an active probe. */
    data class ProbeResult(
        val uploadBytesSec: Long,
        val downloadBytesSec: Long,
        val latencyMs: Long,
        val timestampMs: Long = System.currentTimeMillis(),
        val triggerReason: String
    )

    /** Callback for probe completion. */
    var onProbeComplete: ((ProbeResult) -> Unit)? = null

    /** Whether a probe is currently running. */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** Well-known endpoints for measurement. */
    private val probeUrls = listOf(
        "https://www.google.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://clients3.google.com/generate_204"
    )

    /** Download test URL (small, well-cached file). */
    private val downloadTestUrl = "https://www.google.com/images/phd/px.gif"

    /**
     * Run an active probe. This performs:
     * 1. Latency measurement (HTTP HEAD to connectivity check endpoints)
     * 2. Download throughput estimate (small file download timing)
     *
     * Results are fed into the CalibrationEngine's rolling window.
     *
     * @param reason Why this probe was triggered (for logging/UI).
     */
    suspend fun runProbe(reason: String): ProbeResult? {
        if (isRunning) {
            Log.d(TAG, "Probe already running, skipping")
            return null
        }

        isRunning = true
        Log.d(TAG, "Starting active probe: $reason")

        return try {
            withContext(Dispatchers.IO) {
                // 1. Measure latency (average of multiple pings)
                val latencyMs = measureLatency()

                // 2. Measure download throughput
                val downloadBytesSec = measureDownload()

                // 3. Estimate upload based on latency (rough heuristic)
                // A proper upload test would require a server endpoint to POST to.
                // For now, estimate conservatively from download measurement.
                val uploadBytesSec = (downloadBytesSec * 0.3).toLong() // conservative

                val result = ProbeResult(
                    uploadBytesSec = uploadBytesSec,
                    downloadBytesSec = downloadBytesSec,
                    latencyMs = latencyMs,
                    triggerReason = reason
                )

                Log.d(TAG, "Active probe complete: " +
                        "dl=${downloadBytesSec * 8 / 1_000_000.0} Mbps, " +
                        "ul≈${uploadBytesSec * 8 / 1_000_000.0} Mbps, " +
                        "latency=${latencyMs}ms")

                onProbeComplete?.invoke(result)
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Active probe failed", e)
            null
        } finally {
            isRunning = false
        }
    }

    /**
     * Measure latency by timing HTTP HEAD requests to well-known endpoints.
     * Returns average latency in milliseconds.
     */
    private fun measureLatency(): Long {
        val latencies = mutableListOf<Long>()

        for (url in probeUrls) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.instanceFollowRedirects = false

                val start = System.currentTimeMillis()
                conn.connect()
                val responseCode = conn.responseCode
                val elapsed = System.currentTimeMillis() - start

                conn.disconnect()

                if (responseCode in 200..399) {
                    latencies.add(elapsed)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Latency probe failed for $url", e)
            }
        }

        return if (latencies.isNotEmpty()) {
            latencies.sorted().let { sorted ->
                // Use median to avoid outlier skew
                sorted[sorted.size / 2]
            }
        } else {
            -1L // Probe failed
        }
    }

    /**
     * Measure download throughput by timing a small file download.
     * Returns estimated throughput in bytes/sec.
     */
    private fun measureDownload(): Long {
        return try {
            val conn = URL(downloadTestUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10000

            val start = System.currentTimeMillis()
            conn.connect()

            val inputStream = conn.inputStream
            val buffer = ByteArray(8192)
            var totalBytes = 0L
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                totalBytes += bytesRead
            }

            val elapsed = System.currentTimeMillis() - start
            inputStream.close()
            conn.disconnect()

            if (elapsed > 0 && totalBytes > 0) {
                (totalBytes * 1000 / elapsed) // bytes/sec
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download probe failed", e)
            0L
        }
    }

    companion object {
        private const val TAG = "ActiveProbe"
    }
}
