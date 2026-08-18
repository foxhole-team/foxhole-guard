package com.foxhole.guard.widget

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.tunnelKeptOutPackages
import com.foxhole.guard.core.settings.activeRoutingModePreset
import com.foxhole.guard.ui.cli.home.CliActiveRuntimes
import com.foxhole.guard.ui.cli.home.CliCompactRouteStatus
import com.foxhole.guard.ui.cli.home.cliCompactRouteStatus
import com.foxhole.guard.ui.cli.home.cliSecureDnsLabel
import com.foxhole.guard.ui.defaultDnsServerFor

internal data class StatusWidgetPresentation(
    val connection: StatusWidgetConnection,
    val primaryActive: Boolean,
    val vpnProfile: String?,
    val vpnProtocol: String?,
    val mode: StatusWidgetMode?,
    val scenario: StatusWidgetScenario?,
    val routeStatus: CliCompactRouteStatus?,
    val i2pConnected: Boolean,
    val components: List<StatusWidgetComponent>,
    val vpnIdentity: IpInfo?,
    val vpnLatencyMs: Long?,
    val torIdentity: IpInfo?,
    val torLatencyMs: Long?,
    val dnsServer: String,
)

internal enum class StatusWidgetConnection {
    CONNECTED,
    CONNECTING,
    RECONNECTING,
    DISCONNECTING,
    DISCONNECTED,
    ERROR,
}

internal enum class StatusWidgetMode { VPN, TOR, VPN_TOR }

internal enum class StatusWidgetScope { WHOLE_DEVICE, SELECTED_APPS, EXCEPT_SELECTED }

internal data class StatusWidgetScenario(
    val vpn: StatusWidgetScope?,
    val tor: StatusWidgetScope?,
)

internal enum class StatusWidgetComponent { FIREWALL, TOR, I2P, SENTINEL }

internal enum class StatusWidgetControlAction { START, STOP, RESTART }

internal data class StatusWidgetRuntimeKey(
    val state: ConnectionState,
    val profileId: Long?,
    val torActive: Boolean,
    val trafficMode: TrafficMode,
    val profileName: String?,
    val protocolHint: ProtocolHint?,
)

internal fun ConnectionSnapshot.statusWidgetRuntimeKey(): StatusWidgetRuntimeKey =
    StatusWidgetRuntimeKey(
        state = state,
        profileId = profileId,
        torActive = torActive,
        trafficMode = trafficMode,
        profileName = profileName,
        protocolHint = protocolHint,
    )

internal fun statusWidgetControlActions(
    connection: StatusWidgetConnection,
    primaryActive: Boolean,
): List<StatusWidgetControlAction> =
    when {
        connection == StatusWidgetConnection.DISCONNECTING -> emptyList()
        primaryActive &&
            (
                connection == StatusWidgetConnection.CONNECTING ||
                    connection == StatusWidgetConnection.RECONNECTING
                ) -> listOf(StatusWidgetControlAction.STOP)
        primaryActive -> listOf(StatusWidgetControlAction.STOP, StatusWidgetControlAction.RESTART)
        else -> listOf(StatusWidgetControlAction.START)
    }

private data class StatusWidgetRoute(
    val vpnLive: Boolean,
    val torLive: Boolean,
    val vpnConnected: Boolean,
    val torConnected: Boolean,
    val connection: StatusWidgetConnection,
    val liveMode: StatusWidgetMode?,
)

internal fun statusWidgetPresentation(
    snapshot: ConnectionSnapshot,
    settings: Settings,
    vpnIpInfo: IpInfo?,
    torIpInfo: IpInfo?,
    i2pConnected: Boolean,
    deviceIpInfo: IpInfo? = null,
): StatusWidgetPresentation {
    val route = snapshot.widgetRoute()
    val mode = route.liveMode ?: settings.configuredWidgetMode()
    val rememberedLatency = settings.rememberedWidgetLatency(snapshot.profileId)
    val runtimes = route.widgetActiveRuntimes(snapshot, settings, i2pConnected)
    return StatusWidgetPresentation(
        connection = route.connection.withI2p(i2pConnected),
        primaryActive = route.vpnLive || route.torLive,
        vpnProfile = snapshot.widgetVpnProfile(route.vpnConnected),
        vpnProtocol = snapshot.protocolHint?.name.takeIfConnected(route.vpnConnected),
        mode = mode,
        scenario = settings.widgetScenario(mode),
        routeStatus = cliCompactRouteStatus(settings, runtimes),
        i2pConnected = i2pConnected,
        components = widgetComponents(snapshot, settings, route.torConnected, i2pConnected),
        vpnIdentity =
        vpnIpInfo.takeIfConnected(route.vpnConnected)
            ?: deviceIpInfo.takeIfConnected(i2pConnected || route.torConnected),
        vpnLatencyMs = rememberedLatency.takeIfConnected(route.vpnConnected),
        torIdentity = resolvedTorIdentity(route, vpnIpInfo, torIpInfo),
        torLatencyMs = null,
        dnsServer = settings.dns.server.trim().takeIf { it.isNotBlank() }
            ?.let { server -> "${cliSecureDnsLabel(settings.dns.secureMode)} · $server" }
            ?: "${cliSecureDnsLabel(settings.dns.secureMode)} · ${defaultDnsServerFor(settings.dns.secureMode)}",
    )
}

private fun ConnectionSnapshot.widgetRoute(): StatusWidgetRoute {
    val runtimePresent = state in WIDGET_ACTIVE_STATES
    val activeProfileId = profileId
    val vpnLive = runtimePresent && activeProfileId != null && activeProfileId > 0L
    val torLive = runtimePresent && (activeProfileId == TOR_ONLY_PROFILE_ID || torActive)
    val connected = state == ConnectionState.CONNECTED
    val vpnConnected = connected && vpnLive
    val torConnected = connected && torLive
    return StatusWidgetRoute(
        vpnLive = vpnLive,
        torLive = torLive,
        vpnConnected = vpnConnected,
        torConnected = torConnected,
        connection = widgetConnection(vpnConnected || torConnected, state),
        liveMode = widgetMode(vpnLive, torLive),
    )
}

private fun widgetConnection(
    routeConnected: Boolean,
    state: ConnectionState,
): StatusWidgetConnection =
    when {
        state == ConnectionState.CONNECTING -> StatusWidgetConnection.CONNECTING
        state == ConnectionState.RECONNECTING -> StatusWidgetConnection.RECONNECTING
        state == ConnectionState.DISCONNECTING -> StatusWidgetConnection.DISCONNECTING
        state == ConnectionState.ERROR -> StatusWidgetConnection.ERROR
        routeConnected -> StatusWidgetConnection.CONNECTED
        else -> StatusWidgetConnection.DISCONNECTED
    }

private fun StatusWidgetConnection.withI2p(i2pConnected: Boolean): StatusWidgetConnection =
    if (this == StatusWidgetConnection.DISCONNECTED && i2pConnected) {
        StatusWidgetConnection.CONNECTED
    } else {
        this
    }

private fun StatusWidgetRoute.widgetActiveRuntimes(
    snapshot: ConnectionSnapshot,
    settings: Settings,
    i2pConnected: Boolean,
): CliActiveRuntimes =
    CliActiveRuntimes(
        vpn = vpnLive,
        proxy = vpnLive && snapshot.trafficMode == com.foxhole.core.model.TrafficMode.PROXY,
        tor = torLive,
        torBesideVpn = vpnLive && torLive && settings.privacyRoute.bypassVpnTunnel,
        i2p = i2pConnected,
    )

private fun widgetMode(
    vpnConnected: Boolean,
    torConnected: Boolean,
): StatusWidgetMode? =
    when {
        vpnConnected && torConnected -> StatusWidgetMode.VPN_TOR
        vpnConnected -> StatusWidgetMode.VPN
        torConnected -> StatusWidgetMode.TOR
        else -> null
    }

private fun Settings.configuredWidgetMode(): StatusWidgetMode =
    when (activeRoutingModePreset()) {
        RoutingModePreset.VPN,
        RoutingModePreset.SPLIT_INCLUDE,
        RoutingModePreset.SPLIT_EXCLUDE,
        -> StatusWidgetMode.VPN
        RoutingModePreset.TOR -> StatusWidgetMode.TOR
        RoutingModePreset.VPN_TOR -> StatusWidgetMode.VPN_TOR
    }

private fun Settings.widgetScenario(mode: StatusWidgetMode): StatusWidgetScenario =
    StatusWidgetScenario(
        vpn = vpnWidgetScope().takeIf {
            mode == StatusWidgetMode.VPN || mode == StatusWidgetMode.VPN_TOR
        },
        tor = torWidgetScope().takeIf {
            mode == StatusWidgetMode.TOR || mode == StatusWidgetMode.VPN_TOR
        },
    )

private fun widgetComponents(
    snapshot: ConnectionSnapshot,
    settings: Settings,
    torConnected: Boolean,
    i2pConnected: Boolean,
): List<StatusWidgetComponent> =
    buildList {
        val runtimePresent = snapshot.state in WIDGET_ACTIVE_STATES
        if (settings.expert.firewallEnabled && runtimePresent) add(StatusWidgetComponent.FIREWALL)
        if (torConnected) add(StatusWidgetComponent.TOR)
        if (i2pConnected) add(StatusWidgetComponent.I2P)
        if (settings.sentinelWidgetActive()) add(StatusWidgetComponent.SENTINEL)
    }

private fun Settings.rememberedWidgetLatency(profileId: Long?): Long? =
    profileId
        ?.takeIf { it > 0L }
        ?.let { id -> smartProfilePreferences.firstOrNull { preference -> preference.profileId == id } }
        ?.lastKnownGoodLatencyMs
        ?.takeIf { latency -> latency > 0L }

private fun ConnectionSnapshot.widgetVpnProfile(vpnConnected: Boolean): String? =
    profileName?.takeIf { vpnConnected && it.isNotBlank() }

private fun <T> T?.takeIfConnected(connected: Boolean): T? = takeIf { connected }

private fun resolvedTorIdentity(
    route: StatusWidgetRoute,
    vpnIpInfo: IpInfo?,
    torIpInfo: IpInfo?,
): IpInfo? =
    when {
        !route.torConnected -> null
        torIpInfo != null -> torIpInfo
        !route.vpnConnected -> vpnIpInfo
        else -> null
    }

internal fun widgetHasPrimaryConnection(snapshot: ConnectionSnapshot): Boolean =
    snapshot.state in WIDGET_ACTIVE_STATES && snapshot.profileId != LOCAL_GUARD_PROFILE_ID

private fun Settings.vpnWidgetScope(): StatusWidgetScope =
    when (expert.perAppRoutingMode) {
        PerAppRoutingMode.FULL_TUNNEL -> StatusWidgetScope.WHOLE_DEVICE
        PerAppRoutingMode.INCLUDE_SELECTED_APPS -> StatusWidgetScope.SELECTED_APPS
        PerAppRoutingMode.EXCLUDE_SELECTED_APPS ->
            if (expert.tunnelKeptOutPackages().isEmpty()) {
                StatusWidgetScope.WHOLE_DEVICE
            } else {
                StatusWidgetScope.EXCEPT_SELECTED
            }
    }

private fun Settings.torWidgetScope(): StatusWidgetScope =
    when (privacyRoute.scope) {
        PrivacyRouteScope.ALL_APPS -> StatusWidgetScope.WHOLE_DEVICE
        PrivacyRouteScope.SELECTED_APPS -> StatusWidgetScope.SELECTED_APPS
    }

private fun Settings.sentinelWidgetActive(): Boolean = anomaly.enabled

private val WIDGET_ACTIVE_STATES =
    setOf(
        ConnectionState.CONNECTING,
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
        ConnectionState.DISCONNECTING,
    )
