package com.foxhole.guard.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeThreatIntelUpdateDependencies
import com.foxhole.guard.threatIntelBackgroundUpdateEnabled

class ThreatIntelUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies: FoxholeThreatIntelUpdateDependencies = (applicationContext as FoxholeApplication).appGraph
        if (!threatIntelBackgroundUpdateEnabled(dependencies.settingsRepository.current())) {
            return Result.success()
        }
        val result = dependencies.threatIntelUpdateRepository.refreshNow()
        return when {
            result.status != ThreatIntelUpdateStatus.FAILED -> Result.success()
            result.retryable -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "threat-intel-update"
    }
}
