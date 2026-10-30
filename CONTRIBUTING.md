# Contributing

NotiPush is a small Android notification-to-Pushover bridge. Keep changes focused on reliable delivery, clear setup, and minimal retention of notification content.

## Development

Install the tools in the [README](README.md#build-and-test), then run:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Use a disposable emulator for the full instrumented suite. Tests reset NotiPush's local configuration; the locked-delivery regression also changes emulator idle/battery/listener settings and cleans them up afterward. It refuses to run on a physical phone. Never run the suite against a daily-use installation containing saved settings.

```sh
ANDROID_SERIAL=YOUR_TEST_EMULATOR ./gradlew :app:connectedDebugAndroidTest
```

Tests and CI use synthetic notification text and mock API responses. The locked test posts a shell notification through Android's actual listener, forces deep Doze with the screen off and the app exempted from battery optimization, and sends HTTPS to a local mock with an ephemeral certificate. No real Pushover request is made. CI currently runs unit/lint/release checks, not emulator instrumentation. Do not add real Pushover keys, notification content, private device logs, or signing credentials to fixtures, issues, or pull requests.

## Changes and verification

- Describe the user-visible problem, resulting behaviour, and checks performed.
- Add regression coverage for delivery, filtering, storage, concurrency, or lifecycle changes.
- Test background/lifecycle changes with the phone locked as well as unlocked. Record plugged/unplugged state and avoid app-report polling during the measurement; instrumentation can affect process lifetime. Do not describe one message or a local mock test as a reliability test.
- For shrinking or dependency changes, verify the signed, minified APK and run the release smoke check. Debug tests do not exercise R8's output.
- Update the README and changelog when setup, supported versions, or delivery behaviour changes.

## Prepare a release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts` and add a changelog entry.
2. Run the checks above and `./scripts/build-release.sh` with the established release signing identity.
3. Install the signed APK on a test device and run `scripts/smoke-release.py` for the expected version.
4. Check real notification forwarding and locked-device behaviour with a test account. Record the scope and remaining limits in `docs/VERIFICATION.md`.
5. Attach the verified APK and checksum to a GitHub release after the repository is published. APKs and private signing keys do not belong in Git commits.

GitHub Actions uses a disposable signing identity only to exercise the release build and DEX checks. It does not publish a release or replace the signing key used by existing installations.

## Local Git preparation

The project can be committed on `main` and pushed to an empty GitHub repository of your choice. Set the remote only after selecting that repository:

```sh
git remote add origin YOUR_GITHUB_REPOSITORY_URL
git push -u origin main
```

Check `git status` before committing. Build outputs, `dist/`, `local.properties`, environment files, private keys, and local diagnostic reports are excluded by `.gitignore`.
