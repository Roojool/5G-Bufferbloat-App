# Engineering Experiments

This is the home for literal feasibility, capability and performance evidence.
Proposals are not results. As of 2026-09-14, two first-screen Wi-Fi runs, one
ten-run physical wifi-screen batch and one two-run physical wifi-efficacy pair
are recorded for the debug-only F-01/F-02 no-route harness. Sender captures now
provide strong positive evidence of receive-window control for one Android
12/API 31 device on Wi-Fi. They establish no bufferbloat benefit, cellular
efficacy, general compatibility or production readiness; Stage 1 remains UNPASSED.
Use [Testing](TESTING.md) for evidence categories and
[Compatibility](COMPATIBILITY.md) for narrowly scoped support claims.

## Record rules

- Give each run a stable ID and exact implementation commit/build gate. Repeat
  runs get their own IDs; retain failures and contrary observations.
- Use status `proposed/unrun`, `run/unverified`, `verified for stated scope`,
  `refuted for stated scope`, or `inconclusive`. Verification requires evidence
  and review; it never means universal device support.
- Preserve raw output locally outside version control. Publish only consented,
  redacted text/artifacts with a stated redaction method. Do not commit captures,
  payloads, addresses, credentials, SIM/device identifiers, or precise location.
  Keep failures/error codes and measurement units needed to interpret a result.
- An accepted socket option proves API acceptance only. Separate that from
  readback, observed transport behavior, correctness, and useful latency control.
- Probe only owned/authorized devices and test endpoints. State traffic cost,
  stop/recovery procedure and endpoint privacy before a future test. No project
  relay or default-route activation is authorized merely by listing a proposal.

## Per-run template

```text
Experiment ID / run ID / date:
Status: proposed/unrun | run/unverified | verified for stated scope |
        refuted for stated scope | inconclusive
Hypothesis (falsifiable; success/failure criteria fixed before running):
Implementation commit (full SHA), branch, dirty diff and build variant/gate:
Device model:
Android version/build and kernel version:
SoC / OEM / relevant firmware (no identifiers):
Network: Wi-Fi/cellular, optional carrier, IPv4/IPv6, broad conditions:
Toolchain/ABI and measurement tools/versions:
Capability probe: requested option/value, return/errno, readback/length,
                  supported fields, interpretation and safe fallback:
Procedure: commands, endpoint/method, topology, shaper state, controls,
           load direction, duration, repetitions, stop and recovery:
Raw result: local artifact path + hash (private; do not publish captures):
Redacted result: literal stdout/stderr, tables, units, sample counts,
                 artifact link/hash and redactions applied:
Conclusion: supported/refuted/inconclusive; alternative explanations:
Verified/unverified scope: exact feature, device, OS, network and limits:
Reviewer/date and evidence link:
Next action / related decision / roadmap gate:
```

## Experiment register

### F-01 — protected remote-facing TCP receive control

**Hypothesis:** a stock-Android protected TCP socket can exert useful, bounded
download backpressure, with an optional TCP_WINDOW_CLAMP adjustment, without
corruption, stalls or disproportionate throughput loss. It is not yet known
whether this improves loaded latency on physical cellular networks.

**Planned procedure:** use a small internal harness before gVisor integration.
Record the actual protected remote socket and protect-before-connect result.
Compare unmodified baseline, bounded read cadence/receive buffering, and clamp
variants one variable at a time; record setsockopt/getsockopt returns and errno,
window scaling, values before/after connect and during transfer, actual server
traffic/window observations where an authorized endpoint permits them, idle and
loaded latency, throughput, CPU/memory, stalls, zero-window recovery and hashes.
Repeat across Wi-Fi/cellular and OEM/kernel combinations. Keep a failed probe's
fallback explicit. Window/RTT arithmetic alone is not a measurement.

**Current evidence:** the first screen, ten-run batch and wifi-efficacy records
below are verified only for their stated one-phone Wi-Fi scopes. The reviewed
wifi-efficacy pair supplies strong positive evidence that SO_RCVBUF=65536 on the
protected socket changed the sender-visible advertised receive window while
preserving 128 MiB integrity. Its one candidate run produced only a modest
throughput reduction. Repeatability, useful throttling, loaded-latency benefit,
cellular efficacy and general compatibility remain unverified. **Gate:** Stage 1,
D-04 through D-06; not passed.

### F-02 — TCP_INFO capability and measurement meaning

**Hypothesis:** fields returned by TCP_INFO on the same protected socket provide
usable observations for diagnosis or autorate inputs on a stated Android build.

**Planned procedure:** record getsockopt status/errno, returned struct length and
only fields actually present; compare with independent endpoint timing under
upload, download, idle and changing load. Establish what each field measures and
when it is stale or inapplicable. In particular, do not assume a local
TCP sender RTT field measures downlink queueing. Reject absent/truncated data
safely; a successful call is not an accurate loaded-latency measurement.

**Current evidence:** the first two Wi-Fi runs below returned TCP_INFO
successfully with errno 0 and length 232. The later wifi-efficacy baseline and
candidate made 60/63 successful calls respectively with returned length 192 and
the report's twelve compiled fields available. TCP_INFO RTT remains separate
from the independent RTT records and is not treated as one-way or queue delay.
**Gate:** Stage 1, D-06 and D-13; not passed.

### F-03 — bounded TCP upload pacing and fairness

**Hypothesis:** bounded ordered buffers and paced fair writes propagate
backpressure to the app-facing endpoint while preserving every accepted byte.

**Planned procedure:** start with controlled socket/stream tests; later repeat
through real TUN forwarding. Exercise slow/non-reading peers, partial writes,
EAGAIN, cancellation, half-close/reset, many bulk flows plus short flows, and
abrupt rate reductions. Record userspace/kernel queue bounds, delay, throughput,
per-flow progress, hashes and cleanup. Any packet AQM candidate must separately
identify queue units, ACK/acceptance boundary, sender-retained retransmission
copy and recovery evidence. No drop operation on accepted stream chunks.

**Commit/device/Android/SoC/OEM/network:** not assigned. **Raw/redacted result:**
none; not run. **Conclusion:** none. **Gate:** feasibility then Stage 3, D-07–D-09.

### F-04 — adaptive delay/load autorate

**Hypothesis:** bounded delay/load feedback responds more usefully to capacity
changes than only a fixed percentile × headroom cap, without oscillation or
learning a progressively lower cap from its own shaping.

**Planned procedure:** define independent delay/load methods and user-controlled
probe budget; compare unshaped, static and adaptive runs under step/ramp capacity
changes, idle periods, endpoint failures, stale samples and handover. Record
chosen limits separately from observed throughput, delay distribution, settling
time, oscillation, fairness, data/battery cost and safe fallback. Simulation may
screen a design; physical tests are required for benefit claims.

**Commit/device/Android/SoC/OEM/network:** not assigned. **Raw/redacted result:**
none; not run. **Conclusion:** none. **Gate:** Stage 3, D-10.

### F-05 — dual-stack forwarding and capability fallback

**Hypothesis:** the future engine can preserve IPv4/IPv6 TCP, UDP/QUIC, resolver
policy and safe recovery without optional socket features or OEM profiles.

**Planned procedure:** after Stage 3's internal upload experiments, test dual-stack and
IPv6-only destinations, DNS A/AAAA, MTU/fragmentation/error behavior, transitions,
missing optional probes, failed protection and stop/join. Verify IPv6 bypass
explicitly in earlier IPv4-only internal builds; bypass is not shaped support.
Record transfer checksums and literal failure/recovery observations. Extend upload
shaping/measurement evidence to the full IP-family scope. This Stage 4 evidence
is mandatory before broad whole-device support or public/default-route release
claims, including for upload-only scope; no IPv4-only experiment waives it.

**Commit/device/Android/SoC/OEM/network:** not assigned. **Raw/redacted result:**
none; not run. **Conclusion:** none. **Gate:** Stage 4, D-11–D-13.

## Implemented F-01/F-02 harness (2026-09-12)

Debug implementation available; **Stage 1 UNPASSED**. The first narrow physical
Wi-Fi acceptance/readback and integrity screens are recorded below. Transport
effect, recovery under deliberate stalls, physical benefit, cellular efficacy
and general compatibility remain **UNVERIFIED — REQUIRES PHYSICAL EXPERIMENT**.
F-03 is not implemented. Prompt 0's verdict was GO TO STAGE 1 WITH REQUIRED
DESIGN CHANGES; this harness applies its evidence, configuration, fresh-socket
and ownership requirements.

### Gate, ownership and bounds

`app/src/debug` contains a manually opened Activity, bound VpnService, independent
ExperimentConfig, single-worker runner and separate SocketNative JNI surface.
`native/harness` builds only in debug. No Builder/establish, TUN, routes, gVisor,
production ShaperConfig or ABI v2 changes exist in the harness. Ordinary app
start remains unavailable. Release excludes the harness components/library.

The worker creates a nonblocking CLOEXEC socket, requires protect(fd), optionally
binds to an explicitly selected Android Network, then connects. Protect failure
closes before bind/connect. Numeric input avoids DNS lookup and is not exported.
Native calls retain no callbacks/references. Poll is bounded to 50 ms and checks
cancellation between operations. Only the worker closes its FD, exactly once;
close is never retried after EINTR. Service teardown closes the protection gate,
cancels and waits two seconds. An unexpectedly stuck platform call is retained
and cleanupJoined remains false; it does not justify concurrent FD close or
callback-state destruction. Pathological OS/Binder hangs are not proven absent
by emulator success. No production traffic depends on this service.

Each Run uses a fresh socket. There is no clamp-zero restoration operation.
Requests: buffer/clamp 1..4 MiB; read chunks 1..16384 bytes; cadence 0..2000 ms;
at most eight ordered changes. Retention: 256 TCP_INFO samples plus omitted count,
at most twenty option records and nine cadence events. Duration 1..120 seconds,
default 60; no-progress timeout 0.5..30 seconds, default 10. Expected data
1..256 MiB, plus at most one excess-detection byte. Hashing is incremental SHA-256.
The manual UI still requires the owner to enforce the proposed 1 GiB session
allowance or a smaller chosen allowance. The host batch automation below rejects
plans above its per-run/aggregate byte and time bounds before sending data.

Results distinguish compiled constants, set/get errno (null = not attempted),
requested value, actual readback and length. An absent constant never fabricates
a syscall errno; incomplete integer readbacks remain unavailable. Baseline writes neither
receive option. Failed optional sets do not interrupt safe socket operation,
but invalidate that control variant. COMPLETE means expected count plus EOF,
not independently verified integrity. DEADLINE, STALL_TIMEOUT, EARLY_EOF,
EXCESS_DATA and native errno are explicit. Timestamps, byte/hash counters,
no-progress gaps and recovery counts after one-second gaps are observations.
Transport effect, integrity/recovery conclusion and physical benefit remain
UNVERIFIED in every export. Endpoint addresses/ports, DNS names, network handles,
payload and free-form exception text are excluded.

### F-02 fields and meaning

Storage is zero-initialized and socklen_t reset each call. Each field is decoded
only when its compiled offset plus size fits the actual returned length. Short
prefixes yield null, not zero. Nonzero errno makes every field unavailable; the
length variable on failure is not proof that any field was returned. There is
no exact sizeof requirement. These twelve fields compile with pinned r28c;
newer tails and compiler-dependent bitfields are deliberately not exposed.

| Export | Meaning / limitation |
|---|---|
| state | Local OS TCP connection state |
| rto_us | Local retransmission timeout, microseconds |
| snd_mss_bytes, rcv_mss_bytes | Local MSS observations |
| unacked_segments | Local sender outstanding segments |
| retrans_segments | Outstanding retransmitted segments, not cumulative loss |
| rtt_us, rttvar_us | Local sender RTT estimate/variation; may be stale or handshake dominated during download |
| snd_cwnd_segments | Local sender congestion window, not remote download sender state |
| rcv_rtt_us | Kernel receive estimator, not independently measured one-way queue delay |
| rcv_space_bytes | Receive-space/autotuning observation, not current wire window |
| total_retrans_segments | Local cumulative retransmissions, not the remote sender's count |

TCP_INFO is observation only, never a shaping actuator or guaranteed cellular
queue-delay probe. Compare independent timing and sender evidence.
[Linux TCP interface](https://man7.org/linux/man-pages/man7/tcp.7.html),
[Linux implementation](https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c),
[AOSP header](https://github.com/aosp-mirror/platform_bionic/blob/master/libc/kernel/uapi/linux/tcp.h).
Upstream interfaces do not establish OEM kernel behavior.

### First owner physical Wi-Fi screen (reported 2026-09-12)

These runs used implementation commit
`0e00eb62e7bb04dc7922d633c0add15940dc7257` from
`codex/phase1-protected-socket-harness`, a real arm64 Android phone, an explicitly
selected Wi-Fi Network, and an owner-controlled LAN endpoint. The device model,
Android/kernel version and SoC/OEM were not supplied, so the evidence cannot be
generalized to a device family or Android release. No address, device identifier,
location, capture or other private artifact is committed. The owner supplied the
redacted structured results below; the run date was not separately supplied.

Endpoint contract for both completed runs: 16777216 bytes and SHA-256
`287507f403176f1f5b22b9a4d9cb49f7d7f88ac19e406b5ae87ce109564846bd`.

| Run ID | Variant | Literal redacted result |
|---|---|---|
| F-01-WIFI-BASELINE-01 | Untouched baseline | COMPLETE; 16777216 bytes; SHA-256 exact match; elapsed_ms=4187; longest_no_progress_ms=48; close_errno=0; SO_RCVBUF before/after connect=4194304/4194304; TCP_WINDOW_CLAMP before/after connect=0/3144280; TCP_INFO calls errno=0, returned_length=232 |
| F-01-WIFI-RCVBUF-01 | SO_RCVBUF requested 65536 | COMPLETE; 16777216 bytes; SHA-256 exact match; elapsed_ms=4201; longest_no_progress_ms=43; close_errno=0; setsockopt errno=0; getsockopt errno=0; SO_RCVBUF before/after connect=131072/131072; TCP_WINDOW_CLAMP before/after connect=0/96856; TCP_INFO calls errno=0, returned_length=232 |

Status for both runs: **verified for stated scope** after review of the owner's
redacted report. The baseline protected-socket transfer/integrity screen passed
on this setup. The 65536 receive-buffer request was accepted, doubled on readback
to 131072, and preserved exact byte/hash integrity. TCP_INFO was available;
length 232 covered every compiled field exported by this harness. This is one
phone, one Wi-Fi topology and one run per variant. The near-equal elapsed times
are observations, not a throughput-control result.

Evidence boundaries:

| Layer | Conclusion from these runs |
|---|---|
| 1 API/constant | Available in this build on this phone for the calls exercised |
| 2 Acceptance | SO_RCVBUF=65536 setsockopt succeeded with errno 0; no clamp set was attempted |
| 3 Readback | SO_RCVBUF returned 131072; literal clamp and TCP_INFO readbacks above succeeded |
| 4 Transport effect | **UNVERIFIED**: no sender capture/window evidence; clamp readback is not advertised-window proof |
| 4 Sender response | **UNVERIFIED**: single short runs and elapsed times do not establish useful throttling |
| 5 Integrity/recovery | Exact count/hash and COMPLETE establish integrity for these transfers; deliberate stall, zero-window and recovery remain **UNVERIFIED** |
| 6 Physical benefit | **UNVERIFIED**: no loaded-latency comparison; no cellular run |

An earlier setup attempt on a different hotspot is retained as
F-01-WIFI-SETUP-01 with status **inconclusive**: connect failed before transfer
with CONNECT_FAILED and errno 113. Subsequent LAN success identifies
connectivity/topology as the supported explanation; the failure is not evidence
for or against F-01 control efficacy. No endpoint details are retained.

The later wifi-efficacy record below completes the proposed long fresh-socket
baseline/SO_RCVBUF=65536 pair with sender capture and independent RTT timing.
Its next follow-up is repetition with randomized order under demonstrated
baseline loaded-latency inflation, while retaining count/hash and capture checks,
before moving to the documented randomized cellular pairs.

### Automated owner procedure: Wi-Fi first, then cellular

`tools/stage1_batch.py` drives one fresh protected socket per manifest run through
a debug-only ADB Activity. It never clicks UI coordinates, prepares consent,
establishes a VPN route or calls production code. It fetches origin/main, requires
a clean checkout containing it, records the exact source SHA and installed APK
hash, discovers one authorized ADB target, and collects Android release/API,
kernel and ABI without model/serial/fingerprint identifiers. Android resolves
the requested non-VPN Wi-Fi or cellular Network during the probe and again for
every run; an active matching Network is selected, otherwise exactly one match
or an explicit private `--network-ordinal` is required.

One-time prerequisites: JDK 21/Android SDK/ADB/Python 3, one USB-debug-authorized
physical phone, no competing/lockdown VPN, an owner-controlled numeric endpoint
address reachable over the requested phone transport, and an owner firewall rule
for the chosen test port. Install debug, open the manual Activity once and choose
Prepare if the batch reports CONSENT_REQUIRED:

```powershell
adb shell am start -n com.bufferbloatshaper/.harness.HarnessActivity
```

After that consent and Wi-Fi connection, use the stateless operator. Every
preflight/run command requires the endpoint, local bind address and port again,
plus explicit confirmation for that invocation. Preflight installs/verifies the
current build and probes the current device/transport without transferring
experiment data. Run rediscovers them and starts/stops the endpoint for every
variant; it does not reuse preflight selections:

```powershell
$env:STAGE1_ENDPOINT_IP = "<numeric address of this owner-controlled host>"
py -3 tools\operator.py preflight --preset wifi-screen --transport wifi --endpoint-address $env:STAGE1_ENDPOINT_IP --bind-address $env:STAGE1_ENDPOINT_IP --port 39001 --confirm-endpoint-bind --build --install
py -3 tools\operator.py run --preset wifi-screen --transport wifi --endpoint-address $env:STAGE1_ENDPOINT_IP --bind-address $env:STAGE1_ENDPOINT_IP --port 39001 --confirm-endpoint-bind
py -3 tools\operator.py report --session output\stage1\<session-directory>
```

The operator exposes only `wifi-screen`, `wifi-efficacy` and `cellular-paired`;
custom manifests remain a lower-level engineering interface. Zero attached
authorized devices blocks. Multiple devices require `--serial` on that same
preflight/run invocation. An ambiguous current Android transport requires a
current `--network-ordinal`; neither selection carries forward. Cellular runs
require a globally routable numeric endpoint and reject private-LAN endpoint
assumptions, though a separately confirmed local bind address may sit behind an
owner-managed mapping. Reports contain only allowlisted status/evidence fields,
end with `AWAITING_REVIEWER_CONCLUSION`, and never infer Stage pass/fail or efficacy.

Operator exit codes are: 0 command completed, 2 blocked prerequisite (also
argparse input errors), 3 failed/inconclusive experiment, 70 tooling or evidence-
data failure, and 130 owner abort. Exit 0 means the command/bounded runs completed;
it is not a Stage or efficacy verdict. Raw/private data and every failure remain
under ignored output; generated operator reports are redacted.

On Windows, the normal command automatically attempts private sender capture.
Discovery probes `PATH` first, then honors `--tshark-path`, then checks standard
Wireshark installation locations including Program Files. The orchestrator maps
the selected local bind address to its Windows adapter and matches that adapter
to the stable name reported by `tshark -D`; it never stores or depends on the
transient numeric index. Use `--tshark-interface "<stable name>"` only when that
mapping is ambiguous, `--tshark-path "<executable>"` for a nonstandard install,
or `--no-tshark` to disable the optional attempt. Paths and interface identifiers
remain only in ignored private records.

TShark starts before the endpoint and uses a capture filter restricted to the
owned endpoint host and test TCP port. Missing/unstartable TShark or failed/
ambiguous interface resolution records a clear SKIPPED reason and leaves sender-
window evidence UNVERIFIED without changing transfer conclusions. Normal cleanup
now sends CTRL_BREAK_EVENT to a new Windows process group (SIGINT on Unix), waits
up to ten seconds for TShark/dumpcap to finish, then uses terminate/five-second
wait/kill only as fallbacks. The shutdown method and signal failure are recorded;
neither a signal nor process exit alone proves file completeness. Capture also
has duration (run allowance plus 30 seconds) and 524288 kB file autostop bounds.
The Windows cleanup handler requests capture-child shutdown before exiting;
see [TShark 4.6.8 source](https://github.com/wireshark/wireshark/blob/v4.6.8/tshark.c),
[TShark autostop](https://www.wireshark.org/docs/man-pages/tshark), and
[Python process-group signals](https://docs.python.org/3/library/subprocess.html).
The manifest
records independent adb-shell ping round-trip collection before and during load;
its method and sample summary are retained separately. TCP_INFO RTT never
substitutes for it, and neither method is interpreted as one-way queue delay.

Independent ping records `requested_samples`, `observed_replies`, observed
`min_ms`/`avg_ms`/`max_ms`, `statistics_source`, process `returncode` and an explicit
`completion_reason`. Normal completion with usable summary statistics remains
RECORDED / NORMAL_COMPLETION. When run-result cleanup intentionally stops a
still-running load ping, usable replies remain RECORDED_PARTIAL / TRANSFER_ENDED;
reply-line RTTs are parsed even if ping never emitted its final summary. This
applies to both `wifi-efficacy` and `cellular-paired` without preset changes.
Valid summary statistics/counts take precedence; otherwise only exact numeric
reply RTTs are counted (malformed values and `time<...` bounds are not samples).
`transmitted` and `received` are included only if actually reported in a packet
summary; absent summaries never imply transmitted count, loss or missing samples.
No usable replies remain UNAVAILABLE. Unexpected ping failure, host failure and
owner abort remain UNAVAILABLE with PING_FAILED, RUN_FAILED or OWNER_ABORTED
reasons; any parsed observations are retained. These summaries describe only
the collected interval, not a full-duration trial or a latency-benefit result.
Raw RTT text stays private; previous physical records are not rewritten.

Tracked presets:

| Preset | Contents and policy |
|---|---|
| `wifi-screen` | Baseline; isolated SO_RCVBUF 16384/65536/262144; isolated clamp 16384/65536/262144; cadence 5/50 ms; deliberate cadence withholding/recovery. Ten stable runs, continue after a failed run while retaining it; no combined “best” candidate. |
| `wifi-efficacy` | Longer baseline and explicitly provisional receive-buffer candidate, with longer independent RTT collection. Copy/edit a manifest outside tracked source and pass `--manifest` to change candidate, bytes or duration within bounds. Capture analysis remains external. |
| `cellular-paired` | Five seeded, reproducible randomized baseline/candidate pairs. The candidate and seed are manifest inputs, not a product choice. Stops after a failed run by default; `--continue-on-failure` is explicit. No cellular result is implied. |

Every session checkpoints private input, endpoint output, phone records, RTT text
and optional pcap under ignored `output/stage1/<session>/raw/`. The separate
`redacted-summary.json` excludes endpoints, ports, Network handles/ordinals,
TShark executable/capture-interface/adapter identifiers, capture paths, precise
location, credentials and device/ADB IDs.
Option acceptance/readback, sender transport effect, integrity/recovery and
physical benefit are separate fields. Exact endpoint+phone byte/hash agreement
can mark only that transfer's integrity. Post-capture analysis can establish
observations for captured packets only; physical benefit always remains
UNVERIFIED until reviewed analysis supplies it.

Manifest budgets cannot exceed 256 MiB/120 seconds per run or 1 GiB/one hour per
batch and may be lower. Ctrl+C or creating `ABORT` in the printed session folder
signals cancellation; the existing worker retains sole FD ownership and closes
it. Missing phone/build match/consent/network stops preflight. Endpoint or run
failure is retained, then stop/continue follows the manifest/CLI policy. Raw
artifacts are never deleted to hide contrary evidence.

### Offline sender transport analysis (no experiment rerun)

The batch now invokes `tools/stage1_capture.py` after capture cleanup. To process
an existing session without ADB, consent, endpoint startup, or live capture:

```powershell
py -3 tools\stage1_capture.py --session output\stage1\20260913-100214-wifi-screen-d2a05965
```

The session must already exist below ignored `output/stage1`. Discovery shares
the batch's PATH/Windows fallback and optional `--tshark-path`. The analyzer uses
the recorded local endpoint bind address and port to identify the sender, then
requires a single connection/peer. Multiple connections are SKIPPED; packet
order never chooses direction. Only selected TCP/header fields are requested
with name resolution disabled. Field TSVs and TShark diagnostics stay private.
No original capture, manifest, phone record or summary is repaired or overwritten.
The new `redacted-transport-summary.json` is separate from original evidence;
each run also receives `transport-derived.private.json`. Capture/analyzer hashes
and TShark version identify the input and decoder. No address, MAC, interface,
port, capture path, ADB serial or arbitrary diagnostic string is exported.

Derived data includes both SYN window-scale offers; receiver post-SYN ACK raw
window and scaled rwnd min/median/max; zero-window frames and reopening episodes;
TShark sender probe/retransmission frame counts and peak bytes in flight; and
250 ms bins of sender payload bytes, receiver windows, ACK progression and
advertised right edge. SYN windows are excluded from the window distribution.
Scaling needs both captured handshake offers; an absent offer in a captured
handshake means no scaling, while absent/truncated handshake or unsupported
field means unavailable. ACK progression starts after the server ISN and may
include one FIN sequence byte. Payload sums include retransmissions; they are
not unique delivered bytes. Window median is packet-sampled, not time-weighted.
[RFC 7323](https://www.rfc-editor.org/rfc/rfc7323.html)

`READABLE_TO_EOF` describes file parsing only. Overall COMPLETE additionally needs
the requested fields, usable handshake, payload ACK coverage and a FIN/RST;
PARTIAL retains readable observations when any check is missing, the capture is
truncated, packets are snaplen-truncated, or analysis exceeds its bounds.
SKIPPED covers absent tool/capture, unsupported mandatory direction/window fields,
ambiguous connections and unavailable analysis. Limits: 1 GiB input, 60-second
packet dissection, 200000 decoded rows, 256 MiB field output and 600-second
timeline. Missing values are null; supported but unobserved event counts can be
zero only for the captured portion. A zero count never proves no event occurred
outside it. Readable files and COMPLETE analysis do not establish losslessness.

Bytes-in-flight, probes and retransmissions are TShark analysis observations;
capture loss, ordering and host offload can affect them. They are not direct
kernel counters or radio-airtime evidence. A reopened window followed by sender
data and advancing ACKs supports recovery for that interval, without proving
repeatability. Planned cadence changes are retained with an explicit separate-
clock caveat. TCP_INFO RTT and independent ping remain separate; this analyzer
does not calculate latency benefit or promote any other product capability.
[Wireshark TCP analysis semantics](https://www.wireshark.org/docs/wsug_html_chunked/ChAdvTCPAnalysis.html)

### F-01-WIFI-BATCH-01 — owner batch, offline review 2026-09-13

**Status: verified for stated scope; incomplete capture coverage retained.**
The owner ran one physical wifi-screen batch on implementation
`d2a0596538a284ca1740c5ab787dde1f2bddd340`, debug/no-route, explicit Wi-Fi,
arm64-v8a, Android 12/API 31, kernel 4.14.190-perf+, owned IPv4 LAN endpoint.
No model, SoC, carrier, identifiers or precise location are inferred. The current
task only processed those existing files with TShark 4.6.8; it ran no physical
experiment. The run IDs below are the stored IDs without their common
`wifi-screen-` prefix. All ten phone/endpoint records match 16777216 bytes and
SHA-256 `287507f403176f1f5b22b9a4d9cb49f7d7f88ac19e406b5ae87ce109564846bd`.

| Run | Parse / analysis | Scaled rwnd min / median / max (bytes) | Zero-window / probe / retransmission frames |
|---|---|---|---|
| baseline | readable / PARTIAL | 1499136 / 1572864 / 1572864 | 0 / 0 / 0 |
| rcvbuf-16384 | truncated / PARTIAL | 0 / 21720 / 23360 | 38 / 0 / 2 |
| rcvbuf-65536 | truncated / PARTIAL | 21680 / 96000 / 96000 | 0 / 0 / 0 |
| rcvbuf-262144 | readable / PARTIAL | 87600 / 390912 / 390912 | 0 / 0 / 0 |
| clamp-16384 | readable / PARTIAL | 16060 / 16060 / 16060 | 0 / 0 / 0 |
| clamp-65536 | readable / PARTIAL | 64240 / 65536 / 65536 | 0 / 0 / 0 |
| clamp-262144 | readable / PARTIAL | 87600 / 262144 / 262144 | 0 / 0 / 0 |
| cadence-5ms | readable / COMPLETE | 0 / 49152 / 1572864 | 32 / 0 / 1 |
| cadence-50ms | readable / COMPLETE | 0 / 53248 / 1572864 | 94 / 30 / 87 |
| read-withholding-recovery | readable / COMPLETE | 0 / 45056 / 1572864 | 58 / 3 / 5 |

All ten contain both scale offers. Receiver shifts, in table order, are
12, 0, 1, 3, 0, 1, 3, 12, 12, 12. Eight captures parse to EOF; receive-buffer
16384 and 65536 have a damaged final block and retain only the readable prefix.
The first seven runs lack the final expected payload ACK, even where a FIN/RST
is present, so their analysis remains PARTIAL. This does not negate separately
verified phone/endpoint stream integrity. Counts above describe captured frames
only, and no PCAP repair or deletion was performed.

In the withholding run, phone cadence events occurred at 5025 ms (2000 ms cadence)
and 7025 ms (5 ms cadence). Capture-relative observations show zero window at
5.475132 s, positive window at 7.176171 s, sender data at 7.176282 s and ACK
advance at 7.188721 s. This supports a withholding/reopen/recovery observation in
this run; the clocks are not synchronized precisely. All capture/decoder hashes
and the full numeric progression remain in the ignored derived session records.

These captures establish sender-visible window differences and scoped recovery
observations beyond option acceptance/readback. One fixed-order batch does not
prove repeatable causal throughput control, bufferbloat benefit, cellular efficacy,
general OEM support, integrated forwarding or production readiness. Those remain
UNVERIFIED; Stage 1 is UNPASSED. Next, review these retained traces and independent
RTT adequacy before planning a longer paired Wi-Fi benefit experiment. No repeat
experiment was authorized or run in this follow-up.

### F-01-WIFI-EFFICACY-01 — reviewed physical pair (2026-09-14)

**Status: verified for the stated sender-visible control and integrity scope;
physical benefit inconclusive.** The owner ran the reviewed `wifi-efficacy`
preset using implementation commit
`c52d5d8cdfa9b8bc15774954a0abd857312de80c`, the debug/no-route protected-socket
harness, an explicitly selected Wi-Fi Network and an owner-controlled endpoint.
The broad redacted runtime scope is physical Android 12 / API 31 / arm64-v8a /
kernel family 4.14. No model, serial, SSID, address, port, MAC, interface,
Network handle, capture path, carrier or location is retained.

| Observation | Baseline | SO_RCVBUF=65536 candidate |
|---|---:|---:|
| Outcome | COMPLETE | COMPLETE |
| Bytes | 134217728 | 134217728 |
| Integrity | VERIFIED_FOR_THIS_TRANSFER | VERIFIED_FOR_THIS_TRANSFER |
| Elapsed ms | 15094 | 15868 |
| SO_RCVBUF setsockopt errno | not requested | 0 |
| SO_RCVBUF readback | 2097152 baseline observation | 131072 |
| Capture analysis | COMPLETE | COMPLETE |
| Scaled advertised rwnd min/median/max bytes | 966656 / 1032192 / 1572864 | 8 / 83328 / 96000 |
| Sender bytes-in-flight peak | 80320 | 81768 |
| Zero-window frames | 0 | 0 |
| Zero-window-probe frames | 0 | 0 |
| Retransmission observations | 7 | 9 |
| Idle RTT | RECORDED; requested 30; observed 30; min/avg/max 3.946 / 17.067 / 38.406 ms; NORMAL_COMPLETION | RECORDED; requested 30; observed 30; min/avg/max 3.433 / 15.499 / 115.4 ms; NORMAL_COMPLETION |
| Loaded RTT | RECORDED_PARTIAL; requested 350; observed 80; min/avg/max 3.02 / 7.56675 / 20.2 ms; TRANSFER_ENDED | RECORDED_PARTIAL; requested 350; observed 84; min/avg/max 2.58 / 6.957976190476191 / 16.0 ms; TRANSFER_ENDED |

Arithmetic derived from bytes and elapsed time, not a separate measurement:
baseline throughput was approximately **71.136996 Mbit/s**, candidate throughput
approximately **67.667118 Mbit/s**, and the candidate was approximately
**4.877741% lower**. These are one fixed-order baseline/candidate observations.

**Reviewer conclusion:** this is strong positive evidence for sender-visible
protected-socket receive-window control on this one-device Wi-Fi scope, and
long-transfer integrity was preserved. The single candidate run had only a
modest throughput reduction. Physical bufferbloat benefit remains
**INCONCLUSIVE / UNVERIFIED**: this fixed-order pair did not demonstrate baseline
loaded-latency inflation, loaded RTT collection ended with each transfer, and
there is no repeated/randomized efficacy evidence. It provides no cellular or
general compatibility claim and no integrated-TUN or production evidence.
Stage 1 remains **UNPASSED**. The next useful experiment is a predeclared,
randomized repeated Wi-Fi baseline/candidate series under a topology that first
demonstrates baseline loaded-latency inflation and keeps independent RTT
collection comparable through the load and recovery interval.

### Manual fallback procedure

1. Use an owned phone and authorized directly reachable test machine. Choose a
   byte/time allowance before testing. Do not prepare alongside a needed VPN or
   lockdown: consent may replace the currently prepared VPN app. No route will
   be established. Start with a small transfer on stable Wi-Fi.
2. Build/install debug per SOURCE_BUILD and open the internal Activity:

   ```text
   adb shell am start -n com.bufferbloatshaper/.harness.HarnessActivity
   ```

3. On the owner endpoint, assign local TEST_BIND and TEST_PORT variables (do not
   commit values), then run this standard-library-only utility:

   ```text
   python tools/socket_endpoint.py --bind "$TEST_BIND" --port "$TEST_PORT" --bytes 16777216 --connections 1 --timeout 120
   ```

   It sends N synthetic bytes (byte i = i modulo 251), then EOF. Output contains
   expected hash/count, timestamped send-accepted counts and final hash/count or
   redacted errno. Send acceptance is not wire departure/peer delivery. Default
   one connection, maximum twenty sequential connections; no application input
   relay or proxy exists. Close owner firewall exposure after testing.
4. Enter numeric address/port (not exported), select Default or an explicit
   non-VPN Network, Prepare and accept Android consent. Network choices are a
   snapshot; reopen after network changes. Input JSON, Run fresh socket, wait for
   cleanup and Save redacted result locally. Hiding the UI cancels current work.
5. Compare phone COMPLETE byte count/hash against the endpoint expected values.
   Record exact build commit, phone/Android/kernel/ABI, chosen transport, family,
   run ID, tools and broad conditions separately. Keep failures and partial runs.
6. After Wi-Fi integrity/recovery screens, use a cellular-reachable endpoint and
   disable Wi-Fi or explicitly select cellular. A Wi-Fi-only endpoint cannot
   establish cellular behavior. Record relevant IPv4/IPv6/NAT64 context.

Baseline JSON (match endpoint byte count):

```json
{"expectedBytes":16777216,"durationMs":60000,"stallTimeoutMs":10000,"readBytes":16384,"cadenceMs":0}
```

Add the listed fields to that baseline, on fresh sockets:

| Variant | Added/changed JSON fields |
|---|---|
| Baseline | None |
| Buffer only | `"receiveBuffer":16384`, then 65536 and 262144 in separate runs |
| Clamp only | `"clamp":16384`, then 65536 and 262144 in separate runs |
| Read cadence | `"cadenceMs":5` or 50; choose readBytes near desired bytes/s × cadence/1000 within bounds |
| Combined | Selected buffer + clamp + cadence, after isolated screens |
| Dynamic clamp | `"clamp":262144,"changes":[{"atMs":15000,"clamp":16384},{"atMs":30000,"clamp":262144}]` |
| Dynamic buffer | Equivalent receiveBuffer changes on a new socket |
| Dynamic reads | `"changes":[{"atMs":15000,"cadenceMs":50},{"atMs":30000,"cadenceMs":0}]` |
| Read withholding/recovery | `"changes":[{"atMs":10000,"cadenceMs":2000},{"atMs":12000,"cadenceMs":0}]` |

Dynamic atMs is after connect; exported timestamps are after run start. Combined
initial settings apply buffer before clamp. Reversed-order restoration,
concurrent flows and automatic rate selection are not implemented here. Cadence
is a bounded max-chunk read experiment, not F-03 pacing or an exact rate guarantee.
Short reads/scheduling alter achieved rate. Observe zero-window occurrence at
the sender; two-second withholding does not prove it occurred.

Ensure data lasts long enough to reach each dynamic phase. A fast 16 MiB transfer
may finish before 15 seconds. For full trials plan 30 seconds idle timing, ten
seconds warm-up and sixty seconds scored load, with sufficient data within the
run allowance and at most 120 seconds duration. If the allowance cannot sustain
the interval, record a short screen only, never a full trial.

### External transport evidence and proposed screens

No packet capture is built into Android. At the authorized sender, capture only
the test connection with an owner-installed tool such as tcpdump, starting before
SYN. Keep capture/address artifacts private. Inspect SYN window scale, subsequent
receiver ACK windows/right edge, zero windows/persist and reopening. Optional
server ss/TCP_INFO observations supplement capture; the server's send state
diagnoses download rwnd limitation. Readback alone cannot establish this.

For independent RTT, the owner may use adb shell ping to the authorized endpoint
if permitted (for example 0.2-second intervals, 150 idle samples and 350 load
samples), retaining command, loss, timestamps and units. Shell ICMP support is
not raw-ICMP support in this app. If ICMP is blocked/deprioritized, use an
authorized separate echo tool and state processing/path limitations. Neither
RTT method isolates one-way/downlink queue delay. This task adds no probe server.

Fix criteria before runs; retain all outcomes. After Wi-Fi screens, use at least
five randomized paired baseline/candidate cellular comparisons. Proposed Prompt
0 screening criteria below are not universal product thresholds:

| Evidence layer | Record / proposed interpretation |
|---|---|
| 1 API/constant | Compiled availability, not running-kernel support |
| 2 Acceptance | Literal set result/errno; failure invalidates that control variant |
| 3 Readback | Value/length/errno; SO_RCVBUF request need not equal return |
| 4 Window effect | Repeatable scaled-window change at sender after outstanding credit; absent capture leaves UNVERIFIED |
| 4 Sender response | Backlogged sender and repeatable restriction, 20% decrease as an initial screen, with transport evidence |
| 5 Integrity/recovery | Matching count/hash and EOF; progress within ten seconds after withholding on a verified healthy path; failure is operational-screen failure, not necessarily TCP nonconformance |
| 6 Benefit | Baseline added p95 RTT at least 20 ms; median paired reduction at least 20% AND 10 ms, at least 70% baseline goodput, improvement in at least four of five pairs |

The earlier proposed +/-15% target-rate screen applies only when a meaningful
rate target exists; chunk/cadence is not a pacing guarantee. No baseline bloat
means benefit is inconclusive. Option success, arithmetic or emulator success
cannot pass layers 4–6. Clamp failure may leave buffering/read experiments useful;
download failure may narrow future scope to independently proven upload. Never
compensate with UDP dropping, TLS interception or a relay.

Immediate next: repeat the Wi-Fi efficacy pair with randomized order only after
confirming baseline loaded-latency inflation, then proceed to cellular. Preferred early: Qualcomm and
MediaTek across two OEM/kernel contexts. Later: broader Android/carrier/family,
transition/captive-portal/screen-off/resource matrix. gVisor throughput/CPU/memory/
thermal screening belongs with a pinned minimal Stage 2 adapter. The recorded
one-device Wi-Fi results are not a device-family, carrier or efficacy success.

### Literal wifi-efficacy evidence-documentation validation (2026-09-14)

This documentation-only branch starts at current main
`c52d5d8cdfa9b8bc15774954a0abd857312de80c`. The review read the retained
redacted operator report only; it did not run ADB, an endpoint, capture, or any
physical experiment and did not copy raw/private artifacts.

```text
Initial Gradle attempt with shell-default Java 25.0.2:
FAILURE: Build failed with an exception.
What went wrong: 25.0.2
BUILD FAILED in 7s

JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
OpenJDK 21.0.10
gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
BUILD SUCCESSFUL in 24s
136 actionable tasks: 16 executed, 120 up-to-date
JVM XML: tests=44 failures=0 errors=0 skipped=0
Lint errors=0
Lint warnings=66

Markdown local links: checked=65 broken=0
Markdown fragment links: 0
git diff --check: PASS
Non-documentation changed files: 0
Tracked output files: 0

py -3 tools/verify_harness_build.py
debug/release selected NDK 28.2.13676358 on all three ABIs
debug harness=ON; release harness=OFF; both packaging/manifest gates PASS
```

The successful rerun used the documented JDK 21 after the unsupported shell
default caused the retained pre-task failure. Instrumentation was compiled, not
executed; JVM tests and most build work were UP-TO-DATE. These source checks do
not add physical evidence. The final commit, PR and CI accompany the task report.

### Literal operator-report follow-up validation (2026-09-14; no physical run)

This focused PR #7 follow-up changes only `tools/operator.py`, its host tests and
canonical documentation. It adds allowlisted broad runtime scope, fixed
session-relative redacted artifact filenames, and direct proof that each run
delegates only its current invocation arguments. It reads no retained physical
session and invokes no ADB, endpoint or capture path.

```text
py -3 -m unittest discover -s tools -p 'test_operator.py' -v
Ran 16 tests in 0.541s
OK

py -3 -m unittest discover -s tools -p 'test_*.py'
Ran 68 tests in 3.862s
OK

py -3 tools/verify_harness_build.py
debug/release selected NDK 28.2.13676358 on all three ABIs
debug harness=ON; release harness=OFF; both packaging/manifest gates PASS
```

An initial `py -3 -m unittest tools.test_operator -v` invocation failed during
module import because `tools` is not configured as a Python package. No test or
experiment ran in that attempt. The repository-supported discovery invocation
above executed all focused tests successfully. `py_compile` and `git diff
--check` also passed. The final commit/CI result accompanies the task report.
Stage 1 remains UNPASSED.

### Literal stateless-operator validation (2026-09-14; no physical run)

Implemented on `codex/stage1-operator` from current main
`ce4d9b8553eb6d658802a7846dc60234d0ab6c39` (merged PR #6). The final commit,
PR and CI accompany the task report. No ADB experiment command, endpoint,
capture or existing physical session was run/read/changed. Local Windows/JDK 21:

```text
py -3 -m unittest discover -s tools -p 'test_*.py' -v
Ran 65 tests in 3.917s
OK

gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
BUILD SUCCESSFUL in 1m 6s
136 actionable tasks: 21 executed, 115 up-to-date
JVM XML: tests=44 failures=0 errors=0 skipped=0
Lint errors=0
Lint warnings=66

py -3 tools/verify_harness_build.py
debug/release selected NDK 28.2.13676358 on all three ABIs
debug harness=ON; release harness=OFF; both packaging/manifest gates PASS

py -3 tools/operator.py --help
exit=0; commands={preflight,run,report}

operator run without --confirm-endpoint-bind: exit=2
operator cellular run with private endpoint: exit=2
Markdown local links: checked=88 broken=0
App/native delta: EMPTY
Tracked output files: 0
Builder.establish additions: 0
gVisor dependency additions: 0
```

The first verifier invocation failed before verification because the requested
`tools/operator.py` filename shadowed Python's standard-library `operator`
module when a sibling tool imported `pathlib`. The module-load compatibility
shim corrected that concrete issue; the subsequent verifier and direct tool
help invocations passed.

All 65 Python tests executed; thirteen are focused operator tests using mocks,
temporary directories and synthetic summaries. JVM tests, most build work and
instrumentation compilation were UP-TO-DATE; 21 Gradle tasks executed, including
native configure/build work, manifest processing, lint models and release
packaging. The existing SDK XML version warning appeared and did not fail the
build. No instrumentation test executed. Source/build success establishes only
the stateless wrapper, report redaction and retained debug/release gates. Device,
transport and endpoint rediscovery remain unverified on a live owner session;
Stage 1 remains UNPASSED.

### Literal load-RTT retention validation (2026-09-13; no physical run)

This host-only follow-up to PR #6 preserves replies when transfer completion
stops the load ping before its requested count. All fixtures are synthetic;
existing physical records and `output/` are untouched. Local Windows/JDK 21:

```text
py -3 -m unittest discover -s tools -p 'test_*.py' -v
Ran 52 tests in 2.715s
OK

gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
BUILD SUCCESSFUL in 28s
136 actionable tasks: 16 executed, 120 up-to-date
JVM XML: tests=44 failures=0 errors=0 skipped=0
Lint errors=0
Lint warnings=66
Markdown local links: checked=88 broken=0
App/native delta: EMPTY
Tracked output files: 0
```

The 52 Python tests executed, including twelve new RTT tests. JVM tests, lint
analysis and instrumentation compilation were UP-TO-DATE; no device or
instrumentation test executed. Gradle emitted the existing SDK XML version
warning. `py -3 tools/verify_harness_build.py` passed both packaging/manifest
gates and verified selected NDK 28.2.13676358 on every debug/release ABI, harness
ON only in debug. `git diff --check` passed. Both longer presets retain partial
replies in mocked cleanup tests; physical retention and latency benefit remain
unverified. No existing session was reanalyzed. Stage 1 remains UNPASSED; final
commit/PR and CI results accompany the task report.

### Literal capture-analysis follow-up validation (2026-09-13; offline only)

```text
py -3 -m unittest discover -s tools -p 'test_*.py' -v
Ran 40 tests in 2.604s
OK

py -3 tools/stage1_capture.py --session output/stage1/20260913-100214-wifi-screen-d2a05965
Offline transport analysis: {"COMPLETE": 3, "PARTIAL": 7}
Physical benefit: UNVERIFIED — REQUIRES PHYSICAL EXPERIMENT
Original session files unchanged: 86
Transfer integrity verified: 10
Readable capture files: 8
TShark versions: ['4.6.8']

gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
BUILD SUCCESSFUL in 23s
136 actionable tasks: 16 executed, 120 up-to-date
Markdown local links: checked=88 broken=0
App/native delta: EMPTY
Tracked output files: 0
```

The 86 original-session file hashes matched before/after offline processing.
Python tests executed; JVM tests/lint analysis and instrumentation compilation
were up-to-date in this host-only follow-up. No instrumentation/device test or
live capture was executed. `verify_harness_build.py` passed debug/release gates
with NDK 28.2.13676358 on all ABIs and the harness ON only in debug. The final
commit/PR and CI results accompany the task report. Live graceful shutdown
effectiveness still requires evidence from a later authorized physical capture.

### Literal Windows capture-discovery follow-up validation (2026-09-12; no capture)

Updated open PR #6 on `codex/stage1-batch-automation`; the follow-up commit and
CI URL accompany the task report. The implementation/test/docs delta contains no
app or native file change from the prior PR head. Local Windows/JDK 21 validation:

```text
python -m unittest discover -s tools -p "test_*.py" -v
Ran 18 tests in 2.042s
OK

capture_setup_status=READY
tshark_source=WINDOWS_STANDARD_INSTALL
interface_source=WINDOWS_BIND_ADAPTER_MATCH

gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
BUILD SUCCESSFUL in 23s
136 actionable tasks: 16 executed, 120 up-to-date
JVM XML: tests=44 failures=0 errors=0 skipped=0
Lint errors=0
Lint warnings=66
```

The discovery-only probe used the selected local Wi-Fi bind address and installed
TShark, but started no capture, endpoint or phone experiment. It establishes host
discovery/interface-resolution behavior only. Sender-window evidence and all
physical transport/latency conclusions remain **UNVERIFIED — REQUIRES PHYSICAL
EXPERIMENT**. `verify_harness_build.py` again selected NDK 28.2.13676358 for all
three debug/release ABIs, found the harness ON only in debug, and passed both
packaging/manifest gates.

### Literal batch-automation validation (2026-09-12; no physical run)

Implemented on `codex/stage1-batch-automation` from current main
`2f59669389be2bd89fa1c29f655327d47fa7d1e2`; final commit/PR and CI accompany
the task report. The first Gradle attempt selected local Java 25.0.2 and failed
before compilation with `What went wrong: 25.0.2`. Validation then explicitly
selected the repository-required Android Studio JBR 21.0.10. This failed attempt
is toolchain evidence, not a source failure or physical result.

```text
python -m unittest discover -s tools -p "test_*.py" -v
Ran 12 tests in 2.524s
OK

gradlew :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 49s
22 actionable tasks: 22 executed
JVM XML: tests=44 failures=0 errors=0 skipped=0

gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
BUILD SUCCESSFUL in 1m
136 actionable tasks: 25 executed, 111 up-to-date
Lint errors: 0
Lint warnings: 66
```

`verify_harness_build.py` again reported NDK 28.2.13676358 for all three debug
and release ABIs, harness ON only in debug, and both packaging/manifest gates
PASS. Instrumentation was compiled, not executed. `adb devices -l` listed no
device, so the ADB command path, consent/network resolution, endpoint orchestration,
TShark/RTT collection and physical results are **UNVERIFIED — REQUIRES PHYSICAL
EXPERIMENT**. No new physical F-01/F-02 outcome is recorded by this task.

### Literal implementation validation (2026-09-12; not physical efficacy)

Tested on `codex/phase1-protected-socket-harness`, based on
`dc8b83d499af5c1861ce10d55b055c6853b9968d`; the implementation commit and PR/CI
URL accompany the final task report. Local Windows/JDK 21 validation:

```text
gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
BUILD SUCCESSFUL in 15s
136 actionable tasks: 33 executed, 103 up-to-date
JVM XML totals: tests=42, failures=0, errors=0, skipped=0
New ExperimentTest: 10 tests, failures=0
python -m unittest discover -s tools -p test_socket_endpoint.py -v
Ran 4 tests in 0.009s
OK
```

`verify_harness_build.py` reported selected NDK 28.2.13676358 for debug and
release on arm64-v8a, armeabi-v7a and x86_64. Debug harness=ON, release=OFF;
both packaging/manifest gates PASS. Its selected caches were Debug/f5m6j3v3
and RelWithDebInfo/5e3z3o6y; older 27.x caches were not used. The initial local
build emitted SDK XML version warnings and failed lint on second-service
metadata; metadata was corrected and the required checks subsequently passed.
Remaining debug UI/deprecation lint warnings are not suppressed by a baseline.

An earlier Gradle connected run executed five tests successfully (before adding
the prepared-service test): BUILD SUCCESSFUL in 1m 35s, 69 actionable tasks:
7 executed, 62 up-to-date. Final APKs were installed and all six instrumentation
tests executed directly after accepting the emulator's Android VPN consent:

```text
am instrument -w com.bufferbloatshaper.test/androidx.test.runner.AndroidJUnitRunner
com.bufferbloatshaper.harness.SocketHarnessInstrumentationTest:.....
com.bufferbloatshaper.nativeengine.NativeEngineInstrumentationTest:.
Time: 0.234
OK (6 tests)
```

Environment: owned local AVD codex_plugin_api36, API 36, x86_64, kernel
6.6.66-android15-8-gd0c43a640eab-ab13812146. No physical device was attached.
The emulator displayed a System UI ANR dialog during initial UI preparation;
closing that dialog allowed normal harness consent and tests. This is recorded
as emulator setup behavior, not hidden as a physical compatibility success.
Real-service loopback protection/cancellation/no-VPN-Network and native stub
unavailability were executed checks. Active-route exclusion, carrier behavior,
effective window control, integrity under physical conditions and latency
benefit remain **UNVERIFIED — REQUIRES PHYSICAL EXPERIMENT**.
