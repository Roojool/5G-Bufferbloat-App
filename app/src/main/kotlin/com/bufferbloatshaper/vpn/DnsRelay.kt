package com.bufferbloatshaper.vpn

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * DNS relay — resolves the "nothing loads" gap.
 *
 * When the VPN builder calls addDnsServer("8.8.8.8"), Android routes DNS
 * queries through the TUN interface. Without something to actually forward
 * those queries to a real DNS server, zero hostname resolution happens.
 *
 * This relay:
 * 1. Intercepts UDP port 53 packets from TUN
 * 2. Opens a protected DatagramSocket (VpnService.protect() to avoid loop)
 * 3. Forwards the DNS query to the configured upstream DNS server
 * 4. Receives the response
 * 5. Constructs a proper IP+UDP response packet (using PacketBuilder)
 * 6. Writes it back to TUN
 *
 * DNS queries are always prioritized (never shaped/delayed) — per FlowType.DNS.
 */
class DnsRelay(
    private val vpnService: VpnService,
    private val scope: CoroutineScope
) {
    /** Callback to write response packets back to the TUN interface. */
    var onResponsePacket: ((ByteArray) -> Unit)? = null

    /** Upstream DNS servers (protected from VPN loop). */
    private val upstreamDns = listOf("8.8.8.8", "8.8.4.4")

    /** Query timeout in milliseconds. */
    private val queryTimeoutMs = 5000

    /** Stats. */
    @Volatile var queriesForwarded: Long = 0L; private set
    @Volatile var queriesFailed: Long = 0L; private set

    /**
     * Handle an outbound DNS query read from TUN.
     *
     * @param srcIp Original source IP (app's side of TUN, e.g., 10.0.0.2)
     * @param dstIp Original destination IP (the DNS server the app asked for)
     * @param srcPort Original source port (app's ephemeral port)
     * @param dstPort Destination port (53)
     * @param dnsPayload Raw DNS query payload (UDP payload, no IP/UDP headers)
     */
    fun handleDnsQuery(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        dnsPayload: ByteArray
    ) {
        scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                // Open a protected socket so it doesn't loop through TUN
                socket = DatagramSocket()
                vpnService.protect(socket)
                socket.soTimeout = queryTimeoutMs

                // Pick upstream DNS (use first, fallback to second)
                val upstream = InetAddress.getByName(upstreamDns[0])

                // Forward the query
                val queryPacket = DatagramPacket(dnsPayload, dnsPayload.size, upstream, 53)
                socket.send(queryPacket)

                // Receive the response
                val responseBuffer = ByteArray(4096) // DNS responses can be up to ~4KB
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(responsePacket)

                val responseData = responseBuffer.copyOf(responsePacket.length)

                // Build proper IP+UDP response packet to send back to TUN.
                // Source = the DNS server the app originally asked (dstIp:dstPort)
                // Destination = the app (srcIp:srcPort)
                // This mirrors what would have happened if the query went directly.
                val ipPacket = PacketBuilder.buildUdpPacket(
                    srcIp = dstIp,     // response comes FROM the DNS server
                    dstIp = srcIp,     // response goes TO the app
                    srcPort = dstPort, // from port 53
                    dstPort = srcPort, // to app's ephemeral port
                    payload = responseData
                )

                onResponsePacket?.invoke(ipPacket)
                queriesForwarded++

            } catch (e: IOException) {
                Log.w(TAG, "DNS query failed: ${e.message}")
                queriesFailed++

                // Try fallback DNS
                if (upstreamDns.size > 1) {
                    tryFallbackDns(socket, srcIp, dstIp, srcPort, dstPort, dnsPayload)
                }
            } finally {
                socket?.close()
            }
        }
    }

    private fun tryFallbackDns(
        oldSocket: DatagramSocket?,
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        dnsPayload: ByteArray
    ) {
        oldSocket?.close()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            vpnService.protect(socket)
            socket.soTimeout = queryTimeoutMs

            val upstream = InetAddress.getByName(upstreamDns[1])
            val queryPacket = DatagramPacket(dnsPayload, dnsPayload.size, upstream, 53)
            socket.send(queryPacket)

            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            val responseData = responseBuffer.copyOf(responsePacket.length)
            val ipPacket = PacketBuilder.buildUdpPacket(
                srcIp = dstIp, dstIp = srcIp,
                srcPort = dstPort, dstPort = srcPort,
                payload = responseData
            )

            onResponsePacket?.invoke(ipPacket)
            queriesForwarded++
        } catch (e: IOException) {
            Log.e(TAG, "Fallback DNS also failed: ${e.message}")
            queriesFailed++
        } finally {
            socket?.close()
        }
    }

    companion object {
        private const val TAG = "DnsRelay"
    }
}
