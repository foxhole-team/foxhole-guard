package com.foxhole.guard.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeThreatIntelUpdateDependencies

/**
 * Periodic refresh of the signed SENTINEL threat-intel feed, mirroring [DnsFilterUpdateWorker]. A
 * skipped refresh (e.g. the feed endpoint is not yet configured) is treated as success so the worker
 * stays cheap and quiet until the feed is activated.
 */
class ThreatIntelUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies: FoxholeThreatIntelUpdateDependencies = (applicationContext as FoxholeApplication).appGraph
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
