package com.foxhole.guard.core.webapps

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.WebAppRoute
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun isWebAppTunTransportReady(snapshot: ConnectionSnapshot): Boolean =
    snapshot.state == ConnectionState.CONNECTED && snapshot.trafficMode == TrafficMode.TUNNEL

internal fun webAppRouteSatisfied(isolationEnabled: Boolean, tunnelReady: Boolean): Boolean =
    !isolationEnabled || tunnelReady

internal val WEB_APP_ROUTE_OPTIONS = listOf(
    WebAppRoute.DEFAULT,
    WebAppRoute.DIRECT,
    WebAppRoute.VPN,
    WebAppRoute.BLOCK,
)

internal fun webAppRouteAvailable(route: WebAppRoute): Boolean = route in WEB_APP_ROUTE_OPTIONS

/** Per-app route readiness. Strict routes never degrade to the current/default network. */
internal fun webAppRouteSatisfied(
    route: WebAppRoute,
    blockWithoutTunnel: Boolean,
    snapshot: ConnectionSnapshot,
    i2pReady: Boolean,
): Boolean =
    webAppRouteAvailable(route) && when (route) {
        WebAppRoute.DEFAULT ->
            webAppRouteSatisfied(
                isolationEnabled = blockWithoutTunnel,
                tunnelReady = isWebAppTunTransportReady(snapshot),
            )
        WebAppRoute.DIRECT -> true
        WebAppRoute.VPN ->
            snapshot.state == ConnectionState.CONNECTED &&
                snapshot.profileId?.let { profileId -> profileId > 0L } == true &&

                snapshot.protocolHint != ProtocolHint.WIREGUARD
        WebAppRoute.TOR -> snapshot.state == ConnectionState.CONNECTED && snapshot.torActive
        WebAppRoute.I2P -> snapshot.state == ConnectionState.CONNECTED && i2pReady
        WebAppRoute.BLOCK -> false
    }

internal fun sameWebAppSite(
    appHost: String,
    candidateHost: String,
): Boolean {
    val root = appHost.trim().trimEnd('.').lowercase()
    val candidate = candidateHost.trim().trimEnd('.').lowercase()
    return root.isNotEmpty() &&
        candidate.isNotEmpty() &&
        (candidate == root || candidate.endsWith(".$root"))
}

internal fun isAllowedWebAppUrl(
    appUrl: String,
    candidateUrl: String?,
): Boolean {
    val app = appUrl.toHttpUrlOrNull() ?: return false
    val candidate = candidateUrl?.toHttpUrlOrNull() ?: return false
    return app.scheme == "https" &&
        candidate.scheme == "https" &&
        candidate.port == app.port &&
        sameWebAppSite(app.host, candidate.host)
}
