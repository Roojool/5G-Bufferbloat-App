package com.bufferbloatshaper.shaping

import android.util.Log
import com.bufferbloatshaper.model.FlowType
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Flow classifier for Phase 4 — Adaptive Smart Mode (§7).
 *
 * Classifies flows by observed traffic patterns:
 *   - BULK_TRANSFER: sustained high throughput, large packets
 *   - INTERACTIVE: small packets, high frequency, low inter-arrival variance (gaming)
 *   - VIDEO_CALL: mixed sizes, periodic patterns, SRTP ports
 *   - WEB_BROWSING: bursty short-lived connections
 *   - DNS: port 53
 *
 * Uses heuristic classification based on packet size distribution,
 * inter-arrival times, connection duration, and well-known port ranges.
 */
class FlowClassifier {

    /** Per-flow classification state. */
    private class FlowState {
        var packetCount: Long = 0L
        var totalBytes: Long = 0L
        var lastPacketNs: Long = 0L
        var firstPacketNs: Long = System.nanoTime()
        var smallPacketCount: Long = 0L   // packets < 200 bytes
        var largePacketCount: Long = 0L   // packets > 1000 bytes
        var interArrivalSum: Long = 0L    // sum of inter-arrival times (ns)
        var interArrivalSqSum: Double = 0.0 // sum of squares for variance
        var classification: FlowType = FlowType.UNKNOWN
        var classificationConfidence: Double = 0.0
        var dstPort: Int = 0
    }

    private val flowStates = ConcurrentHashMap<Int, FlowState>()

    /** Minimum packets before attempting classification. */
    private val minPacketsForClassification = 10

    /**
     * Classify a packet's flow. Updates internal state and returns the
     * current classification for this flow.
     *
     * @param packetData Raw packet bytes (IP header + payload).
     * @param flowHash Hash of the 5-tuple.
     * @return Current flow type classification.
     */
    fun classify(packetData: ByteArray, flowHash: Int): FlowType {
        val state = flowStates.getOrPut(flowHash) { FlowState() }
        val now = System.nanoTime()
        val packetSize = packetData.size

        // Update stats
        state.packetCount++
        state.totalBytes += packetSize

        if (packetSize < 200) state.smallPacketCount++
        if (packetSize > 1000) state.largePacketCount++

        // Track inter-arrival times
        if (state.lastPacketNs > 0) {
            val interArrival = now - state.lastPacketNs
            state.interArrivalSum += interArrival
            state.interArrivalSqSum += (interArrival.toDouble() * interArrival.toDouble())
        }
        state.lastPacketNs = now

        // Extract destination port from the IP packet
        if (state.packetCount == 1L && packetData.size >= 24) {
            val buf = ByteBuffer.wrap(packetData)
            val ihl = (buf.get(0).toInt() and 0x0F) * 4
            val protocol = buf.get(9).toInt() and 0xFF
            if ((protocol == 6 || protocol == 17) && packetData.size >= ihl + 4) {
                state.dstPort = buf.getShort(ihl + 2).toInt() and 0xFFFF
            }
        }

        // Quick classification by well-known ports
        if (state.dstPort == 53) {
            state.classification = FlowType.DNS
            state.classificationConfidence = 1.0
            return FlowType.DNS
        }

        // Need enough packets for statistical classification
        if (state.packetCount < minPacketsForClassification) {
            return state.classification
        }

        // Classify based on observed patterns
        state.classification = classifyByPattern(state)
        return state.classification
    }

    private fun classifyByPattern(state: FlowState): FlowType {
        val avgPacketSize = state.totalBytes.toDouble() / state.packetCount
        val smallRatio = state.smallPacketCount.toDouble() / state.packetCount
        val largeRatio = state.largePacketCount.toDouble() / state.packetCount
        val durationSec = (state.lastPacketNs - state.firstPacketNs) / 1_000_000_000.0
        val packetRate = if (durationSec > 0) state.packetCount / durationSec else 0.0

        // Calculate inter-arrival variance
        val avgInterArrival = if (state.packetCount > 1) {
            state.interArrivalSum.toDouble() / (state.packetCount - 1)
        } else 0.0
        val interArrivalVariance = if (state.packetCount > 2) {
            val avgSq = state.interArrivalSqSum / (state.packetCount - 1)
            avgSq - (avgInterArrival * avgInterArrival)
        } else Double.MAX_VALUE

        // Coefficient of variation (normalized variance)
        val cv = if (avgInterArrival > 0) {
            kotlin.math.sqrt(interArrivalVariance.coerceAtLeast(0.0)) / avgInterArrival
        } else Double.MAX_VALUE

        // SRTP/RTP ports for video/voice calls
        val isRtpPort = state.dstPort in 16384..32767

        return when {
            // DNS — already handled above, but just in case
            state.dstPort == 53 -> FlowType.DNS

            // Video/voice call: periodic packets (low CV), mixed sizes, RTP ports
            isRtpPort && cv < 0.5 && packetRate > 20 -> FlowType.VIDEO_CALL

            // Interactive/gaming: small packets, high frequency, very low jitter
            smallRatio > 0.7 && packetRate > 30 && cv < 0.3 && avgPacketSize < 300 ->
                FlowType.INTERACTIVE

            // Bulk transfer: mostly large packets, sustained high throughput
            largeRatio > 0.6 && durationSec > 2.0 && avgPacketSize > 800 ->
                FlowType.BULK_TRANSFER

            // Web browsing: bursty, short-lived, mixed packet sizes
            durationSec < 10.0 && cv > 1.0 -> FlowType.WEB_BROWSING

            // Default: if it's been going long with big packets, it's bulk
            durationSec > 5.0 && avgPacketSize > 500 -> FlowType.BULK_TRANSFER

            else -> FlowType.UNKNOWN
        }
    }

    /**
     * Get current classification for a flow.
     */
    fun getFlowType(flowHash: Int): FlowType {
        return flowStates[flowHash]?.classification ?: FlowType.UNKNOWN
    }

    /**
     * Get all classified flows for stats display.
     */
    fun getAllClassifications(): Map<Int, FlowType> {
        return flowStates.mapValues { it.value.classification }
    }

    /**
     * Clean up stale flow state (flows inactive for > 60 seconds).
     */
    fun cleanup() {
        val now = System.nanoTime()
        val threshold = 60L * 1_000_000_000L
        flowStates.entries.removeAll { (_, state) ->
            (now - state.lastPacketNs) > threshold
        }
    }

    companion object {
        private const val TAG = "FlowClassifier"
    }
}
