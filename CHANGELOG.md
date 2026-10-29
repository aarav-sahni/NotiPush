# Changelog

## 1.0.2 — 2026-10-29

- Fixed a minified-release startup crash caused by removal of WorkManager's generated Room database constructor.
- Retained the constructors used for reflective database, worker, and input-merger creation.
- Added DEX-level release checks that reject the crashing 1.0.1 APK.
- Added physical-device release smoke checks, installation diagnostics, and setup documentation.
- Verified two cold starts of the signed release on a OnePlus 13R running Android 16.

## 1.0.1 — 2026-10-29

- Updated the build toolchain, targeted Android 17, and set Android 16 as the minimum supported version.
- Added modern APK signing, manifest, ARM64, ZIP-integrity, and 16 KB alignment checks.
- Known issue: the minified APK crashes during startup. Use 1.0.2 or later.

## 1.0.0 — 2026-10-29

- Initial notification forwarding to Pushover.
- App selection, keyword filters, encrypted credentials/outbox, delivery history, retries, and setup UI.
