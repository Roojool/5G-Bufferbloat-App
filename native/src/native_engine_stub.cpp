#include "bufferbloat_native_engine.h"

#include <chrono>
#include <cstring>
#include <mutex>
#include <new>

namespace {

uint64_t monotonicMillis() {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(now).count());
}

bool isValidConfig(const BbNativeEngineConfig* config) {
    if (config == nullptr || config->abi_version != BB_NATIVE_ENGINE_ABI_VERSION) {
        return false;
    }

    if (config->egress_rate_bytes_per_second < 0 ||
        config->ingress_rate_bytes_per_second < 0 ||
        config->codel_target_ms == 0 ||
        config->codel_interval_ms == 0 ||
        config->fair_queue_quantum_bytes == 0 ||
        config->reserved != 0) {
        return false;
    }

    return true;
}

void initializeHealth(BbNativeEngineHealth* health) {
    std::memset(health, 0, sizeof(*health));
    health->abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
}

void initializeMetrics(BbNativeEngineMetrics* metrics) {
    std::memset(metrics, 0, sizeof(*metrics));
    metrics->abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
}

void initializeFlowMetrics(BbNativeFlowMetrics* metrics, uint64_t flow_id) {
    std::memset(metrics, 0, sizeof(*metrics));
    metrics->abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
    metrics->flow_id = flow_id;
}

void initializeEvent(BbNativeEngineEvent* event) {
    std::memset(event, 0, sizeof(*event));
    event->abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
}

}  // namespace

struct BbNativeEngine {
    std::mutex mutex;
    BbNativeEngineState state = BB_NATIVE_ENGINE_STATE_CREATED;
    int32_t last_status = BB_NATIVE_STATUS_OK;
    BbNativeEngineEventType last_event = BB_NATIVE_ENGINE_EVENT_NONE;
    uint64_t generation = 0;
    uint64_t updated_at_monotonic_ms = monotonicMillis();
    bool unavailable_event_pending = false;
};

extern "C" {

int32_t bb_native_engine_is_available(void) {
    return 0;
}

const char* bb_native_engine_build_info(void) {
    return "stub-unavailable; no gVisor or tun2socks packet engine is linked";
}

BbNativeEngine* bb_native_engine_create(void) {
    return new (std::nothrow) BbNativeEngine();
}

void bb_native_engine_destroy(BbNativeEngine* engine) {
    delete engine;
}

int32_t bb_native_engine_start(
    BbNativeEngine* engine,
    int tun_fd,
    const BbNativeEngineConfig* config) {
    if (engine == nullptr || tun_fd < 0 || !isValidConfig(config)) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }

    std::lock_guard<std::mutex> lock(engine->mutex);
    // Do not read, duplicate, retain, or close tun_fd in this stub.
    engine->state = BB_NATIVE_ENGINE_STATE_UNAVAILABLE;
    engine->last_status = BB_NATIVE_STATUS_UNAVAILABLE;
    engine->last_event = BB_NATIVE_ENGINE_EVENT_ENGINE_UNAVAILABLE;
    ++engine->generation;
    engine->updated_at_monotonic_ms = monotonicMillis();
    engine->unavailable_event_pending = true;
    return BB_NATIVE_STATUS_UNAVAILABLE;
}

int32_t bb_native_engine_update_config(
    BbNativeEngine* engine,
    const BbNativeEngineConfig* config) {
    if (engine == nullptr || !isValidConfig(config)) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }

    std::lock_guard<std::mutex> lock(engine->mutex);
    // A configuration cannot be applied while no packet engine exists.
    engine->state = BB_NATIVE_ENGINE_STATE_UNAVAILABLE;
    engine->last_status = BB_NATIVE_STATUS_UNAVAILABLE;
    engine->last_event = BB_NATIVE_ENGINE_EVENT_ENGINE_UNAVAILABLE;
    engine->updated_at_monotonic_ms = monotonicMillis();
    engine->unavailable_event_pending = true;
    return BB_NATIVE_STATUS_UNAVAILABLE;
}

int32_t bb_native_engine_stop(BbNativeEngine* engine) {
    if (engine == nullptr) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }

    std::lock_guard<std::mutex> lock(engine->mutex);
    // There is no worker or descriptor to release in the stub.
    engine->state = BB_NATIVE_ENGINE_STATE_STOPPED;
    engine->last_status = BB_NATIVE_STATUS_OK;
    engine->last_event = BB_NATIVE_ENGINE_EVENT_NONE;
    ++engine->generation;
    engine->updated_at_monotonic_ms = monotonicMillis();
    engine->unavailable_event_pending = false;
    return BB_NATIVE_STATUS_OK;
}

int32_t bb_native_engine_get_health(
    BbNativeEngine* engine,
    BbNativeEngineHealth* out_health) {
    if (engine == nullptr || out_health == nullptr) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }

    std::lock_guard<std::mutex> lock(engine->mutex);
    initializeHealth(out_health);
    out_health->state = static_cast<uint32_t>(engine->state);
    out_health->last_status = engine->last_status;
    out_health->last_event_type = static_cast<uint32_t>(engine->last_event);
    out_health->generation = engine->generation;
    out_health->updated_at_monotonic_ms = engine->updated_at_monotonic_ms;
    out_health->detail_code = BB_NATIVE_ENGINE_DETAIL_STUB_BUILD;
    return BB_NATIVE_STATUS_OK;
}

int32_t bb_native_engine_poll_event(
    BbNativeEngine* engine,
    BbNativeEngineEvent* out_event) {
    if (engine == nullptr || out_event == nullptr) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }

    std::lock_guard<std::mutex> lock(engine->mutex);
    initializeEvent(out_event);
    if (!engine->unavailable_event_pending) {
        return BB_NATIVE_STATUS_NO_EVENT;
    }

    out_event->type = BB_NATIVE_ENGINE_EVENT_ENGINE_UNAVAILABLE;
    out_event->status = BB_NATIVE_STATUS_UNAVAILABLE;
    out_event->detail_code = BB_NATIVE_ENGINE_DETAIL_STUB_BUILD;
    out_event->generation = engine->generation;
    out_event->occurred_at_monotonic_ms = engine->updated_at_monotonic_ms;
    engine->unavailable_event_pending = false;
    return BB_NATIVE_STATUS_OK;
}

int32_t bb_native_engine_get_metrics(
    BbNativeEngine* engine,
    BbNativeEngineMetrics* out_metrics) {
    if (engine == nullptr || out_metrics == nullptr) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }

    std::lock_guard<std::mutex> lock(engine->mutex);
    initializeMetrics(out_metrics);
    out_metrics->state = static_cast<uint32_t>(engine->state);
    out_metrics->generation = engine->generation;
    out_metrics->sampled_at_monotonic_ms = monotonicMillis();
    return BB_NATIVE_STATUS_OK;
}

int32_t bb_native_engine_get_flow_metrics(
    BbNativeEngine* engine,
    uint64_t flow_id,
    BbNativeFlowMetrics* out_metrics) {
    if (engine == nullptr || out_metrics == nullptr) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }

    std::lock_guard<std::mutex> lock(engine->mutex);
    initializeFlowMetrics(out_metrics, flow_id);
    out_metrics->state = static_cast<uint32_t>(engine->state);
    out_metrics->generation = engine->generation;
    return BB_NATIVE_STATUS_UNAVAILABLE;
}

const char* bb_native_engine_status_message(int32_t status) {
    switch (status) {
        case BB_NATIVE_STATUS_OK:
            return "ok";
        case BB_NATIVE_STATUS_NO_EVENT:
            return "no queued event";
        case BB_NATIVE_STATUS_INVALID_ARGUMENT:
            return "invalid argument";
        case BB_NATIVE_STATUS_INVALID_STATE:
            return "invalid state";
        case BB_NATIVE_STATUS_UNAVAILABLE:
            return "native packet engine is unavailable in this build";
        case BB_NATIVE_STATUS_NOT_IMPLEMENTED:
            return "not implemented";
        case BB_NATIVE_STATUS_INTERNAL_ERROR:
            return "internal error";
        default:
            return "unknown native engine status";
    }
}

}  // extern "C"
