package com.bufferbloatshaper.harness

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Input only: endpoint values must never be copied into exported records. */
data class ExperimentConfig(
    val address: String,
    val port: Int,
    val expectedBytes: Long,
    val receiveBuffer: Int? = null,
    val clamp: Int? = null,
    val cadenceMs: Long = 0,
    val readBytes: Int = 16_384,
    val changes: List<Change> = emptyList(),
    val durationMs: Long = 60_000,
    val stallTimeoutMs: Long = 10_000
) {
    data class Change(val atMs: Long, val receiveBuffer: Int? = null, val clamp: Int? = null,
                      val cadenceMs: Long? = null)
    fun validate() {
        require(address.length in 2..64 && address.all { it in "0123456789abcdefABCDEF:." }) { "Numeric IP address required" }
        require(port in 1..65535 && expectedBytes in 1..268_435_456L)
        require(durationMs in 1_000..120_000 && stallTimeoutMs in 500..30_000)
        require(cadenceMs in 0..2_000 && readBytes in 1..16_384)
        fun buffer(v: Int?) = require(v == null || v in 1..4_194_304)
        buffer(receiveBuffer); buffer(clamp)
        require(changes.size <= 8 && changes.zipWithNext().all { it.first.atMs < it.second.atMs })
        changes.forEach {
            require(it.atMs in 1 until durationMs)
            buffer(it.receiveBuffer); buffer(it.clamp) // zero is deliberately not a restoration operation
            require(it.cadenceMs == null || it.cadenceMs in 0..2_000)
        }
    }
}

const val UNVERIFIED = "UNVERIFIED — REQUIRES PHYSICAL EXPERIMENT"
data class OptionRecord(val atMs: Long, val phase: String, val kind: String, val requested: Int?,
    val constantAvailable: Boolean, val setErrno: Int?, val getErrno: Int?,
    val returned: Long?, val returnedLength: Long) {
    companion object {
        fun decode(at: Long, phase: String, kind: Int, requested: Int?, raw: LongArray): OptionRecord {
            require(raw.size == 5)
            return OptionRecord(at, phase, if (kind == 0) "SO_RCVBUF" else "TCP_WINDOW_CLAMP",
                requested, raw[0] == 1L, raw[1].takeIf { it >= 0 }?.toInt(), raw[2].takeIf { it >= 0 }?.toInt(),
                raw[3].takeIf { raw[0] == 1L && raw[2] == 0L && raw[4] == 4L && it >= 0 }, raw[4])
        }
    }
}
data class TcpInfoRecord(val errno: Int, val returnedLength: Long, val fields: Map<String, Long?>) {
    companion object {
        val names = listOf("state", "rto_us", "snd_mss_bytes", "rcv_mss_bytes", "unacked_segments",
            "retrans_segments", "rtt_us", "rttvar_us", "snd_cwnd_segments", "rcv_rtt_us",
            "rcv_space_bytes", "total_retrans_segments")
        fun decode(raw: LongArray): TcpInfoRecord {
            require(raw.size == names.size + 2)
            return TcpInfoRecord(raw[0].toInt(), raw[1], names.mapIndexed { i, name ->
                name to raw[i + 2].takeIf { raw[0] == 0L && it >= 0 }
            }.toMap())
        }
    }
}
data class Sample(val atMs: Long, val bytes: Long, val noProgressMs: Long, val info: TcpInfoRecord)
data class CadenceEvent(val atMs: Long, val cadenceMs: Long)
/** Placeholders for separately reviewed owner artifacts; the harness never promotes these layers. */
data class EvidenceRecord(val status: String = UNVERIFIED,
    val artifactSha256: String? = null, val reviewedConclusion: String? = null)
data class ExperimentResult(
    val outcome: String, val errno: Int?, val bytes: Long, val sha256: String,
    val elapsedMs: Long, val longestNoProgressMs: Long, val recoveries: Int,
    val options: List<OptionRecord>, val samples: List<Sample>, val samplesOmitted: Int,
    val closeErrno: Int?,
    val cadenceEvents: List<CadenceEvent> = emptyList(),
    val transportEffect: EvidenceRecord = EvidenceRecord(),
    val integrityRecovery: EvidenceRecord = EvidenceRecord(),
    val physicalBenefit: EvidenceRecord = EvidenceRecord()
)

/** Native calls are synchronous and nonblocking except poll, bounded to 50ms. One worker owns FD. */
interface SocketOps {
    fun open(ipv6: Boolean): Int
    fun close(fd: Int): Int
    fun connect(fd: Int, address: String, port: Int): Int
    fun poll(fd: Int, writing: Boolean): Int
    fun socketError(fd: Int): Int
    fun read(fd: Int, target: ByteArray, limit: Int): Int
    fun option(fd: Int, kind: Int, set: Boolean, requested: Int): LongArray
    fun info(fd: Int): LongArray
}
interface ExperimentClock {
    fun nowMs(): Long
    fun pause(ms: Long)
}
object MonotonicClock : ExperimentClock {
    override fun nowMs() = System.nanoTime() / 1_000_000
    override fun pause(ms: Long) = Thread.sleep(ms)
}

/** No callbacks retained after run returns. Cancellation never closes a worker-owned FD concurrently. */
class ExperimentRunner(private val ops: SocketOps, private val clock: ExperimentClock = MonotonicClock) {
    fun run(config: ExperimentConfig, cancelled: AtomicBoolean,
            protect: (Int) -> Boolean, bind: (Int) -> Unit = {}): ExperimentResult {
        config.validate()
        val start = clock.nowMs()
        var fd = -1
        var bytes = 0L
        var lastProgress = start
        var longest = 0L
        var recoveries = 0
        var omitted = 0
        var closeError: Int? = null
        var outcome = "COMPLETE"
        var error: Int? = null
        val digest = MessageDigest.getInstance("SHA-256")
        val options = mutableListOf<OptionRecord>()
        val samples = mutableListOf<Sample>()
        val cadenceEvents = mutableListOf<CadenceEvent>()
        fun checkRun() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw Stop("CANCELLED")
            if (clock.nowMs() - start >= config.durationMs) throw Stop("DEADLINE")
        }
        fun option(kind: Int, value: Int?, phase: String) {
            options += OptionRecord.decode(clock.nowMs() - start, phase, kind, value,
                ops.option(fd, kind, value != null, value ?: 0))
        }
        try {
            checkRun()
            fd = ops.open(config.address.contains(':'))
            if (fd < 0) throw Stop("OPEN_FAILED", -fd)
            checkRun()
            // Protect must precede even bind. No native callback/global reference exists.
            if (!protect(fd)) throw Stop("PROTECT_FAILED")
            checkRun()
            bind(fd)
            checkRun()
            option(0, config.receiveBuffer, "before_connect")
            option(1, config.clamp, "before_connect")
            val connectError = ops.connect(fd, config.address, config.port)
            if (connectError != 0 && connectError != 115) throw Stop("CONNECT_FAILED", connectError)
            if (connectError == 115) {
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
            option(0, null, "connected"); option(1, null, "connected")
            val connected = clock.nowMs()
            cadenceEvents += CadenceEvent(connected - start, config.cadenceMs)
            lastProgress = connected
            var nextSample = connected
            var nextRead = connected
            var cadence = config.cadenceMs
            var changeIndex = 0
            val buffer = ByteArray(config.readBytes)
            while (true) {
                checkRun()
                val now = clock.nowMs()
                longest = maxOf(longest, now - lastProgress)
                while (changeIndex < config.changes.size && now - connected >= config.changes[changeIndex].atMs) {
                    val change = config.changes[changeIndex++]
                    change.receiveBuffer?.let { option(0, it, "dynamic") }
                    change.clamp?.let { option(1, it, "dynamic") }
                    change.cadenceMs?.let {
                        cadence = it; nextRead = now
                        cadenceEvents += CadenceEvent(now - start, it)
                    }
                }
                if (now >= nextSample) {
                    val sample = Sample(now - start, bytes, now - lastProgress, TcpInfoRecord.decode(ops.info(fd)))
                    if (samples.size < 256) samples += sample else omitted++
                    nextSample = now + 250
                }
                if (now - lastProgress >= config.stallTimeoutMs) throw Stop("STALL_TIMEOUT")
                if (now < nextRead) { clock.pause(minOf(50, nextRead - now)); continue }
                val limit = minOf(buffer.size.toLong(), config.expectedBytes - bytes + 1).toInt()
                val count = ops.read(fd, buffer, limit)
                when {
                    count == 0 -> {
                        if (bytes != config.expectedBytes) throw Stop("EARLY_EOF")
                        break
                    }
                    count == -4 -> continue // EINTR
                    count == -11 -> {
                        val ready = ops.poll(fd, false)
                        if (ready < 0 && ready != -4) throw Stop("POLL_FAILED", -ready)
                    }
                    count < 0 -> throw Stop("READ_FAILED", -count)
                    else -> {
                        require(count <= buffer.size)
                        if (now - lastProgress >= 1_000) recoveries++
                        lastProgress = now
                        bytes += count
                        digest.update(buffer, 0, count)
                        if (bytes > config.expectedBytes) throw Stop("EXCESS_DATA")
                        nextRead = now + cadence
                    }
                }
            }
        } catch (e: Stop) { outcome = e.reason; error = e.code
        } catch (_: InterruptedException) { outcome = "CANCELLED"; Thread.currentThread().interrupt()
        } catch (_: Exception) { outcome = "CALLBACK_OR_INTERNAL_FAILURE"
        } finally {
            if (fd >= 0) closeError = ops.close(fd)
        }
        return ExperimentResult(outcome, error, bytes, digest.digest().joinToString("") { "%02x".format(it) },
            clock.nowMs() - start, longest, recoveries, options.toList(), samples.toList(), omitted, closeError,
            cadenceEvents.toList())
    }
    private class Stop(val reason: String, val code: Int? = null) : Exception()
}
