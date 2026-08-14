package com.foxhole.guard.ui

import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.Settings

/**
 * What a settings change is allowed to do behind the user's back.
 *
 * The "atomic scenario application" switch governs both operating [MODE] and [SCENARIO] choices.
 * Per-app and per-domain [RULES] — routing lanes, BLOCK, EXCLUDE, website rules — are always
 * applied atomically and are deliberately not user-configurable, so no rule path may consult the
 * switch.
 */
internal enum class AtomicApplyScope {
    MODE,
    SCENARIO,
    RULES,
}

/** True when a change of [scope] must be confirmed before it is applied. See [AtomicApplyScope]. */
internal fun requiresApplyConfirmation(
    scope: AtomicApplyScope,
    atomicConnection: Boolean,
): Boolean = scope != AtomicApplyScope.RULES && !atomicConnection

/** Every scenario choice exposed by the routing screen; Home VPN/Tor operating modes are separate. */
internal enum class VpnRoutingScenario {
    WHOLE_DEVICE,
    SELECTED_INCLUDE,
    SELECTED_EXCLUDE,
    PROXY_SERVER,
}

/** A typed, dismissible mode/scenario change parked behind the shared bottom confirmation sheet. */
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
