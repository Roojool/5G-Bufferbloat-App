# Current Roadmap and Release Gates

This sequence supersedes the historical mobile plan and earlier phase numbering.
See [Project Context](PROJECT_CONTEXT.md) for the implementation snapshot and
[Design Decisions](DESIGN_DECISIONS.md) for rationale. Every stage below Phase 0
is planned/unpassed; documenting it does not implement or verify it.

## Phase 0 — public-source foundation (checked in)

The app is a fail-closed source prototype: Compose UI, local settings, routing
preferences, structured manual diagnostics, CI and JNI ABI v2 exist. A deliberately
unavailable three-ABI native stub causes UNSUPPORTED on a valid-config start;
invalid settings report ERROR first. Neither path creates a route.
It carries no traffic. Go/gVisor, socket probes, a real engine and live shaping
are absent. Algorithm tests and lifecycle scaffolding are not a working shaper.

## Stage 1 — feasibility before expensive engine integration (next)

1. Design a small stock-Android internal protected-socket harness and define
   falsifiable procedures in [Experiments](EXPERIMENTS.md). Keep the ordinary app
   unavailable; a debug APK is not automatically an experimental-route gate.
2. Test the **remote-facing OS TCP socket**: bounded receive behavior,
   TCP_WINDOW_CLAMP and TCP_INFO. Record protection, option/errno/readback,
   actual transport effect, latency/throughput and safe failure on physical
   devices. Do not infer remote control from an app-facing gVisor window.
3. Establish bounded stream pacing/backpressure feasibility and identify any
   valid packet AQM queues before adopting queue algorithms.
4. Define capability/device framework contracts: mandatory forwarding/lifecycle
   requirements; optional probes; unknown/unavailable/available outcomes; feature
   disablement and redacted reasons; evidence scoped by Android/kernel/ABI/network.

**Exit:** reviewed literal feasibility results and a go/narrow/defer decision.
Absent or negative download evidence keeps that feature disabled. A proven
upload-only direction may proceed with an explicitly revised scope; never call
unverified download control solved. A successful socket option is not this gate.

## Stage 2 — dependency foundation and real forwarding before shaping

1. Pin/review an Apache-compatible gVisor revision, Go toolchain, transitive
   licenses/notices and reproducible Android build path for all three ABIs.
   Resolve actual NDK selection and future SDK migration in implementation tasks.
   Do not adopt GPL-only or project-proxy-dependent tun2socks code.
2. Build the internal gate and reuse/audit ABI v2: protect every socket before
   bind/connect/send, copy input records, duplicate the borrowed TUN safely,
   preserve opaque token/callback lifetime, and prove failed-start stop/join.
3. Implement ordinary IPv4 TCP **and safe UDP/QUIC forwarding**, unshaped first,
   with bounded resources, ordered streams, teardown, DNS policy preservation,
   health/events and measured aggregate/per-flow counters. Two TCP legs own
   independent state; Android/Linux owns the Internet-facing connection.
4. Implement runtime capability reporting from actual probes, independently of
   device names. Required-path failure blocks interception; optional failures
   leave proven forwarding available without the optional feature.

**Exit:** internal forwarding integrity and lifecycle evidence on all ABIs;
ordinary builds remain unavailable. No route is enabled merely by flipping the
existing gVisor CMake option, which intentionally fails today.

## Stage 3 — early dual-stack and universal correctness

- Implement/test IPv6 TCP/UDP, dual-stack/IPv6-only destinations, DNS A/AAAA,
  MTU and relevant fragmentation/error behavior. Until then internal IPv4-only
  builds explicitly allow IPv6 bypass and cannot claim whole-device control.
- Prove socket protection, start/update/stop, stale-generation exclusion,
  callback join, health failure and watchdog/containment under actual traffic.
- Verify resolver policy, captive portals, Wi-Fi/cellular/default-network
  transitions, revocation, resource bounds and screen-off behavior.
- Missing optional probes or OEM profiles must not break common forwarding.

**Exit:** reviewed dual-stack correctness/lifecycle evidence, before broad
whole-device claims or OEM performance tuning. No physical gate has passed yet.

## Stage 4 — upload shaping, measurement and adaptive autorate

- Add bounded TCP stream buffering, token-budget pacing, fair scheduling and
  backpressure; prove transfer integrity, partial-write handling and fairness.
  Packet-dropping AQM/ECN requires an explicitly valid queue and loss-recovery
  proof. Never discard already-accepted stream bytes.
- Add UDP upload pacing only after safe forwarding is stable, with bounded
  datagrams and a documented loss policy. QUIC/UDP download shaping stays out.
- Implement independent delay/load measurements and locally persisted per-network
  samples with consent/data budgets, aging and endpoint failure handling. No
  project-operated measurement service or self-limited capacity feedback.
- Build adaptive delay/load autorate before relying on static product settings:
  bounded changes, stale-data fallback, idle/handover handling and stability
  evidence. Percentile × headroom may be a seed/fallback, not the entire control
  strategy. Compare static and adaptive behavior explicitly.
- Build honest off/on validation with independent upload/download load, stated
  timing method, throughput, loss/errors, repeat samples and local persistence.
  No grade without actual inputs. Apply per-app traffic profiles only after the
  engine enforces them; current routing allow/deny settings are not rate profiles.

## Stage 5 — optional TCP download control

Only after Stage 1 evidence and a reliable data path, integrate the supported
protected-socket mechanism behind runtime capability and internal experiment
gates. Revalidate effective remote-window behavior, bounded buffering,
zero-window recovery, latency and throughput across physical combinations.
TCP_WINDOW_CLAMP and TCP_INFO remain experimental until those results exist.
The configuration/UI must support unavailable download control honestly; the
current requirement for both positive rate limits is not that capability gate.

Download failure does not authorize UDP dropping, TLS interception or a relay.
If feasibility fails, revise scope and public claims before pursuing release.

## Stage 6 — release evidence and separate activation review

Meet [Testing](TESTING.md) and [Compatibility](COMPATIBILITY.md) with literal
results: emulator/ABI checks, Qualcomm and MediaTek across two OEMs, Airtel/Jio/Vi
where available, Wi-Fi/captive portal, ten start/stop cycles, five checksum-verified
50 MB+ transfers, DNS/TCP/UDP/IPv6, transitions, 30-minute screen-off operation,
and repeated loaded-latency/throughput comparisons at multiple times of day.
Support claims name only tested features and combinations.

Default route activation requires a separate reviewed change after applicable
correctness and physical gates pass. Debug-only work cannot remove the README
prototype banner or pretend the ordinary app forwards traffic. A tagged source
release needs reproducible build evidence and accurate limitations. APK/AAB/Play
distribution additionally requires signing/distribution work and current
[Play Compliance](PLAY_COMPLIANCE.md) review, including a separate API migration.

## Stage 7 — measured OEM/device performance tuning

After universal correctness, use measured profiles for CPU, buffer, scheduling,
battery and thermal budgets. Runtime capabilities still determine optional
availability; a model allowlist cannot determine correctness. Repeat negative
and fallback tests with each tuning profile and retain conservative defaults.

## Stage 8 — later Radio Advisor and mapping research

Observe public radio signals where permission/platform support permits, then
consider local recommendations or mapping. Decide user consent, location/privacy,
retention and measurement validity first. No advisor is implemented. Stock code
does not force LTE/NR bands, NSA/SA or carrier aggregation. Exact band locking,
if separately researched, belongs outside the universal stock application.

## Boundaries throughout

No project relay, telemetry backend, payload decryption, traffic resale or
TLS interception. No universal-phone/carrier or hotspot/tethering guarantee.
Unsupported conditions must be visible and recoverable without a black-hole
route. Successful compilation is not forwarding, efficacy or release evidence.
