# InstallWithOptions – Samsung Galaxy A17 (SM-A175N)

## Target

- Hardware: Samsung SM-A175N, including Korean KOO/KTC/LUC/SKC firmware.
- OS: Android 17, API 37; One UI 9. This profile is enabled **only** when both model and API match.
- Upstream base: zacharee/InstallWithOptions 0.9.2; built using the upstream AGP 9.2.1, Gradle 9.5.1, Kotlin 2.4.0 and compile/target API 37 settings.
- Application ID: `dev.zwander.installwithoptions.a175n` (can coexist with the original app).
- Variant: `0.9.2-a175n.2` (version code 29).

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
- Samsung **Auto Blocker** may prohibit installation from unauthorized sources and block USB commands; check **Settings → Security and privacy → Auto Blocker** when troubleshooting. Do not disable protections solely for convenience.
- Install sessions correctly declare `PACKAGE_SOURCE_LOCAL_FILE` rather than falsely identifying sideloaded APKs as store installs.
- Android 17 deprecates the automatic dependency installation API, so this fork does not expose a nonfunctional dependency download option.
- The installer selects fast/bulk AOSP installation scenario hints depending on the number of packages; these hints do not guarantee reduced elapsed time.
- Profile diagnostics show CPU ABIs, storage available, security patch, and a copy action for troubleshooting.
- On some MediaTek devices, upstream Shizuku 13.6.0 has known issues. If connection fails, use a compatible maintained Shizuku release/fork.
- Android 14+ generally blocks package downgrades for non-debuggable/non-test packages without root. Enabling the flag cannot override that restriction.
- Installed APK signatures must still match on updates.
- Split archives must contain a compatible base APK and split set for this device.
- This fork has not been validated on physical SM-A175N hardware. Check against your exact CSC/build before relying on it.

## Diagnostics

The device card displays the detected model, API and build string, plus the shell/root service connection status. Install results are tracked per package rather than per split APK, using a per-batch callback token to reject unrelated status broadcasts.

## Device verification

```sh
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.security_patch
```

Use the built-in **Copy diagnostics** control for exact firmware/ABI and service status information.

## Reproducible build

Use the checked-in Gradle wrapper on an Android SDK host with JDK 17 and API 37 platform installed:

```sh
./gradlew :app:assembleDebug
```

The resulting file is `app/build/outputs/apk/debug/app-debug.apk`. CI artifacts are **debug-signed on each runner**; their signing certificate may change between runs. Android may reject installing a new artifact over an older debug artifact unless both use the same signing key. For repeatable upgrades, configure a private, persistent signing key (do not commit it to the public repository). Never assume that uninstalling and reinstalling preserves application data.

## Platform references

- Samsung official SM-A175N software updates: https://doc.samsungmobile.com/SM-A175N/036210251124/kor.html
- Android 17 PackageInstaller.SessionParams: https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams
- Samsung Auto Blocker: https://docs.samsungknox.com/admin/fundamentals/whitepaper/samsung-knox-mobile-security/system-security/samsung-auto-blocker/
