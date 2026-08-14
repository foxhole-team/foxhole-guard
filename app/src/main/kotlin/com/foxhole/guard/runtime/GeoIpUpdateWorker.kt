package com.foxhole.guard.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeGeoIpUpdateDependencies

/**
 * Scheduled refresh of the offline IP→country database. Each run is a cheap version probe against
 * the source package manifest; the multi-MB range files download only when the published version
 * actually changed. Runs only while the user keeps the GeoIP auto-update switch on — the switch
 * also gates scheduling, the re-check here just closes the race with a toggle-off.
 */
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
