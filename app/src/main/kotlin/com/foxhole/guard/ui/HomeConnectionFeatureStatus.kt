package com.foxhole.guard.ui

import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.torScopeRunnable
import com.foxhole.core.runtime.i2pRuntimeActive
import com.foxhole.guard.R
import com.foxhole.guard.runtime.FoxholeVpnService
import java.net.Inet6Address
import java.net.InetAddress

// Connection-feature status resolution: which pills exist and the Tor-route predicates.
// Split from HomeScreenSupport.kt.

internal enum class HomeConnectionFeature {
    FIREWALL,
    TOR,
    I2P,
    LAN_PROXY,
}

internal enum class HomeConnectionFeatureStatus(
    val label: String,
) {
    ON("ON"),
    PENDING("PENDING"),
    OFF("OFF"),
}

internal data class HomeConnectionFeatureIndicator(
    val feature: HomeConnectionFeature,
    val titleRes: Int,
    val status: HomeConnectionFeatureStatus,
)

internal fun homeConnectionFeatureIndicators(state: HomeRouteUiState): List<HomeConnectionFeatureIndicator> =
    buildList {
        if (state.settings.ui.showFirewallStatus) {
            add(
                HomeConnectionFeatureIndicator(
                    feature = HomeConnectionFeature.FIREWALL,
                    // Short "FW" label so all four pills fit a phone row before scrolling kicks in.
                    titleRes = R.string.home_connection_feature_firewall_short,
                    status = homeFirewallFeatureStatus(state),
                ),
            )
        }
        // The core master switch OWNS the pill: with the Tor component off there is nothing to
        // quick-launch, so the pill goes with it (an engaged runtime can only exist while the core
        // is on, and then it keeps the pill visible whatever the quick-access toggle says — an
        // active Tor must stay controllable from the dashboard).
        val torPillVisible =
            state.settings.privacyRoute.permitted &&
                (
                    state.settings.ui.showTorQuickLaunch ||
                        state.settings.privacyRoute.enabled ||
                        state.hasTorOnlyRuntime() ||
                        state.torOperation.active
                    )
        if (torPillVisible) {
            add(
                HomeConnectionFeatureIndicator(
                    feature = HomeConnectionFeature.TOR,
                    titleRes = R.string.tor_badge,
                    status = homeTorFeatureStatus(state),
                ),
            )
        }
        // The I2P pill mirrors the Tor one: the i2p core switch owns it, the quick-access toggle
        // only decides whether an enabled core also shows up on the dashboard.
        if (state.settings.i2p.enabled && state.settings.ui.showI2pQuickLaunch) {
            add(
                HomeConnectionFeatureIndicator(
                    feature = HomeConnectionFeature.I2P,
                    titleRes = R.string.home_connection_feature_i2p,
                    status = homeI2pFeatureStatus(state),
                ),
            )
        }
        // The show flag OWNS the LAN proxy pill (like the firewall pill): visible once the LAN
        // proxy has been turned on, staying put when it is turned back off so it can be re-armed.
        if (state.settings.ui.showLanProxyQuickAccess) {
            add(
                HomeConnectionFeatureIndicator(
                    feature = HomeConnectionFeature.LAN_PROXY,
                    // Short "LP" label — same room-saving reason as the firewall pill.
                    titleRes = R.string.home_lan_proxy_short,
                    status =
                    if (state.settings.expert.localSurfaces.allowLanAccess) {
                        HomeConnectionFeatureStatus.ON
                    } else {
                        HomeConnectionFeatureStatus.OFF
                    },
                ),
            )
        }
    }

internal fun homeConnectionFeatureIndicator(
    feature: HomeConnectionFeature,
    state: HomeRouteUiState,
): HomeConnectionFeatureIndicator? =
    homeConnectionFeatureIndicators(state).firstOrNull { it.feature == feature }

internal fun homeFirewallFeatureStatus(state: HomeRouteUiState): HomeConnectionFeatureStatus =
    when {
        !state.settings.expert.firewallEnabled -> HomeConnectionFeatureStatus.OFF
        state.connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID -> HomeConnectionFeatureStatus.ON
        state.connection.state in ACTIVE_CONNECTION_STATES -> HomeConnectionFeatureStatus.ON
        else -> HomeConnectionFeatureStatus.PENDING
    }

/**
 * I2P rides the assembled runtime config (the i2pd child only lives inside a session): enabled
 * with a live session is ON, enabled while idle is the amber "armed" PENDING, otherwise OFF.
 */
internal fun homeI2pFeatureStatus(state: HomeRouteUiState): HomeConnectionFeatureStatus =
    when {
        !state.settings.i2pRuntimeActive() -> HomeConnectionFeatureStatus.OFF
        state.connection.state in ACTIVE_CONNECTION_STATES -> HomeConnectionFeatureStatus.ON
        else -> HomeConnectionFeatureStatus.PENDING
    }

internal fun homeTorFeatureStatus(state: HomeRouteUiState): HomeConnectionFeatureStatus =
    when {
        !state.settings.privacyRoute.enabled -> HomeConnectionFeatureStatus.OFF
        // "Attention" states keep the amber PENDING pill: the route is enabled but cannot run yet
        // because the scope is unset or the selected VPN protocol is UDP (Tor needs a TCP carrier).
        state.activeProfile == null && !homeTorRouteHasRunnableScope(state) -> HomeConnectionFeatureStatus.PENDING
        homeTorSelectedProtocolIsUdp(state) -> HomeConnectionFeatureStatus.PENDING
        state.canRunHomeTorRoute() &&
            (state.hasConfirmedTorRoute() || state.hasRuntimeEngagedTorRoute()) -> HomeConnectionFeatureStatus.ON
        // An in-flight start (active Tor operation or a session still connecting its Tor route) is
        // PENDING; anything else — including a route merely permitted by the settings switch — is
        // idle and must read as OFF (grey). The switch is permission, not engagement.
        state.homeTorRouteTransitioning() -> HomeConnectionFeatureStatus.PENDING
        else -> HomeConnectionFeatureStatus.OFF
    }

private fun HomeRouteUiState.homeTorRouteTransitioning(): Boolean =
    torOperation.active ||
        hasTorOnlyRuntime() ||
        (connection.torActive && connection.state in ACTIVE_CONNECTION_STATES)

private fun HomeRouteUiState.canRunHomeTorRoute(): Boolean =
    (
        activeProfile == null ||
            settings.privacyRoute.directTorEnabled ||
            settings.traffic.mode == TrafficMode.TUNNEL
        ) &&
        homeTorRouteHasRunnableScope(this) &&
        !homeTorSelectedProtocolIsUdp(this)

// Tor routes are validated by the runtime on Tor's own bootstrap 100% + live SOCKS for BOTH scopes
// (the exit-IP probe stays a best-effort background refresh), so an engaged CONNECTED Tor route
// reads ON — matching the green top status — instead of sitting amber PENDING for as long as the
// probe needs to land. Covers the Tor-only runtime and a VPN session that carries the Tor route.
private fun HomeRouteUiState.hasRuntimeEngagedTorRoute(): Boolean =
    connection.state == ConnectionState.CONNECTED &&
        (connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID || connection.torActive)

private fun HomeRouteUiState.hasConfirmedTorRoute(): Boolean {
    val info = torIpInfo
    val requiredFetchedAt =
        when {
            connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> connection.lastChangeAt
            else -> 0L
        }
    return connection.state in ACTIVE_CONNECTION_STATES &&
        (connection.torActive || connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID) &&
        info != null &&
        info.fetchedAt >= requiredFetchedAt &&
        info.hasVisiblePublicAddressForTorStatus()
}

private fun IpInfo.hasVisiblePublicAddressForTorStatus(): Boolean =
    listOfNotNull(ipv4, ip, ipv6)
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .any { candidate -> candidate.isPublicInternetAddressForTorStatus() }

private fun String.isPublicInternetAddressForTorStatus(): Boolean {
    val address = runCatching { InetAddress.getByName(substringBefore('%')) }.getOrNull() ?: return true
    return !address.isNonPublicLocalAddressForTorStatus() &&
        !address.isUniqueLocalIpv6AddressForTorStatus() &&
        !address.isMulticastAddress
}

private fun InetAddress.isNonPublicLocalAddressForTorStatus(): Boolean =
    isAnyLocalAddress ||
        isLoopbackAddress ||
        isLinkLocalAddress ||
        isSiteLocalAddress

private fun InetAddress.isUniqueLocalIpv6AddressForTorStatus(): Boolean =
    this is Inet6Address &&
        address.firstOrNull()?.toInt()?.let { firstByte -> (firstByte and 0xfe) == 0xfc } == true

internal fun homeTorSelectedProtocolIsUdp(state: HomeRouteUiState): Boolean {
    // A UDP tunnel (Hysteria2, WireGuard) has no TCP carrier for an in-tunnel Tor route, so Tor
    // cannot ride inside it. But that restriction only bites while a real VPN profile tunnel is
    // actually live: with the VPN idle, Tor runs on the device route regardless of which protocol
    // the profile has merely *selected*, and with bypass on Tor never enters the tunnel at all. In
    // both of those cases the selected protocol is irrelevant and must not gate the Tor route —
    // otherwise a profile whose selected option happens to be Hysteria2 blocks Tor even with no VPN
    // connected (the user had to toggle the bypass checkbox just to start device-route Tor).
    val liveVpnProfileTunnel =
        state.connection.state in ACTIVE_CONNECTION_STATES &&
            state.connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
            state.connection.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID
    if (!liveVpnProfileTunnel || state.settings.privacyRoute.bypassVpnTunnel) {
        return false
    }
    val protocolHint = state.connection.protocolHint ?: state.activeProfile?.selectedRuntimeProtocolHint()
    return protocolHint in setOf(ProtocolHint.HYSTERIA2, ProtocolHint.WIREGUARD)
}

internal fun homeTorRouteHasRunnableScope(state: HomeRouteUiState): Boolean =
    state.settings.torScopeRunnable()

internal fun homeTorOnlyStartAvailable(state: HomeRouteUiState): Boolean =
    state.activeProfile == null &&
        // A merely PERMITTED core (switch on, route not armed yet) already offers Connect TOR.
        (state.settings.privacyRoute.enabled || state.settings.privacyRoute.permitted) &&
        homeTorRouteHasRunnableScope(state)

private fun Profile.selectedRuntimeProtocolHint(): ProtocolHint? {
    val selectedId = selectedProtocolOptionId?.takeIf(String::isNotBlank)
    return selectedId
        ?.let { optionId -> protocolOptions.firstOrNull { it.id == optionId }?.protocolHint }
        ?: protocolOptions.firstOrNull()?.protocolHint
        ?: protocolHint
}
