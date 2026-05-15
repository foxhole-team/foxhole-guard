package com.foxhole.beta.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeRuntimeDependencies
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.TrafficMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val pendingResult = goAsync()
        val app = context.applicationContext as FoxholeApplication
        scope.launch {
            try {
                val dependencies: FoxholeRuntimeDependencies = app.appGraph
                val settings = dependencies.settingsRepository.current()
                if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    recoverAfterPackageReplace(context, dependencies)
                    return@launch
                }
                if (settings.connection.autoStartOnBoot) {
                    dependencies.diagnosticsLogger.record("connection", "boot restore requested")
                    FoxholeConnectionServiceContract.startForegroundService(
                        context = context,
                        mode = settings.traffic.mode,
                        action = FoxholeConnectionServiceContract.ACTION_RESTORE,
                    )
                } else {
                    val localGuardMode = settings.localGuardModeOrNull()
                    if (localGuardMode != null) {
                        dependencies.diagnosticsLogger.record(
                            "connection",
                            "boot local guard restore requested mode=${localGuardMode.name.lowercase()}",
                        )
                        FoxholeConnectionServiceContract.startForegroundService(
                            context = context,
                            mode = TrafficMode.TUNNEL,
                            action = FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
                            localGuardMode = localGuardMode,
                        )
                    } else {
                        dependencies.diagnosticsLogger.record("connection", "boot restore skipped: auto start disabled")
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
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
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = plan.killTrafficMode,
                action = FoxholeConnectionServiceContract.ACTION_KILL,
                suppressLocalGuard = true,
            )
            delay(PACKAGE_REPLACE_RESTORE_DELAY_MS)
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
}

internal data class PackageReplaceRecoveryPlan(
    val killStaleRuntime: Boolean,
    val killTrafficMode: TrafficMode,
    val localGuardMode: LocalGuardMode? = null,
    val profileId: Long? = null,
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

private const val PACKAGE_REPLACE_RESTORE_DELAY_MS = 500L
