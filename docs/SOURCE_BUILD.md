# Build from Source

## Status

This is a source-only prototype. Building an APK does **not** make it a supported or safe-to-use release. The checked-in native engine is deliberately unavailable, so the service refuses to establish a VPN route rather than activate an unsafe fallback. No signed APK/AAB is published or endorsed by this repository at this stage.

## Prerequisites

- Git
- JDK 21 (Android Studio's bundled JBR 21 or a Temurin/OpenJDK 21 installation)
- Android SDK Platform 35 and the matching platform/build tools accepted by Android Gradle Plugin
- Android NDK r28c (`28.2.13676358`), pinned by Gradle and installed by CI,
  and CMake 3.22.1; see selection verification below
- Android SDK command-line tools or Android Studio
- For device installation: Android Debug Bridge (`adb`) and a device with developer options enabled

The project currently targets Android API 35 and has a minimum API level of 26.

This is checked-in build configuration, not the current Play submission floor;
see [Play Compliance](PLAY_COMPLIANCE.md). API migration requires a separate
implementation task. No maximum runtime API is configured.

The wrapper pins Gradle 8.9 and its checksum; the version catalog selects AGP
8.7.3 and Kotlin 2.1.0. Java/Kotlin bytecode targets 17, separate from the JDK 21
build runtime. Gradle selects CMake 3.22.1 and builds C++17 for arm64-v8a,
armeabi-v7a and x86_64. Gradle now pins `ndkVersion = "28.2.13676358"` (r28c).
CI installs that exact package. Both local and CI verification run
`python tools/verify_harness_build.py` after debug and release assembly; it
reads each selected CMake cache and NDK source.properties and checks that only
debug packages the socket harness. Old local CMake caches are not current build
evidence. A mismatch fails verification; do not fall back to another NDK.

### Windows PowerShell

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

### macOS or Linux

```bash
export JAVA_HOME="/path/to/jdk-21"
./gradlew :app:assembleDebug
```

## Required local checks

Run all of these before opening a pull request:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

The equivalent macOS/Linux command replaces `gradlew.bat` with `./gradlew`. The repository has early source-level unit coverage, but successful unit/static checks are still not behavioral verification of a VPN data plane or a cellular network.

CI runs all four tasks above. `assembleDebugAndroidTest` only compiles/packages
instrumentation tests; execute device tests separately when required and record
literal output. Report UP-TO-DATE/cached work separately from executed checks.

## Install a local debug build

After a successful debug assembly, connect only a device you control and run:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Do not distribute this debug APK as a release. With valid configuration it
reports that a verified native packet engine is unavailable; invalid settings
(including initial zero limits) fail validation first. Neither establishes a
VPN route. Before testing, review [Limitations](LIMITATIONS.md).

## Signing and secrets

There is no release-signing configuration in this repository. Never commit:

- Keystores or signing keys (`.jks`, `.keystore`, `.p12`, `.pem`, `.key`)
- `local.properties`, environment files, access tokens, or credentials
- Device captures, packet captures, or unredacted diagnostics

Use a local, untracked signing setup only when a future release process has been designed and reviewed.

## Reproducibility status

The Gradle wrapper pins Gradle and the project no longer contains a machine-specific JDK location. A public release still requires documented fresh-clone validation on Windows, macOS, and Linux, plus a real native engine in place of the intentionally unavailable JNI stub.

## Internal Stage 1 harness checks

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
python tools/verify_harness_build.py
python -m unittest discover -s tools -p "test_*.py" -v
```

Release assembly is an unsigned packaging regression check, not distribution.
The debug-only harness has its own JNI library and cannot establish a route.
Launch and owner-run procedures are in [Experiments](EXPERIMENTS.md).
For emulator-only execution use `:app:connectedDebugAndroidTest`; see
[Testing](TESTING.md) for the optional prepared-service API test and exact evidence
boundary. CI compiles instrumentation and runs no external-network tests.

For a physical Wi-Fi screen on Windows, connect exactly one authorized phone,
make the owner endpoint address reachable on that Wi-Fi, complete the one-time
consent described in EXPERIMENTS, then run from a clean checkout:

```powershell
$env:STAGE1_ENDPOINT_IP = "<numeric address of this owner-controlled host>"
py -3 tools\stage1_batch.py --preset wifi-screen --transport wifi --endpoint-address $env:STAGE1_ENDPOINT_IP --bind-address $env:STAGE1_ENDPOINT_IP --port 39001 --build --install
```

On Windows this command automatically probes TShark in `PATH` and standard
Wireshark install locations, including Program Files, then maps the local bind
address's Windows adapter to a stable `tshark -D` interface name. It never uses
a numeric capture index. For an unusual install use `--tshark-path`; for an
ambiguous adapter match use `--tshark-interface` with a stable name. Capture
setup failures remain SKIPPED/UNVERIFIED and do not change transfer conclusions.

The first build records the current clean Git SHA and APK hash below ignored
`output/stage1/build-provenance/`; the installed APK must match exactly. Later
runs may omit `--build --install` only while that current-commit provenance and
installed APK still match. This is source/build identity evidence, not physical
socket-control evidence.
