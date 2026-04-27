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
    connect: suspend (
        profileId: Long,
        commandStartId: Int,
        protocolOptionId: String?,
        previousVpnNetworkHandle: Long?,
    ) -> Unit,
    disconnect: suspend (commandStartId: Int?) -> Unit,
    reload: suspend (profileIdHint: Long) -> Unit,
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
        connect = connect,
        disconnect = disconnect,
        reload = reload,
    )
    return Service.START_STICKY
}
