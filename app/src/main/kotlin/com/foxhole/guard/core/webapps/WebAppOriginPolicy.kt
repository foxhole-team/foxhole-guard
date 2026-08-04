package com.foxhole.guard.core.webapps

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun isWebAppTunTransportReady(snapshot: ConnectionSnapshot): Boolean =
    snapshot.state == ConnectionState.CONNECTED && snapshot.trafficMode == TrafficMode.TUNNEL

/**
 * Allows the configured HTTPS host and its descendants, never its parent or a sibling.
 *
 * This deliberately does not guess an eTLD+1. Treating the last two labels as a site would make
 * unrelated tenants on `github.io` or `co.uk` peers. Keeping the configured host as the trust root
 * is the fail-closed rule and still permits sites that move a session to their own subdomain.
 */
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

/** HTTPS, host ancestry and effective port must all stay inside one configured web app. */
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
