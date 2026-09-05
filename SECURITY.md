# Security Policy

## Supported versions

There are no supported production releases yet. The `main` branch is an unreleased research prototype and is handled on a best-effort basis.

## Reporting a vulnerability

Please **do not open a public issue** for a suspected vulnerability, especially one involving traffic exposure, VPN bypass, data leakage, or privilege escalation.

Use the repository's private vulnerability-reporting option from its **Security** tab. If private reporting is not enabled, contact [@Roojool](https://github.com/Roojool) privately through GitHub and include only the minimum information needed to establish contact. Do not post proof-of-concept exploits, traffic captures, credentials, or personal data publicly.

A useful report includes:

- A clear description of the impact and affected version/commit.
- Reproduction steps that do not expose another person's traffic or account.
- Device model, Android version, and relevant network conditions.
- Redacted logs or a minimal proof of concept.
- Any mitigation you know of.

## In scope

Examples include:

- Traffic escaping or being redirected unexpectedly by the local VPN.
- Data exposure through logs, diagnostics, storage, or the app's network behavior.
- Unsafe handling of Android `VpnService`, permissions, sockets, or native code.
- Dependency or build-chain vulnerabilities that materially affect this project.

General feature requests, ordinary reliability bugs, and compatibility reports belong in the normal issue tracker.

## Disclosure

Please give maintainers a reasonable opportunity to investigate and prepare a fix before public disclosure. This project has no formal response-time guarantee while it remains an unreleased prototype.
