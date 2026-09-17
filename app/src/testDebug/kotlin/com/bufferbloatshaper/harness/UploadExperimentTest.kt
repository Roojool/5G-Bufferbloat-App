package com.bufferbloatshaper.harness

import com.bufferbloatshaper.harness.stream.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class UploadExperimentTest {
    private class Clock : StreamHarnessClock {
        var now = 0L
        override fun nowMs() = now
        override fun pause(ms: Long) { now += ms }
    }
    private class Ops(val clock: Clock) : UploadSocketOps {
        val events = mutableListOf<String>()
        val bytes = mutableMapOf<Int, ByteArrayOutputStream>()
        val receipts = mutableMapOf<Int, ByteArray>()
        var calls = 0
        var writeError = 0
        var openFailure = false
        var closeError = 0
        var abortError = 0
        var connectError = 0
        var socketError = 0
        var setError = 0L
        var readback = 32768L
        var optionLength = 4L
        var observationsAvailable = true
        var malformedInfo = false
        var receiptMode = "normal"
        var afterWrite: () -> Unit = {}
        override fun open(ipv6: Boolean): Int {
            if (openFailure) return -24
            val fd = bytes.size + 10
            events += "open:$fd"; bytes[fd] = ByteArrayOutputStream(); return fd
        }
        override fun close(fd: Int): Int { events += "close:$fd"; return closeError }
        override fun abort(fd: Int): Int { events += "abort:$fd"; return abortError }
        override fun connect(fd: Int, address: String, port: Int): Int { events += "connect:$fd"; return connectError }
        override fun poll(fd: Int, writing: Boolean): Int { clock.now += 50; return if (socketError != 0) 1 else 0 }
        override fun socketError(fd: Int) = socketError
        override fun option(fd: Int, kind: Int, set: Boolean, requested: Int): LongArray {
            events += "option:$fd"; assertEquals(2, kind)
            return longArrayOf(1, if (set) setError else -1, 0, readback, optionLength)
        }
        override fun info(fd: Int) = if (malformedInfo) longArrayOf(0) else longArrayOf(if (observationsAvailable) 0 else 92, 104) +
            LongArray(12) { if (observationsAvailable) 0 else -1 }
        override fun sendQueue(fd: Int, notSent: Boolean) =
            if (observationsAvailable) longArrayOf(1, 0, 12) else longArrayOf(1, 95, -1)
        override fun write(fd: Int, source: ByteArray, offset: Int, length: Int): Int {
            events += "write:$fd"; calls++
            if (writeError != 0) return writeError
            if (calls % 4 == 0) return -11
            if (calls % 7 == 0) return -4
            val count = minOf(length, 7)
            bytes.getValue(fd).write(source, offset, count)
            afterWrite()
            return count
        }
        override fun shutdownOutput(fd: Int): Int {
            events += "fin:$fd"
            val hash = hash(bytes.getValue(fd).toByteArray())
            receipts[fd] = when (receiptMode) {
                "wrong" -> ("0".repeat(64) + "\n").toByteArray()
                "short" -> "bad\n".toByteArray()
                "excess" -> "x".repeat(66).toByteArray()
                else -> (hash + "\n").toByteArray()
            }
            return if (receiptMode == "finFailure") 32 else 0
        }
        override fun read(fd: Int, target: ByteArray, limit: Int): Int {
            if (receiptMode == "stalled") return -11
            if (receiptMode == "reset") return -104
            val data = receipts.getValue(fd)
            val count = minOf(data.size, limit, 9)
            data.copyInto(target, 0, 0, count); receipts[fd] = data.drop(count).toByteArray()
            return count
        }
    }
    private val scope = CapabilityScope(31, "4.14", "arm64-v8a", BatchTransport.WIFI, false)
    private fun config(flows: List<Long> = listOf(257, 37), duration: Long = 2000, stall: Long = 500) =
        UploadConfig(ExperimentConfig("192.0.2.1", 39001, flows.sum(), durationMs = duration, stallTimeoutMs = stall),
            flows, StreamHarnessConfig(100000, 64, 16, 64, 256, readChunkBytes = 16,
                maxWriteBytes = 16, durationMs = duration, stallTimeoutMs = stall))
    private fun run(ops: Ops, config: UploadConfig = config(), cancel: AtomicBoolean = AtomicBoolean(),
        protect: (Int) -> Boolean = { ops.events += "protect:$it"; true },
        bind: (Int) -> Unit = { ops.events += "bind:$it" }) = UploadRunner(ops, ops.clock).run(config, scope, cancel, protect, bind)

    @Test fun exactIntegrityPartialWritesEagainFinAndOwnedClose() {
        val ops = Ops(Clock()); val result = run(ops)
        assertEquals("COMPLETE", result.outcome)
        result.stream!!.flows.forEach { f ->
            val data = ops.bytes.getValue(f.flowIndex + 10).toByteArray()
            assertEquals(data.indices.map { (it % 251).toByte() }, data.toList())
            assertEquals(f.acceptedSha256, hash(data)); assertEquals(f.writtenSha256, hash(data))
            assertEquals(0L, f.undeliveredAcceptedBytes)
            val fd = f.flowIndex + 10
            assertTrue(ops.events.indexOf("protect:$fd") < ops.events.indexOf("bind:$fd"))
            assertTrue(ops.events.indexOf("bind:$fd") < ops.events.indexOf("connect:$fd"))
            assertTrue(ops.events.lastIndexOf("write:$fd") < ops.events.indexOf("fin:$fd"))
            assertEquals(1, ops.events.count { it == "close:$fd" })
        }
        assertTrue(ops.events.none { it.startsWith("abort") })
        assertTrue(result.stream.flows.sumOf { it.partialWriteCount } > 0)
        assertTrue(result.stream.flows.sumOf { it.writeEagainCount } > 0)
        assertEquals(16384, result.sockets.first().options.first().requested)
        assertEquals(32768L, result.sockets.first().options.first().returned)
        assertTrue(result.samples.all { it.progress.queuedBytes.sum() <= 256 })
        assertTrue(result.capabilities.all { it.getValue("IPV4_TCP_FORWARDING").state == CapabilityState.UNKNOWN })
    }

    @Test fun protectDeniedOrThrownNeverBindsConnectsOrWritesAndClosesOnce() {
        for (throwing in listOf(false, true)) {
            val ops = Ops(Clock())
            val result = run(ops, protect = { if (throwing) error("private endpoint detail") else false })
            assertEquals(if (throwing) "CALLBACK_OR_INTERNAL_FAILURE" else "PROTECT_FAILED", result.outcome)
            assertEquals(listOf("open:10", "abort:10", "close:10"), ops.events)
            assertNull(result.stream)
        }
    }

    @Test fun secondSocketBindFailureClosesBothWithoutWriting() {
        val ops = Ops(Clock()); val result = run(ops, bind = { if (it == 11) error("private") })
        assertEquals("CALLBACK_OR_INTERNAL_FAILURE", result.outcome)
        assertTrue(ops.events.none { it.startsWith("write") })
        assertEquals(listOf(10, 11), result.sockets.indices.map { it + 10 })
        for (fd in 10..11) assertEquals(1, ops.events.count { it == "close:$fd" })
    }

    @Test fun missingOrExcessiveSendBufferReadbackBlocksBeforeConnect() {
        for (variant in 0..2) {
            val ops = Ops(Clock())
            when (variant) { 0 -> ops.setError = 1; 1 -> ops.readback = 4194304; 2 -> ops.optionLength = 2 }
            assertEquals("SEND_BUFFER_UNAVAILABLE", run(ops).outcome)
            assertTrue(ops.events.none { it.startsWith("connect") || it.startsWith("write") })
            assertEquals(1, ops.events.count { it == "close:10" })
        }
    }

    @Test fun connectFailureAndPendingDeadlineAreBounded() {
        val ops = Ops(Clock()); ops.connectError = 115
        assertEquals("DEADLINE", run(ops).outcome)
        assertEquals(2000L, ops.clock.now)
        val refused = Ops(Clock()); refused.connectError = 115; refused.socketError = 111
        val failure = run(refused)
        assertEquals("CONNECT_FAILED", failure.outcome); assertEquals(111, failure.errno)
        assertTrue(refused.events.none { it.startsWith("write") })
    }

    @Test fun cancellationAndStallAccountForAcceptedBytesAndRequestReset() {
        for (cancelRun in listOf(true, false)) {
            val ops = Ops(Clock()); val token = AtomicBoolean()
            if (cancelRun) ops.afterWrite = { token.set(true) } else ops.writeError = -11
            val result = run(ops, cancel = token)
            assertEquals(if (cancelRun) "CANCELLED" else "STALLED", result.outcome)
            assertTrue(result.stream!!.flows.any { it.undeliveredAcceptedBytes > 0 })
            result.stream.flows.forEach { assertEquals(it.acceptedBytes - it.writtenBytes, it.undeliveredAcceptedBytes) }
            assertTrue(ops.events.none { it.startsWith("fin") })
            assertEquals(2, ops.events.count { it.startsWith("abort") })
        }
    }

    @Test fun resetAndFinFailureCannotReportSuccess() {
        val reset = Ops(Clock()); reset.writeError = -104
        assertEquals("PARTIAL_FAILURE", run(reset).outcome)
        val fin = Ops(Clock()); fin.receiptMode = "finFailure"
        assertEquals("PARTIAL_FAILURE", run(fin).outcome)
    }

    @Test fun receiptsAreBoundedAndRequiredForDelivery() {
        for ((mode, outcome) in mapOf("wrong" to "INTEGRITY_FAILED", "short" to "RECEIPT_MALFORMED",
            "excess" to "RECEIPT_EXCESS", "stalled" to "RECEIPT_STALLED", "reset" to "RECEIPT_FAILED")) {
            val ops = Ops(Clock()); ops.receiptMode = mode
            val result = run(ops)
            assertEquals(outcome, result.outcome)
            assertEquals("COMPLETE", result.stream!!.outcome) // kernel acceptance alone is insufficient
            assertTrue(result.sockets.any { it.receiptStatus == "UNAVAILABLE" })
            assertTrue(ops.clock.now <= 2000)
        }
    }

    @Test fun unavailableObservationsNeverBecomeZeroOrDisableSafeUpload() {
        val ops = Ops(Clock()); ops.observationsAvailable = false
        val result = run(ops)
        assertEquals("COMPLETE", result.outcome)
        assertTrue(result.samples.flatMap { it.sendQueues }.all { it.bytes == null && it.errno == 95 })
        assertTrue(result.samples.flatMap { it.tcpInfo }.all { it.fields.values.all { value -> value == null } })
        assertEquals(CapabilityState.UNAVAILABLE, result.capabilities.first().getValue("TCP_INFO").state)
        assertNull(QueueObservation.decode(longArrayOf(1, 0)).bytes)
        assertNull(QueueObservation.decode(longArrayOf(0, -1, 999)).bytes)
        val malformed = Ops(Clock()); malformed.malformedInfo = true
        val degraded = run(malformed)
        assertEquals("COMPLETE", degraded.outcome)
        assertEquals(CapabilityReason.MALFORMED, degraded.capabilities.first().getValue("TCP_INFO").reason)
    }

    @Test fun cleanupErrorsAndNoSocketSetupAreExplicit() {
        val ops = Ops(Clock()); ops.closeError = 5
        assertEquals("CLEANUP_FAILED", run(ops).outcome)
        val failed = Ops(Clock()); failed.writeError = -104; failed.abortError = 92
        assertTrue(run(failed).sockets.all { it.abortErrno == 92 })
        val unopened = Ops(Clock()); unopened.openFailure = true
        assertEquals("OPEN_FAILED", run(unopened).outcome); assertTrue(unopened.events.isEmpty())
    }

    @Test fun capabilitiesRequireFreshScopedEvidenceAndNoModelAllowlist() {
        val caps = Stage1Capabilities(scope)
        assertFalse(caps.mandatorySatisfied(0))
        assertEquals(CapabilityReason.NOT_PROBED, caps.get(Stage1Capability.PROTECTION, 0).reason)
        caps.record(Stage1Capability.PROTECTION, 0, true)
        assertTrue(caps.enabled(Stage1Capability.PROTECTION, 1))
        assertFalse(caps.enabled(Stage1Capability.PROTECTION, 120001))
        assertEquals(CapabilityReason.STALE, caps.get(Stage1Capability.PROTECTION, 120001).reason)
        assertFalse(caps.enabled(Stage1Capability.PROTECTION, 1, scope.copy(transport = BatchTransport.CELLULAR)))
        caps.record(Stage1Capability.TCP_INFO, 1, false)
        assertEquals(CapabilityReason.MALFORMED, caps.get(Stage1Capability.TCP_INFO, 1).reason)
        caps.record(Stage1Capability.TCP_INFO, 2, true, 92)
        assertEquals(CapabilityReason.CALL_FAILED, caps.get(Stage1Capability.TCP_INFO, 2).reason)
        assertFalse(caps.enabled(Stage1Capability.TCP_INFO, 2))
        Stage1Capability.entries.filter { it.mandatory }.forEach { caps.record(it, 3, true) }
        assertTrue(caps.mandatorySatisfied(3))
        assertFalse(caps.enabled(Stage1Capability.DOWNLOAD_CONTROL, 3))
        assertFalse(caps.enabled(Stage1Capability.PROTECTION, 2)) // future/stale time
    }

    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
