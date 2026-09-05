# Bufferbloat Shaper — Corrected Status & Final Roadmap

This supersedes the priority ordering in the last status report, not the original architecture plan (`mobile-bufferbloat-shaper-plan.md`), which is still substantially right and referenced below. The goal here is one document you can actually trust and stick to, with every status label meaning exactly what it says.

## 1. What to keep from the original plan, unchanged

These held up and don't need revisiting:

- **Local-only architecture (no remote server).** Never contested, still correct, still the right call for trust and Play Store policy simplicity.
- **Egress shaping design (TokenBucket → CoDel → per-flow fair queue).** This is the one area with genuine, repeated, real evidence — logcat counters showing packets actually flowing through the pipeline. The design was right and the implementation is the most trustworthy part of the codebase right now.
- **Refusing to attempt QUIC/UDP TLS interception for download shaping.** Still the right call — never revisit this.
- **Percentile-based calibration (not a naive average).** The design is still correct; only the implementation's real-world behavior is unverified (see status table).
- **The phased structure (0–5).** Reasonable, mostly followed in spirit even though work got interleaved with bug fixes in practice — that's normal and fine.

## 2. The one real open decision: pure-Kotlin relay vs. a real netstack

This is the only place the implementation genuinely departed from the original plan. Don't resolve it by argument — resolve it with the evidence that's been missing this entire project: real-device behavior under real loss and jitter.

**Decision rule:**
- Run the real-device baseline (§4 below) first.
- If normal browsing and moderate downloads survive with occasional, recoverable rough edges → keep pure Kotlin, fix issues as they surface.
- If it stalls, hangs, or silently corrupts data repeatedly under real Airtel conditions → commit to integrating a proven userspace stack (tun2socks/gVisor via JNI) for the TUN-facing TCP side. That's a real engineering lift (cross-compilation, ABI packaging), so it should be justified by evidence, not argued about in the abstract — but don't avoid it indefinitely if the evidence says it's needed.

Don't skip this gate. Building more features on top of an unverified relay is how three of the last five sessions found bugs that had been sitting there for rounds.

## 3. Corrected status table

Four honest categories only: **Verified** (real test evidence exists and is described), **Partially verified** (some real evidence, but not the case that actually matters), **Written, unverified** (exists, compiles, never behaviorally tested), **Known incomplete** (a real, acknowledged gap).

| Component | Actual status | What would move it to "Verified" |
|---|---|---|
| VPN scaffolding | Verified (emulator only) | Same test, real device |
| IPv4 parsing | Written, unverified | Test against real malformed/edge-case packets, not just structural review |
| IPv6 parsing | Written, unverified | Not wired to any relay yet regardless |
| DNS — A/AAAA via system resolver | **Verified** | Already has real logcat evidence |
| DNS — raw fallback relay path | Written, unverified | Force a query type that skips the fast path and confirm it resolves |
| UDP relay (non-DNS) | **Written, unverified** — never actually exercised | Load any QUIC/HTTP-3 site or UDP-based service and confirm traffic flows |
| TCP relay — protected/outbound socket side | Partially verified — one small exchange confirmed | A real sustained download (tens of MB) completing without stalling |
| TCP relay — TUN-facing state machine | **Known incomplete** | No retransmission, no out-of-order handling, no real teardown — real work, not a test |
| ICMP | Written, unverified | An actual `ping` from adb shell or the device showing a reply |
| Egress shaping (TokenBucket/CoDel/FQ) | **Verified** | Already has real counter evidence |
| Auto-calibration | Written, unverified | Confirm it actually adjusts on a real RAT change or handover, not just that the classes exist |
| Download/ingress shaping | Known incomplete (skeleton only) | Functional dynamic receive-window test under real load |
| Flow classification | Written, unverified | Lowest priority — always a later-phase feature |
| Built-in bufferbloat test | Partially verified — traffic now enters the pipeline; the test's own output has never been shown | Actually run it and look at the before/after numbers it produces |
| Foreground service + notification | Service-start verified; notification content unverified | Confirm live stats actually update in the notification |
| Battery/Doze | Wake lock present; exemption prompt missing | Implement + confirm service survives 30+ min screen-off |
| Build tooling | **Verified** | Already confirmed repeatedly |

## 4. Corrected priority order

**Phase A — Close the false-verified gap (emulator is fine for this; it's about code correctness, not radio conditions):**
1. Load a non-DNS UDP/QUIC destination, confirm `UdpRelay` actually carries it
2. Run an actual sustained download (tens of MB) to completion, watch for stalls
3. Run a real `ping`, confirm a reply is actually received
4. Run the built-in `BufferbloatTest` itself, end to end, and look at its real output

**Phase B — Real-device baseline (this is now the most important step in the whole project):**
1. Install on the Airtel phone
2. Confirm VPN start, normal browsing survives over several minutes
3. Run `BufferbloatTest` shaper-off (this is your real baseline, replacing the very first Waveform numbers from the start of this project) and shaper-on
4. Repeat at a few different times of day — congestion varies, one run either way proves little

**Phase C — Resolve the architecture decision** using Phase B's actual results, per §2.

**Phase D — Remaining feature work** (can proceed once A–C give a stable foundation):
- Step 4: wire IPv6 relay (currently route + parser only, not connected to any forwarding)
- Step 5: battery optimization exemption prompt
- Ingress/download receive-window shaping — complete and validate
- Auto-calibration — confirm real adaptive behavior, not just structural presence
- Flow classification — lowest priority, always a later-phase nicety

**Phase E — Ongoing, not a final step:** commit discipline (one logical change per commit, as has actually been working well recently) and keeping the task tracker's status labels honest as you go, rather than reconciling it all at the end.

## 5. Definition of "done" (replacing "100% works")

There's no honest version of "100% works" for software talking to a variable cellular radio — the goal is a concrete, testable bar instead:

- Fresh clone builds clean with no manual environment overrides
- VPN starts reliably across 10 consecutive launches, no crash
- DNS resolves (A and AAAA) on real Airtel data
- A 50MB+ download completes without stalling, 5 times in a row, on real Airtel
- A QUIC/UDP-based service loads correctly
- `ping` gets a real reply (documented as loopback-latency, not real RTT — that caveat stays, it's honest, not a bug)
- `BufferbloatTest` shaper-off vs. shaper-on shows a measurable, repeatable drop in under-load latency on real Airtel, at more than one time of day
- Service survives 30+ minutes of screen-off Doze
- If the phone gets an IPv6 address from Airtel at all (worth checking directly — Airtel is more mixed dual-stack than Jio), an IPv6-only destination is reachable

When every line above is true, that's the actual finish line — not a checklist saying so.
