package com.foxhole.guard.ui

import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.runtime.FoxholeVpnService

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

private fun ConnectionSnapshot.isActiveTrafficMapRouteTunnel(): Boolean =
    state == ConnectionState.CONNECTED &&
        (trafficMode == TrafficMode.TUNNEL || trafficMode == TrafficMode.PROXY) &&
        profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID
