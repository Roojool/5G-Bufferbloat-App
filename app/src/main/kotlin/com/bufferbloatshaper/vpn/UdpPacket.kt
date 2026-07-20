package com.bufferbloatshaper.vpn

import java.nio.ByteBuffer

/**
 * Zero-copy UDP header parser. Reads from a ByteBuffer starting at the UDP header offset.
 *
 * UDP header layout (RFC 768):
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |          Source Port          |       Destination Port        |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |            Length             |           Checksum            |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class UdpPacket(private val buffer: ByteBuffer, private val offset: Int) {

    /** Source port (16-bit unsigned). */
    val sourcePort: Int
        get() = buffer.getShort(offset).toInt() and 0xFFFF

    /** Destination port (16-bit unsigned). */
    val destinationPort: Int
        get() = buffer.getShort(offset + 2).toInt() and 0xFFFF

    /** Total UDP datagram length (header + payload) in bytes. */
    val length: Int
        get() = buffer.getShort(offset + 4).toInt() and 0xFFFF

    /** UDP checksum. */
    val checksum: Int
        get() = buffer.getShort(offset + 6).toInt() and 0xFFFF

    /** Payload length in bytes (total length - 8 byte header). */
    val payloadLength: Int
        get() = length - HEADER_SIZE

    /** Offset where UDP payload data begins (relative to buffer start). */
    val payloadOffset: Int
        get() = offset + HEADER_SIZE

    /** Whether this looks like a DNS query (port 53). */
    val isDns: Boolean
        get() = destinationPort == 53 || sourcePort == 53

    /** Whether this might be QUIC traffic (port 443 with UDP). */
    val isLikelyQuic: Boolean
        get() = destinationPort == 443 || sourcePort == 443

    companion object {
        /** UDP header is always exactly 8 bytes. */
        const val HEADER_SIZE = 8
    }
}
