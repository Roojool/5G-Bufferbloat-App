package com.bufferbloatshaper.harness

import org.json.JSONArray
import org.json.JSONObject

/** Explicit allowlist. Endpoint, network handle, payload and free-form exception text never exported. */
fun ExperimentResult.toJson(config: ExperimentConfig): String = JSONObject().apply {
    put("schema", 1)
    put("build_type", "debug_internal_no_route")
    put("outcome", outcome); put("errno", errno ?: JSONObject.NULL)
    put("bytes", bytes); put("sha256", sha256); put("elapsed_ms", elapsedMs)
    put("longest_no_progress_ms", longestNoProgressMs); put("recoveries_after_1s", recoveries)
    put("close_errno", closeErrno ?: JSONObject.NULL); put("samples_omitted", samplesOmitted)
    fun evidence(record: EvidenceRecord) = JSONObject().apply {
        put("status", record.status); put("artifact_sha256", record.artifactSha256 ?: JSONObject.NULL)
        put("reviewed_conclusion", record.reviewedConclusion ?: JSONObject.NULL)
    }
    put("transport_effect", evidence(transportEffect)); put("integrity_recovery", evidence(integrityRecovery))
    put("physical_benefit", evidence(physicalBenefit))
    put("cadence_events", JSONArray().apply { cadenceEvents.forEach { c -> put(JSONObject().apply {
        put("at_ms", c.atMs); put("cadence_ms", c.cadenceMs)
    }) } })
    put("plan", JSONObject().apply {
        put("expected_bytes", config.expectedBytes); put("duration_ms", config.durationMs)
        put("stall_timeout_ms", config.stallTimeoutMs); put("cadence_ms", config.cadenceMs)
        put("read_bytes", config.readBytes)
        put("changes", JSONArray().apply { config.changes.forEach { c -> put(JSONObject().apply {
            put("at_ms_after_connect", c.atMs); put("rcvbuf", c.receiveBuffer ?: JSONObject.NULL)
            put("clamp", c.clamp ?: JSONObject.NULL); put("cadence_ms", c.cadenceMs ?: JSONObject.NULL)
        }) } })
    })
    put("options", JSONArray().apply { options.forEach { o -> put(JSONObject().apply {
        put("at_ms", o.atMs); put("phase", o.phase); put("kind", o.kind)
        put("requested", o.requested ?: JSONObject.NULL); put("constant_available", o.constantAvailable)
        put("set_errno", o.setErrno ?: JSONObject.NULL); put("get_errno", o.getErrno ?: JSONObject.NULL)
        put("returned", o.returned ?: JSONObject.NULL); put("returned_length", o.returnedLength)
    }) } })
    put("samples", JSONArray().apply { samples.forEach { s -> put(JSONObject().apply {
        put("at_ms", s.atMs); put("bytes", s.bytes); put("no_progress_ms", s.noProgressMs)
        put("tcp_info_errno", s.info.errno); put("tcp_info_length", s.info.returnedLength)
        put("tcp_info_constant_available", true) // compiled by the pinned headers; NOT runtime support
        put("fields", JSONObject().apply { s.info.fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
    }) } })
}.toString(2)
