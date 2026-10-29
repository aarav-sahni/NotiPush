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

Update to **1.0.3 or newer**, using the same signing identity. Keep the existing installation so your Pushover settings survive.

The old delivery path relied entirely on WorkManager, which Android can defer during sleep. The updated app attempts delivery immediately and uses WorkManager for persistent retries. Network access during deep Doze also requires the battery exemption; a CPU wake lock alone is insufficient. [Android's explanation](https://developer.android.com/training/monitoring-device-state/doze-standby).

1. Open **Setup → Delivery while locked → Allow background delivery** and approve the Android prompt. Check that the row reports **Battery optimization exemption granted**.
2. On OnePlus/OxygenOS, allow background activity in **App info → Battery usage**. Enable auto launch if available. Menu names vary; an app battery mode called **Unrestricted** may coexist with Android's optimization exemption, so check the green status inside NotiPush too.
3. Confirm notification access, source selection, filters, and forwarding are enabled.
4. Lock Android, receive a new notification from a selected app, and check Pushover **before** unlocking Android. Avoid keeping the source conversation open on another device if that suppresses phone notifications.
5. Unlock Android and inspect **Setup → Connection check** and **Activity**.

| Observation | Meaning / next step |
| --- | --- |
| Last captured and last accepted both show screen off / locked | NotiPush handled delivery while asleep; check downstream Pushover/iPhone/Watch routing if alerts are missing |
| Capture time is while locked, acceptance occurs after unlock | Check battery exemption, OxygenOS background controls, network access, and queue status |
| Capture time only changes after unlock | The source app or Android/OxygenOS delayed exposing the notification; check the source app's battery/notification settings and NotiPush's listener access |
| No new capture | Source did not post eligible text, a filter blocked it, or the listener is disconnected/restricted |

The timestamps describe the latest eligible captured notification and the latest accepted request; accepted requests include manual tests. They do not prove that a specific iPhone/Watch displayed an alert. Android may redact sensitive notification text and work-profile policy can block access.

Physical OnePlus verification of this update remains a manual check. The automated test exercises a real system listener with the screen off and forced deep Doze, using a local HTTPS mock instead of real Pushover credentials. See [verification scope](VERIFICATION.md).

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
