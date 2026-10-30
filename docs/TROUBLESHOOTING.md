# Troubleshooting

## Start with a test notification

Save your Pushover destination, then tap **Send a test notification**. This tests the credentials and delivery path without requiring notification access or forwarding to be enabled.

| Result | Check |
| --- | --- |
| Accepted, but nothing on iPhone | Same Pushover account, exact device name, Pushover/iOS notification settings, Focus, and device connectivity |
| Failed with HTTP 4xx | Application token, user key, device names, or the Pushover quota/rate limit |
| Queued | Network connection and Android background/battery restrictions |
| Test works, but app notifications do not | Notification access, source selection, keyword filters, and whether the source app actually posted a notification |

## Notifications wait until the phone is unlocked

Update to **1.0.4 or newer**, using the same signing identity. Keep the existing installation so your Pushover settings survive.

Version 1.0.2 relied entirely on WorkManager, which Android can defer during sleep. Version 1.0.3 added immediate delivery but did not resolve the reported OnePlus AOD issue. Version 1.0.4 adds a persistent foreground messaging service and takes the CPU wake lock earlier in the callback. WorkManager remains the persistent retry fallback. Network access during deep Doze also requires the battery exemption; a CPU wake lock alone is insufficient. [Android's explanation](https://developer.android.com/training/monitoring-device-state/doze-standby).

1. Open **Setup → Delivery while locked → Allow background delivery** and approve the Android prompt. Check that the row reports **Battery optimization exemption granted**.
2. Check **Setup → Persistent forwarding → Service: running**. Allow the status-notification permission so the ongoing notification is visible.
3. On OnePlus/OxygenOS, use background activity or auto launch controls only if your build offers them. If its battery page has no controls, return to NotiPush rather than repeatedly opening that page. Check **Sleep standby optimization** in system Settings; it may reduce notification frequency.
4. Confirm notification access, source selection, filters, and forwarding are enabled.
5. Lock Android, receive a new notification from a selected app, and check Pushover **before** unlocking Android. Avoid keeping the source conversation open on another device if that suppresses phone notifications.
6. After the test, inspect **Activity → Show delivery timing** and **Setup → Recent listener events**.

| Observation | Meaning / next step |
| --- | --- |
| Last captured and last accepted both show screen off / locked | NotiPush handled delivery while asleep; check downstream Pushover/iPhone/Watch routing if alerts are missing |
| Capture time is while locked, acceptance occurs after unlock | Check battery exemption, OxygenOS background controls, network access, and queue status |
| Capture time only changes after unlock | The source app or Android/OxygenOS delayed exposing the notification; check the source app's battery/notification settings and NotiPush's listener access |
| No new capture | Source did not post eligible text, a filter blocked it, or the listener is disconnected/restricted |

The timestamps describe the latest eligible captured notification and the latest accepted request; accepted requests include manual tests. They do not prove that a specific iPhone/Watch displayed an alert. Android may redact sensitive notification text and work-profile policy can block access.

A single real WhatsApp notification has been verified on AOD on a OnePlus 13R with 1.0.4. Repeated, long-idle, unplugged, and all-source physical tests remain outstanding. The automated test uses instrumentation inside the app, a real listener, forced deep Doze, and a local HTTPS mock; instrumentation can influence process lifetime. See [verification scope](VERIFICATION.md).

## Clone names show a profile number

The tested OxygenOS 16 build protects its App Cloner alias service with `com.oplus.permission.safe.PROTECT`, a signature/privileged permission. Notification access does not grant that permission. A profile suffix is therefore the safe fallback when the OS does not expose the custom label.

Open **Apps**, tap the individual copy, enter **Forwarded app name**, and save. This is local configuration per profile, not an account-name constant in the APK. It affects future notifications; already delivered Pushover titles are unchanged. WhatsApp Business is no longer a starter entry.

## Collect AOD diagnostics

```sh
# Read-only OS state: AOD/display, idle, app process, app-ops, and exemption.
python3 scripts/diagnose-background.py --serial YOUR_DEVICE

# After the test, include the app's metadata-only timing report.
python3 scripts/diagnose-background.py --serial YOUR_DEVICE --app-report
```

The optional app report invokes service dump code and must not be polled to prove unattended delivery. For an unplugged test, use paired wireless debugging or reconnect USB only after observing whether the receiving device got the alert. USB charging and ADB observation can change the phone's power state. Default reports are written privately outside the repository. For a custom output path, use the ignored `diagnostics/` directory and review metadata before sharing.

## Notification access is restricted

Android may restrict sensitive settings for a sideloaded application. On the app's **App info** screen, look for **Allow restricted settings**, then enable NotiPush in **Notification access**. Availability and menu names depend on Android and the manufacturer. [Google's guidance](https://support.google.com/android/answer/12623953).

For Teams in a work profile, organisation policies may prevent the personal-profile listener from receiving notifications. The app cannot bypass those policies.

## App not installed, including after an App Guard scan

A scan result and the final installation result are separate. Use Android's actual `INSTALL_FAILED_*` status to distinguish package, signing, storage, compatibility, and verification-policy problems.

With a USB-debugging test phone connected:

```sh
# Read-only: device metadata, downloaded APK hashes, and relevant system logs.
python3 scripts/diagnose-install.py

# Perform one normal install/update and capture its result.
python3 scripts/diagnose-install.py --try-install
```

On the tested OnePlus 13R, earlier file-source sessions recorded `INSTALL_FAILED_VERIFICATION_FAILURE (-22): Install not allowed`. A normal ADB request followed by App Guard's **Continue installation** succeeded. The underlying vendor verification policy was not established. This is not evidence that every generic installer failure has the same cause.

Debug and release APKs have different signing identities. If Android explicitly reports a signature conflict, inspect the installed version before replacing it; uninstalling removes local settings. Keep the original release signing key for updates.

## App crashes immediately

Use 1.0.2 or later. In 1.0.1, R8 removed a constructor Room needs while WorkManager initialises. This was fixed with explicit retention rules and a check of the minified DEX. An `am start` success alone does not prove that startup completed.

For a new crash, capture the app-specific crash trace from a test device and record the app version, Android version, and reproduction steps. Keep private notifications and keys out of reports.

## Duplicates or missing content

- A timeout after Pushover accepts a request can cause a duplicate on retry.
- Generic notifications with identical key/title/body are suppressed for five minutes. MessagingStyle fingerprints include message timestamps and last 24 hours.
- Group summaries and ongoing notifications are skipped by default. Ongoing notifications can be enabled in Setup.
- Notifications with no exposed text may be skipped. Attachments and reply actions are not forwarded.
- Android can redact sensitive content such as OTPs. NotiPush does not bypass that protection.

## After reboot or force-stop

The existing listener access and saved settings are retained. Android controls when it reconnects the service. NotiPush does not scan and replay old active notifications. Unlock Android once after reboot; the encrypted settings/outbox are unavailable before that first unlock. Reopen NotiPush after a force-stop, and check listener status and battery settings if delivery does not resume.
