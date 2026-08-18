package com.foxhole.guard.ui

import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.serving
import com.foxhole.core.model.torScopeRunnable
import com.foxhole.core.runtime.i2pRuntimeActive
import com.foxhole.guard.R
import com.foxhole.guard.runtime.FoxholeVpnService
import java.net.Inet6Address
import java.net.InetAddress

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
                    titleRes = R.string.home_connection_feature_firewall_short,
                    status = homeFirewallFeatureStatus(state),
                ),
            )
        }
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
        if (state.settings.i2p.enabled && state.settings.ui.showI2pQuickLaunch) {
            add(
                HomeConnectionFeatureIndicator(
                    feature = HomeConnectionFeature.I2P,
                    titleRes = R.string.home_connection_feature_i2p,
                    status = homeI2pFeatureStatus(state),
                ),
            )
        }
        if (state.settings.ui.showLanProxyQuickAccess) {
            add(
                HomeConnectionFeatureIndicator(
                    feature = HomeConnectionFeature.LAN_PROXY,
                    titleRes = R.string.home_lan_proxy_short,
                    status = homeLanProxyFeatureStatus(state),
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

internal fun homeLanProxyFeatureStatus(state: HomeRouteUiState): HomeConnectionFeatureStatus =
    when {
        !state.settings.expert.localSurfaces.allowLanAccess -> HomeConnectionFeatureStatus.OFF
        state.lanProxy.phase.serving -> HomeConnectionFeatureStatus.ON
        else -> HomeConnectionFeatureStatus.PENDING
    }

internal fun homeI2pFeatureStatus(state: HomeRouteUiState): HomeConnectionFeatureStatus =
    when {
        !state.settings.i2pRuntimeActive() -> HomeConnectionFeatureStatus.OFF
        state.connection.state in ACTIVE_CONNECTION_STATES -> HomeConnectionFeatureStatus.ON
        else -> HomeConnectionFeatureStatus.PENDING
    }

internal fun homeTorFeatureStatus(state: HomeRouteUiState): HomeConnectionFeatureStatus =
    when {
        !state.settings.privacyRoute.enabled -> HomeConnectionFeatureStatus.OFF
        state.activeProfile == null && !homeTorRouteHasRunnableScope(state) -> HomeConnectionFeatureStatus.PENDING
        homeTorSelectedProtocolIsUdp(state) -> HomeConnectionFeatureStatus.PENDING
        state.canRunHomeTorRoute() &&
            (state.hasConfirmedTorRoute() || state.hasRuntimeEngagedTorRoute()) -> HomeConnectionFeatureStatus.ON
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
        (state.settings.privacyRoute.enabled || state.settings.privacyRoute.permitted) &&
        homeTorRouteHasRunnableScope(state)

private fun Profile.selectedRuntimeProtocolHint(): ProtocolHint? {
    val selectedId = selectedProtocolOptionId?.takeIf(String::isNotBlank)
    return selectedId
        ?.let { optionId -> protocolOptions.firstOrNull { it.id == optionId }?.protocolHint }
        ?: protocolOptions.firstOrNull()?.protocolHint
        ?: protocolHint
}
