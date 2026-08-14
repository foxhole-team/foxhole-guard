package com.foxhole.guard.runtime

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.foxhole.core.model.Settings
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.guard.FoxholeApplication
import java.util.concurrent.TimeUnit

/** Durable retry for a persisted quarantine/BLOCK revision not yet acked by the live runtime. */
class QuarantineRuntimeEnforcementWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as FoxholeApplication
        val container = application.appGraph
        val settings =
            runCatching { container.settingsRepository.current() }
                .onFailure { error ->
                    container.diagnosticsLogger.record(
                        "app-inventory",
                        "quarantine enforcement settings unavailable: ${error.javaClass.simpleName}",
                    )
                }.getOrElse { return Result.retry() }
        if (!shouldRearmQuarantineRuntimeEnforcement(settings)) {
            return Result.success()
        }
        return when (
            runCatching {
                container.connectionController.enforceQuarantinePolicy(
                    settings.expert.quarantinePolicyRevision,
                )
            }.onFailure { error ->
                container.diagnosticsLogger.record(
                    "app-inventory",
                    "quarantine enforcement retry failed: ${error.javaClass.simpleName}",
                )
            }.getOrNull()
        ) {
            QuarantineEnforcementOutcome.APPLIED,
            QuarantineEnforcementOutcome.GENUINELY_IDLE,
            -> Result.success()
            QuarantineEnforcementOutcome.RETRY,
            null,
            -> Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "quarantine-runtime-enforcement"
    }
}

internal fun Context.enqueueQuarantineRuntimeEnforcement(@Suppress("UNUSED_PARAMETER") revision: Long) {
    val work =
        OneTimeWorkRequestBuilder<QuarantineRuntimeEnforcementWorker>()
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS,
            ).build()
    WorkManager.getInstance(this).enqueueUniqueWork(
        QuarantineRuntimeEnforcementWorker.WORK_NAME,
        // A running pass may already have captured revision N when N+1 is persisted. Appending a
        // successor closes that lost-wakeup window; same-reason runtime commands still coalesce.
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        work,
    )
}

internal fun shouldRearmQuarantineRuntimeEnforcement(settings: Settings): Boolean =
    settings.expert.quarantinePolicyRevision > 0L &&
        (
            settings.expert.newAppQuarantineEnabled ||
                settings.expert.pendingQuarantinePackages.isNotEmpty() ||
                settings.expert.blockedLanePackages().isNotEmpty()
            )
