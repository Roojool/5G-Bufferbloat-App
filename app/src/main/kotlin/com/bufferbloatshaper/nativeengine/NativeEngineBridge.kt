package com.bufferbloatshaper.nativeengine

import com.bufferbloatshaper.model.ShaperConfig

/**
 * Small, typed Kotlin boundary for the native packet engine ABI.
 *
 * The library may be packaged without a real engine while the gVisor adapter
 * is being developed. In that case every public entry point reports an
 * unavailable capability and callers must fail before establishing a VPN TUN.
 */
object NativeEngineBridge {
    const val STATUS_OK = 0
    const val STATUS_NO_EVENT = 1
    const val STATUS_UNAVAILABLE = -3

    private val libraryLoadFailure: Throwable? by lazy {
        try {
            System.loadLibrary(LIBRARY_NAME)
            null
        } catch (error: Throwable) {
            error
        }
    }

    data class Capability(
        val available: Boolean,
        val detail: String,
        val buildInfo: String? = null
    )

    data class Session internal constructor(internal var handle: Long)

    data class Metrics(
        val status: Int,
        val generation: Long,
        val sampledAtMs: Long,
        val packetsIn: Long,
        val packetsOut: Long,
        val bytesIn: Long,
        val bytesOut: Long,
        val activeFlows: Long,
        val queuedBytes: Long,
        val aqmDrops: Long,
        val udpPacketsPaced: Long
    )

    fun capability(): Capability {
        val loadError = libraryLoadFailure
        if (loadError != null) {
            return Capability(
                available = false,
                detail = "Native engine library could not be loaded: ${loadError.javaClass.simpleName}."
            )
        }

        return try {
            val available = nativeIsAvailable() == 1
            val buildInfo = nativeBuildInfo()
            Capability(
                available = available,
                detail = if (available) {
                    "Native engine is available."
                } else {
                    "Native engine is not available in this build ($buildInfo)."
                },
                buildInfo = buildInfo
            )
        } catch (error: Throwable) {
            Capability(
                available = false,
                detail = "Native engine self-check failed: ${error.javaClass.simpleName}."
            )
        }
    }

    /**
     * Starts a new engine over a borrowed TUN descriptor. A successful native
     * implementation must duplicate the descriptor before returning.
     */
    fun start(borrowedTunFd: Int, config: ShaperConfig): Result<Session> {
        val capability = capability()
        if (!capability.available) {
            return Result.failure(NativeEngineUnavailableException(capability.detail))
        }
        if (borrowedTunFd < 0) {
            return Result.failure(IllegalArgumentException("The VPN TUN descriptor is invalid."))
        }

        return try {
            val handle = nativeCreate()
            if (handle == 0L) {
                Result.failure(IllegalStateException("Native engine allocation failed."))
            } else {
                val status = nativeStart(
                    handle = handle,
                    tunFd = borrowedTunFd,
                    egressRateBytesPerSec = config.egressRateBytesPerSec,
                    ingressRateBytesPerSec = config.ingressRateBytesPerSec,
                    codelTargetMs = config.codelTargetMs.toInt(),
                    codelIntervalMs = config.codelIntervalMs.toInt(),
                    flags = 0
                )
                if (status == STATUS_OK) {
                    Result.success(Session(handle))
                } else {
                    nativeDestroy(handle)
                    Result.failure(IllegalStateException(statusMessage(status)))
                }
            }
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    fun update(session: Session, config: ShaperConfig): Result<Unit> = try {
        require(session.handle != 0L) { "Native engine session is already closed." }
        val status = nativeUpdateConfig(
            handle = session.handle,
            egressRateBytesPerSec = config.egressRateBytesPerSec,
            ingressRateBytesPerSec = config.ingressRateBytesPerSec,
            codelTargetMs = config.codelTargetMs.toInt(),
            codelIntervalMs = config.codelIntervalMs.toInt(),
            flags = 0
        )
        if (status == STATUS_OK) Result.success(Unit)
        else Result.failure(IllegalStateException(statusMessage(status)))
    } catch (error: Throwable) {
        Result.failure(error)
    }

    fun metrics(session: Session): Metrics? {
        return try {
            if (session.handle == 0L) {
                null
            } else {
                val values = nativeGetMetrics(session.handle)
                if (values.size < 12 || values[0].toInt() != STATUS_OK) {
                    null
                } else {
                    Metrics(
                        status = values[0].toInt(),
                        generation = values[2],
                        sampledAtMs = values[3],
                        packetsIn = values[4],
                        packetsOut = values[5],
                        bytesIn = values[6],
                        bytesOut = values[7],
                        activeFlows = values[8],
                        queuedBytes = values[9],
                        aqmDrops = values[10],
                        udpPacketsPaced = values[11]
                    )
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Idempotently stop native work and release its opaque handle. */
    fun close(session: Session?) {
        val handle = session?.handle ?: return
        if (handle == 0L) return
        try {
            nativeStop(handle)
        } catch (_: Throwable) {
            // Kotlin still owns and closes the ParcelFileDescriptor afterwards.
        } finally {
            try {
                nativeDestroy(handle)
            } catch (_: Throwable) {
                // A stale native handle is intentionally harmless in the JNI bridge.
            }
            session.handle = 0L
        }
    }

    private fun statusMessage(status: Int): String = try {
        nativeStatusMessage(status)
    } catch (_: Throwable) {
        "Native engine failed with status $status."
    }

    @JvmStatic private external fun nativeIsAvailable(): Int
    @JvmStatic private external fun nativeBuildInfo(): String
    @JvmStatic private external fun nativeCreate(): Long
    @JvmStatic private external fun nativeDestroy(handle: Long)
    @JvmStatic private external fun nativeStart(
        handle: Long,
        tunFd: Int,
        egressRateBytesPerSec: Long,
        ingressRateBytesPerSec: Long,
        codelTargetMs: Int,
        codelIntervalMs: Int,
        flags: Int
    ): Int
    @JvmStatic private external fun nativeUpdateConfig(
        handle: Long,
        egressRateBytesPerSec: Long,
        ingressRateBytesPerSec: Long,
        codelTargetMs: Int,
        codelIntervalMs: Int,
        flags: Int
    ): Int
    @JvmStatic private external fun nativeStop(handle: Long): Int
    @JvmStatic private external fun nativeGetMetrics(handle: Long): LongArray
    @JvmStatic private external fun nativeStatusMessage(status: Int): String

    private const val LIBRARY_NAME = "bufferbloat_native_engine"
}

class NativeEngineUnavailableException(message: String) : IllegalStateException(message)
