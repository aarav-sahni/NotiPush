package dev.notipush

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject

class ForwardWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val repo = applicationContext.repository
        val id = inputData.getLong("deliveryId", -1)
        val row = repo.store.get(id) ?: return Result.success()
        if (row.status != "queued") return Result.success()
        if (System.currentTimeMillis() - row.created > 86_400_000) {
            repo.store.finish(id, "failed", "Expired after 24 hours offline")
            return Result.success()
        }
        val test = row.packageName == applicationContext.packageName
        if (!test && (!repo.settings.enabled || repo.settings.rules()[row.packageName]?.enabled != true || !repo.hasAccess())) {
            repo.store.finish(id, "cancelled", "Forwarding paused, source disabled, or notification access removed")
            return Result.success()
        }
        val destination = repo.destinationOrNull()
        if (destination == null) {
            repo.store.finish(id, "failed", "Set up your Pushover destination again")
            return Result.success()
        }
        val message = runCatching {
            val data = JSONObject(repo.vault.decrypt(row.payload))
            PushMessage(data.getString("title"), data.getString("body"), data.getLong("timestamp"))
        }.getOrNull()
        if (message == null) {
            repo.store.finish(id, "failed", "Could not decrypt the queued notification")
            return Result.success()
        }
        // Recheck immediately before the request, including cancellations from settings changes.
        if (isStopped || repo.store.get(id)?.status != "queued") return Result.success()
        return when (val result = PushoverClient().send(destination, message)) {
            PushResult.Accepted -> {
                repo.store.finish(id, "sent", "Accepted by Pushover")
                Result.success()
            }
            is PushResult.Rejected -> {
                repo.store.finish(id, "failed", result.reason)
                Result.success()
            }
            is PushResult.Retry -> {
                repo.store.retryDetail(id, result.reason)
                Result.retry()
            }
        }
    }
}

class RecoveryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        applicationContext.repository.schedulePending()
        return Result.success()
    }
}
