package dev.notipush

import android.app.Application
import android.app.Notification
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
class NotificationExtractorTest {
    private fun message(text: String, time: Long, sender: String = "Alice") = Bundle().apply {
        putCharSequence("text", text); putLong("time", time); putCharSequence("sender", sender)
    }
    private fun notification(extras: Bundle) = Notification().apply { this.extras = extras }
    @Test fun messagingStyleForwardsNewMessagesWithoutReplayingPreActivationHistory() {
        val n = notification(Bundle().apply {
            putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "Family")
            putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(message("Old", 1000), message("Hello", 3000), message("Again", 4000)))
        })
        val messages = NotificationExtractor.extract("com.whatsapp", "chat-1", "WhatsApp", n, 4001, 2000)
        assertEquals(listOf("Hello", "Again"), messages.map { it.message.body })
        assertEquals("WhatsApp · Family · Alice", messages.first().message.title)
        assertEquals(3000L, messages.first().message.timestamp)
        assertNotEquals(messages.first().fingerprint, messages.last().fingerprint)
    }
    @Test fun repostingMessagingStyleUsesStableFingerprints() {
        val extras = Bundle().apply { putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(message("Hello", 3000))) }
        val a = NotificationExtractor.extract("app", "key", "App", notification(extras), 4000, 2000)
        val b = NotificationExtractor.extract("app", "key", "App", notification(extras), 5000, 2000)
        assertEquals(a.single().fingerprint, b.single().fingerprint)
    }
    @Test fun genericNotificationPrefersExpandedTextAndSuppressesEmptyPosts() {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "Alice")
            putCharSequence(Notification.EXTRA_TEXT, "Short")
            putCharSequence(Notification.EXTRA_BIG_TEXT, "A longer message")
        }
        val result = NotificationExtractor.extract("app", "key", "App", notification(extras), 4000, 0).single()
        assertEquals("A longer message", result.message.body)
        assertEquals("App · Alice", result.message.title)
        assertTrue(NotificationExtractor.extract("app", "key", "App", notification(Bundle()), 4000, 0).isEmpty())
    }
    @Test fun currentAndroidSupportsPersonSenders() {
        val personMessage = message("SMS", 3000).apply {
            remove("sender")
            putParcelable("sender_person", android.app.Person.Builder().setName("Alice").build())
        }
        val n = notification(Bundle().apply { putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(personMessage)) })
        assertEquals("SMS", NotificationExtractor.extract("sms", "key", "Messages", n, 4000, 2000).single().message.body)
        assertEquals("Messages · Alice", NotificationExtractor.extract("sms", "key", "Messages", n, 4000, 2000).single().message.title)
    }
}
