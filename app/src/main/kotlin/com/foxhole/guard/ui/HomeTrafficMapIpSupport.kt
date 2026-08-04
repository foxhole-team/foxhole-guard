package com.foxhole.guard.ui
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.runtime.FoxholeVpnService

// Traffic-map IP identity candidates: which fetched IpInfo may appear as the map's origin, VPN
// route and Tor exit nodes. Extracted from HomeDashboardRouteIpSupport (file split by domain).

internal fun trafficMapOriginIpInfoCandidate(
    connection: ConnectionSnapshot,
    deviceIpInfo: IpInfo?,
    ipInfo: IpInfo?,
): IpInfo? =
    deviceIpInfo?.takeIf { info ->
        info.hasVisiblePublicAddress() &&
            !connection.shouldRejectTrafficMapOriginAsRouteIp(deviceIpInfo = info, routeIpInfo = ipInfo)
    }

internal fun trafficMapRouteIpInfoCandidate(
    connection: ConnectionSnapshot,
    ipInfo: IpInfo?,
): IpInfo? =
    ipInfo?.takeIf { info ->
        connection.isActiveTrafficMapRouteTunnel() &&
            info.hasVisiblePublicAddress() &&
            info.countryCode?.isNotBlank() == true
    }

internal fun trafficMapTorIpInfoCandidate(
    connection: ConnectionSnapshot,
    torIpInfo: IpInfo?,
): IpInfo? {
    // Tor counts as active for the map only when it is actually engaged: a live session whose
    // applied config carries the Tor route, or the dedicated Tor-only runtime. The settings switch
    // is permission, not engagement — a permitted-but-idle route must not draw a Tor lane, and a
    // cached exit IP left over from a stopped session must never resurrect one after Stop.
    val routeTorActive =
        connection.state in ACTIVE_CONNECTION_STATES &&
            (
                connection.torActive ||
                    connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
                )
    val routeIpInfo = torIpInfo
    return routeIpInfo?.takeIf { info ->
        routeTorActive &&
            info.hasVisiblePublicAddress() &&
            info.countryCode?.isNotBlank() == true
    }
}

internal fun shouldRetainTrafficMapOriginIpInfo(
    connection: ConnectionSnapshot,
    previousOriginIpInfo: IpInfo?,
    candidateOriginIpInfo: IpInfo?,
    routeIpInfo: IpInfo?,
): Boolean {
    val previous = previousOriginIpInfo
    val previousIp = previous?.let(::primaryVisibleIpOrNull)
    val routeIp = routeIpInfo?.let(::primaryVisibleIpOrNull)
    return when {
        previous == null -> false
        candidateOriginIpInfo != null -> false
        !previous.hasVisiblePublicAddress() -> false
        !connection.isActiveTrafficMapRouteTunnel() -> false
        connection.lastChangeAt <= 0L -> false
        previous.fetchedAt >= connection.lastChangeAt -> false
        previousIp == null -> false
        else -> routeIp == null || previousIp != routeIp
    }
}

private fun ConnectionSnapshot.shouldRejectTrafficMapOriginAsRouteIp(
    deviceIpInfo: IpInfo,
    routeIpInfo: IpInfo?,
): Boolean {
    val routeIp = routeIpInfo?.let(::primaryVisibleIpOrNull)
    val deviceIp = primaryVisibleIpOrNull(deviceIpInfo)
    return isActiveTrafficMapRouteTunnel() &&
        routeIp != null &&
        deviceIp != null &&
        deviceIp == routeIp
}

// A CONNECTED profile runtime in EITHER mode: the tunnel's exit IS the dashboard identity, and a
// local-proxy runtime publishes its exit the same way (PROXY geo refresh). Excluding PROXY here
// left the proxy scheme's "Internet" node without the exit flag/country and the map without the
// server route point whenever the profile ran as a local proxy.
private fun ConnectionSnapshot.isActiveTrafficMapRouteTunnel(): Boolean =
    state == ConnectionState.CONNECTED &&
        (trafficMode == TrafficMode.TUNNEL || trafficMode == TrafficMode.PROXY) &&
        profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID
