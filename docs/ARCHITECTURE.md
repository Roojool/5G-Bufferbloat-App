# Architecture

## Status and terminology

This document separates the **current prototype** from the **production target**. A class or screen in the repository is not proof that the corresponding network behavior is reliable. The current implementation roadmap and evidence gates are in [the roadmap](ROADMAP.md).

## Product boundary

The intended product is a local Android network tool:

```text
App traffic -> Android VpnService TUN -> local data plane -> direct destination
```

There is no project-operated VPN gateway, proxy, traffic-inspection service, telemetry backend, or TLS interception point. Android and the modem/carrier retain control of radio selection, cellular bands, NSA/SA mode, and carrier aggregation.

## Current executable behavior

The checked-in Android service checks the packaged native engine capability before it establishes a TUN interface. The checked-in engine is an intentionally unavailable stub, so a start request follows this safe path:

```text
User requests shaping -> NativeEngineBridge capability check -> unavailable
  -> VpnRuntimeState = UNSUPPORTED -> no VPN routes established -> normal device networking remains in use
```

This is deliberate. The unsafe hand-written Kotlin packet relay was removed
from the shipped module rather than retained as a button-triggered fallback.

### Known implementation gaps

- The native engine is a safe stub. It deliberately returns unavailable and does not parse, retain, relay, queue, inspect, or close traffic/TUN file descriptors.
- No TUN-facing TCP/IP stack is shipped. A future engine needs mature retransmission, reordering, congestion, and teardown behavior before it can route traffic.
- IPv6 must not be advertised as supported until a complete native forwarding path is implemented and tested.
- DNS resolver substitution is absent because it would not preserve a user's DNS policy. A native engine must forward ordinary DNS traffic unchanged before DNS interception is enabled.
- Calibration, ingress control, and the validation benchmark remain unverified. Runtime statistics now display only engine-reported aggregate data or an explicit unavailable value; they are not performance evidence until a real engine exists.

These gaps mean the prototype must not be relied on for normal connectivity or sensitive traffic.

## Production target

The planned production data plane moves TUN-facing TCP/IP behavior into a proven native userspace stack accessed through a narrow JNI boundary:

```text
Apps -> VpnService TUN -> native TCP/IP stack -> protected direct sockets -> Internet
                                  |                 |
                            TCP receive-window       Token bucket -> AQM -> fair queue
                            control for TCP only
```

The native engine is expected to own IPv4/IPv6 forwarding, TCP state, retransmission, ordering, teardown, and TCP receive-window accounting. Kotlin retains Android lifecycle, configuration, UI, accessibility, and local diagnostics. The repository contains a JNI ABI boundary and an intentionally unavailable native stub for `arm64-v8a`, `armeabi-v7a`, and `x86_64`; it proves only packaging, capability, and lifecycle contracts—not packet relaying. A gVisor-based implementation remains a planned architecture, not evidence of a completed integration in this prototype.

The intended Kotlin/native contract is deliberately small:

- Start with the borrowed TUN descriptor, immutable configuration, and only a
  narrow `VpnService.protect(fd)` socket-protection callback.
- Atomically update shaping configuration.
- Stop and join the current engine generation.
- Emit aggregate metrics, per-flow metrics, and health/failure events.

A real engine must declare the required IPv4 TCP, protected-socket, safe-stop,
health-event, and flow-metric feature bits before the service establishes a
route. It must join all workers before Kotlin closes the TUN or releases the
socket-protection callback.

## Shaping model

- **TCP upload:** token bucket rate ceiling, controlled-delay queue management, and fair queuing operate before bytes are written to the direct socket.
- **TCP download:** the target mechanism is advertised receive-window control by the local TCP endpoint. It must be measured and verified before it is exposed as a feature.
- **UDP/QUIC:** lightweight upload pacing may be useful. Download-side shaping is out of scope because a local app cannot safely alter encrypted QUIC transport control without TLS interception.

## Safety invariants

The release implementation must preserve these invariants:

1. At most one active VPN/TUN generation owns writes and a wake lock.
2. Stop or failure cancels and joins every native/Kotlin worker before closing the TUN descriptor.
3. IPv6, DNS, captive-portal handling, and per-app routing are enabled only when their forwarding behavior is verified.
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
| Calibration | `calibration` | Network-state monitoring and independently supplied capacity-sample scaffold; no automatic update path |
| Validation | `validation` | Validation-gate UI and grade model; no benchmark runs in the current build |
| Local utilities | `util` | Preferences, notification, user-initiated redacted health-timeline diagnostic |

## Privacy design requirement

No planned architecture may add a remote relay, telemetry service, advertising SDK, TLS certificate authority, payload decryption, or data resale. See [PRIVACY.md](../PRIVACY.md) for the precise behavior of the current prototype, including its DNS and diagnostic limitations.
