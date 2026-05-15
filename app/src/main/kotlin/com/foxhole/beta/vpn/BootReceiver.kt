package com.foxhole.beta.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeRuntimeDependencies
import com.foxhole.beta.core.model.TrafficMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
                    if (!restoreRuntimeAfterPackageReplace(context, dependencies)) {
                        dependencies.diagnosticsLogger.record("connection", "package replace restore skipped: no active runtime")
                    }
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

    private suspend fun restoreRuntimeAfterPackageReplace(
        context: Context,
        dependencies: FoxholeRuntimeDependencies,
    ): Boolean {
        val resumeState = RuntimeResumeStateStore.read(context)
        val resumeLocalGuardMode = resumeState?.localGuardMode
        if (resumeLocalGuardMode != null) {
            dependencies.diagnosticsLogger.record(
                "connection",
                "package replace local guard restore requested mode=${resumeLocalGuardMode.name.lowercase()}",
            )
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = TrafficMode.TUNNEL,
                action = FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
                localGuardMode = resumeLocalGuardMode,
            )
            return true
        }
        val resumeProfileId =
            resumeState
                ?.profileId
                ?.takeIf { profileId -> profileId > 0L || profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID }
        if (resumeProfileId != null) {
            dependencies.diagnosticsLogger.record(
                "connection",
                "package replace profile restore requested mode=${resumeState.trafficMode.name.lowercase()} profile=$resumeProfileId",
            )
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = resumeState.trafficMode,
                action = FoxholeConnectionServiceContract.ACTION_CONNECT,
                profileId = resumeProfileId,
            )
            return true
        }
        if (!dependencies.connectionController.hasActiveVpnNetwork()) {
            return false
        }
        val settings = dependencies.settingsRepository.current()
        val activeProfile = dependencies.profileRepository.getActiveProfile()
        if (activeProfile != null) {
            dependencies.diagnosticsLogger.record(
                "connection",
                "package replace active vpn network found; restarting active profile=${activeProfile.id}",
            )
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = settings.traffic.mode,
                action = FoxholeConnectionServiceContract.ACTION_CONNECT,
                profileId = activeProfile.id,
            )
            return true
        }
        val localGuardMode = settings.localGuardModeOrNull()
        if (localGuardMode != null) {
            dependencies.diagnosticsLogger.record(
                "connection",
                "package replace active vpn network found; restarting local guard mode=${localGuardMode.name.lowercase()}",
            )
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = TrafficMode.TUNNEL,
                action = FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
                localGuardMode = localGuardMode,
            )
            return true
        }
        dependencies.diagnosticsLogger.record("connection", "package replace active vpn network found; clearing stale vpn service")
        FoxholeConnectionServiceContract.startForegroundService(
            context = context,
            mode = TrafficMode.TUNNEL,
            action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
            suppressLocalGuard = true,
        )
        return true
    }
}
