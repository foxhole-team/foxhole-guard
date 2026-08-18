package com.foxhole.guard.ui.cli.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.guard.R
import com.foxhole.guard.ui.PendingRoutingScenarioChange
import com.foxhole.guard.ui.VpnRoutingScenario
import com.foxhole.guard.ui.cli.LocalCliColors

@Composable
internal fun CliRoutingChangeConfirmSheet(
    change: PendingRoutingScenarioChange,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliConfirmSheet(
        title = stringResource(R.string.cli_route_change_confirm_title),
        question = stringResource(R.string.cli_route_change_confirm_question),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        icon = R.drawable.pix_globe,
    ) {
        CliElbowLine(
            text = stringResource(R.string.cli_route_change_confirm_info),
            color = colors.note,
        )
        CliRowDivider()
        if (change is PendingRoutingScenarioChange.I2pRelay) {
            CliKeyValue(
                key = stringResource(R.string.cli_route_change_i2p_relay),
                value = stringResource(
                    R.string.cli_route_change_transition,
                    stringResource(change.currentLabelRes()),
                    stringResource(change.targetLabelRes()),
                ),
                valueColor = colors.info,
            )
        } else {
            CliKeyValue(
                key = stringResource(R.string.cli_route_change_current),
                value = stringResource(change.currentLabelRes()),
                valueColor = colors.dim,
            )
            CliRowDivider()
            CliKeyValue(
                key = stringResource(R.string.cli_route_change_target),
                value = stringResource(change.targetLabelRes()),
                valueColor = colors.info,
            )
        }
    }
}

@StringRes
internal fun PendingRoutingScenarioChange.currentLabelRes(): Int =
    when (this) {
        is PendingRoutingScenarioChange.OperatingMode -> current.labelRes()
        is PendingRoutingScenarioChange.I2pRelay -> enabledLabelRes(currentEnabled)
        is PendingRoutingScenarioChange.Vpn -> current.labelRes()
        is PendingRoutingScenarioChange.Tor -> current.labelRes()
    }

@StringRes
internal fun PendingRoutingScenarioChange.targetLabelRes(): Int =
    when (this) {
        is PendingRoutingScenarioChange.OperatingMode -> target.labelRes()
        is PendingRoutingScenarioChange.I2pRelay -> enabledLabelRes(targetEnabled)
        is PendingRoutingScenarioChange.Vpn -> scenario.labelRes()
        is PendingRoutingScenarioChange.Tor -> scope.labelRes()
    }

@StringRes
private fun enabledLabelRes(enabled: Boolean): Int =
    if (enabled) R.string.cli_route_change_enabled else R.string.cli_route_change_disabled

@StringRes
private fun RoutingModePreset.labelRes(): Int =
    when (this) {
        RoutingModePreset.VPN -> R.string.cli_st_vpn
        RoutingModePreset.TOR -> R.string.cli_st_tor
        RoutingModePreset.VPN_TOR -> R.string.cli_home_status_mode_vpn_tor
        RoutingModePreset.SPLIT_INCLUDE,
        RoutingModePreset.SPLIT_EXCLUDE,
        -> R.string.cli_st_vpn
    }

@StringRes
private fun VpnRoutingScenario.labelRes(): Int =
    when (this) {
        VpnRoutingScenario.WHOLE_DEVICE -> R.string.cli_route_vpn_whole_device
        VpnRoutingScenario.SELECTED_INCLUDE -> R.string.cli_route_split_include
        VpnRoutingScenario.SELECTED_EXCLUDE -> R.string.cli_route_split_exclude
        VpnRoutingScenario.PROXY_SERVER -> R.string.cli_route_vpn_proxy_server
    }

@StringRes
private fun PrivacyRouteScope.labelRes(): Int =
    when (this) {
        PrivacyRouteScope.ALL_APPS -> R.string.cli_route_tor_device
        PrivacyRouteScope.SELECTED_APPS -> R.string.cli_route_tor_apps
    }
