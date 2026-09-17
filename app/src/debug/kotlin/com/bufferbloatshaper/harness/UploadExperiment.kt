package com.bufferbloatshaper.harness

import com.bufferbloatshaper.harness.stream.*
import java.util.concurrent.atomic.AtomicBoolean

interface UploadSocketOps : SocketOps {
    fun write(fd: Int, source: ByteArray, offset: Int, length: Int): Int
    fun shutdownOutput(fd: Int): Int
    fun abort(fd: Int): Int
    /** compiled availability, errno (-1 = not attempted), value (-1 = unavailable). */
    fun sendQueue(fd: Int, notSent: Boolean): LongArray
}

data class UploadConfig(val endpoint: ExperimentConfig, val flowBytes: List<Long>,
    val pacing: StreamHarnessConfig, val sendBufferBytes: Int = 16_384,
    val maxKernelSendBufferBytes: Int = 131_072) {
    fun validate() {
        endpoint.validate(); pacing.validate(flowBytes.size)
        require(flowBytes.size in 1..4 && flowBytes.all { it in 1..268_435_456L })
        require(flowBytes.sum() == endpoint.expectedBytes)
        require(sendBufferBytes in 1..1_048_576 && maxKernelSendBufferBytes in 1..4_194_304)
        require(pacing.maxWriteBytes <= 16_384 && pacing.durationMs == endpoint.durationMs)
    }
}

data class QueueObservation(val available: Boolean?, val errno: Int?, val bytes: Long?) {
    companion object {
        fun decode(raw: LongArray): QueueObservation {
            if (raw.size != 3 || raw[0] !in 0L..1L) return QueueObservation(null, null, null)
            return QueueObservation(raw[0] == 1L, raw[1].takeIf { it >= 0 }?.toInt(),
                raw[2].takeIf { raw[0] == 1L && raw[1] == 0L && it >= 0 })
        }
    }
}

data class UploadSample(val progress: StreamProgress, val sendQueues: List<QueueObservation>,
    val notSentQueues: List<QueueObservation>, val tcpInfo: List<TcpInfoRecord>,
    val intervalMs: Long?, val writeAcceptanceBytesPerSecond: List<Double?>)
data class UploadSocketResult(val flowIndex: Int, val options: List<OptionRecord>,
    val receiptSha256: String?, val receiptStatus: String, val abortErrno: Int?, val closeErrno: Int?)
data class UploadResult(val outcome: String, val elapsedMs: Long, val stream: StreamHarnessResult?,
    val samples: List<UploadSample>, val samplesOmitted: Int, val sockets: List<UploadSocketResult>,
    val capabilities: List<Map<String, CapabilityEvidence>>, val errno: Int?)

/** Owned synthetic source; no app interception and no retained payload allocation. */
internal class SyntheticStream(private val count: Long) : StreamSource {
    private var position = 0L
    override fun read(target: ByteArray, limit: Int): Int {
        if (position == count) return -1
        val size = minOf(limit.toLong(), count - position).toInt()
        repeat(size) { target[it] = ((position + it) % 251).toByte() }
        position += size
        return size
    }
    override fun close() = 0
}

/**
 * One worker owns all FDs from open through close, including partial setup failure.
 * FIN follows queue drain; COMPLETE additionally requires the receiver's exact SHA-256 and EOF.
 * Failure/cancel/deadline requests SO_LINGER(1,0) then closes once, recording reset-request errors.
 * Written means kernel acceptance, not delivery; missing receipts leave delivery UNVERIFIED.
 */
class UploadRunner(private val ops: UploadSocketOps,
    private val clock: StreamHarnessClock = StreamMonotonicClock) {
    fun run(config: UploadConfig, scope: CapabilityScope, cancelled: AtomicBoolean,
        protect: (Int) -> Boolean, bind: (Int) -> Unit): UploadResult {
        config.validate()
        val start = clock.nowMs()
        val owned = mutableListOf<Owned>()
        val samples = mutableListOf<UploadSample>()
        var omitted = 0
        var stream: StreamHarnessResult? = null
        var outcome = "COMPLETE"
        var errno: Int? = null
        fun elapsed() = clock.nowMs() - start
        fun checkRun() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw Stop("CANCELLED")
            if (elapsed() >= config.endpoint.durationMs) throw Stop("DEADLINE")
        }
        try {
            for (index in config.flowBytes.indices) {
                checkRun()
                val fd = ops.open(config.endpoint.address.contains(':'))
                if (fd < 0) throw Stop("OPEN_FAILED", -fd)
                val socket = Owned(index, fd, Stage1Capabilities(scope))
                owned += socket // own immediately, before any fallible callback
                checkRun()
                val protected = try { protect(fd) } catch (e: Exception) {
                    socket.caps.failed(Stage1Capability.PROTECTION, elapsed()); throw e
                }
                if (!protected) {
                    socket.caps.failed(Stage1Capability.PROTECTION, elapsed()); throw Stop("PROTECT_FAILED")
                }
                socket.caps.record(Stage1Capability.PROTECTION, elapsed(), true)
                checkRun()
                try { bind(fd) } catch (e: Exception) {
                    socket.caps.failed(Stage1Capability.NETWORK_BINDING, elapsed()); throw e
                }
                socket.caps.record(Stage1Capability.NETWORK_BINDING, elapsed(), true)
                checkRun()
                fun buffer(set: Boolean) {
                    val option = OptionRecord.decode(elapsed(), if (set) "before_connect" else "connected", 2,
                        if (set) config.sendBufferBytes else null,
                        ops.option(fd, 2, set, config.sendBufferBytes))
                    socket.options += option
                    val valid = option.constantAvailable && option.getErrno == 0 &&
                        option.returned != null && option.returned in 1..config.maxKernelSendBufferBytes.toLong() &&
                        (!set || option.setErrno == 0)
                    socket.caps.record(Stage1Capability.BOUNDED_SEND_BUFFER, elapsed(), valid,
                        option.setErrno?.takeIf { it != 0 } ?: option.getErrno)
                    if (!valid) throw Stop("SEND_BUFFER_UNAVAILABLE")
                }
                buffer(true)
                val error = ops.connect(fd, config.endpoint.address, config.endpoint.port)
                if (error != 0 && error != 115) throw Stop("CONNECT_FAILED", error)
                if (error == 115) {
                    while (true) {
                        checkRun()
                        val ready = ops.poll(fd, true)
                        if (ready == -4) continue
                        if (ready < 0) throw Stop("POLL_FAILED", -ready)
                        if (ready != 0) break
                    }
                    val e = ops.socketError(fd)
                    if (e != 0) throw Stop("CONNECT_FAILED", e)
                }
                buffer(false)
            }
            checkRun()
            val remaining = config.endpoint.durationMs - elapsed()
            var nextSample = 0L
            val flows = owned.map { socket -> ControlledFlow(SyntheticStream(config.flowBytes[socket.index]),
                object : StreamSink {
                    override fun write(source: ByteArray, offset: Int, length: Int): Int {
                        val count = ops.write(socket.fd, source, offset, length)
                        return if (count == -11 || count == -4) 0 else count
                    }
                    override fun shutdownOutput(): Int = ops.shutdownOutput(socket.fd)
                    // The outer worker still owns FD while checking the receiver receipt.
                    override fun close() = 0
                }) }
            stream = StreamPacingRunner(clock).run(config.pacing.copy(durationMs = remaining,
                stallTimeoutMs = minOf(config.pacing.stallTimeoutMs, remaining),
                rateChanges = config.pacing.rateChanges.filter { it.atMs < remaining }), flows, cancelled) { progress ->
                if (progress.atMs >= nextSample) {
                    nextSample = progress.atMs + 250
                    if (samples.size < 480) {
                        fun queue(fd: Int, notSent: Boolean) = try { QueueObservation.decode(ops.sendQueue(fd, notSent)) }
                            catch (_: Exception) { QueueObservation(null, null, null) }
                        val queues = owned.map { queue(it.fd, false) }
                        val notSent = owned.map { queue(it.fd, true) }
                        val info = owned.map { try { TcpInfoRecord.decode(ops.info(it.fd)) }
                            catch (_: Exception) { TcpInfoRecord(-1, 0, TcpInfoRecord.names.associateWith { null }) } }
                        owned.forEachIndexed { i, socket ->
                            socket.caps.record(Stage1Capability.SEND_QUEUE, elapsed(), queues[i].bytes != null, queues[i].errno)
                            socket.caps.record(Stage1Capability.NOT_SENT_QUEUE, elapsed(), notSent[i].bytes != null, notSent[i].errno)
                            socket.caps.record(Stage1Capability.TCP_INFO, elapsed(), info[i].fields.values.any { it != null }, info[i].errno.takeIf { it >= 0 })
                        }
                        val previous = samples.lastOrNull()?.progress
                        val interval = previous?.let { progress.atMs - it.atMs }?.takeIf { it > 0 }
                        val rates = progress.writtenBytes.mapIndexed { i, bytes ->
                            if (interval == null) null else (bytes - previous!!.writtenBytes[i]) * 1000.0 / interval
                        }
                        samples += UploadSample(progress, queues, notSent, info, interval, rates)
                    } else omitted++
                }
            }
            if (stream.outcome != "COMPLETE") throw Stop(stream.outcome)
            // All writes drained and FIN issued. Receive bounded receipts concurrently so a slow
            // peer cannot hide other flows' progress. This phase shares the original deadline.
            val scratch = ByteArray(66)
            var lastProgress = clock.nowMs()
            while (owned.any { it.receiptStatus == "UNAVAILABLE" }) {
                checkRun()
                if (clock.nowMs() - lastProgress >= config.endpoint.stallTimeoutMs) throw Stop("RECEIPT_STALLED")
                owned.filter { it.receiptStatus == "UNAVAILABLE" }.forEach { socket ->
                    val count = ops.read(socket.fd, scratch, minOf(66 - socket.receipt.size, scratch.size))
                    when {
                        count == -11 || count == -4 -> Unit
                        count < 0 -> throw Stop("RECEIPT_FAILED", -count)
                        count == 0 -> {
                            val value = socket.receipt.toByteArray().toString(Charsets.US_ASCII)
                            if (!Regex("[0-9a-f]{64}\\n").matches(value)) throw Stop("RECEIPT_MALFORMED")
                            socket.receiptHash = value.trim()
                            if (socket.receiptHash != stream.flows[socket.index].writtenSha256) throw Stop("INTEGRITY_FAILED")
                            socket.receiptStatus = "COMPLETE"
                            lastProgress = clock.nowMs()
                        }
                        else -> {
                            require(count <= scratch.size && count <= 66 - socket.receipt.size)
                            repeat(count) { socket.receipt.add(scratch[it]) }
                            if (socket.receipt.size > 65) throw Stop("RECEIPT_EXCESS")
                            lastProgress = clock.nowMs()
                        }
                    }
                }
                if (owned.any { it.receiptStatus == "UNAVAILABLE" }) clock.pause(1)
            }
        } catch (e: Stop) { outcome = e.reason; errno = e.code
        } catch (_: InterruptedException) { outcome = "CANCELLED"; Thread.currentThread().interrupt()
        } catch (_: Exception) { outcome = "CALLBACK_OR_INTERNAL_FAILURE"
        } finally {
            owned.forEach { socket ->
                if (outcome != "COMPLETE") socket.abortErrno = safe { ops.abort(socket.fd) }
                socket.closeErrno = safe { ops.close(socket.fd) }
            }
            if (outcome == "COMPLETE" && owned.any { it.closeErrno != 0 }) outcome = "CLEANUP_FAILED"
        }
        return UploadResult(outcome, elapsed(), stream, samples, omitted, owned.map {
            UploadSocketResult(it.index, it.options.toList(), it.receiptHash, it.receiptStatus, it.abortErrno, it.closeErrno)
        }, owned.map { it.caps.snapshot(elapsed()) }, errno)
    }

    private fun safe(call: () -> Int) = try { call() } catch (_: Exception) { -1 }
    private class Stop(val reason: String, val code: Int? = null) : Exception()
    private class Owned(val index: Int, val fd: Int, val caps: Stage1Capabilities) {
        val options = mutableListOf<OptionRecord>()
        val receipt = ArrayList<Byte>(66)
        var receiptHash: String? = null
        var receiptStatus = "UNAVAILABLE"
        var abortErrno: Int? = null
        var closeErrno: Int? = null
    }
}
