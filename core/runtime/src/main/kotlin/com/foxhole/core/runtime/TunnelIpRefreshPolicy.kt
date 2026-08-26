package com.foxhole.core.runtime

import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.core.runtime.network.DNS_INDEPENDENT_IP_INFO_ENDPOINT
import com.foxhole.core.runtime.network.IpInfoFetchMode

internal data class ActiveTunnelIpInfoCache(
    val key: ActiveTunnelIpInfoCacheKey,
    val info: IpInfo,
    val updatedAtMs: Long,
)

internal data class ActiveTunnelIpInfoCacheKey(
    val profileId: Long?,
    val protocolOptionId: String?,
    val connectedAtMs: Long,
    val egressRole: ActiveTunnelIpInfoEgressRole,
)

internal enum class ActiveTunnelIpInfoEgressRole { VPN, TOR }

internal fun ConnectionSnapshot.activeTunnelIpInfoCacheKey(): ActiveTunnelIpInfoCacheKey =
    ActiveTunnelIpInfoCacheKey(
        profileId = profileId,
        protocolOptionId = protocolOptionId,
        connectedAtMs = lastChangeAt,
        egressRole = activeTunnelIpInfoEgressRole(),
    )

internal fun ConnectionSnapshot.activeTunnelIpInfoEgressRole(): ActiveTunnelIpInfoEgressRole {
    val torRoute = appliedTorRoute
    val allAppsTorInsideVpn =
        trafficMode == TrafficMode.TUNNEL &&
            torActive &&
            torRoute?.scope == PrivacyRouteScope.ALL_APPS &&
            torRoute.bypassVpnTunnel.not()
    return if (profileId == TOR_ONLY_PROFILE_ID || allAppsTorInsideVpn) {
        ActiveTunnelIpInfoEgressRole.TOR
    } else {
        ActiveTunnelIpInfoEgressRole.VPN
    }
}

fun Settings.requiresStrictRuntimeProxyIpRefresh(snapshot: ConnectionSnapshot): Boolean =
    snapshot.requiresRuntimeProxyForActiveTunnelIpRefresh() ||
        snapshot.profileId == TOR_ONLY_PROFILE_ID ||
        (
            privacyRoute.permitted &&
                privacyRoute.mode == PrivacyRouteMode.TOR_OVER_VPN &&
                traffic.mode == TrafficMode.TUNNEL &&
                when (privacyRoute.scope) {
                    PrivacyRouteScope.ALL_APPS -> true
                    PrivacyRouteScope.SELECTED_APPS -> expert.torLanePackages().isNotEmpty()
                }
            )

@Suppress("ComplexCondition")
fun Settings.allowsRuntimeProxyTunnelValidation(
    snapshot: ConnectionSnapshot,
    profileId: Long? = snapshot.profileId,
): Boolean {
    if (
        snapshot.state !in ACTIVE_CONNECTION_STATES ||
        snapshot.trafficMode != TrafficMode.TUNNEL ||
        profileId == null ||
        profileId == LOCAL_GUARD_PROFILE_ID
    ) {
        return false
    }
    val includeOnlySplit = expert.runtimeHasIncludeOnlyAppSplit()
    val torOnly = profileId == TOR_ONLY_PROFILE_ID
    return includeOnlySplit || torOnly
}

private fun ExpertSettings.runtimeHasIncludeOnlyAppSplit(): Boolean =
    perAppRoutingMode == PerAppRoutingMode.INCLUDE_SELECTED_APPS &&
        (
            tunnelSelectedPackages().isNotEmpty() ||
                (blockedPackagesEnabled && blockedLanePackages().isNotEmpty())
            )

fun Settings.canUseVpnBoundIpRefreshFallback(
    snapshot: ConnectionSnapshot,
    androidValidatedVpnNetwork: Boolean,
): Boolean =
    !requiresRuntimeProxyOnlyIpRefresh(snapshot) &&
        (!requiresStrictRuntimeProxyIpRefresh(snapshot) || androidValidatedVpnNetwork)

fun Settings.canRecoverCachedActiveTunnelIpInfo(
    snapshot: ConnectionSnapshot,
    androidValidatedVpnNetwork: Boolean,
): Boolean = canUseVpnBoundIpRefreshFallback(snapshot, androidValidatedVpnNetwork)

fun Settings.shouldPreferVpnBoundIpRefresh(
    snapshot: ConnectionSnapshot,
    androidValidatedVpnNetwork: Boolean,
): Boolean =
    androidValidatedVpnNetwork &&
        canUseVpnBoundIpRefreshFallback(snapshot, androidValidatedVpnNetwork = true) &&
        snapshot.profileId != TOR_ONLY_PROFILE_ID

private fun Settings.requiresRuntimeProxyOnlyIpRefresh(snapshot: ConnectionSnapshot): Boolean =
    allowsRuntimeProxyTunnelValidation(snapshot)

fun validatedTunnelIpRefreshFetchMode(
    requestedMode: IpInfoFetchMode,
    androidValidatedVpnNetwork: Boolean,
): IpInfoFetchMode =
    if (androidValidatedVpnNetwork && requestedMode == IpInfoFetchMode.FULL) {
        IpInfoFetchMode.ENTRY_QUICK
    } else {
        requestedMode
    }

fun activeTunnelIpRefreshEndpoint(
    configuredEndpoint: String,
    androidValidatedVpnNetwork: Boolean,
): String {
    if (!androidValidatedVpnNetwork) {
        return configuredEndpoint
    }
    val normalized = configuredEndpoint.trim().ifBlank { BuildConfig.DEFAULT_IP_INFO_ENDPOINT }
    return if (normalized.equals(BuildConfig.DEFAULT_IP_INFO_ENDPOINT, ignoreCase = true)) {
        DNS_INDEPENDENT_IP_INFO_ENDPOINT
    } else {
        configuredEndpoint
    }
}

fun Settings.shouldPublishRuntimeProxyIpInfoToDashboard(snapshot: ConnectionSnapshot): Boolean =
    !snapshot.shouldHoldRuntimeProxyIpInfoForTorOverVpn()

private fun ConnectionSnapshot.shouldHoldRuntimeProxyIpInfoForTorOverVpn(): Boolean {
    val torRoute = appliedTorRoute ?: return false
    return torActive &&
        state in ACTIVE_CONNECTION_STATES &&
        trafficMode == TrafficMode.TUNNEL &&
        profileId != null &&
        profileId != LOCAL_GUARD_PROFILE_ID &&
        profileId != TOR_ONLY_PROFILE_ID &&
        when (torRoute.scope) {
            PrivacyRouteScope.ALL_APPS -> true

            PrivacyRouteScope.SELECTED_APPS -> false
        }
}

private fun ConnectionSnapshot.requiresRuntimeProxyForActiveTunnelIpRefresh(): Boolean =
    state in ACTIVE_CONNECTION_STATES &&
        trafficMode == TrafficMode.TUNNEL &&
        profileId != LOCAL_GUARD_PROFILE_ID

internal fun deviceIpRefreshRequiresExplicitUpstreamNetwork(
    trafficMode: TrafficMode,
    vpnNetworkPresent: Boolean,
    sessionEngaged: Boolean,
    localGuardRuntimeActive: Boolean,
    localGuardAppExcluded: Boolean,
): Boolean =
    trafficMode == TrafficMode.TUNNEL &&
        !localGuardAppExcluded &&
        (vpnNetworkPresent || sessionEngaged || localGuardRuntimeActive)
