package dev.notipush

import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Destructive test configuration: run only on a disposable test emulator. */
@RunWith(AndroidJUnit4::class)
class LockedDeliveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun realListenerCapturesAndDispatchesDuringDeepDozeWithoutUnlock() {
        // Never alter battery state or notification settings on a daily-use phone.
        check(shell("getprop ro.kernel.qemu").trim() == "1") { "Disposable emulator required" }
        val context = instrumentation.targetContext
        val repo = context.repository
        val power = context.getSystemService(PowerManager::class.java)
        val originalTransport = repo.transmit
        val arrived = CountDownLatch(1)
        val sentAsleep = AtomicBoolean(false)
        val wasExempt = repo.batteryExempt()
        val certificate = HeldCertificate.Builder().commonName("NotiPush local test")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.enqueue(MockResponse().setBody("{\"status\":1}"))
        server.start()
        // Trust this ephemeral certificate for this one local URL only; no global TLS override.
        val endpoint = URL(null, server.url("/1/messages.json").toString(), object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection =
                (URL(url.toString()).openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = clientTls.sslSocketFactory()
                }
        })
        val client = PushoverClient(endpoint)
        try {
            repo.reset()
            repo.saveDestination(Destination("a".repeat(30), "b".repeat(30)))
            repo.saveRule("com.android.shell", AppRule())
            repo.transmit = { target, message ->
                val asleepBefore = !power.isInteractive && power.isDeviceIdleMode
                val result = client.send(target, message)
                sentAsleep.set(asleepBefore && !power.isInteractive && power.isDeviceIdleMode)
                if (result == PushResult.Accepted) arrived.countDown()
                result
            }
            shell("cmd notification allow_listener dev.notipush/dev.notipush.NotificationBridge")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!repo.listenerConnected && System.nanoTime() < deadline) Thread.sleep(100)
            assertTrue("System notification listener connected", repo.listenerConnected)
            repo.settings.enabled = true
            shell("cmd deviceidle whitelist +dev.notipush")
            assertTrue(repo.batteryExempt())
            shell("dumpsys battery unplug")
            shell("input keyevent 223")
            val idleResponse = shell("cmd deviceidle force-idle")
            val idleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!power.isDeviceIdleMode && System.nanoTime() < idleDeadline) Thread.sleep(50)
            assertFalse("Screen remains off", power.isInteractive)
            assertTrue("Device is in deep Doze: $idleResponse", power.isDeviceIdleMode)
            shell("cmd notification post -t 'NotiPush lock test' notipush-lock 'Background forwarding check'")
            assertTrue("Live delivery runs within ten seconds, without a job or unlock", arrived.await(10, TimeUnit.SECONDS))
            assertTrue("Transport ran with screen off in Doze", sentAsleep.get())
            assertTrue("Capture diagnostic records locked/screen off", repo.settings.lastCaptureLocked)
            val finishDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while ((repo.store.stats().accepted == 0 || repo.settings.lastDeliveryAt == 0L) && System.nanoTime() < finishDeadline) Thread.sleep(50)
            assertEquals(1, repo.store.stats().accepted)
            assertEquals("One real HTTPS request to the local mock server", 1, server.requestCount)
            assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
            assertTrue(repo.settings.lastDeliveryLocked)
            assertTrue(repo.store.history().all { it.payload.isEmpty() })
            assertFalse("No unlock required", power.isInteractive)
        } finally {
            shell("cmd deviceidle unforce")
            shell("dumpsys battery reset")
            if (!wasExempt) shell("cmd deviceidle whitelist -dev.notipush")
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            shell("cmd notification disallow_listener dev.notipush/dev.notipush.NotificationBridge")
            repo.reset()
            repo.transmit = originalTransport
            server.shutdown()
        }
    }
}
