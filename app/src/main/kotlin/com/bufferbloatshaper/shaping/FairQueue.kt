package com.bufferbloatshaper.shaping

import com.bufferbloatshaper.model.FlowType
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-flow fair queuing with deficit round-robin scheduling (§3, Layer 3).
 *
 * Packets are hashed into separate sub-queues by flow (5-tuple), and served
 * round-robin rather than one global FIFO. This prevents a single greedy upload
 * from starving latency-sensitive traffic.
 *
 * Each sub-queue has its own CoDel AQM instance, so queue management is
 * per-flow rather than aggregate.
 *
 * Phase 4 addition: flow priority support for adaptive smart mode.
 */
class FairQueue(
    private val numBuckets: Int = 1024,
    private val maxQueueSize: Int = 256,
    codelTargetMs: Long = 5,
    codelIntervalMs: Long = 100
) {
    /** A queued packet with its enqueue timestamp for sojourn time calculation. */
    data class QueuedPacket(
        val data: ByteArray,
        val enqueueNs: Long = System.nanoTime(),
        val flowHash: Int,
        val packetSize: Int = data.size,
        val flowType: FlowType = FlowType.UNKNOWN
    )

    /** Per-flow queue with its own CoDel instance and DRR deficit counter. */
    private class FlowQueue(
        codelTargetMs: Long,
        codelIntervalMs: Long
    ) {
        val packets = ArrayDeque<QueuedPacket>(64)
        val codel = CodelAqm(codelTargetMs, codelIntervalMs)
        var deficit: Int = 0
        var lastActivityNs: Long = System.nanoTime()
        var totalBytesSent: Long = 0L
        var totalPacketsSent: Long = 0L
        var totalPacketsDropped: Long = 0L
        var flowType: FlowType = FlowType.UNKNOWN
    }

    private val queues = ConcurrentHashMap<Int, FlowQueue>()
    private val activeFlows = ArrayDeque<Int>() // round-robin order
    private val codelTargetMs = codelTargetMs
    private val codelIntervalMs = codelIntervalMs

    /** Default quantum for DRR (bytes per round per flow). */
    private val quantum: Int = 1500 // ~one MTU-sized packet per round

    /** Total packets currently queued across all flows. */
    @Volatile
    var totalQueuedPackets: Int = 0
        private set

    /** Total packets dropped across all flows. */
    @Volatile
    var totalDroppedPackets: Long = 0
        private set

    /**
     * Enqueue a packet into its flow's sub-queue.
     *
     * @param data Raw packet bytes.
     * @param flowHash Hash of the 5-tuple identifying this flow.
     * @param flowType Classification of this flow (Phase 4).
     * @return true if enqueued successfully, false if dropped (queue full).
     */
    fun enqueue(data: ByteArray, flowHash: Int, flowType: FlowType = FlowType.UNKNOWN): Boolean {
        val bucket = (flowHash and 0x7FFFFFFF) % numBuckets
        val queue = queues.getOrPut(bucket) {
            FlowQueue(codelTargetMs, codelIntervalMs)
        }

        // Drop tail if queue is full
        if (queue.packets.size >= maxQueueSize) {
            totalDroppedPackets++
            queue.totalPacketsDropped++
            return false
        }

        val qp = QueuedPacket(
            data = data,
            flowHash = flowHash,
            packetSize = data.size,
            flowType = flowType
        )

        queue.packets.addLast(qp)
        queue.lastActivityNs = System.nanoTime()
        queue.flowType = flowType
        totalQueuedPackets++

        // Add to active flows if not already present
        synchronized(activeFlows) {
            if (bucket !in activeFlows) {
                activeFlows.addLast(bucket)
            }
        }

        return true
    }

    /**
     * Dequeue the next packet using deficit round-robin across flows.
     * Applies CoDel AQM per-flow at dequeue time.
     *
     * @return The next packet to send, or null if all queues are empty.
     */
    fun dequeue(): QueuedPacket? {
        val now = System.nanoTime()

        synchronized(activeFlows) {
            if (activeFlows.isEmpty()) return null

            // Try each active flow in round-robin order
            val flowsToTry = activeFlows.size
            repeat(flowsToTry) {
                val bucket = activeFlows.peekFirst() ?: return null
                val queue = queues[bucket]

                if (queue == null || queue.packets.isEmpty()) {
                    activeFlows.pollFirst()
                    return@repeat
                }

                // Add quantum to this flow's deficit
                queue.deficit += quantum

                // Try to dequeue from this flow
                while (queue.packets.isNotEmpty() && queue.deficit > 0) {
                    val head = queue.packets.peekFirst() ?: break

                    // Check CoDel — should we drop this packet?
                    val sojournNs = now - head.enqueueNs
                    if (queue.codel.shouldDrop(sojournNs, now)) {
                        // Drop the packet
                        queue.packets.pollFirst()
                        totalQueuedPackets--
                        totalDroppedPackets++
                        queue.totalPacketsDropped++
                        continue
                    }

                    // Packet passes CoDel — check if we have enough deficit
                    if (head.packetSize <= queue.deficit) {
                        queue.packets.pollFirst()
                        queue.deficit -= head.packetSize
                        queue.totalBytesSent += head.packetSize
                        queue.totalPacketsSent++
                        totalQueuedPackets--

                        // Move this flow to the back of the round-robin
                        activeFlows.pollFirst()
                        if (queue.packets.isNotEmpty()) {
                            activeFlows.addLast(bucket)
                        }

                        return head
                    } else {
                        // Not enough deficit — move to next flow
                        break
                    }
                }

                // If queue is empty, remove from active flows and reset deficit
                if (queue.packets.isEmpty()) {
                    activeFlows.pollFirst()
                    queue.deficit = 0
                    queue.codel.reset()
                } else {
                    // Move to back for next round
                    activeFlows.pollFirst()
                    activeFlows.addLast(bucket)
                }
            }
        }

        return null
    }

    /**
     * Dequeue with flow priority (Phase 4: Adaptive Smart Mode).
     *
     * Latency-sensitive flows (INTERACTIVE, VIDEO_CALL, DNS) get priority
     * dequeue over bulk transfers.
     */
    fun dequeueWithPriority(): QueuedPacket? {
        val now = System.nanoTime()

        // First pass: check high-priority queues
        synchronized(activeFlows) {
            for (bucket in activeFlows) {
                val queue = queues[bucket] ?: continue
                if (queue.flowType.isLatencySensitive() && queue.packets.isNotEmpty()) {
                    val head = queue.packets.peekFirst() ?: continue
                    val sojournNs = now - head.enqueueNs
                    if (!queue.codel.shouldDrop(sojournNs, now)) {
                        queue.packets.pollFirst()
                        queue.totalBytesSent += head.packetSize
                        queue.totalPacketsSent++
                        totalQueuedPackets--
                        return head
                    } else {
                        queue.packets.pollFirst()
                        totalQueuedPackets--
                        totalDroppedPackets++
                        queue.totalPacketsDropped++
                    }
                }
            }
        }

        // Second pass: normal DRR for remaining flows
        return dequeue()
    }

    /**
     * Get per-flow stats for the UI.
     */
    fun getFlowStats(): Map<Int, FlowQueueStats> {
        return queues.mapValues { (_, queue) ->
            FlowQueueStats(
                queueDepth = queue.packets.size,
                sojournMs = queue.codel.lastSojournMs,
                totalBytesSent = queue.totalBytesSent,
                totalPacketsSent = queue.totalPacketsSent,
                totalPacketsDropped = queue.totalPacketsDropped,
                inDropState = queue.codel.dropState,
                flowType = queue.flowType
            )
        }
    }

    /** Clean up inactive flow queues (no activity for > 60 seconds). */
    fun cleanupInactiveFlows() {
        val now = System.nanoTime()
        val threshold = 60L * 1_000_000_000L // 60 seconds
        val inactive = queues.entries.filter { (_, queue) ->
            queue.packets.isEmpty() && (now - queue.lastActivityNs) > threshold
        }.map { it.key }

        inactive.forEach { queues.remove(it) }
    }

    /** Number of distinct active flows. */
    val activeFlowCount: Int
        get() = queues.count { it.value.packets.isNotEmpty() }

    data class FlowQueueStats(
        val queueDepth: Int,
        val sojournMs: Long,
        val totalBytesSent: Long,
        val totalPacketsSent: Long,
        val totalPacketsDropped: Long,
        val inDropState: Boolean,
        val flowType: FlowType
    )

    private fun FlowType.isLatencySensitive(): Boolean = when (this) {
        FlowType.INTERACTIVE, FlowType.VIDEO_CALL, FlowType.DNS -> true
        else -> false
    }
}
