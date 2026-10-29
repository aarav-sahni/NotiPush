package dev.notipush

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class ForwardWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = when (
        applicationContext.repository.deliveryEngine.deliver(inputData.getLong("deliveryId", -1)) { isStopped }
    ) {
        DeliveryEngine.Outcome.Finished -> Result.success()
        DeliveryEngine.Outcome.Retry -> Result.retry()
    }
}

class RecoveryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        applicationContext.repository.schedulePending()
        return Result.success()
    }
}
