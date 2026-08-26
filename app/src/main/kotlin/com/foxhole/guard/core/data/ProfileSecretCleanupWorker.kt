package com.foxhole.guard.core.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeProfileMaintenanceDependencies

class ProfileSecretCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val appGraph = (applicationContext as FoxholeApplication).appGraph
        if (appGraph.securityComponents.isDatabaseLockedForBackground()) {
            return Result.success()
        }
        val dependencies: FoxholeProfileMaintenanceDependencies = appGraph
        return runCatching {
            dependencies.profileRepository.cleanupOrphanProfileSecrets()
            Result.success()
        }.getOrElse { error ->
            dependencies.diagnosticsLogger.recordFailure(
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
