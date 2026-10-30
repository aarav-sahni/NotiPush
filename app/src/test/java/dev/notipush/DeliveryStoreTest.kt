package dev.notipush

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
class DeliveryStoreTest {
    private lateinit var context: Context
    private lateinit var store: DeliveryStore
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("notipush.db")
        store = DeliveryStore(context)
    }
    @After fun cleanup() { store.close(); context.deleteDatabase("notipush.db") }
    private fun enqueue(fingerprint: String = "one") = store.enqueue("app", "App", fingerprint, "ciphertext", 60_000)!!
    @Test fun duplicateEventsHaveOneDurableQueueEntry() {
        val id = enqueue()
        assertNull(store.enqueue("app", "App", "one", "ciphertext", 60_000))
        assertEquals(listOf(id), store.pending())
        store.close()
        store = DeliveryStore(context)
        assertEquals("ciphertext", store.get(id)!!.payload)
        assertEquals(listOf(id), store.pending())
    }
    @Test fun completionErasesMessagePayload() {
        val id = enqueue()
        store.finish(id, "sent", "Accepted by Pushover")
        assertEquals("", store.get(id)!!.payload)
        assertTrue(store.pending().isEmpty())
        assertEquals(1, store.stats().accepted)
        // A racing worker must not overwrite the terminal outcome.
        store.finish(id, "failed", "Failure")
        assertEquals("sent", store.get(id)!!.status)
    }
    @Test fun pausingCancelsAndErasesPendingButClearHistoryPreservesQueue() {
        val id = enqueue()
        store.clearHistory()
        assertEquals(listOf(id), store.pending())
        store.cancelPending("Paused")
        assertEquals("cancelled", store.get(id)!!.status)
        assertEquals("", store.get(id)!!.payload)
        store.clearHistory()
        assertTrue(store.history().isEmpty())
    }
    @Test fun staleQueueExpiresAndErasesPayload() {
        val id = enqueue()
        store.writableDatabase.execSQL("UPDATE deliveries SET created=? WHERE id=?", arrayOf(System.currentTimeMillis() - 86_400_001, id))
        assertTrue(store.pending().isEmpty())
        assertEquals("failed", store.get(id)!!.status)
        assertEquals("", store.get(id)!!.payload)
    }
    @Test fun disablingOneAppPreservesOtherApps() {
        val id = enqueue()
        val other = store.enqueue("other", "Other", "other", "ciphertext", 60_000)!!
        store.cancelPending("Disabled", "app")
        assertEquals("cancelled", store.get(id)!!.status)
        assertEquals(listOf(other), store.pending())
    }
    @Test fun queueIsBoundedAndHistoryIsPruned() {
        repeat(500) { enqueue("message-$it") }
        assertNull(store.enqueue("app", "App", "overflow", "ciphertext", 60_000))
        assertEquals(500, store.pending().size)
        assertEquals(1, store.stats().failed)
        store.pending().forEach { store.finish(it, "sent", "Accepted") }
        assertEquals(200, store.history().size)
    }
    @Test fun timingKeepsFirstAttemptWhenLaterRetryRunsAfterWake() {
        val id = store.enqueue("app@998", "Clone", "timed", "ciphertext", 60_000,
            DeliveryTiming(postedAt = 10, receivedAt = 12, receivedState = "AOD"))!!
        store.recordAttempt(id, "AOD · Doze")
        val first = store.get(id)!!.timing.firstAttemptAt
        store.recordAttempt(id, "screen on")
        store.finish(id, "sent", "Accepted")
        store.recordCompletion(id, "screen on")
        store.close(); store = DeliveryStore(context)
        val row = store.get(id)!!
        assertEquals("", row.payload)
        assertEquals(10L, row.timing.postedAt)
        assertEquals("AOD", row.timing.receivedState)
        assertEquals(first, row.timing.firstAttemptAt)
        assertEquals("AOD · Doze", row.timing.firstAttemptState)
        assertEquals("screen on", row.timing.lastAttemptState)
        assertEquals(2, row.timing.attempts)
        assertTrue(row.timing.completedAt > 0)
    }
    @Test fun upgradingVersionOnePreservesEncryptedPendingRows() {
        store.close(); context.deleteDatabase("notipush.db")
        context.openOrCreateDatabase("notipush.db", Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE deliveries (id INTEGER PRIMARY KEY AUTOINCREMENT, package TEXT NOT NULL, label TEXT NOT NULL, created INTEGER NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, payload TEXT NOT NULL)")
            old.execSQL("CREATE TABLE seen (fingerprint TEXT PRIMARY KEY, expires INTEGER NOT NULL)")
            old.execSQL("INSERT INTO deliveries VALUES(1,'app','App',?,'queued','Waiting','ciphertext')", arrayOf(System.currentTimeMillis()))
            old.version = 1
        }
        store = DeliveryStore(context)
        assertEquals("ciphertext", store.get(1)!!.payload)
        assertEquals("queued", store.get(1)!!.status)
        assertEquals(DeliveryTiming(), store.get(1)!!.timing)
        store.recordAttempt(1, "AOD")
        assertEquals(1, store.get(1)!!.timing.attempts)
    }
}
