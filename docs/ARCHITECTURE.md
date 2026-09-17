# Architecture

## Status and terminology

This document separates the **current prototype** from the **production target**. A class or screen in the repository is not proof that the corresponding network behavior is reliable. Read [Project Context](PROJECT_CONTEXT.md) for the current handoff, [Design Decisions](DESIGN_DECISIONS.md) for rationale, and [Roadmap](ROADMAP.md) for sequence and evidence gates.

## Product boundary

The intended product is a local Android network tool:

```text
App traffic -> Android VpnService TUN -> local data plane -> direct destination
```

There is no project-operated VPN gateway, proxy, traffic-inspection service, telemetry backend, or TLS interception point. Android and the modem/carrier retain control of radio selection, cellular bands, NSA/SA mode, and carrier aggregation.

## Current executable behavior

The Android service validates configuration first: invalid settings, including
initial zero limits, report ERROR without creating a route. For valid settings,
it checks native capability before establishing TUN. The unavailable stub follows
this path:

```text
Valid-config start -> NativeEngineBridge capability check -> unavailable
  -> VpnRuntimeState = UNSUPPORTED -> no VPN routes established -> normal device networking remains in use
```

This is deliberate. The unsafe hand-written Kotlin packet relay was removed
from the shipped module rather than retained as a button-triggered fallback.

### Known implementation gaps

- The native engine is a safe stub. It deliberately returns unavailable and does not parse, retain, relay, queue, inspect, or close traffic/TUN file descriptors.
- No TUN-facing TCP/IP stack is shipped. A future engine needs mature retransmission, reordering, congestion, and teardown behavior before it can route traffic.
- IPv6 must not be advertised as supported until a complete native forwarding path is implemented and tested. If an IPv4-only engine is ever enabled, Android's IPv6 family is explicitly allowed to bypass the VPN rather than being captured without a forwarding path.
- DNS resolver substitution is absent because it would not preserve a user's DNS policy. A native engine must forward ordinary DNS traffic unchanged before DNS interception is enabled.
- Calibration, ingress control, and the validation benchmark remain unverified. Runtime statistics now display only engine-reported aggregate data or an explicit unavailable value; they are not performance evidence until a real engine exists.

These gaps mean the prototype must not be relied on for normal connectivity or sensitive traffic.

## Production target

The selected design uses a mature userspace stack through a narrow JNI boundary.
It has **two separate TCP connections**, not one shared receive-window controller:

```text
Connection A (app-facing):
App TCP endpoint <-> VpnService TUN <-> gVisor/userspace TCP endpoint
                                      ACK/window/retransmission owner for A
                                                |
                                      bounded ordered stream bridge
                                      pacing / fairness / backpressure
                                                |
Connection B (Internet-facing):
Protected Android/Linux TCP socket <-----------> real destination TCP endpoint
ACK/window/retransmission owner for B
Candidate download control: this socket's receive behavior/options

UDP/QUIC: TUN datagrams <-> safe native forwarding <-> protected UDP sockets
```

The userspace stack owns app-facing TCP state. Android/Linux owns the protected
socket's independent TCP sequence space, ACKs, congestion control, retransmission
and receive-window advertisement to the real server. The bridge transfers
ordered bytes without decrypting application TLS. TCP termination is not TLS
termination. Changing only gVisor's app-facing receive window limits app upload
into that endpoint; it does not directly advertise a window to the server.

Prove ordinary unshaped IPv4 TCP/UDP forwarding in Stage 2, then test internal
upload shaping, measurement and adaptive autorate in Stage 3 to assess the
primary upload-bufferbloat value before full dual-stack integration. Stage 4
completes IPv6/dual-stack, DNS and network-transition correctness. IPv6 remains
mandatory before broad whole-device support or public/default-route release
claims; until it passes, allow IPv6 bypass in internal IPv4-only builds. Kotlin retains
lifecycle, configuration, UI, accessibility and local diagnostics. The three-ABI
JNI stub establishes a buildable contract, not a real stack or physical lifecycle
proof. gVisor integration remains planned.

The intended Kotlin/native contract is deliberately small:

- Start with the borrowed TUN descriptor, immutable configuration, and only a
  narrow `VpnService.protect(fd)` socket-protection callback; copy the native
  input records before returning and retain the callback only until stop joins.
- Atomically update shaping configuration.
- Stop and join the current engine generation before Kotlin closes the TUN or
  releases the callback; a failed stop is quarantined rather than freed.
- Emit aggregate metrics, per-flow metrics, and health/failure events.

A real engine must declare the required IPv4 TCP, **safe IPv4 UDP forwarding**,
protected-socket, safe-stop, health-event, and flow-metric feature bits before
the service establishes a route. TCP-only shaping does not excuse dropping
UDP/QUIC: download shaping for QUIC remains out of scope, while forwarding is a
hard activation requirement. It must join all workers before Kotlin closes the
TUN or releases the socket-protection callback.

## Shaping model

- **TCP upload:** bound per-flow and total buffering, including kernel send
  buffers; fairly schedule paced writes under a token budget. Handle partial
  writes and EAGAIN without losing bytes. Stop draining the app-facing stack when
  downstream cannot progress, propagating backpressure. Preserve accepted stream
  bytes in order through forwarding or explicit connection failure; never
  discard arbitrary chunks to implement an AQM drop.
- **TCP download (experimental):** test bounded reads/receive buffering and
  TCP_WINDOW_CLAMP on the **protected remote-facing Android/Linux socket**.
  TCP_INFO is a candidate observation source, not a shaping mechanism. Kernel
  acceptance/readback do not establish a changed advertised window, sender
  response or latency improvement. Physical tests must distinguish these claims,
  scaling/autotuning effects and stall recovery before optional enablement.
  A separate debug-only no-route F-01/F-02 harness now probes these options;
  the production engine still implements neither mechanism.
- **Packet AQM:** CoDel-style dropping/ECN is a candidate only at a packet queue
  with valid acceptance and retransmission semantics. Packets before receiver
  acceptance or stack-generated packets with a retained sender retransmission
  copy may qualify after review/tests. Already-read/acknowledged TCP bytes do
  not; dropping them cannot recover the bridge's missing stream data.
- **UDP/QUIC:** preserve datagrams and safe forwarding first. Optional upload
  pacing and bounded datagram loss need an explicit policy and evidence.
  Download shaping is out of scope; never discard UDP/QUIC merely because it
  cannot use the TCP download controller.

## Autorate and production capability integration (planned)

Prefer adaptive feedback from independently measured delay and load, with bounded
rate changes, sample aging, probe failure handling, idle/handover resets and an
explicit measurement budget. Static percentile × headroom may seed or bound a
fallback but is not sufficient adaptation. Never estimate physical capacity only
from traffic already limited by the current cap.

Separate mandatory safe forwarding/lifecycle capabilities from optional socket
controls and observations. Runtime probes on the actual Android/kernel/socket
determine optional availability; unknown or failed probes disable the feature
with a reason. Successful probes still need physical efficacy evidence. The
existing ABI feature mask is a prerequisite contract, not this framework. A
debug-only Stage 1 framework now implements scoped probe states and safe optional
disablement; it has no production activation caller. Production integration and
directional configuration below remain planned.
Current configuration still requires positive upload and download limits.
The following **future requirement** needs a separate implementation change:

- Configuration and runtime state must track upload and TCP download capability,
  requested limits, effective enablement and unavailable reasons independently.
- Independently proven upload shaping must be available when optional TCP
  download control is unavailable, without requiring a positive download limit.
- Expose/enable a download-control setting only when its runtime capability is
  verified; support bidirectional mode when both directions are supported.
- A failed or stale optional download capability must disable that control while
  preserving independently proven upload where forwarding remains safe.
- The UI must never present a configured download limit as proof of effective
  download control. A saved request and measured/verified behavior are distinct.

Stage 3 implements this directional model for internal upload experiments;
Stage 5 integrates optional protected-socket download control. Neither feature
nor the revised configuration model exists in the checked-in stub.

Universal correctness comes before OEM tuning. Profiles may optimize buffer,
scheduling, battery or thermal budgets within proven invariants; they must not
substitute for probes or forwarding. Later Radio Advisor/mapping may use
user-authorized observations and recommendations after privacy review. Stock
operation never forces LTE/NR bands, NSA/SA or carrier aggregation; exact band
locking research belongs outside this universal application.

## Safety invariants

The release implementation must preserve these invariants:

1. At most one active VPN/TUN generation owns writes and a wake lock.
2. Stop or failure cancels and joins every native/Kotlin worker before closing the TUN descriptor. An independent watchdog bounds startup, configuration updates, native health/metric calls, and shutdown before cancelling a native-touching metrics job. If a future engine violates that contract or never returns, the process-level lifecycle latch blocks further activation and the service records a generic local marker before terminating its own process to close app-owned descriptors; this last resort does not replace release evidence.
3. IPv6, DNS, captive-portal handling, and per-app routing are enabled only when their forwarding behavior is verified. Before native IPv6 forwarding exists, Android's IPv6 family bypasses the IPv4-only route.
4. A health failure disables or bypasses the local path with a visible, recoverable explanation; it must not silently black-hole traffic.
5. Metrics shown in the UI and notifications come from measured runtime state, never sample or random values.

## Package map

| Area | Current package | Responsibility |
|---|---|---|
| Android entry and UI | `com.bufferbloatshaper` / `ui` | Activity, Compose screens, user configuration |
| Runtime model | `model` | Configuration, routing preferences, and process-local `VpnRuntimeState` |
| VPN lifecycle | `vpn` | Serialized service lifecycle and safe Android VPN ownership |
| Native boundary | `nativeengine` / `native` | Kotlin/JNI ABI contract and intentionally unavailable native stub |
| Shaping references | `shaping` | Unit-tested TokenBucket, CoDel-style AQM, and fair-queue reference logic; no live packet path |
| F-03 stream feasibility | debug `harness.stream` and `harness.UploadRunner` | Existing byte runner plus controlled synthetic sources, protected remote sockets, bounded observations and receiver receipts; no TUN/route integration |
| Calibration | `calibration` | Network-state monitoring and independently supplied capacity-sample scaffold; no automatic update path |
| Validation | `validation` | Validation-gate UI and grade model; no benchmark runs in the current build |
| Local utilities | `util` | Preferences, notification, user-initiated redacted health-timeline diagnostic |

## Privacy design requirement

No planned architecture may add a remote relay, telemetry service, advertising SDK, TLS certificate authority, payload decryption, or data resale. See [PRIVACY.md](../PRIVACY.md) for the precise behavior of the current prototype, including its DNS and diagnostic limitations.

## Implemented internal Stage 1 seam

The debug source set provides independent ExperimentConfig, a manually opened
consent Activity, an ADB-driven batch Activity, bound no-route VpnService and
separate JNI library. The host invokes one Android command per manifest run;
Android re-resolves the requested Wi-Fi/cellular Network and the existing
service starts a fresh worker/socket. One cancellable worker owns that protected
socket; protection and optional Network binding precede connect. Cancellation
signals the worker and never closes its descriptor concurrently.

The host owns aggregate byte/time policy, starts one synthetic endpoint per run,
can collect independent adb-shell ping and an optional flow-filtered TShark
capture, and checkpoints raw/private files only below ignored `output/`. A
separate allowlisted summary excludes endpoint/Network/device identifiers and
keeps acceptance/readback, sender transport effect, integrity/recovery and
physical benefit distinct. TShark shutdown signals graceful capture-child cleanup
before hard fallbacks. A host-only offline decoder selects direction from owned
endpoint metadata, derives window/ACK/data observations, and marks incomplete
captures/handshakes PARTIAL or SKIPPED. Derived summaries contain numeric/fixed
status values only and never infer latency efficacy. Release excludes this seam. It implements neither production
TCP leg and does not pass Stage 1. See [Experiments](EXPERIMENTS.md).

The debug source set also contains an independent F-03 `StreamPacingRunner`.
Callers inject nonblocking stream sources/sinks and a clock; the runner itself
opens no socket and has no Android or production-engine dependency. Each flow
has a fixed ring allocation, and validation requires the sum of those
allocations to fit the configured global limit. Reads cannot exceed remaining
per-flow/global occupancy. An integer byte budget bounds paced writes; deficit
round robin supplies per-flow service while preserving partial-write offsets.
Zero-byte reads/writes represent EAGAIN. Full queues suppress upstream reads and
record their later resumption. Cancellation, errors and stalls report accepted
but undelivered bytes before deterministic close.

This is a TCP **byte-stream queue after acceptance**, not a packet queue with
retransmission ownership. It is therefore not a valid CoDel/drop/ECN AQM queue
under D-09, and the harness exposes no drop operation.

The debug `UploadRunner` adapts this runner to synthetic sources and up to four
protected OS TCP sockets through the existing JNI library. It owns each FD from
open, before any callback, through a single close, including partial setup failure.
Protect precedes Network binding and connect; SO_SNDBUF is requested and read back
before/after connect. Failed, malformed or above-ceiling readback stops the run.
The kernel value is neither live queue occupancy nor an exact memory allocation.
Nonblocking send uses MSG_NOSIGNAL; partial offsets stay in the existing ring,
EAGAIN/EINTR consume no budget. The bounded source stops producing when queues fill.

Graceful completion drains queues then uses SHUT_WR (FIN), waits for a bounded
65-byte receiver SHA-256 line and EOF on every socket, and closes. Receiver hashes
are compared against exact written hashes; host verification also checks expected
count/hash per flow. Local runner COMPLETE means write acceptance only. Failure,
cancel, reset, stall or deadline requests SO_LINGER(1,0) and closes once; reset
request errors are recorded and do not claim a wire RST. Accepted-but-unwritten
and kernel-accepted-but-unconfirmed bytes remain explicit. No retry/resume of a
failed stream is attempted. Setup, pacing and receipt share a 120-second maximum
deadline; polls are at most 50 ms, pacing ticks 1 ms, receipt polling 1 ms.
The existing two-second service join bound does not prove absence of OS/Binder
hangs: unjoined work remains visible and is never concurrently closed.
Protection executes outside the lifecycle lock so a stuck platform call cannot
block cancellation from reaching that bounded join. A cancellation observed after
protection returns stops before binding/connect; no callback survives worker return.

At most 480 samples retain live per-flow userspace occupancy/progress, actual
sample intervals, configured rate, interval write-acceptance rates, TCP_INFO,
SIOCOUTQ and SIOCOUTQNSD. Missing optional observations stay null with typed
failure evidence. Allocation, occupancy, SO_SNDBUF request/readback, queued
sequence bytes, throughput and radio/wire observations are not interchangeable.
The owner endpoint hashes synthetic uploads only; it is not a project relay.

Debug capability records use mandatory flags, UNKNOWN/AVAILABLE/UNAVAILABLE,
fixed reasons and run-relative timestamps. Records expire after 120 seconds or
scope change (Android API, numeric kernel family, ABI, transport and IP family).
Each run/socket gets fresh records. Forwarding/safe-stop/health/flow-metric
contracts are never inferred from debug probe success. No phone-model allowlist
or production route call exists. Operator procedures and evidence meanings are
in EXPERIMENTS; all new physical behavior remains unexecuted/unverified.
