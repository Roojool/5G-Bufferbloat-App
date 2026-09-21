package com.bufferbloatshaper.harness.stream

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPacingHarnessTest {
    private class Clock : StreamHarnessClock {
        var now = 0L
        override fun nowMs() = now
        override fun pause(ms: Long) { now += ms }
    }

    private class Source(
        private val data: ByteArray,
        private val maxRead: Int = Int.MAX_VALUE,
        private val eagainEvery: Int = 0,
    ) : StreamSource {
        var offset = 0
        var reads = 0
        var closes = 0
        override fun read(target: ByteArray, limit: Int): Int {
            reads++
            if (eagainEvery > 0 && reads % eagainEvery == 0) return 0
            if (offset == data.size) return -1
            val count = minOf(limit, maxRead, data.size - offset)
            data.copyInto(target, 0, offset, offset + count)
            offset += count
            return count
        }
        override fun close(): Int { closes++; return 0 }
    }

    private class Sink(
        private val maxWrite: Int = Int.MAX_VALUE,
        private val eagainCalls: MutableSet<Int> = mutableSetOf(),
        private val resetAtWrittenBytes: Int? = null,
        private val onWrite: ((Int) -> Unit)? = null,
    ) : StreamSink {
        val output = ByteArrayOutputStream()
        var writes = 0
        var shutdowns = 0
        var closes = 0
        override fun write(source: ByteArray, offset: Int, length: Int): Int {
            writes++
            if (writes in eagainCalls) return 0
            if (resetAtWrittenBytes != null && output.size() >= resetAtWrittenBytes) return -104
            val count = minOf(length, maxWrite)
            output.write(source, offset, count)
            onWrite?.invoke(output.size())
            return count
        }
        override fun shutdownOutput(): Int { shutdowns++; return 0 }
        override fun close(): Int { closes++; return 0 }
    }

    private fun payload(size: Int, salt: Int = 0) = ByteArray(size) { ((it * 31 + salt) and 0xff).toByte() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun config(
        rate: Long = 1_000_000,
        burst: Int = 64,
        quantum: Int = 16,
        perFlow: Int = 64,
        global: Int = 64,
        duration: Long = 10_000,
        stall: Long = 1_000,
        changes: List<StreamHarnessConfig.RateChange> = emptyList(),
    ) = StreamHarnessConfig(rate, burst, quantum, perFlow, global,
        readChunkBytes = 16, maxWriteBytes = 16, tickMs = 1,
        durationMs = duration, stallTimeoutMs = stall, rateChanges = changes)

    @Test fun exactOrderedByteAndHashIntegrity() {
        val data = payload(4_097)
        val source = Source(data, maxRead = 13)
        val sink = Sink(maxWrite = 11)
        val result = StreamPacingRunner(Clock()).run(config(), listOf(ControlledFlow(source, sink)))
        val flow = result.flows.single()
        assertEquals("COMPLETE", result.outcome)
        assertEquals(data.toList(), sink.output.toByteArray().toList())
        assertEquals(data.size.toLong(), flow.acceptedBytes)
        assertEquals(flow.acceptedBytes, flow.writtenBytes)
        assertEquals(hash(data), flow.acceptedSha256)
        assertEquals(flow.acceptedSha256, flow.writtenSha256)
        assertEquals(0L, flow.undeliveredAcceptedBytes)
    }

    @Test fun configuredBufferBoundsAreNeverExceeded() {
        val sources = List(3) { Source(payload(1_000, it)) }
        val sinks = List(3) { Sink(eagainCalls = (1..10_000).toMutableSet()) }
        val result = StreamPacingRunner(Clock()).run(
            config(perFlow = 32, global = 96, duration = 500, stall = 100),
            sources.indices.map { ControlledFlow(sources[it], sinks[it]) },
        )
        assertEquals("STALLED", result.outcome)
        assertEquals(96, result.allocatedQueueBytes)
        assertEquals(16, result.readScratchBytes)
        assertEquals(0, result.retainedQueueBytesAfterCleanup)
        assertEquals(result.configuredGlobalBufferBytes, result.maxGlobalQueuedBytes)
        assertTrue(result.flows.all { it.maxQueuedBytes == 32 })
        assertTrue(result.flows.all { it.undeliveredAcceptedBytes == it.acceptedBytes })
        assertTrue(result.flows.all { it.longestNoProgressMs >= 100 })
    }

    @Test fun sustainedBackpressureStopsAndSafelyResumesReads() {
        val data = payload(512)
        val source = Source(data, maxRead = 16)
        val sink = Sink(maxWrite = 8, eagainCalls = (1..80).toMutableSet())
        val result = StreamPacingRunner(Clock()).run(
            config(burst = 32, perFlow = 32, global = 32, duration = 5_000, stall = 1_000),
            listOf(ControlledFlow(source, sink)),
        )
        val flow = result.flows.single()
        assertEquals("COMPLETE", result.outcome)
        assertTrue(flow.readBackpressureCount > 0)
        assertTrue(flow.readResumeCount > 0)
        assertTrue(flow.writeEagainCount >= 80)
        assertEquals(data.toList(), sink.output.toByteArray().toList())
    }

    @Test fun partialWritesAndEagainPreserveOrder() {
        val data = payload(1_025)
        val source = Source(data, maxRead = 15, eagainEvery = 7)
        val sink = Sink(maxWrite = 3, eagainCalls = mutableSetOf(2, 5, 9, 17, 33))
        val result = StreamPacingRunner(Clock()).run(config(), listOf(ControlledFlow(source, sink)))
        val flow = result.flows.single()
        assertEquals("COMPLETE", result.outcome)
        assertTrue(flow.sourceEagainCount > 0)
        assertEquals(5L, flow.writeEagainCount)
        assertTrue(flow.partialWriteCount > 0)
        assertEquals(data.toList(), sink.output.toByteArray().toList())
    }

    @Test fun deficitRoundRobinLetsShortFlowFinishAmongBulkFlows() {
        val data = listOf(payload(2_048, 1), payload(37, 2), payload(2_048, 3))
        val sources = data.map { Source(it, maxRead = 16) }
        val sinks = data.map { Sink(maxWrite = 16) }
        val result = StreamPacingRunner(Clock()).run(
            config(rate = 4_000, burst = 16, quantum = 16, perFlow = 64, global = 192),
            data.indices.map { ControlledFlow(sources[it], sinks[it]) },
        )
        assertEquals("COMPLETE", result.outcome)
        assertTrue(result.flows[1].completedAtMs!! < result.flows[0].completedAtMs!!)
        assertTrue(result.flows[1].completedAtMs!! < result.flows[2].completedAtMs!!)
        assertTrue(result.flows[1].longestNoProgressMs <= result.flows[1].completedAtMs!!)
        assertTrue(result.flows.all { it.writtenBytes > 0 })
        data.indices.forEach { assertEquals(data[it].toList(), sinks[it].output.toByteArray().toList()) }
    }

    @Test fun cancellationRecordsUndeliveredBytesAndCleansUpExactlyOnce() {
        val cancelled = AtomicBoolean(false)
        val source = Source(payload(1_000), maxRead = 16)
        val sink = Sink(maxWrite = 4, onWrite = { if (it >= 20) cancelled.set(true) })
        val result = StreamPacingRunner(Clock()).run(
            config(rate = 100, burst = 8, perFlow = 32, global = 32),
            listOf(ControlledFlow(source, sink)), cancelled,
        )
        val flow = result.flows.single()
        assertEquals("CANCELLED", result.outcome)
        assertTrue(flow.acceptedBytes > flow.writtenBytes)
        assertEquals(flow.acceptedBytes - flow.writtenBytes, flow.undeliveredAcceptedBytes)
        assertEquals(1, source.closes)
        assertEquals(1, sink.closes)
        assertEquals(0, sink.shutdowns)
    }

    @Test fun orderlyHalfCloseDrainsWhileResetReportsAcceptedRemainder() {
        val completeSource = Source(payload(100), maxRead = 9)
        val completeSink = Sink(maxWrite = 7)
        val complete = StreamPacingRunner(Clock()).run(
            config(), listOf(ControlledFlow(completeSource, completeSink)))
        assertEquals("COMPLETE", complete.outcome)
        assertEquals(1, completeSink.shutdowns)
        assertEquals(0, complete.flows.single().shutdownOutputStatus)

        val resetSource = Source(payload(200), maxRead = 16)
        val resetSink = Sink(maxWrite = 8, resetAtWrittenBytes = 24)
        val reset = StreamPacingRunner(Clock()).run(
            config(burst = 32, perFlow = 32, global = 32),
            listOf(ControlledFlow(resetSource, resetSink)))
        val resetFlow = reset.flows.single()
        assertEquals("PARTIAL_FAILURE", reset.outcome)
        assertEquals("SINK_FAILED", resetFlow.outcome)
        assertEquals(104, resetFlow.failureCode)
        assertTrue(resetFlow.undeliveredAcceptedBytes > 0)
        assertEquals(resetFlow.acceptedBytes - resetFlow.writtenBytes, resetFlow.undeliveredAcceptedBytes)
        assertEquals(1, resetSource.closes)
        assertEquals(1, resetSink.closes)
    }

    @Test fun abruptRateReductionTakesEffectWithoutBreakingBudgetOrIntegrity() {
        val data = payload(40)
        val source = Source(data, maxRead = 8)
        val sink = Sink(maxWrite = 8)
        val result = StreamPacingRunner(Clock()).run(
            config(rate = 1_000, burst = 8, quantum = 8, perFlow = 32, global = 32,
                duration = 10_000, stall = 5_000,
                changes = listOf(StreamHarnessConfig.RateChange(10, 10))),
            listOf(ControlledFlow(source, sink)),
        )
        assertEquals("COMPLETE", result.outcome)
        assertEquals(listOf(StreamHarnessResult.AppliedRateChange(10, 10)), result.appliedRateChanges)
        assertTrue(result.elapsedMs >= 2_000)
        assertTrue(result.pacingWaitCount > 0)
        assertTrue(result.maxPacingTokensBytes <= 8)
        assertTrue(result.largestWriteRequestBytes <= 8)
        assertEquals(data.toList(), sink.output.toByteArray().toList())
    }
}
