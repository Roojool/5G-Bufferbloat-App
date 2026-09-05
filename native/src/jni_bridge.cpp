#include <jni.h>

#include "bufferbloat_native_engine.h"

#include <cstdint>
#include <mutex>
#include <unordered_map>
#include <unordered_set>

namespace {

constexpr jint kDefaultFairQueueQuantumBytes = 1514;

constexpr jsize kHealthArraySize = 6;
constexpr jsize kMetricsArraySize = 12;
constexpr jsize kEventArraySize = 6;
constexpr jsize kFlowMetricsArraySize = 10;

JavaVM* g_java_vm = nullptr;
std::mutex g_handles_mutex;
std::unordered_set<BbNativeEngine*> g_live_handles;

struct SocketProtectorContext {
    JavaVM* java_vm;
    jobject protector;
    jmethodID protect_socket_method;
};

/* Each context is released only after bb_native_engine_stop has joined all
 * engine workers. That ownership rule prevents native workers from calling a
 * deleted global Java reference during teardown. */
std::unordered_map<BbNativeEngine*, SocketProtectorContext*> g_socket_protectors;

BbNativeEngine* pointerFromHandle(jlong handle) {
    if (handle == 0) {
        return nullptr;
    }
    return reinterpret_cast<BbNativeEngine*>(static_cast<uintptr_t>(handle));
}

template <typename Operation>
int32_t withLiveEngine(jlong handle, Operation operation) {
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    BbNativeEngine* const engine = pointerFromHandle(handle);
    if (engine == nullptr || g_live_handles.find(engine) == g_live_handles.end()) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }
    return operation(engine);
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

    std::lock_guard<std::mutex> lock(g_handles_mutex);
    g_live_handles.insert(engine);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(engine));
}

extern "C" JNIEXPORT void JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeDestroy(
    JNIEnv* env, jclass, jlong handle) {
    BbNativeEngine* const engine = pointerFromHandle(handle);
    if (engine == nullptr) {
        return;
    }

    SocketProtectorContext* protector_context = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_handles_mutex);
        const auto iterator = g_live_handles.find(engine);
        if (iterator == g_live_handles.end()) {
            return;
        }
        g_live_handles.erase(iterator);
        const auto protector_iterator = g_socket_protectors.find(engine);
        if (protector_iterator != g_socket_protectors.end()) {
            protector_context = protector_iterator->second;
            g_socket_protectors.erase(protector_iterator);
        }
    }

    bb_native_engine_destroy(engine);
    destroySocketProtector(env, protector_context);
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
    const int32_t status = withLiveEngine(handle, [&params, protector_context](BbNativeEngine* engine) {
        if (g_socket_protectors.find(engine) != g_socket_protectors.end()) {
            return static_cast<int32_t>(BB_NATIVE_STATUS_INVALID_STATE);
        }
        const int32_t start_status = bb_native_engine_start(engine, &params);
        if (start_status == BB_NATIVE_STATUS_OK) {
            g_socket_protectors.emplace(engine, protector_context);
        }
        return start_status;
    });
    if (status != BB_NATIVE_STATUS_OK) {
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
    return withLiveEngine(handle, [&config](BbNativeEngine* engine) {
        return bb_native_engine_update_config(engine, &config);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeStop(
    JNIEnv*, jclass, jlong handle) {
    return withLiveEngine(handle, [](BbNativeEngine* engine) {
        return bb_native_engine_stop(engine);
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeGetHealth(
    JNIEnv* env, jclass, jlong handle) {
    BbNativeEngineHealth health{};
    health.abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
    health.struct_size = sizeof(health);
    const int32_t status = withLiveEngine(handle, [&health](BbNativeEngine* engine) {
        return bb_native_engine_get_health(engine, &health);
    });
    return newHealthArray(env, status, health);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeGetMetrics(
    JNIEnv* env, jclass, jlong handle) {
    BbNativeEngineMetrics metrics{};
    metrics.abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
    metrics.struct_size = sizeof(metrics);
    const int32_t status = withLiveEngine(handle, [&metrics](BbNativeEngine* engine) {
        return bb_native_engine_get_metrics(engine, &metrics);
    });
    return newMetricsArray(env, status, metrics);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativePollEvent(
    JNIEnv* env, jclass, jlong handle) {
    BbNativeEngineEvent event{};
    event.abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
    event.struct_size = sizeof(event);
    const int32_t status = withLiveEngine(handle, [&event](BbNativeEngine* engine) {
        return bb_native_engine_poll_event(engine, &event);
    });
    return newEventArray(env, status, event);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeGetFlowMetrics(
    JNIEnv* env, jclass, jlong handle, jlong flow_id) {
    BbNativeFlowMetrics metrics{};
    metrics.abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
    metrics.struct_size = sizeof(metrics);
    const int32_t status = withLiveEngine(handle, [&metrics, flow_id](BbNativeEngine* engine) {
        return bb_native_engine_get_flow_metrics(
            engine, static_cast<uint64_t>(flow_id), &metrics);
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
