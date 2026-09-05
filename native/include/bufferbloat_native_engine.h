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

#define BB_NATIVE_ENGINE_ABI_VERSION 2u

typedef struct BbNativeEngine BbNativeEngine;

/* Features a real engine must declare before Android routes traffic to it. */
typedef enum BbNativeEngineFeature {
    BB_NATIVE_FEATURE_IPV4_TCP = 1ull << 0,
    BB_NATIVE_FEATURE_PROTECTED_SOCKETS = 1ull << 1,
    BB_NATIVE_FEATURE_SAFE_STOP = 1ull << 2,
    BB_NATIVE_FEATURE_HEALTH_EVENTS = 1ull << 3,
    BB_NATIVE_FEATURE_FLOW_METRICS = 1ull << 4,
    /* The IPv4 default route also captures UDP/QUIC. Forward it safely even
     * though download-side UDP/QUIC shaping is deliberately out of scope. */
    BB_NATIVE_FEATURE_IPV4_UDP_FORWARDING = 1ull << 5,
} BbNativeEngineFeature;

#define BB_NATIVE_REQUIRED_FEATURES \
    (BB_NATIVE_FEATURE_IPV4_TCP | BB_NATIVE_FEATURE_PROTECTED_SOCKETS | \
     BB_NATIVE_FEATURE_SAFE_STOP | BB_NATIVE_FEATURE_HEALTH_EVENTS | \
     BB_NATIVE_FEATURE_FLOW_METRICS | BB_NATIVE_FEATURE_IPV4_UDP_FORWARDING)

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
    BB_NATIVE_ENGINE_EVENT_PROTECT_SOCKET_FAILED = 3,
    BB_NATIVE_ENGINE_EVENT_EVENT_OVERFLOW = 4,
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
    uint32_t struct_size;
    uint32_t flags;
    int64_t egress_rate_bytes_per_second;
    int64_t ingress_rate_bytes_per_second;
    uint32_t codel_target_ms;
    uint32_t codel_interval_ms;
    uint32_t fair_queue_quantum_bytes;
    uint32_t fair_queue_buckets;
    uint32_t headroom_per_mille;
    uint32_t burst_per_mille;
    uint32_t reserved;
} BbNativeEngineConfig;

/*
 * Native code must invoke this immediately after socket() and before
 * bind/connect/send. A false result means it must close that socket, emit a
 * typed failure, and never fall back to an unprotected socket. The TUN FD is
 * never passed to this callback.
 */
typedef int32_t (*BbNativeProtectSocketFn)(void* context, int socket_fd);

typedef struct BbNativeSocketProtector {
    uint32_t abi_version;
    uint32_t struct_size;
    BbNativeProtectSocketFn protect_socket;
    void* context;
} BbNativeSocketProtector;

typedef struct BbNativeEngineStartParams {
    uint32_t abi_version;
    uint32_t struct_size;
    int32_t tun_fd;
    uint32_t reserved;
    const BbNativeEngineConfig* config;
    const BbNativeSocketProtector* socket_protector;
} BbNativeEngineStartParams;

/*
 * Pointer-lifetime contract for bb_native_engine_start:
 *
 * - The caller owns the start-parameter, configuration, and socket-protector
 *   records. The engine must synchronously validate and copy every field it
 *   needs before start returns, on both success and failure. It must never
 *   retain any of those record pointers.
 * - It may retain only the copied protect function and its opaque context;
 *   the bridge keeps that context valid until stop returns OK.
 * - The TUN descriptor is borrowed. On a successful start, the engine must
 *   duplicate it before returning and must never close the caller's FD. If a
 *   start attempt fails after duplicating it or creating workers, stop must
 *   close/join them before it reports OK.
 */

/* A pull-based health snapshot; no callback crosses the JNI lifetime boundary. */
typedef struct BbNativeEngineHealth {
    uint32_t abi_version;
    uint32_t struct_size;
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
    uint32_t struct_size;
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
    uint32_t struct_size;
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
    uint32_t struct_size;
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

/* Version and activation mask compiled into this native ABI implementation. */
BB_NATIVE_API uint32_t bb_native_engine_abi_version(void);
BB_NATIVE_API uint64_t bb_native_engine_required_feature_bits(void);

/* A bitset of BbNativeEngineFeature values; the checked-in stub returns zero. */
BB_NATIVE_API uint64_t bb_native_engine_feature_bits(void);

/* Static, non-user-specific build description for diagnostics. */
BB_NATIVE_API const char* bb_native_engine_build_info(void);

BB_NATIVE_API BbNativeEngine* bb_native_engine_create(void);
BB_NATIVE_API void bb_native_engine_destroy(BbNativeEngine* engine);

/* Starts over a BORROWED TUN descriptor; see the pointer-lifetime contract. */
BB_NATIVE_API int32_t bb_native_engine_start(
    BbNativeEngine* engine,
    const BbNativeEngineStartParams* params);

/* The engine must copy config before this call returns; it must not retain it. */
BB_NATIVE_API int32_t bb_native_engine_update_config(
    BbNativeEngine* engine,
    const BbNativeEngineConfig* config);

/*
 * Stops native work and is safe after any failed/partial start. Returning OK
 * is a synchronous quiescence guarantee: all workers have joined, all
 * duplicated descriptors are closed, and no callback can run again. A
 * non-OK result means the caller must retain the engine and callback context
 * and must not destroy either. A production engine must surface that failure
 * through its health/event interface and must never claim SAFE_STOP until this
 * rule is proven.
 */
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
