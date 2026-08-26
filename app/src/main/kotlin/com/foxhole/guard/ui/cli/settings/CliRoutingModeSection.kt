package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.packages
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.VpnRoutingScenario
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CLI_MENU_ROW_MIN_HEIGHT
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliSecretRow
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onHttpSurfaceChanged
import com.foxhole.guard.ui.onLocalProxyAuthChanged
import com.foxhole.guard.ui.onLocalProxyAuthEnabledChanged
import com.foxhole.guard.ui.onPrivacyRouteBlockAppsWhenTorUnavailableChanged
import com.foxhole.guard.ui.onPrivacyRouteScopeSelected
import com.foxhole.guard.ui.onProxySurfaceModeSelected
import com.foxhole.guard.ui.onSocksSurfaceChanged
import com.foxhole.guard.ui.onVpnRoutingScenarioSelected

internal enum class CliVpnConn { WHOLE_DEVICE, SELECTED_APPS, PROXY_SERVER }

internal enum class CliMissingAppsTarget { VPN, TOR }

@Composable
internal fun CliVpnModeSection(
    viewModel: HomeViewModel,
    settings: Settings,
    onMissingAppsRejected: (CliMissingAppsTarget) -> Unit,
) {
    val colors = LocalCliColors.current
    val conn = when {
        settings.expert.localSurfaces.http.enabled -> CliVpnConn.PROXY_SERVER
        settings.expert.perAppRoutingMode == PerAppRoutingMode.FULL_TUNNEL -> CliVpnConn.WHOLE_DEVICE
        else -> CliVpnConn.SELECTED_APPS
    }
    val noAppsSelected = remember(settings.expert.appAssignments) {
        settings.expert.tunnelSelectedPackages().none(String::isNotBlank)
    }
    val noTorAppsSelected = remember(settings.expert.appAssignments) {
        settings.expert.packages(AppTunnelLane.TOR).none(String::isNotBlank)
    }
    CliPanel(
        icon = R.drawable.lin_globe,
        title = stringResource(R.string.cli_route_apps_traffic_title),
        modifier = Modifier.fillMaxWidth(),
        infoText = stringResource(R.string.cli_help_routing_body),
    ) {
        CliDropdownRow(
            label = stringResource(R.string.cli_route_vpn_conn),
            icon = R.drawable.lin_shield,
            value = stringResource(vpnConnLabel(conn)),
            options = CliVpnConn.entries.map { candidate ->
                CliDropdownOption(
                    id = candidate.name,
                    label = stringResource(vpnConnLabel(candidate)),
                    icon = vpnConnIcon(candidate),
                    iconTint = colors.vpn,
                )
            },
            selectedId = conn.name,
            onSelect = { id ->
                val candidate = CliVpnConn.valueOf(id)
                if (vpnConnNeedsApps(candidate) && noAppsSelected) {
                    onMissingAppsRejected(CliMissingAppsTarget.VPN)
                } else {
                    applyVpnConn(viewModel, candidate, settings)
                }
            },
            showSelectedOptionIcon = true,
            infoText = stringResource(vpnConnNote(conn, settings)),
        )
        CliSettingsAnimatedRows(visible = conn == CliVpnConn.SELECTED_APPS) {
            CliSplitControls(viewModel, settings)
        }
        CliSettingsAnimatedRows(visible = conn == CliVpnConn.PROXY_SERVER) {
            CliLocalProxyControls(viewModel, settings)
        }

        CliRowDivider()
        CliDropdownRow(
            label = stringResource(R.string.cli_route_tor_conn),
            icon = R.drawable.lin_tor,
            value = stringResource(torScopeLabel(settings.privacyRoute.scope)),
            options = PrivacyRouteScope.entries.map { candidate ->
                CliDropdownOption(
                    id = candidate.name,
                    label = stringResource(torScopeLabel(candidate)),
                    icon = if (candidate == PrivacyRouteScope.ALL_APPS) {
                        R.drawable.lin_device
                    } else {
                        R.drawable.lin_apps
                    },
                    iconTint = colors.tor,
                )
            },
            selectedId = settings.privacyRoute.scope.name,
            onSelect = { id ->
                val candidate = PrivacyRouteScope.valueOf(id)
                if (candidate == PrivacyRouteScope.SELECTED_APPS && noTorAppsSelected) {
                    onMissingAppsRejected(CliMissingAppsTarget.TOR)
                } else {
                    viewModel.onPrivacyRouteScopeSelected(candidate)
                }
            },
            showSelectedOptionIcon = true,
        )
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_route_tor_block_without),
            icon = R.drawable.lin_forbidden,
            checked = settings.privacyRoute.blockAppsWhenTorUnavailable,
            onToggle = { value -> viewModel.onPrivacyRouteBlockAppsWhenTorUnavailableChanged(value) },
            infoText = stringResource(R.string.cli_route_tor_block_without_note),
        )
    }
}

internal fun vpnConnNeedsApps(conn: CliVpnConn): Boolean = conn == CliVpnConn.SELECTED_APPS

private fun vpnConnLabel(conn: CliVpnConn): Int = when (conn) {
    CliVpnConn.WHOLE_DEVICE -> R.string.cli_route_vpn_whole_device
    CliVpnConn.SELECTED_APPS -> R.string.cli_route_vpn_selected
    CliVpnConn.PROXY_SERVER -> R.string.cli_route_vpn_proxy_server
}

private fun vpnConnIcon(conn: CliVpnConn): Int = when (conn) {
    CliVpnConn.WHOLE_DEVICE -> R.drawable.lin_shield
    CliVpnConn.SELECTED_APPS -> R.drawable.lin_apps
    CliVpnConn.PROXY_SERVER -> R.drawable.lin_device
}

private fun vpnConnNote(
    conn: CliVpnConn,
    settings: Settings,
): Int = when (conn) {
    CliVpnConn.WHOLE_DEVICE -> R.string.cli_route_vpn_note_whole
    CliVpnConn.PROXY_SERVER -> R.string.cli_route_vpn_note_proxy_server
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
    val scenario =
        when (conn) {
            CliVpnConn.PROXY_SERVER -> VpnRoutingScenario.PROXY_SERVER
            CliVpnConn.WHOLE_DEVICE -> VpnRoutingScenario.WHOLE_DEVICE
            CliVpnConn.SELECTED_APPS ->
                if (settings.expert.perAppRoutingMode == PerAppRoutingMode.EXCLUDE_SELECTED_APPS) {
                    VpnRoutingScenario.SELECTED_EXCLUDE
                } else {
                    VpnRoutingScenario.SELECTED_INCLUDE
                }
        }
    viewModel.onVpnRoutingScenarioSelected(scenario)
}

private fun torScopeLabel(scope: PrivacyRouteScope): Int = when (scope) {
    PrivacyRouteScope.ALL_APPS -> R.string.cli_route_tor_device
    PrivacyRouteScope.SELECTED_APPS -> R.string.cli_route_tor_apps
}

@Composable
private fun CliLocalProxyControls(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    val surfaces = settings.expert.localSurfaces
    CliDropdownRow(
        label = stringResource(R.string.cli_route_proxy_mode),
        icon = R.drawable.lin_link,
        value = surfaces.proxyMode.name.lowercase(),
        options = ProxySurfaceMode.entries.map { surface ->
            CliDropdownOption(id = surface.name, label = surface.name.lowercase())
        },
        selectedId = surfaces.proxyMode.name,
        onSelect = { id -> viewModel.onProxySurfaceModeSelected(ProxySurfaceMode.valueOf(id)) },
    )
    CliSettingsAnimatedRows(visible = surfaces.proxyMode != ProxySurfaceMode.HTTP) {
        CliProxyPortRow(
            key = "local-socks",
            label = stringResource(R.string.cli_lan_proxy_socks_address),
            port = surfaces.socks.port,
            onPort = { port -> viewModel.onSocksSurfaceChanged(surfaces.socks.copy(port = port)) },
        )
    }
    CliSettingsAnimatedRows(visible = surfaces.proxyMode != ProxySurfaceMode.SOCKS5) {
        CliProxyPortRow(
            key = "local-http",
            label = stringResource(R.string.cli_lan_proxy_http_address),
            port = surfaces.http.port,
            onPort = { port -> viewModel.onHttpSurfaceChanged(surfaces.http.copy(port = port)) },
        )
    }
    CliToggleRow(
        label = stringResource(R.string.cli_route_proxy_auth),
        icon = R.drawable.lin_lock,
        checked = surfaces.auth.enabled,
        onToggle = viewModel::onLocalProxyAuthEnabledChanged,
        infoText = stringResource(R.string.cli_route_proxy_auth_note),
    )
    CliSettingsAnimatedRows(visible = surfaces.auth.enabled) {
        CliInputRow(
            prompt = "user",
            value = surfaces.auth.username,
            onValueChange = { value ->
                viewModel.onLocalProxyAuthChanged(surfaces.auth.copy(username = value.take(64)))
            },
            rowMinHeight = CLI_MENU_ROW_MIN_HEIGHT,
        )
        CliSecretRow(
            prompt = "pass",
            value = surfaces.auth.password,
            clipboardLabel = stringResource(R.string.cli_route_proxy_auth),
            onValueChange = { value ->
                viewModel.onLocalProxyAuthChanged(surfaces.auth.copy(password = value.take(128)))
            },
            rowMinHeight = CLI_MENU_ROW_MIN_HEIGHT,
        )
    }
}

@Composable
private fun CliSplitControls(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    val colors = LocalCliColors.current
    val exclude = settings.expert.perAppRoutingMode == PerAppRoutingMode.EXCLUDE_SELECTED_APPS
    CliDropdownRow(
        label = stringResource(R.string.cli_route_split_kind),
        icon = R.drawable.lin_apps,
        value = stringResource(
            if (exclude) R.string.cli_route_split_exclude else R.string.cli_route_split_include,
        ),
        options = listOf(
            CliDropdownOption(
                id = PerAppRoutingMode.INCLUDE_SELECTED_APPS.name,
                label = stringResource(R.string.cli_route_split_include),
                icon = R.drawable.lin_shield,
                iconTint = colors.vpn,
            ),
            CliDropdownOption(
                id = PerAppRoutingMode.EXCLUDE_SELECTED_APPS.name,
                label = stringResource(R.string.cli_route_split_exclude),
                icon = R.drawable.lin_globe,
                iconTint = colors.dim,
            ),
        ),
        selectedId = settings.expert.perAppRoutingMode.name,
        onSelect = { id ->
            viewModel.onVpnRoutingScenarioSelected(
                if (PerAppRoutingMode.valueOf(id) == PerAppRoutingMode.EXCLUDE_SELECTED_APPS) {
                    VpnRoutingScenario.SELECTED_EXCLUDE
                } else {
                    VpnRoutingScenario.SELECTED_INCLUDE
                },
            )
        },
        showSelectedOptionIcon = true,
    )
}
