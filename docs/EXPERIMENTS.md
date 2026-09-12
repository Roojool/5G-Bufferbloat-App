# Engineering Experiments

This is the home for literal feasibility, capability and performance evidence.
Proposals are not results. As of 2026-09-12, **no physical experiment results are
recorded here**. A separate debug-only F-01/F-02 no-route harness is implemented.
Source/CI/emulator checks do not verify physical socket-control efficacy.
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

## Experiment register — all physical outcomes unrun/unverified

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

**Commit/device/Android/SoC/OEM/network:** not assigned. **Raw/redacted result:**
none; not run. **Conclusion:** none. **Gate:** Stage 1, D-04 through D-06.

### F-02 — TCP_INFO capability and measurement meaning

**Hypothesis:** fields returned by TCP_INFO on the same protected socket provide
usable observations for diagnosis or autorate inputs on a stated Android build.

**Planned procedure:** record getsockopt status/errno, returned struct length and
only fields actually present; compare with independent endpoint timing under
upload, download, idle and changing load. Establish what each field measures and
when it is stale or inapplicable. In particular, do not assume a local
TCP sender RTT field measures downlink queueing. Reject absent/truncated data
safely; a successful call is not an accurate loaded-latency measurement.

**Commit/device/Android/SoC/OEM/network:** not assigned. **Raw/redacted result:**
none; not run. **Conclusion:** none. **Gate:** Stage 1, D-06 and D-13.

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

Debug implementation available; **Stage 1 UNPASSED**. All physical outcomes:
**UNVERIFIED — REQUIRES PHYSICAL EXPERIMENT**. The register above retains the
unrun hypotheses. F-03 is not implemented. Prompt 0's verdict was GO TO STAGE 1
WITH REQUIRED DESIGN CHANGES; this harness applies its evidence, configuration,
fresh-socket and ownership requirements.

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
The owner must enforce the proposed 1 GiB session allowance or a smaller chosen
allowance; there is no automated aggregate-session budget in this first harness.

Results distinguish compiled constants, set errno (null = not attempted), get
errno, requested value, actual readback and length. Baseline writes neither
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

### Owner procedure: Wi-Fi first, then cellular

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

Immediate minimum: one phone, Wi-Fi then cellular. Preferred early: Qualcomm and
MediaTek across two OEM/kernel contexts. Later: broader Android/carrier/family,
transition/captive-portal/screen-off/resource matrix. gVisor throughput/CPU/memory/
thermal screening belongs with a pinned minimal Stage 2 adapter. No physical
device/carrier success is recorded here.

### Literal implementation validation (2026-09-12; not physical efficacy)

Tested on `codex/phase1-protected-socket-harness`, based on
`dc8b83d499af5c1861ce10d55b055c6853b9968d`; the implementation commit and PR/CI
URL accompany the final task report. Local Windows/JDK 21 validation:

```text
gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
BUILD SUCCESSFUL in 16s
136 actionable tasks: 29 executed, 107 up-to-date
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
Time: 0.233
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
