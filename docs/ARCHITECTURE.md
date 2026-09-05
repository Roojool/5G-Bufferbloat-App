# Architecture

## Status and terminology

This document separates the **current prototype** from the **production target**. A class or screen in the repository is not proof that the corresponding network behavior is reliable. The detailed evidence record is [the status and roadmap](../bufferbloat-shaper-status-and-roadmap.md).

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

This is deliberate. The source must not turn on the historical Kotlin relay merely because a button was pressed; that relay does not meet the reliability requirements for a TUN-facing TCP stack.

## Historical prototype data path

The repository retains the earlier Kotlin relay implementation as migration/reference code. Its high-level design was:

```text
Apps
  -> VpnService TUN
  -> PacketReader / IPv4 parser
  -> TCP: EgressShaper -> TcpRelay -> protected Java socket
  -> UDP: UdpRelay -> protected datagram socket
  -> relay response -> PacketBuilder -> PacketWriter -> TUN -> apps
```

`EgressShaper` contains the token bucket, CoDel-style queue management, and fair-queue primitives. `TcpRelay` and `UdpRelay` own the corresponding historical prototype behavior. They are not instantiated by the current `ShaperVpnService`; the historical DNS relay was removed to avoid resolver substitution.

### Known implementation gaps

- The native engine is a safe stub. It deliberately returns unavailable and does not parse, retain, relay, queue, inspect, or close traffic/TUN file descriptors.
- The historical `TcpRelay` is hand-written and partial. It does not provide the mature retransmission, reordering, congestion, and teardown behavior required of a production TUN-facing TCP stack.
- The historical VPN builder installed IPv6 routing without a forwarding path. IPv6 must not be advertised as supported until a complete native forwarding path is implemented and tested.
- The prior DNS relay was removed because resolver substitution and synthetic A/AAAA responses do not preserve a user's DNS policy. A native engine must forward ordinary DNS traffic unchanged before DNS interception is enabled.
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

- Start with the TUN file descriptor and immutable configuration.
- Atomically update shaping configuration.
- Stop and join the current engine generation.
- Emit aggregate metrics, per-flow metrics, and health/failure events.

## Shaping model

- **TCP upload:** token bucket rate ceiling, controlled-delay queue management, and fair queuing operate before bytes are written to the direct socket.
- **TCP download:** the target mechanism is advertised receive-window control by the local TCP endpoint. It must be measured and verified before it is exposed as a feature.
- **UDP/QUIC:** lightweight upload pacing may be useful. Download-side shaping is out of scope because a local app cannot safely alter encrypted QUIC transport control without TLS interception.

## Safety invariants

The release implementation must preserve these invariants:

1. At most one active VPN/TUN generation owns writes and a wake lock.
2. Stop or failure cancels and joins every relay task before closing the TUN descriptor.
3. IPv6, DNS, captive-portal handling, and per-app routing are enabled only when their forwarding behavior is verified.
4. A health failure disables or bypasses the local path with a visible, recoverable explanation; it must not silently black-hole traffic.
5. Metrics shown in the UI and notifications come from measured runtime state, never sample or random values.

## Package map

| Area | Current package | Responsibility |
|---|---|---|
| Android entry and UI | `com.bufferbloatshaper` / `ui` | Activity, Compose screens, user configuration |
| Runtime model | `model` | Configuration, routing preferences, and process-local `VpnRuntimeState` |
| VPN and packet handling | `vpn` | TUN service, parsers, relays, packet I/O |
| Native boundary | `nativeengine` / `native` | Kotlin/JNI ABI contract and intentionally unavailable native stub |
| Shaping | `shaping` | Token bucket, CoDel-style AQM, fair queue, ingress-controller scaffold |
| Calibration | `calibration` | Network-state monitoring and active/passive estimate scaffolding |
| Validation | `validation` | Before/after test scaffold and grades |
| Local utilities | `util` | Preferences, notification, battery monitoring |

## Privacy design requirement

No planned architecture may add a remote relay, telemetry service, advertising SDK, TLS certificate authority, payload decryption, or data resale. See [PRIVACY.md](../PRIVACY.md) for the precise behavior of the current prototype, including its DNS and diagnostic limitations.
