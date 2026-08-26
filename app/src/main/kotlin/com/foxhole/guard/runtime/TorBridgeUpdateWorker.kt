package com.foxhole.guard.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeTorBridgeUpdateDependencies

class TorBridgeUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies: FoxholeTorBridgeUpdateDependencies = (applicationContext as FoxholeApplication).appGraph
        if (!dependencies.settingsRepository.current().privacyRoute.bridgesAutoUpdate) {
            return Result.success()
        }
        val result = dependencies.torBridgeUpdateRepository.refreshNow()
        return if (result.status == TorBridgeUpdateStatus.FAILED) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "tor-bridge-update"
    }
}
