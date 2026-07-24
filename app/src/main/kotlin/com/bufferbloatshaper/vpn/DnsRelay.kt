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

    /** Upstream DNS fallback servers (protected from VPN loop). */
    private val fallbackDns = listOf("10.0.2.3", "8.8.8.8", "1.1.1.1")

    /** Query timeout in milliseconds. */
    private val queryTimeoutMs = 800

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
            // First try instant system DNS resolution (bulletproof on Android with DoT/DoH/system cache)
            if (resolveWithSystemDns(srcIp, dstIp, srcPort, dstPort, dnsPayload)) {
                return@launch
            }

            // Fallback to UDP socket forwarding if system DNS resolution is skipped/fails
            val targets = mutableListOf<InetAddress>()
            try {
                targets.add(InetAddress.getByName("10.0.2.3"))
            } catch (_: Exception) {}

            try {
                val origDst = InetAddress.getByAddress(dstIp)
                if (!targets.contains(origDst)) targets.add(origDst)
            } catch (_: Exception) {}

            var success = false
            for (target in targets) {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket(null)
                    if (!NetworkUtils.protectAndBind(vpnService, socket)) {
                        Log.e(TAG, "Failed to protect fallback DNS socket for target $target")
                        socket.close()
                        continue
                    }

                    socket.bind(InetSocketAddress(0))
                    socket.connect(target, 53)
                    socket.soTimeout = queryTimeoutMs

                    val queryPacket = DatagramPacket(dnsPayload, dnsPayload.size)
                    socket.send(queryPacket)

                    val responseBuffer = ByteArray(4096)
                    val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                    socket.receive(responsePacket)

                    val responseData = responseBuffer.copyOf(responsePacket.length)
                    val ipPacket = PacketBuilder.buildUdpPacket(
                        srcIp = dstIp,
                        dstIp = srcIp,
                        srcPort = dstPort,
                        dstPort = srcPort,
                        payload = responseData
                    )

                    onResponsePacket?.invoke(ipPacket)
                    queriesForwarded++
                    success = true
                    Log.d(TAG, "Fallback DNS query to ${target.hostAddress} succeeded! (${responseData.size} bytes)")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback DNS query to ${target.hostAddress} failed (${e.javaClass.simpleName}): ${e.message}")
                } finally {
                    socket?.close()
                }
            }

            if (!success) {
                queriesFailed++
                Log.e(TAG, "All DNS query targets failed for query from $srcPort")
            }
        }
    }

    private fun resolveWithSystemDns(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        dnsPayload: ByteArray
    ): Boolean {
        if (dnsPayload.size < 12) return false
        val qnameResult = parseQName(dnsPayload, 12) ?: return false
        val qname = qnameResult.first
        val qtypeOffset = qnameResult.second
        if (dnsPayload.size < qtypeOffset + 4) return false

        val qtype = ((dnsPayload[qtypeOffset].toInt() and 0xFF) shl 8) or (dnsPayload[qtypeOffset + 1].toInt() and 0xFF)
        // TYPE A = 1, TYPE AAAA = 28
        if (qtype != 1 && qtype != 28) return false

        val physicalNetwork = NetworkUtils.getPhysicalDefaultNetwork(vpnService)

        return try {
            val allAddrs = if (physicalNetwork != null) {
                physicalNetwork.getAllByName(qname)
            } else {
                InetAddress.getAllByName(qname)
            }
            val addresses = if (qtype == 1) {
                allAddrs.filterIsInstance<java.net.Inet4Address>()
            } else {
                allAddrs.filterIsInstance<java.net.Inet6Address>()
            }
            if (addresses.isEmpty()) return false

            val responsePayload = buildDnsResponsePayload(dnsPayload, qtypeOffset + 4, addresses, isIpv6 = (qtype == 28))
            val ipPacket = PacketBuilder.buildUdpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                payload = responsePayload
            )
            onResponsePacket?.invoke(ipPacket)
            queriesForwarded++
            Log.d(TAG, "Resolved DNS (${if (qtype == 1) "A" else "AAAA"}) for $qname via physical network $physicalNetwork (${addresses.size} addresses)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "System DNS resolution for $qname failed on network $physicalNetwork: ${e.message}")
            false
        }
    }

    private fun parseQName(payload: ByteArray, startOffset: Int): Pair<String, Int>? {
        var offset = startOffset
        val sb = StringBuilder()
        while (offset < payload.size) {
            val len = payload[offset].toInt() and 0xFF
            if (len == 0) {
                offset++
                break
            }
            if ((len and 0xC0) == 0xC0) return null
            if (offset + 1 + len > payload.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(payload, offset + 1, len, Charsets.US_ASCII))
            offset += 1 + len
        }
        return Pair(sb.toString(), offset)
    }

    private fun buildDnsResponsePayload(
        queryPayload: ByteArray,
        questionEndOffset: Int,
        addresses: List<InetAddress>,
        isIpv6: Boolean
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Transaction ID (copied from query header bytes 0-1)
        out.write(queryPayload[0].toInt())
        out.write(queryPayload[1].toInt())
        out.write(0x81)
        out.write(0x80)
        out.write(0x00)
        out.write(0x01)
        out.write(addresses.size shr 8)
        out.write(addresses.size and 0xFF)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(queryPayload, 12, questionEndOffset - 12)

        for (addr in addresses) {
            out.write(0xC0)
            out.write(0x0C)
            if (isIpv6) {
                // Type 28 (AAAA)
                out.write(0x00)
                out.write(0x1C)
                // Class 1 (IN)
                out.write(0x00)
                out.write(0x01)
                // TTL 300s
                out.write(0x00)
                out.write(0x00)
                out.write(0x01)
                out.write(0x2C)
                // RDLENGTH 16 bytes for IPv6
                out.write(0x00)
                out.write(0x10)
            } else {
                // Type 1 (A)
                out.write(0x00)
                out.write(0x01)
                // Class 1 (IN)
                out.write(0x00)
                out.write(0x01)
                // TTL 300s
                out.write(0x00)
                out.write(0x00)
                out.write(0x01)
                out.write(0x2C)
                // RDLENGTH 4 bytes for IPv4
                out.write(0x00)
                out.write(0x04)
            }
            out.write(addr.address)
        }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "DnsRelay"
    }
}
