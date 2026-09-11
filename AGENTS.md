# Agent instructions

## Mandatory context and documentation protocol

Before doing anything else, read this file completely. Then read all of these
canonical documents completely before making any change:

- [README.md](README.md)
- [docs/PROJECT_CONTEXT.md](docs/PROJECT_CONTEXT.md)
- [docs/DESIGN_DECISIONS.md](docs/DESIGN_DECISIONS.md)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/ROADMAP.md](docs/ROADMAP.md)
- [docs/LIMITATIONS.md](docs/LIMITATIONS.md)
- [docs/TESTING.md](docs/TESTING.md)
- [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)
- [docs/SOURCE_BUILD.md](docs/SOURCE_BUILD.md)
- [docs/EXPERIMENTS.md](docs/EXPERIMENTS.md)
- [docs/PLAY_COMPLIANCE.md](docs/PLAY_COMPLIANCE.md)
- [PRIVACY.md](PRIVACY.md)
- [CONTRIBUTING.md](CONTRIBUTING.md)
- [SECURITY.md](SECURITY.md)
- [mobile-bufferbloat-shaper-plan.md](mobile-bufferbloat-shaper-plan.md)

Also read `docs/RADIO_OPTIMIZATION.md` completely if it exists. The mobile plan
is a superseded historical record, not the implementation contract. Follow the
documentation hierarchy in PROJECT_CONTEXT. Report missing canonical files;
never silently substitute a document from an unmerged PR for checked-in truth.
Read applicable nested instructions and native/README.md for native work.

Establish the current branch, commit, working tree, remote/main state, open PRs,
CI state, and actual Gradle/native configuration. Do not assume an unmerged PR
is part of main. Preserve unrelated work and identify any explicit PR dependency.

## Evidence, safe activation, and public claims

"Built" and "works" are different claims. Never report a task done without
literal command/test/device output. Distinguish source inspection, cached
checks, compiled instrumentation, executed tests, runtime probes, and physical
network evidence. Record experiments in docs/EXPERIMENTS.md; never invent results.

The checked-in native engine is an intentional unavailable stub. Do not make it
report available merely to create a route. Future engines require an explicit
debug/internal build gate and the staged evidence in docs/ROADMAP.md. The existing
gVisor CMake option deliberately fails and is not a working experimental gate.
Missing mandatory forwarding/lifecycle capabilities must prevent interception;
missing optional capabilities must disable the feature visibly. Never silently
black-hole traffic or add an unsafe fallback relay.

README.md and docs/LIMITATIONS.md describe what an ordinary user gets today.
Debug/internal work is not public behavior. Preserve the prototype status banner
and default-behavior claims until a separately reviewed PR passes the route gate
and enables the behavior by default. Clarifying limitations is allowed; claiming
gated features work is not. Compatibility successes require literal physical
evidence for the exact feature/device/network combination, never placeholder
success rows. Keep local-only/no-relay/no-telemetry/no-TLS-interception boundaries.

## Living documentation and final report

After engineering work, update docs/PROJECT_CONTEXT.md with the actual state,
evidence, open experiments, and next unpassed gate. Update ARCHITECTURE and
ROADMAP whenever engineering status/design changes, TESTING when procedures
change, DESIGN_DECISIONS when decisions change, and other affected documents
when their truth changes. Keep planned behavior explicitly separate from code.
Recheck primary sources for dated policy/toolchain claims; documentation alone
must not pretend to change the Gradle target SDK.

Every task ends with a documentation review/report: list each canonical document
as updated or reviewed/no change with a reason (and optional absent files as
absent); report contradictions, changed/new files, implementation truth, next
engineering gate, unresolved decisions, branch, commit, PR, and literal validation
and link-check output. Review all canonical documents even when only some need
edits. Do not claim completion without actual output.

## Git workflow

Use a focused `codex/<short-topic>` branch, commit scoped work, and open a PR with
actual verification output. Never push directly to main, force-push main, weaken
protection, or merge your own PR. Main currently requires passing CI and one
approving review; inspect live protection rather than assuming it is unchanged.
An owner's explicit task instructions take precedence over generic defaults.
