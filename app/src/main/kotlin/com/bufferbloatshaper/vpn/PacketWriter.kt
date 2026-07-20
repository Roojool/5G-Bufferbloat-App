package com.bufferbloatshaper.vpn

import android.util.Log
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Writes packets back to the TUN file descriptor.
 *
 * Packets written to the TUN fd are delivered to the Android networking stack
 * as if they arrived from the network — this is how responses from the relay
 * get back to the originating app.
 */
class PacketWriter(
    private val tunChannel: FileChannel
) {
    /**
     * Write a raw IP packet to the TUN interface.
     * The packet must be a complete, valid IP packet (no ethernet framing).
     */
    @Synchronized
    fun writePacket(packet: ByteArray): Boolean {
        return try {
            val buffer = ByteBuffer.wrap(packet)
            while (buffer.hasRemaining()) {
                tunChannel.write(buffer)
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error writing to TUN", e)
            false
        }
    }

    /**
     * Write a packet from a ByteBuffer to the TUN interface.
     */
    @Synchronized
    fun writePacket(buffer: ByteBuffer): Boolean {
        return try {
            while (buffer.hasRemaining()) {
                tunChannel.write(buffer)
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error writing to TUN", e)
            false
        }
    }

    companion object {
        private const val TAG = "PacketWriter"
    }
}
