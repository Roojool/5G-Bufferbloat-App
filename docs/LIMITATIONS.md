# Limitations and Non-Goals

## Read this before testing

Bufferbloat Shaper is an unreleased research prototype. The checked-in build deliberately does not relay traffic, and a future engine could affect connectivity if it is implemented incorrectly. Do not depend on it for emergency communication, work-critical connectivity, financial activity, or any situation where a connectivity failure would be harmful.

## Permanent product boundaries

These are intentional design limits, not missing toggles:

- The app cannot force 5G, choose cellular bands, enable carrier aggregation, or select NSA/SA mode. Those decisions belong to the modem, carrier, and radio network.
- Exact LTE/NR band forcing is outside the universal stock app. If separately
  authorized as research, device-specific privileged band locking belongs outside
  this application. A later Radio Advisor may observe/recommend conditions using
  available permissions/APIs; it is not implemented and would not control bands.
- The app is not a commercial VPN. It does not provide a remote exit location, anonymity guarantee, censorship bypass, geo-unblocking, or a project-operated relay.
- The app does not decrypt TLS, install a local CA, bypass certificate pinning, or inspect encrypted application payloads.
- QUIC/UDP download shaping is not a product goal. A safe local TCP receive-window mechanism does not exist for generic encrypted QUIC traffic without performing TLS interception. If an IPv4 VPN route is ever enabled, UDP/QUIC must still be forwarded safely; it may not be dropped merely because it is not shaped.
- The app cannot eliminate latency from radio scheduling, cell congestion, server processing, or routing outside the device.

## Current prototype limitations

The following are release blockers, not caveats to hide from users:

- The checked-in native engine is an intentionally unavailable stub. The service refuses to establish a TUN route rather than run an unsafe fallback, so this build does not currently shape traffic.
- No TUN-facing TCP data plane is shipped. A future engine must provide full TCP state machinery before it can route traffic.
- The historical IPv6/DNS prototype routed IPv6 without a working forwarding path and changed resolver behavior. IPv6 routes and DNS substitution are now absent from the service; an IPv4-only engine must explicitly let IPv6 use Android's ordinary network until dual-stack forwarding passes tests. A native implementation must preserve normal resolver behavior before DNS interception is enabled.
- TCP download shaping is unimplemented/unverified. The candidate control point
  is the protected remote-facing Android/Linux TCP socket, not only the separate
  app-facing userspace endpoint. TCP_WINDOW_CLAMP/TCP_INFO and runtime optional
  probes remain experiments without physical evidence.
- Calibration does not yet take or persist independent physical-network measurements; automatic updates remain disabled rather than feeding shaped throughput back into its own limit.
- Adaptive delay/load autorate is planned; a percentile/headroom calculation is
  not a working adaptive controller. No upload pacing/backpressure or packet AQM
  is connected to traffic, and accepted TCP stream bytes may not be dropped.
- The statistics UI reports only aggregate metrics supplied by a native engine, configured caps, or an explicit unavailable value. It has no random/sample charts, but no live metrics exist while the stub is installed.
- The validation screen is a visible release gate, not a benchmark. It intentionally withholds before/after scores until independent two-way load generation, verified shaper state, and local measurement persistence exist.
- Lifecycle, handover, captive portal, screen-off, and battery behavior are not proven on physical carrier networks.
- A future engine that fails to confirm stop/join or exceeds the bounded native lifecycle watchdog during startup or shutdown is treated as an unrecoverable safety fault: the app records a generic local marker and terminates its own process so Android closes app-owned descriptors. That containment path has not been exercised against a real engine and is not a substitute for the lifecycle release gate.

## VPN and tethering scope

Android permits one active VPN service per user/profile. Starting another stops
the existing service; another always-on or lockdown VPN can also prevent this
app's operation or ordinary-network fallback. The manifest disables this app's
always-on support on Android 8.1/API 27 and later, where that metadata is honored;
the configured minimum API 26 must not be assumed to honor the opt-out.
This project does not promise coexistence with another VPN.
[Android VPN guide](https://developer.android.com/develop/connectivity/vpn)

A debug-only no-route F-01/F-02 harness can now collect socket-call observations
from an owner-authorized synthetic endpoint. It does not prove download control,
create a forwarding path, or pass Stage 1. Every physical efficacy outcome remains
UNVERIFIED — REQUIRES PHYSICAL EXPERIMENT. See [Experiments](EXPERIMENTS.md).

Hotspot/tethering traffic is a separate, **non-guaranteed** scope. An on-device
VpnService path and its tests do not establish that traffic from tethered clients
is captured, forwarded or shaped. Any later research needs its own routing and
physical evidence; no hotspot-support claim exists today.

## Measurement limits

A lower latency number is useful only when the shaper state, load generation, endpoint, throughput, and repeated samples are known. Test results vary with signal, network congestion, server behavior, time of day, and protocols selected by apps. Results from a one-off test or a compile-only check are not a performance claim.

ICMP behavior is especially limited: Android apps cannot open arbitrary raw ICMP sockets without elevated privileges. The current service does not claim Internet ICMP support; any future validation must use a stated, independently verifiable measurement method.

## Privacy and support limits

No project telemetry service currently exists, but Android packet handling and diagnostic logging can still expose metadata locally. See [PRIVACY.md](../PRIVACY.md). Never post raw packet captures, credentials, unredacted logs, or another person's traffic in a public issue.

The source is supplied under the Apache License 2.0 without warranty. There is no service-level agreement, carrier support agreement, or compatibility guarantee.

## What would make a public release credible

Before describing the project as usable, the code must pass the evidence gates in [Testing](TESTING.md), including reliable lifecycle behavior, TCP/UDP/DNS operation, real IPv6 forwarding, sustained transfers, transitions, screen-off testing, and repeatable shaper-off/on latency measurements on documented device/carrier combinations.
