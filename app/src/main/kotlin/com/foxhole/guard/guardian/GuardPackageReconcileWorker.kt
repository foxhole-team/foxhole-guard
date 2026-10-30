package com.foxhole.guard.guardian

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.core.sentinel.sentinelAttempt
import java.util.concurrent.TimeUnit

class GuardPackageReconcileWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as FoxholeApplication).appGraph
        val security = graph.securityComponents
        if (!security.isEventMonitoringActive()) return Result.success()
        val drained = sentinelAttempt { security.guardSentinel.drainPackageChanges() }
            .onFailure { error ->
                graph.diagnosticsLogger.recordFailure(
                    WORK_NAME,
                    "guard package reconciliation failed error=${error.javaClass.simpleName}",
                )
            }
        return if (drained.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        const val WORK_NAME = "guard-package-reconcile"
    }
}

internal suspend fun Context.enqueueGuardPackageReconcile() {
    val request = OneTimeWorkRequestBuilder<GuardPackageReconcileWorker>()
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(this).enqueueUniqueWork(
        GuardPackageReconcileWorker.WORK_NAME,
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        request,
    ).await()
}
