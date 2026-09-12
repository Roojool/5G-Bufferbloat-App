# Current Project Context

Snapshot: 2026-09-12. This Stage 1 task starts from current main
`dc8b83d499af5c1861ce10d55b055c6853b9968d` (merged PR #4), on
`codex/phase1-protected-socket-harness`. PR #3's rebaseline and PR #4's hygiene
are merged; older closed/unmerged proposals are not dependencies. The stale
local main was not used as the implementation base. Final commit/PR and CI
results accompany the task report; this snapshot does not claim a pending PR
has merged. Unrelated ignored local artifacts were preserved.

## Reconciliation and next gate

Prompt 0 verdict: **GO TO STAGE 1 WITH REQUIRED DESIGN CHANGES**. Retain two
independent TCP legs; test receive control on the protected Android/Linux leg;
never drop accepted stream bytes; preserve independent upload capability;
keep IPv6 mandatory before broad/default/public activation. The reference
queues are not production-ready. The harness implements separate experiment
configuration and evidence layers, fresh socket baselines, length-safe native
observations, bounded ownership and explicit failure. It implements no F-03,
AQM, autorate, gVisor, TUN forwarding or production capability framework.

**Stage 1 remains UNPASSED.** F-01/F-02 physical window, sender-response,
recovery and loaded-latency evidence is **UNVERIFIED — REQUIRES PHYSICAL
EXPERIMENT**. Next: owner Wi-Fi screens, then cellular paired runs following
[Experiments](EXPERIMENTS.md). Negative download evidence may narrow the product
to independently proven upload; upload itself is not proven by this task.

## Checked-in implementation truth

- Ordinary app behavior remains the unavailable JNI ABI v2 stub: false
  availability, zero features/traffic, no packet handling. Valid configuration
  reaches UNSUPPORTED before TUN; invalid/initial zero limits reach ERROR first.
- Production service, native ABI/header, JNI bridge and stub are unchanged by
  the harness. No production route gate has passed. Existing lifetime/watchdog
  scaffolding is not real-engine evidence.
- A separate debug-only Activity, bound VpnService and JNI library now run
  no-route protected-socket F-01/F-02 experiments. No Builder/establish call,
  routes, DNS lookup, normal launcher entry, release component/library, or
  persistent native callback exists in that harness.
- Native sockets are nonblocking and worker-owned. Protect succeeds before
  optional explicit Network binding and connect. Cancellation uses bounded
  polling; worker finally closes exactly once. Result storage is bounded.
- F-01 records option availability, acceptance/errno and actual readback,
  timestamps, read cadence, bytes, SHA-256 and no-progress/recovery observations.
  Higher evidence layers remain UNVERIFIED. Independent variants use new sockets.
- F-02 extracts twelve compiled/length-present TCP_INFO fields into typed,
  redacted values. It neither controls rate nor identifies one-way radio delay.
- Owner-run Python endpoint generates synthetic deterministic bytes only. No
  project endpoint, application relay/proxy, packet capture, TLS interception,
  telemetry or arbitrary payload inspection is added.
- Production directional configuration still requires both positive limits.
  Its independent capability model remains Stage 3 work. Kotlin queues,
  calibration and validation remain disconnected scaffolding.

## Build and evidence

Min SDK 26; compile/target SDK 35; Gradle 8.9; AGP 8.7.3; Kotlin 2.1.0;
Compose BOM 2024.12.01; build JDK 21 and bytecode 17; CMake 3.22.1/C++17.
Gradle now pins NDK r28c **28.2.13676358**, for arm64-v8a, armeabi-v7a and
x86_64. Actual local/CI selection is checked from CMakeCache plus the selected
NDK's source.properties, independently of merely installing that package.
`tools/verify_harness_build.py` also checks debug/release native packaging.
No Go/gVisor version is pinned or integrated; its old CMake switch still fails.

The task runs required assembly/JVM/lint checks, release packaging regression,
endpoint unit tests, and emulator loopback/API tests. Literal counts, executed
versus cached tasks and exact emulator scope are recorded in EXPERIMENTS and
the task/PR report. No build, emulator or option success passes a physical gate.
Main at task start had successful CI and no open PRs. Live protection required
`Build, unit test, and lint`, zero approving reviews, no force-push; this task
changes no protection and does not merge its PR.

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
