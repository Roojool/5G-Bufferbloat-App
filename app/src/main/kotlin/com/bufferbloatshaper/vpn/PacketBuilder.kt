package com.bufferbloatshaper.vpn

import java.nio.ByteBuffer

/**
 * Constructs valid IP + TCP/UDP packets for writing back to the TUN interface.
 *
 * The TUN fd expects complete, valid IP packets. Every response from the relay
 * (TCP data from servers, DNS responses, UDP replies) must be wrapped in a
 * proper IP header with correct checksums.
 *
 * This is the piece that was completely missing from the original implementation
 * and is the reason the TcpRelay's ingress path was broken (writing raw payload
 * to TUN, which the kernel silently drops).
 */
object PacketBuilder {

    /**
     * Build an IPv4 + TCP packet.
     *
     * @param srcIp Source IP (the remote server's IP).
     * @param dstIp Destination IP (the app's IP, typically 10.0.0.2).
     * @param srcPort Source port (remote server port).
     * @param dstPort Destination port (app's local port).
     * @param seqNumber TCP sequence number.
     * @param ackNumber TCP acknowledgment number.
     * @param flags TCP flags (SYN, ACK, FIN, RST, PSH combinations).
     * @param windowSize TCP advertised window.
     * @param payload TCP payload data (can be empty for control packets).
     * @return Complete IP+TCP packet as ByteArray.
     */
    fun buildTcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNumber: Long,
        ackNumber: Long,
        flags: Int,
        windowSize: Int,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val ipHeaderLen = 20
        val tcpHeaderLen = 20
        val totalLen = ipHeaderLen + tcpHeaderLen + payload.size

        val packet = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(packet)

        // --- IPv4 Header ---
        buf.put(0, (0x45).toByte()) // version=4, IHL=5
        buf.put(1, 0) // TOS
        buf.putShort(2, totalLen.toShort()) // Total length
        buf.putShort(4, 0) // Identification
        buf.putShort(6, 0x4000.toShort()) // Flags: Don't Fragment
        buf.put(8, 64) // TTL
        buf.put(9, 6) // Protocol: TCP
        buf.putShort(10, 0) // Header checksum (computed below)
        // Source IP
        for (i in 0..3) buf.put(12 + i, srcIp[i])
        // Destination IP
        for (i in 0..3) buf.put(16 + i, dstIp[i])

        // IP header checksum
        val ipChecksum = computeChecksum(packet, 0, ipHeaderLen)
        buf.putShort(10, ipChecksum.toShort())

        // --- TCP Header ---
        val tcpOffset = ipHeaderLen
        buf.putShort(tcpOffset, srcPort.toShort()) // Source port
        buf.putShort(tcpOffset + 2, dstPort.toShort()) // Destination port
        buf.putInt(tcpOffset + 4, seqNumber.toInt()) // Sequence number
        buf.putInt(tcpOffset + 8, ackNumber.toInt()) // Acknowledgment
        buf.put(tcpOffset + 12, (0x50).toByte()) // Data offset: 5 words (20 bytes)
        buf.put(tcpOffset + 13, flags.toByte()) // Flags
        buf.putShort(tcpOffset + 14, windowSize.toShort()) // Window
        buf.putShort(tcpOffset + 16, 0) // Checksum (computed below)
        buf.putShort(tcpOffset + 18, 0) // Urgent pointer

        // TCP payload
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, tcpOffset + tcpHeaderLen, payload.size)
        }

        // TCP checksum (includes pseudo-header)
        val tcpChecksum = computeTcpChecksum(
            srcIp, dstIp,
            packet, tcpOffset, tcpHeaderLen + payload.size
        )
        buf.putShort(tcpOffset + 16, tcpChecksum.toShort())

        return packet
    }

    /**
     * Build an IPv4 + UDP packet.
     *
     * @param srcIp Source IP.
     * @param dstIp Destination IP.
     * @param srcPort Source port.
     * @param dstPort Destination port.
     * @param payload UDP payload data.
     * @return Complete IP+UDP packet as ByteArray.
     */
    fun buildUdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val udpLen = udpHeaderLen + payload.size
        val totalLen = ipHeaderLen + udpLen

        val packet = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(packet)

        // --- IPv4 Header ---
        buf.put(0, (0x45).toByte())
        buf.put(1, 0)
        buf.putShort(2, totalLen.toShort())
        buf.putShort(4, 0)
        buf.putShort(6, 0x4000.toShort()) // Don't Fragment
        buf.put(8, 64) // TTL
        buf.put(9, 17) // Protocol: UDP
        buf.putShort(10, 0)
        for (i in 0..3) buf.put(12 + i, srcIp[i])
        for (i in 0..3) buf.put(16 + i, dstIp[i])

        val ipChecksum = computeChecksum(packet, 0, ipHeaderLen)
        buf.putShort(10, ipChecksum.toShort())

        // --- UDP Header ---
        val udpOffset = ipHeaderLen
        buf.putShort(udpOffset, srcPort.toShort())
        buf.putShort(udpOffset + 2, dstPort.toShort())
        buf.putShort(udpOffset + 4, udpLen.toShort())
        buf.putShort(udpOffset + 6, 0) // Checksum (computed below)

        // UDP payload
        System.arraycopy(payload, 0, packet, udpOffset + udpHeaderLen, payload.size)

        // UDP checksum (includes pseudo-header)
        val udpChecksum = computeUdpChecksum(
            srcIp, dstIp,
            packet, udpOffset, udpLen
        )
        buf.putShort(udpOffset + 6, udpChecksum.toShort())

        return packet
    }

    /**
     * Compute the standard Internet checksum (RFC 1071).
     * Used for IP headers.
     */
    fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length

        // Sum 16-bit words
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        // Handle odd byte
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }

        // Fold 32-bit sum to 16 bits
        while (sum > 0xFFFF) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return (sum.toInt().inv()) and 0xFFFF
    }

    /**
     * Compute TCP checksum with pseudo-header.
     */
    private fun computeTcpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        data: ByteArray,
        tcpOffset: Int,
        tcpLength: Int
    ): Int {
        // Build pseudo-header + TCP segment for checksum
        val pseudoLen = 12 + tcpLength + (if (tcpLength % 2 != 0) 1 else 0)
        val pseudo = ByteArray(pseudoLen)

        // Pseudo-header: src IP (4) + dst IP (4) + zero (1) + protocol (1) + TCP length (2)
        System.arraycopy(srcIp, 0, pseudo, 0, 4)
        System.arraycopy(dstIp, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = 6 // TCP
        pseudo[10] = (tcpLength shr 8).toByte()
        pseudo[11] = (tcpLength and 0xFF).toByte()

        // Copy TCP segment (with checksum field zeroed — it already is from caller)
        System.arraycopy(data, tcpOffset, pseudo, 12, tcpLength)

        return computeChecksum(pseudo, 0, pseudo.size)
    }

    /**
     * Compute UDP checksum with pseudo-header.
     */
    private fun computeUdpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        data: ByteArray,
        udpOffset: Int,
        udpLength: Int
    ): Int {
        val pseudoLen = 12 + udpLength + (if (udpLength % 2 != 0) 1 else 0)
        val pseudo = ByteArray(pseudoLen)

        System.arraycopy(srcIp, 0, pseudo, 0, 4)
        System.arraycopy(dstIp, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = 17 // UDP
        pseudo[10] = (udpLength shr 8).toByte()
        pseudo[11] = (udpLength and 0xFF).toByte()

        System.arraycopy(data, udpOffset, pseudo, 12, udpLength)

        val checksum = computeChecksum(pseudo, 0, pseudo.size)
        // UDP allows 0x0000 to mean "no checksum"; if computed checksum is 0, use 0xFFFF
        return if (checksum == 0) 0xFFFF else checksum
    }

    // TCP flag constants
    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10
    const val TCP_SYN_ACK = TCP_SYN or TCP_ACK
    const val TCP_FIN_ACK = TCP_FIN or TCP_ACK
    const val TCP_PSH_ACK = TCP_PSH or TCP_ACK
}
