package com.foxhole.beta.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import com.foxhole.beta.FoxholeRuntimeDependencies
import com.foxhole.beta.core.model.NotificationSnapshot

internal fun Service.handleForegroundRuntimeCommand(
    intent: Intent?,
    startId: Int,
    notificationManager: NotificationManager,
    currentNotificationSnapshot: () -> NotificationSnapshot,
    buildNotification: (NotificationSnapshot) -> Notification,
    container: FoxholeRuntimeDependencies,
    launchCommand: (suspend () -> Unit) -> Unit,
    launchPriorityCommand: (suspend () -> Unit) -> Unit,
    connect: suspend (
        profileId: Long,
        commandStartId: Int,
        protocolOptionId: String?,
        previousVpnNetworkHandle: Long?,
    ) -> Unit,
    disconnect: suspend (commandStartId: Int?) -> Unit,
    disconnectWithOptions: suspend (commandStartId: Int?, suppressLocalGuard: Boolean) -> Unit = { commandStartId, _ ->
        disconnect(commandStartId)
    },
    reload: suspend (profileIdHint: Long) -> Unit,
    startLocalGuard: suspend (LocalGuardMode, Int) -> Unit = { _, commandStartId ->
        disconnect(commandStartId)
    },
): Int {
    ensureConnectionNotificationChannel(notificationManager)
    startForeground(
        FoxholeConnectionServiceContract.NOTIFICATION_ID,
        buildNotification(currentNotificationSnapshot()),
    )
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
    return Service.START_STICKY
}
