package com.bufferbloatshaper.vpn

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketBuilderAndParserTest {

    @Test
    fun tcpBuilderProducesParsablePacketWithValidChecksums() {
        val source = ipv4(192, 0, 2, 10)
        val destination = ipv4(10, 0, 0, 2)
        val payload = "hello".encodeToByteArray()
        val packet = PacketBuilder.buildTcpPacket(
            srcIp = source,
            dstIp = destination,
            srcPort = 443,
            dstPort = 52_000,
            seqNumber = 0xFFFF_FFFEL,
            ackNumber = 1234L,
            flags = PacketBuilder.TCP_PSH_ACK,
            windowSize = 65_535,
            payload = payload
        )
        val buffer = ByteBuffer.wrap(packet)
        val ip = IpPacket(buffer)
        val tcp = TcpPacket(buffer, ip.payloadOffset)

        assertTrue(IpPacket.isValidIpv4(buffer))
        assertEquals(4, ip.version)
        assertEquals(20, ip.headerLength)
        assertEquals(45, ip.totalLength)
        assertEquals(IpPacket.PROTOCOL_TCP, ip.protocol)
        assertArrayEquals(source, ip.sourceAddress)
        assertArrayEquals(destination, ip.destinationAddress)
        assertEquals(20 + payload.size, ip.payloadLength)
        assertEquals(0, PacketBuilder.computeChecksum(packet, 0, ip.headerLength))
        assertEquals(0, transportChecksum(source, destination, IpPacket.PROTOCOL_TCP, packet.copyOfRange(20, packet.size)))

        assertEquals(443, tcp.sourcePort)
        assertEquals(52_000, tcp.destinationPort)
        assertEquals(0xFFFF_FFFEL, tcp.sequenceNumber)
        assertEquals(1234L, tcp.acknowledgmentNumber)
        assertEquals(20, tcp.headerLength)
        assertEquals(payload.size, tcp.payloadLength(ip.totalLength, ip.headerLength))
        assertEquals(65_535, tcp.windowSize)
        assertTrue(tcp.isAck)
        assertTrue(tcp.isPsh)
        assertEquals("ACK PSH", tcp.flagsString)
        assertArrayEquals(payload, packet.copyOfRange(tcp.payloadOffset, packet.size))
    }

    @Test
    fun udpBuilderProducesParsablePacketWithValidChecksums() {
        val source = ipv4(8, 8, 8, 8)
        val destination = ipv4(10, 0, 0, 2)
        val payload = byteArrayOf(1, 2, 3) // Odd length exercises checksum padding.
        val packet = PacketBuilder.buildUdpPacket(
            srcIp = source,
            dstIp = destination,
            srcPort = 53,
            dstPort = 53_000,
            payload = payload
        )
        val buffer = ByteBuffer.wrap(packet)
        val ip = IpPacket(buffer)
        val udp = UdpPacket(buffer, ip.payloadOffset)

        assertTrue(IpPacket.isValidIpv4(buffer))
        assertEquals(IpPacket.PROTOCOL_UDP, ip.protocol)
        assertEquals(31, ip.totalLength)
        assertEquals(0, PacketBuilder.computeChecksum(packet, 0, ip.headerLength))
        assertEquals(0, transportChecksum(source, destination, IpPacket.PROTOCOL_UDP, packet.copyOfRange(20, packet.size)))

        assertEquals(53, udp.sourcePort)
        assertEquals(53_000, udp.destinationPort)
        assertEquals(11, udp.length)
        assertEquals(3, udp.payloadLength)
        assertTrue(udp.isDns)
        assertFalse(udp.isLikelyQuic)
        assertArrayEquals(payload, packet.copyOfRange(udp.payloadOffset, packet.size))
    }

    @Test
    fun ipv4ParserValidatesHeadersAndDoesNotMoveCallerBufferPosition() {
        val packet = PacketBuilder.buildUdpPacket(
            srcIp = ipv4(203, 0, 113, 9),
            dstIp = ipv4(10, 0, 0, 2),
            srcPort = 443,
            dstPort = 4_444,
            payload = byteArrayOf(9, 8)
        )
        val buffer = ByteBuffer.wrap(packet)
        buffer.position(7)
        val ip = IpPacket(buffer)

        assertArrayEquals(packet, ip.toByteArray())
        assertEquals(7, buffer.position())
        assertEquals(4, IpPacket.ipVersion(buffer))
        assertFalse(IpPacket.isValidIpv4(ByteBuffer.wrap(ByteArray(19))))

        val invalidVersion = packet.clone()
        invalidVersion[0] = 0x65.toByte()
        assertFalse(IpPacket.isValidIpv4(ByteBuffer.wrap(invalidVersion)))

        val invalidLength = packet.clone()
        invalidLength[2] = 0x7F
        invalidLength[3] = 0xFF.toByte()
        assertFalse(IpPacket.isValidIpv4(ByteBuffer.wrap(invalidLength)))
    }

    @Test
    fun ipv6ParserReadsBaseHeaderAndPayloadWithoutChangingBufferPosition() {
        val source = ByteArray(16) { index -> index.toByte() }
        val destination = ByteArray(16) { index -> (index + 16).toByte() }
        val payload = byteArrayOf(7, 8, 9, 10)
        val packet = ByteArray(40 + payload.size)
        packet[0] = 0x60.toByte()
        packet[4] = 0.toByte()
        packet[5] = payload.size.toByte()
        packet[6] = IpPacket.PROTOCOL_UDP.toByte()
        packet[7] = 64.toByte()
        source.copyInto(packet, destinationOffset = 8)
        destination.copyInto(packet, destinationOffset = 24)
        payload.copyInto(packet, destinationOffset = 40)
        val buffer = ByteBuffer.wrap(packet)
        assertTrue(Ip6Packet.isValidIpv6(buffer))
        buffer.position(3)
        val ip = Ip6Packet(buffer)

        assertEquals(6, ip.version)
        assertEquals(44, ip.totalLength)
        assertEquals(IpPacket.PROTOCOL_UDP, ip.nextHeader)
        assertTrue(ip.isUdp)
        assertArrayEquals(source, ip.sourceAddress)
        assertArrayEquals(destination, ip.destinationAddress)
        val payloadSlice = ip.payloadSlice()
        val parsedPayload = ByteArray(payloadSlice.remaining())
        payloadSlice.get(parsedPayload)
        assertArrayEquals(payload, parsedPayload)
        assertArrayEquals(packet, ip.toByteArray())
        assertEquals(3, buffer.position())

        val invalidLength = packet.clone()
        invalidLength[5] = 5.toByte()
        assertFalse(Ip6Packet.isValidIpv6(ByteBuffer.wrap(invalidLength)))
    }

    @Test
    fun checksumMatchesRfc1071ExampleIncludingOddByteHandling() {
        val data = byteArrayOf(
            0x00, 0x01, 0xF2.toByte(), 0x03,
            0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte()
        )

        assertEquals(0x220D, PacketBuilder.computeChecksum(data, 0, data.size))
        assertEquals(0xFEFF, PacketBuilder.computeChecksum(byteArrayOf(0x01), 0, 1))
    }

    private fun ipv4(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(
        a.toByte(), b.toByte(), c.toByte(), d.toByte()
    )

    private fun transportChecksum(
        source: ByteArray,
        destination: ByteArray,
        protocol: Int,
        transport: ByteArray
    ): Int {
        val pseudoHeader = ByteArray(12 + transport.size + (transport.size % 2))
        source.copyInto(pseudoHeader, destinationOffset = 0)
        destination.copyInto(pseudoHeader, destinationOffset = 4)
        pseudoHeader[9] = protocol.toByte()
        pseudoHeader[10] = (transport.size ushr 8).toByte()
        pseudoHeader[11] = transport.size.toByte()
        transport.copyInto(pseudoHeader, destinationOffset = 12)
        return PacketBuilder.computeChecksum(pseudoHeader, 0, pseudoHeader.size)
    }
}
