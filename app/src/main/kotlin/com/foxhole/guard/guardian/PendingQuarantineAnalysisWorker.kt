package com.foxhole.guard.guardian

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.reconcilePendingQuarantineAnalyses
import java.util.concurrent.TimeUnit

class PendingQuarantineAnalysisWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FoxholeApplication
        val remaining =
            runCatching { app.reconcilePendingQuarantineAnalyses() }
                .onFailure { error ->
                    app.container.diagnosticsLogger.record(
                        "app-inventory",
                        "pending quarantine analysis retry failed: ${error.javaClass.simpleName}",
                    )
                }.getOrElse { true }
        return if (remaining) Result.retry() else Result.success()
    }

    companion object {
        const val WORK_NAME = "pending-quarantine-analysis"
    }
}

internal fun Context.enqueuePendingQuarantineAnalysis() {
    val work =
        OneTimeWorkRequestBuilder<PendingQuarantineAnalysisWorker>()
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS,
            ).build()
    WorkManager.getInstance(this).enqueueUniqueWork(
        PendingQuarantineAnalysisWorker.WORK_NAME,
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        work,
    )
}
