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
        val featureBits: Long = 0L,
        val abiVersion: Int = 0,
        val requiredFeatureBits: Long = 0L,
        val shutdownQuarantined: Boolean = false
    )

    /** Opaque, non-copyable native generation handle. */
    class Session internal constructor(initialHandle: Long) {
        private val lock = Any()
        private var handle = initialHandle

        /** Serializes a bridge operation with close so a stale Session cannot
         * race a destroy or operate on a newer engine generation. */
        internal fun <T> withOpenHandle(operation: (Long) -> T): T? = synchronized(lock) {
            val current = handle
            if (current == 0L) null else operation(current)
        }

        /** Retires this Kotlin capability before teardown; native tokens are
         * monotonic and never expose raw pointers. */
        internal fun closeOnce(operation: (Long) -> Result<Unit>): Result<Unit> = synchronized(lock) {
            val current = handle
            if (current == 0L) Result.success(Unit) else {
                handle = 0L
                operation(current)
            }
        }
    }

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
            val abiVersion = nativeAbiVersion()
            val requiredFeatureBits = nativeRequiredFeatureBits()
            val shutdownQuarantined = nativeHasQuarantinedEngine() != 0
            val engineLinked = nativeIsAvailable() != 0
            val featureBits = nativeFeatureBits()
            val available = isSafeActivationContract(
                abiVersion = abiVersion,
                engineLinked = engineLinked,
                featureBits = featureBits,
                nativeRequiredFeatureBits = requiredFeatureBits,
                shutdownQuarantined = shutdownQuarantined
            )
            val buildInfo = nativeBuildInfo()
            Capability(
                available = available,
                detail = if (available) {
                    "Native engine is available."
                } else if (shutdownQuarantined) {
                    "A prior native engine did not confirm shutdown. Restart the app before trying again."
                } else if (abiVersion != EXPECTED_ABI_VERSION) {
                    "Native engine ABI $abiVersion is incompatible with expected ABI $EXPECTED_ABI_VERSION."
                } else if (requiredFeatureBits != REQUIRED_TRAFFIC_FEATURES) {
                    "Native engine activation requirements are incompatible with this app build."
                } else if (engineLinked) {
                    "Native engine is missing required safe-forwarding features ($buildInfo)."
                } else {
                    "Native engine is not available in this build ($buildInfo)."
                },
                buildInfo = buildInfo,
                featureBits = featureBits,
                abiVersion = abiVersion,
                requiredFeatureBits = requiredFeatureBits,
                shutdownQuarantined = shutdownQuarantined
            )
        } catch (error: Throwable) {
            Capability(
                available = false,
                detail = "Native engine self-check failed: ${error.javaClass.simpleName}."
            )
        }
    }

    /**
     * Kotlin owns the activation minimum; a future native implementation may
     * advertise additional features but cannot lower protected-socket, safe
     * stop, health, metrics, TCP, or safe UDP forwarding requirements.
     */
    internal fun isSafeActivationContract(
        abiVersion: Int,
        engineLinked: Boolean,
        featureBits: Long,
        nativeRequiredFeatureBits: Long,
        shutdownQuarantined: Boolean
    ): Boolean =
        !shutdownQuarantined &&
            abiVersion == EXPECTED_ABI_VERSION &&
            engineLinked &&
            nativeRequiredFeatureBits == REQUIRED_TRAFFIC_FEATURES &&
            (featureBits and REQUIRED_TRAFFIC_FEATURES) == REQUIRED_TRAFFIC_FEATURES

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

        val handle = try {
            nativeCreate()
        } catch (error: Throwable) {
            return Result.failure(error)
        }
        if (handle == 0L) {
            return Result.failure(IllegalStateException("Native engine allocation failed."))
        }

        return try {
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
                val startFailure = IllegalStateException(statusMessage(status))
                Result.failure(combineStartAndCleanupFailure(startFailure, stopAndDestroy(handle).exceptionOrNull()))
            }
        } catch (error: Throwable) {
            // A Java/JNI exception can arrive after native start has begun.
            // Always request stop/join before the handle is eligible for free.
            Result.failure(combineStartAndCleanupFailure(error, stopAndDestroy(handle).exceptionOrNull()))
        }
    }

    fun update(session: Session, config: ShaperConfig): Result<Unit> =
        session.withOpenHandle { handle ->
            try {
                val status = nativeUpdateConfig(
                    handle = handle,
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
        } ?: Result.failure(IllegalStateException("Native engine session is already closed."))

    fun metrics(session: Session): Metrics? {
        return session.withOpenHandle { handle ->
            try {
                val values = nativeGetMetrics(handle)
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
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** Pull a structured health snapshot; a non-OK query is treated as unavailable. */
    fun health(session: Session): Health? {
        return session.withOpenHandle { handle ->
            try {
                val values = nativeGetHealth(handle)
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
    }

    /** Returns one native event, or null when no event is queued. */
    fun pollEvent(session: Session): Event? {
        return session.withOpenHandle { handle ->
            try {
                val values = nativePollEvent(handle)
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
    }

    fun flowMetrics(session: Session, flowId: Long): FlowMetrics? {
        if (flowId < 0L) return null
        return session.withOpenHandle { handle ->
            try {
                val values = nativeGetFlowMetrics(handle, flowId)
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
    }

    /**
     * Idempotently stop native work and release its opaque handle. A non-OK
     * stop is never followed by destroy: JNI retains the token and callback
     * context rather than risking a use-after-free in a broken future engine.
     */
    fun close(session: Session?): Result<Unit> {
        if (session == null) return Result.success(Unit)
        return session.closeOnce(::stopAndDestroy)
    }

    private fun stopAndDestroy(handle: Long): Result<Unit> {
        val stopStatus = try {
            nativeStop(handle)
        } catch (error: Throwable) {
            return Result.failure(
                NativeEngineStopFailureException(
                    "Native engine did not confirm worker shutdown.",
                    error
                )
            )
        }
        if (stopStatus != STATUS_OK) {
            return Result.failure(
                NativeEngineStopFailureException(
                    "Native engine did not confirm worker shutdown: ${statusMessage(stopStatus)}"
                )
            )
        }

        val destroyStatus = try {
            nativeDestroy(handle)
        } catch (error: Throwable) {
            return Result.failure(error)
        }
        return if (destroyStatus == STATUS_OK) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException("Native engine cleanup failed: ${statusMessage(destroyStatus)}")
            )
        }
    }

    private fun combineStartAndCleanupFailure(
        startFailure: Throwable,
        cleanupFailure: Throwable?
    ): Throwable {
        if (cleanupFailure is NativeEngineStopFailureException) {
            cleanupFailure.addSuppressed(startFailure)
            return cleanupFailure
        }
        cleanupFailure?.let(startFailure::addSuppressed)
        return startFailure
    }

    private fun statusMessage(status: Int): String = try {
        nativeStatusMessage(status)
    } catch (_: Throwable) {
        "Native engine failed with status $status."
    }

    private fun ShaperConfig.nativeFlags(): Int =
        if (smartModeEnabled) FLAG_SMART_MODE_REQUESTED else 0

    @JvmStatic private external fun nativeIsAvailable(): Int
    @JvmStatic private external fun nativeAbiVersion(): Int
    @JvmStatic private external fun nativeRequiredFeatureBits(): Long
    @JvmStatic private external fun nativeHasQuarantinedEngine(): Int
    @JvmStatic private external fun nativeFeatureBits(): Long
    @JvmStatic private external fun nativeBuildInfo(): String
    @JvmStatic private external fun nativeCreate(): Long
    @JvmStatic private external fun nativeDestroy(handle: Long): Int
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
    private const val EXPECTED_ABI_VERSION = 2
    private const val REQUIRED_TRAFFIC_FEATURES = 0x3fL
}

class NativeEngineUnavailableException(message: String) : IllegalStateException(message)

/**
 * A future native engine could still own a duplicated TUN descriptor after
 * this result, or native startup/shutdown could exceed the independent
 * lifecycle deadline. The service treats either as a process-termination
 * safety event, not a recoverable in-process teardown failure.
 */
class NativeEngineStopFailureException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
