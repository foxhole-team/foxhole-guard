package com.foxhole.beta.vpn

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeDnsFilterUpdateDependencies

class DnsFilterUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies: FoxholeDnsFilterUpdateDependencies = (applicationContext as FoxholeApplication).appGraph
        val result = dependencies.dnsFilterUpdateRepository.refreshNow(requireAutoEnabled = true)
        return when {
            result.status != DnsFilterUpdateStatus.FAILED -> Result.success()
            result.retryable -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "dns-filter-update"
    }
}
