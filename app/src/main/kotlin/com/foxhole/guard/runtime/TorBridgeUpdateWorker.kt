package com.foxhole.guard.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeTorBridgeUpdateDependencies

/**
 * Scheduled 12-hour refresh of the Tor bridge list from the configured source (Tor Project builtin
 * by default, the Foxhole mirror when "Foxhole proxy" is on). Runs only while the user keeps the
 * bridge auto-update switch on; the re-check here just closes the race with a toggle-off. A fresh
 * list applies on the next Tor start (bridges have no hot reload).
 */
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
