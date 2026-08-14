package com.foxhole.guard.guardian

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.core.model.GuardHostingMode
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.WatchdogNames

/**
 * Energy-efficient fallback tick for [WatchdogNames.GUARD]: runs on WorkManager's periodic
 * schedule with NO network constraint (the guard must work offline). Each tick first checks
 * for a suspected blackout, then emits its own heartbeat and reconciles the package inventory
 * (catching changes missed while no host was attached). In REINFORCED mode it also starts the
 * dedicated guard service when nothing is currently keeping the daemon resident.
 */
class GuardHeartbeatWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as FoxholeApplication).appGraph
        val security = graph.securityComponents
        if (!security.isEventMonitoringActive()) {
            return Result.success()
        }
        val sentinel = security.guardSentinel
        val tick = runCatching {
            // Detect BEFORE this worker writes its own heartbeat, otherwise every gap is hidden by
            // the just-written record and blackout detection can never fire.
            sentinel.detectBlackout()
            sentinel.emitHeartbeat("worker")
            sentinel.reconcileInventory()
        }.onFailure { error ->
            graph.diagnosticsLogger.recordFailure(
                WatchdogNames.GUARD_ID,
                "guard heartbeat tick failed error=${error.javaClass.simpleName}",
            )
        }
        if (tick.isFailure) {
            return Result.retry()
        }
        if (
            security.guardHostingMode() == GuardHostingMode.REINFORCED &&
            !sentinel.hasAttachedHosts()
        ) {
            runCatching { FoxholeGuardService.start(applicationContext) }
        }
        return Result.success()
    }

    companion object {
        /** WorkManager keys unique work by string, so the watchdog's name goes in as an id. */
        const val WORK_NAME = WatchdogNames.GUARD_ID
    }
}
