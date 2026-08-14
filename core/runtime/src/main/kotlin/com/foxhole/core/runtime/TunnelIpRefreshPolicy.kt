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

// The tunnel-aware IP-refresh policy: which fetch mode/endpoint a validated tunnel uses
// and when the dashboard may trust it. Split from TunnelValidationGateway.kt.

internal data class ActiveTunnelIpInfoCache(
    val key: ActiveTunnelIpInfoCacheKey,
    val info: IpInfo,
    val updatedAtMs: Long,
)

internal data class ActiveTunnelIpInfoCacheKey(
    val profileId: Long?,
    val protocolOptionId: String?,
    val connectedAtMs: Long,
)

internal fun ConnectionSnapshot.activeTunnelIpInfoCacheKey(): ActiveTunnelIpInfoCacheKey =
    ActiveTunnelIpInfoCacheKey(
        profileId = profileId,
        protocolOptionId = protocolOptionId,
        connectedAtMs = lastChangeAt,
    )

fun Settings.requiresStrictRuntimeProxyIpRefresh(snapshot: ConnectionSnapshot): Boolean =
    snapshot.requiresRuntimeProxyForActiveTunnelIpRefresh() ||
        snapshot.profileId == TOR_ONLY_PROFILE_ID ||
        (
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
    !shouldHoldRuntimeProxyIpInfoForTorOverVpn(snapshot)

private fun Settings.shouldHoldRuntimeProxyIpInfoForTorOverVpn(snapshot: ConnectionSnapshot): Boolean =
    privacyRoute.mode == PrivacyRouteMode.TOR_OVER_VPN &&
        snapshot.state in ACTIVE_CONNECTION_STATES &&
        snapshot.trafficMode == TrafficMode.TUNNEL &&
        snapshot.profileId != null &&
        snapshot.profileId != LOCAL_GUARD_PROFILE_ID &&
        snapshot.profileId != TOR_ONLY_PROFILE_ID &&
        when (privacyRoute.scope) {
            // All traffic rides Tor: any tunnel-bound probe returns the Tor exit, so it must never
            // be shown as the VPN identity on the dashboard (it goes to the Tor channel instead).
            PrivacyRouteScope.ALL_APPS -> true
            // Only the selected apps ride Tor: the app's own (uid-less runtime proxy / app-uid
            // bound) probes egress through the plain VPN outbound and return the real VPN
            // identity. Holding them starved the dashboard network card AND mislabelled the VPN
            // egress as a "Tor exit" in the Tor window — publish them as ordinary VPN identity.
            PrivacyRouteScope.SELECTED_APPS -> false
        }

private fun ConnectionSnapshot.requiresRuntimeProxyForActiveTunnelIpRefresh(): Boolean =
    state in ACTIVE_CONNECTION_STATES &&
        trafficMode == TrafficMode.TUNNEL &&
        profileId != LOCAL_GUARD_PROFILE_ID

/**
 * Whether a *device* (upstream-truth) IP refresh must be bound to the upstream network instead of
 * riding the process default network. The default network is our own tun whenever the runtime has a
 * VPN network up — including the connect/stop window where the tun exists but the control-plane
 * snapshot hasn't flipped yet — and an unbound fetch there returns the tunnel/Tor egress, which then
 * poisons deviceIpInfo and everything latched from it (traffic-map origin pin).
 */
internal fun deviceIpRefreshMustBypassRuntimeTunnel(
    trafficMode: TrafficMode,
    vpnNetworkPresent: Boolean,
    sessionEngaged: Boolean,
    localGuardRuntimeActive: Boolean,
): Boolean =
    trafficMode == TrafficMode.TUNNEL &&
        (vpnNetworkPresent || sessionEngaged || localGuardRuntimeActive)
