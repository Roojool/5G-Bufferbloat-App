# Current Project Context

Snapshot: 2026-09-11. This concise handoff describes the documentation rebaseline
on `codex/docs-rebaseline`, based on main implementation commit
`7cb9289147c519ca42fee1603fceeda2587cf447`. No app, native, Gradle, or CI behavior
changes in this rebaseline. Refresh this file after engineering work.

## Repository and release state

- Main and origin/main were both `7cb9289` at inspection; tracked files were
  clean. Existing untracked `.codex-artifacts/`, `.codex-finalizer/`, and `output/`
  are local material, not implementation or evidence adopted by this handoff.
- [PR #1: Clean repository hygiene and track AGENTS.md](https://github.com/Roojool/5G-Bufferbloat-App/pull/1)
  and [PR #2: Add AI project handoff and future-phase prompt guide](https://github.com/Roojool/5G-Bufferbloat-App/pull/2)
  were open, based on main, with successful CI. Neither is included in this
  branch. AGENTS.md was absent on main: this rebaseline adds it using the
  proposed rules from PR #1 plus the owner's new protocol. Reconcile that overlap
  during review; PR #2's phase sequence must be reconciled before adoption.
- [Main CI at the implementation baseline](https://github.com/Roojool/5G-Bufferbloat-App/actions/runs/33964804525)
  succeeded. CI builds the debug app and instrumentation APK, runs JVM tests and
  lint; it does not execute physical network tests.
- The rebaseline's local four-task CI-equivalent check reported `BUILD SUCCESSFUL
  in 23s`, `82 actionable tasks: 7 executed, 75 up-to-date`. App/instrumentation
  assembly and JVM tests were UP-TO-DATE; no device tests ran. An SDK XML version
  mismatch warning was emitted. Full command/output accompanies the PR.
- Source-only, unreleased prototype. `versionName = "1.0.0"` is build metadata,
  not a release. No APK/AAB distribution gate has passed. Main requires the
  `Build, unit test, and lint` check, an approving review, and no force-push.

## Checked-in implementation and native state

- Compose UI, local preferences/profiles and allow/deny app routing settings,
  serialized VpnService lifecycle, runtime-state/notification scaffolding, and
  user-initiated structured local diagnostic sharing exist.
- JNI ABI v2 packages a C++17 **unavailable stub** for arm64-v8a, armeabi-v7a,
  and x86_64. Availability is false, feature bits are zero, valid start/update
  return unavailable, and traffic counters are zero. It opens no sockets and
  handles no TUN packets. No Go toolchain, gVisor revision, or adapter is pinned.
- The bridge checks availability, exact ABI and required features before the
  service creates a route. A valid-config start reports UNSUPPORTED; invalid
  settings (including initial zero limits) report ERROR first. Neither creates
  a route. Protection callbacks, opaque tokens, copied-input
  contracts, stop/join quarantine, and watchdog/process containment are safety
  scaffolding, not proven real-engine lifecycle behavior.
- Required feature bits cover IPv4 TCP, safe IPv4 UDP forwarding, protected
  sockets, safe stop, health events and flow metrics. These static declarations
  are not the planned runtime socket/device capability framework.
- Kotlin TokenBucket/CoDel/fair-queue references have JVM coverage but no traffic
  path. Capacity-sample calculation, network monitoring, and validation screens
  are scaffolding; automatic calibration and benchmark grades remain disabled.

## Proven and explicitly unproven

Source inspection and linked CI establish the checked-in stub/contract, build
packaging, and available JVM/static checks. They do not prove physical operation.
No physical feature-support or bufferbloat result is recorded in this rebaseline.

**Unproven/not implemented:** real IPv4/IPv6 forwarding; real-engine protection,
stop/recovery under traffic; TCP upload shaping; protected-socket TCP download
control; TCP_WINDOW_CLAMP/TCP_INFO availability and usefulness on phones;
adaptive autorate; independent measurement/persistence; handover/captive portals;
screen-off reliability; battery/thermal cost; device/carrier compatibility;
Radio Advisor. QUIC/UDP download shaping and exact band forcing are out of scope.

## Active architecture (decision, not shipped engine)

App TCP connects through TUN to a local gVisor/userspace TCP endpoint. A bounded,
ordered stream bridge connects that endpoint to a **separate protected
Android/Linux TCP socket**, which connects directly to the server. Each TCP leg
owns its own acknowledgements, windows and retransmissions. The Internet-facing
socket is the candidate TCP download-control point; changing only gVisor's
app-facing receive window does not set the server-facing window.

TCP upload uses bounded buffers, pacing, fair scheduling and backpressure.
Accepted stream bytes must be preserved; packet AQM needs explicit packet and
retransmission ownership. Adaptive delay/load autorate is preferred, with static
percentile/headroom settings only as a possible bounded seed/fallback. IPv6
precedes broad whole-device claims. Optional features follow runtime probes;
OEM profiles may optimize performance only after universal correctness.
See [Architecture](ARCHITECTURE.md) and [Design Decisions](DESIGN_DECISIONS.md).

## Next engineering gate and experiments

Next is **Stage 1 feasibility**, before expensive gVisor integration: design a
small internal stock-Android protected-socket harness, gather literal physical
evidence for socket options/observability and receive backpressure, and define
capability outcomes and fallback behavior. Do not activate the default stub.
The experiment must distinguish option acceptance from an effective advertised
window, server response, and loaded-latency benefit. A negative download result
can narrow the product to proven upload capabilities; it cannot justify a claim.

[Experiments](EXPERIMENTS.md) lists proposed F-01 (remote TCP receive control),
F-02 (TCP_INFO observability), F-03 (stream pacing/backpressure), F-04 (adaptive
autorate), and F-05 (early dual-stack forwarding). All are unrun/unverified.
Later gates require real forwarding before shaping, dual-stack evidence, and
the full [Testing](TESTING.md)/[Compatibility](COMPATIBILITY.md) release matrix.

## Current SDK and toolchain

| Item | Checked-in state |
|---|---|
| Android | minSdk 26; compileSdk/targetSdk **35** |
| Gradle / AGP | 8.9 with wrapper SHA-256 / 8.7.3 |
| Kotlin / Compose BOM | 2.1.0 / 2024.12.01 |
| Build JDK / bytecode | JDK 21 in CI and build instructions; Java/Kotlin target 17 |
| Native | CMake 3.22.1 selected by Gradle; C++17; three ABIs above |
| NDK | CI installs r28c (28.2.13676358); app Gradle does **not** pin ndkVersion. This local check's three CMake caches selected 27.0.12077973; CI installation is not proof of selection |
| Go / gVisor | Neither pinned nor integrated; enabling BUFFERBLOAT_WITH_GVISOR intentionally fails configuration |

Policy requirements are separate from this configuration. See
[Play Compliance](PLAY_COMPLIANCE.md) for current submission requirements and
the future implementation/release review needed; this PR does not upgrade SDKs.

## Documentation hierarchy

1. Owner's explicit task instructions and AGENTS.md govern work. Actual checked-in
   source/build files and literal evidence establish what exists and is proven;
   document and resolve contradictions rather than inventing behavior.
2. PROJECT_CONTEXT is the current session handoff; DESIGN_DECISIONS and
   ARCHITECTURE own the active design; ROADMAP owns sequence and gates.
3. TESTING and EXPERIMENTS own procedures/evidence; COMPATIBILITY owns scoped
   support; LIMITATIONS and README describe ordinary-user truth.
4. SOURCE_BUILD, PRIVACY, SECURITY, CONTRIBUTING and PLAY_COMPLIANCE own their
   respective build, data, reporting, contribution and dated policy details.
   RADIO_OPTIMIZATION, if created later, must remain subordinate to these bounds.
5. mobile-bufferbloat-shaper-plan.md is superseded history. The unmerged
   AI_PROJECT_HANDOFF proposal is not canonical; reconcile it with this hierarchy
   before any future merge. This snapshot never makes a pending PR part of main.
