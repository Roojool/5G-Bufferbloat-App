# Bufferbloat Shaper: project context for an AI workflow planner

Snapshot date: 2026-09-10. Public repository: [Roojool/5G-Bufferbloat-App](https://github.com/Roojool/5G-Bufferbloat-App).

This file is a portable briefing for an AI that will help the project owner write detailed implementation prompts. It explains the product, the current executable behavior, the work already completed, the future phases, and the evidence required to move between them. It does not authorize implementation by itself.

**Current starting point:** Phase 0, the public-source foundation, is implemented. Phase 1, the real native traffic engine, has not been completed or enabled. The shipped native library deliberately reports unavailable. With valid configuration, starting the app's shaper shows an unsupported state before any VPN route is created. The current app does not shape or forward device traffic.

This is a dated summary, not another authoritative plan. When writing a new prompt, re-read the live repository and reconcile changes since this snapshot. Do not mark work complete from a class name, a screenshot, this handoff, or a successful build alone.

## 1. How to use this file

The project owner can paste this entire file into another AI and ask it to use the prompt-writing instructions in sections 13–15. An AI with repository access should read the linked sources before drafting an implementation task. An AI without repository access should identify its assumptions and require the implementing agent to verify them before editing.

Use these sources for their respective subjects:

| Source | Authority |
|---|---|
| `AGENTS.md` | Repository agent workflow, required reading, debug gates, evidence, and PR rules |
| [README.md](../README.md) and [LIMITATIONS.md](LIMITATIONS.md) | What a real user receives today and what may be claimed publicly |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Actual engineering structure, contracts, safety invariants, and production target |
| [ROADMAP.md](ROADMAP.md) | Current phase numbering, dependency order, and release gates |
| [TESTING.md](TESTING.md) | Required verification procedures and the distinction between builds and behavior |
| [COMPATIBILITY.md](COMPATIBILITY.md) | Build targets, missing device evidence, and requirements for support claims |
| [SOURCE_BUILD.md](SOURCE_BUILD.md) | Build prerequisites and required local checks |
| [PRIVACY.md](../PRIVACY.md), [CONTRIBUTING.md](../CONTRIBUTING.md), [SECURITY.md](../SECURITY.md) | Privacy boundaries, contribution process, and vulnerability reporting |
| [native/README.md](../native/README.md) | Existing JNI ABI, ownership, protection callback, and stub behavior |
| [mobile-bufferbloat-shaper-plan.md](../mobile-bufferbloat-shaper-plan.md) | Historical motivation only; its phase numbers and some proposals are superseded |

At this snapshot, `AGENTS.md` is committed on the hygiene branch in [PR #1](https://github.com/Roojool/5G-Bufferbloat-App/pull/1), which is still open. Its exact proposed contents are available at [commit 4f5df67](https://github.com/Roojool/5G-Bufferbloat-App/blob/4f5df675d3968c88b083b8adc9320525d733bf1f/AGENTS.md). Do not assume it has reached `main` until the PR is merged. Its rules are summarized in section 12 so this briefing remains usable on its own.

## 2. Product purpose and boundaries

Bufferbloat Shaper explores whether a local Android tool can reduce extra latency during a busy connection while retaining useful throughput. The desired experience is more responsive interactive traffic while uploads or downloads occupy the connection. That is the engineering goal, not a measured result of the current app.

The app uses Android's public `VpnService` interface as a local packet entry point. It is not a hosted VPN subscription: no project-operated server should carry user traffic. A future native engine should forward traffic directly to its normal destination. Kotlin owns Android lifecycle, configuration, screens, notifications, and local diagnostics.

The permanent product boundaries are:

- Local operation with no project-operated proxy, telemetry backend, analytics service, traffic resale, or automatic diagnostic upload.
- No TLS interception, local certificate authority, payload decryption, or traffic-content inspection.
- No ability or promise to force 5G, radio bands, carrier aggregation, NSA, or SA. The repository name does not imply those capabilities.
- No QUIC/UDP download-shaping claim. Safe UDP/QUIC forwarding is still mandatory if a default VPN route is enabled.
- TCP download control remains a planned capability that needs its own implementation and evidence.
- Unsupported states must be visible and must not silently leave device traffic captured without a functioning path.
- Broad compatibility means tested combinations plus safe fallback, never every phone, modem, carrier, and Android build.
- Initial public distribution is Apache-2.0 source on GitHub. No APK/AAB download, Play Store launch, or universal-compatibility claim is part of the initial release scope.

Why these limits exist: a local shaper cannot control modem/carrier decisions or eliminate remote congestion and radio scheduling. TCP and QUIC have different transport behavior, so TCP flow-control techniques must not be advertised as QUIC control. An unfinished packet engine can break connectivity; route activation is therefore an evidence gate. The detailed protocol and dependency design must be checked against primary upstream documentation when implementation begins.

## 3. How the project reached this point

The early product plan proposed a local VPN packet path, queue management, automatic calibration, download control, and validation. Earlier development experimented with a hand-written Kotlin relay, DNS changes, and IPv6 routing. The later review identified that a UI and simple shaping algorithms did not establish a reliable TCP/IP forwarding implementation.

The production direction changed to a mature native stack, with gVisor Netstack as the planned dependency. The unsafe Kotlin relay was removed from the shipped module. The replacement currently consists of a JNI contract and an intentionally unavailable native stub. This preserves a safe stopping point while the real engine is designed and verified.

The public-source foundation added license and policy documents, architecture and roadmap documentation, source-build instructions, CI, native packaging, lifecycle containment, local configuration, and honest runtime-state scaffolding. Git history was cleaned during the earlier public-foundation work. Do not repeat that historical rewrite: the current workflow prohibits force-pushing `main`.

The latest hygiene task removed unrelated local root files `RESEARCH.md`, `PLAN.md`, `AGENT_CONTEXT.md`, `AGENT_PROMPT.md`, and `CLAUDE.md`. They were untracked, so there are no deletion entries for them in Git. They must not be recreated as competing plans. The authoritative `docs/` files were unchanged.

The same hygiene PR adds root-scoped ignores for `.codex-artifacts/`, `.codex-finalizer/`, and `output/`, retaining the local files. Those folders held a presentation generator, a runtime dependency junction, rendered slides, deck drafts/final files, and validation reports. It also tracks the existing `AGENTS.md` without altering its contents. The pitch deck is a communication artifact, not runtime evidence.

## 4. Repository and review snapshot

| Item | Verified state on 2026-09-10 |
|---|---|
| Public `main` | `7cb9289147c519ca42fee1603fceeda2587cf447`, “Harden fail-closed native lifecycle” |
| Main CI | [Android CI run 33964804525](https://github.com/Roojool/5G-Bufferbloat-App/actions/runs/33964804525), completed successfully for that commit |
| Hygiene work | `4f5df675d3968c88b083b8adc9320525d733bf1f` on `codex/repo-hygiene-cleanup` |
| Hygiene review | [PR #1](https://github.com/Roojool/5G-Bufferbloat-App/pull/1), open and unmerged; its PR and branch CI runs both succeeded |
| Required main check | `Build, unit test, and lint`, with the branch required to be up to date |
| Required review | One approving review; stale reviews dismissed |
| Other protection | Admins included; conversations must be resolved; force-push and deletion disabled |

This handoff is documentation work on a separate `codex/ai-project-handoff` branch based on `main`; it does not merge or complete the hygiene PR. Refresh commit, review, and CI state at the start of every future implementation task.

## 5. The actual user workflow today

The Compose app has dashboard, statistics, validation/test, and settings screens. Configuration is stored locally. The intended controls are present, but an enabled-looking setting is not evidence that packets are being shaped.

1. The user opens the app and sees the current runtime state and local settings.
2. The user can set manual upload/download limits and configuration preferences. Startup requires positive upload and download caps; the default zero values can cause a configuration error before native capability is checked.
3. A start request with valid configuration checks native availability, ABI compatibility, and required feature bits before route establishment. The dashboard also checks capability before requesting VPN activation.
4. The shipped library reports unavailable. The user receives an explicit unsupported explanation; no TUN route is established and no traffic is relayed by the app.
5. Screens and notifications use shared runtime state. With no running engine, measured throughput and validation results remain unavailable.
6. The validation screen explains missing prerequisites. It does not run a real before/after benchmark.
7. Settings offer manual diagnostic sharing through Android's share sheet. Nothing is uploaded until the user chooses to share it.

The normal valid-config start path is:

```text
Start request
  -> validate configuration
  -> check native capability and ABI
  -> unavailable
  -> UNSUPPORTED state
  -> no VPN route; ordinary networking remains in use
```

The runtime enum also contains `STOPPED`, `STARTING`, `RUNNING`, `STOPPING`, and `ERROR`. These states describe the lifecycle model; their existence does not prove that the real engine reaches or survives `RUNNING`.

## 6. Components that exist and their implementation limits

Source paths below are relative to the repository. Most Kotlin classes are under `app/src/main/kotlin/com/bufferbloatshaper/`.

| Component | Existing work | Work still required |
|---|---|---|
| `MainActivity.kt`, `ui/screens/` | Compose navigation and dashboard/statistics/settings/validation screens | Live engine behavior and measured results |
| `model/ShaperConfig.kt`, `util/Preferences.kt` | Manual caps, preset/configuration validation, local persistence, MTU and routing settings | Apply policies through a verified traffic engine |
| `model/VpnRuntimeState.kt` | Observable runtime model and bounded local health timeline | Valid native samples and complete per-flow/event integration |
| `vpn/ShaperVpnService.kt` | Serialized lifecycle, capability gate, future TUN ownership, failure cleanup, metric-polling scaffold | Real forwarding and device verification under failures and transitions |
| `vpn/NativeLifecycleWatchdog.kt`, `VpnSocketProtectionGate.kt` | Bounded lifecycle containment and synchronized protection gate | Real-engine failure, revocation, callback, and teardown evidence |
| `nativeengine/NativeEngineBridge.kt` | Typed Kotlin adapter, ABI/feature checks, session and metric contracts | A working native engine behind the contract |
| `native/` | C++ JNI bridge, ABI v2 header, CMake build, unavailable stub for three ABIs | Pinned gVisor/Go dependency and real engine adapter |
| `shaping/` | Kotlin TokenBucket, CoDel-style and fair-queue reference logic with JVM tests | Native queue placement, scheduling, retransmission integration, and performance evidence |
| `calibration/PassiveEstimator.kt` | In-memory independent-sample/percentile scaffold | Probe generation, robust sample lifecycle, per-network persistence and guarded updates |
| `calibration/NetworkStateMonitor.kt` | Network monitoring scaffold and optional RAT information | Complete default-network/SIM/MTU/captive-portal recovery integration |
| `validation/TestResult.kt` | Validation result/grade calculation model | Independent loads, verified shaper state, measurement persistence and repeatable comparisons |
| `util/LocalDiagnostics.kt`, `Notifications.kt` | Structured manual export and runtime-based notification utilities | Additional measured health/capability coverage as implementation advances |

Four profile names exist: Safe, Balanced, Maximum Throughput, and Custom. The first three currently select headroom factors 0.80, 0.85, and 0.93 with corresponding queue settings. These are saved configuration values, not measured throughput retention. Android application routing supports all apps, only selected apps, or excluded apps. An allow/deny list is not a separate rate/AQM policy for each app.

Smart Mode is a stored setting without a live adaptive implementation, and saving configuration currently forces automatic calibration off. `PassiveEstimator` and `NetworkStateMonitor` have no production callers. Settings has a separate connectivity observer that refreshes the diagnostic capability summary; it does not supply traffic-path handover recovery.

The existing diagnostic export omits payloads, DNS query names, IP addresses, app package lists, and phone/SIM identifiers. It does include device make/model, OS, local timestamps, settings, broad network information, and structured transitions. A user must review it before sharing. Raw error strings and raw logcat must not become the export format. The in-process health timeline is bounded to 100 transitions.

## 7. Native contract: reuse what is already there

**ABI v2 already exists.** `native/include/bufferbloat_native_engine.h`, `native/src/jni_bridge.cpp`, `native/README.md`, and the Kotlin bridge describe the same version. Phase 1 step 2 is not a reason to start another bridge from scratch: review and extend the existing contract, then prove a real engine honors it.

The current contract covers creation, start, configuration update, stop/join, health/event polling, aggregate metrics, and per-flow metrics. Kotlin/JNI uses opaque session tokens rather than exposing pointer values. Exact ABI agreement, native availability, and all required feature bits are prerequisites for activation; feature bits are not a substitute for tests.

The native stub returns unavailable, opens no forwarding sockets, forwards no packets, and reports zero traffic metrics. `BUFFERBLOAT_WITH_GVISOR` is currently a CMake guard that deliberately fails when enabled because no real implementation is vendored. It is not an existing working debug engine toggle.

Preserve these implementation rules:

- Kotlin owns the original `ParcelFileDescriptor`. A future successful engine duplicates the borrowed TUN FD for its own use and closes only what it owns.
- Native code copies stack-owned start/configuration records before returning. It must not retain pointers to temporary ABI input records.
- Every outbound socket must pass the narrow `VpnService.protect(fd)` callback before bind/connect/send. A failed or throwing callback must close the socket and return a typed error; never connect unprotected.
- The protection callback/context remains valid until all native workers join. No callback may run after successful stop.
- Stop is synchronous with respect to worker completion, duplicated descriptor cleanup, and callback lifetime. Failed stop is quarantined rather than freed unsafely.
- One runtime generation owns the active TUN, writers, work, and wake lock. Stale sessions and repeated lifecycle calls must be safe.
- The independent watchdog bounds native-touching operations. A stuck or unsafe teardown can trigger a generic local marker and process termination so Android closes app-owned descriptors. This fallback still needs real-engine evidence.
- Metrics/events remain typed and bounded. They must not contain packet payloads, endpoint addresses, DNS names, or other private traffic identifiers.

## 8. Intended traffic path and shaping mechanisms

The production target is:

```text
Android apps
  -> VpnService TUN
  -> mature native TCP/IP stack
  -> protected direct sockets
  -> ordinary Internet destinations

Kotlin: lifecycle, configuration, screens, notifications, local diagnostics
Native: TCP/IP state, forwarding, queues, retransmission, ordering, metrics
```

TCP upload should eventually use a token-bucket rate ceiling, controlled-delay queue management, and fair scheduling. A critical design decision is exactly which queue each mechanism owns. It is unsafe to discard arbitrary bytes after an outer TCP socket has accepted them; transport semantics and retransmission must remain correct. Existing Kotlin algorithm tests do not resolve native queue placement.

TCP download control is a desired receive-window/backpressure capability. A later design must identify which TCP endpoint and receive buffers can influence the real sender, including the native-to-OS-socket boundary. Do not assume that changing only the app-facing gVisor receive window throttles the remote server's download. The current roadmap does not give production TCP ingress control a separate numbered milestone; flag this for explicit scheduling and evidence rather than quietly treating it as already delivered.

UDP forwarding comes before UDP pacing. QUIC downloads must continue to work as forwarded traffic even though download shaping is out of scope. Initially, IPv6 must bypass an incomplete IPv4-only path; enabling IPv6 routes requires a separate functional implementation and tests. Preserve ordinary DNS behavior and the user's policy instead of substituting a hard-coded resolver.

## 9. Future phases and useful prompt boundaries

The phase names and ordering below come from `docs/ROADMAP.md`. Suggested acceptance criteria explain how to turn that roadmap into reviewable tasks; they are not evidence that those tasks have passed.

### Phase 0: public-source foundation — implemented

License, documentation, CI, build setup, three-ABI native packaging, lifecycle and UI scaffolding, configuration, and structured local diagnostics exist. The next task is Phase 1 preparation, not a claim that the app already works.

### Phase 1: design and build a real local engine

| Roadmap step | Bounded task for a future implementation prompt | Required evidence before treating it as complete |
|---|---|---|
| 1. Dependency and build foundation | Pin an Apache-compatible gVisor revision, Go toolchain and dependencies; record licenses/notices; establish repeatable Android builds | Exact revisions/tool versions, license inventory, reproducible commands and actual results for arm64-v8a, armeabi-v7a, and x86_64; default app still unavailable |
| 2. Native protection/lifetime contract | Audit existing ABI v2 and implement/prove its use by the real engine | Protect-before-connect, false/throw paths, no callback after join, copied input lifetime, stale-token and failed-start cleanup tests |
| 3. IPv4 forwarding | Implement complete TCP and safe UDP forwarding with protected direct sockets, bounded queues, health/events/metrics and serialized teardown | Packet/checksum/state tests, transfer integrity, bounded resource behavior, and explicit failure-path results in the authorized internal test setup |
| 4. TCP upload shaping | Put TokenBucket, bounded fair queues and CoDel where the native stack owns the relevant transport/queue semantics | Deterministic queue/rate/fairness tests and load evidence that preserves data integrity; no arbitrary dropping of accepted TCP stream bytes |
| 5. Lifecycle and route gate | Prove start/update/stop, health-failure containment, TUN ownership and cleanup across ABIs | Literal emulator/device results and review; only then consider the separate change enabling routes by default |

All real-engine work stays behind an explicit debug/internal build gate until Phase 1 step 5's device verification passes. No prompt may make the shipped stub report available just to get traffic through it. An internal traffic test must explicitly state its permitted route setup, build gate, device, rollback and verification requirements. An ordinary debug APK is not automatically an authorized experimental-engine build.

Default production route activation and public-claim updates require their own reviewed change. An internal implementation PR must not edit the README status banner or `docs/LIMITATIONS.md` as though ordinary users receive the experimental behavior.

### Phase 2: carefully expand functionality

After the foundation is proven, divide work into dependency-aware tasks for dual-stack forwarding and A/AAAA behavior; preservation of DNS policy and redacted failure diagnostics; UDP upload pacing; live health/events/per-flow metric integration; and network/default-network, Wi-Fi/cellular, captive-portal, MTU and optional RAT signals.

Each task needs its own safe pause/recovery tests. Do not treat the existence of `NetworkStateMonitor` as complete handover support. Do not introduce radio control. IPv6 routes remain gated until actual dual-stack operation passes.

### Phase 3: measurement and product features

Implement per-app traffic profiles only once the engine can apply them. Add independent physical-network probes and persist separate rolling samples per network. Select measured, guarded percentile/headroom updates and avoid feeding already-shaped throughput back into its own cap.

Implement a validation flow that explicitly confirms shaper-off/on state, runs independent upload and download loads, records throughput and queue delay, persists results locally, and supports repeated equivalent comparisons. No fabricated grades, sample charts, or configured limits presented as measurements are acceptable.

Extend only app-owned structured diagnostics. Decide probe endpoints, user consent/data use, sample aging, network identity/privacy, stale data behavior and endpoint failures in the relevant task. A chosen external test endpoint still receives probe traffic; local-only operation does not mean that tests can measure Internet performance without contacting anything. Do not silently add a project-operated service.

### Phase 4: evidence before a tagged source release

The required matrix is India-first: API 26–35 emulator coverage; Qualcomm and MediaTek physical devices across at least two OEMs; Airtel, Jio and Vi where service is available; Wi-Fi; and relevant network transitions.

Release evidence must include:

- Ten consecutive VPN start/stop cycles without crashes, restart loops, leaks, stale writers, or wake-lock problems.
- Working DNS A/AAAA, browsing, TCP, UDP/QUIC and required IPv4/IPv6 behavior, plus captive-portal recovery.
- Five checksum-verified transfers of at least 50 MB without corruption or stalls.
- Carrier/default-network handover and at least 30 minutes of screen-off operation.
- Repeated shaper-off/on trials at multiple times of day with lower loaded latency and acceptable throughput retention.
- Published compatibility results only for tested combinations, with exact version/device/network conditions and redacted literal evidence.

Do not add placeholder physical-device successes. A successful build, ABI library load, or single browse session does not pass the matrix. A tagged source release needs release notes, known limitations and reproducible build instructions; APK/AAB or store distribution is not authorized by that milestone.

## 10. Build and verification context

Current build stack: JDK 21, Gradle 8.9 with wrapper checksum, Android Gradle Plugin 8.7.3, Kotlin 2.1.0, Compose, Android SDK 35, NDK r28c (`28.2.13676358`) and CMake 3.22.1. Minimum Android API is 26; compile/target API is 35. Native targets are `arm64-v8a`, `armeabi-v7a` and `x86_64`. Java/Kotlin bytecode target 17 is separate from the required JDK 21 build runtime.

Use the live build files and `docs/SOURCE_BUILD.md` if any versions change. Do not commit a machine-specific JDK path, `local.properties`, signing material, local captures or diagnostics. Go and gVisor are not pinned in the current source; choosing them is Phase 1 step 1.

Required local commands from the repository root:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

CI additionally compiles the instrumentation test APK:

```text
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

`assembleDebugAndroidTest` compiles tests; it does not execute them on a device. CI currently runs builds, JVM tests and lint on Ubuntu with JDK 21. It does not execute the physical carrier matrix or establish network performance.

Existing test sources are under `app/src/test/kotlin/com/bufferbloatshaper/` and `app/src/androidTest/kotlin/com/bufferbloatshaper/`. JVM coverage includes shaping reference behavior, configuration/routing validation, runtime timeline state, structured export, validation arithmetic, native contract logic, watchdog and socket-protection gate behavior. Instrumentation source covers the unavailable native boundary. Future native packet/TCP/DNS tests, live service lifecycle tests and field measurements are still required.

A fresh isolated-worktree check during preparation of this handoff on 2026-09-10 ran the four-task CI-equivalent command above against the unchanged app/native source at `7cb9289`. Actual output:

```text
> Task :app:assembleDebugAndroidTest
> Task :app:testDebugUnitTest
> Task :app:assembleDebug
> Task :app:lintDebug

BUILD SUCCESSFUL in 1m 31s
82 actionable tasks: 82 executed
```

The generated JVM XML reports recorded 32 tests with zero failures, errors or skips. The instrumentation APK was compiled, not run on a device. The build emitted SDK XML-reader and Kotlin/Android deprecation warnings but succeeded. These are source-level results; no physical device or carrier behavior was tested in this check.

Historical local hygiene-check evidence on 2026-09-10 used the combined assemble/unit/lint command and reported:

```text
> Task :app:assembleDebug UP-TO-DATE
> Task :app:testDebugUnitTest UP-TO-DATE
> Task :app:lintDebug

BUILD SUCCESSFUL in 1m 22s
57 actionable tasks: 11 executed, 46 up-to-date
```

This is explicitly a previous check, with assembly and JVM results reused. Fresh prompts must request fresh evidence appropriate to their changes rather than copying these lines as a new result. Main CI and the hygiene PR's successful CI are linked in section 4. No physical carrier-support result is established by those checks.

## 11. Known planning traps to resolve explicitly

- **Phase numbering:** historical plan and some source comments use old phase numbers. Current `docs/ROADMAP.md` is the sequence to follow.
- **Already-existing ABI:** ABI v2/protection/opaque handles exist as scaffolding. Audit and integrate them; do not declare step 2 proven merely because the interfaces compile.
- **Build flag:** the existing CMake gVisor option fails on purpose. A working internal engine gate must be designed and tested in its authorized task.
- **App routing versus policy:** selected/excluded packages decide what enters a VPN route; they do not implement individual rate limits or AQM profiles.
- **Limits versus samples:** configured Mbps/headroom are user settings. No native traffic means no live measured throughput.
- **Calibration naming:** `PassiveEstimator` accepts independently supplied samples; its name does not authorize learning capacity from rate-limited relay traffic. Sample aging, network separation and persistence still need work.
- **TCP ingress:** keep it visible as a planned capability, but obtain an explicit design, place in the current roadmap and evidence gate before implementation. Historical claims do not establish a working receive-window controller.
- **IPv6/DNS:** do not restore earlier routing/resolver behavior just to fill a checkbox. Verify forwarding and policy preservation first.
- **Release naming:** the Gradle version name `1.0.0` is a build configuration value, not proof of a released or supported product.
- **Old claims and external facts:** historical market comparisons, radio assumptions, store policies, and performance targets are not verified product claims. Check primary upstream documentation for technical decisions; store distribution is outside the initial source-release scope.

## 12. Agent and Git workflow rules

Before code changes, read in full: README, ARCHITECTURE, ROADMAP, LIMITATIONS, TESTING, COMPATIBILITY, SOURCE_BUILD, PRIVACY, CONTRIBUTING, SECURITY, and the historical mobile plan. Apply the current `AGENTS.md` when available; the owner has already supplied its rules for this workflow.

For an implementation task:

1. Inspect Git status, current branch/commit, open PR dependencies, live main protection and relevant CI. Preserve unrelated user changes and distinguish untracked local files from committed source.
2. Create a focused `codex/<short-topic>` branch from the appropriate verified base. If work depends on an unmerged PR, state that dependency; never silently call it merged.
3. Implement only the task's scoped objective. Keep the default native engine unavailable and internal work gated until the applicable verification allows more.
4. Update `docs/ARCHITECTURE.md` and `docs/ROADMAP.md` in every PR that changes engineering status. Update `docs/TESTING.md` when a new verification procedure is established. Do not change those files for unrelated housekeeping or merely to create activity.
5. Do not change README's status banner or LIMITATIONS' real-user claims for debug-gated work. Only the PR that removes the gate and enables the behavior by default may update them.
6. Add compatibility entries only for combinations actually verified on physical hardware, never placeholder results.
7. Run required build/unit/lint checks and the targeted native/instrumentation/device checks appropriate to the task. Record commands, actual output, commit and device context. State cached, skipped and unavailable checks accurately.
8. Commit intentionally, push the task branch and open a PR with actual verification output. Do not push directly to `main`, force-push it, weaken protection, or merge your own PR.
9. Report the PR link, changed behavior, proven evidence and remaining gates. A missing physical device is an unpassed device gate, not permission to substitute a success claim.

The owner's explicit instructions for an individual task take precedence over generic workflow defaults. For example, the earlier read-only status check explicitly required no changes and no new branch. This handoff itself is documentation only and does not advance the engineering phases.

## 13. Instructions for the AI that will write future prompts

Your role is to turn the current roadmap into precise tasks for an implementing agent. You are not being asked to claim completion, redesign the product boundaries, or invent missing test results.

Start by identifying the current commit, open PRs, completed evidence and exact next roadmap step. Separate what already exists from what must be integrated and proven. Then prepare one bounded implementation prompt at a time, with later prompts dependent on review and evidence from earlier ones.

Every detailed prompt should contain:

- The single objective, exact roadmap step and expected user/engineering outcome.
- The verified starting point, base branch/commit, open PR dependencies and relevant source paths.
- Required reading and the existing interfaces or scaffolding to reuse.
- In-scope changes and explicit boundaries; identify any design choice not yet decided by the authoritative docs.
- The default-build behavior, internal/debug gate and rule that forbids unsupported route activation.
- Ordered implementation work, including native ownership, error handling, privacy and concurrency requirements where relevant.
- Tests and commands with acceptance conditions, including specific negative paths; never invent expected performance results.
- Required documentation updates and public-claim restrictions.
- The `codex/<topic>` branch, commit, PR and review workflow.
- A completion report containing actual command output, results, limitations and the next unpassed gate.

For later-stage tasks, carry forward exact results and newly approved decisions from the previous PR. Do not give a single prompt permission to implement all future phases or to enable routes just because an algorithm compiles. Technical dependency choices should use primary upstream sources and exact pinned revisions, not an AI's recollection of current APIs.

## 14. Reusable implementation-prompt template

```text
Task: [one bounded objective]
Roadmap: docs/ROADMAP.md, Phase [number], step [number/name]
Branch: codex/[short-topic]

Starting point:
- Verify current Git state, live base commit, open PR dependencies and CI.
- Expected completed work: [specific implementation and evidence].
- Existing components to reuse: [paths/contracts].
- Do not assume a pending PR is merged.

Read AGENTS.md and every required authoritative document before code changes.
Treat the historical mobile plan as background, not the phase sequence.

Implement:
1. [Concrete change with scope and contract]
2. [Failure/lifetime/privacy handling]
3. [Targeted tests]

Preserve:
- Local-only product boundaries and normal-network safe fallback.
- The default unavailable engine until the route gate is explicitly passed.
- Explicit internal/debug gating for experimental engine work.
- README/LIMITATIONS public claims while work remains gated.
- Unrelated user changes and authoritative document ownership.

Acceptance and evidence:
- [Functional and negative-case criteria tied to this task]
- Run required assemble/unit/lint checks and applicable native/device checks.
- Paste actual commands/results; distinguish cached, compiled-only, and executed.
- If a device-dependent check cannot run, report it as unverified.

Documentation:
- Update ARCHITECTURE and ROADMAP for real engineering changes.
- Update TESTING for new repeatable verification procedures.
- Add COMPATIBILITY entries only for physically tested combinations.

Delivery:
- Commit only scoped files on the codex branch and open a PR with evidence.
- Do not push to main, weaken protection, or merge your own PR.
- Report what is implemented, what is proven, and what gate remains.
```

## 15. Suggested first request to the prompt-writing AI

```text
Use this handoff to write a detailed implementation prompt for Phase 1
step 1: pinning a reviewed, Apache-compatible gVisor Netstack dependency,
Go toolchain, licenses/notices and reproducible Android build process.

Require the implementing agent to inspect the latest repository and PRs
first. The app currently ships an unavailable native stub. ABI v2 and
socket-protection/lifetime scaffolding already exist. Reuse those facts.

Keep this task confined to the dependency/build foundation and its evidence.
Do not implement forwarding, replace the stub's public availability result,
enable VPN routes, or advance later phases. Specify all three Android ABIs,
upstream verification and dependency-license checks, default-unavailable
regression checks, documentation updates, actual build/test output, and the
protected-main PR workflow.

Identify unresolved technical choices and what evidence the implementing
agent must gather. Do not pick a gVisor revision or Go version from memory,
and do not treat an unmerged hygiene PR as part of main. Return the prompt
and a short acceptance checklist, without starting implementation yourself.
```

When a later session updates this handoff, date the new snapshot and distinguish merged source, pending PRs, internal experiments and physically verified behavior. The authoritative documentation and literal evidence remain the basis for every future prompt.
