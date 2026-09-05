package com.bufferbloatshaper.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The observable, measured state shared by every screen and notification. */
enum class VpnRuntimeStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
    UNSUPPORTED
}

data class VpnRuntimeMetrics(
    val egressRateBytesPerSec: Double = 0.0,
    val ingressTargetBytesPerSec: Long = 0L,
    /** Derived only from consecutive native byte counters; null means no sample. */
    val measuredEgressBytesPerSec: Double? = null,
    /** Derived only from consecutive native byte counters; null means no sample. */
    val measuredIngressBytesPerSec: Double? = null,
    val bytesEgressed: Long = 0L,
    val packetsEgressed: Long = 0L,
    val queuedPackets: Int = 0,
    val queuedBytes: Long = 0L,
    val droppedPackets: Long = 0L,
    val activeFlows: Int = 0,
    val measuredAtMs: Long = 0L
)

data class VpnRuntimeState(
    val status: VpnRuntimeStatus = VpnRuntimeStatus.STOPPED,
    val generation: Long = 0L,
    val detail: String = "Shaping is off.",
    val recoverableError: String? = null,
    val config: ShaperConfig? = null,
    val metrics: VpnRuntimeMetrics = VpnRuntimeMetrics(),
    val startedAtMs: Long? = null,
    val ipv6Supported: Boolean = false,
    val nativeEngineAvailable: Boolean = false
)

/** A redaction-ready, local-only record of a meaningful runtime transition. */
data class VpnHealthEvent(
    val timestampMs: Long,
    val generation: Long,
    val status: VpnRuntimeStatus,
    val detail: String,
    val recoverableError: String?
)

/**
 * Process-local state only. It is deliberately not persisted or sent off the
 * device; a service restart begins with an explicit state transition.
 */
object VpnRuntimeStateStore {
    private const val MAX_TIMELINE_EVENTS = 100

    private val lock = Any()
    private val mutableState = MutableStateFlow(VpnRuntimeState())
    private val mutableTimeline = MutableStateFlow<List<VpnHealthEvent>>(emptyList())
    val state: StateFlow<VpnRuntimeState> = mutableState.asStateFlow()
    val timeline: StateFlow<List<VpnHealthEvent>> = mutableTimeline.asStateFlow()

    fun publish(next: VpnRuntimeState) {
        synchronized(lock) {
            val previous = mutableState.value
            mutableState.value = next
            appendTransition(previous, next)
        }
    }

    fun update(transform: (VpnRuntimeState) -> VpnRuntimeState) {
        synchronized(lock) {
            val previous = mutableState.value
            val next = transform(previous)
            mutableState.value = next
            appendTransition(previous, next)
        }
    }

    private fun appendTransition(previous: VpnRuntimeState, next: VpnRuntimeState) {
        val meaningfulChange = previous.status != next.status ||
            previous.generation != next.generation ||
            previous.detail != next.detail ||
            previous.recoverableError != next.recoverableError
        if (!meaningfulChange) return

        mutableTimeline.value = (mutableTimeline.value + VpnHealthEvent(
            timestampMs = System.currentTimeMillis(),
            generation = next.generation,
            status = next.status,
            detail = next.detail,
            recoverableError = next.recoverableError
        )).takeLast(MAX_TIMELINE_EVENTS)
    }

    /** Keeps the singleton deterministic for local JVM tests. */
    internal fun resetForTests() {
        synchronized(lock) {
            mutableState.value = VpnRuntimeState()
            mutableTimeline.value = emptyList()
        }
    }
}
