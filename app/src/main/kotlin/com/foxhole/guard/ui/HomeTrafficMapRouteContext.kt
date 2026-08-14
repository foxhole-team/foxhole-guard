package com.foxhole.guard.ui

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficMapI2pCarrier
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.excludedLanePackages
import com.foxhole.core.model.networkUp
import com.foxhole.core.model.packages
import com.foxhole.core.runtime.i2pRaisesLocalGuard
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
    val appRoute =
        coreState.settings.trafficMapAppRouteProjection(
            torRouteActive = tor.torRouteActive,
            torOnlyRuntime = tor.torOnlyRuntimeActive,
        )
    val i2pCarrier = trafficMapI2pCarrier(coreState.connection, coreState.i2pPhase.phase)
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
        // Both facts are live: the router confirmed its carrier and the snapshot names its owner.
        // A stored I2P toggle can no longer draw a success-shaped route lane.
        i2pActive = i2pCarrier != null,
        i2pCarrier = i2pCarrier,
        torAppPackages = appRoute.torApps,
        proxyModeActive = coreState.trafficMapProxyModeActive(),
        splitTunnelActive = appRoute.directBranch,
        splitAppPackages = appRoute.vpnApps,
        directAppPackages = appRoute.directApps,
        lanProxyEnabled = coreState.settings.expert.localSurfaces.allowLanAccess,
        networkJournalEnabled = coreState.settings.expert.networkActivityLogging,
    )
}

/** Projects only live runtime facts; settings are deliberately not part of this decision. */
internal fun trafficMapI2pCarrier(
    connection: ConnectionSnapshot,
    i2pPhase: I2pNetworkPhase,
): TrafficMapI2pCarrier? {
    if (!i2pPhase.networkUp || connection.state !in com.foxhole.core.model.ACTIVE_CONNECTION_STATES) {
        return null
    }
    val profileId = connection.profileId
    return when (profileId) {
        LOCAL_GUARD_PROFILE_ID -> TrafficMapI2pCarrier.DEVICE
        TOR_ONLY_PROFILE_ID -> TrafficMapI2pCarrier.TOR
        null -> null
        // The Android carrier belongs to the positive-id VPN runtime even when that profile routes
        // its egress through Tor. `torActive` describes a hop inside the profile, not ownership of
        // the carrier TUN; only the standalone TOR_ONLY runtime owns the orange carrier.
        else -> TrafficMapI2pCarrier.VPN.takeIf { profileId > 0L }
    }
}

internal data class TrafficMapAppRouteProjection(
    val buildable: Boolean = true,
    val vpnApps: List<String> = emptyList(),
    val torApps: List<String> = emptyList(),
    val directApps: List<String> = emptyList(),
    /** The direct side also contains every package not explicitly named by the include split. */
    val directRemainder: Boolean = false,
    /** Whole captured TUN traffic exits through Tor; explicit direct exceptions still stay out. */
    val torAllApps: Boolean = false,
) {
    val directBranch: Boolean
        get() = directRemainder || directApps.isNotEmpty()
}

private data class TrafficMapAssignedPackages(
    val vpn: List<String>,
    val tor: List<String>,
    val blocked: List<String>,
    val excluded: List<String>,
)

/**
 * The app membership the map is allowed to claim, projected from the same TUN policy as
 * `RuntimeTunInbound.buildSplitPlan`.
 *
 * The diagram names explicit app rules, not every package implicitly carried by Android's TUN.
 * A TOR-pinned app therefore belongs only to a live Tor lane; while Tor is absent it is omitted
 * instead of being relabelled as a VPN/direct app. The inverse holds for VPN rules in a Tor-only
 * runtime. This keeps the two saved rule classes visually disjoint in every single-route mode.
 */
internal fun Settings.trafficMapAppRouteProjection(
    torRouteActive: Boolean,
    torOnlyRuntime: Boolean = false,
): TrafficMapAppRouteProjection {
    if (!torOnlyRuntime && traffic.mode != TrafficMode.TUNNEL) {
        return TrafficMapAppRouteProjection()
    }
    val assigned = trafficMapAssignedPackages()
    if (torOnlyRuntime) return torOnlyTrafficMapProjection(assigned, torRouteActive)
    return vpnTrafficMapProjection(assigned, torRouteActive)
}

private fun Settings.trafficMapAssignedPackages(): TrafficMapAssignedPackages =
    TrafficMapAssignedPackages(
        vpn = expert.packages(AppTunnelLane.VPN).normalizedRoutePackages(),
        tor = expert.packages(AppTunnelLane.TOR).normalizedRoutePackages(),
        blocked = expert.blockedLanePackages()
            .takeIf { expert.blockedPackagesEnabled }
            .orEmpty()
            .normalizedRoutePackages(),
        excluded = expert.excludedLanePackages().normalizedRoutePackages(),
    )

private fun Settings.torOnlyTrafficMapProjection(
    assigned: TrafficMapAssignedPackages,
    torRouteActive: Boolean,
): TrafficMapAppRouteProjection {
    if (!torRouteActive) return TrafficMapAppRouteProjection()
    return when (privacyRoute.scope) {
        PrivacyRouteScope.ALL_APPS -> TrafficMapAppRouteProjection(torAllApps = true)
        PrivacyRouteScope.SELECTED_APPS ->
            TrafficMapAppRouteProjection(
                buildable = assigned.tor.isNotEmpty(),
                torApps = assigned.tor,
                directApps = assigned.excluded,
                directRemainder = assigned.tor.isNotEmpty(),
            )
    }
}

private fun Settings.vpnTrafficMapProjection(
    assigned: TrafficMapAssignedPackages,
    torRouteActive: Boolean,
): TrafficMapAppRouteProjection {
    val included = routeIncludedPackages(assigned)
    val torSelected = selectedTorRoutePackages(assigned, torRouteActive)
    val failClosedTor = failClosedTorRoutePackages(assigned, torRouteActive)
    val excluded = routeExcludedPackages(assigned, included, torSelected, failClosedTor)
    val buildable = torRouteSelectionBuildable(torRouteActive, included)
    return TrafficMapAppRouteProjection(
        buildable = buildable,
        vpnApps = vpnLanePackages(assigned, included, torSelected, failClosedTor),
        torApps = torSelected,
        // INCLUDE_ONLY has no explicit Android exclude list because every package outside the
        // include set is direct by definition; keep the user's E lane visible inside that rest.
        directApps = (if (included.isNotEmpty()) assigned.excluded else excluded)
            .withoutInactiveTorRules(assigned, torRouteActive),
        directRemainder = included.isNotEmpty(),
        torAllApps = buildable && torRouteActive && privacyRoute.scope == PrivacyRouteScope.ALL_APPS,
    )
}

private fun Settings.routeIncludedPackages(assigned: TrafficMapAssignedPackages): List<String> =
    if (expert.perAppRoutingMode == PerAppRoutingMode.INCLUDE_SELECTED_APPS) {
        (assigned.vpn + assigned.tor + assigned.blocked).normalizedRoutePackages()
    } else {
        emptyList()
    }

private fun Settings.routeBaseExcludedPackages(assigned: TrafficMapAssignedPackages): List<String> =
    if (expert.perAppRoutingMode == PerAppRoutingMode.EXCLUDE_SELECTED_APPS) {
        (assigned.vpn + assigned.tor + assigned.excluded).normalizedRoutePackages()
    } else {
        assigned.excluded
    }

private fun Settings.selectedTorRoutePackages(
    assigned: TrafficMapAssignedPackages,
    torRouteActive: Boolean,
): List<String> =
    assigned.tor.takeIf {
        torRouteActive && privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS
    }.orEmpty()

private fun Settings.failClosedTorRoutePackages(
    assigned: TrafficMapAssignedPackages,
    torRouteActive: Boolean,
): List<String> =
    assigned.tor.takeIf {
        !torRouteActive && privacyRoute.blockAppsWhenTorUnavailable
    }.orEmpty()

private fun Settings.routeExcludedPackages(
    assigned: TrafficMapAssignedPackages,
    included: List<String>,
    torSelected: List<String>,
    failClosedTor: List<String>,
): List<String> {
    if (included.isNotEmpty()) return emptyList()
    return routeBaseExcludedPackages(assigned).filterNot { packageName ->
        packageName in torSelected || packageName in failClosedTor
    }
}

private fun Settings.torRouteSelectionBuildable(
    torRouteActive: Boolean,
    included: List<String>,
): Boolean =
    !(torRouteActive && privacyRoute.scope == PrivacyRouteScope.ALL_APPS && included.isNotEmpty())

private fun vpnLanePackages(
    assigned: TrafficMapAssignedPackages,
    included: List<String>,
    torSelected: List<String>,
    failClosedTor: List<String>,
): List<String> {
    if (included.isEmpty()) return emptyList()
    val unavailableTor = (torSelected + failClosedTor).toSet()
    return assigned.vpn
        .filterNot(unavailableTor::contains)
        .normalizedRoutePackages()
}

private fun List<String>.withoutInactiveTorRules(
    assigned: TrafficMapAssignedPackages,
    torRouteActive: Boolean,
): List<String> =
    if (torRouteActive) {
        this
    } else {
        filterNot(assigned.tor.toSet()::contains).normalizedRoutePackages()
    }

private fun List<String>.normalizedRoutePackages(): List<String> =
    asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()
        .toList()

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
    val torOnlyRuntimeActive: Boolean,
    val torOwnsTunnelProbes: Boolean,
    val torRouteActive: Boolean,
)

private fun HomeUiState.trafficMapTorContext(): TrafficMapTorContext {
    val activeState = connection.state in com.foxhole.core.model.ACTIVE_CONNECTION_STATES
    val firewallActive =
        connection.profileId == com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID && activeState
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
