# Install with Options — Samsung Galaxy A17 (SM-A175N)

Target: SM-A175N, Android 17 / API 37, One UI 9. Samsung's published firmware
`A175NKSU7DZJ3` is an example; the app detects the actual device and API at runtime.

## Variant
This fork uses the separate application ID `dev.zwander.installwithoptions.a17`
so that its debug-signed APK can coexist with the upstream app. The variant is
based on InstallWithOptions 0.9.2. No system files or partitions are modified.

## Presets
- **Safe**: replace/update existing APKs only.
- **Legacy**: also request test APK and low-target-SDK installation. Android 17
  may still reject incompatible APKs.
- **Test**: also mark installation as coming from ADB and permit test APKs.

Presets never enable Disable Verification, Grant All Requested Permissions,
Allow Downgrade, Install for All Users, or APEX installation automatically.
Manual options remain available.

## Reliability enhancements
- Track one result per requested package, even if the system callback omits its name.
- Reuse the returned session ID as a callback request code, preventing collisions.
- Close file descriptors after stream transfer and use 256 KiB stream buffers.
- Declare total install size where known; mark batches with the Android bulk scenario.
- Fail fast on null/unreadable streams or partial copies rather than committing
  a silently truncated package.
- List profile IDs using Android API-compatible reflection fallbacks.
- Support nested APKs inside XAPK/APKM/APKS and reject unsafe ZIP entries.

## Android restrictions
- Package signature checks cannot be bypassed by Disable Verification.
- Downgrading normal release packages generally requires root, even with the flag.
- Grant All Requested Permissions only concerns runtime permissions.
- On MediaTek devices, upstream reports that Shizuku 13.6.0 has a compatibility
  issue. Use Shizuku 13.5.4 or a version/fork with the fix.

## Build
GitHub Actions builds a **debug** APK on push / pull request. The fork's
workflow runs `testDebugUnitTest` and `assembleDebug`.
For local builds: `./gradlew :app:testDebugUnitTest :app:assembleDebug`.

Build/test results and on-device install behavior must be verified before
treating this fork as production ready.
