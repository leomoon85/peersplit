package com.peersplit.app.network.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.peersplit.app.data.local.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Persistent background retry worker for PeerSplit V2 outbox delivery records.
 * Periodically drains unacknowledged outgoing signed operations with exponential backoff.
 */
class OutboxRetryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "OutboxRetryWorker"
        const val WORK_NAME = "peersplit_outbox_retry_worker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<OutboxRetryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Enqueued periodic outbox retry worker")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val database = AppDatabase.getInstance(applicationContext)
            val syncManager = P2PSyncManager(applicationContext, database)
            syncManager.initialize()
            syncManager.flushOutboxOperations()
            syncManager.flushUnsyncedBlocks()
            kotlinx.coroutines.delay(3000L)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in outbox retry worker: ${e.message}", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
