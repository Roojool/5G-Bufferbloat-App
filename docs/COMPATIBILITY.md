# Compatibility and Field-Test Matrix

## What “supported” means

Support is a **feature/capability-level evidence claim**, not one binary label
for a phone. Each claim names the implementation commit, device/Android/kernel,
network, feature and its [Testing](TESTING.md) evidence. Broad product support
also requires the complete release gate; one working optional feature cannot
waive forwarding/integrity requirements. Compilation or one browse session is
not a compatibility result.

The project can never guarantee identical behavior on every Android build, modem firmware, carrier network, captive portal, or corporate VPN configuration. Android apps cannot force 5G, choose a band, enable carrier aggregation, or control NSA/SA selection.

## Current source compatibility

| Item | Current state |
|---|---|
| Minimum Android version | API 26 (Android 8.0), as configured in the app |
| Compile/target SDK | API 35, as configured in the app |
| Kotlin/JVM build runtime | JDK 21 is the supported build runtime |
| Native ABIs | A safe, unavailable JNI stub is configured for `arm64-v8a`, `armeabi-v7a`, and `x86_64`; it does not relay packets |
| Physical-device confidence | Not established; prior evidence is primarily exploratory/emulator work |
| IPv4 traffic | The checked-in service fails closed before capturing routes because no verified native engine exists |
| IPv6 traffic | Not supported; no native IPv6 forwarding path is implemented |
| TCP download shaping | Not implemented/verified as a production capability |
| QUIC/UDP download shaping | Out of scope |

API 26 is the configured minimum and API 35 the compile/target level; no maxSdk
is declared. This is not proof of runtime support on API 26–35 or newer devices.
The checked-in build is intentionally unavailable as a traffic shaper. Current
submission policy is recorded separately in [Play Compliance](PLAY_COMPLIANCE.md).

## Feature/capability support model (planned framework)

| Capability | Current evidence/status | Required gate |
|---|---|---|
| Stub packaging / pre-route unavailable check | Source and CI evidence; no real traffic | Maintain negative lifecycle/ABI tests |
| IPv4 TCP and UDP/QUIC forwarding | Not implemented | Mandatory integrity/protection/lifecycle tests |
| IPv6 forwarding | Not implemented; future IPv4-only path allows bypass | Early dual-stack/IPv6-only evidence before whole-device claims |
| TCP upload pacing/fairness/backpressure | Disconnected Kotlin references only | Bounded buffers, byte integrity and physical load evidence |
| TCP download control | Experimental proposal; no implementation | Protected-socket effect and physical latency/throughput evidence |
| TCP_WINDOW_CLAMP / TCP_INFO | No runtime probes or phone results | Probe API/field availability, then validate actual usefulness separately |
| Adaptive autorate | Not implemented | Independent delay/load, stability and safe fallback evidence |
| OEM tuning / Radio Advisor | Planned later; not implemented | Common correctness first, then scoped performance/permission evidence |

Future records must distinguish unknown/unprobed, unavailable (with reason),
probe-available but unverified, verified for stated scope, and failed/regressed.
Only real results enter the physical matrix; the table above is an implementation
inventory, not placeholder device successes. Runtime probes determine optional
availability; model/SoC/OEM labels provide context, not correctness decisions.
Static ABI requirements remain necessary but are not dynamic socket probes.

Missing optional download control must be reported without disabling independently
proven forwarding/upload capabilities. Missing mandatory forwarding, protection
or safe-stop capability must block route activation. This framework and UI are
planned: current configuration still requires both positive upload/download caps.

## Required public-release matrix

The initial matrix is India-first. Record exact device model, Android build, SIM/network, app commit, and literal test evidence in a redacted issue or release report.

| Category | Minimum coverage before public release | Status |
|---|---|---|
| Emulator | x86_64 coverage for the supported Android API range | Not complete |
| Chipset | At least one Qualcomm and one MediaTek device | Not complete |
| OEM | At least two OEMs | Not complete |
| Cellular | Airtel, Jio, and Vi where service is available | Not complete |
| Wi-Fi | At least one stable Wi-Fi network and one captive portal/recovery scenario | Not complete |
| Network transitions | Wi-Fi ↔ cellular and default-SIM/network changes | Not complete |
| IPv6 | A dual-stack network and an IPv6-only destination after native IPv6 support lands | Blocked by current implementation |

## Compatibility result format

Use the **Compatibility report** issue form. A valid report includes:

- Device/OEM and Android version (no serial number or phone number).
- App version and commit SHA.
- Network transport and carrier, without precise location unless the reporter explicitly chooses to provide it.
- Whether the VPN started, stopped, and recovered cleanly.
- Results for DNS A/AAAA, browsing, TCP transfer, UDP/QUIC, network transition, and screen-off stability.
- Shaper-off/on latency measurements, throughput, and method used.
- Feature-by-feature probe outcomes and evidence, including unavailable optional
  features, kernel version, SoC, ABI, fallback and verification scope.
- Redacted logs only when needed to reproduce a failure.

## Expected safe behavior

An unsupported or unhealthy state must be visible and recoverable. It should fail closed with respect to the local shaper—stop or bypass the app's interception path—rather than capture traffic and leave it unforwarded. The checked-in stub meets this condition before route establishment; a real engine must prove it under traffic and failure conditions.

## Explicit non-compatibility claims

This project does not claim compatibility with:

- Another active VPN in the same Android user/profile: Android permits only one;
  starting a new VPN stops the previous service. Always-on/lockdown policies may
  prevent activation or ordinary-network fallback, and the app disables its own
  always-on support. [Android VPN guide](https://developer.android.com/develop/connectivity/vpn)
- Networks that prohibit local VPN services, use nonstandard captive-portal behavior, or require unimplemented enterprise controls.
- Every IPv6 implementation before full IPv6 forwarding passes the field matrix.
- Every QUIC/HTTP/3 workload for download-side latency control.
- Tethering/hotspot clients: this is a separate, non-guaranteed routing scope,
  not established by successful on-device VPN tests.
