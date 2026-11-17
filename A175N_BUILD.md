# InstallWithOptions – Samsung Galaxy A17 (SM-A175N)

## Target

- Hardware: Samsung SM-A175N, including Korean KOO/KTC/LUC/SKC firmware.
- OS: Android 17, API 37; One UI 9. This profile is enabled **only** when both model and API match.
- Upstream base: zacharee/InstallWithOptions 0.9.2; built using the upstream AGP 9.2.1, Gradle 9.5.1, Kotlin 2.4.0 and compile/target API 37 settings.
- Application ID: `dev.zwander.installwithoptions.a175n` (can coexist with the original app).

## Install

1. Open **Actions** > **Build Galaxy A17 debug APK** > **Run workflow** (or open the PR's build run).
2. Download the `InstallWithOptions-A175N-Android17-debug` build artifact and extract its APK.
3. Install the APK normally. Launch Shizuku and grant this **fork** permission, or use an already configured root service.
4. Confirm that the profile shows SM-A175N, API 37 and that the installer service is connected.
5. Choose APK(s) and select the least permissive preset that works.

## Presets

| Preset | Android package-install flags |
| --- | --- |
| Standard | Replace existing |
| Test APK | Replace existing + allow test-only APK |
| Legacy APK | Replace existing + bypass low-target-SDK block |

Presets overwrite all selected flag checkboxes. Manual flags remain available below. The legacy mode does **not** defeat signature verification, ABI incompatibility, policy restrictions or downgrade enforcement. Avoid disabling verification unless you explicitly understand the implications.

## Platform limits

- A Shizuku user service usually runs as the **shell** UID; it is not system/root. One UI can enforce additional restrictions.
- On some MediaTek devices, upstream Shizuku 13.6.0 has known issues. If connection fails, use a compatible maintained Shizuku release/fork.
- Android 14+ generally blocks package downgrades for non-debuggable/non-test packages without root. Enabling the flag cannot override that restriction.
- Installed APK signatures must still match on updates.
- Split archives must contain a compatible base APK and split set for this device.
- This fork has not been validated on physical SM-A175N hardware. Check against your exact CSC/build before relying on it.

## Diagnostics

The device card displays the detected model, API and build string, plus the shell/root service connection status. Install results are tracked per package rather than per split APK, using a per-batch callback token to reject unrelated status broadcasts.

## Reproducible build

Use the checked-in Gradle wrapper on an Android SDK host with JDK 17 and API 37 platform installed:

```sh
./gradlew :app:assembleDebug
```

The resulting file is `app/build/outputs/apk/debug/app-debug.apk`. Debug APK is signed with the development/debug key of its build environment; do not use it as an update for a release-signed app.
