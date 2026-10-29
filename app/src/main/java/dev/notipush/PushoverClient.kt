package dev.notipush

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed interface PushResult {
    data object Accepted : PushResult
    data class Rejected(val reason: String) : PushResult
    data class Retry(val reason: String) : PushResult
}

class PushoverClient(private val endpoint: URL = URL("https://api.pushover.net/1/messages.json")) {
    fun send(destination: Destination, message: PushMessage): PushResult {
        destination.validationError()?.let { return PushResult.Rejected(it) }
        val fields = linkedMapOf(
            "token" to destination.token, "user" to destination.user,
            "title" to MessagePolicy.truncate(message.title, 250),
            "message" to MessagePolicy.truncate(message.body.ifBlank { "(No text in notification)" }, 1024),
            "priority" to destination.priority.toString(), "timestamp" to (message.timestamp / 1000).toString()
        )
        if (destination.device.isNotBlank()) fields["device"] = destination.device
        val form = fields.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" }.toByteArray(Charsets.UTF_8)
        var connection: HttpURLConnection? = null
        return try {
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(form.size)
            }
            connection.outputStream.use { it.write(form) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText().take(16_384) }.orEmpty()
            val accepted = runCatching { JSONObject(body).optInt("status") == 1 }.getOrDefault(false)
            when {
                status == 200 && accepted -> PushResult.Accepted
                MessagePolicy.retryable(status) -> PushResult.Retry("Pushover is unavailable (HTTP $status). Retrying automatically.")
                status == 429 -> PushResult.Rejected("Pushover's quota or rate limit was reached. Check your Pushover account.")
                status in 400..499 -> PushResult.Rejected("Pushover rejected the request (HTTP $status). Check your token, user key, and devices.")
                else -> PushResult.Rejected("Pushover did not accept the message (HTTP $status). Check destination settings.")
            }
        } catch (_: IOException) {
            PushResult.Retry("Network error. Retrying automatically; an uncertain response can cause a duplicate.")
        } finally { connection?.disconnect() }
    }
}
