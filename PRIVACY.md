# Privacy Notice

**Status:** applies to the current source prototype; last reviewed September 2026.

## Plain-language summary

Bufferbloat Shaper is intended to be a local network tool. This project does not operate a VPN endpoint, proxy, telemetry service, advertising service, or analytics backend. It does not intentionally send device diagnostics to the project owner.

The current prototype is not release-ready. Do not treat this notice as a guarantee of production-grade behavior before the [known limitations](docs/LIMITATIONS.md) are resolved and independently tested.

## What the app handles on the device

The checked-in native-engine stub is unavailable, and the Android service is designed to fail before establishing a VPN route in that state. Therefore the default checked-in build should not receive device traffic through a TUN interface.

The unsafe hand-written Kotlin relay was removed from the shipped module. If a future verified engine enables the VPN service, handling IP packets will necessarily expose the process to packet headers such as addresses, ports, protocol, packet sizes, and timing.

The prior DNS relay was removed because it did not preserve a user's resolver policy. The current service does not establish a TUN route while the native engine is unavailable. The app does **not** decrypt TLS/HTTPS traffic, install a certificate authority, or intentionally persist application payloads. This is a code-level description, not a claim that the prototype is safe to use for sensitive traffic.

## Network requests made by the current prototype

- A future relayed application connection is intended to be opened directly from the device to its original destination, not through a project-operated server.
- The service does not configure public DNS addresses or substitute a resolver. Any future native engine must forward ordinary DNS traffic unchanged before DNS interception is enabled.
- Automatic calibration and the prior built-in traffic benchmark are disabled/removed until independent physical-network measurement and verified native metrics exist. The checked-in build makes no project-operated measurement requests.

These behaviors are documented because they are relevant to privacy and must be redesigned or made user-controllable before a production release.

### Debug-only owner socket experiments

The optional internal F-01/F-02 Activity opens ordinary protected sockets only
to a numeric test endpoint explicitly supplied by the owner. It creates no VPN
route and reads only the owner-requested synthetic test transfer; it does not
capture other apps' traffic. It computes byte counts and SHA-256 incrementally,
and reads socket-option/TCP_INFO observations. No payload is retained in results.
In the manual UI, address/port and selected Network remain transient input and
are not exported. Host batch automation stores its input manifest, endpoint logs,
optional sender capture and RTT command output only below the repository's
git-ignored local `output/stage1/` tree. It separately writes an allowlisted
summary without endpoint addresses/ports, Network handles, capture paths,
precise location, credentials or device/ADB identifiers. Bounded Android results
remain app-private until the host retrieves them or the owner explicitly saves
manual redacted JSON; there is no automatic upload. The chosen
endpoint sees the connection and its ordinary network metadata. The project
operates no endpoint. Release APKs exclude this internal harness. See
[Experiments](docs/EXPERIMENTS.md) for bounds and owner data-cost controls.

## Local storage and logs

The current source stores local shaper configuration in Android `SharedPreferences`. It retains up to 100 in-process health transitions; these disappear when the app process ends. If a future native engine fails to confirm its lifecycle state or exceeds the bounded native lifecycle watchdog, the app stores one generic local safety marker before ending its own process; it contains no packet, endpoint, app, or error-text data and is consumed on the next launch to explain the event. The shipped fail-closed path logs operational state but does not log packet or endpoint data.

The Settings screen offers a **user-initiated** text export through Android's system share sheet. It includes device make/model, OS version, broad network-capability summary, exact local timestamps, structured runtime state, saved shaper settings, and health-transition status/generation values. It does not export free-form service/error text. It excludes traffic payloads, DNS names, IP addresses, app package names, phone/SIM identifiers, Wi-Fi names, and precise location. Nothing is sent until the user selects a share destination.

Uninstalling the app normally removes its app-private storage. Android system backups, OEM tools, or manually collected logs may have separate retention rules.

## What this project does not do

- Operate a relay, VPN gateway, proxy, or telemetry server.
- Sell, share, or monetize traffic data.
- Use ad or analytics SDKs.
- Perform TLS interception or certificate-pinning bypass.
- Intentionally collect account credentials, message contents, contacts, location, phone numbers, or advertising identifiers.

## Your choices

- Do not enable the VPN service if you do not want the app process to handle your traffic metadata.
- Do not share a diagnostic report unless you are comfortable disclosing its redacted device/OS and broad transport summary to the destination you choose.
- Review and redact any Android log output before sharing it in an issue.

## Changes to this notice

Material changes to data handling must update this document and the in-app disclosure before release. Questions or potential privacy/security issues should follow [SECURITY.md](SECURITY.md).
