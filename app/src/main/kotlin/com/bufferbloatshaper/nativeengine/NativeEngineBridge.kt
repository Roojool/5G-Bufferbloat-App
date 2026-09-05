package com.bufferbloatshaper.nativeengine

import com.bufferbloatshaper.model.ShaperConfig
import kotlin.math.roundToInt

/**
 * Android's VpnService must protect every native-created outbound socket or it
 * can loop back into the TUN. The engine receives this narrow capability, not
 * a VpnService or Context object.
 */
fun interface SocketProtector {
    fun protectSocket(socketFd: Int): Boolean
}

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
    const val ENGINE_STATE_RUNNING = 2
    const val ENGINE_STATE_FAILED = 5

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
        val buildInfo: String? = null,
        val featureBits: Long = 0L
    )

    /** Opaque, non-copyable native generation handle. */
    class Session internal constructor(@Volatile internal var handle: Long)

    data class Metrics(
        val status: Int,
        val state: Int,
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

    data class Health(
        val status: Int,
        val state: Int,
        val lastStatus: Int,
        val lastEventType: Int,
        val detailCode: Int,
        val generation: Long
    )

    data class Event(
        val status: Int,
        val type: Int,
        val eventStatus: Int,
        val detailCode: Int,
        val generation: Long,
        val occurredAtMs: Long
    )

    data class FlowMetrics(
        val status: Int,
        val state: Int,
        val generation: Long,
        val flowId: Long,
        val bytesIn: Long,
        val bytesOut: Long,
        val queuedBytes: Long,
        val aqmDrops: Long,
        val protocol: Int
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
            val engineLinked = nativeIsAvailable() == 1
            val featureBits = nativeFeatureBits()
            val available = engineLinked &&
                (featureBits and REQUIRED_TRAFFIC_FEATURES) == REQUIRED_TRAFFIC_FEATURES
            val buildInfo = nativeBuildInfo()
            Capability(
                available = available,
                detail = if (available) {
                    "Native engine is available."
                } else if (engineLinked) {
                    "Native engine is missing required safe-forwarding features ($buildInfo)."
                } else {
                    "Native engine is not available in this build ($buildInfo)."
                },
                buildInfo = buildInfo,
                featureBits = featureBits
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
    fun start(
        borrowedTunFd: Int,
        config: ShaperConfig,
        socketProtector: SocketProtector
    ): Result<Session> {
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
                    fairQueueBuckets = config.fqBuckets,
                    headroomPermille = (config.headroomFactor * 1_000).roundToInt(),
                    burstPermille = (config.burstFraction * 1_000).roundToInt(),
                    flags = config.nativeFlags(),
                    socketProtector = socketProtector
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
            fairQueueBuckets = config.fqBuckets,
            headroomPermille = (config.headroomFactor * 1_000).roundToInt(),
            burstPermille = (config.burstFraction * 1_000).roundToInt(),
            flags = config.nativeFlags()
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
                        state = values[1].toInt(),
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

    /** Pull a structured health snapshot; a non-OK query is treated as unavailable. */
    fun health(session: Session): Health? {
        if (session.handle == 0L) return null
        return try {
            val values = nativeGetHealth(session.handle)
            if (values.size < 6 || values[0].toInt() != STATUS_OK) null else Health(
                status = values[0].toInt(),
                state = values[1].toInt(),
                lastStatus = values[2].toInt(),
                lastEventType = values[3].toInt(),
                detailCode = values[4].toInt(),
                generation = values[5]
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** Returns one native event, or null when no event is queued. */
    fun pollEvent(session: Session): Event? {
        if (session.handle == 0L) return null
        return try {
            val values = nativePollEvent(session.handle)
            when {
                values.size < 6 -> null
                values[0].toInt() == STATUS_NO_EVENT -> null
                values[0].toInt() != STATUS_OK -> null
                else -> Event(
                    status = values[0].toInt(),
                    type = values[1].toInt(),
                    eventStatus = values[2].toInt(),
                    detailCode = values[3].toInt(),
                    generation = values[4],
                    occurredAtMs = values[5]
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun flowMetrics(session: Session, flowId: Long): FlowMetrics? {
        if (session.handle == 0L || flowId < 0L) return null
        return try {
            val values = nativeGetFlowMetrics(session.handle, flowId)
            if (values.size < 10 || values[0].toInt() != STATUS_OK) null else FlowMetrics(
                status = values[0].toInt(),
                state = values[1].toInt(),
                generation = values[2],
                flowId = values[3],
                bytesIn = values[4],
                bytesOut = values[5],
                queuedBytes = values[6],
                aqmDrops = values[7],
                protocol = values[8].toInt()
            )
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

    private fun ShaperConfig.nativeFlags(): Int =
        if (smartModeEnabled) FLAG_SMART_MODE_REQUESTED else 0

    @JvmStatic private external fun nativeIsAvailable(): Int
    @JvmStatic private external fun nativeFeatureBits(): Long
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
        fairQueueBuckets: Int,
        headroomPermille: Int,
        burstPermille: Int,
        flags: Int,
        socketProtector: SocketProtector
    ): Int
    @JvmStatic private external fun nativeUpdateConfig(
        handle: Long,
        egressRateBytesPerSec: Long,
        ingressRateBytesPerSec: Long,
        codelTargetMs: Int,
        codelIntervalMs: Int,
        fairQueueBuckets: Int,
        headroomPermille: Int,
        burstPermille: Int,
        flags: Int
    ): Int
    @JvmStatic private external fun nativeStop(handle: Long): Int
    @JvmStatic private external fun nativeGetHealth(handle: Long): LongArray
    @JvmStatic private external fun nativeGetMetrics(handle: Long): LongArray
    @JvmStatic private external fun nativePollEvent(handle: Long): LongArray
    @JvmStatic private external fun nativeGetFlowMetrics(handle: Long, flowId: Long): LongArray
    @JvmStatic private external fun nativeStatusMessage(status: Int): String

    private const val LIBRARY_NAME = "bufferbloat_native_engine"
    private const val FLAG_SMART_MODE_REQUESTED = 1
    private const val REQUIRED_TRAFFIC_FEATURES = 0x1FL
}

class NativeEngineUnavailableException(message: String) : IllegalStateException(message)
