package com.foxhole.guard.ui.cli.settings

import com.foxhole.core.model.RoutingRuleAction

/** Domain actions that the runtime route assembler can enforce today. */
internal enum class CliSiteLane(
    val action: RoutingRuleAction,
) {
    VPN(RoutingRuleAction.PROXY),
    DIRECT(RoutingRuleAction.DIRECT),
    BLOCK(RoutingRuleAction.BLOCK),
    ;

    companion object {
        fun from(action: RoutingRuleAction): CliSiteLane =
            entries.first { lane -> lane.action == action }
    }
}
