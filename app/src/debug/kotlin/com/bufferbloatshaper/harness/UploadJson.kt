package com.bufferbloatshaper.harness

import com.bufferbloatshaper.harness.stream.StreamHarnessConfig
import org.json.JSONArray
import org.json.JSONObject

internal fun parseUploadConfig(endpoint: ExperimentConfig, json: JSONObject): UploadConfig {
    val bytes = json.getJSONArray("flowBytes")
    val changes = json.optJSONArray("rateChanges")
    return UploadConfig(endpoint, (0 until bytes.length()).map { bytes.getLong(it) },
        StreamHarnessConfig(json.getLong("rateBytesPerSecond"), json.optInt("burstBytes", 16384),
            json.optInt("quantumBytes", 4096), json.optInt("perFlowBufferBytes", 65536),
            json.optInt("globalBufferBytes", 262144), maxWriteBytes = 16384,
            durationMs = endpoint.durationMs, stallTimeoutMs = endpoint.stallTimeoutMs,
            rateChanges = (0 until (changes?.length() ?: 0)).map {
                val change = changes!!.getJSONObject(it)
                StreamHarnessConfig.RateChange(change.getLong("atMs"), change.getLong("rateBytesPerSecond"))
            }), json.optInt("sendBufferBytes", 16384), json.optInt("maxKernelSendBufferBytes", 131072)
    ).also { it.validate() }
}

private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
    pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
private fun <T> array(items: List<T>, convert: (T) -> JSONObject) = JSONArray().apply { items.forEach { put(convert(it)) } }

/** Explicit export schema: no reflection, endpoints, OS error strings, or private identifiers. */
fun UploadResult.toJson(config: UploadConfig): JSONObject = obj(
    "schema" to 1, "experiment" to "upload", "outcome" to outcome, "errno" to errno,
    "elapsed_ms" to elapsedMs, "physical_benefit" to UNVERIFIED,
    "delivery_status" to if (outcome == "COMPLETE") "RECEIVER_HASH_VERIFIED" else "UNVERIFIED",
    "samples_omitted" to samplesOmitted,
    "wire_rate_bytes_per_second" to null, "kernel_memory_allocation_bytes" to null,
    "unsupported_observations" to JSONArray(listOf("RADIO_QUEUE", "WIRE_DEPARTURE_RATE", "KERNEL_MEMORY_ALLOCATION")),
    "plan" to obj("flow_bytes" to JSONArray(config.flowBytes), "rate_bytes_per_second" to config.pacing.rateBytesPerSecond,
        "burst_bytes" to config.pacing.burstBytes, "quantum_bytes" to config.pacing.quantumBytes,
        "per_flow_buffer_bytes" to config.pacing.perFlowBufferBytes, "global_buffer_bytes" to config.pacing.globalBufferBytes,
        "requested_sndbuf_bytes" to config.sendBufferBytes, "max_kernel_sndbuf_readback_bytes" to config.maxKernelSendBufferBytes,
        "duration_ms" to config.endpoint.durationMs, "stall_timeout_ms" to config.endpoint.stallTimeoutMs,
        "rate_changes" to array(config.pacing.rateChanges) { obj("at_ms_after_pacing_start" to it.atMs,
            "rate_bytes_per_second" to it.rateBytesPerSecond) },
        "jni_write_scratch_bound_bytes" to 16384, "receipt_capacity_bytes_per_flow" to 66),
    "sockets" to array(sockets) { socket -> obj("flow_index" to socket.flowIndex,
        "receipt_sha256" to socket.receiptSha256, "receipt_status" to socket.receiptStatus,
        "abort_errno" to socket.abortErrno, "close_errno" to socket.closeErrno,
        "options" to array(socket.options) { o -> obj("at_ms" to o.atMs, "phase" to o.phase, "kind" to o.kind,
            "requested" to o.requested, "constant_available" to o.constantAvailable, "set_errno" to o.setErrno,
            "get_errno" to o.getErrno, "returned" to o.returned, "returned_length" to o.returnedLength) }) },
    "capabilities" to array(capabilities) { evidence -> JSONObject().apply { evidence.forEach { (key, e) ->
        put(key, obj("state" to e.state.name, "reason" to e.reason.name, "at_ms" to e.atMs, "errno" to e.errno,
            "mandatory" to Stage1Capability.valueOf(key).mandatory,
            "scope" to obj("android_api" to e.scope.androidApi, "kernel_family" to e.scope.kernelFamily,
                "abi" to e.scope.abi, "transport" to e.scope.transport.wireName, "ipv6" to e.scope.ipv6)))
    } } },
    "samples" to array(samples) { sample ->
        fun queues(values: List<QueueObservation>) = array(values) { obj("constant_available" to it.available,
            "errno" to it.errno, "bytes" to it.bytes) }
        obj("at_ms_after_pacing_start" to sample.progress.atMs, "configured_rate_bytes_per_second" to sample.progress.rateBytesPerSecond,
            "observed_interval_ms" to sample.intervalMs,
            "write_acceptance_bytes_per_second" to JSONArray(sample.writeAcceptanceBytesPerSecond),
            "userspace_queued_bytes" to JSONArray(sample.progress.queuedBytes),
            "accepted_bytes" to JSONArray(sample.progress.acceptedBytes), "written_bytes" to JSONArray(sample.progress.writtenBytes),
            "outq" to queues(sample.sendQueues), "not_sent" to queues(sample.notSentQueues),
            "tcp_info" to array(sample.tcpInfo) { info -> obj("errno" to info.errno, "length" to info.returnedLength,
                "fields" to JSONObject().apply { info.fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }) })
    },
).apply {
    stream?.let { s ->
        put("stream", obj("outcome" to s.outcome, "elapsed_ms" to s.elapsedMs,
            "allocated_queue_bytes" to s.allocatedQueueBytes, "read_scratch_bytes" to s.readScratchBytes,
            "retained_queue_bytes_after_cleanup" to s.retainedQueueBytesAfterCleanup,
            "max_global_queued_bytes" to s.maxGlobalQueuedBytes, "total_accepted_bytes" to s.totalAcceptedBytes,
            "total_written_bytes" to s.totalWrittenBytes, "pacing_wait_count" to s.pacingWaitCount,
            "scheduler_rounds" to s.schedulerRounds, "largest_write_request_bytes" to s.largestWriteRequestBytes,
            "write_acceptance_bytes_per_second" to if (s.elapsedMs > 0) s.totalWrittenBytes * 1000.0 / s.elapsedMs else null,
            "applied_rate_changes" to array(s.appliedRateChanges) { obj("at_ms" to it.atMs, "rate_bytes_per_second" to it.rateBytesPerSecond) },
            "flows" to array(s.flows) { f -> obj("flow_index" to f.flowIndex, "outcome" to f.outcome,
                "accepted_bytes" to f.acceptedBytes, "written_bytes" to f.writtenBytes,
                "undelivered_accepted_bytes" to f.undeliveredAcceptedBytes,
                "kernel_accepted_delivery_unconfirmed_bytes" to if (sockets[f.flowIndex].receiptStatus == "COMPLETE") 0 else f.writtenBytes,
                "accepted_sha256" to f.acceptedSha256, "written_sha256" to f.writtenSha256,
                "max_queued_bytes" to f.maxQueuedBytes, "source_eagain_count" to f.sourceEagainCount,
                "write_eagain_count" to f.writeEagainCount, "partial_write_count" to f.partialWriteCount,
                "read_backpressure_count" to f.readBackpressureCount, "read_resume_count" to f.readResumeCount,
                "longest_no_progress_ms" to f.longestNoProgressMs, "first_write_at_ms" to f.firstWriteAtMs,
                "completed_at_ms" to f.completedAtMs, "shutdown_output_status" to f.shutdownOutputStatus,
                "failure_code" to f.failureCode) }))
    }
}
