# Limitations and Non-Goals

## Read this before testing

Bufferbloat Shaper is an unreleased research prototype. The checked-in build deliberately does not relay traffic, and a future engine could affect connectivity if it is implemented incorrectly. Do not depend on it for emergency communication, work-critical connectivity, financial activity, or any situation where a connectivity failure would be harmful.

## Permanent product boundaries

These are intentional design limits, not missing toggles:

- The app cannot force 5G, choose cellular bands, enable carrier aggregation, or select NSA/SA mode. Those decisions belong to the modem, carrier, and radio network.
- The app is not a commercial VPN. It does not provide a remote exit location, anonymity guarantee, censorship bypass, geo-unblocking, or a project-operated relay.
- The app does not decrypt TLS, install a local CA, bypass certificate pinning, or inspect encrypted application payloads.
- QUIC/UDP download shaping is not a product goal. A safe local TCP receive-window mechanism does not exist for generic encrypted QUIC traffic without performing TLS interception.
- The app cannot eliminate latency from radio scheduling, cell congestion, server processing, or routing outside the device.

## Current prototype limitations

The following are release blockers, not caveats to hide from users:

- The checked-in native engine is an intentionally unavailable stub. The service refuses to establish a TUN route rather than run an unsafe fallback, so this build does not currently shape traffic.
- No TUN-facing TCP data plane is shipped. A future engine must provide full TCP state machinery before it can route traffic.
- The historical IPv6/DNS prototype routed IPv6 without a working forwarding path and changed resolver behavior. IPv6 routes and DNS substitution are now absent from the service; a native implementation must preserve normal resolver behavior before DNS interception is enabled.
- TCP download shaping is scaffolding, not a validated receive-window controller.
- Calibration does not yet take or persist independent physical-network measurements; automatic updates remain disabled rather than feeding shaped throughput back into its own limit.
- The statistics UI reports only aggregate metrics supplied by a native engine, configured caps, or an explicit unavailable value. It has no random/sample charts, but no live metrics exist while the stub is installed.
- The validation screen is a visible release gate, not a benchmark. It intentionally withholds before/after scores until independent two-way load generation, verified shaper state, and local measurement persistence exist.
- Lifecycle, handover, captive portal, screen-off, and battery behavior are not proven on physical carrier networks.

## Measurement limits

A lower latency number is useful only when the shaper state, load generation, endpoint, throughput, and repeated samples are known. Test results vary with signal, network congestion, server behavior, time of day, and protocols selected by apps. Results from a one-off test or a compile-only check are not a performance claim.

ICMP behavior is especially limited: Android apps cannot open arbitrary raw ICMP sockets without elevated privileges. The current service does not claim Internet ICMP support; any future validation must use a stated, independently verifiable measurement method.

## Privacy and support limits

No project telemetry service currently exists, but Android packet handling and diagnostic logging can still expose metadata locally. See [PRIVACY.md](../PRIVACY.md). Never post raw packet captures, credentials, unredacted logs, or another person's traffic in a public issue.

The source is supplied under the Apache License 2.0 without warranty. There is no service-level agreement, carrier support agreement, or compatibility guarantee.

## What would make a public release credible

Before describing the project as usable, the code must pass the evidence gates in [Testing](TESTING.md), including reliable lifecycle behavior, TCP/UDP/DNS operation, real IPv6 forwarding, sustained transfers, transitions, screen-off testing, and repeatable shaper-off/on latency measurements on documented device/carrier combinations.
