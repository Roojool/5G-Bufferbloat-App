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

/**
 * Process-local state only. It is deliberately not persisted or sent off the
 * device; a service restart begins with an explicit state transition.
 */
object VpnRuntimeStateStore {
    private val mutableState = MutableStateFlow(VpnRuntimeState())
    val state: StateFlow<VpnRuntimeState> = mutableState.asStateFlow()

    fun publish(next: VpnRuntimeState) {
        mutableState.value = next
    }

    fun update(transform: (VpnRuntimeState) -> VpnRuntimeState) {
        mutableState.value = transform(mutableState.value)
    }
}
