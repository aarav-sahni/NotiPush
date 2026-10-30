package dev.notipush

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.FileDescriptor
import java.io.PrintWriter

/** User-enabled cross-device text forwarding, with an ongoing status and explicit Pause. */
class ForwardingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = repository
        if (intent?.action == ACTION_PAUSE) repo.pause()
        if (!repo.settings.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Forwarding status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows when your cross-device notification bridge is running."
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, ForwardingService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notipush_mono)
            .setContentTitle("NotiPush is forwarding")
            .setContentText("Listening for selected apps while the screen is off")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Pause", pause).build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            repo.forwarderRunning = true
            repo.settings.lastServiceError = ""
        } catch (_: RuntimeException) {
            repo.forwarderRunning = false
            repo.settings.lastServiceError = "Android blocked the forwarding service. Open NotiPush and check background delivery settings."
            stopSelf()
            return START_NOT_STICKY
        }
        repo.requestReconnect()
        repo.captureWhileAwake { repo.flushPending() }
        return START_STICKY
    }

    override fun onDestroy() {
        repository.forwarderRunning = false
        super.onDestroy()
    }
    // Android's service-dump route is gated by system/ADB DUMP access. Only metadata
    // is returned, so a locked-device test can be inspected afterward without opening UI.
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        writer.println("NOTIPUSH_DIAGNOSTICS_BEGIN")
        runCatching { repository.diagnostics().toString() }
            .onSuccess(writer::println).onFailure { writer.println("Diagnostics unavailable") }
        writer.println("NOTIPUSH_DIAGNOSTICS_END")
    }

    companion object {
        const val CHANNEL = "notipush.forwarding"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PAUSE = "dev.notipush.PAUSE"
    }
}
