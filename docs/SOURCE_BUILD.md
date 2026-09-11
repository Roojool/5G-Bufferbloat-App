# Build from Source

## Status

This is a source-only prototype. Building an APK does **not** make it a supported or safe-to-use release. The checked-in native engine is deliberately unavailable, so the service refuses to establish a VPN route rather than activate an unsafe fallback. No signed APK/AAB is published or endorsed by this repository at this stage.

## Prerequisites

- Git
- JDK 21 (Android Studio's bundled JBR 21 or a Temurin/OpenJDK 21 installation)
- Android SDK Platform 35 and the matching platform/build tools accepted by Android Gradle Plugin
- Android NDK r28c (`28.2.13676358`), the intended/recommended native toolchain
  installed by CI, and CMake 3.22.1; see the selection distinction below
- Android SDK command-line tools or Android Studio
- For device installation: Android Debug Bridge (`adb`) and a device with developer options enabled

The project currently targets Android API 35 and has a minimum API level of 26.

This is checked-in build configuration, not the current Play submission floor;
see [Play Compliance](PLAY_COMPLIANCE.md). API migration requires a separate
implementation task. No maximum runtime API is configured.

The wrapper pins Gradle 8.9 and its checksum; the version catalog selects AGP
8.7.3 and Kotlin 2.1.0. Java/Kotlin bytecode targets 17, separate from the JDK 21
build runtime. Gradle selects CMake 3.22.1 and builds C++17 for arm64-v8a,
armeabi-v7a and x86_64. CI installs the intended/recommended NDK r28c
(`28.2.13676358`), but `app/build.gradle.kts` does **not** pin `ndkVersion`.
Local Gradle/CMake selection can therefore differ; the recorded local build's
three ABI caches selected `27.0.12077973`. Installing r28c does not prove Gradle
selected it, locally or in CI. Record the selected NDK from each build's native
output/CMakeCache.txt rather than inferring it from installed packages.
A later implementation/build task must pin and verify the actual NDK on local
and CI builds. This documentation PR does not change Gradle or establish a pin.

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
