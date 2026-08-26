package com.foxhole.guard.ui.cli.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.torScopeRunnable
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.RoutingRouteUiState
import com.foxhole.guard.ui.SettingsRouteUiState
import com.foxhole.guard.ui.cli.CliColors
import com.foxhole.guard.ui.cli.CliMotion
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliDashedInfoNote
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRejectFeedback
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.cliRejectShake
import com.foxhole.guard.ui.onAppLaneChanged

@Composable
@Suppress("CyclomaticComplexMethod")
internal fun CliAssignedAppsSection(
    viewModel: HomeViewModel,
    state: SettingsRouteUiState,
    appState: RoutingRouteUiState,
    onAddApps: () -> Unit,
    missingAppsTarget: CliMissingAppsTarget?,
    missingAppsFeedback: CliRejectFeedback,
) {
    val colors = LocalCliColors.current
    val assignments = state.settings.expert.appAssignments
    val pendingQuarantine = state.settings.expert.pendingQuarantinePackages.toSet()
    val assigned = remember(assignments, pendingQuarantine) {
        assignments.keys.filterNot { it in pendingQuarantine }.sorted()
    }
    val inventoryReady = appState.installedAppsLoaded
    val installedIndex = remember(appState.installedApps) {
        appState.installedApps.associateBy(InstalledAppOption::packageName)
    }
    val labelOf: (String) -> String = { packageName ->
        installedIndex[packageName]?.label?.ifEmpty { packageName }
            ?: if (inventoryReady) packageName else "…"
    }
    val emptyNoteColor by animateColorAsState(
        targetValue = if (missingAppsTarget != null) colors.err else colors.firewall,
        animationSpec = CliMotion.standard(),
        label = "missingAppsNote",
    )
    val torAppsMissing = !state.settings.torScopeRunnable()
    val emptyNoteIcon = when {
        missingAppsTarget == CliMissingAppsTarget.VPN -> R.drawable.lin_shield
        missingAppsTarget == CliMissingAppsTarget.TOR || torAppsMissing -> R.drawable.lin_tor
        else -> R.drawable.lin_info
    }

    CliPanel(
        icon = R.drawable.lin_apps,
        title = stringResource(R.string.cli_route_apps_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (assigned.isEmpty() || torAppsMissing || missingAppsTarget != null) {
            CliDashedInfoNote(
                text = stringResource(
                    when {
                        missingAppsTarget == CliMissingAppsTarget.VPN -> R.string.split_tunnel_requires_apps
                        missingAppsTarget == CliMissingAppsTarget.TOR || torAppsMissing ->
                            R.string.privacy_route_select_apps_first
                        else -> R.string.cli_route_apps_empty
                    },
                ),
                icon = emptyNoteIcon,
                centered = true,
                centeredIconLeading = true,
                centeredIconFirstLine = true,
                centeredIconGap = CliSpacing.sm,
                outerVerticalPadding = CliSpacing.sm,
                color = emptyNoteColor,
                modifier = Modifier.cliRejectShake(missingAppsFeedback),
            )
        }
        var showAll by rememberSaveable { mutableStateOf(false) }
        val visibleAssigned = if (showAll || assigned.size <= LANES_COLLAPSED_MAX) {
            assigned
        } else {
            assigned.take(LANES_COLLAPSED_MAX)
        }
        visibleAssigned.forEachIndexed { index, packageName ->
            if (index > 0) CliRowDivider()
            CliAssignedAppRow(
                viewModel = viewModel,
                packageName = packageName,
                label = labelOf(packageName),
                lane = assignments[packageName] ?: AppTunnelLane.VPN,
                installed = installedIndex[packageName],
            )
        }
        if (!showAll && assigned.size > LANES_COLLAPSED_MAX) {
            CliActionRow(
                label = stringResource(R.string.cli_route_apps_show_all, assigned.size),
                icon = R.drawable.lin_apps,
                onTap = { showAll = true },
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        val hasCandidates = appState.installedApps.any { option -> option.packageName !in assignments }
        CliButton(
            label = stringResource(R.string.cli_route_add_apps),
            icon = R.drawable.lin_add,
            color = if (hasCandidates) colors.accent else colors.dim,
            onClick = onAddApps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val APP_LANE_OPTIONS = listOf(
    AppTunnelLane.TOR,
    AppTunnelLane.VPN,
    AppTunnelLane.BLOCK,
    AppTunnelLane.EXCLUDE,
)
private const val OPT_REMOVE = "remove"

private const val LANES_COLLAPSED_MAX = 10
private val APP_ROW_ICON_SIZE = 18.dp

@Composable
private fun CliAssignedAppRow(
    viewModel: HomeViewModel,
    packageName: String,
    label: String,
    lane: AppTunnelLane,
    installed: InstalledAppOption?,
) {
    val colors = LocalCliColors.current
    val icon = rememberCliAppIcon(
        packageName = packageName,
        versionCode = installed?.versionCode,
        lastUpdateTime = installed?.lastUpdateTime,
        bitmapSize = APP_ROW_ICON_SIZE,
    )
    CliDropdownRow(
        leading = {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(APP_ROW_ICON_SIZE))
            } else {
                Box(modifier = Modifier.size(APP_ROW_ICON_SIZE))
            }
        },
        label = label,
        value = appLaneSelectedLabel(lane),
        options = APP_LANE_OPTIONS.map { candidate ->
            CliDropdownOption(
                id = candidate.name,
                label = appLaneActionLabel(candidate),
                icon = appLaneIcon(candidate),
                iconTint = appLaneColor(candidate, colors),
            )
        } + CliDropdownOption(
            id = OPT_REMOVE,
            label = stringResource(R.string.cli_route_remove),
            icon = R.drawable.lin_cross,
        ),
        selectedId = lane.name,
        onSelect = { id ->
            viewModel.onAppLaneChanged(
                packageName = packageName,
                lane = if (id == OPT_REMOVE) null else AppTunnelLane.valueOf(id),
            )
        },
        labelColor = colors.fg,
        valueColor = appLaneColor(lane, colors),
        showSelectedOptionIcon = true,
    )
}

@Composable
private fun appLaneSelectedLabel(lane: AppTunnelLane): String = when (lane) {
    AppTunnelLane.TOR -> "tor"
    AppTunnelLane.VPN -> "vpn"
    AppTunnelLane.BLOCK -> stringResource(R.string.cli_route_lane_blocked)
    AppTunnelLane.EXCLUDE -> stringResource(R.string.cli_route_lane_excluded)
}

@Composable
private fun appLaneActionLabel(lane: AppTunnelLane): String = when (lane) {
    AppTunnelLane.TOR -> "tor"
    AppTunnelLane.VPN -> "vpn"
    AppTunnelLane.BLOCK -> stringResource(R.string.cli_route_lane_block)
    AppTunnelLane.EXCLUDE -> stringResource(R.string.cli_route_lane_exclude)
}

private fun appLaneIcon(lane: AppTunnelLane): Int = when (lane) {
    AppTunnelLane.TOR -> R.drawable.lin_tor
    AppTunnelLane.VPN -> R.drawable.lin_shield
    AppTunnelLane.BLOCK -> R.drawable.lin_forbidden
    AppTunnelLane.EXCLUDE -> R.drawable.lin_globe
}

private fun appLaneColor(
    lane: AppTunnelLane,
    colors: CliColors,
): Color = when (lane) {
    AppTunnelLane.TOR -> colors.tor
    AppTunnelLane.VPN -> colors.vpn
    AppTunnelLane.BLOCK -> colors.err
    AppTunnelLane.EXCLUDE -> colors.dim
}
