package com.bufferbloatshaper.harness

import android.content.Intent
import android.net.Network
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bound to the visible internal Activity only. No Builder, TUN, routes or foreground restart. */
class HarnessService : VpnService() {
    inner class LocalBinder : Binder() { val service get() = this@HarnessService }
    private val gate = Any()
    private var stopped = false
    private val executor = Executors.newSingleThreadExecutor()
    private var cancellation = AtomicBoolean(false)
    @Volatile var running = false
        private set
    @Volatile var result: ExperimentResult? = null
        private set
    @Volatile var cleanupJoined = true
        private set

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else LocalBinder()

    fun start(config: ExperimentConfig, network: Network?): Boolean = synchronized(gate) {
        if (stopped || running) return false
        val request = config.copy(changes = config.changes.toList())
        request.validate()
        cancellation = AtomicBoolean(false)
        val token = cancellation
        result = null
        running = true
        cleanupJoined = false
        executor.execute {
            try {
                result = ExperimentRunner(SocketNative).run(request, token, protect = { fd ->
                    synchronized(gate) { !stopped && !token.get() && protect(fd) }
                }, bind = { fd ->
                    // fromFd duplicates the descriptor; bindSocket affects the same socket.
                    network?.let { selected ->
                        ParcelFileDescriptor.fromFd(fd).use { selected.bindSocket(it.fileDescriptor) }
                    }
                })
            } finally { running = false; cleanupJoined = true }
        }
        true
    }

    fun cancel() { cancellation.set(true) }
    private fun stopHarness() {
        synchronized(gate) { stopped = true; cancellation.set(true) }
        executor.shutdown()
        // Worker alone closes FD; never race close with native I/O or reuse.
        cleanupJoined = executor.awaitTermination(2, TimeUnit.SECONDS)
        // If a platform call is unexpectedly stuck, retain its worker/state; never claim joined.
    }
    override fun onRevoke() { stopHarness(); super.onRevoke() }
    override fun onDestroy() { stopHarness(); super.onDestroy() }
}
