# Bufferbloat Shaper

An Android/Kotlin research prototype for mitigating mobile bufferbloat with a local `VpnService` traffic relay. It is designed to apply upload pacing with a token bucket, CoDel-style active queue management, and per-flow fair queuing; it also explores TCP download control and adaptive calibration.

## Current status

The project builds successfully, but it is **not production-ready**. The IPv4 egress-shaping path is implemented; the hand-written TCP relay, IPv6 forwarding, TCP download shaping, calibration, and on-device reliability all need further validation and hardening. See [the status and roadmap](bufferbloat-shaper-status-and-roadmap.md) and [the full handoff](bufferbloat-shaper-handoff-for-chatgpt.md) before making claims about runtime behavior.

## Build

- Android Studio with its bundled JBR/JDK 21
- Android SDK 35

Run `gradlew.bat :app:assembleDebug` on Windows. The JDK location is presently pinned in `gradle.properties`; update it locally if Android Studio is installed elsewhere.

## Repository conventions

- Commit source, Gradle wrapper files, configuration, and project documentation.
- Do not commit build outputs, IDE metadata, local Gradle distributions, or device captures.
- Keep changes small and independently verifiable. A successful compile is not evidence that the VPN data path works.

## Privacy and scope

The intended design is local-only: the VPN interface intercepts device traffic on-device and does not route it through a project-operated server. This app cannot control cellular bands, carrier aggregation, or 5G mode.
