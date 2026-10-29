# Changelog

## 1.0.3 — 2026-10-29

- Added immediate forwarding from the notification listener instead of waiting for a scheduled job.
- Added short, time-limited CPU wake locks and retained the encrypted WorkManager retry queue.
- Shared delivery coordination prevents concurrent live/retry requests for the same queued message.
- Added an explicit battery exemption request, exemption status, and OnePlus background-activity guidance.
- Added capture/acceptance timestamps with screen-off/locked status, without retaining message text.
- Added concurrency, cancellation, retry, wake-lock cleanup, and forced-Doze device regression coverage.
- Expanded setup, troubleshooting, contribution, and release documentation; added GitHub Actions checks.

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
