package com.foxhole.guard.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeGeoIpUpdateDependencies

class GeoIpUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies: FoxholeGeoIpUpdateDependencies = (applicationContext as FoxholeApplication).appGraph
        if (!dependencies.settingsRepository.current().connection.geoIpAutoUpdate) {
            return Result.success()
        }
        val result = dependencies.geoIpUpdateRepository.refreshNow()
        return if (result.status == GeoIpUpdateStatus.FAILED) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "geoip-update"
    }
}
