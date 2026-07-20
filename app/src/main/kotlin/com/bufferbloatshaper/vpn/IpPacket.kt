package com.bufferbloatshaper.vpn

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * IPv4 header parser. Reads directly from a ByteBuffer using absolute
 * position reads only — never mutates buffer.position().
 *
 * IPv4 header layout (RFC 791):
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |Version|  IHL  |Type of Service|          Total Length         |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |         Identification        |Flags|      Fragment Offset    |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |  Time to Live |    Protocol   |         Header Checksum       |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                       Source Address                          |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                    Destination Address                        |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class IpPacket(private val buffer: ByteBuffer) {

    /** IP version (should be 4). */
    val version: Int
        get() = (buffer.get(0).toInt() shr 4) and 0x0F

    /** Internet Header Length in 32-bit words. */
    val ihl: Int
        get() = buffer.get(0).toInt() and 0x0F

    /** Header length in bytes. */
    val headerLength: Int
        get() = ihl * 4

    /** Type of Service / DSCP + ECN. */
    val tos: Int
        get() = buffer.get(1).toInt() and 0xFF

    /** Total packet length in bytes (header + payload). */
    val totalLength: Int
        get() = buffer.getShort(2).toInt() and 0xFFFF

    /** TTL. */
    val ttl: Int
        get() = buffer.get(8).toInt() and 0xFF

    /** Protocol number: 6 = TCP, 17 = UDP, 1 = ICMP. */
    val protocol: Int
        get() = buffer.get(9).toInt() and 0xFF

    /**
     * Source IP address as raw 4 bytes.
     * Uses absolute reads — does NOT mutate buffer position.
     */
    val sourceAddress: ByteArray
        get() {
            val addr = ByteArray(4)
            for (i in 0..3) addr[i] = buffer.get(12 + i)
            return addr
        }

    /**
     * Destination IP address as raw 4 bytes.
     * Uses absolute reads — does NOT mutate buffer position.
     */
    val destinationAddress: ByteArray
        get() {
            val addr = ByteArray(4)
            for (i in 0..3) addr[i] = buffer.get(16 + i)
            return addr
        }

    /** Source IP as InetAddress. */
    val sourceInetAddress: InetAddress
        get() = InetAddress.getByAddress(sourceAddress)

    /** Destination IP as InetAddress. */
    val destinationInetAddress: InetAddress
        get() = InetAddress.getByAddress(destinationAddress)

    /** Source IP as dotted string. */
    val sourceAddressString: String
        get() = sourceInetAddress.hostAddress ?: "0.0.0.0"

    /** Destination IP as dotted string. */
    val destinationAddressString: String
        get() = destinationInetAddress.hostAddress ?: "0.0.0.0"

    /** Payload length (total - header). */
    val payloadLength: Int
        get() = totalLength - headerLength

    /** Offset in the buffer where the transport-layer payload begins. */
    val payloadOffset: Int
        get() = headerLength

    /** Whether this is a TCP packet. */
    val isTcp: Boolean get() = protocol == PROTOCOL_TCP

    /** Whether this is a UDP packet. */
    val isUdp: Boolean get() = protocol == PROTOCOL_UDP

    /** Whether this is an ICMP packet. */
    val isIcmp: Boolean get() = protocol == PROTOCOL_ICMP

    /**
     * Compute 5-tuple hash for flow identification.
     * Hash of: src IP, dst IP, protocol, src port, dst port.
     */
    fun flowHash(srcPort: Int, dstPort: Int): Int {
        var hash = 17
        hash = hash * 31 + sourceAddress.contentHashCode()
        hash = hash * 31 + destinationAddress.contentHashCode()
        hash = hash * 31 + protocol
        hash = hash * 31 + srcPort
        hash = hash * 31 + dstPort
        return hash
    }

    /**
     * Get a copy of the raw packet bytes.
     * Saves and restores buffer position.
     */
    fun toByteArray(): ByteArray {
        val len = totalLength
        val bytes = ByteArray(len)
        val savedPos = buffer.position()
        buffer.position(0)
        buffer.get(bytes, 0, len)
        buffer.position(savedPos)
        return bytes
    }

    /**
     * Get a view of the transport-layer payload.
     * Uses duplicate() so the original buffer is not affected.
     */
    fun payloadSlice(): ByteBuffer {
        val slice = buffer.duplicate()
        slice.position(payloadOffset)
        slice.limit(totalLength)
        return slice.slice()
    }

    companion object {
        const val PROTOCOL_ICMP = 1
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17

        /** Minimum valid IPv4 header size. */
        const val MIN_HEADER_SIZE = 20

        /**
         * Quick validation: is this buffer a valid-looking IPv4 packet?
         */
        fun isValidIpv4(buffer: ByteBuffer): Boolean {
            if (buffer.remaining() < MIN_HEADER_SIZE) return false
            val versionIhl = buffer.get(0).toInt()
            val version = (versionIhl shr 4) and 0x0F
            if (version != 4) return false
            val ihl = versionIhl and 0x0F
            if (ihl < 5) return false
            val totalLength = buffer.getShort(2).toInt() and 0xFFFF
            return totalLength >= ihl * 4 && totalLength <= buffer.remaining()
        }

        /**
         * Check IP version from first nibble of a buffer (works for both v4 and v6).
         */
        fun ipVersion(buffer: ByteBuffer): Int {
            if (buffer.remaining() < 1) return -1
            return (buffer.get(0).toInt() shr 4) and 0x0F
        }
    }
}
