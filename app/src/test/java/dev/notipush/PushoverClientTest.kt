package dev.notipush

import android.app.Application
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLDecoder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
class PushoverClientTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun cleanup() { server.shutdown() }
    private fun send(device: String = "", status: Int = 200, body: String = "{\"status\":1}"): PushResult {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
        return PushoverClient(server.url("/messages").toUrl()).send(
            Destination("a".repeat(30), "b".repeat(30), device),
            PushMessage("App · Alice", "Hello & नमस्ते 😀", 100_000))
    }
    private fun fields(body: String) = body.split('&').associate {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }
    @Test fun postsEncodedUnicodeWithAllDevicesAsDefault() {
        assertEquals(PushResult.Accepted, send())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        val received = fields(request.body.readUtf8())
        assertEquals("Hello & नमस्ते 😀", received["message"])
        assertEquals("App · Alice", received["title"])
        assertEquals("100", received["timestamp"])
        assertFalse(received.containsKey("device"))
        assertFalse(received.containsKey("html"))
    }
    @Test fun forwardsOptionalDeviceNames() {
        assertEquals(PushResult.Accepted, send("iphone,ipad"))
        assertEquals("iphone,ipad", fields(server.takeRequest().body.readUtf8())["device"])
    }
    @Test fun quotaAndInvalidKeysFailWithoutRetryButServerErrorsRetry() {
        assertTrue(send(status = 429, body = "{\"status\":0}") is PushResult.Rejected)
        assertTrue(send(status = 400, body = "{\"status\":0}") is PushResult.Rejected)
        assertTrue(send(status = 503, body = "{\"status\":0}") is PushResult.Retry)
    }
    @Test fun httpSuccessRequiresPushoverAcceptance() {
        assertTrue(send(body = "{\"status\":0}") is PushResult.Rejected)
        assertTrue(send(body = "not json") is PushResult.Rejected)
    }
    @Test fun neverFollowsRedirectsWithCredentials() {
        assertTrue(send(status = 302) is PushResult.Rejected)
        assertEquals(1, server.requestCount)
    }
}
