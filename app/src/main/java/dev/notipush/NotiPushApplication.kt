package dev.notipush

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
import androidx.work.*
import org.json.JSONObject
import org.json.JSONArray
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
            if (repository.settings.enabled && repository.batteryExempt()) repository.ensureForwarder()
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
    val sources = AppSources(context)
    val store = DeliveryStore(context)
    val io = Executors.newSingleThreadExecutor()
    // Isolated device tests replace this; production starts with HTTPS Pushover.
    internal var transmit: (Destination, PushMessage) -> PushResult = PushoverClient()::send
    val deliveryEngine = DeliveryEngine(store,
        allowed = { pkg -> pkg == context.packageName ||
            (settings.enabled && settings.ruleFor(pkg)?.enabled == true && hasAccess()) },
        destination = ::destinationOrNull,
        decode = { encrypted ->
            val data = JSONObject(vault.decrypt(encrypted))
            PushMessage(data.getString("title"), data.getString("body"), data.getLong("timestamp"))
        },
        transmit = { target, message -> transmit(target, message) },
        onAccepted = { settings.recordDelivery(screenOffOrLocked()) },
        onAttempt = { id -> store.recordAttempt(id, powerDescription()) },
        onCompletion = { id -> store.recordCompletion(id, powerDescription()) })
    private val immediate = ImmediateDelivery(context, { id -> deliveryEngine.deliver(id) })
    @Volatile var listenerConnected = false
    @Volatile var forwarderRunning = false
    fun enableForwarding() {
        check(batteryExempt()) { "Allow background delivery first" }
        check(hasAccess() && destinationOrNull() != null) { "Complete notification access and destination setup" }
        if (!settings.enabled) settings.enabled = true
        if (!ensureForwarder()) {
            settings.enabled = false
            error("Android blocked the forwarding service")
        }
        requestReconnect()
    }
    fun ensureForwarder(): Boolean {
        if (!settings.enabled || forwarderRunning) return true
        return try {
            ContextCompat.startForegroundService(context, Intent(context, ForwardingService::class.java))
            true
        } catch (_: RuntimeException) {
            settings.lastServiceError = "Android blocked service startup. Open NotiPush and allow background delivery."
            false
        }
    }
    fun batteryExempt() = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    fun powerDescription(): String {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val power = context.getSystemService(PowerManager::class.java)
        val state = when (display?.state) {
            Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "AOD"
            Display.STATE_OFF -> "screen off"
            Display.STATE_ON, Display.STATE_ON_SUSPEND -> if (!power.isInteractive) "non-interactive display" else if (screenOffOrLocked()) "lock screen" else "screen on"
            else -> "unknown display"
        }
        return state + (if (power.isDeviceIdleMode) " · Doze" else "") +
            (if (!batteryExempt()) " · battery restricted" else "")
    }
    private fun screenOffOrLocked() = !context.getSystemService(PowerManager::class.java).isInteractive ||
        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    fun captureWhileAwake(action: () -> Unit) {
        val wakeLock = notificationWakeLock(context)
        try { io.execute { try { action() } finally { wakeLock.releaseSafely() } } }
        catch (error: RuntimeException) { wakeLock.releaseSafely(); throw error }
    }
    fun hasAccess() = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(context, NotificationBridge::class.java))
    fun destinationOrNull(): Destination? = runCatching { settings.destination().takeIf { it.validationError() == null } }.getOrNull()
    fun requestReconnect() {
        if (hasAccess()) NotificationListenerService.requestRebind(ComponentName(context, NotificationBridge::class.java))
    }
    fun pause() {
        settings.enabled = false
        context.stopService(Intent(context, ForwardingService::class.java))
        forwarderRunning = false
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
    fun capture(pkg: String, label: String, captured: CapturedMessage, dedupMillis: Long = 86_400_000,
                timing: DeliveryTiming = DeliveryTiming()): Long? {
        val payload = JSONObject().put("title", captured.message.title).put("body", captured.message.body)
            .put("timestamp", captured.message.timestamp).toString()
        val id = store.enqueue(pkg, label, captured.fingerprint, vault.encrypt(payload), dedupMillis, timing) ?: return null
        if (pkg != context.packageName) settings.recordCapture(screenOffOrLocked())
        // Persist a fallback first, then send without waiting on Android's job scheduler.
        try { schedule(id) } finally { immediate.dispatch(id) }
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
            .setInitialDelay(45, TimeUnit.SECONDS)
            .addTag("delivery")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("delivery-$id", ExistingWorkPolicy.KEEP, request)
    }
    fun schedulePending() { store.pending().forEach(::schedule) }
    fun flushPending() {
        store.pending().forEach { id -> try { schedule(id) } finally { immediate.dispatch(id) } }
    }
    fun diagnostics(): JSONObject {
        val rules = settings.rules()
        val selectedPackages = starterApps.map { it.packageName }.toSet()
        val catalog = sources.discover(settings.observedSources())
        val sourceMetadata = catalog.sources.filter { it.packageName in selectedPackages || it.key in rules }.map {
            val rule = settings.ruleFor(it.key)
            JSONObject().put("source", it.key).put("label", rule?.displayName?.ifBlank { it.label } ?: it.label)
                .put("enabled", rule?.enabled == true)
        }
        val deliveries = store.history().take(20).map { row ->
            val t = row.timing
            JSONObject().put("id", row.id).put("source", row.packageName).put("label", row.label)
                .put("createdAt", row.created).put("status", row.status).put("detail", row.detail)
                .put("postedAt", t.postedAt).put("receivedAt", t.receivedAt).put("receivedState", t.receivedState)
                .put("firstAttemptAt", t.firstAttemptAt).put("firstAttemptState", t.firstAttemptState)
                .put("lastAttemptAt", t.lastAttemptAt).put("lastAttemptState", t.lastAttemptState)
                .put("completedAt", t.completedAt).put("completedState", t.completedState).put("attempts", t.attempts)
        }
        return JSONObject().put("version", BuildConfig.VERSION_NAME).put("enabled", settings.enabled)
            .put("listenerConnected", listenerConnected).put("notificationAccess", hasAccess())
            .put("batteryExempt", batteryExempt()).put("forwarderRunning", forwarderRunning)
            .put("configurationPresent", destinationOrNull() != null).put("power", powerDescription())
            .put("serviceError", settings.lastServiceError).put("sources", JSONArray(sourceMetadata))
            .put("deliveries", JSONArray(deliveries)).put("listenerEvents", JSONArray(settings.preferences.getString("listenerObservations", "[]") ?: "[]"))
    }
    fun reset() {
        pause()
        WorkManager.getInstance(context).cancelAllWorkByTag("delivery")
        store.erase()
        settings.erase()
        vault.eraseKey()
    }
}
