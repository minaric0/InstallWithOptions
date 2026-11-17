# Install with Options — Samsung SM-A175N / One UI 9

Target: **Samsung Galaxy A17 4G (SM-A175N)**, **Android 17 / API 37**, **One UI 9**.

This is a device-focused development fork of Zachary Wander's InstallWithOptions
(MIT license). It uses Shizuku's shell service, not an exploit or a framework patch.

## Changes

- API 37 compile/target and **arm64-v8a only** packaging for this device.
- Independent application ID: `dev.minaric0.installwithoptions.a175n` so the
  upstream app can be installed side-by-side.
- On-device profile/privileged-service diagnostics and MediaTek Shizuku warning.
- **Standard APK** and explicitly chosen **Legacy APK** install presets.
- New Android installation scenario choices (system default, fast, bulk, bulk secondary).
- Explicit user-confirmation request mode (system policy is authoritative).
- File-descriptor cleanup, session fsync, stricter copy lengths, per-package result
  association, install-progress reset, and disabled install button until connected.
- Temporary APK filenames (rather than provider names), archive entry/size/path
  validation, and guaranteed import-spinner cleanup.

## Install and run

1. Enable Developer options and Wireless debugging on the device.
2. Start a compatible Shizuku service (or root service if rooted) and authorize this
   installer. The Shizuku service may need to be started after every reboot.
3. On MediaTek, **Shizuku 13.6.0** has a known compatibility problem. Use a fixed fork
   or a compatible Shizuku version such as **13.5.4** if affected.
4. Download the `InstallWithOptions-SM-A175N-Android17-debug` artifact from the
   GitHub Actions **Build SM-A175N Android 17 APK** run. Extract the ZIP to get the APK.
5. Install the debug APK. GitHub-hosted debug APKs may have different signing keys
   between runs. Updating may require uninstalling the previous debug build.

## Presets

- **Standard APK**: replace an existing app if permitted.
- **Legacy APK**: replace existing, install test-only APKs, request bypass of the
  low target SDK block. Use this only for APKs you trust.
- The advanced per-install options remain individually configurable.

### Platform limitations

Shizuku running with **shell** identity does **not** override APK signing,
privileged system permissions, device policy, or the Android 17 downgrade
restriction on non-debuggable apps. The **User Confirmation** option requests
behavior; Samsung/Android may still require confirmation.

## Build

Requirements: JDK 17, Android SDK API 37, Android SDK Build Tools 36.0.0,
and Gradle wrapper supplied by this repository.

```bash
./gradlew :app:assembleDebug
```

Artifact: `app/build/outputs/apk/debug/*.apk`.

## Validation checklist (real SM-A175N required)

- Verify developer options, Shizuku service startup, grant/revoke permission and
  recovery after binder disconnect.
- Import a single APK, multiple APKs, and an `.apks`/ `.apkm` split archive.
- Verify display of each success/failure result after sequential installations.
- Verify signature mismatches and blocked downgrades still fail safely.
- Test user confirmation mode with/without install privileges.
- Confirm package version, targetSdk=37, and installed architecture via
  `adb shell dumpsys package dev.minaric0.installwithoptions.a175n`.

CI builds source but **does not replace on-device validation**. No claims are
made about bypassing system-enforced protections.
