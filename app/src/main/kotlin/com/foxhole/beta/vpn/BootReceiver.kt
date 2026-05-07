package com.foxhole.beta.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeTileDependencies
import com.foxhole.beta.core.model.TrafficMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
                val dependencies: FoxholeTileDependencies = app.appGraph
                val settings = dependencies.settingsRepository.settings.first()
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
}
