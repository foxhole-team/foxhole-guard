package com.foxhole.guard.ui

import com.foxhole.core.model.TrafficMode

enum class RoutingChangeAction {
    HOT_RELOAD,

    FULL_SWITCH,
}

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
        !old.torOnlyRuntime && old.trafficMode != new.trafficMode -> RoutingChangeAction.FULL_SWITCH
        else -> RoutingChangeAction.HOT_RELOAD
    }
