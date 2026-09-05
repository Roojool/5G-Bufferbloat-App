# Build from Source

## Status

This is a source-only prototype. Building an APK does **not** make it a supported or safe-to-use release. The checked-in native engine is deliberately unavailable, so the service refuses to establish a VPN route rather than shape traffic through an unsafe legacy relay. No signed APK/AAB is published or endorsed by this repository at this stage.

## Prerequisites

- Git
- JDK 21 (Android Studio's bundled JBR 21 or a Temurin/OpenJDK 21 installation)
- Android SDK Platform 35 and the matching platform/build tools accepted by Android Gradle Plugin
- Android NDK r28c (`28.2.13676358`) and CMake 3.22.1 for the checked-in JNI-stub build
- Android SDK command-line tools or Android Studio
- For device installation: Android Debug Bridge (`adb`) and a device with developer options enabled

The project currently targets Android API 35 and has a minimum API level of 26.

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
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

The equivalent macOS/Linux command replaces `gradlew.bat` with `./gradlew`. The repository has early source-level unit coverage, but successful unit/static checks are still not behavioral verification of a VPN data plane or a cellular network.

## Install a local debug build

After a successful debug assembly, connect only a device you control and run:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Do not distribute this debug APK as a release. It should report that a verified native packet engine is unavailable and leave ordinary connectivity unchanged. Before doing any development test, review [Limitations](LIMITATIONS.md).

## Signing and secrets

There is no release-signing configuration in this repository. Never commit:

- Keystores or signing keys (`.jks`, `.keystore`, `.p12`, `.pem`, `.key`)
- `local.properties`, environment files, access tokens, or credentials
- Device captures, packet captures, or unredacted diagnostics

Use a local, untracked signing setup only when a future release process has been designed and reviewed.

## Reproducibility status

The Gradle wrapper pins Gradle and the project no longer contains a machine-specific JDK location. A public release still requires documented fresh-clone validation on Windows, macOS, and Linux, plus a real native engine in place of the intentionally unavailable JNI stub.
