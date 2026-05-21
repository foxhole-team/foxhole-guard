package com.foxhole.beta.core.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeProfileMaintenanceDependencies

class ProfileSecretCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies: FoxholeProfileMaintenanceDependencies = (applicationContext as FoxholeApplication).appGraph
        return runCatching {
            dependencies.profileRepository.cleanupOrphanProfileSecrets()
            Result.success()
        }.getOrElse { error ->
            dependencies.diagnosticsLogger.record(
                "profile",
                "orphan profile secret cleanup failed: ${error.message ?: error.javaClass.simpleName}",
            )
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "profile-secret-cleanup"
    }
}
