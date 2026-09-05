#include <jni.h>

#include "bufferbloat_native_engine.h"

#include <cstdint>
#include <limits>
#include <mutex>
#include <new>
#include <unordered_map>

namespace {

constexpr jint kDefaultFairQueueQuantumBytes = 1514;

constexpr jsize kHealthArraySize = 6;
constexpr jsize kMetricsArraySize = 12;
constexpr jsize kEventArraySize = 6;
constexpr jsize kFlowMetricsArraySize = 10;

JavaVM* g_java_vm = nullptr;
std::mutex g_handles_mutex;

struct SocketProtectorContext {
    JavaVM* java_vm;
    jobject protector;
    jmethodID protect_socket_method;
};

/*
 * Kotlin receives a monotonic token, never an engine address. This avoids an
 * ABA bug where allocator reuse could make an old Java Session operate on a
 * newly-created engine at the same address. The global lock intentionally
 * serializes bridge calls with destroy; a real engine remains responsible for
 * its own worker synchronization and the stop/join contract in the C header.
 */
struct EngineEntry {
    BbNativeEngine* engine = nullptr;
    SocketProtectorContext* protector_context = nullptr;
    bool start_attempted = false;
    bool stop_confirmed = false;
};

std::unordered_map<jlong, EngineEntry> g_live_engines;
jlong g_next_handle = 1;
bool g_shutdown_quarantined = false;

jlong nextOpaqueHandleLocked() {
    const jlong first_candidate = g_next_handle;
    do {
        const jlong candidate = g_next_handle;
        g_next_handle = candidate == std::numeric_limits<jlong>::max()
            ? 1
            : candidate + 1;
        if (g_live_engines.find(candidate) == g_live_engines.end()) {
            return candidate;
        }
    } while (g_next_handle != first_candidate);
    return 0;
}

template <typename Operation>
int32_t withLiveEngine(jlong handle, Operation operation) {
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    const auto iterator = g_live_engines.find(handle);
    if (handle == 0 || iterator == g_live_engines.end() || iterator->second.engine == nullptr) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }
    try {
        return operation(iterator->second);
    } catch (...) {
        return BB_NATIVE_STATUS_INTERNAL_ERROR;
    }
}

int32_t stopEntryLocked(EngineEntry& entry) {
    if (entry.engine == nullptr) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }
    if (entry.stop_confirmed) {
        return BB_NATIVE_STATUS_OK;
    }
    try {
        const int32_t status = bb_native_engine_stop(entry.engine);
        if (status == BB_NATIVE_STATUS_OK) {
            entry.stop_confirmed = true;
        } else {
            // Do not activate a new VPN generation in this process once an
            // older engine has failed to prove it is quiescent.
            g_shutdown_quarantined = true;
        }
        return status;
    } catch (...) {
        g_shutdown_quarantined = true;
        return BB_NATIVE_STATUS_INTERNAL_ERROR;
    }
}

bool validJvmConfig(jlong egress_rate_bytes_per_second,
                    jlong ingress_rate_bytes_per_second,
                    jint codel_target_ms,
                    jint codel_interval_ms,
                    jint fair_queue_buckets,
                    jint headroom_per_mille,
                    jint burst_per_mille) {
    return egress_rate_bytes_per_second >= 0 &&
        ingress_rate_bytes_per_second >= 0 &&
        codel_target_ms > 0 &&
        codel_interval_ms > 0 &&
        fair_queue_buckets > 0 &&
        headroom_per_mille >= 1 && headroom_per_mille <= 1000 &&
        burst_per_mille >= 1 && burst_per_mille <= 1000;
}

BbNativeEngineConfig configFromJvm(jlong egress_rate_bytes_per_second,
                                   jlong ingress_rate_bytes_per_second,
                                   jint codel_target_ms,
                                   jint codel_interval_ms,
                                   jint fair_queue_buckets,
                                   jint headroom_per_mille,
                                   jint burst_per_mille,
                                   jint flags) {
    BbNativeEngineConfig config{};
    config.abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
    config.struct_size = sizeof(config);
    config.flags = static_cast<uint32_t>(flags);
    config.egress_rate_bytes_per_second = static_cast<int64_t>(egress_rate_bytes_per_second);
    config.ingress_rate_bytes_per_second = static_cast<int64_t>(ingress_rate_bytes_per_second);
    config.codel_target_ms = static_cast<uint32_t>(codel_target_ms);
    config.codel_interval_ms = static_cast<uint32_t>(codel_interval_ms);
    config.fair_queue_quantum_bytes = kDefaultFairQueueQuantumBytes;
    config.fair_queue_buckets = static_cast<uint32_t>(fair_queue_buckets);
    config.headroom_per_mille = static_cast<uint32_t>(headroom_per_mille);
    config.burst_per_mille = static_cast<uint32_t>(burst_per_mille);
    return config;
}

SocketProtectorContext* createSocketProtector(JNIEnv* env, jobject protector) {
    if (env == nullptr || protector == nullptr || g_java_vm == nullptr) {
        return nullptr;
    }

    jclass protector_class = env->GetObjectClass(protector);
    if (protector_class == nullptr) {
        return nullptr;
    }
    jmethodID method = env->GetMethodID(protector_class, "protectSocket", "(I)Z");
    env->DeleteLocalRef(protector_class);
    if (method == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }

    jobject global_reference = env->NewGlobalRef(protector);
    if (global_reference == nullptr) {
        return nullptr;
    }
    auto* const context = new (std::nothrow) SocketProtectorContext{
        g_java_vm,
        global_reference,
        method,
    };
    if (context == nullptr) {
        env->DeleteGlobalRef(global_reference);
    }
    return context;
}

void destroySocketProtector(JNIEnv* env, SocketProtectorContext* context) {
    if (context == nullptr) {
        return;
    }
    if (env != nullptr && context->protector != nullptr) {
        env->DeleteGlobalRef(context->protector);
    }
    delete context;
}

int32_t protectSocketCallback(void* opaque_context, int socket_fd) {
    auto* const context = static_cast<SocketProtectorContext*>(opaque_context);
    if (context == nullptr || context->java_vm == nullptr ||
        context->protector == nullptr || context->protect_socket_method == nullptr ||
        socket_fd < 0) {
        return 0;
    }

    JNIEnv* env = nullptr;
    bool attached_here = false;
    const jint get_env_result = context->java_vm->GetEnv(
        reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if (context->java_vm->AttachCurrentThread(
                &env, nullptr) != JNI_OK) {
            return 0;
        }
        attached_here = true;
    } else if (get_env_result != JNI_OK || env == nullptr) {
        return 0;
    }

    const jboolean protected_socket = env->CallBooleanMethod(
        context->protector,
        context->protect_socket_method,
        static_cast<jint>(socket_fd));
    const bool threw = env->ExceptionCheck();
    if (threw) {
        // Do not propagate arbitrary Java exception text to native diagnostics.
        env->ExceptionClear();
    }
    if (attached_here) {
        context->java_vm->DetachCurrentThread();
    }
    return (!threw && protected_socket == JNI_TRUE) ? 1 : 0;
}

jlongArray newHealthArray(JNIEnv* env, int32_t status, const BbNativeEngineHealth& health) {
    const jlong values[kHealthArraySize] = {
        static_cast<jlong>(status),
        static_cast<jlong>(health.state),
        static_cast<jlong>(health.last_status),
        static_cast<jlong>(health.last_event_type),
        static_cast<jlong>(health.detail_code),
        static_cast<jlong>(health.generation),
    };
    jlongArray result = env->NewLongArray(kHealthArraySize);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, kHealthArraySize, values);
    }
    return result;
}

jlongArray newMetricsArray(JNIEnv* env, int32_t status, const BbNativeEngineMetrics& metrics) {
    const jlong values[kMetricsArraySize] = {
        static_cast<jlong>(status),
        static_cast<jlong>(metrics.state),
        static_cast<jlong>(metrics.generation),
        static_cast<jlong>(metrics.sampled_at_monotonic_ms),
        static_cast<jlong>(metrics.tun_packets_in),
        static_cast<jlong>(metrics.tun_packets_out),
        static_cast<jlong>(metrics.tun_bytes_in),
        static_cast<jlong>(metrics.tun_bytes_out),
        static_cast<jlong>(metrics.active_flows),
        static_cast<jlong>(metrics.queued_bytes),
        static_cast<jlong>(metrics.aqm_drops),
        static_cast<jlong>(metrics.udp_packets_paced),
    };
    jlongArray result = env->NewLongArray(kMetricsArraySize);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, kMetricsArraySize, values);
    }
    return result;
}

jlongArray newEventArray(JNIEnv* env, int32_t status, const BbNativeEngineEvent& event) {
    const jlong values[kEventArraySize] = {
        static_cast<jlong>(status),
        static_cast<jlong>(event.type),
        static_cast<jlong>(event.status),
        static_cast<jlong>(event.detail_code),
        static_cast<jlong>(event.generation),
        static_cast<jlong>(event.occurred_at_monotonic_ms),
    };
    jlongArray result = env->NewLongArray(kEventArraySize);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, kEventArraySize, values);
    }
    return result;
}

jlongArray newFlowMetricsArray(JNIEnv* env, int32_t status, const BbNativeFlowMetrics& metrics) {
    const jlong values[kFlowMetricsArraySize] = {
        static_cast<jlong>(status),
        static_cast<jlong>(metrics.state),
        static_cast<jlong>(metrics.generation),
        static_cast<jlong>(metrics.flow_id),
        static_cast<jlong>(metrics.bytes_in),
        static_cast<jlong>(metrics.bytes_out),
        static_cast<jlong>(metrics.queued_bytes),
        static_cast<jlong>(metrics.aqm_drops),
        static_cast<jlong>(metrics.protocol),
        0,
    };
    jlongArray result = env->NewLongArray(kFlowMetricsArraySize);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, kFlowMetricsArraySize, values);
    }
    return result;
}

}  // namespace

/* JNI symbols for NativeEngineBridge. The checked-in native implementation is
 * unavailable, but the Kotlin adapter already invokes this ABI for capability,
 * lifecycle, health, event, and metric operations. */
extern "C" JNIEXPORT jint JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeIsAvailable(
    JNIEnv*, jclass) {
    return static_cast<jint>(bb_native_engine_is_available());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeAbiVersion(
    JNIEnv*, jclass) {
    return static_cast<jint>(bb_native_engine_abi_version());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeRequiredFeatureBits(
    JNIEnv*, jclass) {
    return static_cast<jlong>(bb_native_engine_required_feature_bits());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeHasQuarantinedEngine(
    JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    return g_shutdown_quarantined ? 1 : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeFeatureBits(
    JNIEnv*, jclass) {
    return static_cast<jlong>(bb_native_engine_feature_bits());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeBuildInfo(
    JNIEnv* env, jclass) {
    return env->NewStringUTF(bb_native_engine_build_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeCreate(
    JNIEnv*, jclass) {
    BbNativeEngine* const engine = bb_native_engine_create();
    if (engine == nullptr) {
        return 0;
    }

    jlong handle = 0;
    try {
        std::lock_guard<std::mutex> lock(g_handles_mutex);
        handle = nextOpaqueHandleLocked();
        if (handle != 0) {
            const auto inserted = g_live_engines.emplace(
                handle,
                EngineEntry{engine, nullptr, false, false});
            if (!inserted.second) {
                handle = 0;
            }
        }
    } catch (...) {
        handle = 0;
    }

    if (handle == 0) {
        // Do not expose an unregistered native allocation to Kotlin.
        bb_native_engine_destroy(engine);
    }
    return handle;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeDestroy(
    JNIEnv* env, jclass, jlong handle) {
    EngineEntry entry{};
    {
        std::lock_guard<std::mutex> lock(g_handles_mutex);
        const auto iterator = g_live_engines.find(handle);
        if (handle == 0 || iterator == g_live_engines.end()) {
            return BB_NATIVE_STATUS_INVALID_ARGUMENT;
        }
        const int32_t stop_status = stopEntryLocked(iterator->second);
        if (stop_status != BB_NATIVE_STATUS_OK) {
            // A failed stop can still own worker/callback state. Keep this
            // opaque handle quarantined rather than freeing it underneath a
            // future asynchronous engine.
            return stop_status;
        }
        entry = iterator->second;
        g_live_engines.erase(iterator);
    }

    try {
        bb_native_engine_destroy(entry.engine);
    } catch (...) {
        // The stop contract has already made the callback quiescent, so it is
        // safe to release bridge-owned state even if a broken implementation
        // throws while destroying itself.
        destroySocketProtector(env, entry.protector_context);
        return BB_NATIVE_STATUS_INTERNAL_ERROR;
    }
    destroySocketProtector(env, entry.protector_context);
    return BB_NATIVE_STATUS_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeStart(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint tun_fd,
    jlong egress_rate_bytes_per_second,
    jlong ingress_rate_bytes_per_second,
    jint codel_target_ms,
    jint codel_interval_ms,
    jint fair_queue_buckets,
    jint headroom_per_mille,
    jint burst_per_mille,
    jint flags,
    jobject socket_protector) {
    if (!validJvmConfig(egress_rate_bytes_per_second, ingress_rate_bytes_per_second,
                        codel_target_ms, codel_interval_ms, fair_queue_buckets,
                        headroom_per_mille, burst_per_mille)) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }
    SocketProtectorContext* const protector_context = createSocketProtector(env, socket_protector);
    if (protector_context == nullptr) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }
    const BbNativeEngineConfig config = configFromJvm(
        egress_rate_bytes_per_second, ingress_rate_bytes_per_second,
        codel_target_ms, codel_interval_ms, fair_queue_buckets,
        headroom_per_mille, burst_per_mille, flags);
    const BbNativeSocketProtector protector = {
        BB_NATIVE_ENGINE_ABI_VERSION,
        sizeof(BbNativeSocketProtector),
        protectSocketCallback,
        protector_context,
    };
    const BbNativeEngineStartParams params = {
        BB_NATIVE_ENGINE_ABI_VERSION,
        sizeof(BbNativeEngineStartParams),
        tun_fd,
        0,
        &config,
        &protector,
    };
    bool context_installed = false;
    const int32_t status = withLiveEngine(handle, [&params, protector_context, &context_installed](EngineEntry& entry) {
        if (entry.start_attempted || entry.stop_confirmed || entry.protector_context != nullptr) {
            return static_cast<int32_t>(BB_NATIVE_STATUS_INVALID_STATE);
        }
        // Install the callback context *before* calling start. A failed start
        // may have created workers and retained the copied callback, so only
        // stop/join is allowed to release this global Java reference.
        entry.start_attempted = true;
        entry.protector_context = protector_context;
        context_installed = true;
        try {
            return bb_native_engine_start(entry.engine, &params);
        } catch (...) {
            return static_cast<int32_t>(BB_NATIVE_STATUS_INTERNAL_ERROR);
        }
    });
    if (!context_installed) {
        destroySocketProtector(env, protector_context);
    }
    return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeUpdateConfig(
    JNIEnv*,
    jclass,
    jlong handle,
    jlong egress_rate_bytes_per_second,
    jlong ingress_rate_bytes_per_second,
    jint codel_target_ms,
    jint codel_interval_ms,
    jint fair_queue_buckets,
    jint headroom_per_mille,
    jint burst_per_mille,
    jint flags) {
    if (!validJvmConfig(egress_rate_bytes_per_second, ingress_rate_bytes_per_second,
                        codel_target_ms, codel_interval_ms, fair_queue_buckets,
                        headroom_per_mille, burst_per_mille)) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }
    const BbNativeEngineConfig config = configFromJvm(
        egress_rate_bytes_per_second, ingress_rate_bytes_per_second,
        codel_target_ms, codel_interval_ms, fair_queue_buckets,
        headroom_per_mille, burst_per_mille, flags);
    return withLiveEngine(handle, [&config](EngineEntry& entry) {
        if (!entry.start_attempted || entry.stop_confirmed) {
            return static_cast<int32_t>(BB_NATIVE_STATUS_INVALID_STATE);
        }
        return bb_native_engine_update_config(entry.engine, &config);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeStop(
    JNIEnv* env, jclass, jlong handle) {
    SocketProtectorContext* protector_context = nullptr;
    const int32_t status = withLiveEngine(handle, [&protector_context](EngineEntry& entry) {
        const int32_t stop_status = stopEntryLocked(entry);
        if (stop_status == BB_NATIVE_STATUS_OK) {
            protector_context = entry.protector_context;
            entry.protector_context = nullptr;
        }
        return stop_status;
    });
    if (status == BB_NATIVE_STATUS_OK) {
        destroySocketProtector(env, protector_context);
    }
    return status;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeGetHealth(
    JNIEnv* env, jclass, jlong handle) {
    BbNativeEngineHealth health{};
    health.abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
    health.struct_size = sizeof(health);
    const int32_t status = withLiveEngine(handle, [&health](EngineEntry& entry) {
        return bb_native_engine_get_health(entry.engine, &health);
    });
    return newHealthArray(env, status, health);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeGetMetrics(
    JNIEnv* env, jclass, jlong handle) {
    BbNativeEngineMetrics metrics{};
    metrics.abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
    metrics.struct_size = sizeof(metrics);
    const int32_t status = withLiveEngine(handle, [&metrics](EngineEntry& entry) {
        return bb_native_engine_get_metrics(entry.engine, &metrics);
    });
    return newMetricsArray(env, status, metrics);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativePollEvent(
    JNIEnv* env, jclass, jlong handle) {
    BbNativeEngineEvent event{};
    event.abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
    event.struct_size = sizeof(event);
    const int32_t status = withLiveEngine(handle, [&event](EngineEntry& entry) {
        return bb_native_engine_poll_event(entry.engine, &event);
    });
    return newEventArray(env, status, event);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeGetFlowMetrics(
    JNIEnv* env, jclass, jlong handle, jlong flow_id) {
    BbNativeFlowMetrics metrics{};
    metrics.abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
    metrics.struct_size = sizeof(metrics);
    const int32_t status = withLiveEngine(handle, [&metrics, flow_id](EngineEntry& entry) {
        return bb_native_engine_get_flow_metrics(
            entry.engine, static_cast<uint64_t>(flow_id), &metrics);
    });
    return newFlowMetricsArray(env, status, metrics);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeStatusMessage(
    JNIEnv* env, jclass, jint status) {
    return env->NewStringUTF(bb_native_engine_status_message(static_cast<int32_t>(status)));
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* java_vm, void*) {
    g_java_vm = java_vm;
    return JNI_VERSION_1_6;
}
