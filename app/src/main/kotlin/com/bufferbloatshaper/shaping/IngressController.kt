package com.bufferbloatshaper.shaping

import android.util.Log
import com.bufferbloatshaper.vpn.TcpRelay

/**
 * Ingress (download) controller — Phase 3, §4.
 *
 * Controls download throughput by dynamically adjusting the TCP receive window
 * advertised to remote servers. Because TcpRelay fully terminates the TCP
 * connection locally (three-way handshake, sequence numbers, the works),
 * we control the receive window on the relay-to-server connection.
 *
 * This is standard TCP flow control, not a hack — it directly caps how much
 * data the server is allowed to have in flight, which combined with RTT
 * caps effective throughput.
 *
 * Per §4: This operates at the transport layer, entirely below TLS — we're
 * not touching encrypted payload, so this works without breaking HTTPS.
 *
 * IMPORTANT (§4): This ONLY works for TCP. QUIC/UDP deliberately moved
 * flow-control inside its encrypted transport, making middlebox control
 * impossible without full TLS termination (MITM). We explicitly do NOT
 * attempt QUIC/UDP download shaping.
 */
class IngressController(
    private val tcpRelay: TcpRelay
) {
    /** Current target download rate in bytes/sec. */
    @Volatile
    var targetRateBytesSec: Long = 0L
        private set

    /** Estimated RTT in milliseconds (updated from TcpRelay observations). */
    @Volatile
    var estimatedRttMs: Double = 50.0 // conservative default

    /** Whether ingress control is active. */
    @Volatile
    var isActive: Boolean = false
        private set

    /** Minimum receive window to advertise (don't starve the connection). */
    private val minWindowBytes = 8 * 1024 // 8 KB

    /** Maximum receive window (no throttling). */
    private val maxWindowBytes = 1024 * 1024 // 1 MB

    /**
     * Set the target download rate and enable ingress control.
     *
     * Calculates the optimal receive window as:
     *   window = targetRate × RTT
     *
     * This is the bandwidth-delay product (BDP). By advertising exactly this
     * window, we allow the server to send at our target rate but no faster —
     * because it can't have more than windowSize bytes in flight at any time.
     *
     * @param rateBytesSec Target download rate in bytes per second.
     */
    fun setTargetRate(rateBytesSec: Long) {
        targetRateBytesSec = rateBytesSec

        if (rateBytesSec <= 0) {
            disable()
            return
        }

        isActive = true
        updateReceiveWindow()
        Log.d(TAG, "Ingress target set: ${rateBytesSec * 8 / 1_000_000.0} Mbps " +
                "(RTT=${estimatedRttMs}ms, window=${calculateWindowSize()} bytes)")
    }

    /**
     * Update the estimated RTT and recalculate the receive window.
     * Called periodically from TcpRelay's RTT observations.
     */
    fun updateRtt(rttMs: Double) {
        if (rttMs > 0 && rttMs < 5000) { // sanity check
            // Exponential moving average
            estimatedRttMs = estimatedRttMs * 0.8 + rttMs * 0.2
            if (isActive) {
                updateReceiveWindow()
            }
        }
    }

    /**
     * Calculate the optimal receive window size based on current
     * target rate and RTT (bandwidth-delay product).
     */
    private fun calculateWindowSize(): Int {
        val rttSec = estimatedRttMs / 1000.0
        val bdp = (targetRateBytesSec * rttSec).toLong()
        return bdp.toInt().coerceIn(minWindowBytes, maxWindowBytes)
    }

    /**
     * Apply the calculated receive window to all TCP relay connections.
     */
    private fun updateReceiveWindow() {
        val windowSize = calculateWindowSize()
        tcpRelay.updateReceiveBufferSize(windowSize)
        Log.d(TAG, "Receive window updated: $windowSize bytes " +
                "(rate=${targetRateBytesSec * 8 / 1_000_000.0} Mbps, RTT=${estimatedRttMs}ms)")
    }

    /** Disable ingress control — remove receive window limit. */
    fun disable() {
        isActive = false
        targetRateBytesSec = 0L
        tcpRelay.updateReceiveBufferSize(0) // 0 = no override, use system default
        Log.d(TAG, "Ingress control disabled")
    }

    /** Current state for the UI. */
    data class IngressStats(
        val isActive: Boolean,
        val targetRateBytesSec: Long,
        val estimatedRttMs: Double,
        val windowSizeBytes: Int
    )

    fun getStats(): IngressStats = IngressStats(
        isActive = isActive,
        targetRateBytesSec = targetRateBytesSec,
        estimatedRttMs = estimatedRttMs,
        windowSizeBytes = if (isActive) calculateWindowSize() else 0
    )

    companion object {
        private const val TAG = "IngressController"
    }
}
