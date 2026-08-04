package com.foxhole.guard.runtime

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
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ConnectivityHealthState
import com.foxhole.core.model.NotificationSnapshot
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.guard.R

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

/**
 * Claims the foreground slot the moment the service is created, with a notification that touches
 * nothing but the channel and one string.
 *
 * Android gives a service a few seconds between `startForegroundService()` and `startForeground()`
 * and kills the whole PROCESS with ForegroundServiceDidNotStartInTimeException when it misses the
 * window. Reaching the real notification means going through the app graph, the encrypted settings
 * and the connection snapshot — on a cold process (which is exactly the case after the system just
 * killed us, or on boot restore) that ran past the deadline. Claim the slot first; the true
 * notification replaces this one microseconds later in onStartCommand.
 */
internal fun Service.claimForegroundSlotEarly(notificationManager: NotificationManager) {
    runCatching {
        ensureConnectionNotificationChannel(notificationManager)
        val notification =
            NotificationCompat.Builder(this, FoxholeConnectionServiceContract.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_vpn)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentTitle(getString(R.string.notification_status_connecting))
                .setOngoing(true)
                .build()
        startForeground(FoxholeConnectionServiceContract.NOTIFICATION_ID, notification)
    }
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
            // exp CLI copy: the launcher is the CLI activity - notifications open it.
            Intent(this, com.foxhole.guard.ui.cli.CliMainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val action = notificationActionForState(snapshot.state)
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

internal fun FoxholeVpnService.currentNotificationSnapshotInternal(): NotificationSnapshot {
    val connection = FoxholeVpnRuntimeBridge.snapshot.value
    val ipInfo = FoxholeVpnRuntimeBridge.ipInfo.value
    val traffic = FoxholeVpnRuntimeBridge.traffic.value
    val localGuardMode = activeLocalGuardMode
    if (localGuardMode != null) {
        return NotificationSnapshot(
            state = ConnectionState.CONNECTED,
            statusMessage = localGuardMode.name,
            updatedAt = connection.lastChangeAt,
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
    notificationHealthText(snapshot).orEmpty()

internal fun FoxholeVpnService.notificationExpandedTextInternal(snapshot: NotificationSnapshot): String? {
    val lines =
        buildList {
            notificationHealthText(snapshot)?.takeIf(String::isNotBlank)?.let(::add)
            if (snapshot.state == ConnectionState.CONNECTED && !snapshot.profileName.isNullOrBlank()) {
                add(getString(R.string.notification_body_profile, snapshot.profileName))
            }
            if (container.settingsRepository.settings.value.expert.localSurfaces.allowLanAccess) {
                add(getString(R.string.notification_body_lan_access_enabled))
            }
        }
    return lines.takeIf(List<String>::isNotEmpty)?.joinToString(separator = "\n")
}

internal fun FoxholeVpnService.notificationHealthTextInternal(snapshot: NotificationSnapshot): String? =
    snapshot.statusMessage
        ?.takeIf { snapshot.state == ConnectionState.RECONNECTING }
        ?: notificationBodyRes(snapshot)?.let(::getString)

internal fun FoxholeVpnService.notificationStateLabelInternal(snapshot: NotificationSnapshot): String =
    when {
        localGuardFirewallNotificationActive() -> getString(R.string.notification_status_firewall)
        activeLocalGuardMode == LocalGuardMode.DNS -> getString(R.string.notification_status_dns_guard)
        snapshot.state == ConnectionState.CONNECTED -> getString(connectedNotificationStateLabelRes())
        snapshot.state == ConnectionState.CONNECTING -> getString(R.string.notification_status_connecting)
        snapshot.state == ConnectionState.RECONNECTING -> getString(R.string.notification_status_reconnecting)
        snapshot.state == ConnectionState.ERROR -> getString(R.string.notification_status_error)
        else -> getString(R.string.notification_status_disconnected)
    }

private fun FoxholeVpnService.connectedNotificationStateLabelRes(): Int =
    when (notificationRouteKind()) {
        FoxholeNotificationRouteKind.VPN_TUNNEL -> R.string.notification_status_vpn_tunnel_connected
        FoxholeNotificationRouteKind.VPN_PROXY -> R.string.notification_status_vpn_proxy_connected
        FoxholeNotificationRouteKind.TOR_ONLY -> R.string.notification_status_tor_proxy_connected
        FoxholeNotificationRouteKind.TOR_IN_VPN -> R.string.notification_status_tor_in_vpn_connected
        FoxholeNotificationRouteKind.TOR_BESIDE_VPN -> R.string.notification_status_tor_beside_vpn_connected
        null -> R.string.notification_status_connected
    }

private fun FoxholeVpnService.connectedNotificationBodyRes(): Int =
    when (notificationRouteKind()) {
        FoxholeNotificationRouteKind.VPN_TUNNEL -> R.string.notification_body_connected
        FoxholeNotificationRouteKind.VPN_PROXY -> R.string.notification_body_vpn_proxy_connected
        FoxholeNotificationRouteKind.TOR_ONLY -> R.string.notification_body_tor_proxy_connected
        FoxholeNotificationRouteKind.TOR_IN_VPN -> R.string.notification_body_tor_in_vpn_connected
        FoxholeNotificationRouteKind.TOR_BESIDE_VPN -> R.string.notification_body_tor_beside_vpn_connected
        null -> R.string.notification_body_connected
    }

private fun FoxholeVpnService.localGuardFirewallNotificationActive(): Boolean =
    activeLocalGuardMode == LocalGuardMode.FIREWALL

private fun FoxholeVpnService.localGuardNotificationBodyRes(): Int? =
    when {
        localGuardFirewallNotificationActive() -> R.string.notification_body_firewall
        activeLocalGuardMode == LocalGuardMode.DNS -> R.string.notification_body_dns_guard
        else -> null
    }

private fun FoxholeVpnService.notificationBodyRes(snapshot: NotificationSnapshot): Int? =
    localGuardNotificationBodyRes() ?: when (snapshot.state) {
        ConnectionState.CONNECTED ->
            when (snapshot.connectivityHealthState) {
                ConnectivityHealthState.CHECKING -> R.string.notification_body_validating
                ConnectivityHealthState.ONLINE -> connectedNotificationBodyRes()
                ConnectivityHealthState.OFFLINE -> R.string.notification_body_no_network
            }
        ConnectionState.CONNECTING -> R.string.notification_body_connecting
        ConnectionState.RECONNECTING -> R.string.notification_body_reconnecting
        ConnectionState.IDLE -> null
        ConnectionState.ERROR -> R.string.notification_body_error
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
