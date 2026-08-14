package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.torScopeRunnable
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.VpnRoutingScenario
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onHttpSurfaceChanged
import com.foxhole.guard.ui.onLocalProxyAuthChanged
import com.foxhole.guard.ui.onLocalProxyAuthEnabledChanged
import com.foxhole.guard.ui.onPrivacyRouteBlockAppsWhenTorUnavailableChanged
import com.foxhole.guard.ui.onPrivacyRouteScopeSelected
import com.foxhole.guard.ui.onProxySurfaceModeSelected
import com.foxhole.guard.ui.onSocksSurfaceChanged
import com.foxhole.guard.ui.onVpnRoutingScenarioSelected

/**
 * The three answers to "what does the VPN do for this device".
 *
 * The first two ride the FoxCore TUN and differ only in who is captured. The third has no tun at
 * all: the core runs as a proxy on loopback and serves the apps that point at it — Android sharing
 * the connection with itself rather than the tunnel swallowing it.
 */
private enum class CliVpnConn { WHOLE_DEVICE, SELECTED_APPS, PROXY_SERVER }

/**
 * The "app traffic routing" window: two ordered sections inside one CLI panel —
 * 1. VPN connection (whole device / selected apps / local proxy server) with the include/exclude
 *    sub-choice, a link-styled note whose text tracks the picked mode, and — for the proxy — its
 *    surface, port and authentication;
 * 2. Tor (whole device / selected apps) plus the fail-closed "block without Tor" guard.
 */
@Composable
internal fun CliVpnModeSection(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    val colors = LocalCliColors.current
    // The scenario is the loopback listener being up, NOT TrafficMode.PROXY: that mode meant a
    // session with no tun and was retired with the proxy-only service (settings normalisation still
    // migrates a stored value back). What the owner asked for is a tunnel that also serves this
    // phone at 127.0.0.1, and that is exactly a named loopback inbound beside the tun.
    val conn = when {
        settings.expert.localSurfaces.http.enabled -> CliVpnConn.PROXY_SERVER
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
            onSelect = { id -> applyVpnConn(viewModel, CliVpnConn.valueOf(id), settings) },
            showSelectedOptionIcon = true,
        )
        // Selected apps: pick the split direction; the note below explains it and re-reads on change.
        if (conn == CliVpnConn.SELECTED_APPS) {
            CliSplitControls(viewModel, settings)
        }
        // Link-styled explanatory line — its text tracks the connection and split direction. It sits
        // directly under the control it explains: below the proxy block it read as a note about the
        // proxy, which is not what it says.
        CliElbowLine(text = stringResource(vpnConnNote(conn, settings)), color = colors.note)
        // Only the device-local proxy lives here. Sharing the tunnel with the Wi-Fi is a different
        // surface with its own screen (extras → proxy server): this one publishes nothing outside
        // the phone.
        if (conn == CliVpnConn.PROXY_SERVER) {
            CliLocalProxyControls(viewModel, settings)
        }

        // The quiet stitch between the two managements: VPN above, Tor below — the panel is one,
        // but the axes are independent and the eye needs the boundary.
        CliRowDivider(modifier = Modifier.padding(vertical = CliSpacing.xs))
        // Section 2 — Tor: whole device or selected apps, plus the fail-closed "block without Tor" guard.
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
            onSelect = { id -> viewModel.onPrivacyRouteScopeSelected(PrivacyRouteScope.valueOf(id)) },
            showSelectedOptionIcon = true,
        )
        // Selected-apps Tor with an empty TOR lane can carry nothing yet — surface inline the same
        // "choose apps first" guard the Home terminal prints, so the routing screen explains itself.
        if (!settings.torScopeRunnable()) {
            CliElbowLine(
                text = stringResource(R.string.privacy_route_select_apps_first),
                color = colors.note,
            )
        }
        CliToggleRow(
            label = stringResource(R.string.cli_route_tor_block_without),
            icon = R.drawable.pix_forbidden,
            checked = settings.privacyRoute.blockAppsWhenTorUnavailable,
            onToggle = { value -> viewModel.onPrivacyRouteBlockAppsWhenTorUnavailableChanged(value) },
            note = stringResource(R.string.cli_route_tor_block_without_note),
        )
    }
}

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

/**
 * The explanatory note under the VPN connection dropdown. Whole device tunnels everything (no
 * exceptions); selected apps reads include- or exclude-first from the split direction.
 */
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
    // VPN reach and the Tor lane are independent axes, so this control writes only the VPN one.
    // Going through RoutingModePreset.VPN forced privacyRoute to OFF, which made "VPN over the
    // whole device + Tor for selected apps" impossible to express: picking the VPN reach silently
    // switched Tor off.
    // One typed action owns both the local listener and the underlying tunnel reach. This prevents
    // the UI/runtime from observing "proxy already disabled, old scenario still active" and lets
    // the atomic-application switch gate every scenario through the same bottom sheet.
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

/**
 * The device-local proxy: which surface it speaks, on which port, and whether it asks for
 * credentials.
 *
 * This is the scenario where the VPN serves Android itself instead of capturing it — apps that know
 * the address use it, everything else keeps going out as before. Authentication is a real switch
 * here, unlike on the LAN surface: this listener is on loopback, reachable only from this device,
 * so an anonymous one is a choice about the apps on the phone rather than about the Wi-Fi.
 */
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
        note = stringResource(R.string.cli_route_proxy_auth_note),
    )
    if (surfaces.auth.enabled) {
        CliInputRow(
            prompt = "user",
            value = surfaces.auth.username,
            onValueChange = { value ->
                viewModel.onLocalProxyAuthChanged(surfaces.auth.copy(username = value.take(64)))
            },
        )
        CliInputRow(
            prompt = "pass",
            value = surfaces.auth.password,
            password = true,
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
