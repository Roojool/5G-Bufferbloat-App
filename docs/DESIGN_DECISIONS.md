# Design Decisions

Decision baseline: 2026-09-11. These are the revised architecture's constraints
and rationale, **not evidence of an implemented engine**. Checked-in behavior is
in [Project Context](PROJECT_CONTEXT.md); execution gates are in
[Roadmap](ROADMAP.md). Changes to these decisions require a recorded rationale,
affected evidence, and review.

| ID | Decision | Rationale and evidence boundary |
|---|---|---|
| D-01 | Use a local-only Android VpnService. | Capture eligible on-device app traffic using Android's VPN interface without requiring root. The current stub captures nothing; this does not imply hotspot coverage or another concurrent VPN. |
| D-02 | No project relay. | Open direct protected connections to original destinations. No hosted exit, proxy dependency, telemetry backend, TLS interception or payload decryption. Local-only does not automatically satisfy Play policy. |
| D-03 | gVisor/userspace stack owns app-facing TCP. | Reuse a mature TCP implementation for the TUN leg's sequence numbers, ACKs, windows, ordering and retransmissions. No pinned gVisor source or integration exists today. |
| D-04 | A protected Android/Linux socket owns Internet-facing TCP. | The OS TCP endpoint connects to the actual server. This is a separate TCP connection with independent congestion, receive-window and retransmission state. Protection must succeed before bind/connect/send. |
| D-05 | Test TCP download control on that protected remote-facing socket. | Its receive behavior can influence the real sender. Changing the app-facing gVisor receive window controls app upload into the local endpoint; it does not directly advertise a download window to the server. |
| D-06 | TCP_WINDOW_CLAMP and TCP_INFO remain experimental. | Kernel acceptance, readback, available fields and effective behavior vary. Physical protected-socket results must establish usefulness; a constant in a header or successful setsockopt is insufficient. TCP_INFO is observation, not a rate-control actuator. |
| D-07 | TCP upload uses bounded buffers, pacing, fairness and backpressure. | Bound memory across userspace and kernel queues, schedule flows fairly, handle partial writes/EAGAIN, and stop reading when downstream cannot progress. A token bucket is a pacing/rate budget, not permission to discard stream bytes. |
| D-08 | Never discard arbitrary already-accepted TCP stream bytes. | Once a TCP endpoint has acknowledged data, the original sender may no longer retain it. The bridge must preserve order and every accepted byte through forwarding or surface an explicit connection failure; silent byte removal corrupts the stream. |
| D-09 | Packet-dropping AQM is allowed only with correct packet/retransmission ownership. | A real packet queue before receiver acceptance, or a stack-owned packet queue whose sender retains retransmittable data, can be a candidate. A queue of bytes read from TCP is not such a queue. UDP datagram drops need an explicit loss policy and tests; QUIC forwarding cannot be disabled because download shaping is absent. |
| D-10 | Prefer adaptive delay/load autorate over only static percentile × headroom. | Cellular capacity changes faster than a fixed historical cap can track. Measure independent delay and offered load, bound adjustments, detect stale/failed probes and avoid learning capacity from self-limited throughput. Percentile/headroom may seed or bound a fallback; no controller is implemented. |
| D-11 | IPv6 is mandatory before broad whole-device support or public/default-route release claims. | Stage 3 tests the primary upload-bufferbloat value internally before full dual-stack integration in Stage 4. IPv4-only capture leaves a traffic family outside control; Stage 4 must prove IPv6, DNS A/AAAA, MTU and transitions before release, even for upload-only scope. Earlier internal builds explicitly permit IPv6 bypass; this ordering never makes IPv6 optional for release. |
| D-12 | OEM/device profiles optimize performance, not correctness. | Universal ownership, forwarding and safe-failure invariants cannot depend on a model-name allowlist. Tune resource/battery/performance budgets only after the common path works. |
| D-13 | Runtime capability probes determine optional feature availability. | Probe the actual socket/platform and keep unknown, unavailable and verified behavior distinct. Static ABI feature bits cover engine contracts; they do not prove optional kernel behavior. Failure disables the optional feature with a reason; required-path failure prevents route activation. |
| D-14 | The stock app does not force LTE/NR bands. | Universal stock operation must not rely on privileged modem interfaces, root, engineering menus or carrier changes. It also does not force NSA/SA or carrier aggregation. |
| D-15 | A future Radio Advisor may observe and recommend conditions. | Later, user-authorized public radio signals could support local advice or mapping where available. Handle permission denial/missing/stale data and decide consent, storage and location privacy first. No advisor or map is implemented. |
| D-16 | Exact band locking research belongs outside the universal stock app. | If ever separately authorized, isolate device-specific privileged experiments in a separate research scope; do not make them a stock-app dependency, hidden toggle, or compatibility promise. |

## Directional configuration requirement (D-13 implementation consequence)

Future configuration and runtime state must represent upload and TCP download
capabilities independently, including requested settings, effective enablement
and reasons for unavailability. Independently proven upload shaping must work
when TCP download control is unavailable, without requiring a positive download
limit. Only expose/enable a download-control setting when its runtime capability
is verified. Support bidirectional mode when both directions are supported;
losing optional download capability must not prevent independently proven upload
where forwarding remains safe. Never present a configured download cap as proof
that download control works.

Stage 3 establishes this model for internal upload experiments; Stage 5 may add
verified protected-socket download control. Current source still validates both
limits as positive. This requirement is planned, not implemented by this PR.

## Technical interpretation and references

The two-connection split is this project's chosen adapter design, not a claim
that every gVisor deployment uses host sockets. Upstream describes Netstack as
an independently reusable userspace stack; source/toolchain selection still
requires review. [gVisor networking architecture](https://gvisor.dev/docs/architecture_guide/networking/)

Android's VPN API provides socket protection and permits only one active VPN
service per user/profile. Protection excludes an outbound socket from VPN
routing; it does not add transport encryption.
[Android VPN guide](https://developer.android.com/develop/connectivity/vpn)

Linux documents TCP_WINDOW_CLAMP as a limit on the advertised window, subject
to a minimum, and TCP_INFO as socket information with a nonportable interface.
These establish experiment candidates, not Android device support or latency
benefits. [Linux tcp(7)](https://man7.org/linux/man-pages/man7/tcp.7.html)

TCP reliability and acknowledgements motivate preservation of accepted bytes;
CoDel defines packet queue management, not deletion from an acknowledged byte
stream. Queue placement and loss recovery must be proven in our adapter.
[TCP specification](https://www.rfc-editor.org/rfc/rfc9293.html),
[CoDel specification](https://www.rfc-editor.org/rfc/rfc8289.html)

## Decision change record template

Date / decision ID / proposed replacement / rationale / implementation commit /
experiment and literal evidence links / effect on gates and public claims /
review outcome. Do not mark a proposal accepted merely because it is documented.
