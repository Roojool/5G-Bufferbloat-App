# Testing and Release Evidence

## Principle

For this project, “built” and “works” are different claims. Every behavior claim needs literal, reproducible evidence. Do not replace logs, test output, or measurements with a narrative summary.

## Automated checks

The JVM suite also covers the bounded local health timeline and verifies that
the manually shared diagnostic report exports only structured transition
status/generation values, never free-form error detail. Device make/model and
local timestamps are intentionally visible in that report and must be reviewed
before the user shares it.

The repository CI runs these source-level checks on JDK 21:

```text
:app:assembleDebug
:app:testDebugUnitTest
:app:lintDebug
```

They establish that the source can assemble and pass available static/unit checks. They cannot establish VPN reliability, carrier compatibility, radio behavior, or bufferbloat improvement.

The debug assembly also compiles the intentionally unavailable JNI-stub boundary for `arm64-v8a`, `armeabi-v7a`, and `x86_64`. That is an ABI packaging and fail-closed capability check, not a native data-plane or traffic test.

New native-engine work should add native/unit tests for packet parsing/checksums, TCP state, queue accounting, TokenBucket behavior, CoDel-style logic, fair queuing, DNS behavior, profiles, calibration calculations, and validation grading. It must also test `VpnService.protect(fd)` before every direct connect, false/throw protect paths, no callback after stop, and bounded typed-event behavior. Instrumentation tests should cover VPN lifecycle, configuration changes, health transitions, native library loading, and safe fallback behavior.

## Device test record

For a network-affecting change, record:

- App version and commit SHA.
- Device/OEM/model and Android version; omit serial number, phone number, IMEI, IMSI, and precise location.
- Transport (Wi-Fi/cellular), carrier if the tester chooses to disclose it, and approximate signal/network state.
- Whether the shaper was disabled, enabled, or failed over.
- Commands/test steps and literal, redacted output.
- Any failure, recovery action, and result.

Use the Compatibility report issue form for a shareable record.

## Public-release gate

No tagged public release is ready until each item below has evidence on the [compatibility matrix](COMPATIBILITY.md):

| Gate | Required evidence |
|---|---|
| Lifecycle | Ten consecutive start/stop cycles without crash, restart loop, stale writer, or wake-lock leak |
| Basic traffic | DNS A/AAAA, normal browsing, TCP, and QUIC/UDP work while the VPN is active |
| Transfer reliability | Five checksum-verified transfers of at least 50 MB complete without stalls or corruption |
| IPv6 | A dual-stack/IPv6-only destination works after a complete IPv6 relay is implemented |
| Mobility | Wi-Fi/cellular and default-network transitions recover without silently losing traffic |
| Background | At least 30 minutes of screen-off operation without an unexpected shutdown or sustained leak |
| Bufferbloat result | Repeatable shaper-off/on tests at different times of day show measured loaded-latency improvement with acceptable throughput retention |
| Privacy | No project-operated endpoint, analytics SDK, payload decryption, or unintended diagnostic upload is present |

## Validation methodology

Before a result can be called a bufferbloat improvement:

1. Confirm and record whether shaping is actually on or off.
2. Generate independent upload and download load, not only the app's own normal traffic.
3. Measure idle and loaded latency against a stated endpoint and method.
4. Record actual throughput, duration, packet loss/error behavior, and repeated samples.
5. Compare equivalent network conditions as closely as possible.

Do not grade or market a result when the VPN state, flow path, or latency method is unknown. The current service does not claim raw-ICMP support; a future measurement method must state exactly what it measures.

## When to stop testing

Stop the test immediately and disable the VPN if traffic stalls, the device loses ordinary connectivity, a restart loop appears, a wake-lock warning is observed, or unexpected traffic handling occurs. Capture a redacted diagnostic record only after connectivity is restored.
