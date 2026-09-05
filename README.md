# Bufferbloat Shaper

> **Prototype status — not ready for daily-use networking.** This is an Android/Kotlin research project for a local traffic shaper. It builds, but it has not passed the real-device reliability and bufferbloat-validation gates required for a public release.

Bufferbloat Shaper explores whether an Android `VpnService` can reduce added latency during a busy connection. The intended product is local-only: it uses Android's VPN interface as a packet-capture mechanism and is not a hosted VPN, proxy, or traffic-analytics service.

## What is in the repository today

- A Compose Android UI, observable runtime-state scaffold, and a `VpnService` lifecycle implementation that fails safely when its native engine is unavailable.
- A versioned JNI boundary packaged for `arm64-v8a`, `armeabi-v7a`, and `x86_64`. The checked-in native implementation is deliberately an unavailable stub and never relays a packet.
- Historical IPv4 packet parsing and UDP/TCP relay code, plus token bucket, CoDel-style AQM, fair queuing, calibration, validation, notification, and statistics scaffolding.

The implementation is **not a completed shaper**. In fact, the checked-in build intentionally does not activate a VPN route: it reports that a verified native packet engine is unavailable and leaves the device on its ordinary network. The historical Kotlin TCP relay is retained only as reference/migration code; it lacks production TCP behavior such as retransmission and robust out-of-order handling. IPv6 forwarding, live metrics, calibration, and validation are also incomplete. Read [Limitations](docs/LIMITATIONS.md) before installing or testing it.

## Product boundaries

- No project-operated relay, telemetry service, traffic resale, TLS interception, or payload decryption.
- No ability to force 5G, select a cellular band, choose NSA/SA, or enable carrier aggregation.
- TCP download control is a planned capability; QUIC/UDP download shaping is not a goal.
- An unavailable native engine is reported visibly before a VPN route is created. A working engine still has to prove health/failover behavior on devices.

## Documentation

- [Architecture and current implementation status](docs/ARCHITECTURE.md)
- [Known limitations and non-goals](docs/LIMITATIONS.md)
- [Compatibility and field-test matrix](docs/COMPATIBILITY.md)
- [Source build instructions](docs/SOURCE_BUILD.md)
- [Testing and release evidence](docs/TESTING.md)
- [Privacy notice](PRIVACY.md)
- [Contributing](CONTRIBUTING.md)
- [Security reporting](SECURITY.md)
- [Original technical/product plan](mobile-bufferbloat-shaper-plan.md)
- [Corrected project status and roadmap](bufferbloat-shaper-status-and-roadmap.md)

## Build from source

This repository is source-only. No APK or AAB is published as a release artifact.

See [Source Build](docs/SOURCE_BUILD.md) for prerequisites and commands. The short version is:

```powershell
.\gradlew.bat :app:assembleDebug
```

Use JDK 21, Android SDK Platform 35, Android NDK r28c, and CMake 3.22.1. The Gradle project now avoids a machine-specific JDK path; see [Source Build](docs/SOURCE_BUILD.md) for the complete setup.

## Contributing and reporting

Please do not report security vulnerabilities in public issues. Follow [SECURITY.md](SECURITY.md). For ordinary defects, feature proposals, and compatibility results, use the repository's issue templates and include reproducible, redacted evidence.

## License

Copyright 2026 Roojool.

Licensed under the [Apache License 2.0](LICENSE).
