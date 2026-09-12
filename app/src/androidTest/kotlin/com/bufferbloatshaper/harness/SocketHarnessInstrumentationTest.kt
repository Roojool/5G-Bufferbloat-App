package com.bufferbloatshaper.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import java.util.concurrent.CountDownLatch

/** Loopback only; no VPN consent, routes, external server, or physical efficacy claim. */
@RunWith(AndroidJUnit4::class)
class SocketHarnessInstrumentationTest {
    @Test fun preparedRealServiceProtectsWithoutRouteAndCancelsCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Owner/emulator preparation is explicit. Never dismiss VPN consent or replace a VPN here.
        assumeTrue("Prepare the internal VPN permission before this optional API-only test", VpnService.prepare(context) == null)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun hasVpn() = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        assumeTrue("Do not test alongside an existing VPN", !hasVpn())
        val ready = CountDownLatch(1)
        var service: HarnessService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as HarnessService.LocalBinder).service; ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, HarnessService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val harness = requireNotNull(service)
            ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { listener ->
                listener.soTimeout = 5000
                assertTrue(harness.start(ExperimentConfig(listener.inetAddress.hostAddress!!, listener.localPort, 100), null))
                listener.accept().use {
                    assertFalse(hasVpn())
                    harness.cancel()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (harness.running && System.nanoTime() < deadline) Thread.sleep(10)
                    assertFalse(harness.running); assertTrue(harness.cleanupJoined)
                    assertEquals("CANCELLED", harness.result?.outcome)
                    assertEquals(0, harness.result?.closeErrno)
                    assertFalse(hasVpn())
                }
            }
        } finally { service?.cancel(); context.unbindService(connection) }
    }
    @Test fun nativeInfoDecoderChecksEveryBoundaryAndPreservesErrors() {
        val ends = SocketNative.fieldEnds()
        val keys = TcpInfoRecord.names
        val values = listOf(7L, 0L, 0L, 0L, 0L, 0L, 123456L, 0L, 0L, 0L, 0L, 19L)
        ends.forEachIndexed { i, end ->
            assertNull(TcpInfoRecord.decode(SocketNative.decodeFixture(end - 1, 0)).fields[keys[i]])
            assertEquals(values[i], TcpInfoRecord.decode(SocketNative.decodeFixture(end, 0)).fields[keys[i]])
        }
        assertTrue(TcpInfoRecord.decode(SocketNative.decodeFixture(0, 0)).fields.values.all { it == null })
        val failed = TcpInfoRecord.decode(SocketNative.decodeFixture(1000, 9))
        assertEquals(9, failed.errno); assertTrue(failed.fields.values.all { it == null })
    }
    @Test fun invalidDescriptorPreservesNativeErrno() {
        assertEquals(9, TcpInfoRecord.decode(SocketNative.info(-1)).errno)
        assertEquals(9, SocketNative.option(-1, 0, true, 4096)[1].toInt())
    }
    @Test fun nativeSocketIsClosedOnDeniedProtection() {
        var observed = -1
        val result = ExperimentRunner(SocketNative).run(ExperimentConfig("127.0.0.1", 1, 1),
            AtomicBoolean(), { observed = it; false })
        assertEquals("PROTECT_FAILED", result.outcome)
        assertEquals(9, SocketNative.socketError(observed))
    }
    @Test fun nativeLoopbackExactStreamAndRedactedExport() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5000
            val writer = executor.submit {
                server.accept().use { socket -> socket.getOutputStream().write(byteArrayOf(1, 2, 3, 4, 5)) }
            }
            try {
                val config = ExperimentConfig(server.inetAddress.hostAddress!!, server.localPort, 5)
                // Deliberate test seam: true is a fake protector, NOT VpnService protection evidence.
                val result = ExperimentRunner(SocketNative).run(config, AtomicBoolean(), { true })
                assertEquals("COMPLETE", result.outcome); assertEquals(5L, result.bytes)
                assertEquals(0, result.closeErrno)
                val json = result.toJson(config)
                assertFalse(json.contains(config.address)); assertFalse(json.contains("\"port\""))
                assertTrue(json.contains(UNVERIFIED)); assertTrue(result.samples.isNotEmpty())
                writer.get(5, TimeUnit.SECONDS)
            } finally { executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS) }
        }
    }
}
