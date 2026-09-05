# Native engine scaffold

This is a **compile-oriented JNI boundary only**. It is wired into the Android
Gradle module and has a typed Kotlin bridge, but it does not provide packet
relaying. The checked-in Android service checks its unavailable capability
before establishing a TUN route, so it cannot accidentally activate an unsafe
fallback data path.

This repository does not yet vendor a pinned Go toolchain, gVisor checkout, or
other reviewed native netstack dependency. Consequently, the checked-in
`bufferbloat_native_engine` library is an intentionally safe
`stub-unavailable` implementation:

- `bb_native_engine_is_available()` returns `0`.
- `start` and `update_config` return `BB_NATIVE_STATUS_UNAVAILABLE`.
- It never reads, duplicates, retains, modifies, or closes the supplied TUN
  file descriptor.
- It forwards no packets, has no sockets, does no TLS/payload inspection, and
  reports zero traffic metrics.

This behavior is deliberate: a future Android service must check availability
*before* capturing routes, and on an unavailable/failed start must preserve or
restore ordinary connectivity rather than leave a black-hole VPN active.

## Contract

[`include/bufferbloat_native_engine.h`](include/bufferbloat_native_engine.h)
defines ABI version 2. It supplies the future engine boundary for:

- creation, borrowed-TUN start with a narrow Android socket-protection
  callback, atomic configuration update, and idempotent stop;
- pull-based health/event snapshots, aggregate metrics, and per-flow metric
  lookup;
- explicit engine state, feature bits, and failure status codes with no user
  traffic data in the diagnostic interface.

`start` receives a **borrowed** descriptor through
`BbNativeEngineStartParams`. Kotlin remains responsible for the
`ParcelFileDescriptor`; any future successful engine must duplicate the FD
before returning and must never close the caller's descriptor. This avoids a
failed native start leaking a descriptor or stealing lifecycle ownership from
the VPN service. All extensible ABI structures carry `abi_version` and
`struct_size` so a future adapter can reject an incompatible caller safely.

`BbNativeSocketProtector` is equally important: a real engine must call its
`protect_socket(fd)` callback immediately after every direct outbound
`socket()` and before bind/connect/send. It must close and report a typed error
if the callback fails—never fall back to an unprotected socket that could loop
through the VPN. The JNI bridge holds a global Kotlin callback reference only
until native `stop` has joined all workers; it never passes a `VpnService` or
Android `Context` into the engine.

`src/jni_bridge.cpp` exports unmangled JNI methods for
`com.bufferbloatshaper.nativeengine.NativeEngineBridge`. The checked-in Kotlin
adapter loads the library, reports its unavailable capability, and supports the
future lifecycle/metrics contract. The bridge validates JNI handles against a
native live-handle set to make stale/double-destroy calls harmless in the
checked-in stub. A real asynchronous engine must replace the raw-pointer
registry with opaque monotonic tokens and per-session lifetime gates before it
is enabled.

The array layouts used by the bridge are deliberately documented here so the
future Kotlin adapter can turn them into typed runtime-state models without
reflecting private native fields:

| Method | Array values, in order |
| --- | --- |
| `nativeGetHealth` (`LongArray`) | status, state, lastStatus, lastEventType, detailCode, generation |
| `nativeGetMetrics` (`LongArray`) | status, state, generation, sampledAtMs, packetsIn, packetsOut, bytesIn, bytesOut, activeFlows, queuedBytes, aqmDrops, udpPacketsPaced |
| `nativePollEvent` (`LongArray`) | status, type, eventStatus, detailCode, generation, occurredAtMs |
| `nativeGetFlowMetrics` (`LongArray`) | status, state, generation, opaqueFlowId, bytesIn, bytesOut, queuedBytes, aqmDrops, protocol, reserved |

The Kotlin bridge preserves 64-bit generations and timestamps. No array or
event may include addresses, ports, DNS names, package names, or payload data.

## Local NDK compile check

The project accepts only the release ABIs `arm64-v8a`, `armeabi-v7a`, and
`x86_64`, with API 26 or newer. From PowerShell, use an output directory outside
the repository:

```powershell
$ndk = "$env:ANDROID_SDK_ROOT\\ndk\\28.2.13676358"
$cmake = "$env:ANDROID_SDK_ROOT\\cmake\\3.22.1\\bin\\cmake.exe"
$ninja = "$env:ANDROID_SDK_ROOT\\cmake\\3.22.1\\bin\\ninja.exe"
& $cmake -S native -B "$env:TEMP\\bufferbloat-native-x86_64" -G Ninja `
  "-DCMAKE_MAKE_PROGRAM=$ninja" `
  "-DCMAKE_TOOLCHAIN_FILE=$ndk\\build\\cmake\\android.toolchain.cmake" `
  -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=android-26
& $cmake --build "$env:TEMP\\bufferbloat-native-x86_64"
```

Repeat the configuration with `-DANDROID_ABI=arm64-v8a` and
`-DANDROID_ABI=armeabi-v7a`. Gradle packages this unavailable stub for all
three release ABIs; that packaging must not be confused with a working native
engine or traffic verification.

## Replacing the stub

Do not flip `BUFFERBLOAT_WITH_GVISOR` on: CMake intentionally fails because no
real engine has been reviewed or vendored. A production migration must:

1. Pin and audit the upstream source and licenses, including its Go version and
   Android cross-compilation path.
2. Implement this C ABI with a real userspace netstack that owns TCP state,
   IPv4/IPv6 forwarding, retransmission, ordering, teardown, and receive-window
   accounting.
3. Make the implementation duplicate the borrowed TUN FD, invoke the supplied
   socket-protection callback before every direct socket connects, publish only
   aggregate/redacted metrics, and provide deterministic stop/join behavior.
4. Replace the unavailable stub with a reviewed adapter while preserving the
   Kotlin/Gradle ABI contract, required feature bits, and release ABI packaging.
5. Prove the real engine on all three ABIs with tests and real-device traffic before routing
   any production traffic through it.

Until those conditions are met, this native directory is an integration seam,
not a functioning packet engine.
