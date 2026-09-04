package com.bufferbloatshaper.validation

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Built-in bufferbloat test — §6.
 *
 * Bakes a simple idle-vs-loaded latency test directly into the app:
 *   1. Measure idle latency (ping under idle conditions)
 *   2. Saturate the connection (upload + download simultaneously)
 *   3. Measure loaded latency (ping while saturating)
 *   4. Compare: added latency = loaded - idle
 *
 * "Same general methodology behind every public bufferbloat test."
 *
 * Runs automatically before and after enabling shaping, and shows
 * the before/after numbers side by side. Does double duty: genuinely
 * useful feature AND regression test during development.
 */
class BufferbloatTest {

    /** Callback for progress updates. */
    var onProgress: ((phase: String, progressPercent: Int) -> Unit)? = null

    /** Whether a test is currently running. */
    @Volatile
    var isRunning = false
        private set

    /** Latency measurement endpoints. */
    private val pingTargets = listOf(
        "8.8.8.8",       // Google DNS
        "1.1.1.1",       // Cloudflare DNS
        "208.67.222.222" // OpenDNS
    )

    /** Download saturation endpoints. */
    private val downloadUrls = listOf(
        "https://speed.cloudflare.com/__down?bytes=10000000",
        "https://www.google.com/images/phd/px.gif"
    )

    /**
     * Run the full bufferbloat test.
     *
     * @param shapingActive Whether shaping is currently enabled (for labeling results).
     * @return Test result, or null if the test failed.
     */
    suspend fun runTest(shapingActive: Boolean): TestResult? {
        if (isRunning) return null
        isRunning = true

        return try {
            withContext(Dispatchers.IO) {
                // Phase 1: Measure idle latency
                onProgress?.invoke("Measuring idle latency...", 10)
                val idleLatencies = measureLatencies(count = 10, delayMs = 200)
                val idleLatency = idleLatencies.median()

                if (idleLatency < 0) {
                    Log.e(TAG, "Failed to measure idle latency")
                    return@withContext null
                }

                onProgress?.invoke("Idle latency: ${idleLatency.toInt()}ms", 30)
                Log.d(TAG, "Idle latency: ${idleLatency}ms (samples: $idleLatencies)")

                // Phase 2: Start saturation traffic
                onProgress?.invoke("Saturating connection...", 40)
                val saturationJob = launch { saturateConnection() }

                // Give saturation time to fill buffers
                delay(2000)

                // Phase 3: Measure loaded latency
                onProgress?.invoke("Measuring loaded latency...", 60)
                val loadedLatencies = measureLatencies(count = 10, delayMs = 200)
                val loadedLatency = loadedLatencies.median()

                // Stop saturation
                saturationJob.cancel()

                if (loadedLatency < 0) {
                    Log.e(TAG, "Failed to measure loaded latency")
                    return@withContext null
                }

                onProgress?.invoke("Loaded latency: ${loadedLatency.toInt()}ms", 90)
                Log.d(TAG, "Loaded latency: ${loadedLatency}ms (samples: $loadedLatencies)")

                // Phase 4: Calculate results
                val addedLatency = (loadedLatency - idleLatency).coerceAtLeast(0.0)
                val grade = TestResult.gradeFromAddedLatency(addedLatency)

                val result = TestResult(
                    idleLatencyMs = idleLatency,
                    loadedLatencyMs = loadedLatency,
                    addedLatencyMs = addedLatency,
                    grade = grade,
                    shapingActive = shapingActive
                )

                onProgress?.invoke("Test complete — Grade: ${grade.label}", 100)
                Log.d(TAG, "Test complete: idle=${idleLatency}ms, loaded=${loadedLatency}ms, " +
                        "added=${addedLatency}ms, grade=${grade.label}")

                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bufferbloat test failed", e)
            null
        } finally {
            isRunning = false
        }
    }

    /**
     * Measure latency by timing ICMP-style requests.
     * Uses HTTP HEAD as a fallback since raw ICMP requires root on Android.
     */
    private suspend fun measureLatencies(count: Int, delayMs: Long): List<Double> {
        val latencies = mutableListOf<Double>()

        repeat(count) {
            val target = pingTargets[it % pingTargets.size]
            val latency = measureSingleLatency(target)
            if (latency > 0) {
                latencies.add(latency)
            }
            delay(delayMs)
        }

        return latencies
    }

    /**
     * Measure a single latency sample.
     * Tries DNS resolution timing first, then falls back to HTTP HEAD.
     */
    private fun measureSingleLatency(target: String): Double {
        return try {
            // Method 1: TCP connect timing (more accurate than HTTP HEAD)
            val start = System.nanoTime()
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(target, 53), 1000)
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            socket.close()
            elapsed
        } catch (e: Exception) {
            try {
                // Method 2: HTTP HEAD as fallback
                val url = URL("https://www.google.com/generate_204")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val start = System.nanoTime()
                conn.connect()
                conn.responseCode
                val elapsed = (System.nanoTime() - start) / 1_000_000.0
                conn.disconnect()
                elapsed
            } catch (e2: Exception) {
                -1.0
            }
        }
    }

    /**
     * Saturate the connection by downloading continuously.
     * Runs until cancelled.
     */
    private suspend fun saturateConnection() {
        coroutineScope {
            repeat(3) { i ->
                launch(Dispatchers.IO) {
                    var totalBytesStreamed = 0L
                    val startTime = System.currentTimeMillis()
                    while (isActive) {
                        try {
                            val urlStr = downloadUrls[i % downloadUrls.size]
                            Log.d(TAG, "Saturate worker #$i starting download from $urlStr")
                            val conn = URL(urlStr).openConnection() as HttpURLConnection
                            conn.connectTimeout = 5000
                            conn.readTimeout = 10000
                            conn.connect()

                            val buffer = ByteArray(8192)
                            val input = conn.inputStream
                            var readBytes = 0
                            while (isActive && input.read(buffer).also { readBytes = it } != -1) {
                                totalBytesStreamed += readBytes
                                if (totalBytesStreamed % (1024 * 1024) < 8192) {
                                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                                    val mbps = if (elapsedSec > 0) (totalBytesStreamed * 8.0 / 1_000_000.0) / elapsedSec else 0.0
                                    Log.d(TAG, "Worker #$i downloaded ${totalBytesStreamed / (1024 * 1024)} MB in ${"%.2f".format(elapsedSec)}s (${"%.2f".format(mbps)} Mbps)")
                                }
                            }
                            input.close()
                            conn.disconnect()
                        } catch (e: Exception) {
                            Log.w(TAG, "Saturate worker #$i encountered error: ${e.message}")
                            delay(100)
                        }
                    }
                }
            }
        }
    }

    /** Calculate median of a list. */
    private fun List<Double>.median(): Double {
        if (isEmpty()) return -1.0
        val sorted = sorted()
        val mid = size / 2
        return if (size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    companion object {
        private const val TAG = "BufferbloatTest"
    }
}
