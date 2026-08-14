package com.foxhole.guard.runtime

import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.launch

// VPN-first startup ordering for Tor-in-VPN sessions (BUG 1):
//
//   1. connect() builds the FIRST session config with the Tor route deferred (plain VPN), so the
//      tunnel comes up, validates, and the network widget shows the VPN identity first.
//   2. The tunnel-validated hook calls scheduleDeferredTorRouteUpgrade(), which hot-reloads the
//      session right away; the reload rebuilds the full config with the Tor route, so Tor engages
//      strictly AFTER the VPN is proven up. A failed VPN start never starts anything else.

/**
 * True when the first connect of a VPN profile must defer the in-tunnel Tor route until the
 * tunnel validates. Tor-beside-VPN (bypass) is not deferred: it neither rides the tunnel nor
 * holds the network widget's VPN identity.
 */
internal fun shouldDeferTorRouteForVpnFirstStartup(
    settings: Settings,
    torOnlyConnect: Boolean,
    trafficMode: TrafficMode,
): Boolean =
    !torOnlyConnect &&
        trafficMode == TrafficMode.TUNNEL &&
        settings.privacyRoute.enabled &&
        !settings.privacyRoute.bypassVpnTunnel

/**
 * Step 2 of the VPN-first ordering: called from the tunnel-validated hook. No-ops unless this
 * exact session deferred its Tor route; re-checks the route is still wanted and that the very
 * same session is still CONNECTED before hot-reloading it with the full config.
 */
internal fun FoxholeVpnService.scheduleDeferredTorRouteUpgrade(session: VpnSession) {
    if (pendingTorRouteUpgradeSessionId != session.correlationId) {
        // Never bail silently here. A disarmed id means the VPN comes up WITHOUT its Tor route while
        // the UI still reports VPN+TOR — the exact shape of the "local guard active ⇒ VPN+TOR is
        // really VPN-only" bug, which stayed invisible for so long precisely because this return
        // logged nothing. If the session wanted Tor deferred, say so loudly.
        if (session.configJson.isNotBlank() && pendingTorRouteUpgradeSessionId == null) {
            container.diagnosticsLogger.recordStructured(
                "connection",
                "deferred tor route upgrade not armed for validated session",
                "sessionId=${session.correlationId}",
            )
        }
        return
    }
    pendingTorRouteUpgradeSessionId = null
    scope.launch {
        val routeSettings = container.settingsRepository.settings.value.privacyRoute
        val sessionStillCurrent = activeSession?.correlationId == session.correlationId
        val torStillWantedInTunnel = routeSettings.enabled && !routeSettings.bypassVpnTunnel
        // Gate on the session still being current — this fires from the tunnel-VALIDATED event, so
        // the runtime is already up; a real drop clears activeSession. Deliberately NOT gated on the
        // bridge's CONNECTED publish: a control-plane transition (e.g. an I2P handoff) can briefly
        // fence that write as stale, and skipping on that single stale read left VPN+TOR running as
        // VPN-only. Staying event-driven keeps the no-timers ordering contract.
        if (!sessionStillCurrent || !torStillWantedInTunnel) {
            container.diagnosticsLogger.record(
                "connection",
                "deferred tor route upgrade skipped sessionId=${session.correlationId} " +
                    "current=$sessionStillCurrent tor_wanted=${routeSettings.enabled}",
            )
            return@launch
        }
        container.diagnosticsLogger.record(
            "connection",
            "vpn-first startup: engaging tor route after tunnel validation sessionId=${session.correlationId}",
        )
        container.connectionController.reload(session.profileId)
    }
}
