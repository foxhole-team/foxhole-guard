package com.foxhole.guard.runtime

import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.launch

internal fun shouldDeferTorRouteForVpnFirstStartup(
    settings: Settings,
    torOnlyConnect: Boolean,
    trafficMode: TrafficMode,
): Boolean =
    !torOnlyConnect &&
        trafficMode == TrafficMode.TUNNEL &&
        settings.privacyRoute.permitted &&
        settings.privacyRoute.enabled &&
        !settings.privacyRoute.bypassVpnTunnel

internal fun FoxholeVpnService.scheduleDeferredTorRouteUpgrade(session: VpnSession) {
    if (pendingTorRouteUpgradeSessionId != session.correlationId) {
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
        val torStillWantedInTunnel =
            routeSettings.permitted && routeSettings.enabled && !routeSettings.bypassVpnTunnel

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
