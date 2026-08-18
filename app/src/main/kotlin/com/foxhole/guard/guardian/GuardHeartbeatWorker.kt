package com.foxhole.guard.guardian

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.core.model.GuardHostingMode
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.WatchdogNames

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
        const val WORK_NAME = WatchdogNames.GUARD_ID
    }
}
