package com.bufferbloatshaper.vpn

import com.bufferbloatshaper.nativeengine.NativeEngineStopFailureException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NativeLifecycleWatchdogTest {
    @Test
    fun completionBeforeDeadlineCancelsContainment() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val timeout = CountDownLatch(1)
            val watchdog = NativeLifecycleWatchdog(
                executor = executor,
                timeoutMs = 100,
                operation = "test operation"
            ) { timeout.countDown() }

            assertNull(watchdog.complete())
            assertFalse("A completed call must not time out", timeout.await(250, TimeUnit.MILLISECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun deadlineReportsOneTypedFailureAndRetainsItForLateCompletion() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val timeout = CountDownLatch(1)
            val receivedFailure = AtomicReference<NativeEngineStopFailureException?>()
            val timeoutCalls = AtomicInteger(0)
            val watchdog = NativeLifecycleWatchdog(
                executor = executor,
                timeoutMs = 10,
                operation = "metrics collection"
            ) { failure ->
                receivedFailure.set(failure)
                timeoutCalls.incrementAndGet()
                timeout.countDown()
            }

            assertTrue("The deadline should fire", timeout.await(2, TimeUnit.SECONDS))
            val timeoutFailure = receivedFailure.get()
            assertNotNull(timeoutFailure)
            assertTrue(timeoutFailure!!.message!!.contains("metrics collection"))
            assertSame(timeoutFailure, watchdog.complete())
            assertEquals(1, timeoutCalls.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
