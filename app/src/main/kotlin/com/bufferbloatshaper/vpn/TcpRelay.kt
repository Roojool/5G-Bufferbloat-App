package com.bufferbloatshaper.vpn

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages bidirectional TCP relay connections with proper packet construction.
 *
 * Architecture (per §2):
 *   App → TUN → [parse IP/TCP] → TcpRelay → [protected socket] → Real Server
 *   Real Server → [protected socket] → TcpRelay → [PacketBuilder] → TUN → App
 *
 * KEY FIX: The original TcpRelay wrote raw TCP payload data to TUN. The kernel
 * expects complete IP packets (IP header + TCP header + payload). We now use
 * PacketBuilder to construct proper response packets with correct checksums
 * and sequence numbers.
 *
 * This is still NOT a full TCP stack (no retransmission, no OOO reassembly,
 * no congestion control on the TUN-facing side). The protected socket's kernel
 * TCP stack handles all of that on the server-facing side. What we provide is:
 * - Connection lifecycle (SYN → ESTABLISHED → FIN/RST)
 * - Sequence number tracking for both directions
 * - Proper IP+TCP packet construction for TUN responses
 *
 * Phase 3 addition: receive-window throttling on the server-facing socket for
 * download shaping (see IngressController).
 */
class TcpRelay(
    private val vpnService: VpnService,
    private val scope: CoroutineScope
) {
    private val connections = ConcurrentHashMap<ConnectionKey, TcpConnection>()

    /** Callback to write response packets back to the TUN interface. */
    var onResponsePacket: ((ByteArray) -> Unit)? = null

    /** Callback for tracking bytes relayed (used by PassiveEstimator). */
    var onBytesRelayed: ((egress: Int, ingress: Int) -> Unit)? = null

    /** Ingress receive buffer size override for download shaping (Phase 3). */
    @Volatile
    var ingressReceiveBufferSize: Int = 0  // 0 = no override

    data class ConnectionKey(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int
    )

    /**
     * Tracks a single TCP relay connection and its sequence number state.
     */
    private class TcpConnection(
        val key: ConnectionKey,
        val channel: SocketChannel,
        /** Original source IP bytes (the app's TUN-side IP). */
        val appIp: ByteArray,
        /** Original destination IP bytes (the real server). */
        val serverIp: ByteArray,
        /** The app's port. */
        val appPort: Int,
        /** The server's port. */
        val serverPort: Int,
        var state: State = State.CONNECTING,
        var bytesSent: Long = 0L,
        var bytesReceived: Long = 0L,
        val createdAtNs: Long = System.nanoTime(),
        var lastActivityNs: Long = System.nanoTime()
    ) {
        enum class State { CONNECTING, SYN_RECEIVED, ESTABLISHED, CLOSING, CLOSED }

        /**
         * Sequence number tracking:
         * - tunSeqNum: our sequence number on the TUN side (what we send TO the app)
         * - tunAckNum: what we expect to receive FROM the app (their sequence number)
         * These track independently from the server-side kernel TCP's sequence numbers.
         */
        val tunSeqNum = AtomicLong(0)  // our seq → app
        val tunAckNum = AtomicLong(0)  // app's seq → us (we ACK this)
    }

    /**
     * Handle an outbound TCP packet from an app (read from TUN).
     * If it's a SYN, establish a new relay connection.
     * If it's data on an existing connection, forward it.
     */
    fun handleOutboundPacket(
        ipPacket: IpPacket,
        tcpPacket: TcpPacket,
        rawPacket: ByteArray
    ) {
        val key = ConnectionKey(
            srcIp = ipPacket.sourceAddressString,
            srcPort = tcpPacket.sourcePort,
            dstIp = ipPacket.destinationAddressString,
            dstPort = tcpPacket.destinationPort
        )

        when {
            tcpPacket.isRst -> {
                closeConnection(key)
            }
            tcpPacket.isSynOnly -> {
                // New connection — open relay to real destination
                initiateConnection(
                    key,
                    ipPacket.destinationInetAddress,
                    tcpPacket.destinationPort,
                    ipPacket.sourceAddress,
                    ipPacket.destinationAddress,
                    tcpPacket.sourcePort,
                    tcpPacket.destinationPort,
                    tcpPacket.sequenceNumber
                )
            }
            tcpPacket.isFin -> {
                val conn = connections[key]
                if (conn != null) {
                    // Update ack for the FIN
                    conn.tunAckNum.set(tcpPacket.sequenceNumber + 1)
                    // Forward any remaining data
                    forwardData(conn, tcpPacket, ipPacket)
                    // Send FIN-ACK back to app
                    sendTcpControl(conn, PacketBuilder.TCP_FIN_ACK)
                    conn.state = TcpConnection.State.CLOSING
                    scope.launch(Dispatchers.IO) {
                        delay(2000)
                        closeConnection(key)
                    }
                }
            }
            tcpPacket.isAck && !tcpPacket.isSyn -> {
                val conn = connections[key]
                if (conn != null) {
                    // If this is the ACK completing the 3-way handshake
                    if (conn.state == TcpConnection.State.SYN_RECEIVED) {
                        conn.state = TcpConnection.State.ESTABLISHED
                        conn.tunAckNum.set(tcpPacket.sequenceNumber)
                        // Start reading responses from the real server
                        launchIngressReader(conn)
                        Log.d(TAG, "Connection established (3-way handshake complete): $key")
                    }

                    // Forward data if present
                    forwardData(conn, tcpPacket, ipPacket)
                }
            }
        }
    }

    /**
     * Initiate a new relay connection to the real destination.
     * Opens a protected socket and begins the TCP 3-way handshake simulation.
     */
    private fun initiateConnection(
        key: ConnectionKey,
        dstAddress: InetAddress,
        dstPort: Int,
        appIpBytes: ByteArray,
        serverIpBytes: ByteArray,
        appPort: Int,
        serverPort: Int,
        clientIsn: Long
    ) {
        if (connections.containsKey(key)) return

        scope.launch(Dispatchers.IO) {
            try {
                val channel = SocketChannel.open()
                channel.configureBlocking(false)

                // CRITICAL: protect this socket from the VPN tunnel
                vpnService.protect(channel.socket())

                // Apply receive buffer size for download shaping (Phase 3)
                val rxBufSize = ingressReceiveBufferSize
                if (rxBufSize > 0) {
                    channel.socket().receiveBufferSize = rxBufSize
                }

                val conn = TcpConnection(
                    key = key,
                    channel = channel,
                    appIp = appIpBytes.copyOf(),
                    serverIp = serverIpBytes.copyOf(),
                    appPort = appPort,
                    serverPort = serverPort
                )

                // Initialize sequence numbers
                // Our ISN (what we send to the app) — use a timestamp-based value
                val ourIsn = (System.nanoTime() and 0xFFFFFFFFL)
                conn.tunSeqNum.set(ourIsn)
                // We need to ACK the client's SYN (ISN + 1)
                conn.tunAckNum.set(clientIsn + 1)

                connections[key] = conn

                // Start async connect to real server
                channel.connect(InetSocketAddress(dstAddress, dstPort))

                val connectTimeout = 10_000L
                val startTime = System.currentTimeMillis()
                while (!channel.finishConnect()) {
                    if (System.currentTimeMillis() - startTime > connectTimeout) {
                        Log.w(TAG, "Connection timeout: $key")
                        // Send RST to app
                        sendTcpControl(conn, PacketBuilder.TCP_RST)
                        closeConnection(key)
                        return@launch
                    }
                    delay(10)
                }

                // Server-side connection established!
                // Send SYN-ACK back to the app (step 2 of 3-way handshake)
                conn.state = TcpConnection.State.SYN_RECEIVED
                sendTcpControl(conn, PacketBuilder.TCP_SYN_ACK)
                // SYN-ACK consumes 1 sequence number
                conn.tunSeqNum.incrementAndGet()

                Log.d(TAG, "SYN-ACK sent to app: $key")

            } catch (e: IOException) {
                Log.e(TAG, "Failed to connect: $key", e)
                // Try to send RST to the app
                connections[key]?.let { sendTcpControl(it, PacketBuilder.TCP_RST) }
                closeConnection(key)
            }
        }
    }

    /**
     * Forward outbound data from the app to the real server.
     */
    private fun forwardData(conn: TcpConnection, tcpPacket: TcpPacket, ipPacket: IpPacket) {
        val payloadLen = tcpPacket.payloadLength(ipPacket.totalLength, ipPacket.headerLength)
        if (payloadLen <= 0) return

        // Update our ACK number (we received this many bytes from the app)
        conn.tunAckNum.set(tcpPacket.sequenceNumber + payloadLen)

        scope.launch(Dispatchers.IO) {
            try {
                val payload = ByteBuffer.allocate(payloadLen)
                val rawSlice = ipPacket.payloadSlice()
                rawSlice.position(tcpPacket.headerLength)
                if (rawSlice.remaining() >= payloadLen) {
                    val tempArray = ByteArray(payloadLen)
                    rawSlice.get(tempArray)
                    payload.put(tempArray)
                    payload.flip()

                    while (payload.hasRemaining()) {
                        conn.channel.write(payload)
                    }

                    conn.bytesSent += payloadLen
                    conn.lastActivityNs = System.nanoTime()
                    onBytesRelayed?.invoke(payloadLen, 0)

                    // ACK the received data back to the app
                    sendTcpControl(conn, PacketBuilder.TCP_ACK)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error forwarding data: ${conn.key}", e)
                sendTcpControl(conn, PacketBuilder.TCP_RST)
                closeConnection(conn.key)
            }
        }
    }

    /**
     * Read data coming back from the real server (ingress) and write it
     * back to the TUN interface as proper IP+TCP packets.
     *
     * KEY FIX: Uses PacketBuilder to construct valid IP+TCP packets instead
     * of writing raw payload data (which the kernel silently drops).
     */
    private fun launchIngressReader(conn: TcpConnection) {
        scope.launch(Dispatchers.IO) {
            val buffer = ByteBuffer.allocate(32768)
            try {
                while (conn.state == TcpConnection.State.ESTABLISHED && conn.channel.isOpen) {
                    buffer.clear()
                    val bytesRead = conn.channel.read(buffer)
                    when {
                        bytesRead > 0 -> {
                            buffer.flip()
                            val data = ByteArray(bytesRead)
                            buffer.get(data)

                            conn.bytesReceived += bytesRead
                            conn.lastActivityNs = System.nanoTime()
                            onBytesRelayed?.invoke(0, bytesRead)

                            // Build proper IP+TCP response packet
                            val currentSeq = conn.tunSeqNum.get()
                            val ipPacket = PacketBuilder.buildTcpPacket(
                                srcIp = conn.serverIp,  // from server
                                dstIp = conn.appIp,     // to app
                                srcPort = conn.serverPort,
                                dstPort = conn.appPort,
                                seqNumber = currentSeq,
                                ackNumber = conn.tunAckNum.get(),
                                flags = PacketBuilder.TCP_PSH_ACK,
                                windowSize = 65535,
                                payload = data
                            )

                            // Advance our sequence number by bytes sent
                            conn.tunSeqNum.addAndGet(bytesRead.toLong())

                            onResponsePacket?.invoke(ipPacket)
                        }
                        bytesRead == -1 -> {
                            // Server closed connection — send FIN to app
                            Log.d(TAG, "Server closed connection: ${conn.key}")
                            sendTcpControl(conn, PacketBuilder.TCP_FIN_ACK)
                            conn.tunSeqNum.incrementAndGet() // FIN consumes 1 seq
                            conn.state = TcpConnection.State.CLOSING
                            scope.launch {
                                delay(5000)
                                closeConnection(conn.key)
                            }
                            return@launch
                        }
                        else -> {
                            delay(1)
                        }
                    }
                }
            } catch (e: IOException) {
                if (conn.state != TcpConnection.State.CLOSED) {
                    Log.e(TAG, "Ingress read error: ${conn.key}", e)
                    sendTcpControl(conn, PacketBuilder.TCP_RST)
                    closeConnection(conn.key)
                }
            }
        }
    }

    /**
     * Send a TCP control packet (SYN-ACK, ACK, FIN, RST) back to the app.
     * These are header-only packets with no payload.
     */
    private fun sendTcpControl(conn: TcpConnection, flags: Int) {
        try {
            val packet = PacketBuilder.buildTcpPacket(
                srcIp = conn.serverIp,
                dstIp = conn.appIp,
                srcPort = conn.serverPort,
                dstPort = conn.appPort,
                seqNumber = conn.tunSeqNum.get(),
                ackNumber = conn.tunAckNum.get(),
                flags = flags,
                windowSize = 65535
            )
            onResponsePacket?.invoke(packet)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send control packet: ${conn.key}", e)
        }
    }

    /** Close and clean up a relay connection. */
    fun closeConnection(key: ConnectionKey) {
        val conn = connections.remove(key) ?: return
        conn.state = TcpConnection.State.CLOSED
        try {
            conn.channel.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing channel: $key", e)
        }
        Log.d(TAG, "Connection closed: $key (sent=${conn.bytesSent}, rcvd=${conn.bytesReceived})")
    }

    /** Get the number of active connections. */
    val activeConnectionCount: Int
        get() = connections.count { it.value.state == TcpConnection.State.ESTABLISHED }

    /** Get all active connection keys (for stats display). */
    val activeConnections: List<ConnectionKey>
        get() = connections.filter { it.value.state == TcpConnection.State.ESTABLISHED }.keys.toList()

    /** Get stats for a specific connection. */
    fun getConnectionStats(key: ConnectionKey): Pair<Long, Long>? {
        val conn = connections[key] ?: return null
        return Pair(conn.bytesSent, conn.bytesReceived)
    }

    /**
     * Update receive buffer size on all existing connections (Phase 3).
     * Called by IngressController when the target rate changes.
     */
    fun updateReceiveBufferSize(bufferSize: Int) {
        ingressReceiveBufferSize = bufferSize
        connections.values.forEach { conn ->
            if (conn.state == TcpConnection.State.ESTABLISHED) {
                try {
                    conn.channel.socket().receiveBufferSize = bufferSize
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update rx buffer: ${conn.key}", e)
                }
            }
        }
    }

    /** Clean up stale connections (no activity for > 5 minutes). */
    fun cleanupStaleConnections() {
        val now = System.nanoTime()
        val staleThreshold = 5L * 60 * 1_000_000_000L
        connections.entries.removeAll { (key, conn) ->
            val isStale = (now - conn.lastActivityNs) > staleThreshold
            if (isStale) {
                conn.state = TcpConnection.State.CLOSED
                try { conn.channel.close() } catch (_: IOException) {}
                Log.d(TAG, "Cleaned up stale connection: $key")
            }
            isStale
        }
    }

    /** Shut down all connections. */
    fun shutdown() {
        connections.keys.toList().forEach { closeConnection(it) }
        connections.clear()
    }

    companion object {
        private const val TAG = "TcpRelay"
    }
}
