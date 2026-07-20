package com.bufferbloatshaper.vpn

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * General UDP relay for non-DNS UDP traffic (QUIC, gaming, video, etc.).
 *
 * The original code had no UDP forwarding at all — handleUdpPacket() enqueued
 * into the egress shaper but onSendPacket was a no-op, so every UDP packet
 * was silently black-holed. This relay provides the actual forwarding path.
 *
 * Architecture:
 *   App → TUN → parse IP+UDP → UdpRelay.handleOutbound()
 *     → protected DatagramSocket → real destination
 *     → response arrives → PacketBuilder constructs IP+UDP → write to TUN
 *
 * Per §4: UDP/QUIC is NOT shaped on ingress. Egress pacing through the token
 * bucket is applied before packets reach this relay.
 *
 * Session tracking uses NAT-style mapping:
 *   (srcIp, srcPort, dstIp, dstPort) → protected DatagramSocket
 * Sessions time out after 60 seconds of inactivity.
 */
class UdpRelay(
    private val vpnService: VpnService,
    private val scope: CoroutineScope
) {
    /** Callback to write response packets back to the TUN interface. */
    var onResponsePacket: ((ByteArray) -> Unit)? = null

    /** Callback for tracking bytes relayed (used by PassiveEstimator). */
    var onBytesRelayed: ((egress: Int, ingress: Int) -> Unit)? = null

    /** Session key for NAT mapping. */
    data class SessionKey(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int
    )

    /** A UDP session with its protected socket. */
    private class UdpSession(
        val key: SessionKey,
        val socket: DatagramSocket,
        val originalSrcIp: ByteArray,
        val originalDstIp: ByteArray,
        val dstAddress: InetAddress,
        var lastActivityNs: Long = System.nanoTime(),
        var bytesSent: Long = 0L,
        var bytesReceived: Long = 0L
    )

    private val sessions = ConcurrentHashMap<SessionKey, UdpSession>()

    /** Session inactivity timeout. */
    private val sessionTimeoutNs = 60L * 1_000_000_000L // 60 seconds

    /**
     * Handle an outbound UDP packet from an app (read from TUN).
     *
     * @param srcIp Source IP bytes (app's TUN IP)
     * @param dstIp Destination IP bytes (real destination)
     * @param srcPort Source port (app's ephemeral port)
     * @param dstPort Destination port
     * @param payload UDP payload (no headers)
     */
    fun handleOutbound(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ) {
        val srcIpStr = InetAddress.getByAddress(srcIp).hostAddress ?: return
        val dstIpStr = InetAddress.getByAddress(dstIp).hostAddress ?: return
        val key = SessionKey(srcIpStr, srcPort, dstIpStr, dstPort)

        val session = sessions.getOrPut(key) {
            createSession(key, srcIp.copyOf(), dstIp.copyOf()) ?: return
        }

        // Forward the packet
        scope.launch(Dispatchers.IO) {
            try {
                val packet = DatagramPacket(
                    payload, payload.size,
                    session.dstAddress, dstPort
                )
                session.socket.send(packet)
                session.bytesSent += payload.size
                session.lastActivityNs = System.nanoTime()
                onBytesRelayed?.invoke(payload.size, 0)
            } catch (e: IOException) {
                Log.w(TAG, "UDP send failed for $key: ${e.message}")
                closeSession(key)
            }
        }
    }

    /**
     * Create a new UDP session with a protected socket and start
     * the ingress reader coroutine.
     */
    private fun createSession(
        key: SessionKey,
        srcIp: ByteArray,
        dstIp: ByteArray
    ): UdpSession? {
        return try {
            val socket = DatagramSocket()
            vpnService.protect(socket)
            socket.soTimeout = 0 // Non-blocking reads handled by coroutine

            val dstAddress = InetAddress.getByAddress(dstIp)
            val session = UdpSession(key, socket, srcIp, dstIp, dstAddress)

            // Start reading responses from the real destination
            launchIngressReader(session)

            Log.d(TAG, "UDP session created: $key")
            session
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create UDP session: $key", e)
            null
        }
    }

    /**
     * Read responses from the real destination and write them back to TUN.
     */
    private fun launchIngressReader(session: UdpSession) {
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(65535)
            try {
                // Set a reasonable read timeout so we can check for session closure
                session.socket.soTimeout = 5000

                while (isActive && sessions.containsKey(session.key)) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        session.socket.receive(packet)

                        if (packet.length > 0) {
                            val responseData = buffer.copyOf(packet.length)
                            session.bytesReceived += packet.length
                            session.lastActivityNs = System.nanoTime()
                            onBytesRelayed?.invoke(0, packet.length)

                            // Build proper IP+UDP response packet
                            // Source = the real destination (server)
                            // Destination = the original source (app)
                            val ipPacket = PacketBuilder.buildUdpPacket(
                                srcIp = session.originalDstIp,  // from server
                                dstIp = session.originalSrcIp,  // to app
                                srcPort = session.key.dstPort,  // from server's port
                                dstPort = session.key.srcPort,  // to app's port
                                payload = responseData
                            )

                            onResponsePacket?.invoke(ipPacket)
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Check if session is stale
                        val elapsed = System.nanoTime() - session.lastActivityNs
                        if (elapsed > sessionTimeoutNs) {
                            Log.d(TAG, "UDP session timed out: ${session.key}")
                            closeSession(session.key)
                            return@launch
                        }
                    }
                }
            } catch (e: IOException) {
                if (sessions.containsKey(session.key)) {
                    Log.w(TAG, "UDP ingress read error: ${session.key}", e)
                    closeSession(session.key)
                }
            }
        }
    }

    /** Close and remove a session. */
    fun closeSession(key: SessionKey) {
        val session = sessions.remove(key) ?: return
        try {
            session.socket.close()
        } catch (_: Exception) {}
        Log.d(TAG, "UDP session closed: $key " +
                "(sent=${session.bytesSent}, rcvd=${session.bytesReceived})")
    }

    /** Clean up stale sessions. */
    fun cleanupStaleSessions() {
        val now = System.nanoTime()
        sessions.entries.removeAll { (key, session) ->
            val isStale = (now - session.lastActivityNs) > sessionTimeoutNs
            if (isStale) {
                try { session.socket.close() } catch (_: Exception) {}
                Log.d(TAG, "Cleaned up stale UDP session: $key")
            }
            isStale
        }
    }

    /** Get active session count. */
    val activeSessionCount: Int get() = sessions.size

    /** Shut down all sessions. */
    fun shutdown() {
        sessions.values.forEach { session ->
            try { session.socket.close() } catch (_: Exception) {}
        }
        sessions.clear()
    }

    companion object {
        private const val TAG = "UdpRelay"
    }
}
