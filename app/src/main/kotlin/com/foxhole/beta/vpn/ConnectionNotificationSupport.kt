package com.foxhole.beta.vpn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.foxhole.beta.MainActivity
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.NotificationSnapshot
import com.foxhole.beta.core.model.TrafficMode

internal data class ConnectionNotificationAction(
    val serviceAction: String,
    @param:StringRes val labelRes: Int,
    val requestCode: Int,
    val ongoing: Boolean,
)

internal fun notificationActionForState(state: ConnectionState): ConnectionNotificationAction =
    when (state) {
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
        -> ConnectionNotificationAction(
            serviceAction = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
            labelRes = R.string.disconnect,
            requestCode = 2,
            ongoing = true,
        )

        ConnectionState.IDLE,
        ConnectionState.ERROR,
        -> ConnectionNotificationAction(
            serviceAction = FoxholeConnectionServiceContract.ACTION_RESTORE,
            labelRes = R.string.connect,
            requestCode = 3,
            ongoing = false,
        )
    }

internal fun notificationActionForSnapshot(
    snapshot: NotificationSnapshot,
    analysisStatus: String,
): ConnectionNotificationAction {
    val action = notificationActionForState(snapshot.state)
    return if (
        snapshot.statusMessage == analysisStatus &&
        snapshot.state in ACTIVE_NOTIFICATION_ACTION_STATES
    ) {
        action.copy(labelRes = R.string.notification_action_auto_connect)
    } else {
        action
    }
}

internal fun Service.removeForegroundNotification() {
    stopForeground(Service.STOP_FOREGROUND_REMOVE)
}

internal fun Service.ensureConnectionNotificationChannel(notificationManager: NotificationManager) {
    notificationManager.createNotificationChannel(
        NotificationChannel(
            FoxholeConnectionServiceContract.NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setAllowBubbles(false)
            }
        },
    )
}

internal fun Service.buildConnectionNotification(
    mode: TrafficMode,
    snapshot: NotificationSnapshot,
    collapsedText: (NotificationSnapshot) -> String,
    expandedText: (NotificationSnapshot) -> String?,
    stateLabel: (NotificationSnapshot) -> String,
    @DrawableRes smallIconRes: Int = R.drawable.notification_icon,
    showAction: Boolean = false,
): Notification {
    val openIntent =
        PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val action =
        notificationActionForSnapshot(
            snapshot = snapshot,
            analysisStatus = getString(R.string.notification_status_analysis),
        )
    val builder =
        NotificationCompat.Builder(this, FoxholeConnectionServiceContract.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle(stateLabel(snapshot))
            .setOngoing(action.ongoing)
            .setContentIntent(openIntent)
    if (showAction) {
        val actionIntent =
            PendingIntent.getService(
                this,
                action.requestCode,
                FoxholeConnectionServiceContract.serviceIntent(this, mode, action.serviceAction),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        builder.addAction(0, getString(action.labelRes), actionIntent)
    }
    collapsedText(snapshot).takeIf { it.isNotBlank() }?.let(builder::setContentText)
    expandedText(snapshot)?.let { expanded ->
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
    }
    if (!snapshot.isRedacted) {
        builder.setPublicVersion(
            buildConnectionNotification(
                mode = mode,
                snapshot =
                    snapshot.copy(
                        profileName = null,
                        ipAddress = null,
                        countryCode = null,
                        countryName = null,
                        txRate = 0L,
                        rxRate = 0L,
                        txTotal = 0L,
                        rxTotal = 0L,
                        updatedAt = 0L,
                        isRedacted = true,
                    ),
                collapsedText = collapsedText,
                expandedText = expandedText,
                stateLabel = stateLabel,
                smallIconRes = smallIconRes,
                showAction = showAction,
            ),
        )
    }
    return builder.build()
}

private val ACTIVE_NOTIFICATION_ACTION_STATES =
    setOf(
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
    )

internal fun Service.updateConnectionNotification(buildNotification: () -> Notification) {
    TileService.requestListeningState(this, ComponentName(this, FoxholeTileService::class.java))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    runCatching {
        NotificationManagerCompat.from(this).notify(
            FoxholeConnectionServiceContract.NOTIFICATION_ID,
            buildNotification(),
        )
    }
}
