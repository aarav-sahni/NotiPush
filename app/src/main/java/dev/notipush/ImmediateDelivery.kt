package dev.notipush

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** A short CPU handoff from the listener callback to capture / HTTPS. */
internal fun notificationWakeLock(context: Context): PowerManager.WakeLock? = runCatching {
    context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NotiPush:notification")
        .apply { setReferenceCounted(false); acquire(45_000) }
}.getOrNull()

internal fun PowerManager.WakeLock?.releaseSafely() {
    if (this != null) runCatching { if (isHeld) release() }
}

/** Live delivery doesn't wait for a job slot. WorkManager is the durable fallback. */
internal class ImmediateDelivery(
    private val context: Context,
    private val deliver: (Long) -> DeliveryEngine.Outcome,
    private val executor: Executor = Executors.newSingleThreadExecutor()
) {
    private val gate = Any()
    private val pending = linkedSetOf<Long>()
    private var running = false

    fun dispatch(id: Long) {
        synchronized(gate) {
            pending.add(id)
            if (running) return
            running = true
            // Acquire before returning to the notification listener / capture thread.
            val wakeLock = notificationWakeLock(context)
            try { executor.execute { drain(wakeLock) } }
            catch (_: RuntimeException) {
                pending.clear(); running = false; wakeLock.releaseSafely()
                // Each ID already has a durable WorkManager fallback.
            }
        }
    }

    private fun drain(initialLock: PowerManager.WakeLock?) {
        var wakeLock = initialLock
        try {
            while (true) {
                val id = synchronized(gate) {
                    pending.firstOrNull().also { next ->
                        if (next == null) running = false else pending.remove(next)
                    }
                } ?: return
                // Renew only for actual work; overlap covers the CPU handoff.
                val nextLock = notificationWakeLock(context)
                wakeLock.releaseSafely()
                wakeLock = nextLock
                if (deliver(id) == DeliveryEngine.Outcome.Retry) {
                    // Don't attempt the whole backlog on a broken network.
                    synchronized(gate) { pending.clear(); running = false }
                    return
                }
            }
        } catch (_: Exception) {
            synchronized(gate) { pending.clear(); running = false }
        } finally { wakeLock.releaseSafely() }
    }
}
