package com.bufferbloatshaper.vpn

import java.nio.ByteBuffer

/**
 * Zero-copy TCP header parser. Reads from a ByteBuffer starting at the TCP header offset.
 *
 * TCP header layout (RFC 793):
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |          Source Port          |       Destination Port        |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                        Sequence Number                       |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                    Acknowledgment Number                     |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |  Data |           |U|A|P|R|S|F|                               |
 *  | Offset| Reserved  |R|C|S|S|Y|I|            Window             |
 *  |       |           |G|K|H|T|N|N|                               |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |           Checksum            |         Urgent Pointer        |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class TcpPacket(private val buffer: ByteBuffer, private val offset: Int) {

    /** Source port (16-bit unsigned). */
    val sourcePort: Int
        get() = buffer.getShort(offset).toInt() and 0xFFFF

    /** Destination port (16-bit unsigned). */
    val destinationPort: Int
        get() = buffer.getShort(offset + 2).toInt() and 0xFFFF

    /** Sequence number (32-bit unsigned). */
    val sequenceNumber: Long
        get() = buffer.getInt(offset + 4).toLong() and 0xFFFFFFFFL

    /** Acknowledgment number (32-bit unsigned). */
    val acknowledgmentNumber: Long
        get() = buffer.getInt(offset + 8).toLong() and 0xFFFFFFFFL

    /** Data offset in 32-bit words (header length / 4). */
    val dataOffset: Int
        get() = (buffer.get(offset + 12).toInt() shr 4) and 0x0F

    /** TCP header length in bytes. */
    val headerLength: Int
        get() = dataOffset * 4

    /** Raw flags byte. */
    private val flagsByte: Int
        get() = buffer.get(offset + 13).toInt() and 0x3F

    val isFin: Boolean get() = (flagsByte and FLAG_FIN) != 0
    val isSyn: Boolean get() = (flagsByte and FLAG_SYN) != 0
    val isRst: Boolean get() = (flagsByte and FLAG_RST) != 0
    val isPsh: Boolean get() = (flagsByte and FLAG_PSH) != 0
    val isAck: Boolean get() = (flagsByte and FLAG_ACK) != 0
    val isUrg: Boolean get() = (flagsByte and FLAG_URG) != 0

    /** Advertised receive window (16-bit unsigned). */
    val windowSize: Int
        get() = buffer.getShort(offset + 14).toInt() and 0xFFFF

    /** TCP checksum. */
    val checksum: Int
        get() = buffer.getShort(offset + 16).toInt() and 0xFFFF

    /** Urgent pointer. */
    val urgentPointer: Int
        get() = buffer.getShort(offset + 18).toInt() and 0xFFFF

    /** Offset where TCP payload data begins (relative to buffer start). */
    val payloadOffset: Int
        get() = offset + headerLength

    /** Length of TCP payload in bytes (requires IP total length). */
    fun payloadLength(ipTotalLength: Int, ipHeaderLength: Int): Int {
        return ipTotalLength - ipHeaderLength - headerLength
    }

    /** Check if this is a SYN-only (connection initiation) packet. */
    val isSynOnly: Boolean get() = isSyn && !isAck

    /** Check if this is a SYN-ACK (connection response) packet. */
    val isSynAck: Boolean get() = isSyn && isAck

    /** Human-readable flags string for debugging. */
    val flagsString: String
        get() = buildString {
            if (isSyn) append("SYN ")
            if (isAck) append("ACK ")
            if (isFin) append("FIN ")
            if (isRst) append("RST ")
            if (isPsh) append("PSH ")
            if (isUrg) append("URG ")
        }.trim()

    companion object {
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10
        const val FLAG_URG = 0x20

        /** Minimum TCP header size (no options). */
        const val MIN_HEADER_SIZE = 20
    }
}
