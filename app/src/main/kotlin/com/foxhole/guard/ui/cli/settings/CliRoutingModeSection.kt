package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.Settings
import com.foxhole.core.model.torScopeRunnable
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onPrivacyRouteBlockAppsWhenTorUnavailableChanged
import com.foxhole.guard.ui.onPrivacyRouteScopeSelected
import com.foxhole.guard.ui.onRoutingModePresetSelected

/**
 * The two protected ways an app's traffic can leave the device. Both ride the FoxCore TUN; Split
 * only narrows which apps are captured. Optional local SOCKS/HTTP listeners are configured
 * separately and remain attached to this same protected runtime.
 */
private enum class CliVpnConn { WHOLE_DEVICE, SELECTED_APPS }

/**
 * The "app traffic routing" window: two ordered sections inside one CLI panel —
 * 1. VPN connection (whole device / selected apps) with the include/exclude sub-choice and
 *    a link-styled note whose text tracks the picked mode;
 * 2. Tor (whole device / selected apps) plus the fail-closed "block without Tor" guard.
 */
@Composable
internal fun CliVpnModeSection(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    val colors = LocalCliColors.current
    val conn = when {
        settings.expert.perAppRoutingMode == PerAppRoutingMode.FULL_TUNNEL -> CliVpnConn.WHOLE_DEVICE
        else -> CliVpnConn.SELECTED_APPS
    }
    CliPanel(
        icon = R.drawable.pix_globe,
        title = stringResource(R.string.cli_route_apps_traffic_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Section 1 — VPN connection: whole device / selected apps / proxy server.
        CliDropdownRow(
            label = stringResource(R.string.cli_route_vpn_conn),
            value = stringResource(vpnConnLabel(conn)),
            options = CliVpnConn.entries.map { candidate ->
                CliDropdownOption(
                    id = candidate.name,
                    label = stringResource(vpnConnLabel(candidate)),
                    icon = vpnConnIcon(candidate),
                )
            },
            selectedId = conn.name,
            onSelect = { id -> applyVpnConn(viewModel, CliVpnConn.valueOf(id), settings) },
        )
        // Selected apps: pick the split direction; the note below explains it and re-reads on change.
        if (conn == CliVpnConn.SELECTED_APPS) {
            CliSplitControls(viewModel, settings)
        }
        // Link-styled explanatory line — its text tracks the connection and split direction.
        CliElbowLine(text = stringResource(vpnConnNote(conn, settings)), color = colors.info)

        Spacer(modifier = Modifier.height(CliSpacing.sm))
        // Section 2 — Tor: whole device or selected apps, plus the fail-closed "block without Tor" guard.
        CliDropdownRow(
            label = stringResource(R.string.cli_route_tor_conn),
            value = stringResource(torScopeLabel(settings.privacyRoute.scope)),
            options = PrivacyRouteScope.entries.map { candidate ->
                CliDropdownOption(
                    id = candidate.name,
                    label = stringResource(torScopeLabel(candidate)),
                    icon = if (candidate == PrivacyRouteScope.ALL_APPS) {
                        R.drawable.pix_device
                    } else {
                        R.drawable.pix_apps
                    },
                )
            },
            selectedId = settings.privacyRoute.scope.name,
            onSelect = { id -> viewModel.onPrivacyRouteScopeSelected(PrivacyRouteScope.valueOf(id)) },
        )
        // Selected-apps Tor with an empty TOR lane can carry nothing yet — surface inline the same
        // "choose apps first" guard the Home terminal prints, so the routing screen explains itself.
        if (!settings.torScopeRunnable()) {
            CliElbowLine(
                text = stringResource(R.string.privacy_route_select_apps_first),
                color = colors.info,
            )
        }
        CliToggleRow(
            label = stringResource(R.string.cli_route_tor_block_without),
            checked = settings.privacyRoute.blockAppsWhenTorUnavailable,
            onToggle = { value -> viewModel.onPrivacyRouteBlockAppsWhenTorUnavailableChanged(value) },
            note = stringResource(R.string.cli_route_tor_block_without_note),
        )
    }
}

private fun vpnConnLabel(conn: CliVpnConn): Int = when (conn) {
    CliVpnConn.WHOLE_DEVICE -> R.string.cli_route_vpn_whole_device
    CliVpnConn.SELECTED_APPS -> R.string.cli_route_vpn_selected
}

private fun vpnConnIcon(conn: CliVpnConn): Int = when (conn) {
    CliVpnConn.WHOLE_DEVICE -> R.drawable.pix_shield
    CliVpnConn.SELECTED_APPS -> R.drawable.pix_apps
}

/**
 * The explanatory note under the VPN connection dropdown. Whole device tunnels everything (no
 * exceptions); selected apps reads include- or exclude-first from the split direction.
 */
private fun vpnConnNote(
    conn: CliVpnConn,
    settings: Settings,
): Int = when (conn) {
    CliVpnConn.WHOLE_DEVICE -> R.string.cli_route_vpn_note_whole
    CliVpnConn.SELECTED_APPS ->
        if (settings.expert.perAppRoutingMode == PerAppRoutingMode.EXCLUDE_SELECTED_APPS) {
            R.string.cli_route_vpn_note_exclude
        } else {
            R.string.cli_route_vpn_note_include
        }
}

private fun applyVpnConn(
    viewModel: HomeViewModel,
    conn: CliVpnConn,
    settings: Settings,
) {
    when (conn) {
        CliVpnConn.WHOLE_DEVICE -> {
            viewModel.onRoutingModePresetSelected(
                preset = RoutingModePreset.VPN,
                scope = settings.privacyRoute.scope,
            )
        }
        CliVpnConn.SELECTED_APPS -> {
            // Keep the user's existing include/exclude direction; default to "only selected".
            val preset =
                if (settings.expert.perAppRoutingMode == PerAppRoutingMode.EXCLUDE_SELECTED_APPS) {
                    RoutingModePreset.SPLIT_EXCLUDE
                } else {
                    RoutingModePreset.SPLIT_INCLUDE
                }
            viewModel.onRoutingModePresetSelected(preset = preset, scope = settings.privacyRoute.scope)
        }
    }
}

private fun torScopeLabel(scope: PrivacyRouteScope): Int = when (scope) {
    PrivacyRouteScope.ALL_APPS -> R.string.cli_route_tor_device
    PrivacyRouteScope.SELECTED_APPS -> R.string.cli_route_tor_apps
}

@Composable
private fun CliSplitControls(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    val exclude = settings.expert.perAppRoutingMode == PerAppRoutingMode.EXCLUDE_SELECTED_APPS
    CliDropdownRow(
        label = stringResource(R.string.cli_route_split_kind),
        value = stringResource(
            if (exclude) R.string.cli_route_split_exclude else R.string.cli_route_split_include,
        ),
        options = listOf(
            CliDropdownOption(
                id = PerAppRoutingMode.INCLUDE_SELECTED_APPS.name,
                label = stringResource(R.string.cli_route_split_include),
            ),
            CliDropdownOption(
                id = PerAppRoutingMode.EXCLUDE_SELECTED_APPS.name,
                label = stringResource(R.string.cli_route_split_exclude),
            ),
        ),
        selectedId = settings.expert.perAppRoutingMode.name,
        onSelect = { id ->
            val selectedMode = PerAppRoutingMode.valueOf(id)
            viewModel.onRoutingModePresetSelected(
                preset =
                if (selectedMode == PerAppRoutingMode.INCLUDE_SELECTED_APPS) {
                    RoutingModePreset.SPLIT_INCLUDE
                } else {
                    RoutingModePreset.SPLIT_EXCLUDE
                },
                scope = settings.privacyRoute.scope,
            )
        },
    )
}
