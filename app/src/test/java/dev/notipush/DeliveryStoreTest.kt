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
}
