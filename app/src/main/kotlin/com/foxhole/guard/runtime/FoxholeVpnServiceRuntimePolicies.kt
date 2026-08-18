package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.NotificationSnapshot
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.isUdpTransport
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.i2pRuntimeActive
import com.foxhole.guard.R

// Top-level policies of the VPN service: restore/reload predicates, notification route
// resolution and the per-feature runtime-stats gates. Split from FoxholeVpnService.kt.

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
    val settings = container.settingsRepository.settings.value
    return activeSession?.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID ||
        settings.privacyRoute.mode == PrivacyRouteMode.TOR_OVER_VPN &&
        settings.traffic.mode == TrafficMode.TUNNEL &&
        (settings.privacyRoute.bypassVpnTunnel || activeSession?.protocolHint?.isUdpTransport() != true)
}

internal enum class FoxholeNotificationRouteKind {
    VPN_TUNNEL,
    VPN_PROXY,
    TOR_ONLY,
    TOR_IN_VPN,
    TOR_BESIDE_VPN,
}

/**
 * The per-app scope of whatever route is live — the second half of what the notification has to
 * say. "Only these apps" and "everything except these apps" are opposite guarantees, and a user
 * reading one line while the other is in force is the worst outcome this text can produce, so the
 * scope is carried explicitly instead of being folded into the route kind.
 */
internal enum class FoxholeNotificationSplitScope {
    /** Whole device: per-app rules exist but nothing is scoped by them. */
    NONE,

    /** Only the selected apps take the route; everything else stays off it. */
    INCLUDE,

    /** Everything takes the route except the selected apps. */
    EXCLUDE,
}

/**
 * The scope as the runtime actually applies it: a mode without a single selected package routes the
 * whole device whatever the switch says, so an empty selection reads NONE rather than promising a
 * split that is not in force.
 */
internal fun FoxholeVpnService.notificationSplitScope(): FoxholeNotificationSplitScope {
    val expert = container.settingsRepository.settings.value.expert
    if (expert.tunnelSelectedPackages().none(String::isNotBlank)) {
        return FoxholeNotificationSplitScope.NONE
    }
    return when (expert.perAppRoutingMode) {
        PerAppRoutingMode.INCLUDE_SELECTED_APPS -> FoxholeNotificationSplitScope.INCLUDE
        PerAppRoutingMode.EXCLUDE_SELECTED_APPS -> FoxholeNotificationSplitScope.EXCLUDE
        PerAppRoutingMode.FULL_TUNNEL -> FoxholeNotificationSplitScope.NONE
    }
}

// Text-layer counterpart to notificationTorRouteActive() (icon layer) — reuses the exact same
// TOR_OVER_VPN/bypassVpnTunnel/TrafficMode.TUNNEL conditions rather than re-deriving them, so the
// two can't drift apart, but distinguishes tunneled-vs-beside Tor and VPN tunnel-vs-proxy for text.
internal fun FoxholeVpnService.notificationRouteKind(): FoxholeNotificationRouteKind? {
    val settings = container.settingsRepository.settings.value
    val session = activeSession
    val torOverVpnActive =
        settings.privacyRoute.mode == PrivacyRouteMode.TOR_OVER_VPN &&
            settings.traffic.mode == TrafficMode.TUNNEL &&
            (settings.privacyRoute.bypassVpnTunnel || session?.protocolHint?.isUdpTransport() != true)
    return when {
        session?.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> FoxholeNotificationRouteKind.TOR_ONLY
        torOverVpnActive && settings.privacyRoute.bypassVpnTunnel -> FoxholeNotificationRouteKind.TOR_BESIDE_VPN
        torOverVpnActive -> FoxholeNotificationRouteKind.TOR_IN_VPN
        session == null -> null
        settings.traffic.mode == TrafficMode.PROXY -> FoxholeNotificationRouteKind.VPN_PROXY
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
