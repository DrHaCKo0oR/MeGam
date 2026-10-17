package app.shaqati.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.shaqati.graph

/** Periodic background search (no notifications; results wait in the app). */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = applicationContext.graph.repository
        repo.refresh()
        return Result.success()
    }
}

/** Collects photo-analysis batch results; reschedules itself while batches are pending. */
class BatchPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = applicationContext.graph.repository
        runCatching { repo.pollBatches() }
        if (repo.hasPendingBatches()) repo.scheduleBatchPoll(if (runAttemptCount < 3) 20 else 60)
        return Result.success()
    }
}
