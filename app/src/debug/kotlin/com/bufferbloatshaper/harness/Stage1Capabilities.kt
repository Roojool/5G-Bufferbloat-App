package com.bufferbloatshaper.harness

/** Debug contract only. No production activation caller; model names are deliberately absent. */
enum class CapabilityState { UNKNOWN, AVAILABLE, UNAVAILABLE }
enum class CapabilityReason { NOT_PROBED, OBSERVED, CALL_FAILED, MALFORMED, STALE, SCOPE_CHANGED }
enum class Stage1Capability(val mandatory: Boolean) {
    PROTECTION(true), NETWORK_BINDING(true), BOUNDED_SEND_BUFFER(true),
    IPV4_TCP_FORWARDING(true), IPV4_UDP_FORWARDING(true), SAFE_STOP(true),
    HEALTH_EVENTS(true), FLOW_METRICS(true),
    TCP_INFO(false), SEND_QUEUE(false), NOT_SENT_QUEUE(false), DOWNLOAD_CONTROL(false),
}

data class CapabilityScope(val androidApi: Int, val kernelFamily: String, val abi: String,
    val transport: BatchTransport, val ipv6: Boolean) {
    init {
        require(androidApi >= 26)
        require(kernelFamily == "UNKNOWN" || Regex("[0-9]{1,3}\\.[0-9]{1,3}").matches(kernelFamily))
        require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "UNKNOWN"))
    }
}

data class CapabilityEvidence(val state: CapabilityState, val reason: CapabilityReason,
    val scope: CapabilityScope, val atMs: Long, val errno: Int? = null)

class Stage1Capabilities(private val scope: CapabilityScope) {
    private val evidence = mutableMapOf<Stage1Capability, CapabilityEvidence>()

    fun record(capability: Stage1Capability, atMs: Long, valid: Boolean, errno: Int? = null) {
        require(atMs >= 0 && (errno == null || errno >= 0))
        evidence[capability] = CapabilityEvidence(
            if (valid && (errno == null || errno == 0)) CapabilityState.AVAILABLE else CapabilityState.UNAVAILABLE,
            if (errno != null && errno != 0) CapabilityReason.CALL_FAILED else if (!valid) CapabilityReason.MALFORMED
            else CapabilityReason.OBSERVED, scope, atMs, errno)
    }

    fun failed(capability: Stage1Capability, atMs: Long) {
        evidence[capability] = CapabilityEvidence(CapabilityState.UNAVAILABLE, CapabilityReason.CALL_FAILED, scope, atMs)
    }

    fun get(capability: Stage1Capability, nowMs: Long, currentScope: CapabilityScope = scope): CapabilityEvidence {
        val found = evidence[capability]
        val reason = when {
            currentScope != scope -> CapabilityReason.SCOPE_CHANGED
            found == null -> CapabilityReason.NOT_PROBED
            nowMs < found.atMs || nowMs - found.atMs > 120_000 -> CapabilityReason.STALE
            else -> return found
        }
        return CapabilityEvidence(CapabilityState.UNKNOWN, reason, currentScope, nowMs)
    }

    /** Unknown/failed optional probes disable only that observation; required failures block the contract. */
    fun enabled(capability: Stage1Capability, nowMs: Long, currentScope: CapabilityScope = scope) =
        get(capability, nowMs, currentScope).state == CapabilityState.AVAILABLE

    fun mandatorySatisfied(nowMs: Long) = Stage1Capability.entries.filter { it.mandatory }.all { enabled(it, nowMs) }
    fun snapshot(nowMs: Long) = Stage1Capability.entries.associate { it.name to get(it, nowMs) }
}
