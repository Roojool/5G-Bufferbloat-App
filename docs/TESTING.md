# Testing and Release Evidence

## Principle

For this project, “built” and “works” are different claims. Every behavior claim needs literal, reproducible evidence. Do not replace logs, test output, or measurements with a narrative summary.

## Automated checks

The JVM suite also covers the bounded local health timeline and verifies that
the manually shared diagnostic report exports only structured transition
status/generation values, never free-form error detail. Device make/model and
local timestamps are intentionally visible in that report and must be reviewed
before the user shares it.

The repository CI runs these source-level checks on JDK 21:

```text
:app:assembleDebug
:app:assembleDebugAndroidTest
:app:testDebugUnitTest
:app:lintDebug
```

They establish that the source can assemble and pass available static/unit checks. They cannot establish VPN reliability, carrier compatibility, radio behavior, or bufferbloat improvement.

`assembleDebugAndroidTest` compiles/packages instrumentation tests; it does not
execute them. Distinguish executed tests from UP-TO-DATE or cached results.

The debug assembly also compiles the intentionally unavailable JNI-stub boundary for `arm64-v8a`, `armeabi-v7a`, and `x86_64`. That is an ABI packaging and fail-closed capability check, not a native data-plane or traffic test.

New native-engine work should add native/unit tests for packet parsing/checksums, TCP state, queue accounting, TokenBucket behavior, CoDel-style logic, fair queuing, DNS behavior, profiles, calibration calculations, and validation grading. It must also test `VpnService.protect(fd)` before every direct connect, false/throw protect paths, no callback after stop, opaque-token stale-handle rejection, failed-start stop/join cleanup, and bounded typed-event behavior. Instrumentation tests should cover VPN lifecycle, configuration changes, health transitions, native library loading, IPv6 fall-through while only IPv4 is routed, revocation during startup, a non-OK or hung native startup/shutdown/process-containment path, and safe fallback behavior.

## Device test record

For a network-affecting change, record:

- App version and commit SHA.
- Device/OEM/model and Android version; omit serial number, phone number, IMEI, IMSI, and precise location.
- Transport (Wi-Fi/cellular), carrier if the tester chooses to disclose it, and approximate signal/network state.
- Whether the shaper was disabled, enabled, or failed over.
- Commands/test steps and literal, redacted output.
- Any failure, recovery action, and result.

Use the Compatibility report issue form for a shareable record.

## Feasibility and capability evidence categories

Use a per-run record in [Experiments](EXPERIMENTS.md) with hypothesis, exact
commit, device, Android/kernel, SoC/OEM, network, procedure, literal redacted
output, conclusion and verification scope. Two initial one-phone Wi-Fi runs now
establish only baseline/receive-buffer acceptance, readback and transfer
integrity for their stated setup; the mechanism and benefit experiments remain
unrun or unverified as recorded there.

| Category | What it can establish | What it cannot establish alone |
|---|---|---|
| Source/configuration inspection | Stub behavior, configured SDK/ABI, contracts | Executed runtime or physical support |
| Build/JVM/static checks | Packaging and tested reference/contract behavior | Forwarding, real-engine lifecycle or latency benefit |
| Emulator/instrumentation execution | Specific API/lifecycle behavior in that environment | Physical modem/OEM/carrier behavior |
| Feasibility harness | Candidate mechanism behavior on a stated protected remote socket | Integrated TUN forwarding or production readiness |
| Runtime capability probe | Actual option/API status, errno, readback, struct length/fields | Effective window control or bufferbloat improvement |
| Physical forwarding/integrity | Stated TCP/UDP/DNS/IP-family correctness and recovery | Shaping efficacy or every device feature |
| Physical control/autorate efficacy | Repeated delay/load/throughput results for a feature and conditions | Universal device/carrier or hotspot support |

Before expensive integration, F-01/F-02 must distinguish option acceptance,
transport effect and benefit on the **protected Android/Linux socket**. Compare
baseline, receive-buffer/read cadence, and clamp variants; record TCP window
scaling/autotuning effects, stalls/zero-window recovery and independent timing.
TCP_INFO fields must be length-checked and their meaning validated; an RTT field
must not be assumed to measure downlink queue delay. Repeat on physical hardware.

Capability tests must cover unknown, unavailable, malformed/truncated, stale and
successful probe results; permission/option failures; missing optional features;
and conservative fallback without OEM profiles. Mandatory protection/forwarding
failure must prevent interception. Optional failure must disable only the optional
feature where safe forwarding is proven. A model name or ABI bit is not a probe.

Stage 3's future directional configuration/runtime tests must prove that upload
works without an available download controller or positive download limit;
download-control settings require verified runtime capability; bidirectional mode
requires both supported directions; and loss of optional download capability
preserves independently proven upload where safe. UI tests must distinguish
configured download caps from effective control. Current source implements none
of this directional gating and still requires both positive limits.

TCP upload tests must preserve every accepted byte under partial writes, EAGAIN,
slow readers, rate changes, half-close, cancellation and concurrent flows. Record
userspace/kernel buffer bounds and fairness. Any drop/ECN AQM test must identify
the packet queue, ACK/acceptance boundary, retransmission owner and loss recovery;
never apply packet-drop assertions to accepted stream chunks.

Autorate tests must compare unshaped, static and adaptive runs under changing
capacity, stale/failed probes, idle and handover. Record independent delay/load,
chosen limits separately from throughput, settling/oscillation and measurement
cost. Feeding already-shaped throughput back as capacity is not valid evidence.

Stage 3's internal IPv4 upload/measurement/autorate experiments assess the primary
value before significant full dual-stack integration. Stage 2 forwarding and
lifecycle safeguards remain prerequisites; test IPv6 bypass explicitly in these
internal builds. Stage 4 then verifies dual-stack/IPv6-only forwarding, DNS A/AAAA,
MTU/error behavior, network transitions and shaping/measurement across IP families.
IPv6 is mandatory before broad whole-device support or public/default-route
release claims, even for upload-only scope. Bypass is not IPv6 shaping support,
and moving Stage 4 after internal upload experiments never waives the release gate.

## Public-release gate

No tagged public release is ready until each item below has evidence on the [compatibility matrix](COMPATIBILITY.md):

| Gate | Required evidence |
|---|---|
| Lifecycle | Ten consecutive start/stop cycles without crash, restart loop, stale writer, or wake-lock leak |
| Basic traffic | DNS A/AAAA, normal browsing, TCP, and QUIC/UDP work while the VPN is active |
| Transfer reliability | Five checksum-verified transfers of at least 50 MB complete without stalls or corruption |
| IPv6 | A dual-stack/IPv6-only destination works after a complete IPv6 relay is implemented |
| Mobility | Wi-Fi/cellular and default-network transitions recover without silently losing traffic |
| Background | At least 30 minutes of screen-off operation without an unexpected shutdown or sustained leak |
| Bufferbloat result | Repeatable shaper-off/on tests at different times of day show measured loaded-latency improvement with acceptable throughput retention |
| Optional TCP download | If claimed, protected-socket probes plus physical effect/benefit evidence and safe unavailable-feature behavior |
| Adaptive autorate | If claimed, independent delay/load feedback, bounded/stable response and stale/failed-probe fallback evidence |
| Privacy | No project-operated endpoint, analytics SDK, payload decryption, or unintended diagnostic upload is present |

## Validation methodology

Before a result can be called a bufferbloat improvement:

1. Confirm and record whether shaping is actually on or off.
2. Generate independent upload and download load, not only the app's own normal traffic.
3. Measure idle and loaded latency against a stated endpoint and method.
4. Record actual throughput, duration, packet loss/error behavior, and repeated samples.
5. Compare equivalent network conditions as closely as possible.

Do not grade or market a result when the VPN state, flow path, or latency method is unknown. The current service does not claim raw-ICMP support; a future measurement method must state exactly what it measures.

## Documentation-only validation

Review every canonical document and report updated/reviewed-no-change reasons,
new files, contradictions and remaining gates. Run `git diff --check`; verify
relative Markdown destinations/fragments and new external primary-source links.
Print commands and literal counts/errors, including blocked or unreachable URLs;
an HTTP response alone does not establish a policy claim. Confirm the commit
contains only intended documentation and no app/native/build changes. Run the
required source checks above and report cached versus executed work honestly.

## When to stop testing

Stop the test immediately and disable the VPN if traffic stalls, the device loses ordinary connectivity, a restart loop appears, a wake-lock warning is observed, or unexpected traffic handling occurs. Capture a redacted diagnostic record only after connectivity is restored.

## Internal Stage 1 checks

Exact Wi-Fi-first/cellular-second owner instructions, JSON variants, field
meanings and proposed screening criteria are in [Experiments](EXPERIMENTS.md).
Stage 1 remains UNPASSED. The first owner Wi-Fi baseline and SO_RCVBUF=65536
runs establish the narrow acceptance/readback and exact transfer-integrity scope
recorded there. Sender-observed window/throughput, deliberate stall/zero-window
recovery, loaded-latency benefit, cellular efficacy and broader compatibility
remain UNVERIFIED — REQUIRES PHYSICAL EXPERIMENT. Keep these conclusions separate.

Alongside the four required Gradle tasks, run:

```text
gradlew :app:assembleRelease
python tools/verify_harness_build.py
python -m unittest discover -s tools -p "test_*.py" -v
```

Release assembly checks unsigned packaging, not distribution. The verifier
checks actual selected r28c from CMake caches/source.properties and debug-only
harness libraries/components on all ABIs. CI runs these checks and needs no
external test endpoint. src/testDebug tests cover mapping, absent fields,
exact byte/hash accounting, protection/cancellation/cleanup failures, bounded
storage and dynamic settings. Endpoint tests use synthetic partial-write peers.
Host tests cover manifest bounds, isolated presets, stable IDs, deterministic
seeded pairing, redaction, hash classification, stop/continue policy and endpoint
failure cleanup. They do not execute a physical network experiment.

After one-time VPN consent, the host batch verifies a clean checkout containing
current origin/main, an exact installed debug APK hash, one selected ADB target,
the requested Android Network and all manifest budgets before sending data. Each
run checkpoints raw/private artifacts under ignored `output/stage1/` and updates
an allowlisted `redacted-summary.json`. Ctrl+C or an `ABORT` file signals worker
cancellation; completed and contrary records remain. Missing consent/network/
endpoint or build provenance stops before or at the affected run. Missing TShark
or ping evidence is marked skipped/unavailable and cannot fail open into an
efficacy conclusion. See [Experiments](EXPERIMENTS.md) for the literal command.

Execute `:app:connectedDebugAndroidTest` only on a designated emulator/test phone.
SocketHarnessInstrumentationTest covers native prefix boundaries for all twelve
fields, invalid-FD errno, denied-protect closure and an ephemeral loopback stream
with redacted export. Its ordinary loopback test uses a fake true protector and
is NOT VpnService.protect evidence. The optional real-service test requires the
owner to prepare permission first via HarnessActivity and no active VPN; otherwise
it is skipped. It checks actual protect, no VPN Network, cancellation and closure.
Tests never accept consent or replace another VPN themselves. Record skips.
The existing packaged-stub test still requires unavailable/ABI v2/zero features.

To retain an emulator's explicitly prepared permission, install app and test APKs,
open the internal Activity and accept Android's consent, then execute directly:

```text
adb -s emulator-5554 shell am instrument -w com.bufferbloatshaper.test/androidx.test.runner.AndroidJUnitRunner
```

Substitute the actual designated emulator serial. Direct execution avoids a
Gradle test runner uninstall/reinstall discarding the prepared state. Report API,
build and ABI. Real protect success without a VPN route still does not prove
exclusion from an active route: that belongs to Stage 2. No instrumentation or
emulator result proves physical window efficacy or carrier latency benefit.
