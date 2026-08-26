package com.foxhole.guard.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
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
import com.foxhole.core.model.RuntimeTeardownPhase
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.networkUp
import com.foxhole.core.model.serving
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.i2pRuntimeActive
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
        ConnectionState.DISCONNECTING,
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

internal fun shouldShowConnectionNotificationAction(state: ConnectionState): Boolean =
    state != ConnectionState.DISCONNECTING

internal fun Service.removeForegroundNotification() {
    stopForeground(Service.STOP_FOREGROUND_REMOVE)
}

internal fun Context.ensureConnectionNotificationChannel(notificationManager: NotificationManager) {
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
    if (showAction && shouldShowConnectionNotificationAction(snapshot.state)) {
        val actionIntent =
            PendingIntent.getService(
                this,
                action.requestCode,
                FoxholeConnectionServiceContract.serviceIntent(
                    context = this,
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
            state = connection.state,
            statusMessage = notificationStatusMessage(connection),
            updatedAt = connection.lastChangeAt,
            isSmartStartConnection = connection.isSmartStartConnection,
        )
    }
    return NotificationSnapshot(
        profileName = connection.profileName,
        state = connection.state,
        statusMessage = notificationStatusMessage(connection),
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

private fun FoxholeVpnService.notificationStatusMessage(
    connection: com.foxhole.core.model.ConnectionSnapshot,
): String? =
    if (connection.state == ConnectionState.DISCONNECTING) {
        getString(notificationTeardownBodyRes(connection.teardownPhase))
    } else {
        connection.message
    }

@StringRes
internal fun notificationTeardownBodyRes(phase: RuntimeTeardownPhase?): Int =
    when (phase) {
        RuntimeTeardownPhase.VPN -> R.string.notification_body_disconnecting_vpn
        RuntimeTeardownPhase.TOR -> R.string.notification_body_disconnecting_tor
        RuntimeTeardownPhase.I2P -> R.string.notification_body_disconnecting_i2p
        RuntimeTeardownPhase.ANDROID_TUNNEL, null -> R.string.notification_body_disconnecting_android_tunnel
    }

internal fun FoxholeVpnService.notificationCollapsedTextInternal(snapshot: NotificationSnapshot): String =
    notificationHealthText(snapshot).orEmpty()

internal fun FoxholeVpnService.notificationExpandedTextInternal(snapshot: NotificationSnapshot): String? {
    val settings = container.settingsRepository.settings.value
    val lines =
        buildList {
            notificationHealthText(snapshot)?.takeIf(String::isNotBlank)?.let(::add)
            if (snapshot.state == ConnectionState.CONNECTED && !snapshot.profileName.isNullOrBlank()) {
                add(getString(R.string.notification_body_profile, snapshot.profileName))
            }
            notificationTorExitLine()?.let(::add)
            addAll(notificationI2pLines(settings))
            addAll(notificationProxySurfaceLines(settings))
        }
    return lines.takeIf(List<String>::isNotEmpty)?.joinToString(separator = "\n")
}

private fun FoxholeVpnService.notificationTorExitLine(): String? {
    if (FoxholeVpnRuntimeBridge.torPhase.value.phase != TorNetworkPhase.CONNECTED) return null
    val info = FoxholeVpnRuntimeBridge.torRouteIpInfo.value ?: return null
    val exit =
        listOfNotNull(
            (info.ipv4 ?: info.ip).takeIf(String::isNotBlank),
            info.countryCode?.takeIf(String::isNotBlank)?.uppercase(),
            info.city?.takeIf(String::isNotBlank),
        ).joinToString(" · ")
    return exit.takeIf(String::isNotBlank)?.let { getString(R.string.notification_body_tor_exit, it) }
}

private fun FoxholeVpnService.notificationI2pLines(settings: Settings): List<String> {
    if (!settings.i2pRuntimeActive()) {
        return emptyList()
    }
    val up = FoxholeVpnRuntimeBridge.i2pPhase.value.phase.networkUp
    val relaying = settings.i2p.relayTransitTraffic
    return buildList {
        add(
            getString(
                when {
                    up && relaying -> R.string.notification_status_i2p_relay_connected
                    up -> R.string.notification_status_i2p_connected
                    else -> R.string.notification_status_i2p_starting
                },
            ),
        )
        if (relaying) {
            add(getString(R.string.notification_body_i2p_relay_thanks))
        }
    }
}

private fun FoxholeVpnService.notificationProxySurfaceLines(settings: Settings): List<String> =
    buildList {
        if (settings.traffic.mode == TrafficMode.PROXY) {
            add(getString(R.string.notification_body_proxy_access_enabled))
        }
        if (FoxholeVpnRuntimeBridge.lanProxyStatus.value.phase.serving) {
            add(getString(R.string.notification_body_lan_access_enabled))
        }
    }

internal fun FoxholeVpnService.notificationHealthTextInternal(snapshot: NotificationSnapshot): String? =
    snapshot.statusMessage
        ?.takeIf {
            snapshot.state == ConnectionState.RECONNECTING ||
                snapshot.state == ConnectionState.DISCONNECTING ||
                snapshot.state == ConnectionState.CONNECTING && snapshot.isSmartStartConnection
        }
        ?: notificationBodyRes(snapshot)?.let(::getString)

internal fun FoxholeVpnService.notificationStateLabelInternal(snapshot: NotificationSnapshot): String =
    when {
        snapshot.state == ConnectionState.DISCONNECTING -> getString(R.string.notification_status_disconnecting)
        snapshot.state == ConnectionState.CONNECTING -> getString(R.string.notification_status_connecting)
        snapshot.state == ConnectionState.RECONNECTING -> getString(R.string.notification_status_reconnecting)
        snapshot.state == ConnectionState.ERROR -> getString(R.string.notification_status_error)
        snapshot.state == ConnectionState.CONNECTED && localGuardFirewallNotificationActive() ->
            getString(R.string.notification_status_firewall)
        snapshot.state == ConnectionState.CONNECTED && activeLocalGuardMode == LocalGuardMode.DNS ->
            getString(R.string.notification_status_dns_guard)
        snapshot.state == ConnectionState.CONNECTED ->
            notificationTorTransitionTitleRes(
                routeKind = notificationRouteKind(),
                phase = FoxholeVpnRuntimeBridge.torPhase.value.phase,
            )?.let(::getString) ?: getString(connectedNotificationStateLabelRes())
        else -> getString(R.string.notification_status_disconnected)
    }

@StringRes
internal fun notificationTorTransitionTitleRes(
    routeKind: FoxholeNotificationRouteKind?,
    phase: TorNetworkPhase,
): Int? {
    val carriesTor = routeKind == FoxholeNotificationRouteKind.TOR_ONLY ||
        routeKind == FoxholeNotificationRouteKind.TOR_IN_VPN ||
        routeKind == FoxholeNotificationRouteKind.TOR_BESIDE_VPN
    if (!carriesTor || phase == TorNetworkPhase.CONNECTED) return null
    return if (phase == TorNetworkPhase.BUILDING_CIRCUITS) {
        R.string.notification_status_tor_building
    } else {
        R.string.notification_status_tor_connecting
    }
}

private fun FoxholeVpnService.connectedNotificationStateLabelRes(): Int {
    val split = notificationSplitScope() != FoxholeNotificationSplitScope.NONE
    return when (notificationRouteKind()) {
        FoxholeNotificationRouteKind.VPN_TUNNEL ->
            if (split) {
                R.string.notification_status_vpn_proxy_connected
            } else {
                R.string.notification_status_vpn_tunnel_connected
            }
        FoxholeNotificationRouteKind.VPN_PROXY -> R.string.notification_status_vpn_proxy_connected
        FoxholeNotificationRouteKind.TOR_ONLY -> R.string.notification_status_tor_proxy_connected
        FoxholeNotificationRouteKind.TOR_IN_VPN ->
            if (split) {
                R.string.notification_status_proxy_tor_inside_proxy_vpn
            } else {
                R.string.notification_status_tor_in_vpn_connected
            }
        FoxholeNotificationRouteKind.TOR_BESIDE_VPN ->
            if (split) {
                R.string.notification_status_proxy_tor_beside_proxy_vpn
            } else {
                R.string.notification_status_tor_beside_vpn_connected
            }
        null -> R.string.notification_status_connected
    }
}

private fun FoxholeVpnService.connectedNotificationBodyRes(): Int {
    val scope = notificationSplitScope()
    return when (notificationRouteKind()) {
        FoxholeNotificationRouteKind.VPN_TUNNEL, FoxholeNotificationRouteKind.VPN_PROXY ->
            scope.bodyRes(
                include = R.string.notification_body_proxy_vpn_include,
                exclude = R.string.notification_body_proxy_vpn_exclude,
                wholeDevice = R.string.notification_body_connected,
            )
        FoxholeNotificationRouteKind.TOR_ONLY -> R.string.notification_body_tor_proxy_connected
        FoxholeNotificationRouteKind.TOR_IN_VPN ->
            scope.bodyRes(
                include = R.string.notification_body_proxy_tor_inside_vpn_include,
                exclude = R.string.notification_body_proxy_tor_inside_vpn_exclude,
                wholeDevice = R.string.notification_body_tor_in_vpn_connected,
            )
        FoxholeNotificationRouteKind.TOR_BESIDE_VPN ->
            scope.bodyRes(
                include = R.string.notification_body_proxy_tor_beside_vpn_include,
                exclude = R.string.notification_body_proxy_tor_beside_vpn_exclude,
                wholeDevice = R.string.notification_body_tor_beside_vpn_connected,
            )
        null -> R.string.notification_body_connected
    }
}

@StringRes
private fun FoxholeNotificationSplitScope.bodyRes(
    @StringRes include: Int,
    @StringRes exclude: Int,
    @StringRes wholeDevice: Int,
): Int =
    when (this) {
        FoxholeNotificationSplitScope.INCLUDE -> include
        FoxholeNotificationSplitScope.EXCLUDE -> exclude
        FoxholeNotificationSplitScope.NONE -> wholeDevice
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
    when (snapshot.state) {
        ConnectionState.CONNECTED ->
            localGuardNotificationBodyRes()
                ?: notificationTorTransitionTitleRes(
                    routeKind = notificationRouteKind(),
                    phase = FoxholeVpnRuntimeBridge.torPhase.value.phase,
                )?.let { R.string.notification_body_tor_connecting }
                ?: when (snapshot.connectivityHealthState) {
                    ConnectivityHealthState.CHECKING -> R.string.notification_body_validating
                    ConnectivityHealthState.ONLINE -> connectedNotificationBodyRes()
                    ConnectivityHealthState.OFFLINE -> R.string.notification_body_no_network
                }
        ConnectionState.CONNECTING -> R.string.notification_body_connecting
        ConnectionState.RECONNECTING -> R.string.notification_body_reconnecting
        ConnectionState.DISCONNECTING -> R.string.notification_body_disconnecting
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
