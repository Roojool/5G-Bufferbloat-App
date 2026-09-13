package com.bufferbloatshaper.harness.stream

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only controls for deterministic F-03 stream feasibility. */
data class StreamHarnessConfig(
    val rateBytesPerSecond: Long,
    val burstBytes: Int,
    val quantumBytes: Int,
    val perFlowBufferBytes: Int,
    val globalBufferBytes: Int,
    val readChunkBytes: Int = 4_096,
    val maxWriteBytes: Int = 4_096,
    val tickMs: Long = 1,
    val durationMs: Long = 60_000,
    val stallTimeoutMs: Long = 5_000,
    val rateChanges: List<RateChange> = emptyList(),
) {
    data class RateChange(val atMs: Long, val rateBytesPerSecond: Long)

    fun validate(flowCount: Int) {
        require(flowCount in 1..64)
        require(rateBytesPerSecond in 1..100_000_000)
        require(burstBytes in 1..1_048_576)
        require(quantumBytes in 1..65_536)
        require(perFlowBufferBytes in 1..1_048_576)
        require(globalBufferBytes in 1..16_777_216)
        require(perFlowBufferBytes.toLong() * flowCount <= globalBufferBytes)
        require(burstBytes <= globalBufferBytes)
        require(readChunkBytes in 1..65_536 && maxWriteBytes in 1..65_536)
        require(tickMs in 1..100 && durationMs in 1..120_000)
        require(stallTimeoutMs in 1..30_000 && stallTimeoutMs <= durationMs)
        require(rateChanges.size <= 16)
        require(rateChanges.zipWithNext().all { it.first.atMs < it.second.atMs })
        rateChanges.forEach {
            require(it.atMs in 1 until durationMs)
            require(it.rateBytesPerSecond in 1..100_000_000)
        }
    }
}

/**
 * Nonblocking source contract. Positive values append accepted bytes, zero is
 * EAGAIN, -1 is an orderly half-close, and values below -1 are typed failures.
 */
interface StreamSource {
    fun read(target: ByteArray, limit: Int): Int
    fun close(): Int
}

/** Positive writes consume bytes, zero is EAGAIN, and negative values are failures. */
interface StreamSink {
    fun write(source: ByteArray, offset: Int, length: Int): Int
    fun shutdownOutput(): Int
    fun close(): Int
}

data class ControlledFlow(val source: StreamSource, val sink: StreamSink)

interface StreamHarnessClock {
    fun nowMs(): Long
    fun pause(ms: Long)
}

object StreamMonotonicClock : StreamHarnessClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000
    override fun pause(ms: Long) = Thread.sleep(ms)
}

data class StreamFlowCounters(
    val flowIndex: Int,
    val outcome: String,
    val acceptedBytes: Long,
    val writtenBytes: Long,
    val undeliveredAcceptedBytes: Long,
    val acceptedSha256: String,
    val writtenSha256: String,
    val maxQueuedBytes: Int,
    val sourceEagainCount: Long,
    val writeEagainCount: Long,
    val partialWriteCount: Long,
    val readBackpressureCount: Long,
    val readResumeCount: Long,
    val longestNoProgressMs: Long,
    val firstWriteAtMs: Long?,
    val completedAtMs: Long?,
    val shutdownOutputStatus: Int?,
    val failureCode: Int?,
    val sourceCloseStatus: Int,
    val sinkCloseStatus: Int,
)

data class StreamHarnessResult(
    val outcome: String,
    val elapsedMs: Long,
    val initialRateBytesPerSecond: Long,
    val burstBytes: Int,
    val quantumBytes: Int,
    val perFlowBufferBytes: Int,
    val maxWriteBytes: Int,
    val totalAcceptedBytes: Long,
    val totalWrittenBytes: Long,
    val maxGlobalQueuedBytes: Int,
    val configuredGlobalBufferBytes: Int,
    val allocatedQueueBytes: Int,
    val readScratchBytes: Int,
    val retainedQueueBytesAfterCleanup: Int,
    val largestWriteRequestBytes: Int,
    val maxPacingTokensBytes: Long,
    val pacingWaitCount: Long,
    val appliedRateChanges: List<AppliedRateChange>,
    val schedulerRounds: Long,
    val flows: List<StreamFlowCounters>,
) {
    data class AppliedRateChange(val atMs: Long, val rateBytesPerSecond: Long)
}

/**
 * Synchronous feasibility runner. The caller owns no queue memory or callback
 * after run returns. Accepted bytes are drained in order or reported as
 * undelivered on cancellation/failure; they are never silently discarded.
 */
class StreamPacingRunner(
    private val clock: StreamHarnessClock = StreamMonotonicClock,
) {
    fun run(
        config: StreamHarnessConfig,
        flows: List<ControlledFlow>,
        cancelled: AtomicBoolean = AtomicBoolean(false),
    ): StreamHarnessResult {
        config.validate(flows.size)
        val start = clock.nowMs()
        val states = flows.mapIndexed { index, flow -> State(index, flow, config.perFlowBufferBytes, start) }
        val budget = ByteBudget(config.rateBytesPerSecond, config.burstBytes, start)
        val readScratch = ByteArray(minOf(config.readChunkBytes, config.perFlowBufferBytes))
        var globalQueued = 0
        var maxGlobalQueued = 0
        var largestWriteRequest = 0
        var pacingWaits = 0L
        val appliedRateChanges = mutableListOf<StreamHarnessResult.AppliedRateChange>()
        var changeIndex = 0
        var cursor = 0
        var rounds = 0L
        var overall = "COMPLETE"
        var lastGlobalProgress = start

        try {
            loop@ while (states.any { !it.terminal }) {
                val now = clock.nowMs()
                val elapsed = now - start
                if (cancelled.get() || Thread.currentThread().isInterrupted) {
                    overall = "CANCELLED"
                    states.filter { !it.terminal }.forEach { it.finish("CANCELLED", elapsed) }
                    break
                }
                if (elapsed >= config.durationMs) {
                    overall = "DEADLINE"
                    states.filter { !it.terminal }.forEach { it.finish("DEADLINE", elapsed) }
                    break
                }
                while (changeIndex < config.rateChanges.size && elapsed >= config.rateChanges[changeIndex].atMs) {
                    val change = config.rateChanges[changeIndex++]
                    budget.changeRate(now, change.rateBytesPerSecond)
                    appliedRateChanges += StreamHarnessResult.AppliedRateChange(elapsed, change.rateBytesPerSecond)
                }

                var progressed = false
                for (state in states) {
                    if (state.terminal || state.sourceDone) continue
                    val allowance = minOf(
                        state.queue.remaining,
                        config.globalBufferBytes - globalQueued,
                        readScratch.size,
                    )
                    if (allowance == 0) {
                        if (!state.readBackpressured) {
                            state.readBackpressured = true
                            state.readBackpressureCount++
                        }
                        continue
                    }
                    if (state.readBackpressured) {
                        state.readBackpressured = false
                        state.readResumeCount++
                    }
                    val count = state.flow.source.read(readScratch, allowance)
                    when {
                        count > allowance -> throw IllegalStateException("source exceeded bounded read allowance")
                        count > 0 -> {
                            state.queue.append(readScratch, count)
                            state.acceptedDigest.update(readScratch, 0, count)
                            state.acceptedBytes += count
                            globalQueued += count
                            state.maxQueuedBytes = maxOf(state.maxQueuedBytes, state.queue.size)
                            maxGlobalQueued = maxOf(maxGlobalQueued, globalQueued)
                            state.lastProgressMs = now
                            progressed = true
                        }
                        count == 0 -> state.sourceEagainCount++
                        count == -1 -> state.sourceDone = true
                        else -> {
                            state.sourceDone = true
                            state.pendingOutcome = "SOURCE_FAILED"
                            state.failureCode = -count
                        }
                    }
                }

                for (offset in states.indices) {
                    val state = states[(cursor + offset) % states.size]
                    if (state.terminal || state.queue.size == 0) continue
                    state.deficit = minOf(state.deficit + config.quantumBytes, config.quantumBytes * 4)
                    val tokens = budget.available(now)
                    if (tokens == 0L) {
                        pacingWaits++
                        continue
                    }
                    val requested = minOf(
                        state.deficit,
                        state.queue.contiguousBytes,
                        config.maxWriteBytes,
                        tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    )
                    if (requested == 0) continue
                    largestWriteRequest = maxOf(largestWriteRequest, requested)
                    val count = state.flow.sink.write(state.queue.bytes, state.queue.head, requested)
                    when {
                        count > requested -> throw IllegalStateException("sink exceeded bounded write request")
                        count > 0 -> {
                            if (count < requested) state.partialWriteCount++
                            state.writtenDigest.update(state.queue.bytes, state.queue.head, count)
                            state.queue.remove(count)
                            state.writtenBytes += count
                            state.deficit -= count
                            globalQueued -= count
                            budget.consume(count)
                            state.lastProgressMs = now
                            if (state.firstWriteAtMs == null) state.firstWriteAtMs = elapsed
                            progressed = true
                        }
                        count == 0 -> state.writeEagainCount++
                        else -> {
                            state.failureCode = -count
                            globalQueued -= state.queue.size
                            state.finish("SINK_FAILED", elapsed)
                        }
                    }
                }
                cursor = (cursor + 1) % states.size
                rounds++

                for (state in states) {
                    if (!state.terminal && state.sourceDone && state.queue.size == 0) {
                        state.shutdownOutputStatus = state.flow.sink.shutdownOutput()
                        state.finish(
                            if (state.pendingOutcome == null && state.shutdownOutputStatus == 0) "COMPLETE"
                            else state.pendingOutcome ?: "HALF_CLOSE_FAILED",
                            elapsed,
                        )
                    }
                    state.longestNoProgressMs = maxOf(state.longestNoProgressMs, now - state.lastProgressMs)
                }

                if (progressed) lastGlobalProgress = now
                if (states.any { !it.terminal } && now - lastGlobalProgress >= config.stallTimeoutMs) {
                    overall = "STALLED"
                    states.filter { !it.terminal }.forEach { it.finish("STALLED", elapsed) }
                    break@loop
                }
                if (states.any { !it.terminal }) clock.pause(config.tickMs)
            }
            if (overall == "COMPLETE" && states.any { it.outcome != "COMPLETE" }) {
                overall = "PARTIAL_FAILURE"
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            overall = "CANCELLED"
            val elapsed = clock.nowMs() - start
            states.filter { !it.terminal }.forEach { it.finish("CANCELLED", elapsed) }
        } catch (_: Exception) {
            overall = "HARNESS_FAILURE"
            val elapsed = clock.nowMs() - start
            states.filter { !it.terminal }.forEach { it.finish("HARNESS_FAILURE", elapsed) }
        } finally {
            states.forEach {
                it.undeliveredAcceptedBytes = it.acceptedBytes - it.writtenBytes
                it.sourceCloseStatus = safeClose { it.flow.source.close() }
                it.sinkCloseStatus = safeClose { it.flow.sink.close() }
                it.queue.clear()
            }
        }

        return StreamHarnessResult(
            outcome = overall,
            elapsedMs = clock.nowMs() - start,
            initialRateBytesPerSecond = config.rateBytesPerSecond,
            burstBytes = config.burstBytes,
            quantumBytes = config.quantumBytes,
            perFlowBufferBytes = config.perFlowBufferBytes,
            maxWriteBytes = config.maxWriteBytes,
            totalAcceptedBytes = states.sumOf { it.acceptedBytes },
            totalWrittenBytes = states.sumOf { it.writtenBytes },
            maxGlobalQueuedBytes = maxGlobalQueued,
            configuredGlobalBufferBytes = config.globalBufferBytes,
            allocatedQueueBytes = config.perFlowBufferBytes * states.size,
            readScratchBytes = readScratch.size,
            retainedQueueBytesAfterCleanup = states.sumOf { it.queue.size },
            largestWriteRequestBytes = largestWriteRequest,
            maxPacingTokensBytes = budget.maxObservedTokens,
            pacingWaitCount = pacingWaits,
            appliedRateChanges = appliedRateChanges.toList(),
            schedulerRounds = rounds,
            flows = states.map { it.result() },
        )
    }

    private fun safeClose(close: () -> Int): Int = try {
        close()
    } catch (_: Exception) {
        -1
    }

    private class State(
        val index: Int,
        val flow: ControlledFlow,
        capacity: Int,
        startMs: Long,
    ) {
        val queue = ByteRing(capacity)
        val acceptedDigest = MessageDigest.getInstance("SHA-256")
        val writtenDigest = MessageDigest.getInstance("SHA-256")
        var acceptedBytes = 0L
        var writtenBytes = 0L
        var undeliveredAcceptedBytes = 0L
        var maxQueuedBytes = 0
        var sourceEagainCount = 0L
        var writeEagainCount = 0L
        var partialWriteCount = 0L
        var readBackpressureCount = 0L
        var readResumeCount = 0L
        var longestNoProgressMs = 0L
        var firstWriteAtMs: Long? = null
        var completedAtMs: Long? = null
        var shutdownOutputStatus: Int? = null
        var sourceCloseStatus = -1
        var sinkCloseStatus = -1
        var sourceDone = false
        var readBackpressured = false
        var terminal = false
        var pendingOutcome: String? = null
        var outcome = "RUNNING"
        var failureCode: Int? = null
        var deficit = 0
        var lastProgressMs = startMs

        fun finish(reason: String, elapsedMs: Long) {
            outcome = reason
            completedAtMs = elapsedMs
            terminal = true
        }

        fun result() = StreamFlowCounters(
            flowIndex = index,
            outcome = outcome,
            acceptedBytes = acceptedBytes,
            writtenBytes = writtenBytes,
            undeliveredAcceptedBytes = undeliveredAcceptedBytes,
            acceptedSha256 = acceptedDigest.digest().hex(),
            writtenSha256 = writtenDigest.digest().hex(),
            maxQueuedBytes = maxQueuedBytes,
            sourceEagainCount = sourceEagainCount,
            writeEagainCount = writeEagainCount,
            partialWriteCount = partialWriteCount,
            readBackpressureCount = readBackpressureCount,
            readResumeCount = readResumeCount,
            longestNoProgressMs = longestNoProgressMs,
            firstWriteAtMs = firstWriteAtMs,
            completedAtMs = completedAtMs,
            shutdownOutputStatus = shutdownOutputStatus,
            failureCode = failureCode,
            sourceCloseStatus = sourceCloseStatus,
            sinkCloseStatus = sinkCloseStatus,
        )
    }

    private class ByteBudget(rate: Long, private val burst: Int, startMs: Long) {
        private var rateBytesPerSecond = rate
        private var tokens = burst.toLong()
        private var remainder = 0L
        private var lastMs = startMs
        var maxObservedTokens = tokens
            private set

        fun available(nowMs: Long): Long {
            refill(nowMs)
            return tokens
        }

        fun consume(bytes: Int) {
            require(bytes >= 0 && bytes <= tokens)
            tokens -= bytes
        }

        fun changeRate(nowMs: Long, rate: Long) {
            refill(nowMs)
            rateBytesPerSecond = rate
            remainder = 0
        }

        private fun refill(nowMs: Long) {
            val elapsed = nowMs - lastMs
            if (elapsed <= 0) return
            val numerator = elapsed * rateBytesPerSecond + remainder
            tokens = minOf(burst.toLong(), tokens + numerator / 1_000)
            remainder = numerator % 1_000
            lastMs = nowMs
            maxObservedTokens = maxOf(maxObservedTokens, tokens)
        }
    }

    private class ByteRing(capacity: Int) {
        val bytes = ByteArray(capacity)
        var head = 0
            private set
        var size = 0
            private set
        val remaining get() = bytes.size - size
        val contiguousBytes get() = minOf(size, bytes.size - head)

        fun append(source: ByteArray, count: Int) {
            require(count in 0..remaining)
            var copied = 0
            var tail = (head + size) % bytes.size
            while (copied < count) {
                val part = minOf(count - copied, bytes.size - tail)
                source.copyInto(bytes, tail, copied, copied + part)
                copied += part
                tail = (tail + part) % bytes.size
            }
            size += count
        }

        fun remove(count: Int) {
            require(count in 0..size)
            head = (head + count) % bytes.size
            size -= count
            if (size == 0) head = 0
        }

        fun clear() {
            bytes.fill(0)
            head = 0
            size = 0
        }
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
