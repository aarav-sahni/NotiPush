package dev.notipush

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
class ImmediateDeliveryTest {
    private val tasks = mutableListOf<Runnable>()
    private val executor = Executor { tasks.add(it) }
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun keepsCpuAwakeBeforeExecutorStartsAndReleasesAfterDelivery() {
        val delivered = mutableListOf<Long>()
        val sender = ImmediateDelivery(context, {
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
            delivered.add(it)
            DeliveryEngine.Outcome.Finished
        }, executor)
        sender.dispatch(1); sender.dispatch(1); sender.dispatch(2)
        assertEquals(1, tasks.size)
        val handoff = ShadowPowerManager.getLatestWakeLock()
        assertTrue(handoff.isHeld)
        tasks.removeAt(0).run()
        assertEquals(listOf(1L, 2L), delivered)
        assertFalse(handoff.isHeld)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }
    @Test fun networkFailureStopsBurstAndNextNotificationCanRestartPump() {
        val delivered = mutableListOf<Long>()
        var retry = true
        val sender = ImmediateDelivery(context, {
            delivered.add(it)
            if (retry) DeliveryEngine.Outcome.Retry else DeliveryEngine.Outcome.Finished
        }, executor)
        sender.dispatch(1); sender.dispatch(2)
        tasks.removeAt(0).run()
        assertEquals(listOf(1L), delivered)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        retry = false
        sender.dispatch(3)
        tasks.removeAt(0).run()
        assertEquals(listOf(1L, 3L), delivered)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }
    @Test fun unexpectedFailureReleasesWakeLockAndAllowsNextAttempt() {
        var fail = true
        val sender = ImmediateDelivery(context, {
            if (fail) error("Transport failed")
            DeliveryEngine.Outcome.Finished
        }, executor)
        sender.dispatch(1)
        tasks.removeAt(0).run()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        fail = false
        sender.dispatch(2)
        assertEquals(1, tasks.size)
        tasks.removeAt(0).run()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }
}
