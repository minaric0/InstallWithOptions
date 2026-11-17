# SM-A175N / Android 17 / One UI 9 profile

This fork is tailored for the Korean Samsung **SM-A175N** on **Android 17
(API 37)**. The profile activates only when Android reports manufacturer
`samsung`, model `SM-A175N`, and SDK 37. It **does not** infer the One UI
version from the SDK level: confirm One UI 9 in Settings > About phone >
Software information.

## Install

1. Open the [Build SM-A175N Android 17 APK](../../actions/workflows/build-sm-a175n.yml)
   GitHub Actions workflow, run it if necessary, and download its debug APK artifact.
2. Install the debug APK. It has the separate application ID
   `dev.zwander.installwithoptions.a175n`, so it won't replace the original.
3. Start Shizuku and grant this fork access, or use a supported root environment.
4. Select one or more APK/APKS/APKM/XAPK files, choose a preset, and install.

## Presets and implementation

| Mode | Flags | Use |
|---|---|---|
| Safe (initial default, only on exact device) | Replace Existing | Ordinary APK updates/reinstalls |
| Legacy (explicit opt-in) | Replace Existing, Allow Test, Bypass Low Target SDK Block | Debug APKs or old apps |
| Manual | Any supported advanced flags | Specialist workflows |

Existing saved option selections are **never overwritten**. Initial defaults
are seeded once and only if there is no existing selection.

On Android 17 the fork also exposes the AOSP **Ignore Dexopt Profile** flag.
This is a troubleshooting flag, **not** a performance improvement: leaving
usable ART profiles intact is normally better for launch performance.

The installer now:
- Identifies files as `PACKAGE_SOURCE_LOCAL_FILE` instead of `STORE`.
- Informs the platform of known total split-APK sizes before creating a session.
- Requests `INSTALL_SCENARIO_FAST` for single-app installs and
  `INSTALL_SCENARIO_BULK` for multi-app batches.
- Copies APK data in 64 KiB chunks, verifies known byte counts, and closes
  the source descriptors after each synchronous privileged installation call.
- Uses unique cache names for incoming APKs and bundles.
- Handles Samsung/IUserManager overload changes with a fallback.
- Prevents repeat Install taps while an install is active.

## Platform limitations

- Shizuku/shell **cannot override APK signature mismatches** or protected
  platform permissions.
- On Android 14+, release APK downgrades are generally blocked unless the
  platform and signing/build conditions permit them; checking Allow Downgrade
  does not guarantee success.
- Samsung security features (including Auto Blocker) and device policy can
  independently prohibit sideloading.
- The older Shizuku 13.6.0 release has a reported MediaTek compatibility
  problem; ensure a working Shizuku build for the actual device firmware.
- APK architecture/split matching remains mandatory. Use a proper full set of
  native ABI, language and density splits.

## Build/test status

Compiles against Android SDK 37 with the fork's existing Gradle/AGP/Kotlin
versions. GitHub Actions builds `:app:assembleDebug`. Real-device
SM-A175N/One UI 9 integration testing, signature and security policy checks,
and runtime Shizuku verification are still required before calling the
release validated.

Suggested smoke test:
```sh
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.oneui
adb shell pm list packages | grep installwithoptions
```
The last One UI property might be absent depending on firmware; use Settings
to verify its displayed One UI version.
