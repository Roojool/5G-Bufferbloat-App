#include <jni.h>

#include "bufferbloat_native_engine.h"

#include <cstdint>
#include <mutex>
#include <unordered_set>

namespace {

constexpr jint kDefaultFairQueueQuantumBytes = 1514;

constexpr jsize kHealthArraySize = 6;
constexpr jsize kMetricsArraySize = 12;
constexpr jsize kEventArraySize = 6;

std::mutex g_handles_mutex;
std::unordered_set<BbNativeEngine*> g_live_handles;

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
                    jint codel_interval_ms) {
    return egress_rate_bytes_per_second >= 0 &&
        ingress_rate_bytes_per_second >= 0 &&
        codel_target_ms > 0 &&
        codel_interval_ms > 0;
}

BbNativeEngineConfig configFromJvm(jlong egress_rate_bytes_per_second,
                                   jlong ingress_rate_bytes_per_second,
                                   jint codel_target_ms,
                                   jint codel_interval_ms,
                                   jint flags) {
    BbNativeEngineConfig config{};
    config.abi_version = BB_NATIVE_ENGINE_ABI_VERSION;
    config.flags = static_cast<uint32_t>(flags);
    config.egress_rate_bytes_per_second = static_cast<int64_t>(egress_rate_bytes_per_second);
    config.ingress_rate_bytes_per_second = static_cast<int64_t>(ingress_rate_bytes_per_second);
    config.codel_target_ms = static_cast<uint32_t>(codel_target_ms);
    config.codel_interval_ms = static_cast<uint32_t>(codel_interval_ms);
    config.fair_queue_quantum_bytes = kDefaultFairQueueQuantumBytes;
    return config;
}

jintArray newHealthArray(JNIEnv* env, int32_t status, const BbNativeEngineHealth& health) {
    const jint values[kHealthArraySize] = {
        static_cast<jint>(status),
        static_cast<jint>(health.state),
        static_cast<jint>(health.last_status),
        static_cast<jint>(health.last_event_type),
        static_cast<jint>(health.detail_code),
        static_cast<jint>(health.generation),
    };
    jintArray result = env->NewIntArray(kHealthArraySize);
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, kHealthArraySize, values);
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

}  // namespace

/*
 * These symbols intentionally have no Kotlin caller yet. The future adapter
 * should expose an object named
 * com.bufferbloatshaper.nativeengine.NativeEngineBridge with matching external
 * methods after Gradle packages this library.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeIsAvailable(
    JNIEnv*, jclass) {
    return static_cast<jint>(bb_native_engine_is_available());
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
    JNIEnv*, jclass, jlong handle) {
    BbNativeEngine* const engine = pointerFromHandle(handle);
    if (engine == nullptr) {
        return;
    }

    {
        std::lock_guard<std::mutex> lock(g_handles_mutex);
        const auto iterator = g_live_handles.find(engine);
        if (iterator == g_live_handles.end()) {
            return;
        }
        g_live_handles.erase(iterator);
    }

    bb_native_engine_destroy(engine);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeStart(
    JNIEnv*,
    jclass,
    jlong handle,
    jint tun_fd,
    jlong egress_rate_bytes_per_second,
    jlong ingress_rate_bytes_per_second,
    jint codel_target_ms,
    jint codel_interval_ms,
    jint flags) {
    if (!validJvmConfig(egress_rate_bytes_per_second, ingress_rate_bytes_per_second,
                        codel_target_ms, codel_interval_ms)) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }
    const BbNativeEngineConfig config = configFromJvm(
        egress_rate_bytes_per_second, ingress_rate_bytes_per_second,
        codel_target_ms, codel_interval_ms, flags);
    return withLiveEngine(handle, [&config, tun_fd](BbNativeEngine* engine) {
        return bb_native_engine_start(engine, tun_fd, &config);
    });
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
    jint flags) {
    if (!validJvmConfig(egress_rate_bytes_per_second, ingress_rate_bytes_per_second,
                        codel_target_ms, codel_interval_ms)) {
        return BB_NATIVE_STATUS_INVALID_ARGUMENT;
    }
    const BbNativeEngineConfig config = configFromJvm(
        egress_rate_bytes_per_second, ingress_rate_bytes_per_second,
        codel_target_ms, codel_interval_ms, flags);
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

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeGetHealth(
    JNIEnv* env, jclass, jlong handle) {
    BbNativeEngineHealth health{};
    const int32_t status = withLiveEngine(handle, [&health](BbNativeEngine* engine) {
        return bb_native_engine_get_health(engine, &health);
    });
    return newHealthArray(env, status, health);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeGetMetrics(
    JNIEnv* env, jclass, jlong handle) {
    BbNativeEngineMetrics metrics{};
    const int32_t status = withLiveEngine(handle, [&metrics](BbNativeEngine* engine) {
        return bb_native_engine_get_metrics(engine, &metrics);
    });
    return newMetricsArray(env, status, metrics);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativePollEvent(
    JNIEnv* env, jclass, jlong handle) {
    BbNativeEngineEvent event{};
    const int32_t status = withLiveEngine(handle, [&event](BbNativeEngine* engine) {
        return bb_native_engine_poll_event(engine, &event);
    });
    return newEventArray(env, status, event);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bufferbloatshaper_nativeengine_NativeEngineBridge_nativeStatusMessage(
    JNIEnv* env, jclass, jint status) {
    return env->NewStringUTF(bb_native_engine_status_message(static_cast<int32_t>(status)));
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}
