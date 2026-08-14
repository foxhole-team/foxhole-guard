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

/** Durable retry for installs whose fail-closed BLOCK was saved before full risk analysis. */
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
        // A pending package remains safely blocked, but its facts must not stay in the
        // "analysis pending" state forever merely because a fixed retry budget elapsed.
        // WorkManager caps exponential backoff, so this remains bounded in frequency while
        // continuing across process restarts until analysis succeeds or the app is removed.
        return if (remaining) Result.retry() else Result.success()
    }

    companion object {
        const val WORK_NAME = "pending-quarantine-analysis"
    }
}

internal fun Context.enqueuePendingQuarantineAnalysis() {
    val work =
        OneTimeWorkRequestBuilder<PendingQuarantineAnalysisWorker>()
            // The broadcast performs an immediate enrichment pass. This delayed durable pass is
            // insurance for timeout/process death and normally never races the inline analyzer.
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS,
            ).build()
    WorkManager.getInstance(this).enqueueUniqueWork(
        PendingQuarantineAnalysisWorker.WORK_NAME,
        // Never lose an install that arrives while the currently RUNNING worker is between its
        // final repository scan and Result.success(). KEEP drops that wake-up; appending leaves a
        // cheap no-op successor when the current pass already handled it, and a real durable pass
        // when it did not.
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        work,
    )
}
