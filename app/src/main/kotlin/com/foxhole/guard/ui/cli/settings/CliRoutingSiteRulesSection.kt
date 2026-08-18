package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.RoutingPreset
import com.foxhole.core.model.RoutingRule
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliColors
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliDashedInfoNote
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliInputModal
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.deleteRule
import com.foxhole.guard.ui.normalizedSiteMaskToken
import com.foxhole.guard.ui.saveSiteRule
import com.foxhole.guard.ui.siteMaskValidationErrorRes

@Composable
internal fun CliSiteRulesSection(
    viewModel: HomeViewModel,
    activePreset: RoutingPreset?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val rules = remember(activePreset) {
        activePreset
            ?.rules
            .orEmpty()
            .filter { rule ->
                (rule.matchDomains.isNotEmpty() || rule.matchIpCidrs.isNotEmpty()) &&
                    rule.matchPorts.isEmpty() &&
                    rule.matchProtocols.isEmpty() &&
                    rule.matchNetworks.isEmpty()
            }
            .sortedBy(RoutingRule::order)
    }
    var newDomain by rememberSaveable { mutableStateOf("") }
    var newLane by rememberSaveable { mutableStateOf(CliSiteLane.VPN) }
    var showInvalid by rememberSaveable { mutableStateOf(false) }
    var addOpen by rememberSaveable { mutableStateOf(false) }

    CliPanel(
        icon = R.drawable.pix_link,
        title = stringResource(R.string.cli_route_sites_title),
        infoText = stringResource(R.string.cli_route_sites_note) + "\n" +
            stringResource(R.string.cli_route_sites_formats),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (rules.isEmpty()) {
            CliDashedInfoNote(
                text = stringResource(R.string.cli_route_sites_empty),
                centered = true,
                centeredIconLeading = true,
                centeredIconFirstLine = true,
            )
        }
        rules.forEachIndexed { index, rule ->
            if (index > 0) CliRowDivider()
            CliSiteRuleRow(
                rule = rule,
                onLaneSelect = { lane ->
                    viewModel.saveSiteRule(
                        ruleId = rule.id,
                        domains = siteRuleTokens(rule),
                        action = lane.action,
                    )
                },
                onRemove = { viewModel.deleteRule(rule.id) },
            )
        }
        CliButton(
            label = stringResource(R.string.cli_input_domain_add),
            icon = R.drawable.pix_add,
            color = colors.accent,
            onClick = { addOpen = true },
            modifier = Modifier.fillMaxWidth(),
        )
        if (addOpen) {
            CliInputModal(
                title = stringResource(R.string.cli_input_domain_title),
                icon = R.drawable.pix_globe,
                prompt = "add",
                value = newDomain,
                belowInput = {
                    CliDropdownRow(
                        label = stringResource(R.string.cli_route_sites_lane),
                        value = siteLaneSelectedLabel(newLane),
                        options = siteLaneOptions(colors),
                        selectedId = newLane.name,
                        onSelect = { id -> newLane = CliSiteLane.valueOf(id) },
                        note = siteLaneNote(newLane),
                        valueColor = siteLaneColor(newLane, colors),
                        showSelectedOptionIcon = true,
                    )
                },
                onValueChange = { raw ->
                    newDomain = raw.take(MAX_DOMAIN_INPUT)
                    showInvalid = false
                },
                onSubmit = {
                    val mask = normalizedSiteMaskToken(newDomain)
                    if (mask == null || siteMaskValidationErrorRes(mask) != null) {
                        showInvalid = true
                    } else {
                        viewModel.saveSiteRule(
                            ruleId = null,
                            domains = listOf(mask),
                            action = newLane.action,
                        )
                        newDomain = ""
                        showInvalid = false
                        addOpen = false
                    }
                },
                onDismiss = { addOpen = false },
            )
        }
        if (showInvalid) {
            CliElbowLine(
                text = stringResource(R.string.cli_route_sites_invalid),
                color = colors.warn,
            )
        }
    }
}

@Composable
private fun CliSiteRuleRow(
    rule: RoutingRule,
    onLaneSelect: (CliSiteLane) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalCliColors.current
    val lane = CliSiteLane.from(rule.action)
    CliDropdownRow(
        label = siteRuleTokens(rule).joinToString(", "),
        value = siteLaneSelectedLabel(lane),
        options = siteLaneOptions(colors) + CliDropdownOption(
            id = SITE_OPT_REMOVE,
            label = stringResource(R.string.cli_route_remove),
            icon = R.drawable.pix_cross,
        ),
        selectedId = lane.name,
        onSelect = { id ->
            if (id == SITE_OPT_REMOVE) onRemove() else onLaneSelect(CliSiteLane.valueOf(id))
        },
        note = siteLaneNote(lane),
        labelColor = colors.fg,
        valueColor = siteLaneColor(lane, colors),
        showSelectedOptionIcon = true,
    )
}

private fun siteRuleTokens(rule: RoutingRule): List<String> =
    rule.matchDomains + rule.matchIpCidrs.map { cidr -> "cidr:$cidr" }

@Composable
private fun siteLaneOptions(colors: CliColors): List<CliDropdownOption> {
    val labels = CliSiteLane.entries.map { lane -> lane to siteLaneActionLabel(lane) }
    return labels.map { (lane, label) ->
        CliDropdownOption(
            id = lane.name,
            label = label,
            icon = siteLaneIcon(lane),
            iconTint = siteLaneColor(lane, colors),
        )
    }
}

@Composable
private fun siteLaneSelectedLabel(lane: CliSiteLane): String = when (lane) {
    CliSiteLane.TOR -> "tor"
    CliSiteLane.VPN -> "vpn"
    CliSiteLane.DIRECT -> stringResource(R.string.cli_route_lane_excluded)
    CliSiteLane.BLOCK -> stringResource(R.string.cli_route_lane_blocked)
}

@Composable
private fun siteLaneActionLabel(lane: CliSiteLane): String = when (lane) {
    CliSiteLane.TOR -> "tor"
    CliSiteLane.VPN -> "vpn"
    CliSiteLane.DIRECT -> stringResource(R.string.cli_route_lane_exclude)
    CliSiteLane.BLOCK -> stringResource(R.string.cli_route_lane_block)
}

@Composable
private fun siteLaneNote(lane: CliSiteLane): String? =
    stringResource(R.string.cli_route_sites_lane_tor_note).takeIf { lane == CliSiteLane.TOR }

private fun siteLaneIcon(lane: CliSiteLane): Int = when (lane) {
    CliSiteLane.TOR -> R.drawable.pix_tor
    CliSiteLane.VPN -> R.drawable.pix_shield
    CliSiteLane.DIRECT -> R.drawable.pix_globe
    CliSiteLane.BLOCK -> R.drawable.pix_forbidden
}

private fun siteLaneColor(
    lane: CliSiteLane,
    colors: CliColors,
): Color = when (lane) {
    CliSiteLane.TOR -> colors.tor
    CliSiteLane.VPN -> colors.vpn
    CliSiteLane.DIRECT -> colors.dim
    CliSiteLane.BLOCK -> colors.err
}

private const val MAX_DOMAIN_INPUT = 253
private const val SITE_OPT_REMOVE = "remove"
