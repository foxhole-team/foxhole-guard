package com.foxhole.guard.ui

import com.foxhole.core.model.TrafficMode

/**
 * What a routing-settings change does to a LIVE runtime. Split mode, app sets, proxy surfaces
 * and the Tor overlay reshape the SAME runtime and hot-reload in place; changing the runtime
 * shape (tunnel<->proxy, entering or leaving the standalone tor-only session) is always a full
 * teardown->start transition, which the UI confirms before applying.
 */
enum class RoutingChangeAction {
    /** Compatible with the running runtime: reload it in place — no restart, no prompt. */
    HOT_RELOAD,

    /** Incompatible runtime shape: full transition, confirmed by the user first. */
    FULL_SWITCH,
}

/** The routing shape of a runtime: what it serves now (old) vs what settings demand (new). */
data class RoutingChangeState(
    val trafficMode: TrafficMode,
    val torOnlyRuntime: Boolean = false,
)

internal fun resolveRoutingChangeAction(
    old: RoutingChangeState,
    new: RoutingChangeState,
): RoutingChangeAction =
    when {
        old.torOnlyRuntime != new.torOnlyRuntime -> RoutingChangeAction.FULL_SWITCH
        // The standalone tor-only session has no VPN leg, so traffic.mode does not shape it.
        !old.torOnlyRuntime && old.trafficMode != new.trafficMode -> RoutingChangeAction.FULL_SWITCH
        else -> RoutingChangeAction.HOT_RELOAD
    }
