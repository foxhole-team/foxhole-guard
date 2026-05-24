package com.foxhole.beta.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import com.foxhole.beta.FoxholeRuntimeDependencies
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.NotificationSnapshot

internal fun Service.handleForegroundRuntimeCommand(
    intent: Intent?,
    startId: Int,
    notificationManager: NotificationManager,
    currentNotificationSnapshot: () -> NotificationSnapshot,
    buildNotification: (NotificationSnapshot) -> Notification,
    container: FoxholeRuntimeDependencies,
    launchCommand: (String, suspend () -> Unit) -> Unit,
    launchPriorityCommand: (RuntimeCommandPriority, String, suspend () -> Unit) -> Unit,
    connect: suspend (
        profileId: Long,
        commandStartId: Int,
        protocolOptionId: String?,
        previousVpnNetworkHandle: Long?,
    ) -> Unit,
    disconnect: suspend (commandStartId: Int?) -> Unit,
    disconnectWithOptions: suspend (
        commandStartId: Int?,
        suppressLocalGuard: Boolean,
        preserveSmartStartAnalysis: Boolean,
    ) -> Unit = { commandStartId, _, _ ->
        disconnect(commandStartId)
    },
    reload: suspend (profileIdHint: Long) -> Unit,
    startLocalGuard: suspend (LocalGuardMode, Int) -> Unit = { _, commandStartId ->
        disconnect(commandStartId)
    },
    failClosedTeardown: suspend (commandStartId: Int, action: String?) -> Unit,
): Int {
    ensureConnectionNotificationChannel(notificationManager)
    startForeground(
        FoxholeConnectionServiceContract.NOTIFICATION_ID,
        buildNotification(currentNotificationSnapshot()),
    )
    val action = intent?.action
    when {
        action == null -> {
            val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
            if (snapshot.state in NULL_INTENT_ACTIVE_STATES) {
                container.diagnosticsLogger.record(
                    "connection",
                    "runtime service null intent ignored while runtime active",
                )
            } else {
                launchCommand("null_intent_reconcile") {
                    disconnectWithOptions(
                        startId,
                        true,
                        false,
                    )
                }
            }
        }
        isFailClosedRuntimeServiceCommand(action) -> {
            launchPriorityCommand(
                RuntimeCommandPriority.KILL,
                "fail_closed:$action",
            ) { failClosedTeardown(startId, action) }
        }
        else -> {
            handleRuntimeServiceCommand(
                intent = intent,
                startId = startId,
                container = container,
                launchCommand = launchCommand,
                launchPriorityCommand = launchPriorityCommand,
                connect = connect,
                disconnect = disconnectWithOptions,
                reload = reload,
                startLocalGuard = startLocalGuard,
            )
        }
    }
    return Service.START_NOT_STICKY
}

private val NULL_INTENT_ACTIVE_STATES =
    setOf(
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
    )
