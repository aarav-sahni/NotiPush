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
        if (repository.settings.enabled && repository.batteryExempt()) repository.ensureForwarder()
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
        val receivedAt = System.currentTimeMillis()
        if (!repo.settings.enabled) return
        val source = sourceKey(sbn.packageName, sbn.uid / 100_000)
        val rule = repo.settings.ruleFor(source)?.takeIf { it.enabled } ?: return
        val callbackLock = notificationWakeLock(this)
        try {
            val receipt = DeliveryTiming(postedAt = sbn.postTime, receivedAt = receivedAt, receivedState = repo.powerDescription())
            fun observed(result: String) = repo.settings.recordObservation(source, receipt, result)
            if (repo.destinationOrNull() == null) { observed("Destination missing or could not be decrypted"); return }
            val notification = sbn.notification
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) { observed("Group summary skipped"); return }
            if (!repo.settings.includeOngoing && notification.flags and Notification.FLAG_ONGOING_EVENT != 0) { observed("Ongoing notification skipped"); return }
            val since = maxOf(repo.settings.enabledSince, rule.since)
            // Also prevents the listener from forwarding stale posts after reconnecting.
            if (sbn.postTime < since) { observed("Older than forwarding activation"); return }
            val label = rule.displayName.ifBlank { repo.sources.label(sbn.packageName, sbn.uid / 100_000) }
            repo.settings.rememberSource(SourceApp(sbn.packageName, label, sbn.uid / 100_000))
            if (source !in repo.settings.rules()) repo.settings.saveRule(source, rule)
            val parsed = runCatching {
                // Android's notification key already includes user/UID identity. Keep the
                // existing fingerprint format so an upgrade doesn't replay clone messages.
                NotificationExtractor.extract(sbn.packageName, sbn.key, label, notification, sbn.postTime, since)
            }.getOrElse {
                repo.settings.lastListenerError = "An app posted a notification that could not be read."
                observed("Notification parsing failed")
                return
            }
            observed(if (parsed.isEmpty()) "No message text exposed by Android" else "Text available; checking filters")
            repo.captureWhileAwake {
                try {
                    if (!repo.settings.enabled || repo.settings.ruleFor(source)?.enabled != true) return@captureWhileAwake
                    val currentRule = repo.settings.ruleFor(source) ?: return@captureWhileAwake
                    parsed.forEach { captured ->
                        if (currentRule.accepts(captured.message.title, captured.message.body)) {
                            val hasMessages = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)?.isNotEmpty() == true
                            repo.capture(source, label, captured, if (hasMessages) 86_400_000 else 300_000, receipt)
                        }
                    }
                    repo.settings.lastListenerError = ""
                } catch (_: Exception) {
                    repo.settings.lastListenerError = "A notification could not be queued. Reopen NotiPush and check your destination settings."
                }
            }
        } finally { callbackLock.releaseSafely() }
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
