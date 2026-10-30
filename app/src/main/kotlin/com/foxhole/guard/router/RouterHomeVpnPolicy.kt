package com.foxhole.guard.router

internal fun routerHomeVpnManagementAllowed(pairingMatches: Boolean, pairingActive: Boolean,
    managementEnabled: Boolean, publicIpConfirmed: Boolean, optedIn: Boolean): Boolean =
    pairingMatches && pairingActive && managementEnabled && publicIpConfirmed && optedIn

internal data class RouterHomeVpnRoute(val prefixLength: Int, val unicast: Boolean)

internal fun routerHomeVpnRouteUsable(matchingRoutes: List<RouterHomeVpnRoute>): Boolean {
    if (matchingRoutes.isEmpty() || matchingRoutes.any { it.prefixLength !in 0..32 }) return false
    val longest = matchingRoutes.maxOf { it.prefixLength }
    return matchingRoutes.filter { it.prefixLength == longest }.all { it.unicast }
}
