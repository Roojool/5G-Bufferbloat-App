# Current Roadmap and Release Gates

## Current stop point

The checked-in Android app is a **fail-closed source prototype**. Its native
library packages a deliberately unavailable ABI stub, so a start request shows
a recoverable unsupported state before Android creates a TUN route. It does not
currently shape, relay, inspect, or carry device traffic. This is intentional:
the unsafe hand-written Kotlin relay was removed instead of being kept as a
fallback TCP/IP stack.

## Phase 0 — public-source foundation (implemented)

- Apache-2.0 source-only repository, JDK 21 build instructions, wrapper
  checksum, CI, contribution/security/privacy documents, issue forms, and
  compatibility/limitations pages.
- Safe Kotlin/JNI lifecycle boundary for `arm64-v8a`, `armeabi-v7a`, and
  `x86_64`; unavailable or unhealthy engines must leave normal networking in
  place.
- Local profiles, supported Android allow/deny app routing configuration,
  measured-value-only UI scaffolding, and a user-initiated structured
  diagnostic export. No telemetry or project-operated network service exists.

Completion of this phase is **not** evidence that the app works as a shaper.

## Phase 1 — design and build a real local engine

1. Pin an Apache-compatible gVisor Netstack source revision, Go toolchain,
   dependency licenses/notices, and reproducible Android build process. Do not
   substitute GPL-only or proxy-dependent `tun2socks` code.
2. ABI v2 now exposes a narrow Android `VpnService.protect(fd)` callback. A
   real engine must honor and test it for every direct socket so it cannot loop
   back into the VPN; it must also replace the stub's raw-pointer registry with
   opaque lifetime-safe session tokens before being enabled.
3. Implement IPv4 TCP forwarding first: complete TUN-facing TCP state,
   ordering, teardown, direct protected sockets, bounded queues, health events,
   aggregate/per-flow metrics, and serialized teardown.
4. Add TokenBucket, bounded fair queueing, and CoDel only where the native
   stack owns the relevant queue and retransmission semantics. Never drop bytes
   already accepted by an outer TCP socket.
5. Prove native start/update/stop, health failure, and TUN ownership on each
   ABI before enabling any route.

## Phase 2 — carefully expand functionality

- IPv6 only after native dual-stack forwarding and A/AAAA behavior pass tests;
  until then IPv6 must bypass the incomplete local path.
- Preserve the device DNS policy; do not replace resolvers or log query
  contents. Surface only redacted DNS failure diagnostics.
- Add UDP **upload** pacing only after TCP is stable. QUIC/UDP download shaping
  remains out of scope.
- Wire native health/events/per-flow metrics into `VpnRuntimeState`; displayed
  rates must be measured, not configured caps or simulated values.
- Connect network/default-network, Wi-Fi/cellular, captive-portal, MTU, and
  optional user-authorized RAT signals to safe pause/recover behavior. Never
  claim control of 5G, bands, NSA/SA, or carrier aggregation.

## Phase 3 — measurement and product features

- Add opt-in per-app **profiles** only after the engine applies them; the
  current allow/deny routing setting is not a distinct traffic policy.
- Add direct independent physical-network probes and locally persisted,
  per-network rolling samples. Never use traffic already limited by the shaper
  to learn its own rate cap.
- Build an honest validation flow: explicit off/on state, independent two-way
  load, local throughput and queue-delay samples, persistence, and repeatable
  comparisons. No grade may be shown without those inputs.
- Add only app-owned, structured diagnostic events to manual export; do not
  export raw logcat or arbitrary network/error strings.

## Phase 4 — evidence before a tagged source release

All requirements in [Testing](TESTING.md) and [Compatibility](COMPATIBILITY.md)
need literal results before claiming support:

- API 26–35 emulator coverage and all three native ABIs.
- Qualcomm and MediaTek physical devices across at least two OEMs; Airtel,
  Jio, Vi where available, and Wi-Fi.
- Ten consecutive start/stop cycles; DNS A/AAAA, TCP, QUIC/UDP, IPv4/IPv6,
  captive-portal recovery; five checksum-verified 50 MB+ transfers; handover;
  and 30-minute screen-off operation.
- Repeated shaper-off/on measurements at multiple times of day showing lower
  loaded latency with acceptable throughput retention.
- Published matrix entries only for combinations that actually pass.

## Project boundaries

- No remote proxy, telemetry server, TLS interception, payload inspection, or
  data sale.
- No universal-phone/carrier claim. Unsupported conditions must show a clear
  recoverable state and must not leave traffic black-holed.
- No APK/AAB distribution until the implementation and evidence gates above
  are complete. The repository is source-only.
