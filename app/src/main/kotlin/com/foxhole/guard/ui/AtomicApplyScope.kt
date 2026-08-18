package com.foxhole.guard.ui

import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.Settings

internal enum class AtomicApplyScope {
    MODE,
    SCENARIO,
    RULES,
}

internal fun requiresApplyConfirmation(
    scope: AtomicApplyScope,
    atomicConnection: Boolean,
): Boolean = scope != AtomicApplyScope.RULES && !atomicConnection

internal enum class VpnRoutingScenario {
    WHOLE_DEVICE,
    SELECTED_INCLUDE,
    SELECTED_EXCLUDE,
    PROXY_SERVER,
}

internal sealed interface PendingRoutingScenarioChange {
    data class OperatingMode(
        val current: RoutingModePreset,
        val target: RoutingModePreset,
        val scope: PrivacyRouteScope,
    ) : PendingRoutingScenarioChange

    data class I2pRelay(
        val currentEnabled: Boolean,
        val targetEnabled: Boolean,
    ) : PendingRoutingScenarioChange

    data class Vpn(
        val current: VpnRoutingScenario,
        val scenario: VpnRoutingScenario,
    ) : PendingRoutingScenarioChange

    data class Tor(
        val current: PrivacyRouteScope,
        val scope: PrivacyRouteScope,
    ) : PendingRoutingScenarioChange
}

internal fun currentVpnRoutingScenario(settings: Settings): VpnRoutingScenario =
    when {
        settings.expert.localSurfaces.http.enabled -> VpnRoutingScenario.PROXY_SERVER
        settings.expert.perAppRoutingMode == PerAppRoutingMode.FULL_TUNNEL ->
            VpnRoutingScenario.WHOLE_DEVICE
        settings.expert.perAppRoutingMode == PerAppRoutingMode.EXCLUDE_SELECTED_APPS ->
            VpnRoutingScenario.SELECTED_EXCLUDE
        else -> VpnRoutingScenario.SELECTED_INCLUDE
    }

internal fun VpnRoutingScenario.perAppRoutingMode(current: PerAppRoutingMode): PerAppRoutingMode =
    when (this) {
        VpnRoutingScenario.WHOLE_DEVICE -> PerAppRoutingMode.FULL_TUNNEL
        VpnRoutingScenario.SELECTED_INCLUDE -> PerAppRoutingMode.INCLUDE_SELECTED_APPS
        VpnRoutingScenario.SELECTED_EXCLUDE -> PerAppRoutingMode.EXCLUDE_SELECTED_APPS
        VpnRoutingScenario.PROXY_SERVER -> current
    }
