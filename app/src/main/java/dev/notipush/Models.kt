package dev.notipush

import java.security.MessageDigest

data class SourceApp(val packageName: String, val label: String)

val starterApps = listOf(
    SourceApp("com.whatsapp", "WhatsApp"),
    SourceApp("com.google.android.apps.messaging", "Google Messages"),
    SourceApp("com.microsoft.teams", "Microsoft Teams"),
    SourceApp("com.whatsapp.w4b", "WhatsApp Business")
)

data class AppRule(
    val enabled: Boolean = true,
    val include: String = "",
    val exclude: String = "",
    val since: Long = System.currentTimeMillis()
) {
    fun accepts(title: String, message: String): Boolean {
        val text = "$title\n$message"
        fun words(value: String) = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val allow = words(include)
        val deny = words(exclude)
        return (allow.isEmpty() || allow.any { text.contains(it, ignoreCase = true) }) &&
            deny.none { text.contains(it, ignoreCase = true) }
    }
}

data class Destination(val token: String = "", val user: String = "", val device: String = "", val priority: Int = 0) {
    fun validationError(): String? = when {
        !token.matches(Regex("[A-Za-z0-9]{30}")) -> "The application API token must be 30 letters or numbers."
        !user.matches(Regex("[A-Za-z0-9]{30}")) -> "The user or group key must be 30 letters or numbers."
        device.isNotBlank() && !device.matches(Regex("[A-Za-z0-9_-]{1,25}(,[A-Za-z0-9_-]{1,25})*")) ->
            "Use device names separated by commas, with no spaces (25 characters per name)."
        priority !in -1..1 -> "Choose low, normal, or high priority."
        else -> null
    }
}

data class PushMessage(val title: String, val body: String, val timestamp: Long)
data class CapturedMessage(val fingerprint: String, val message: PushMessage)
data class Delivery(
    val id: Long, val packageName: String, val label: String, val created: Long,
    val status: String, val detail: String, val payload: String
)
data class DeliveryStats(val accepted: Int = 0, val queued: Int = 0, val failed: Int = 0)

object MessagePolicy {
    fun truncate(value: String, limit: Int): String {
        val length = value.codePointCount(0, value.length)
        return if (length <= limit) value else value.substring(0, value.offsetByCodePoints(0, limit - 1)) + "…"
    }
    fun fingerprint(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun retryable(httpCode: Int) = httpCode in 500..599
}
