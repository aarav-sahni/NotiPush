# Verification — 29 October 2026

## Version 1.0.4 — persistent forwarding and profile identities

| Check | Result |
| --- | --- |
| Unit tests on API 36/37 framework models | 67 passed, 0 failed |
| Android 16 ARM64 emulator suite | 5 passed; focused Doze/privacy regression also passed after diagnostics changes |
| Forced-Doze path | Foreground service running; fresh listener callback, actual HTTPS to a local TLS mock, timing recorded, and Pause action verified |
| Database upgrade | Version 1 encrypted queued rows survive version 2 timing-schema migration |
| Profile rules | Independent clone identities, legacy fallback, display-name persistence, and observed-profile discovery passed |
| Diagnostic helper tests | 11 passed |
| Lint | 0 errors; non-fatal warnings remain |
| Signed, minified APK | v2/v3 signatures, reflection entry points including the foreground service, CRC, ARM64, and static 16 KB alignment passed |
| Manual signed upgrade on OnePlus 13R / OxygenOS 16 | 1.0.4 installed; existing configuration retained |
| Two physical signed-release cold starts | Each survived 15 seconds, rendered navigation, and produced no new NotiPush crash |
| Physical service state | Notification access and exemption granted; foreground service running; destination configuration present |
| Physical WhatsApp notification on AOD | One manually generated clone notification captured and accepted while the display remained in AOD |

For that one physical notification, Android post → listener arrival was **128 ms**, the first send began **207 ms** after posting, and Pushover accepted it **745 ms** after posting. Post, receive, first attempt, and completion are retained as metadata, without message text. The user also confirmed AOD delivery.

**Limits:** one notification is not a reliability or soak test. Repeated/burst messages, every WhatsApp profile, Google Messages, Teams, long idle, unplugged operation, and physical offline/reconnect cases have not all been verified. The phone was USB-connected during the measured sample. Instrumented emulator tests run inside the app and can influence process lifetime; they do not establish unattended OEM behaviour. Android 17 and 16 KB runtime limitations recorded below still apply.

On this build the clone-alias endpoint rejects callers without `com.oplus.permission.safe.PROTECT`, defined by the OS as **signature|privileged**. The app distinguishes clone profiles, but automatic custom aliases are unavailable through that endpoint. Use the per-copy display-name setting; no personal account-name mapping is embedded in the APK. Device-specific manual setup is separate from source verification.

A normal ADB update request stalled behind the vendor verifier without a visible approval prompt. Opening the APK manually completed the signed upgrade. No verifier, system permission boundary, AOD setting, or app data was disabled/erased to make installation succeed.

## Version 1.0.3 — locked-screen delivery

The prior implementation handed every message to WorkManager. That made the initial send subject to job scheduling during sleep. The update persists and schedules the retry first, then sends immediately on a dedicated background executor. Capture and sending use partial wake locks with 45-second safety timeouts, released after use. The live sender and retry worker share delivery coordination; queue rows remain recoverable after process death.

Setup now requests Android's battery optimization exemption on an explicit tap and shows its actual status. The exemption allows network/wake-lock use during deep Doze; it does not override source-app notification behaviour or OxygenOS policies. Capture/acceptance timestamps and screen-off/locked flags provide diagnostics without message text. Existing settings and the signing identity are preserved.

| Check | Result |
| --- | --- |
| Unit tests on API 36/37 framework models | 57 passed, 0 failed |
| Live sender / retry worker race | One POST; terminal payload erased |
| Retry, cancellation during decode, access removal, expiry, stopped-worker recovery | Passed |
| Wake-lock handoff, cleanup on failure, restart after offline burst | Passed |
| Android 16 ARM64 emulator device suite | 5 passed, 0 failed |
| Real system notification listener with screen off and forced deep Doze | Captured a fresh synthetic shell notification without unlocking |
| HTTPS dispatch during forced deep Doze, battery exemption enabled | One accepted request to a local TLS mock using the actual Pushover client; captured and accepted timestamps recorded as screen off/locked |
| Keystore use while screen off / in Doze | Real settings/payload encryption and decryption passed in the listener-to-HTTPS test |
| Lint / Python diagnostic tests | 0 lint errors / 5 helper tests passed |
| Signed, minified APK and reflection / signature / alignment gates | Passed |
| Signed 1.0.2 → 1.0.3 upgrade on Android 16 ARM64 emulator (4 KB pages) | Passed |
| Two signed-release cold starts | Each survived 15 seconds, rendered navigation, and produced no new NotiPush crash |

The instrumented Doze test runs only on a disposable emulator. It uses synthetic content, placeholder keys, and an ephemeral local TLS certificate. It does not contact Pushover. Cleanup exits forced idle, resets simulated battery state, restores the prior battery exemption, and removes the test listener grant. Screenshots were refreshed from empty demo settings, including the new [background-delivery setup](screenshots/background.png).

At its release, 1.0.3 had not been physically tested on the OnePlus. The subsequent user test still showed AOD delays; the instrumented emulator result alone was insufficient. The 1.0.4 section above records the later physical evidence. Android 17/16 KB runtime limitations below still apply. GitHub Actions is configured but has not run on a remote repository.

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
