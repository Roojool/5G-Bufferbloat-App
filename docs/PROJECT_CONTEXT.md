# Current Project Context

Snapshot: 2026-09-11, after the documentation rebaseline merged. Current remote
main (`origin/main`) is `529dfdd06c156fe6889edf19333b759031a629b1`, the PR #3 merge
commit. This hygiene follow-up uses `codex/repo-hygiene-after-rebaseline`, created
directly from that main. It changes only root-local artifact ignore rules and
repository-state documentation; app, native, Gradle and CI behavior are unchanged.
Refresh this file after engineering work.

## Repository and release state

- [PR #3](https://github.com/Roojool/5G-Bufferbloat-App/pull/3) is **MERGED** into
  main at `529dfdd06c156fe6889edf19333b759031a629b1` (2026-09-11 17:38:21 UTC).
  Its canonical documentation, including AGENTS.md, is now checked-in main truth.
  The rebaseline changed documentation, not the implementation.
- [PR #1: Clean repository hygiene and track AGENTS.md](https://github.com/Roojool/5G-Bufferbloat-App/pull/1)
  and [PR #2: Add AI project handoff and future-phase prompt guide](https://github.com/Roojool/5G-Bufferbloat-App/pull/2)
  are both **CLOSED / UNMERGED** (`mergedAt: null`), closed on 2026-09-11.
  PR #3's adopted AGENTS.md supersedes PR #1's proposal. This hygiene follow-up
  recreates only PR #1's useful root-scoped artifact ignore rules; it does not
  restore that PR's AGENTS.md. PR #2's AI_PROJECT_HANDOFF sequence remains
  superseded by PROJECT_CONTEXT + DESIGN_DECISIONS + ROADMAP and must not be
  reintroduced unchanged. Historical PRs are not implementation evidence.
- Live remote heads confirm `codex/repo-hygiene-cleanup` and
  `codex/ai-project-handoff` are deleted. `codex/docs-rebaseline` still exists at
  `ee18af1b3c63bda1eae11d1865421ba1e93b328f`; the older
  `codex/public-release-foundation` branch also remains. This task deletes no
  branches and modifies neither closed PR.
- No PRs were open at inspection. The hygiene PR from
  `codex/repo-hygiene-after-rebaseline` is the only current proposed change;
  its exact commit, PR URL and fresh validation accompany the PR report.
  Tracked files were clean before this follow-up. Existing `.codex-artifacts/`,
  `.codex-finalizer/` and `output/` were untracked local/generated material with
  no tracked Android/native build-input references. This branch ignores those
  root directories; it neither commits nor deletes their contents and adopts
  none of them as engineering evidence.
- [Main CI for the merged rebaseline](https://github.com/Roojool/5G-Bufferbloat-App/actions/runs/34628820771)
  is completed/success at `529dfdd06c156fe6889edf19333b759031a629b1`. CI builds the
  debug app and instrumentation APK, runs JVM tests and lint; it does not execute
  physical network tests. This main result is separate from hygiene-PR checks.
- Historical evidence: the initial rebaseline's local four-task CI-equivalent check reported `BUILD SUCCESSFUL
  in 23s`, `82 actionable tasks: 7 executed, 75 up-to-date`. App/instrumentation
  assembly and JVM tests were UP-TO-DATE; no device tests ran. An SDK XML version
  mismatch warning was emitted. Its PR CI executed all 82 tasks successfully.
  These are earlier results, not this hygiene follow-up's fresh validation; dated
  command/output for each pass accompanies the PR.
- Source-only, unreleased prototype. `versionName = "1.0.0"` is build metadata,
  not a release. No APK/AAB distribution gate has passed. Live main protection
  requires `Build, unit test, and lint`, has **0 required approving reviews**,
  and disallows force-push. The earlier one-review statement was stale; this
  task changes no protection settings and does not merge its own PR.

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
No physical networking experiment has passed; no physical feature-support or
bufferbloat result is recorded. No traffic forwarding or shaping exists.

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
percentile/headroom settings only as a possible bounded seed/fallback. Stage 3
tests the primary upload-bufferbloat value internally before Stage 4's full
dual-stack/DNS/transition integration. IPv6 remains mandatory before broad
whole-device support or public/default-route release claims. Optional features follow runtime probes;
OEM profiles may optimize performance only after universal correctness.
See [Architecture](ARCHITECTURE.md) and [Design Decisions](DESIGN_DECISIONS.md).

Future configuration/runtime state must track each direction independently:
proven upload shaping must work when TCP download control is unavailable;
download settings require verified runtime capability; bidirectional mode needs
both directions supported. Configured download limits are not evidence of
effective control. This requires later code: today's validation requires both
positive limits and implements neither directional capability gating nor shaping.

## Next engineering gate and experiments

Next is **Stage 1 — protected-socket / transport feasibility**, before expensive
gVisor integration: design a
small internal stock-Android protected-socket harness, gather literal physical
evidence for socket options/observability and receive backpressure, and define
capability outcomes and fallback behavior. This hygiene task does not begin
Stage 1 or activate the default stub.
The experiment must distinguish option acceptance from an effective advertised
window, server response, and loaded-latency benefit. A negative download result
can narrow the product to proven upload capabilities; it cannot justify a claim.

[Experiments](EXPERIMENTS.md) lists proposed F-01 (remote TCP receive control),
F-02 (TCP_INFO observability), F-03 (stream pacing/backpressure), F-04 (adaptive
autorate), and F-05 (dual-stack forwarding). All are unrun/unverified.
After Stage 1, Stage 2 establishes dependencies and real unshaped IPv4 TCP/UDP
forwarding; Stage 3 proves internal upload shaping/measurement/adaptive autorate;
Stage 4 establishes IPv6/dual-stack, DNS and network-transition correctness;
Stage 5 adds optional protected-socket TCP download control; Stage 6 requires full
validation/release evidence and separate default activation review. Stage 7 tunes
OEM/SoC/model performance, and Stage 8 considers Radio Advisor/mapping. Internal
IPv4 upload experiments do not waive Stage 4 or the full
[Testing](TESTING.md)/[Compatibility](COMPATIBILITY.md) release matrix.

## Current SDK and toolchain

| Item | Checked-in state |
|---|---|
| Android | minSdk 26; compileSdk/targetSdk **35** |
| Gradle / AGP | 8.9 with wrapper SHA-256 / 8.7.3 |
| Kotlin / Compose BOM | 2.1.0 / 2024.12.01 |
| Build JDK / bytecode | JDK 21 in CI and build instructions; Java/Kotlin target 17 |
| Native | CMake 3.22.1 selected by Gradle; C++17; three ABIs above |
| NDK | Intended/recommended: r28c (28.2.13676358), installed by CI. `app/build.gradle.kts` does **not** pin `ndkVersion`; local Gradle/CMake selection can differ. The recorded local build selected 27.0.12077973 on all three ABIs. Installing r28c does not prove selection; a later implementation/build task must pin and verify the actual NDK |
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
5. mobile-bufferbloat-shaper-plan.md is superseded history. PR #2's closed/unmerged
   AI_PROJECT_HANDOFF sequence is superseded by PROJECT_CONTEXT,
   DESIGN_DECISIONS and ROADMAP; do not reintroduce it unchanged. This snapshot never
   makes a pending PR part of main.
