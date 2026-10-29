package dev.notipush

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationBridge : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        repository.listenerConnected = true
        repository.settings.lastListenerError = ""
        // Never replay active notifications on connection or reboot.
    }
    override fun onListenerDisconnected() {
        repository.listenerConnected = false
        repository.requestReconnect()
        super.onListenerDisconnected()
    }
    override fun onDestroy() {
        repository.listenerConnected = false
        super.onDestroy()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName || sbn.packageName == "net.superblock.pushover") return
        val repo = repository
        if (!repo.settings.enabled) return
        val rule = repo.settings.rules()[sbn.packageName]?.takeIf { it.enabled } ?: return
        if (repo.destinationOrNull() == null) return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (!repo.settings.includeOngoing && notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val since = maxOf(repo.settings.enabledSince, rule.since)
        // Also prevents the listener from forwarding stale posts after reconnecting.
        if (sbn.postTime < since) return
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val parsed = runCatching {
            NotificationExtractor.extract(sbn.packageName, sbn.key, label, notification, sbn.postTime, since)
        }.getOrElse {
            repo.settings.lastListenerError = "An app posted a notification that could not be read."
            return
        }
        repo.captureWhileAwake {
            try {
                if (!repo.settings.enabled || repo.settings.rules()[sbn.packageName]?.enabled != true) return@captureWhileAwake
                val currentRule = repo.settings.rules()[sbn.packageName] ?: return@captureWhileAwake
                parsed.forEach { captured ->
                    if (currentRule.accepts(captured.message.title, captured.message.body)) {
                        val hasMessages = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)?.isNotEmpty() == true
                        repo.capture(sbn.packageName, label, captured, if (hasMessages) 86_400_000 else 300_000)
                    }
                }
                repo.settings.lastListenerError = ""
            } catch (_: Exception) {
                repo.settings.lastListenerError = "A notification could not be queued. Reopen NotiPush and check your destination settings."
            }
        }
    }
}

object NotificationExtractor {
    fun extract(pkg: String, key: String, label: String, notification: Notification, postTime: Long, since: Long): List<CapturedMessage> {
        val extras = notification.extras ?: Bundle.EMPTY
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)?.toList().orEmpty()
        if (messages.isNotEmpty()) {
            return messages.mapNotNull { data ->
                val timestamp = data.getLong("time", postTime).takeIf { it > 0 } ?: postTime
                if (timestamp < since) return@mapNotNull null
                val text = data.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() }
                    ?: if (data.containsKey("type")) "[Attachment]" else return@mapNotNull null
                val personName = data.getParcelable("sender_person", android.app.Person::class.java)?.name?.toString()
                val sender = personName ?: data.getCharSequence("sender")?.toString().orEmpty()
                val heading = listOf(title, sender).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                CapturedMessage(MessagePolicy.fingerprint(pkg, key, timestamp.toString(), sender, text),
                    PushMessage(listOf(label, heading).filter { it.isNotBlank() }.joinToString(" · "), text, timestamp))
            }
        }
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return listOf(CapturedMessage(MessagePolicy.fingerprint(pkg, key, title, text),
            PushMessage(listOf(label, title).filter { it.isNotBlank() }.joinToString(" · "), text, postTime)))
    }
}
