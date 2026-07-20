package com.bufferbloatshaper.shaping

import android.util.Log
import com.bufferbloatshaper.model.FlowType
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Egress shaping pipeline orchestrator (§3).
 *
 * Full pipeline: enqueue → FairQueue (with per-flow CoDel) → TokenBucket → send
 *
 * This is essentially a userspace reimplementation of what cake/fq_codel do
 * at the router level, as described in §3 of the plan.
 *
 * Phase 0: Token bucket only
 * Phase 1: + CoDel AQM + per-flow fair queuing
 * Phase 4: + flow priority for latency-sensitive traffic
 */
class EgressShaper(
    rateBytesPerSec: Double,
    private val codelTargetMs: Long = 5,
    private val codelIntervalMs: Long = 100,
    private val fqBuckets: Int = 1024,
    private val burstFraction: Double = 0.02,
    private val smartModeEnabled: Boolean = false
) {
    /** Token bucket enforcing the rate ceiling (Layer 1). */
    val tokenBucket = TokenBucket(
        rateBytesPerSec = rateBytesPerSec,
        burstBytes = (rateBytesPerSec * burstFraction).toLong().coerceAtLeast(1500)
    )

    /** Per-flow fair queue with CoDel AQM (Layers 2+3). */
    val fairQueue = FairQueue(
        numBuckets = fqBuckets,
        codelTargetMs = codelTargetMs,
        codelIntervalMs = codelIntervalMs
    )

    /** Flow classifier for Phase 4 adaptive mode. */
    var flowClassifier: FlowClassifier? = null

    /** Callback: send this packet to the relay/network. */
    var onSendPacket: ((ByteArray) -> Unit)? = null

    /** Stats counters */
    val totalBytesShaped = AtomicLong(0)
    val totalPacketsShaped = AtomicLong(0)
    val totalPacketsEnqueued = AtomicLong(0)

    /** Whether the dequeue loop is running. */
    @Volatile
    private var running = false

    private var dequeueJob: Job? = null

    /**
     * Enqueue a packet into the shaping pipeline.
     * The packet will be rate-limited, AQM-checked, and fair-queued
     * before being sent out.
     *
     * @param data Raw packet bytes.
     * @param flowHash Hash of the 5-tuple for flow identification.
     */
    fun enqueue(data: ByteArray, flowHash: Int) {
        totalPacketsEnqueued.incrementAndGet()

        // Classify flow type (Phase 4)
        val flowType = flowClassifier?.classify(data, flowHash) ?: FlowType.UNKNOWN

        // Enqueue into the fair queue (which handles per-flow CoDel)
        fairQueue.enqueue(data, flowHash, flowType)
    }

    /**
     * Start the dequeue loop. Continuously dequeues packets from the
     * fair queue, paces them through the token bucket, and sends them.
     */
    fun start(scope: CoroutineScope) {
        if (running) return
        running = true

        dequeueJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Egress shaper dequeue loop started (rate=${tokenBucket.rateBytesPerSec} B/s)")

            while (running && isActive) {
                // Dequeue next packet (with priority if smart mode is on)
                val packet = if (smartModeEnabled) {
                    fairQueue.dequeueWithPriority()
                } else {
                    fairQueue.dequeue()
                }

                if (packet == null) {
                    // No packets queued — yield briefly
                    delay(1)
                    continue
                }

                // Pace through token bucket
                val waitNs = tokenBucket.nanosUntilAvailable(packet.packetSize)
                if (waitNs > 0) {
                    // Wait for tokens (this is the actual pacing)
                    val waitMs = waitNs / 1_000_000
                    val waitNsRemainder = (waitNs % 1_000_000).toInt()
                    if (waitMs > 0 || waitNsRemainder > 0) {
                        delay(waitMs.coerceAtLeast(1))
                    }
                }

                // Consume tokens and send
                if (tokenBucket.tryConsume(packet.packetSize)) {
                    totalBytesShaped.addAndGet(packet.packetSize.toLong())
                    totalPacketsShaped.incrementAndGet()
                    onSendPacket?.invoke(packet.data)
                } else {
                    // Tokens still not available after wait — re-enqueue
                    // This can happen due to timing; just try again next iteration
                    fairQueue.enqueue(packet.data, packet.flowHash, packet.flowType)
                }
            }

            Log.d(TAG, "Egress shaper dequeue loop stopped")
        }
    }

    /** Stop the dequeue loop. */
    fun stop() {
        running = false
        dequeueJob?.cancel()
        dequeueJob = null
    }

    /**
     * Update the shaping rate dynamically (called by CalibrationEngine).
     */
    fun updateRate(newRateBytesPerSec: Double) {
        tokenBucket.updateRate(newRateBytesPerSec, burstFraction)
        Log.d(TAG, "Egress rate updated to ${newRateBytesPerSec / 125_000} Mbps")
    }

    /** Periodic cleanup of stale flow queues. */
    fun cleanup() {
        fairQueue.cleanupInactiveFlows()
    }

    /** Snapshot of current shaper state for the UI. */
    data class ShaperStats(
        val rateBytesPerSec: Double,
        val burstCapacity: Long,
        val tokenFillRatio: Double,
        val totalQueuedPackets: Int,
        val totalDroppedPackets: Long,
        val totalBytesShaped: Long,
        val totalPacketsShaped: Long,
        val activeFlowCount: Int,
        val flowStats: Map<Int, FairQueue.FlowQueueStats>
    )

    fun getStats(): ShaperStats = ShaperStats(
        rateBytesPerSec = tokenBucket.rateBytesPerSec,
        burstCapacity = tokenBucket.burstCapacity,
        tokenFillRatio = tokenBucket.fillRatio(),
        totalQueuedPackets = fairQueue.totalQueuedPackets,
        totalDroppedPackets = fairQueue.totalDroppedPackets,
        totalBytesShaped = totalBytesShaped.get(),
        totalPacketsShaped = totalPacketsShaped.get(),
        activeFlowCount = fairQueue.activeFlowCount,
        flowStats = fairQueue.getFlowStats()
    )

    companion object {
        private const val TAG = "EgressShaper"
    }
}
