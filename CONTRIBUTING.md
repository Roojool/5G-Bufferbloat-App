# Contributing to Bufferbloat Shaper

Thank you for helping improve this project. It is a network-sensitive Android prototype, so evidence and safety matter more than the size of a patch.

## Before you start

- Follow [AGENTS.md](AGENTS.md): first read it completely, then all canonical
  documents before changes, and establish branch/commit/worktree, open PRs, CI
  and actual build/native configuration. Use [Project Context](docs/PROJECT_CONTEXT.md)
  for the handoff and hierarchy. The mobile plan is superseded history.
- Check open issues before starting substantial work. Open an issue first for a new data-plane design, a new dependency, privacy-sensitive behavior, or a broad UI change.
- Do not describe a behavior as working merely because the app compiles. Include the test output or redacted device evidence that supports the claim.

## Local workflow

1. Fork the repository and create a focused branch, such as `fix/vpn-lifecycle` or `docs/source-build`.
2. Make one logical change at a time. Avoid mixing refactors, behavior changes, and formatting-only edits in one pull request.
3. Run the relevant checks from [Source Build](docs/SOURCE_BUILD.md): debug assembly, unit tests, and lint at minimum.
4. For networking changes, provide reproducible device evidence following [Testing](docs/TESTING.md). State the Android version, device/OEM, network type/carrier, and whether the shaper was active.
5. Open a pull request using the supplied template.
6. After engineering work, update PROJECT_CONTEXT and any affected design,
   roadmap, testing, compatibility or disclosure documents. Include a final
   review of every canonical document with updated/no-change reasons, remaining
   gates and literal validation/link-check output. Never assume an unmerged PR
   is part of main; keep experimental and ordinary-user claims distinct.

## Safety and privacy requirements

- Do not add a project-operated proxy, tracking SDK, analytics backend, traffic resale, TLS interception, or payload decryption.
- Do not log, commit, or attach credentials, private keys, raw packet captures, complete browsing histories, phone numbers, IMSIs, or location data.
- Redact hostnames, IP addresses, identifiers, and timestamps in diagnostic excerpts unless they are strictly necessary and you have permission to share them.
- Preserve a safe failure mode: a broken relay must not silently capture and black-hole traffic.
- Do not make compatibility, throughput, latency, or battery claims without literal supporting evidence.

## Pull-request expectations

Each pull request should explain:

- What changed and why.
- Which supported behavior changed, including privacy impact where relevant.
- How it was tested and the actual result.
- What remains unverified.

Maintainers may ask to split a large change, request device evidence, or defer feature work until the core VPN lifecycle and data plane are verified.

## License

By submitting a contribution, you agree that it is licensed under the [Apache License 2.0](LICENSE).
