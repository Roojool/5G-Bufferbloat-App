# Engineering Experiments

This is the home for literal feasibility, capability and performance evidence.
Proposals are not results. As of 2026-09-11, **no physical experiment results are
recorded here**. Existing source/CI checks do not verify the proposed mechanisms.
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

## Proposed experiment register — all unrun/unverified

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
none; not run. **Conclusion:** none. **Gate:** feasibility then Stage 4, D-07–D-09.

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
none; not run. **Conclusion:** none. **Gate:** Stage 4, D-10.

### F-05 — early dual-stack forwarding and capability fallback

**Hypothesis:** the future engine can preserve IPv4/IPv6 TCP, UDP/QUIC, resolver
policy and safe recovery without optional socket features or OEM profiles.

**Planned procedure:** after real forwarding exists, test dual-stack and
IPv6-only destinations, DNS A/AAAA, MTU/fragmentation/error behavior, transitions,
missing optional probes, failed protection and stop/join. Verify IPv6 bypass
explicitly in earlier IPv4-only internal builds; bypass is not shaped support.
Record transfer checksums and literal failure/recovery observations.

**Commit/device/Android/SoC/OEM/network:** not assigned. **Raw/redacted result:**
none; not run. **Conclusion:** none. **Gate:** Stage 3, D-11–D-13.
