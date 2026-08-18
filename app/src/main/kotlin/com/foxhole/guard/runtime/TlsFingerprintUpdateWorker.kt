package com.foxhole.guard.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeTlsFingerprintUpdateDependencies

class TlsFingerprintUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies: FoxholeTlsFingerprintUpdateDependencies =
            (applicationContext as FoxholeApplication).appGraph
        if (!dependencies.settingsRepository.settings.value.connection.tlsFingerprintAutoUpdate) {
            return Result.success()
        }
        val result = dependencies.tlsFingerprintUpdateRepository.refreshNow()
        return when {
            result.status != TlsFingerprintUpdateStatus.FAILED -> Result.success()
            result.retryable -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "tls-fingerprint-update"
    }
}
