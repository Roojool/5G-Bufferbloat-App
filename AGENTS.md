# Agent instructions

Before any code change, read in full: README.md, docs/ARCHITECTURE.md,
docs/ROADMAP.md, docs/LIMITATIONS.md, docs/TESTING.md, docs/COMPATIBILITY.md,
docs/SOURCE_BUILD.md, PRIVACY.md, CONTRIBUTING.md, SECURITY.md,
mobile-bufferbloat-shaper-plan.md.

Verification rule: "built" and "works" are different claims (see
docs/TESTING.md). Never report a task done without pasting the actual
command/test/device output that proves it. The checked-in native engine is
an intentional fail-closed stub — do not make it report available for a
real route until the specific roadmap step says so, and only behind an
explicit debug/build flag until Phase 1 step 5's device verification passes.

Public-claim rule: README.md and docs/LIMITATIONS.md describe what a real
user gets today. Anything built behind the debug/internal flag is not yet
true for a real user. Do not edit README.md's status banner or
LIMITATIONS.md's claims for any debug-gated work — only the PR that actually
removes the gate and enables a route by default may update them.

Living-doc rule: docs/ARCHITECTURE.md and docs/ROADMAP.md track real
engineering status and get updated every PR that changes it. docs/TESTING.md
gets updated whenever a PR establishes a new verification procedure, so the
next session can repeat it. docs/COMPATIBILITY.md only gets real entries for
combinations actually tested on physical hardware — never placeholder rows.

Workflow rule: main is protected (CI must pass, one approving review
required, no force-push). For every task, create a branch named
codex/<short-topic>, commit your work there, and open a pull request with
the actual verification output in the PR description. Never push directly
to main, and never merge your own PR.