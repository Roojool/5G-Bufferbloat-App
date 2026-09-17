package com.bufferbloatshaper.harness

/** Packaged only in debug. Never changes production NativeEngineBridge ABI v2. */
object SocketNative : UploadSocketOps {
    init { System.loadLibrary("bufferbloat_socket_harness") }
    external override fun open(ipv6: Boolean): Int
    external override fun close(fd: Int): Int
    external override fun connect(fd: Int, address: String, port: Int): Int
    external override fun poll(fd: Int, writing: Boolean): Int
    external override fun socketError(fd: Int): Int
    external override fun read(fd: Int, target: ByteArray, limit: Int): Int
    external override fun option(fd: Int, kind: Int, set: Boolean, requested: Int): LongArray
    external override fun info(fd: Int): LongArray
    external override fun write(fd: Int, source: ByteArray, offset: Int, length: Int): Int
    external override fun shutdownOutput(fd: Int): Int
    external override fun abort(fd: Int): Int
    external override fun sendQueue(fd: Int, notSent: Boolean): LongArray
    external fun decodeFixture(length: Int, error: Int): LongArray
    external fun fieldEnds(): IntArray
}
