package com.bufferbloatshaper.harness

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class ExperimentTest {
    private class Clock : ExperimentClock {
        var time = 0L
        override fun nowMs() = time
        override fun pause(ms: Long) { time += ms }
    }
    private class Fake(val clock: Clock) : SocketOps {
        val calls = mutableListOf<String>()
        var closed = 0
        var data = byteArrayOf(1, 2, 3, 4, 5)
        var offset = 0
        var block = false
        var readError: Int? = null
        override fun open(ipv6: Boolean): Int { calls += "open"; return 9 }
        override fun close(fd: Int): Int { calls += "close"; closed++; return 0 }
        override fun connect(fd: Int, address: String, port: Int): Int { calls += "connect"; return 0 }
        override fun poll(fd: Int, writing: Boolean): Int { clock.time += 50; return 0 }
        override fun socketError(fd: Int) = 0
        override fun read(fd: Int, target: ByteArray, limit: Int): Int {
            clock.time += 1
            readError?.let { return -it }
            if (block) return -11
            val count = minOf(2, limit, data.size - offset)
            data.copyInto(target, 0, offset, offset + count); offset += count
            return count
        }
        override fun option(fd: Int, kind: Int, set: Boolean, requested: Int): LongArray {
            calls += "option:$kind:$set:$requested"
            return longArrayOf(1, if (set) 0 else -1, 0, 8192, 4)
        }
        override fun info(fd: Int) = longArrayOf(0, 104) + LongArray(12) { -1 }
    }
    private fun config() = ExperimentConfig("127.0.0.1", 12345, 5)

    @Test fun protectFailureClosesBeforeAnyOtherSocketOperation() {
        val clock = Clock(); val ops = Fake(clock)
        val result = ExperimentRunner(ops, clock).run(config(), AtomicBoolean(), { ops.calls += "protect"; false })
        assertEquals(listOf("open", "protect", "close"), ops.calls)
        assertEquals("PROTECT_FAILED", result.outcome); assertEquals(1, ops.closed)
    }
    @Test fun protectsThenBindsThenConnectsAndHashesExactBytes() {
        val clock = Clock(); val ops = Fake(clock)
        val result = ExperimentRunner(ops, clock).run(config(), AtomicBoolean(),
            { ops.calls += "protect"; true }, { ops.calls += "bind" })
        assertEquals(listOf("open", "protect", "bind"), ops.calls.take(3))
        assertTrue(ops.calls.indexOf("connect") > ops.calls.indexOf("protect"))
        assertEquals("COMPLETE", result.outcome); assertEquals(5L, result.bytes)
        val expected = MessageDigest.getInstance("SHA-256").digest(ops.data).joinToString("") { "%02x".format(it) }
        assertEquals(expected, result.sha256); assertEquals(1, ops.closed)
        assertEquals(UNVERIFIED, result.integrityRecovery.status)
    }
    @Test fun cancellationFromProtectionNeverConnectsAndLeavesNoCallbacks() {
        val clock = Clock(); val ops = Fake(clock); val token = AtomicBoolean()
        var callbacks = 0
        val result = ExperimentRunner(ops, clock).run(config(), token, { callbacks++; token.set(true); true })
        assertEquals("CANCELLED", result.outcome); assertFalse(ops.calls.contains("connect"))
        assertEquals(1, callbacks); assertEquals(1, ops.closed)
    }
    @Test fun thrownProtectionAndBindingBothCleanUp() {
        for (throwOnProtect in listOf(true, false)) {
            val clock = Clock(); val ops = Fake(clock)
            val result = ExperimentRunner(ops, clock).run(config(), AtomicBoolean(),
                { if (throwOnProtect) error("private endpoint text") else true }, { error("private endpoint text") })
            assertEquals("CALLBACK_OR_INTERNAL_FAILURE", result.outcome)
            assertFalse(result.toString().contains("private endpoint text")); assertEquals(1, ops.closed)
        }
    }
    @Test fun readErrnoAndShortStreamAreNotSuccess() {
        val clock = Clock(); val ops = Fake(clock); ops.readError = 104
        val result = ExperimentRunner(ops, clock).run(config(), AtomicBoolean(), { true })
        assertEquals("READ_FAILED", result.outcome); assertEquals(104, result.errno)
        ops.readError = null
        val short = ExperimentRunner(ops, clock).run(config().copy(expectedBytes = 6), AtomicBoolean(), { true })
        assertEquals("EARLY_EOF", short.outcome); assertEquals(2, ops.closed)
    }
    @Test fun stallsTimeOutAndCloseExactlyOnce() {
        val clock = Clock(); val ops = Fake(clock); ops.block = true
        val result = ExperimentRunner(ops, clock).run(config().copy(stallTimeoutMs = 500), AtomicBoolean(), { true })
        assertEquals("STALL_TIMEOUT", result.outcome); assertEquals(1, ops.closed)
    }
    @Test fun longRunCapsSamplesAndDynamicOptionRecords() {
        val clock = Clock(); val ops = Fake(clock)
        ops.data = ByteArray(2_000)
        val result = ExperimentRunner(ops, clock).run(config().copy(expectedBytes = 2_000,
            cadenceMs = 100, durationMs = 120_000, changes = listOf(ExperimentConfig.Change(1000, clamp = 4096))),
            AtomicBoolean(), { true })
        assertEquals(256, result.samples.size); assertTrue(result.samplesOmitted > 0)
        assertEquals(5, result.options.size); assertEquals("dynamic", result.options.last().phase)
        assertEquals(1, ops.closed)
    }
    @Test fun failedAndUnavailableOptionsDoNotBecomeSuccessfulReadbacks() {
        val failed = OptionRecord.decode(0, "test", 1, 100, longArrayOf(1, 22, 92, 0, 4))
        assertEquals(22, failed.setErrno); assertEquals(92, failed.getErrno); assertNull(failed.returned)
        val unsupported = OptionRecord.decode(0, "test", 1, null, longArrayOf(0, -1, 92, -1, 0))
        assertFalse(unsupported.constantAvailable); assertNull(unsupported.setErrno)
        val doubled = OptionRecord.decode(0, "test", 0, 4096, longArrayOf(1, 0, 0, 8192, 4))
        assertEquals(4096, doubled.requested); assertEquals(8192L, doubled.returned)
    }
    @Test fun missingInfoFieldsAreNullAndErrorsOverrideValues() {
        val absent = TcpInfoRecord.decode(longArrayOf(0, 0) + LongArray(12) { -1 })
        assertTrue(absent.fields.values.all { it == null })
        val failed = TcpInfoRecord.decode(longArrayOf(92, 104) + LongArray(12) { 123 })
        assertEquals(92, failed.errno); assertTrue(failed.fields.values.all { it == null })
    }
    @Test fun zeroClampAndUnboundedInputsAreRejectedBeforeOpening() {
        for (bad in listOf(config().copy(clamp = 0), config().copy(readBytes = 100_000),
            config().copy(expectedBytes = Long.MAX_VALUE), config().copy(address = "example.com"),
            config().copy(changes = List(9) { ExperimentConfig.Change(it.toLong() + 1) }))) {
            val clock = Clock(); val ops = Fake(clock)
            try { ExperimentRunner(ops, clock).run(bad, AtomicBoolean(), { true }); fail("accepted invalid input") }
            catch (_: IllegalArgumentException) { assertTrue(ops.calls.isEmpty()) }
        }
    }
}
