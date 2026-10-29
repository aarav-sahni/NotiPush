package dev.notipush

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NotiPushApplication : Application() {
    lateinit var repository: NotiPushRepository
        private set
    override fun onCreate() {
        super.onCreate()
        repository = NotiPushRepository(this)
        repository.io.execute {
            repository.settings.initialize()
            repository.schedulePending()
            val recovery = PeriodicWorkRequestBuilder<RecoveryWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork("recover-outbox", ExistingPeriodicWorkPolicy.KEEP, recovery)
        }
    }
}

val Context.repository: NotiPushRepository
    get() = (applicationContext as NotiPushApplication).repository

class NotiPushRepository(private val context: Context) {
    val vault = Vault()
    val settings = SecureSettings(context, vault)
    val store = DeliveryStore(context)
    val io = Executors.newSingleThreadExecutor()
    @Volatile var listenerConnected = false
    fun hasAccess() = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    fun destinationOrNull(): Destination? = runCatching { settings.destination().takeIf { it.validationError() == null } }.getOrNull()
    fun requestReconnect() {
        if (hasAccess()) NotificationListenerService.requestRebind(ComponentName(context, NotificationBridge::class.java))
    }
    fun pause() {
        settings.enabled = false
        store.cancelPending("Cancelled when forwarding was paused")
    }
    fun saveDestination(destination: Destination) {
        if (destinationOrNull() == destination) return
        store.cancelPending("Cancelled because destination settings changed")
        settings.saveDestination(destination)
    }
    fun saveRule(pkg: String, rule: AppRule) {
        settings.saveRule(pkg, rule)
        if (!rule.enabled) store.cancelPending("Source app disabled", pkg)
    }
    fun capture(pkg: String, label: String, captured: CapturedMessage, dedupMillis: Long = 86_400_000): Long? {
        val payload = JSONObject().put("title", captured.message.title).put("body", captured.message.body)
            .put("timestamp", captured.message.timestamp).toString()
        val id = store.enqueue(pkg, label, captured.fingerprint, vault.encrypt(payload), dedupMillis) ?: return null
        schedule(id)
        return id
    }
    fun sendTest(): Long? {
        val now = System.currentTimeMillis()
        return capture(context.packageName, "NotiPush test", CapturedMessage(
            MessagePolicy.fingerprint("test", now.toString()),
            PushMessage("NotiPush · you're connected", "Your Android notifications have a new way home. Test received? You're ready to turn on forwarding.", now)))
    }
    fun schedule(id: Long) {
        val request = OneTimeWorkRequestBuilder<ForwardWorker>()
            .setInputData(workDataOf("deliveryId" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("delivery")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("delivery-$id", ExistingWorkPolicy.KEEP, request)
    }
    fun schedulePending() { store.pending().forEach(::schedule) }
    fun reset() {
        pause()
        WorkManager.getInstance(context).cancelAllWorkByTag("delivery")
        store.erase()
        settings.erase()
        vault.eraseKey()
    }
}
