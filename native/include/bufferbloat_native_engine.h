#ifndef BUFFERBLOAT_NATIVE_ENGINE_H_
#define BUFFERBLOAT_NATIVE_ENGINE_H_

/*
 * Stable C boundary between Android/Kotlin and the future native netstack.
 *
 * The checked-in implementation is a deliberately unavailable stub.  It does
 * not parse, relay, queue, inspect, duplicate, or close packets/TUN file
 * descriptors.  A later gVisor-backed adapter must preserve this ABI or bump
 * BB_NATIVE_ENGINE_ABI_VERSION.
 */

#include <stdint.h>

#if defined(__GNUC__) || defined(__clang__)
#define BB_NATIVE_API __attribute__((visibility("default")))
#else
#define BB_NATIVE_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define BB_NATIVE_ENGINE_ABI_VERSION 1u

typedef struct BbNativeEngine BbNativeEngine;

typedef enum BbNativeStatus {
    BB_NATIVE_STATUS_OK = 0,
    /* A successful poll with no queued health event. */
    BB_NATIVE_STATUS_NO_EVENT = 1,

    BB_NATIVE_STATUS_INVALID_ARGUMENT = -1,
    BB_NATIVE_STATUS_INVALID_STATE = -2,
    /* No real packet engine is linked into this build. */
    BB_NATIVE_STATUS_UNAVAILABLE = -3,
    BB_NATIVE_STATUS_NOT_IMPLEMENTED = -4,
    BB_NATIVE_STATUS_INTERNAL_ERROR = -5,
} BbNativeStatus;

typedef enum BbNativeEngineState {
    BB_NATIVE_ENGINE_STATE_CREATED = 0,
    BB_NATIVE_ENGINE_STATE_STARTING = 1,
    BB_NATIVE_ENGINE_STATE_RUNNING = 2,
    BB_NATIVE_ENGINE_STATE_STOPPING = 3,
    BB_NATIVE_ENGINE_STATE_STOPPED = 4,
    BB_NATIVE_ENGINE_STATE_FAILED = 5,
    BB_NATIVE_ENGINE_STATE_UNAVAILABLE = 6,
} BbNativeEngineState;

typedef enum BbNativeEngineEventType {
    BB_NATIVE_ENGINE_EVENT_NONE = 0,
    BB_NATIVE_ENGINE_EVENT_ENGINE_UNAVAILABLE = 1,
    BB_NATIVE_ENGINE_EVENT_HEALTH_FAILURE = 2,
} BbNativeEngineEventType;

typedef enum BbNativeEngineDetailCode {
    BB_NATIVE_ENGINE_DETAIL_NONE = 0,
    BB_NATIVE_ENGINE_DETAIL_STUB_BUILD = 1,
} BbNativeEngineDetailCode;

/*
 * Configuration passed atomically to start/update. Rates are bytes/second;
 * zero represents an unset rate, not a packet-processing instruction.
 * Reserved fields must be zero until a later ABI version assigns them.
 */
typedef struct BbNativeEngineConfig {
    uint32_t abi_version;
    uint32_t flags;
    int64_t egress_rate_bytes_per_second;
    int64_t ingress_rate_bytes_per_second;
    uint32_t codel_target_ms;
    uint32_t codel_interval_ms;
    uint32_t fair_queue_quantum_bytes;
    uint32_t reserved;
} BbNativeEngineConfig;

/* A pull-based health snapshot; no callback crosses the JNI lifetime boundary. */
typedef struct BbNativeEngineHealth {
    uint32_t abi_version;
    uint32_t state;
    int32_t last_status;
    uint32_t last_event_type;
    uint64_t generation;
    uint64_t updated_at_monotonic_ms;
    uint32_t detail_code;
    uint32_t reserved;
} BbNativeEngineHealth;

/* Aggregate counters from a real engine. The stub always returns zero traffic. */
typedef struct BbNativeEngineMetrics {
    uint32_t abi_version;
    uint32_t state;
    uint64_t generation;
    uint64_t sampled_at_monotonic_ms;
    uint64_t tun_packets_in;
    uint64_t tun_packets_out;
    uint64_t tun_bytes_in;
    uint64_t tun_bytes_out;
    uint64_t active_flows;
    uint64_t queued_bytes;
    uint64_t aqm_drops;
    uint64_t udp_packets_paced;
} BbNativeEngineMetrics;

/* Per-flow counters. Flow identifiers are opaque and local to one generation. */
typedef struct BbNativeFlowMetrics {
    uint32_t abi_version;
    uint32_t state;
    uint64_t generation;
    uint64_t flow_id;
    uint64_t bytes_in;
    uint64_t bytes_out;
    uint64_t queued_bytes;
    uint64_t aqm_drops;
    uint32_t protocol;
    uint32_t reserved;
} BbNativeFlowMetrics;

typedef struct BbNativeEngineEvent {
    uint32_t abi_version;
    uint32_t type;
    int32_t status;
    uint32_t detail_code;
    uint64_t generation;
    uint64_t occurred_at_monotonic_ms;
} BbNativeEngineEvent;

/*
 * Returns non-zero only when a real, verified engine is linked. Call this
 * before establishing/capturing a VPN route so unavailable builds can fail
 * closed without disrupting traffic.
 */
BB_NATIVE_API int32_t bb_native_engine_is_available(void);

/* Static, non-user-specific build description for diagnostics. */
BB_NATIVE_API const char* bb_native_engine_build_info(void);

BB_NATIVE_API BbNativeEngine* bb_native_engine_create(void);
BB_NATIVE_API void bb_native_engine_destroy(BbNativeEngine* engine);

/*
 * Starts the engine over a BORROWED TUN descriptor. The caller retains
 * ownership. A future successful implementation must duplicate the FD before
 * returning and must never close the caller's descriptor.
 */
BB_NATIVE_API int32_t bb_native_engine_start(
    BbNativeEngine* engine,
    int tun_fd,
    const BbNativeEngineConfig* config);

BB_NATIVE_API int32_t bb_native_engine_update_config(
    BbNativeEngine* engine,
    const BbNativeEngineConfig* config);

/* Stops native work. It is safe to call after a failed start. */
BB_NATIVE_API int32_t bb_native_engine_stop(BbNativeEngine* engine);

BB_NATIVE_API int32_t bb_native_engine_get_health(
    BbNativeEngine* engine,
    BbNativeEngineHealth* out_health);

BB_NATIVE_API int32_t bb_native_engine_poll_event(
    BbNativeEngine* engine,
    BbNativeEngineEvent* out_event);

BB_NATIVE_API int32_t bb_native_engine_get_metrics(
    BbNativeEngine* engine,
    BbNativeEngineMetrics* out_metrics);

BB_NATIVE_API int32_t bb_native_engine_get_flow_metrics(
    BbNativeEngine* engine,
    uint64_t flow_id,
    BbNativeFlowMetrics* out_metrics);

BB_NATIVE_API const char* bb_native_engine_status_message(int32_t status);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // BUFFERBLOAT_NATIVE_ENGINE_H_
