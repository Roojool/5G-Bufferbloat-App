# Current Project Context

Snapshot: 2026-09-21. PR #10 final engineering review started at clean head
`9f0252dffb3e059edaf05e6e5d16d2c820a04e64` on `codex/stage1-physical-ready`.
Fetched current main remains `310a24856845cb7edfb7cca18bb2af0600d177bb`, the
merge of PR #9; PR #10 targets it without an unmerged dependency. The review
fixed completed-flow no-progress inflation and preserved collected worker
evidence when ADB result-file removal times out or fails. Both regressions
failed before the fixes. Final local checks passed: 64 JVM tests, 80 Python
tests, debug/instrumentation/release assembly, lint (0 errors, 66 existing
warnings), three-ABI NDK/packaging verification and 87 local Markdown links.
Literal output is in EXPERIMENTS; final head and CI accompany the task/PR report.
The debug F-03 adapter connects the existing
stream runner to controlled synthetic sources and protected Android/Linux TCP
sockets. Capability contracts, bounded socket observations and the existing
operator/endpoint workflow support a later frozen-commit physical campaign.
No ADB, attached device, emulator, capture or physical experiment was used in
this review. Ponytail Lite and the existing RTK configuration were unchanged.
**Stage 1 remains IN PROGRESS / UNPASSED.** Final commit/PR accompany the report.

## Reconciliation and next gate

Prompt 0 verdict: **GO TO STAGE 1 WITH REQUIRED DESIGN CHANGES**. Retain two
independent TCP legs; test receive control on the protected Android/Linux leg;
never drop accepted stream bytes; preserve independent upload capability;
keep IPv6 mandatory before broad/default/public activation. The reference
queues are not production-ready. The harness implements separate experiment
configuration and evidence layers, fresh socket baselines, length-safe native
observations, bounded ownership and explicit failure. The protected-socket
harness now includes a separate F-03 physical adapter and debug capability
contracts. It implements no packet AQM, autorate, gVisor, TUN forwarding or
production capability activation.

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
performed in this task.
Negative download evidence may narrow the product to independently proven
upload; upload itself is not proven by this task.

F-03 now has deterministic source-level feasibility for ordered byte integrity,
fixed per-flow/global allocation and occupancy bounds, byte-budget pacing,
deficit round-robin scheduling, partial writes/EAGAIN, read backpressure/resume,
cancellation, half-close, reset, stalls and abrupt rate reduction. Its new
physical adapter has source-tested ownership, receipts and failure semantics;
native calls and instrumentation compile, but have not executed in this task.
SO_SNDBUF request/readback, userspace occupancy and optional socket queue
observations are separate. Physical pacing/backpressure/fairness remains
unverified. The next unpassed gate is the controlled physical campaign and
reviewed go/narrow/defer decision, not production route activation.

## Checked-in implementation truth

- Ordinary app behavior remains the unavailable JNI ABI v2 stub: false
  availability, zero features/traffic, no packet handling. Valid configuration
  reaches UNSUPPORTED before TUN; invalid/initial zero limits reach ERROR first.
- Production service, native ABI/header, JNI bridge and stub are unchanged by
  the harness. No production route gate has passed. Existing lifetime/watchdog
  scaffolding is not real-engine evidence.
- A separate debug-only Activity, bound VpnService and JNI library support
  no-route protected-socket F-01/F-02/F-03 experiments. No Builder/establish call,
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
  commands over six reviewed presets. Every preflight/run invocation
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
  An explicit `--source-commit` requires exact clean HEAD even if main advances;
  APK provenance still must match. Cancellation attempts bounded retrieval of
  final worker accounting before cleanup. Missing cleanup evidence is unavailable.
  Download baseline p95 inflation below 20 ms is unsuitable/inconclusive;
  missing samples are inconclusive. No positive efficacy verdict is generated.
- `harness.stream.StreamPacingRunner` exists only in the debug source set. Its
  separate `StreamHarnessConfig` allocates fixed ring buffers, enforces total
  allocation/occupancy bounds, schedules flows with deficit round robin and
  gates every downstream write with an integer byte budget. Typed counters retain
  accepted/written/undelivered bytes, hashes, queue peaks, EAGAIN/partial writes,
  backpressure/resume, pacing/rate changes, progress and cleanup status. The
  synchronous runner retains no callback after return. Its queues contain
  already-accepted TCP stream bytes and are explicitly invalid for packet-drop
  or ECN AQM under D-09.
- `UploadRunner` reuses that runner; it owns up to four nonblocking sockets,
  requires protection before binding/connect, and fails if requested SO_SNDBUF
  cannot be verified within its configured readback ceiling. Graceful FIN follows
  drained queues; completion additionally requires exact receiver hashes and EOF.
  Failure requests abort/reset and reports accepted-but-unwritten bytes separately
  from kernel-accepted bytes whose delivery remains unconfirmed. One worker closes
  every FD once. No other app's TCP stream is captured.
- Debug `Stage1Capabilities` separates mandatory contracts from optional probes,
  uses UNKNOWN/AVAILABLE/UNAVAILABLE and fixed redacted reasons, rejects stale or
  changed Android/kernel/ABI/transport/IP-family scope, and has no production
  activation caller. Forwarding/lifecycle contracts remain UNKNOWN; API observations
  do not establish efficacy. Optional observation failure leaves its values null.
- Production directional configuration still requires both positive limits.
  Its independent capability model remains Stage 3 work. Production shaping
  queues, calibration and validation remain disconnected scaffolding.

## Build and evidence

Min SDK 26; compile/target SDK 35; Gradle 8.9; AGP 8.7.3; Kotlin 2.1.0;
Compose BOM 2024.12.01; build JDK 21 and bytecode 17; CMake 3.22.1/C++17.
Gradle now pins NDK r28c **28.2.13676358**, for arm64-v8a, armeabi-v7a and
x86_64. Actual local/CI selection is checked from CMakeCache plus the selected
NDK's source.properties, independently of merely installing that package.
`tools/verify_harness_build.py` also checks debug/release native packaging.
No Go/gVisor version is pinned or integrated; its old CMake switch still fails.

The task runs required assembly/JVM/lint checks, release packaging regression,
endpoint/orchestrator/operator and F-03 stream unit tests, and instrumentation compilation.
Literal counts and executed versus cached task evidence are recorded in
EXPERIMENTS and the task/PR report. No build, emulator or option success passes a physical gate.
The owner phone reports and batches establish only their recorded Wi-Fi
acceptance, integrity and scoped sender-visible transport observations; none
passes Stage 1. The reviewed wifi-efficacy session physically exercised the
operator workflow and produced complete captures plus retained partial load-ping
replies for its stated setup; broader host/device compatibility remains
unverified. The operator implementation commit has successful CI. Live protection
requires `Build, unit test, and lint`, zero approving reviews and no force-push;
this task changes no protection and does not merge its PR. Starting main CI failed
before compilation because SDK setup requested the removed `tools` package;
the workflow now explicitly requests `platform-tools` and keeps all required checks.

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
