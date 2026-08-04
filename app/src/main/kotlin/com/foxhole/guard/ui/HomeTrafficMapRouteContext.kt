package com.foxhole.guard.ui

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.packages
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.core.runtime.i2pRaisesLocalGuard
import com.foxhole.core.runtime.i2pRuntimeActive
import com.foxhole.core.runtime.shouldPublishRuntimeProxyIpInfoToDashboard

// The traffic-map route context: split-tunnel/Tor projections shared by map states.
// Split from HomeRouteStateProducer.kt.

internal fun TrafficMapUiState.withTrafficMapRouteContext(
    coreState: HomeUiState,
    serverPings: Map<ProfileOptionLatencyKey, ProfileOptionServerPingState>,
    tunnelPings: Map<ProfileOptionLatencyKey, ProfileOptionTunnelPingState>,
): TrafficMapUiState {
    val badge = coreState.trafficMapProtocolBadge()
    val route = vpnRoute
    val withBadge =
        if (badge == null || route == null) {
            this
        } else {
            copy(vpnRoute = route.copy(protocolBadge = badge))
        }
    // The exit-arrow latency mirrors the dashboard's "server ping": the live ping of the active
    // profile's selected protocol option. UDP protocols (Hysteria2, WireGuard) have no TCP server
    // ping, so the tunnel latency probe fills in — otherwise the map showed no latency at all.
    val serverPing = selectedOptionMetricMs(coreState, serverPings) { state -> state.pingMs }
    val tunnelPing = selectedOptionMetricMs(coreState, tunnelPings) { state -> state.pingMs }
    val latency = serverPing ?: tunnelPing
    val tor = coreState.trafficMapTorContext()
    return withBadge.copy(
        exitLatencyMs = if (tor.torOwnsTunnelProbes) serverPing else latency,
        torExitLatencyMs =
        when {
            tor.torOnlyRuntimeActive -> latency
            tor.torOwnsTunnelProbes -> tunnelPing
            else -> null
        },
        torBypassesVpn = coreState.settings.privacyRoute.directTorEnabled,
        torRouteActive = tor.torRouteActive,
        firewallActive = tor.firewallActive,
        firewallTransparent = tor.firewallActive && coreState.settings.i2pRaisesLocalGuard(),
        // I2P is "on the route" once it is engaged and any carrier TUN is live: a profile,
        // Tor-only, or the transparent firewall guard raised by I2P itself.
        i2pActive =
        coreState.settings.i2pRuntimeActive() &&
            coreState.connection.state in com.foxhole.core.model.ACTIVE_CONNECTION_STATES,
        torAppPackages = tor.torAppPackages,
        proxyModeActive = coreState.trafficMapProxyModeActive(),
        splitTunnelActive = coreState.trafficMapSplitTunnelActive(),
        splitAppPackages = coreState.trafficMapSplitAppPackages(),
        directAppPackages = coreState.trafficMapDirectAppPackages(),
        lanProxyEnabled = coreState.settings.expert.localSurfaces.allowLanAccess,
        networkJournalEnabled = coreState.settings.expert.networkActivityLogging,
    )
}

// A per-app split tunnel: only part of the device rides the VPN, the rest stays direct — the
// route scheme forks the device into a VPN lane and a direct lane.
private fun HomeUiState.trafficMapSplitTunnelActive(): Boolean =
    settings.traffic.mode == TrafficMode.TUNNEL &&
        settings.expert.perAppRoutingMode != PerAppRoutingMode.FULL_TUNNEL &&
        settings.expert.tunnelSelectedPackages().isNotEmpty()

// Icons under the scheme's split node: only include-mode picks ride the VPN lane; in exclude
// mode the picked apps are the direct side, so the node shows no app row.
private fun HomeUiState.trafficMapSplitAppPackages(): List<String> =
    if (trafficMapSplitTunnelActive() &&
        settings.expert.perAppRoutingMode == PerAppRoutingMode.INCLUDE_SELECTED_APPS
    ) {
        settings.expert.tunnelSelectedPackages().filter(String::isNotBlank)
    } else {
        emptyList()
    }

// Icons embedded in the scheme's dashed DIRECT branch: exclude-mode picks leave the tunnel, so
// the direct arrow carries their chip; include mode has no enumerable direct side.
private fun HomeUiState.trafficMapDirectAppPackages(): List<String> =
    if (trafficMapSplitTunnelActive() &&
        settings.expert.perAppRoutingMode == PerAppRoutingMode.EXCLUDE_SELECTED_APPS
    ) {
        settings.expert.tunnelSelectedPackages().filter(String::isNotBlank)
    } else {
        emptyList()
    }

// The scheme's proxy branch: a live primary-profile runtime serving as a LOCAL PROXY (no TUN) —
// device traffic exits directly, proxy-configured clients ride the profile.
private fun HomeUiState.trafficMapProxyModeActive(): Boolean =
    settings.traffic.mode == com.foxhole.core.model.TrafficMode.PROXY &&
        connection.state in com.foxhole.core.model.ACTIVE_CONNECTION_STATES &&
        connection.profileId != com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID &&
        connection.profileId != com.foxhole.core.model.TOR_ONLY_PROFILE_ID

private fun <T> selectedOptionMetricMs(
    coreState: HomeUiState,
    metrics: Map<ProfileOptionLatencyKey, T>,
    metricMs: (T) -> Long?,
): Long? {
    val activeProfileId = coreState.activeProfile?.id ?: return null
    val optionId =
        resolveDashboardLatencyOptionId(coreState.activeProfile, coreState.connection) ?: return null
    return metrics.entries
        .firstOrNull { (key, _) -> key.profileId == activeProfileId && key.optionId == optionId }
        ?.value
        ?.let(metricMs)
}

private data class TrafficMapTorContext(
    val firewallActive: Boolean,
    val torAppPackages: List<String>,
    val torOnlyRuntimeActive: Boolean,
    val torOwnsTunnelProbes: Boolean,
    val torRouteActive: Boolean,
)

private fun HomeUiState.trafficMapTorContext(): TrafficMapTorContext {
    val activeState = connection.state in com.foxhole.core.model.ACTIVE_CONNECTION_STATES
    val firewallActive =
        connection.profileId == com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID && activeState
    val privacyRoute = settings.privacyRoute
    val torAppPackages =
        if (privacyRoute.scope == com.foxhole.core.model.PrivacyRouteScope.SELECTED_APPS) {
            settings.expert.packages(com.foxhole.core.model.AppTunnelLane.TOR)
        } else {
            emptyList()
        }
    val torOnlyRuntimeActive =
        connection.profileId == com.foxhole.core.model.TOR_ONLY_PROFILE_ID && activeState
    // With the Tor route owning the whole tunnel egress (ALL_APPS scope), every tunnel-bound
    // public probe exits through the Tor circuit — the dashboard "tunnel ping" IS the Tor latency
    // and must not be shown on the VPN lane. This mirrors the identity split used for IP infos
    // (shouldPublishRuntimeProxyIpInfoToDashboard): Tor latency is available exactly when the Tor
    // exit country is. With SELECTED_APPS scope no probe of ours rides Tor, so the lane stays
    // unlabeled instead of lying with a VPN figure.
    val torOwnsTunnelProbes =
        connection.torActive &&
            activeState &&
            !settings.shouldPublishRuntimeProxyIpInfoToDashboard(connection)
    return TrafficMapTorContext(
        firewallActive = firewallActive,
        torAppPackages = torAppPackages,
        torOnlyRuntimeActive = torOnlyRuntimeActive,
        torOwnsTunnelProbes = torOwnsTunnelProbes,
        // The map draws the Tor lane only for an actually engaged Tor: the Tor-only runtime or a
        // live session whose applied config carries the route. The settings switch merely permits
        // Tor — a permitted-but-idle route must render as an ordinary direct/VPN scheme.
        torRouteActive = torOnlyRuntimeActive || (connection.torActive && activeState),
    )
}

private fun HomeUiState.trafficMapProtocolBadge(): String? {
    val protocol =
        connection.protocolHint
            ?: activeProfile
                ?.protocolOptionOrDefault(connection.protocolOptionId)
                ?.protocolHint
            ?: activeProfile?.protocolHint
    return when {
        protocol == null -> null
        protocol == ProtocolHint.HYSTERIA2 || protocol == ProtocolHint.WIREGUARD -> "UDP"
        else -> "TCP"
    }
}

// The Tor/I2P/Statistics cores are permission gates: a disabled core hides its settings entry
// outright (and, for Tor/I2P, renames the routing row to whatever is left standing).
internal fun Settings.toSettingsHomeNavUiState(): SettingsHomeNavUiState =
    SettingsHomeNavUiState(
        expertVisible = ui.showExpertSettings,
        torEnabled = privacyRoute.permitted,
        i2pEnabled = i2p.enabled,
        torFirst =
        privacyRouteTorFirst(
            torEnabled = privacyRoute.permitted,
            i2pEnabled = i2p.enabled,
            torEnabledAtMs = ui.torEnabledAtMs,
            i2pEnabledAtMs = ui.i2pEnabledAtMs,
        ),
        statisticsEnabled = statistics.componentVisible,
    )

internal data class TrafficMapOriginSelection(
    val connection: com.foxhole.core.model.ConnectionSnapshot,
    val routeIpInfo: IpInfo?,
    val candidate: IpInfo?,
    val protocolSearchRunning: Boolean,
)
