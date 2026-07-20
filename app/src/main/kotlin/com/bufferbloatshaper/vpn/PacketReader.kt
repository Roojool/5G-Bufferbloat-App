package com.bufferbloatshaper.vpn

import android.util.Log
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads raw IP packets from the TUN file descriptor.
 *
 * The TUN interface delivers complete IP packets (no ethernet framing).
 * Each read() call returns exactly one IP packet.
 */
class PacketReader(
    private val tunChannel: FileChannel
) {
    private val buffer = ByteBuffer.allocate(MAX_PACKET_SIZE)

    /**
     * Read one IP packet from the TUN interface.
     * Returns the raw packet bytes, or null if no packet is available.
     *
     * This call blocks until a packet is available (or the fd is closed).
     */
    fun readPacket(): ByteArray? {
        return try {
            buffer.clear()
            val bytesRead = tunChannel.read(buffer)
            if (bytesRead > 0) {
                buffer.flip()
                val packet = ByteArray(bytesRead)
                buffer.get(packet)
                packet
            } else {
                null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading from TUN", e)
            null
        }
    }

    /**
     * Read one IP packet into a reusable ByteBuffer.
     * Returns the number of bytes read, or -1 on error/EOF.
     * The buffer is flipped and ready to read after this call.
     */
    fun readPacketInto(target: ByteBuffer): Int {
        return try {
            target.clear()
            val bytesRead = tunChannel.read(target)
            if (bytesRead > 0) {
                target.flip()
            }
            bytesRead
        } catch (e: IOException) {
            Log.e(TAG, "Error reading from TUN", e)
            -1
        }
    }

    companion object {
        private const val TAG = "PacketReader"
        /** Maximum IP packet size (MTU). We use a generous buffer. */
        const val MAX_PACKET_SIZE = 32767
    }
}
