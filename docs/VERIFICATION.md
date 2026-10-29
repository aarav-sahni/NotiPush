# Verification — 29 October 2026

## Version 1.0.2 — physical OnePlus diagnosis and release fix

Two distinct failures were established on the connected OnePlus 13R (CPH2691, Android 16 / API 36, ARM64, 4 KB pages):

1. Earlier file-source install sessions ended with `INSTALL_FAILED_VERIFICATION_FAILURE (-22)` and `Install not allowed`. A normal ADB request for the same signed APK, followed by App Guard's **Continue installation**, succeeded. The vendor's underlying policy or handoff issue remains undetermined; a clean scan screen did not represent the final verifier result.
2. The installed 1.0.1 release crashed before rendering, in `androidx.startup.InitializationProvider → WorkManagerInitializer`, with `NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []`. The release shrinker had removed the constructor used by Room's reflection-based initialisation.

Version 1.0.2 retains that constructor and WorkManager's reflective input-merger entry points. A DEX-level release gate checks the public constructors of the generated WorkManager database, input merger, forwarding worker, and recovery worker. It **rejects the actual crashing 1.0.1 APK** and accepts 1.0.2.

| Check | Result |
| --- | --- |
| Signed 1.0.2 upgrade on the physical OnePlus 13R | Passed; app data preserved |
| Two release cold starts on the physical phone | Each survived 15 seconds and rendered main navigation |
| Crash buffer during release smoke test | No new NotiPush crash |
| WorkManager initialisation and periodic recovery scheduling | Passed; Room database initialisation no longer crashes |
| System job callback | Reached WorkManager; early periodic invocation correctly rescheduled |
| Unit tests / lint | 35 passed / 0 lint errors |
| APK signatures, minified constructors, integrity, ARM64, 16 KB static alignment | Passed |
| Diagnostic helper tests | 5 passed |

The release remains minified; this fix does not disable R8, clear application data, disable App Guard, or change the signing identity. User Pushover credentials and real message delivery were not exercised in these checks.

**Correction to previous verification:** an `am start -W` success only confirmed that Android accepted the launch request. It did not establish that the 1.0.1 process remained alive. Debug instrumentation tests did not exercise its minified startup path. The physical release smoke checks above replace that inadequate launch check.

## Version 1.0.1 — current Android build

The app now requires Android 16 (API 36), compiles and targets Android 17 (API 37), and uses AGP 9.4.0, Gradle 9.6.0, and Build Tools 37.0.0. The deliverable is one production APK with standard uncompressed, 16 KB aligned native libraries and v2/v3 signatures. The original release signing identity and package name are preserved.

| Check | Result |
| --- | --- |
| Signed release build and production manifest checks | Passed |
| Unit tests on Android 16/17 framework models | 35 passed, 0 failed |
| Android lint | Passed, 0 errors |
| Android 16 ARM64 emulator device tests | 4 passed, 0 failed |
| Android 16 normal graphical installer: upgrade 1.0.0 → 1.0.1 | App installed successfully |
| Android 16 clean installation of signed release | Passed; launch command alone was insufficient to establish runtime health |
| APK v2/v3 signatures, CRC, ARM64, ZIP/ELF 16 KB alignment | Passed |

Android 17 framework behaviour was exercised with Robolectric 4.17. A full Android 17 emulator boot could not be completed on this host: the emulator reported host `mprotect`/hypervisor errors and hung before the app could run. The 16 KB system image also could not be booted here; native ELF segments and uncompressed ZIP offsets were verified statically. These results do **not** establish an Android 17 physical-device test or 16 KB device runtime test.

At the time of 1.0.1 verification, the OnePlus-specific rejection was unconfirmed. The later physical-device evidence and release crash fix are recorded in the 1.0.2 section above. Do not use the crashing 1.0.1 release.

## Version 1.0.0 — original checks

The following checks passed on the original source:

| Check | Result |
| --- | --- |
| Debug APK build | Passed |
| Unit tests | 20 passed, 0 failed |
| Android lint | Passed, 0 errors; informational style/dependency warnings remain |
| Device tests on Pixel 6 emulator, Android 15 / API 35, ARM64 | 4 passed, 0 failed |
| Signed, R8-optimised release build | Passed |
| Gradle wrapper download and SHA-256 verification | Passed |
| Release APK signature and artifact checksum | Verified |
| Release APK install and cold launch on Android 15 | Passed |
| System notification listener registration on Android 15 | Granted and bound |

The tests cover include/exclude precedence, destination validation, Unicode truncation, notification extraction, stable deduplication fingerprints, queue persistence across database reopen, cancellation, expiration, capacity, payload erasure, and Pushover form encoding/response handling against a local mock HTTP server. The original version's tests included API 26 compatibility; current tests exercise API 36/37 instead.

The device tests verify app navigation, actual Android Keystore AES-GCM encryption with random IVs, tamper rejection, key erasure, input validation, encrypted credential persistence, and screen capture. The [overview](screenshots/overview.png), [app picker](screenshots/apps.png), [activity](screenshots/activity.png), and [setup](screenshots/setup.png) screenshots were visually inspected. The test APK temporarily clears screenshot protection for those empty demo screens; the delivered app keeps it enabled.

No real Pushover credentials are bundled or used in automated tests. Real WhatsApp / Google Messages / Teams → Pushover → iPhone / Apple Watch delivery has not been verified on physical devices. Use **Setup → Send a test notification** and then receive a fresh notification from each source app after completing setup on your phone.

The signed release and checksum are in `dist/`. Preserve the signing directory described in the README for future app updates.
