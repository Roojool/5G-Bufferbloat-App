# Google Play and Android Release Compliance

Primary-source review date: **2026-09-11**. This is an engineering release
checklist, **not legal advice or a claim of Google Play approval**. Policies and
Console forms change; verify them again immediately before submission.

The current project is source-only and ships an unavailable native stub. No
publisher account, Play Console declaration, listing, approval, or extension was
inspected or established by this review. Local-only design is not an automatic
policy exemption. See [Privacy](../PRIVACY.md) for current app behavior and
[Project Context](PROJECT_CONTEXT.md) for implementation state.

## Target SDK: policy versus checked-in configuration

For ordinary phone/tablet apps, new apps and app updates must target **Android
16 / API 36 or higher from 2026-08-31**. The existing-app availability threshold
is API 35; it is a different rule. The policy describes a possible extension to
2026-11-01, which must not be assumed granted to this project. Form-factor
exceptions are not this app's release target.
[Play target API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en),
[Android target SDK guidance](https://developer.android.com/google/play/requirements/target-sdk)

Checked-in `app/build.gradle.kts` still has **minSdk 26, compileSdk 35 and
targetSdk 35**. This documentation-only rebaseline changes none of those values.
A separate implementation task must migrate the SDK/toolchain as necessary and
verify changed platform behavior before a compliant new submission; replacing
API 35 in build documentation would falsely claim that work had happened.

## VpnService eligibility, declaration and listing

The policy permits core VPN functionality and specified uses including network
tools. It requires disclosure of VpnService use in the listing, encryption from
the device to the VPN tunnel endpoint, and forbids manipulation of other apps'
traffic for monetization. Network-tool intent alone does not establish approval.
[VpnService policy](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en)

All apps using VpnService must complete the Play Console declaration and resubmit
when use changes. Prepare accurate category/functionality, data-handling and
monetization answers. The guide requests short demonstration video links (90
seconds or less) showing VPN use and, where applicable, disclosure/consent,
including acceptance, the Android VPN grant, rejection and re-entry behavior.
[VpnService declaration guidance](https://support.google.com/googleplay/android-developer/answer/12564964?hl=en)

**Unresolved for this architecture:** there is no project tunnel endpoint;
protected OS sockets would connect directly to app destinations. The published
wording does not explicitly approve or exempt this local-only arrangement from
the endpoint-encryption requirement. Obtain release-specific Play clarification
or review of the exact design. Destination HTTPS is not a project VPN encryption
layer; VpnService.protect prevents routing loops and does not encrypt traffic.
Do not add a relay or TLS interception to silently resolve this uncertainty.
[Android VPN guide](https://developer.android.com/develop/connectivity/vpn)

## Prominent disclosure and consent

For unexpected personal/sensitive-data access or use, present an understandable
disclosure in the normal app flow before permission/consent and access. Identify
the data, purpose and sharing. Obtain explicit affirmative action; navigation,
timeout or dismissal is not consent. The VPN-specific guidance applies disclosure
and consent where personal/sensitive data is accessed or collected. Handling
traffic locally does not excuse analyzing metadata access.
[Prominent disclosure guidance](https://support.google.com/googleplay/android-developer/answer/11150561?hl=en),
[User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)

Before release, verify the real app's disclosure and refusal/re-entry flow,
Android VPN consent, foreground operation, and listing all agree with the final
artifact. Manifest metadata, this document, a privacy-policy link, or Android's
generic VPN prompt alone does not demonstrate that the app met the prominent
disclosure requirement. No production disclosure approval is claimed here.

## Developer account and verification

The full Play Console Requirements policy requires developers providing apps
approved to use VpnService to register an **Organization account**. Complete
legal identity/contact verification and applicable D-U-N-S requirements; confirm
any documented exception through Google rather than assuming one.
[Play Console Requirements](https://support.google.com/googleplay/android-developer/answer/10788890?hl=en),
[Developer account information requirements](https://support.google.com/googleplay/android-developer/answer/13628312?hl=en)

Separately, personal accounts created after 2023-11-13 face a closed-test gate of
at least 12 testers continuously opted in for 14 days before applying for
production access. This conditional rule is not an alternative to the VPN
organization-account requirement.
[Personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-GB)

The current Console policy also announces Play-app package registration for
Android developer verification effective **2026-09-30**, upcoming on this review
date. Confirm the publisher/package's actual registration in Console even where
Google registers Play apps automatically.
[Console verification policy](https://support.google.com/googleplay/android-developer/answer/10788890?hl=en)

## Privacy and Data safety

Provide a public, active, non-geofenced, non-editable, non-PDF privacy-policy URL in Console
and a policy link/text inside the app. Cover app/developer identity, privacy
contact, data access/use/sharing, security, retention and deletion. A no-data
claim does not remove the privacy-policy requirement. Account deletion rules
apply if a future app creates user accounts; the stock prototype has no account
service. Audit the final policy against the final artifact.
[User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)

Data safety declarations are required for closed/open/production distribution;
internal-only testing has an exemption. Strictly on-device processing that is
not transmitted off-device is outside the guide's collection definition. Audit
any future probes, third-party endpoints, SDKs and manually shared diagnostics
against the form's actual definitions/exceptions. No project telemetry does not
by itself establish every Data safety answer. If ephemeral off-device processing
is introduced, confirm its form treatment; do not assume it is exempt.
[Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)

Current local settings, bounded health history and user-directed diagnostic
sharing are described in PRIVACY.md. Packet processing, radio/location advice or
measurement changes need another data-flow audit and disclosure review before
release. Do not claim that a future engine's metadata exposure is already tested.

## Other Android release checks

The manifest currently declares a `specialUse` foreground service and related
permissions, with always-on VPN support disabled. For Android 14+ targets, verify
the final applicable service type, permission, Console explanation and video.
`specialUse` is subject to review, not blanket approval.
[Foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)

Verify 16 KB page-size compatibility of every native dependency and the final
64-bit artifact, including alignment and execution. The current Android guidance
covers API 35+ Play apps on 64-bit devices and states an unsupported-update block
beginning 2027-02-01; recheck the live deadline and applicability at release.
Three-ABI stub packaging does not prove a future Go/gVisor artifact meets it.
[Android 16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes)

## Release-time verification still required

- Recheck target API deadlines, any approved extension, and SDK migration tests.
- Resolve local-only VpnService eligibility/encryption interpretation with Play;
  complete the actual declaration, listing and demonstration evidence.
- Confirm Organization publisher identity, verification and package registration.
- Verify disclosure/consent/decline behavior and foreground-service declarations.
- Publish an appropriate privacy URL and audit Data safety against all code/SDKs,
  probes, destinations, permissions and diagnostic-sharing behavior.
- Verify native 16 KB behavior, release build/signing and platform compatibility.
- Pass the engineering/physical gates in [Testing](TESTING.md). Policy review
  cannot establish forwarding correctness or bufferbloat benefit.
