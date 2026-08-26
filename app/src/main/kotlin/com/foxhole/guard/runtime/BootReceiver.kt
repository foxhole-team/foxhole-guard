package com.foxhole.guard.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.GuardHostingMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.RuntimeResumeState
import com.foxhole.core.runtime.RuntimeResumeStateStore
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeRuntimeDependencies
import com.foxhole.guard.R
import com.foxhole.guard.applyGuardReconcileSchedule
import com.foxhole.guard.finishPendingBroadcast
import com.foxhole.guard.guardian.FoxholeGuardService
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PackageReplaceRecoveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FoxholeApplication
        val dependencies: FoxholeRuntimeDependencies = app.appGraph
        recoverAfterPackageReplace(applicationContext, dependencies)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "package_replace_recovery"

        fun enqueue(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<PackageReplaceRecoveryWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (bootReceiverDispatch(action) == null) {
            return
        }
        val pendingResult = goAsync()
        val app = context.applicationContext as FoxholeApplication
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            finishPendingBroadcast(
                timeoutMs = BOOT_RECEIVER_TIMEOUT_MS,
                finish = pendingResult::finish,
                onTimeout = {
                    runCatching {
                        app.appGraph.diagnosticsLogger.recordFailure(
                            "connection",
                            "boot receiver timed out action=$action",
                        )
                    }
                },
            ) {
                val dependencies: FoxholeRuntimeDependencies = app.appGraph
                handleBootReceiverAction(
                    context = context,
                    action = action,
                    dependencies = dependencies,
                )
            }
        }
    }
}

internal suspend fun handleBootReceiverAction(
    context: Context,
    action: String,
    dependencies: FoxholeRuntimeDependencies,
) {
    when (bootReceiverDispatch(action)) {
        BootReceiverDispatch.PACKAGE_REPLACE_RECOVERY -> {
            dependencies.diagnosticsLogger.record("connection", "package replace recovery enqueued")
            runCatching {
                PackageReplaceRecoveryWorker.enqueue(context.applicationContext)
            }.onFailure { error ->
                dependencies.diagnosticsLogger.recordFailure(
                    "connection",
                    "package replace recovery enqueue failed error=${error.javaClass.simpleName}",
                )
            }
            return
        }

        BootReceiverDispatch.BOOT_RESTORE -> Unit
        null -> return
    }

    val security = (context.applicationContext as FoxholeApplication).appGraph.securityComponents

    runCatching { security.journalEvent(GuardEvent(type = GuardEventType.BOOT_COMPLETED)) }

    if (security.isEventMonitoringActive() && security.guardHostingMode() == GuardHostingMode.REINFORCED) {
        runCatching { FoxholeGuardService.start(context) }
    }

    val settings = dependencies.settingsRepository.current()
    val plan = bootRestorePlan(settings)
    dependencies.diagnosticsLogger.record("connection", plan.diagnosticMessage)
    when (plan.action) {
        BootRestoreAction.RESTORE_PROFILE ->
            if (security.isDatabaseLockedForBackground()) {
                runCatching { security.journalEvent(GuardEvent(type = GuardEventType.VPN_RESTORE_DEFERRED)) }
                dependencies.diagnosticsLogger.record("connection", "boot restore deferred: password-locked")

                plan.localGuardMode?.let { guardMode ->
                    startLocalGuardAtBoot(context, guardMode)
                    dependencies.diagnosticsLogger.record(
                        "connection",
                        "boot local guard started while locked mode=${guardMode.name.lowercase()}",
                    )
                }
            } else {
                FoxholeConnectionServiceContract.startForegroundService(
                    context = context,
                    mode = plan.trafficMode,
                    action = FoxholeConnectionServiceContract.ACTION_RESTORE,
                )
            }

        BootRestoreAction.START_LOCAL_GUARD ->
            startLocalGuardAtBoot(context, checkNotNull(plan.localGuardMode))

        BootRestoreAction.SKIP -> Unit
    }
}

private fun startLocalGuardAtBoot(context: Context, mode: LocalGuardMode) {
    context.applyGuardReconcileSchedule(enabled = true)
    FoxholeConnectionServiceContract.startForegroundService(
        context = context,
        mode = TrafficMode.TUNNEL,
        action = FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
        localGuardMode = mode,
    )
}

internal enum class BootReceiverDispatch {
    BOOT_RESTORE,
    PACKAGE_REPLACE_RECOVERY,
}

internal fun bootReceiverDispatch(action: String?): BootReceiverDispatch? =
    when (action) {
        Intent.ACTION_BOOT_COMPLETED -> BootReceiverDispatch.BOOT_RESTORE
        Intent.ACTION_MY_PACKAGE_REPLACED -> BootReceiverDispatch.PACKAGE_REPLACE_RECOVERY
        else -> null
    }

internal enum class BootRestoreAction {
    SKIP,
    RESTORE_PROFILE,
    START_LOCAL_GUARD,
}

internal data class BootRestorePlan(
    val action: BootRestoreAction,
    val trafficMode: TrafficMode,
    val localGuardMode: LocalGuardMode? = null,
    val diagnosticMessage: String,
)

internal fun bootRestorePlan(settings: Settings): BootRestorePlan =
    bootRestorePlan(
        autoStartOnBoot = settings.connection.autoStartOnBoot,
        trafficMode = settings.traffic.mode,
        localGuardMode = settings.localGuardModeOrNull(),
    )

internal fun bootRestorePlan(
    autoStartOnBoot: Boolean,
    trafficMode: TrafficMode,
    localGuardMode: LocalGuardMode?,
): BootRestorePlan =
    when {
        autoStartOnBoot ->
            BootRestorePlan(
                action = BootRestoreAction.RESTORE_PROFILE,
                trafficMode = trafficMode,
                localGuardMode = localGuardMode,
                diagnosticMessage = "boot restore requested",
            )

        localGuardMode != null ->
            BootRestorePlan(
                action = BootRestoreAction.START_LOCAL_GUARD,
                trafficMode = TrafficMode.TUNNEL,
                localGuardMode = localGuardMode,
                diagnosticMessage = "boot local guard restore requested mode=${localGuardMode.name.lowercase()}",
            )

        else ->
            BootRestorePlan(
                action = BootRestoreAction.SKIP,
                trafficMode = trafficMode,
                diagnosticMessage = "boot restore skipped: auto start disabled",
            )
    }

internal suspend fun recoverAfterPackageReplace(
    context: Context,
    dependencies: FoxholeRuntimeDependencies,
) {
    dependencies.diagnosticsLogger.record("connection", "app_update_replaced")
    dependencies.connectionController.clearAppliedRuntime()
    FoxholeVpnRuntimeBridge.clearTransientState()

    val security = (context.applicationContext as FoxholeApplication).appGraph.securityComponents
    runCatching { security.journalEvent(GuardEvent(type = GuardEventType.MY_PACKAGE_REPLACED)) }
    if (security.isDatabaseLockedForBackground()) {
        runCatching { security.journalEvent(GuardEvent(type = GuardEventType.VPN_RESTORE_DEFERRED)) }
        dependencies.diagnosticsLogger.record("connection", "package replace recovery deferred: password-locked")
        return
    }

    val resumeState = RuntimeResumeStateStore.read(context)
    val settings = dependencies.settingsRepository.current()
    val activeProfile = dependencies.profileRepository.getActiveProfile()
    val plan =
        packageReplaceRecoveryPlan(
            resumeState = resumeState,
            hasActiveVpnNetwork = dependencies.connectionController.hasActiveVpnNetwork(),
            activeProfileId = activeProfile?.id,
            settingsTrafficMode = settings.traffic.mode,
            localGuardMode = settings.localGuardModeOrNull(),
            torOnlyRestoreAllowed = settings.privacyRoute.permitted && settings.privacyRoute.enabled,
        )
    if (plan.killStaleRuntime) {
        dependencies.diagnosticsLogger.record(
            "connection",
            "package replace stale runtime kill requested mode=${plan.killTrafficMode.name.lowercase()}",
        )
        val killStartedAtMs = System.currentTimeMillis()
        FoxholeConnectionServiceContract.startForegroundService(
            context = context,
            mode = plan.killTrafficMode,
            action = FoxholeConnectionServiceContract.ACTION_KILL,
            suppressLocalGuard = true,
        )
        val runtimeSettled =
            waitForPackageReplaceRuntimeIdle(
                dependencies = dependencies,
                killTrafficMode = plan.killTrafficMode,
                killStartedAtMs = killStartedAtMs,
            )
        if (!runtimeSettled) {
            dependencies.diagnosticsLogger.recordStructured(
                "connection",
                "package replace stale runtime did not settle",
                "mode=${plan.killTrafficMode.name.lowercase()}",
                "timeout_ms=$PACKAGE_REPLACE_RUNTIME_IDLE_TIMEOUT_MS",
            )
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.ERROR,
                    trafficMode = settings.traffic.mode,
                    message = context.getString(R.string.reconnect_required),
                ),
            )
            return
        }
    }
    when {
        plan.localGuardMode != null -> {
            dependencies.diagnosticsLogger.record(
                "connection",
                "package replace local guard restore requested mode=${plan.localGuardMode.name.lowercase()}",
            )
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = TrafficMode.TUNNEL,
                action = FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
                localGuardMode = plan.localGuardMode,
            )
        }

        plan.profileId != null && plan.profileTrafficMode != null -> {
            val resumeTrafficModeName = plan.profileTrafficMode.name.lowercase()
            dependencies.diagnosticsLogger.record(
                "connection",
                "package replace profile restore requested mode=$resumeTrafficModeName profile=${plan.profileId}",
            )
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = plan.profileTrafficMode,
                action = FoxholeConnectionServiceContract.ACTION_CONNECT,
                profileId = plan.profileId,
                protocolOptionId = plan.protocolOptionId,
            )
        }

        plan.reconnectRequired -> {
            dependencies.diagnosticsLogger.record("connection", "package replace reconnect required")
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.ERROR,
                    trafficMode = settings.traffic.mode,
                    message = context.getString(R.string.reconnect_required),
                ),
            )
        }

        else -> {
            dependencies.diagnosticsLogger.record(
                "connection",
                "package replace restore skipped: no active runtime",
            )
        }
    }
    dependencies.diagnosticsLogger.record("ip", "post-update ip refresh requested reason=post-update")
}

private suspend fun waitForPackageReplaceRuntimeIdle(
    dependencies: FoxholeRuntimeDependencies,
    killTrafficMode: TrafficMode,
    killStartedAtMs: Long,
): Boolean =
    withTimeoutOrNull(PACKAGE_REPLACE_RUNTIME_IDLE_TIMEOUT_MS) {
        while (true) {
            val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
            val hasActiveVpnNetwork =
                killTrafficMode == TrafficMode.TUNNEL &&
                    dependencies.connectionController.hasActiveVpnNetwork()
            if (
                isPackageReplaceRuntimeIdleAfterKill(
                    snapshot = snapshot,
                    hasActiveVpnNetwork = hasActiveVpnNetwork,
                    killTrafficMode = killTrafficMode,
                    killStartedAtMs = killStartedAtMs,
                )
            ) {
                return@withTimeoutOrNull true
            }
            delay(PACKAGE_REPLACE_RUNTIME_IDLE_POLL_MS)
        }
    } == true

internal fun isPackageReplaceRuntimeIdleAfterKill(
    snapshot: ConnectionSnapshot,
    hasActiveVpnNetwork: Boolean,
    killTrafficMode: TrafficMode,
    killStartedAtMs: Long,
): Boolean =
    when {
        killTrafficMode == TrafficMode.TUNNEL && !hasActiveVpnNetwork -> true
        else ->
            snapshot.lastChangeAt >= killStartedAtMs &&
                snapshot.state !in ACTIVE_CONNECTION_STATES &&
                (killTrafficMode != TrafficMode.TUNNEL || !hasActiveVpnNetwork)
    }

internal data class PackageReplaceRecoveryPlan(
    val killStaleRuntime: Boolean,
    val killTrafficMode: TrafficMode,
    val localGuardMode: LocalGuardMode? = null,
    val profileId: Long? = null,
    val protocolOptionId: String? = null,
    val profileTrafficMode: TrafficMode? = null,
    val reconnectRequired: Boolean = false,
)

private data class PackageReplaceResumeProfile(
    val profileId: Long? = null,
    val rejectedTorOnly: Boolean = false,
)

private fun RuntimeResumeState?.resolvePackageReplaceResumeProfile(
    torOnlyRestoreAllowed: Boolean,
): PackageReplaceResumeProfile {
    val storedProfileId = this?.profileId ?: return PackageReplaceResumeProfile()
    if (storedProfileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
        return PackageReplaceResumeProfile(
            profileId = storedProfileId.takeIf { torOnlyRestoreAllowed },
            rejectedTorOnly = !torOnlyRestoreAllowed,
        )
    }
    return PackageReplaceResumeProfile(profileId = storedProfileId.takeIf { it > 0L })
}

internal fun packageReplaceRecoveryPlan(
    resumeState: RuntimeResumeState?,
    hasActiveVpnNetwork: Boolean,
    activeProfileId: Long?,
    settingsTrafficMode: TrafficMode,
    localGuardMode: LocalGuardMode?,
    torOnlyRestoreAllowed: Boolean = true,
): PackageReplaceRecoveryPlan {
    val resumeLocalGuardMode = resumeState?.localGuardMode
    val resumeProfile = resumeState.resolvePackageReplaceResumeProfile(torOnlyRestoreAllowed)
    val resumeTrafficMode = resumeState?.trafficMode
    val killTrafficMode = resumeTrafficMode ?: settingsTrafficMode
    return when {
        resumeLocalGuardMode != null ->
            PackageReplaceRecoveryPlan(
                killStaleRuntime = true,
                killTrafficMode = TrafficMode.TUNNEL,
                localGuardMode = resumeLocalGuardMode,
            )

        resumeProfile.rejectedTorOnly ->
            PackageReplaceRecoveryPlan(
                killStaleRuntime = true,
                killTrafficMode = TrafficMode.TUNNEL,
                localGuardMode = localGuardMode,
            )

        resumeProfile.profileId != null && resumeTrafficMode != null ->
            PackageReplaceRecoveryPlan(
                killStaleRuntime = true,
                killTrafficMode = resumeTrafficMode,
                profileId = resumeProfile.profileId,
                protocolOptionId = resumeState.protocolOptionId,
                profileTrafficMode = resumeTrafficMode,
            )

        hasActiveVpnNetwork && activeProfileId != null ->
            PackageReplaceRecoveryPlan(
                killStaleRuntime = true,
                killTrafficMode = settingsTrafficMode,
                profileId = activeProfileId,
                profileTrafficMode = settingsTrafficMode,
            )

        hasActiveVpnNetwork && localGuardMode != null ->
            PackageReplaceRecoveryPlan(
                killStaleRuntime = true,
                killTrafficMode = TrafficMode.TUNNEL,
                localGuardMode = localGuardMode,
            )

        hasActiveVpnNetwork ->
            PackageReplaceRecoveryPlan(
                killStaleRuntime = true,
                killTrafficMode = killTrafficMode,
                reconnectRequired = true,
            )

        localGuardMode != null ->
            PackageReplaceRecoveryPlan(
                killStaleRuntime = false,
                killTrafficMode = TrafficMode.TUNNEL,
                localGuardMode = localGuardMode,
            )

        else ->
            PackageReplaceRecoveryPlan(
                killStaleRuntime = false,
                killTrafficMode = killTrafficMode,
            )
    }
}

internal const val BOOT_RECEIVER_TIMEOUT_MS = 500L
private const val PACKAGE_REPLACE_RUNTIME_IDLE_TIMEOUT_MS = 3_000L
private const val PACKAGE_REPLACE_RUNTIME_IDLE_POLL_MS = 100L
