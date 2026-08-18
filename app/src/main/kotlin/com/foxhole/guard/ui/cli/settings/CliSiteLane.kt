package com.foxhole.guard.ui.cli.settings

import com.foxhole.core.model.RoutingRuleAction

internal enum class CliSiteLane(
    val action: RoutingRuleAction,
) {
    TOR(RoutingRuleAction.TOR),
    VPN(RoutingRuleAction.PROXY),
    DIRECT(RoutingRuleAction.DIRECT),
    BLOCK(RoutingRuleAction.BLOCK),
    ;

    companion object {
        fun from(action: RoutingRuleAction): CliSiteLane =
            entries.firstOrNull { lane -> lane.action == action } ?: VPN
    }
}
