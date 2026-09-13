# Current Project Context

Snapshot: 2026-09-14. Implementation commit
`c52d5d8cdfa9b8bc15774954a0abd857312de80c` is the exact source from which the
owner's recorded physical `wifi-efficacy` pair was produced. This
documentation-only review changes no app, native or tooling source and commits
no raw/private evidence or identifiers.

## Reconciliation and next gate

Prompt 0 verdict: **GO TO STAGE 1 WITH REQUIRED DESIGN CHANGES**. Retain two
independent TCP legs; test receive control on the protected Android/Linux leg;
never drop accepted stream bytes; preserve independent upload capability;
keep IPv6 mandatory before broad/default/public activation. The reference
queues are not production-ready. The harness implements separate experiment
configuration and evidence layers, fresh socket baselines, length-safe native
observations, bounded ownership and explicit failure. It implements no F-03,
AQM, autorate, gVisor, TUN forwarding or production capability framework.

**Stage 1 remains UNPASSED.** One real arm64 phone on an explicitly selected
Wi-Fi Network completed the baseline and SO_RCVBUF=65536 transfers with exact
byte/hash integrity. The buffer request was accepted and read back as 131072;
TCP_INFO calls succeeded with length 232 and the documented fields available.
A subsequent ten-run physical wifi-screen batch on `d2a0596538a284ca1740c5ab787dde1f2bddd340`
has exact count/hash integrity on all runs. Offline sender analysis establishes
window observations and scoped zero-window/reopen/recovery. Eight captures parse
to EOF, two are truncated; only three include the full payload ACK/end coverage.
The later 128 MiB baseline/SO_RCVBUF=65536 `wifi-efficacy` pair on physical
Android 12/API 31/arm64-v8a/kernel family 4.14 completed with integrity and
complete captures. Its scaled advertised receive-window distributions provide
strong positive sender-visible control evidence for this one-device Wi-Fi scope;
the candidate's arithmetic-derived throughput was about 4.88% lower. Physical
bufferbloat benefit remains inconclusive/unverified because baseline loaded RTT
did not inflate, loaded RTT records ended partially with the transfers, and the
fixed-order pair was not repeated/randomized. Cellular efficacy and general OEM
support remain **UNVERIFIED — REQUIRES PHYSICAL EXPERIMENT**. Next: run a
predeclared randomized repeated Wi-Fi pair only after confirming a topology with
baseline loaded-latency inflation, then proceed to cellular. No physical run was
repeated in this documentation task.
Negative download evidence may narrow the product to independently proven
upload; upload itself is not proven by this task.

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
  Its own output does not establish sender transport effect. Independent variants
  use new sockets; separate sender-capture analysis can add scoped observations.
- F-02 extracts twelve compiled/length-present TCP_INFO fields into typed,
  redacted values. It neither controls rate nor identifies one-way radio delay.
- Owner-run Python endpoint generates synthetic deterministic bytes only. No
  project endpoint, application relay/proxy, in-app packet capture, TLS interception,
  telemetry or arbitrary payload inspection is added.
- A host Python orchestrator and debug-only ADB Activity command surface can run
  manifest-defined fresh-socket experiments sequentially after manual consent.
  They re-resolve an explicit Wi-Fi/cellular Network per run, manage the owner
  endpoint, enforce byte/time plans, retain failures under ignored `output/`,
  and create a separate redacted summary. Optional sender TShark capture probes
  PATH first, honors an explicit executable override, checks standard Windows
  Wireshark locations, and maps the selected bind address's adapter to a stable
  TShark interface name. Ambiguous/unavailable capture remains skipped and
  unverified. Capture and independent adb-shell ping observations never promote
  efficacy automatically, and interface identifiers/paths remain private.
  TShark now receives a graceful control signal and bounded flush wait before
  terminate/kill fallback. Post-capture/offline analysis derives header-only
  window, ACK/data and recovery observations with explicit partial-file/coverage
  status; original physical records remain unchanged and output stays ignored.
  Independent ping now retains normal completion as RECORDED and observed
  replies after intentional transfer-end cleanup as RECORDED_PARTIAL, even
  without a final summary. Requested/observed counts, observed min/avg/max and
  completion reason are explicit; missing summary counts/loss are not inferred.
  No usable replies remain UNAVAILABLE. TCP_INFO RTT stays separate and no
  latency-benefit conclusion is generated.
- `tools/operator.py` provides stateless `preflight`, `run` and `report`
  commands over the three reviewed presets. Every preflight/run invocation
  requires current endpoint/bind/port confirmation, discovers the currently
  attached device, and probes the requested Android transport; run then delegates
  to the existing batch path, which re-resolves Network per fresh socket. It
  retains existing bounds, abort/failure records and private/redacted storage.
  Cellular use rejects a private/non-global endpoint. Reports use a strict
  allowlist, make no Stage/efficacy conclusion, and end awaiting reviewer judgment.
  They include only broad target kind, numeric Android release/API level,
  allowlisted ABI and numeric kernel family from the already-redacted device
  context, plus the two fixed session-relative redacted artifact filenames.
  Arbitrary runtime strings and raw/private paths are never rendered.
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
endpoint/orchestrator/operator unit tests, and instrumentation compilation.
Literal counts and executed versus cached task evidence are recorded in
EXPERIMENTS and the task/PR report. No build, emulator or option success passes a physical gate.
The owner phone reports and batches establish only their recorded Wi-Fi
acceptance, integrity and scoped sender-visible transport observations; none
passes Stage 1. The reviewed wifi-efficacy session physically exercised the
operator workflow and produced complete captures plus retained partial load-ping
replies for its stated setup; broader host/device compatibility remains
unverified. The PR #7 implementation commit has successful CI. Live protection
requires `Build, unit test, and lint`, zero approving reviews and no force-push;
this task changes no protection and does not merge its PR.

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
