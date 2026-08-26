package com.foxhole.guard.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeDnsFilterUpdateDependencies

class DnsFilterUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies: FoxholeDnsFilterUpdateDependencies = (applicationContext as FoxholeApplication).appGraph

        val result =
            dependencies.dnsFilterUpdateRepository.autoRefresh(
                forcedRefreshIntervalMs = FORCED_REFRESH_INTERVAL_MS,
            )
        return when {
            result.status != DnsFilterUpdateStatus.FAILED -> Result.success()
            result.retryable -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "dns-filter-update"
        private const val FORCED_REFRESH_INTERVAL_MS = 72L * 60L * 60L * 1000L
    }
}
