package com.foxhole.guard.runtime

import android.content.Context
import android.net.VpnService
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.guard.FoxholeApplication

/**
 * Brings the firewall/DNS guard back after a process death.
 *
 * The runtime services return START_NOT_STICKY on purpose (fail-closed: a half-restored VPN must
 * never come back on its own), and the in-service heal job dies with the process it lives in. So a
 * low-memory kill or a swipe from recents used to leave the guard silently down until the user
 * opened the app again. This periodic tick is the out-of-process safety net: it delegates to
 * [FoxholeConnectionController.syncLocalGuard], which is a no-op when the guard is already serving
 * and defers to an active profile tunnel.
 *
 * No network constraint: the guard is a local firewall and must be restored offline too.
 */
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
            // Consent was revoked while we were away; starting the service would only fail-close.
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

        // The one-shot safety net for the in-service heal (see enqueueGuardHealSafetyNet): the heal
        // dies with the service on every error teardown, and this worker carries the retry from
        // outside the process.
        const val HEAL_WORK_NAME = "guard-heal"
    }
}
