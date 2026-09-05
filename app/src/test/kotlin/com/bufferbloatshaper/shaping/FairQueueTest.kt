package com.bufferbloatshaper.shaping

import com.bufferbloatshaper.model.FlowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FairQueueTest {

    @Test
    fun dequeueServesActiveFlowsRoundRobin() {
        val queue = fairQueue()

        queue.enqueue(byteArrayOf(1), flowHash = 10)
        queue.enqueue(byteArrayOf(2), flowHash = 20)
        queue.enqueue(byteArrayOf(3), flowHash = 10)

        assertEquals(1, requireNotNull(queue.dequeue()).data[0].toInt())
        assertEquals(2, requireNotNull(queue.dequeue()).data[0].toInt())
        assertEquals(3, requireNotNull(queue.dequeue()).data[0].toInt())
        assertNull(queue.dequeue())
        assertEquals(0, queue.totalQueuedPackets)
    }

    @Test
    fun fullFlowQueueDropsTailAndReportsDropInStats() {
        val queue = FairQueue(
            numBuckets = 1,
            maxQueueSize = 2,
            codelTargetMs = Long.MAX_VALUE,
            codelIntervalMs = 100
        )

        assertTrue(queue.enqueue(byteArrayOf(1), flowHash = 1))
        assertTrue(queue.enqueue(byteArrayOf(2), flowHash = 2))
        assertFalse(queue.enqueue(byteArrayOf(3), flowHash = 3))

        assertEquals(2, queue.totalQueuedPackets)
        assertEquals(1L, queue.totalDroppedPackets)
        assertEquals(1, queue.activeFlowCount)
        val stats = requireNotNull(queue.getFlowStats()[0])
        assertEquals(2, stats.queueDepth)
        assertEquals(1L, stats.totalPacketsDropped)
    }

    @Test
    fun priorityDequeueServesDnsBeforeEarlierBulkTraffic() {
        val queue = fairQueue()

        queue.enqueue(byteArrayOf(1), flowHash = 10, flowType = FlowType.BULK_TRANSFER)
        queue.enqueue(byteArrayOf(2), flowHash = 20, flowType = FlowType.DNS)

        assertEquals(2, requireNotNull(queue.dequeueWithPriority()).data[0].toInt())
        assertEquals(1, requireNotNull(queue.dequeue()).data[0].toInt())
        assertEquals(0, queue.totalQueuedPackets)
    }

    @Test
    fun flowStatsTrackSentBytesPacketsAndClassification() {
        val queue = fairQueue()
        val flowHash = 42

        queue.enqueue(ByteArray(120) { 7 }, flowHash, FlowType.INTERACTIVE)
        requireNotNull(queue.dequeue())

        val bucket = (flowHash and 0x7FFFFFFF) % 32
        val stats = requireNotNull(queue.getFlowStats()[bucket])
        assertEquals(0, stats.queueDepth)
        assertEquals(120L, stats.totalBytesSent)
        assertEquals(1L, stats.totalPacketsSent)
        assertEquals(FlowType.INTERACTIVE, stats.flowType)
    }

    private fun fairQueue() = FairQueue(
        numBuckets = 32,
        maxQueueSize = 16,
        // Tests do not depend on wall-clock queue age or CoDel behavior.
        codelTargetMs = Long.MAX_VALUE,
        codelIntervalMs = 100
    )
}
