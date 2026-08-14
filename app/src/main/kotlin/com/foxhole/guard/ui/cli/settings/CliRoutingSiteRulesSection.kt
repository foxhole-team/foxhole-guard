package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.RoutingPreset
import com.foxhole.core.model.RoutingRule
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliInputModal
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.deleteRule
import com.foxhole.guard.ui.normalizedSiteMaskToken
import com.foxhole.guard.ui.saveSiteRule
import com.foxhole.guard.ui.siteMaskValidationErrorRes

/** Encrypted domain rules applied by the runtime and hot-reloaded into the active TUN. */
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
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.cli_route_sites_note),
            style = CliType.small,
            color = colors.dim,
        )
        CliElbowLine(
            text = stringResource(R.string.cli_route_sites_formats),
            color = colors.note,
        )
        if (rules.isEmpty()) {
            Text(
                text = stringResource(R.string.cli_route_sites_empty),
                style = CliType.small,
                color = colors.dim,
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
                prompt = "add",
                value = newDomain,
                // The lane belongs to the domain being added, so it is asked for here. Left in the
                // section it read as a stray setting — with no rules there was nothing above it for
                // it to qualify.
                belowInput = {
                    CliDropdownRow(
                        label = stringResource(R.string.cli_route_sites_lane),
                        value = siteLaneLabel(newLane),
                        options = CliSiteLane.entries.map { lane ->
                            CliDropdownOption(id = lane.name, label = siteLaneLabel(lane), icon = siteLaneIcon(lane))
                        },
                        selectedId = newLane.name,
                        onSelect = { id -> newLane = CliSiteLane.valueOf(id) },
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

/** A rule row: the lane dropdown plus a delete cross. */
@Composable
private fun CliSiteRuleRow(
    rule: RoutingRule,
    onLaneSelect: (CliSiteLane) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CliDropdownRow(
            label = siteRuleTokens(rule).joinToString(", "),
            value = siteLaneLabel(CliSiteLane.from(rule.action)),
            options = CliSiteLane.entries.map { lane ->
                CliDropdownOption(id = lane.name, label = siteLaneLabel(lane), icon = siteLaneIcon(lane))
            },
            selectedId = CliSiteLane.from(rule.action).name,
            onSelect = { id -> onLaneSelect(CliSiteLane.valueOf(id)) },
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .cliPressable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "[x]", style = CliType.body, color = colors.err)
        }
    }
}

private fun siteRuleTokens(rule: RoutingRule): List<String> =
    rule.matchDomains + rule.matchIpCidrs.map { cidr -> "cidr:$cidr" }

// Technical lane names, identical in every locale, like the lane labels in the app list above.
private fun siteLaneLabel(lane: CliSiteLane): String = lane.name.lowercase()

private fun siteLaneIcon(lane: CliSiteLane): Int = when (lane) {
    CliSiteLane.VPN -> R.drawable.pix_shield
    CliSiteLane.DIRECT -> R.drawable.pix_globe
    CliSiteLane.BLOCK -> R.drawable.pix_forbidden
}

// Domain input ceiling: the DNS name length limit, like MAX_SERVER_LENGTH in the dns section.
private const val MAX_DOMAIN_INPUT = 253
