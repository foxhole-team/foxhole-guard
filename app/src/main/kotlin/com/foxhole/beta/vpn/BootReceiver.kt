package com.foxhole.beta.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeRuntimeDependencies
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.finishPendingBroadcast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
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
                        app.appGraph.diagnosticsLogger.record("connection", "boot receiver timed out action=$action")
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
    val settings = dependencies.settingsRepository.current()
    if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
        recoverAfterPackageReplace(context, dependencies)
        return
    }

    val plan = bootRestorePlan(settings)
    dependencies.diagnosticsLogger.record("connection", plan.diagnosticMessage)
    when (plan.action) {
        BootRestoreAction.RESTORE_PROFILE ->
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = plan.trafficMode,
                action = FoxholeConnectionServiceContract.ACTION_RESTORE,
            )

        BootRestoreAction.START_LOCAL_GUARD ->
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = TrafficMode.TUNNEL,
                action = FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
                localGuardMode = checkNotNull(plan.localGuardMode),
            )

        BootRestoreAction.SKIP -> Unit
    }
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

private suspend fun recoverAfterPackageReplace(
    context: Context,
    dependencies: FoxholeRuntimeDependencies,
) {
    dependencies.diagnosticsLogger.record("connection", "app_update_replaced")
    dependencies.connectionController.clearAppliedRuntime()
    FoxholeVpnRuntimeBridge.clearTransientState()

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

internal fun packageReplaceRecoveryPlan(
    resumeState: RuntimeResumeState?,
    hasActiveVpnNetwork: Boolean,
    activeProfileId: Long?,
    settingsTrafficMode: TrafficMode,
    localGuardMode: LocalGuardMode?,
): PackageReplaceRecoveryPlan {
    val resumeLocalGuardMode = resumeState?.localGuardMode
    val resumeProfileId =
        resumeState
            ?.profileId
            ?.takeIf { profileId -> profileId > 0L || profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID }
    val resumeTrafficMode = resumeState?.trafficMode
    val killTrafficMode = resumeTrafficMode ?: settingsTrafficMode
    return when {
        resumeLocalGuardMode != null ->
            PackageReplaceRecoveryPlan(
                killStaleRuntime = true,
                killTrafficMode = TrafficMode.TUNNEL,
                localGuardMode = resumeLocalGuardMode,
            )

        resumeProfileId != null && resumeTrafficMode != null ->
            PackageReplaceRecoveryPlan(
                killStaleRuntime = true,
                killTrafficMode = resumeTrafficMode,
                profileId = resumeProfileId,
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

private const val BOOT_RECEIVER_TIMEOUT_MS = 8_000L
private const val PACKAGE_REPLACE_RUNTIME_IDLE_TIMEOUT_MS = 3_000L
private const val PACKAGE_REPLACE_RUNTIME_IDLE_POLL_MS = 100L
