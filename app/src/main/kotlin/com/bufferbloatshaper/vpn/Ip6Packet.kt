package com.bufferbloatshaper.vpn

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * IPv6 header parser. Reads directly from a ByteBuffer using absolute
 * position reads only — never mutates buffer.position().
 *
 * IPv6 header layout (RFC 8200):
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |Version| Traffic Class |           Flow Label                  |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |         Payload Length        |  Next Header  |   Hop Limit   |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                                                               |
 *  +                                                               +
 *  |                                                               |
 *  +                         Source Address                        +
 *  |                           (128 bits)                          |
 *  +                                                               +
 *  |                                                               |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                                                               |
 *  +                                                               +
 *  |                                                               |
 *  +                      Destination Address                      +
 *  |                           (128 bits)                          |
 *  +                                                               +
 *  |                                                               |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class Ip6Packet(private val buffer: ByteBuffer) {

    /** IP version (should be 6). */
    val version: Int
        get() = (buffer.get(0).toInt() shr 4) and 0x0F

    /** Header length in bytes (fixed at 40 for base IPv6 header). */
    val headerLength: Int
        get() = 40

    /** Payload length in bytes (from header). */
    val payloadLength: Int
        get() = buffer.getShort(4).toInt() and 0xFFFF

    /** Total packet length (header + payload). */
    val totalLength: Int
        get() = headerLength + payloadLength

    /** Next Header (protocol). Note: This does not resolve extension headers yet. */
    val nextHeader: Int
        get() = buffer.get(6).toInt() and 0xFF

    /** Hop Limit. */
    val hopLimit: Int
        get() = buffer.get(7).toInt() and 0xFF

    /**
     * Source IP address as raw 16 bytes.
     */
    val sourceAddress: ByteArray
        get() {
            val addr = ByteArray(16)
            for (i in 0..15) addr[i] = buffer.get(8 + i)
            return addr
        }

    /**
     * Destination IP address as raw 16 bytes.
     */
    val destinationAddress: ByteArray
        get() {
            val addr = ByteArray(16)
            for (i in 0..15) addr[i] = buffer.get(24 + i)
            return addr
        }

    /** Source IP as InetAddress. */
    val sourceInetAddress: InetAddress
        get() = InetAddress.getByAddress(sourceAddress)

    /** Destination IP as InetAddress. */
    val destinationInetAddress: InetAddress
        get() = InetAddress.getByAddress(destinationAddress)

    /** Source IP as string. */
    val sourceAddressString: String
        get() = sourceInetAddress.hostAddress ?: "::"

    /** Destination IP as string. */
    val destinationAddressString: String
        get() = destinationInetAddress.hostAddress ?: "::"

    /** Offset in the buffer where the transport-layer payload begins. */
    val payloadOffset: Int
        get() = headerLength

    /** Whether this is a TCP packet. */
    val isTcp: Boolean get() = nextHeader == IpPacket.PROTOCOL_TCP

    /** Whether this is a UDP packet. */
    val isUdp: Boolean get() = nextHeader == IpPacket.PROTOCOL_UDP

    /** Whether this is an ICMPv6 packet. */
    val isIcmpv6: Boolean get() = nextHeader == 58

    /**
     * Compute 5-tuple hash for flow identification.
     */
    fun flowHash(srcPort: Int, dstPort: Int): Int {
        var hash = 17
        hash = hash * 31 + sourceAddress.contentHashCode()
        hash = hash * 31 + destinationAddress.contentHashCode()
        hash = hash * 31 + nextHeader
        hash = hash * 31 + srcPort
        hash = hash * 31 + dstPort
        return hash
    }

    /**
     * Get a copy of the raw packet bytes.
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
     */
    fun payloadSlice(): ByteBuffer {
        val slice = buffer.duplicate()
        slice.position(payloadOffset)
        slice.limit(totalLength)
        return slice.slice()
    }

    companion object {
        const val MIN_HEADER_SIZE = 40

        /**
         * Quick validation: is this buffer a valid-looking IPv6 packet?
         */
        fun isValidIpv6(buffer: ByteBuffer): Boolean {
            if (buffer.remaining() < MIN_HEADER_SIZE) return false
            val version = (buffer.get(0).toInt() shr 4) and 0x0F
            if (version != 6) return false
            val payloadLen = buffer.getShort(4).toInt() and 0xFFFF
            return MIN_HEADER_SIZE + payloadLen <= buffer.remaining()
        }
    }
}
