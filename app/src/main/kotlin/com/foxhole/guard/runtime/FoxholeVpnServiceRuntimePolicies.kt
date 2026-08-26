package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.NotificationSnapshot
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.i2pRuntimeActive
import com.foxhole.guard.R

internal fun shouldAttemptRuntimeReloadRestore(
    previousSession: VpnSession?,
    failedSession: VpnSession,
): Boolean =
    previousSession != null &&
        previousSession.profileId == failedSession.profileId &&
        previousSession.configJson != failedSession.configJson

internal fun FoxholeVpnService.isSameLocalGuardRuntimeActive(mode: LocalGuardMode): Boolean {
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    return activeLocalGuardMode == mode &&
        snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        snapshot.state == ConnectionState.CONNECTED
}

internal suspend fun FoxholeVpnService.isLocalGuardRuntimeCurrent(mode: LocalGuardMode): Boolean =
    container.connectionController.appliedRuntimeSignature.value ==
        container.connectionController.currentLocalGuardRuntimeFingerprint(mode)

internal fun FoxholeVpnService.notificationSmallIconRes(snapshot: NotificationSnapshot): Int =
    when {
        activeLocalGuardMode == LocalGuardMode.DNS -> R.drawable.ic_notification_dns
        activeLocalGuardMode != null -> R.drawable.ic_notification_firewall
        snapshot.state == ConnectionState.ERROR -> R.drawable.ic_notification_error
        snapshot.state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING) &&
            notificationTorRouteActive() -> R.drawable.ic_notification_tor
        else -> R.drawable.ic_notification_vpn
    }

private fun FoxholeVpnService.notificationTorRouteActive(): Boolean {
    val session = activeSession
    return session?.torActive == true || session?.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
}

internal enum class FoxholeNotificationRouteKind {
    VPN_TUNNEL,
    VPN_PROXY,
    TOR_ONLY,
    TOR_IN_VPN,
    TOR_BESIDE_VPN,
}

internal enum class FoxholeNotificationSplitScope {
    NONE,

    INCLUDE,

    EXCLUDE,
}

internal fun FoxholeVpnService.notificationSplitScope(): FoxholeNotificationSplitScope {
    val expert = container.settingsRepository.settings.value.expert
    appliedTorNotificationSplitScope(activeSession)?.let { return it }
    if (expert.tunnelSelectedPackages().none(String::isNotBlank)) {
        return FoxholeNotificationSplitScope.NONE
    }
    return when (expert.perAppRoutingMode) {
        PerAppRoutingMode.INCLUDE_SELECTED_APPS -> FoxholeNotificationSplitScope.INCLUDE
        PerAppRoutingMode.EXCLUDE_SELECTED_APPS -> FoxholeNotificationSplitScope.EXCLUDE
        PerAppRoutingMode.FULL_TUNNEL -> FoxholeNotificationSplitScope.NONE
    }
}

internal fun appliedTorNotificationSplitScope(session: VpnSession?): FoxholeNotificationSplitScope? {
    if (session?.torActive != true) {
        return null
    }
    val route = session.appliedTorRoute ?: return null
    return FoxholeNotificationSplitScope.INCLUDE.takeIf {
        route.scope == PrivacyRouteScope.SELECTED_APPS &&
            route.selectedPackages.any(String::isNotBlank)
    }
}

internal fun FoxholeVpnService.notificationRouteKind(): FoxholeNotificationRouteKind? {
    return notificationRouteKind(
        session = activeSession,
        trafficMode = FoxholeVpnRuntimeBridge.snapshot.value.trafficMode,
    )
}

internal fun notificationRouteKind(
    session: VpnSession?,
    trafficMode: TrafficMode,
): FoxholeNotificationRouteKind? {
    return when {
        session?.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> FoxholeNotificationRouteKind.TOR_ONLY
        session?.torActive == true && session.appliedTorRoute?.bypassVpnTunnel == true ->
            FoxholeNotificationRouteKind.TOR_BESIDE_VPN
        session?.torActive == true -> FoxholeNotificationRouteKind.TOR_IN_VPN
        session == null -> null
        trafficMode == TrafficMode.PROXY -> FoxholeNotificationRouteKind.VPN_PROXY
        else -> FoxholeNotificationRouteKind.VPN_TUNNEL
    }
}

internal fun FoxholeVpnService.appTrafficStatsRuntimeEnabled(settings: Settings): Boolean =
    settings.statistics.enabled &&
        settings.statistics.appTrafficEnabled &&
        settings.appTrafficStatsEnabled &&
        settings.appTrafficUsageAccessConsent &&
        appTrafficStatsRecorder.hasUsageAccess()

internal fun destinationCountryTrackingRuntimeEnabled(settings: Settings): Boolean =
    settings.ui.trafficMapEnabled ||
        settings.statistics.enabled &&
        (
            settings.statistics.countryTrafficEnabled ||
                (
                    settings.anomaly.enabled &&
                        settings.statistics.anomalyMetricsEnabled &&
                        settings.anomaly.analyzeDestinationCountries
                    )
            )

internal fun dnsRuntimeStatsRuntimeEnabled(settings: Settings): Boolean =
    settings.statistics.enabled && settings.statistics.dnsFilteringEnabled

internal fun networkActivityStatsRuntimeEnabled(settings: Settings): Boolean =
    settings.expert.networkActivityLogging

internal fun laneTrafficStatsRuntimeEnabled(settings: Settings): Boolean =
    settings.privacyRoute.enabled || settings.i2pRuntimeActive()

internal fun FoxholeVpnService.runtimeConnectionObserverNeeded(settings: Settings): Boolean =
    destinationCountryTrackingRuntimeEnabled(settings) ||
        dnsRuntimeStatsRuntimeEnabled(settings) ||
        networkActivityStatsRuntimeEnabled(settings) ||
        appTrafficStatsRuntimeEnabled(settings) ||
        laneTrafficStatsRuntimeEnabled(settings)
