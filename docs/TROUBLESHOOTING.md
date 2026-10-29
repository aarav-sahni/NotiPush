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

This is a known issue reported with 1.0.2 and is under investigation. Do not assume successful delivery with the app open proves background delivery.

Set NotiPush battery use to **Unrestricted**, allow background activity/autostart where the manufacturer provides those settings, and confirm notification access remains granted. If Android exposes redacted notification text, NotiPush cannot recover the hidden content.

To reproduce on a test device, enable forwarding, lock the phone, receive a fresh notification from a selected app, and compare the Pushover arrival time before and after unlocking. Capture whether the listener saw the notification while locked or whether only delivery was delayed. Do not include message text or credentials in shared logs.

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

The existing listener access and saved settings are retained. Android controls when it reconnects the service. NotiPush does not scan and replay old active notifications. Reopen NotiPush after a force-stop, and check listener status and battery settings if delivery does not resume.
