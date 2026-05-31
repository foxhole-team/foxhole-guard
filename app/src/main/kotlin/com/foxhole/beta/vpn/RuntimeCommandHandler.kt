package com.foxhole.beta.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import com.foxhole.beta.FoxholeRuntimeDependencies
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.NotificationSnapshot
import com.foxhole.beta.core.model.TrafficMode

internal fun Service.handleForegroundRuntimeCommand(
    intent: Intent?,
    startId: Int,
    trafficMode: TrafficMode,
    notificationManager: NotificationManager,
    currentNotificationSnapshot: () -> NotificationSnapshot,
    buildNotification: (NotificationSnapshot) -> Notification,
    container: FoxholeRuntimeDependencies,
    dispatchRuntimeCommand: (RuntimeCommand, suspend (RuntimeCommand) -> Unit) -> Unit,
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
                dispatchRuntimeCommand(
                    RuntimeCommand.Stop(
                        reason = "null_intent_reconcile",
                        source = RuntimeCommandSource.SYSTEM,
                    ),
                ) {
                    disconnectWithOptions(
                        startId,
                        true,
                        false,
                    )
                }
            }
        }
        isFailClosedRuntimeServiceCommand(action) -> {
            dispatchRuntimeCommand(
                RuntimeCommand.Kill(
                    reason = "fail_closed:$action",
                    source = RuntimeCommandSource.SYSTEM,
                ),
            ) { failClosedTeardown(startId, action) }
        }
        else -> {
            handleRuntimeServiceCommand(
                intent = intent,
                startId = startId,
                trafficMode = trafficMode,
                container = container,
                dispatchRuntimeCommand = dispatchRuntimeCommand,
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
