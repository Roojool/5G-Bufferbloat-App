# Compatibility and Field-Test Matrix

## What “supported” means

For this project, support is evidence-based. A device/carrier/network combination is listed as supported only after it passes the complete field-test record in [Testing](TESTING.md). Compilation, installation, or one successful browse session is not a compatibility result.

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

The source currently declares support for Android API 26–35 only as a build target range. The checked-in build is intentionally unavailable as a traffic shaper, so it makes no runtime compatibility promise.

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
- Redacted logs only when needed to reproduce a failure.

## Expected safe behavior

An unsupported or unhealthy state must be visible and recoverable. It should fail closed with respect to the local shaper—stop or bypass the app's interception path—rather than capture traffic and leave it unforwarded. The checked-in stub meets this condition before route establishment; a real engine must prove it under traffic and failure conditions.

## Explicit non-compatibility claims

This project does not claim compatibility with:

- Other always-on VPNs or lockdown VPN policies running at the same time.
- Networks that prohibit local VPN services, use nonstandard captive-portal behavior, or require unimplemented enterprise controls.
- Every IPv6 implementation before full IPv6 forwarding passes the field matrix.
- Every QUIC/HTTP/3 workload for download-side latency control.
