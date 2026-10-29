package dev.notipush

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
class DeliveryEngineTest {
    private lateinit var store: DeliveryStore
    private var allowed = true
    private var decode: (String) -> PushMessage = { PushMessage("Title", "Body", 1) }
    private var result: PushResult = PushResult.Accepted
    private val sends = AtomicInteger()
    private fun engine(sender: (Destination, PushMessage) -> PushResult = { _, _ -> sends.incrementAndGet(); result }) =
        DeliveryEngine(store, { allowed }, { Destination("a".repeat(30), "b".repeat(30)) },
            { decode(it) }, sender)
    private fun enqueue() = store.enqueue("source", "Source", "event", "ciphertext", 60_000)!!
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("notipush.db")
        store = DeliveryStore(context)
    }
    @After fun cleanup() { store.close(); RuntimeEnvironment.getApplication().deleteDatabase("notipush.db") }

    @Test fun liveAndWorkerRaceMakeOneRequest() {
        val id = enqueue()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val shared = engine { _, _ ->
            sends.incrementAndGet(); entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            PushResult.Accepted
        }
        val threads = Executors.newFixedThreadPool(2)
        try {
            val live = threads.submit<DeliveryEngine.Outcome> { shared.deliver(id) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val worker = threads.submit<DeliveryEngine.Outcome> { shared.deliver(id) }
            release.countDown()
            assertEquals(DeliveryEngine.Outcome.Finished, live.get(5, TimeUnit.SECONDS))
            assertEquals(DeliveryEngine.Outcome.Finished, worker.get(5, TimeUnit.SECONDS))
            assertEquals(1, sends.get())
            assertEquals("sent", store.get(id)!!.status)
            assertEquals("", store.get(id)!!.payload)
        } finally { release.countDown(); threads.shutdownNow() }
    }
    @Test fun transientFailureSurvivesRecoveryWithEncryptedPayload() {
        val id = enqueue()
        result = PushResult.Retry("Offline")
        assertEquals(DeliveryEngine.Outcome.Retry, engine().deliver(id))
        assertEquals("ciphertext", store.get(id)!!.payload)
        assertEquals(listOf(id), store.pending())
        result = PushResult.Accepted
        engine().deliver(id)
        assertEquals(2, sends.get())
        assertEquals("sent", store.get(id)!!.status)
        assertEquals("", store.get(id)!!.payload)
    }
    @Test fun cancellationDuringDecodePreventsPost() {
        val id = enqueue()
        decode = { store.cancelPending("Destination changed"); PushMessage("Title", "Body", 1) }
        engine().deliver(id)
        assertEquals(0, sends.get())
        assertEquals("cancelled", store.get(id)!!.status)
    }
    @Test fun accessRemovedDuringDecodePreventsPost() {
        val id = enqueue()
        decode = { allowed = false; PushMessage("Title", "Body", 1) }
        engine().deliver(id)
        assertEquals(0, sends.get())
        assertEquals("cancelled", store.get(id)!!.status)
        assertEquals("", store.get(id)!!.payload)
    }
    @Test fun stoppedWorkerLeavesPayloadForRecovery() {
        val id = enqueue()
        engine().deliver(id) { true }
        assertEquals(0, sends.get())
        assertEquals("ciphertext", store.get(id)!!.payload)
    }
    @Test fun expiredQueueIsNeverSent() {
        val id = enqueue()
        store.writableDatabase.execSQL("UPDATE deliveries SET created=? WHERE id=?", arrayOf(System.currentTimeMillis() - 86_400_001, id))
        engine().deliver(id)
        assertEquals(0, sends.get())
        assertEquals("failed", store.get(id)!!.status)
        assertEquals("", store.get(id)!!.payload)
    }
    @Test fun badCiphertextFailsWithoutSending() {
        val id = enqueue()
        decode = { error("Cannot decrypt") }
        engine().deliver(id)
        assertEquals(0, sends.get())
        assertEquals("failed", store.get(id)!!.status)
        assertEquals("", store.get(id)!!.payload)
    }
    @Test fun rejectedRequestCannotBeReplayedByBackup() {
        val id = enqueue()
        result = PushResult.Rejected("Invalid destination")
        val shared = engine()
        shared.deliver(id); shared.deliver(id)
        assertEquals(1, sends.get())
        assertEquals("failed", store.get(id)!!.status)
        assertEquals("", store.get(id)!!.payload)
    }
}
