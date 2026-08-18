package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
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

private enum class CliVpnConn { WHOLE_DEVICE, SELECTED_APPS, PROXY_SERVER }

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
        icon = R.drawable.pix_globe,
        title = stringResource(R.string.cli_route_apps_traffic_title),
        modifier = Modifier.fillMaxWidth(),
        infoText = stringResource(R.string.cli_help_routing_body),
    ) {
        CliDropdownRow(
            label = stringResource(R.string.cli_route_vpn_conn),
            icon = R.drawable.pix_shield,
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
        if (conn == CliVpnConn.SELECTED_APPS) {
            CliSplitControls(viewModel, settings)
        }
        if (conn == CliVpnConn.PROXY_SERVER) {
            CliLocalProxyControls(viewModel, settings)
        }

        CliRowDivider(modifier = Modifier.padding(vertical = CliSpacing.xs))
        CliDropdownRow(
            label = stringResource(R.string.cli_route_tor_conn),
            icon = R.drawable.pix_tor,
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
        CliRowDivider(modifier = Modifier.padding(vertical = CliSpacing.xs))
        CliToggleRow(
            label = stringResource(R.string.cli_route_tor_block_without),
            icon = R.drawable.pix_forbidden,
            checked = settings.privacyRoute.blockAppsWhenTorUnavailable,
            onToggle = { value -> viewModel.onPrivacyRouteBlockAppsWhenTorUnavailableChanged(value) },
            infoText = stringResource(R.string.cli_route_tor_block_without_note),
        )
    }
}

private fun vpnConnNeedsApps(conn: CliVpnConn): Boolean = conn != CliVpnConn.WHOLE_DEVICE

private fun vpnConnLabel(conn: CliVpnConn): Int = when (conn) {
    CliVpnConn.WHOLE_DEVICE -> R.string.cli_route_vpn_whole_device
    CliVpnConn.SELECTED_APPS -> R.string.cli_route_vpn_selected
    CliVpnConn.PROXY_SERVER -> R.string.cli_route_vpn_proxy_server
}

private fun vpnConnIcon(conn: CliVpnConn): Int = when (conn) {
    CliVpnConn.WHOLE_DEVICE -> R.drawable.pix_shield
    CliVpnConn.SELECTED_APPS -> R.drawable.pix_apps
    CliVpnConn.PROXY_SERVER -> R.drawable.pix_device
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
        icon = R.drawable.pix_link,
        value = surfaces.proxyMode.name.lowercase(),
        options = ProxySurfaceMode.entries.map { surface ->
            CliDropdownOption(id = surface.name, label = surface.name.lowercase())
        },
        selectedId = surfaces.proxyMode.name,
        onSelect = { id -> viewModel.onProxySurfaceModeSelected(ProxySurfaceMode.valueOf(id)) },
    )
    if (surfaces.proxyMode != ProxySurfaceMode.HTTP) {
        CliProxyPortRow(
            key = "local-socks",
            label = stringResource(R.string.cli_lan_proxy_socks_address),
            port = surfaces.socks.port,
            onPort = { port -> viewModel.onSocksSurfaceChanged(surfaces.socks.copy(port = port)) },
        )
    }
    if (surfaces.proxyMode != ProxySurfaceMode.SOCKS5) {
        CliProxyPortRow(
            key = "local-http",
            label = stringResource(R.string.cli_lan_proxy_http_address),
            port = surfaces.http.port,
            onPort = { port -> viewModel.onHttpSurfaceChanged(surfaces.http.copy(port = port)) },
        )
    }
    CliToggleRow(
        label = stringResource(R.string.cli_route_proxy_auth),
        icon = R.drawable.pix_lock,
        checked = surfaces.auth.enabled,
        onToggle = viewModel::onLocalProxyAuthEnabledChanged,
        infoText = stringResource(R.string.cli_route_proxy_auth_note),
    )
    if (surfaces.auth.enabled) {
        CliInputRow(
            prompt = "user",
            value = surfaces.auth.username,
            onValueChange = { value ->
                viewModel.onLocalProxyAuthChanged(surfaces.auth.copy(username = value.take(64)))
            },
        )
        CliSecretRow(
            prompt = "pass",
            value = surfaces.auth.password,
            clipboardLabel = stringResource(R.string.cli_route_proxy_auth),
            onValueChange = { value ->
                viewModel.onLocalProxyAuthChanged(surfaces.auth.copy(password = value.take(128)))
            },
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
        icon = R.drawable.pix_apps,
        value = stringResource(
            if (exclude) R.string.cli_route_split_exclude else R.string.cli_route_split_include,
        ),
        options = listOf(
            CliDropdownOption(
                id = PerAppRoutingMode.INCLUDE_SELECTED_APPS.name,
                label = stringResource(R.string.cli_route_split_include),
                icon = R.drawable.pix_shield,
                iconTint = colors.vpn,
            ),
            CliDropdownOption(
                id = PerAppRoutingMode.EXCLUDE_SELECTED_APPS.name,
                label = stringResource(R.string.cli_route_split_exclude),
                icon = R.drawable.pix_globe,
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
