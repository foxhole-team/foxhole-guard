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
import com.foxhole.beta.core.model.ConnectivityHealthState
import com.foxhole.beta.core.model.NotificationSnapshot
import com.foxhole.beta.core.model.TrafficMode

internal data class ConnectionNotificationAction(
    val serviceAction: String,
    @param:StringRes val labelRes: Int,
    val requestCode: Int,
    val ongoing: Boolean,
    val suppressLocalGuard: Boolean = false,
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
            suppressLocalGuard = true,
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
        snapshot.state in RuntimeNotificationCoordinator.activeNotificationActionStates
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
    @DrawableRes smallIconRes: Int = R.drawable.ic_notification_vpn,
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
                FoxholeConnectionServiceContract.serviceIntent(
                    context = this,
                    mode = mode,
                    action = action.serviceAction,
                    suppressLocalGuard = action.suppressLocalGuard,
                ),
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
                snapshot = snapshot.copy(
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

internal object RuntimeNotificationCoordinator {
    val activeNotificationActionStates =
        setOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.RECONNECTING,
        )
}

internal fun FoxholeVpnService.currentNotificationSnapshotInternal(): NotificationSnapshot {
    val connection = FoxholeVpnRuntimeBridge.snapshot.value
    val ipInfo = FoxholeVpnRuntimeBridge.ipInfo.value
    val traffic = FoxholeVpnRuntimeBridge.traffic.value
    val localGuardMode = activeLocalGuardMode
    if (localGuardMode != null) {
        val analysisStatus = getString(R.string.notification_status_analysis)
        val analysisMessage =
            connection.message
                .takeIf { connection.isSmartStartConnection && it == analysisStatus }
        return NotificationSnapshot(
            state = ConnectionState.CONNECTED,
            statusMessage = analysisMessage ?: localGuardMode.name,
            updatedAt = connection.lastChangeAt,
            isSmartStartConnection = analysisMessage != null,
        )
    }
    return NotificationSnapshot(
        profileName = connection.profileName,
        state = connection.state,
        statusMessage = connection.message,
        connectivityHealthState = notificationConnectivityHealthState,
        ipAddress = ipInfo?.ipv4 ?: ipInfo?.ip,
        countryCode = ipInfo?.countryCode,
        countryName = ipInfo?.countryName,
        trafficAvailable = traffic.available,
        txRate = traffic.txBytesPerSec,
        rxRate = traffic.rxBytesPerSec,
        txTotal = traffic.txTotalBytes,
        rxTotal = traffic.rxTotalBytes,
        updatedAt = maxOf(connection.lastChangeAt, ipInfo?.fetchedAt ?: 0L, traffic.sampledAt),
        isSmartStartConnection = connection.isSmartStartConnection,
    )
}

internal fun FoxholeVpnService.notificationCollapsedTextInternal(snapshot: NotificationSnapshot): String =
    buildNotificationStatusText(snapshot, notificationHealthText(snapshot))

internal fun FoxholeVpnService.notificationExpandedTextInternal(snapshot: NotificationSnapshot): String? =
    buildNotificationStatusText(snapshot, notificationHealthText(snapshot))

internal fun FoxholeVpnService.notificationHealthTextInternal(snapshot: NotificationSnapshot): String? =
    notificationBodyRes(snapshot)?.let(::getString)

internal fun FoxholeVpnService.notificationStateLabelInternal(snapshot: NotificationSnapshot): String =
    when {
        snapshot.statusMessage == getString(R.string.notification_status_analysis) ->
            getString(R.string.notification_status_analysis)
        localGuardFirewallNotificationActive() -> getString(R.string.notification_status_firewall)
        activeLocalGuardMode == LocalGuardMode.JOURNAL -> getString(R.string.notification_status_journal)
        activeLocalGuardMode == LocalGuardMode.DNS -> getString(R.string.notification_status_dns_guard)
        snapshot.state == ConnectionState.CONNECTED ->
            if (snapshot.isSmartStartConnection) {
                getString(R.string.notification_status_connected_smart)
            } else {
                getString(R.string.notification_status_connected)
            }
        snapshot.state == ConnectionState.CONNECTING -> getString(R.string.notification_status_connecting)
        snapshot.state == ConnectionState.RECONNECTING -> getString(R.string.notification_status_reconnecting)
        snapshot.state == ConnectionState.ERROR -> getString(R.string.notification_status_error)
        else -> getString(R.string.notification_status_disconnected)
    }

private fun FoxholeVpnService.buildNotificationStatusText(
    snapshot: NotificationSnapshot,
    baseText: String?,
): String =
    when {
        activeLocalGuardMode != null -> baseText.orEmpty()
        snapshot.state == ConnectionState.CONNECTED && snapshot.isSmartStartConnection ->
            listOfNotNull(baseText, getString(R.string.smart_profile_tag))
                .filter(String::isNotBlank)
                .joinToString(separator = " • ")
        else -> baseText.orEmpty()
    }

private fun FoxholeVpnService.localGuardFirewallNotificationActive(): Boolean {
    val firewallGuardActive = activeLocalGuardMode == LocalGuardMode.FIREWALL
    val journalFirewallActive =
        activeLocalGuardMode == LocalGuardMode.JOURNAL &&
            container.settingsRepository.settings.value.expert.firewallEnabled
    return firewallGuardActive || journalFirewallActive
}

private fun FoxholeVpnService.localGuardNotificationBodyRes(): Int? =
    when {
        localGuardFirewallNotificationActive() -> R.string.notification_body_firewall
        activeLocalGuardMode == LocalGuardMode.JOURNAL -> R.string.notification_body_journal
        activeLocalGuardMode == LocalGuardMode.DNS -> R.string.notification_body_dns_guard
        else -> null
    }

private fun FoxholeVpnService.notificationBodyRes(snapshot: NotificationSnapshot): Int? =
    if (snapshot.statusMessage == getString(R.string.notification_status_analysis)) {
        R.string.notification_body_validating
    } else {
        localGuardNotificationBodyRes() ?: when (snapshot.state) {
            ConnectionState.CONNECTED ->
                when (snapshot.connectivityHealthState) {
                    ConnectivityHealthState.CHECKING -> R.string.notification_body_validating
                    ConnectivityHealthState.ONLINE -> R.string.notification_body_connected
                    ConnectivityHealthState.OFFLINE -> R.string.notification_body_waiting
                }
            ConnectionState.CONNECTING ->
                R.string.notification_body_waiting
            ConnectionState.RECONNECTING ->
                if (snapshot.statusMessage == getString(R.string.status_smart_start_reconnecting)) {
                    R.string.notification_body_smart_start_reconnecting
                } else {
                    R.string.notification_body_reconnecting
                }
            ConnectionState.IDLE,
            ConnectionState.ERROR,
            -> null
        }
    }

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
