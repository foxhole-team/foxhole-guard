package com.foxhole.guard.runtime

import android.content.Context
import android.net.VpnService
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.guard.FoxholeApplication

class GuardReconcileWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as FoxholeApplication
        val container = application.appGraph
        val settings = container.settingsRepository.current()
        if (settings.localGuardModeOrNull() == null) {
            return Result.success()
        }
        if (VpnService.prepare(applicationContext) != null) {
            container.diagnosticsLogger.record(
                "connection",
                "guard reconcile skipped: vpn consent missing",
            )
            return Result.success()
        }
        runCatching { container.connectionController.syncLocalGuard() }
            .onFailure { failure ->
                container.diagnosticsLogger.recordFailure(
                    "connection",
                    "guard reconcile failed: ${failure.javaClass.simpleName}",
                )
            }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "guard-reconcile"

        const val HEAL_WORK_NAME = "guard-heal"
    }
}
