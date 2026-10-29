package dev.notipush

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Shared by live notification delivery and persistent retries in this app process. */
class DeliveryEngine(
    private val store: DeliveryStore,
    private val allowed: (String) -> Boolean,
    private val destination: () -> Destination?,
    private val decode: (String) -> PushMessage,
    private val transmit: (Destination, PushMessage) -> PushResult,
    private val onAccepted: () -> Unit = {}
) {
    enum class Outcome { Finished, Retry }

    // Both send paths hold this lock from the queue read through completion.
    // Process death leaves the encrypted row queued; no lease can get stuck.
    private val gate = ReentrantLock(true)
    fun deliver(id: Long, stopped: () -> Boolean = { false }): Outcome {
        // Bound contention so an immediate attempt doesn't exhaust its wake lock waiting.
        if (!gate.tryLock(5, TimeUnit.SECONDS)) return Outcome.Retry
        try { return deliverLocked(id, stopped) } finally { gate.unlock() }
    }
    private fun deliverLocked(id: Long, stopped: () -> Boolean): Outcome {
        val row = store.get(id) ?: return Outcome.Finished
        if (row.status != "queued") return Outcome.Finished
        if (System.currentTimeMillis() - row.created > 86_400_000) {
            store.finish(id, "failed", "Expired after 24 hours offline")
            return Outcome.Finished
        }
        if (!allowed(row.packageName)) {
            store.finish(id, "cancelled", "Forwarding paused, source disabled, or notification access removed")
            return Outcome.Finished
        }
        val target = destination()
        if (target == null) {
            store.finish(id, "failed", "Set up your Pushover destination again")
            return Outcome.Finished
        }
        val message = runCatching { decode(row.payload) }.getOrNull()
        if (message == null) {
            store.finish(id, "failed", "Could not decrypt the queued notification")
            return Outcome.Finished
        }
        if (stopped() || store.get(id)?.status != "queued") return Outcome.Finished
        if (!allowed(row.packageName)) {
            store.finish(id, "cancelled", "Forwarding paused, source disabled, or notification access removed")
            return Outcome.Finished
        }
        val result = try { transmit(target, message) } catch (_: Exception) {
            PushResult.Retry("Delivery interrupted; retry queued")
        }
        return when (result) {
            PushResult.Accepted -> {
                store.finish(id, "sent", "Accepted by Pushover")
                onAccepted()
                Outcome.Finished
            }
            is PushResult.Rejected -> {
                store.finish(id, "failed", result.reason)
                Outcome.Finished
            }
            is PushResult.Retry -> {
                store.retryDetail(id, result.reason)
                Outcome.Retry
            }
        }
    }
}
