# Privacy Notice

**Status:** applies to the current source prototype; last reviewed September 2026.

## Plain-language summary

Bufferbloat Shaper is intended to be a local network tool. This project does not operate a VPN endpoint, proxy, telemetry service, advertising service, or analytics backend. It does not intentionally send device diagnostics to the project owner.

The current prototype is not release-ready. Do not treat this notice as a guarantee of production-grade behavior before the [known limitations](docs/LIMITATIONS.md) are resolved and independently tested.

## What the app handles on the device

The checked-in native-engine stub is unavailable, and the Android service is designed to fail before establishing a VPN route in that state. Therefore the default checked-in build should not receive device traffic through a TUN interface.

The source tree retains historical relay code. If a future verified engine enables the VPN service, handling IP packets will necessarily expose the process to packet headers such as addresses, ports, protocol, packet sizes, and timing.

The current prototype also parses DNS A/AAAA request payloads to resolve names and may write operational details to Android logcat. It does **not** decrypt TLS/HTTPS traffic, install a certificate authority, or intentionally persist application payloads. This is a code-level description, not a claim that the prototype is safe to use for sensitive traffic.

## Network requests made by the current prototype

- A future relayed application connection is intended to be opened directly from the device to its original destination, not through a project-operated server.
- Historical VPN/DNS code configures public DNS addresses and includes fallback resolver behavior. It does not yet reliably preserve a user's existing DNS policy and is not active in the checked-in fail-closed service.
- Calibration and validation scaffolding can send direct requests to third-party connectivity, DNS, and speed-test endpoints such as Google and Cloudflare if invoked by future integration. Those providers receive ordinary requests from the device under their own policies; they are not project telemetry endpoints.

These behaviors are documented because they are relevant to privacy and must be redesigned or made user-controllable before a production release.

## Local storage and logs

The current source stores shaper configuration and saved validation values in Android `SharedPreferences`. Android logcat output may include operational state and, in some paths, DNS names or network endpoint details. The app does not yet provide a diagnostic-export feature; if one is added, it must require an explicit user action and redact sensitive data by default.

Uninstalling the app normally removes its app-private storage. Android system backups, OEM tools, or manually collected logs may have separate retention rules.

## What this project does not do

- Operate a relay, VPN gateway, proxy, or telemetry server.
- Sell, share, or monetize traffic data.
- Use ad or analytics SDKs.
- Perform TLS interception or certificate-pinning bypass.
- Intentionally collect account credentials, message contents, contacts, location, phone numbers, or advertising identifiers.

## Your choices

- Do not enable the VPN service if you do not want the app process to handle your traffic metadata.
- Keep auto-calibration and built-in testing disabled if you do not want their direct measurement requests.
- Review and redact any log output before sharing it in an issue.

## Changes to this notice

Material changes to data handling must update this document and the in-app disclosure before release. Questions or potential privacy/security issues should follow [SECURITY.md](SECURITY.md).
