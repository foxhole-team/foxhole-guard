package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.LanProxyPhase
import com.foxhole.core.model.LanProxyStatusSnapshot
import com.foxhole.core.model.LanProxyUnavailableReason
import com.foxhole.core.model.LanProxyUpstream
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.serving
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliContextHelpButton
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliInputModal
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onCopyLanProxyPassword
import com.foxhole.guard.ui.onHttpSurfaceChanged
import com.foxhole.guard.ui.onLanProxyAuthChanged
import com.foxhole.guard.ui.onLanProxySurfaceModeSelected
import com.foxhole.guard.ui.onLocalProxyLanAccessChanged
import com.foxhole.guard.ui.onSocksSurfaceChanged

@Composable
internal fun CliLanProxySubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val lanStatus by viewModel.lanProxyStatus.collectAsStateWithLifecycle()
    val lan = state.settings.expert.localSurfaces
    val colors = LocalCliColors.current

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(
            label = stringResource(R.string.cli_route_lan_proxy),
            icon = R.drawable.pix_device,
            trailing = { CliContextHelpButton(bodyRes = R.string.cli_help_lan_proxy_body) },
        )

        Column(

            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalCliBottomChromeClearance.current),

        ) {
            CliPanel(
                title = stringResource(R.string.cli_route_lan_proxy),
                icon = R.drawable.pix_link,
                modifier = Modifier.fillMaxWidth(),
                infoText = stringResource(R.string.cli_lan_proxy_compat_note),
            ) {
                CliToggleRow(
                    label = stringResource(R.string.cli_lan_proxy_enable),
                    icon = R.drawable.pix_device,
                    checked = lan.allowLanAccess,
                    onToggle = viewModel::onLocalProxyLanAccessChanged,
                    infoText = stringResource(R.string.cli_lan_proxy_trusted_note),
                )
                if (lan.allowLanAccess) {
                    CliDropdownRow(
                        label = stringResource(R.string.cli_route_lan_mode),
                        icon = R.drawable.pix_link,
                        value = lan.lanProxyMode.name.lowercase(),
                        options = ProxySurfaceMode.entries.map { surface ->
                            CliDropdownOption(id = surface.name, label = surface.name.lowercase())
                        },
                        selectedId = lan.lanProxyMode.name,
                        onSelect = { id -> viewModel.onLanProxySurfaceModeSelected(ProxySurfaceMode.valueOf(id)) },
                    )
                    CliLanPortRow(viewModel = viewModel, lan = lan)
                }
            }
            if (lan.allowLanAccess) {
                Spacer(modifier = Modifier.height(CliSpacing.sm))
                CliLanProxyStatusPanel(status = lanStatus)
                Spacer(modifier = Modifier.height(CliSpacing.sm))
                CliLanProxyAuthPanel(viewModel = viewModel, lan = lan)
            }
            Spacer(modifier = Modifier.height(CliSpacing.md))
        }
    }
}

@Composable
private fun CliLanProxyAuthPanel(
    viewModel: HomeViewModel,
    lan: LocalSurfaceSettings,
) {
    val colors = LocalCliColors.current
    CliPanel(
        icon = R.drawable.pix_lock,
        title = stringResource(R.string.cli_lan_proxy_auth),
        modifier = Modifier.fillMaxWidth(),
        infoText = stringResource(R.string.cli_lan_proxy_auth_required_note),
    ) {
        CliInputRow(
            prompt = "user",
            value = lan.lanAuth.username,
            onValueChange = { value ->
                viewModel.onLanProxyAuthChanged(lan.lanAuth.copy(username = value.take(64)))
            },
        )
        CliInputRow(
            prompt = "pass",
            value = lan.lanAuth.password,
            password = true,
            onValueChange = { value ->
                viewModel.onLanProxyAuthChanged(lan.lanAuth.copy(password = value.take(128)))
            },
        )
        if (lan.lanAuth.password.isBlank()) {
            Text(
                text = stringResource(R.string.cli_lan_proxy_pass_required_warn),
                style = CliType.small,
                color = colors.warn,
                modifier = Modifier.padding(bottom = CliSpacing.xs),
            )
        }
        CliActionRow(
            label = stringResource(R.string.cli_lan_proxy_copy_pass),
            icon = R.drawable.pix_copy,
            onTap = viewModel::onCopyLanProxyPassword,
            enabled = lan.lanAuth.password.isNotBlank(),
        )
    }
}

@Composable
private fun CliLanProxyStatusPanel(status: LanProxyStatusSnapshot) {
    val colors = LocalCliColors.current
    CliPanel(
        icon = R.drawable.pix_link,
        title = stringResource(R.string.cli_lan_proxy_status),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliLanProxyValueRow(
            label = stringResource(R.string.cli_lan_proxy_state),
            value = stringResource(status.phase.labelRes),
            valueColor = if (status.phase.serving) colors.ok else colors.warn,
        )
        status.upstream?.let { upstream ->
            CliLanProxyValueRow(
                label = stringResource(R.string.cli_lan_proxy_upstream),
                value = stringResource(upstream.labelRes),
            )
        }
        status.socksAddress?.let { address ->
            CliLanProxyValueRow(label = stringResource(R.string.cli_lan_proxy_socks_address), value = address)
        }
        status.httpAddress?.let { address ->
            CliLanProxyValueRow(label = stringResource(R.string.cli_lan_proxy_http_address), value = address)
        }
        status.reason?.let { reason ->
            Text(
                text = stringResource(reason.labelRes),
                style = CliType.small,
                color = colors.warn,
                modifier = Modifier.padding(top = CliSpacing.xs),
            )
        }
    }
}

@Composable
private fun CliLanProxyValueRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = CliType.body, color = colors.dim)
        Text(
            text = value,
            style = CliType.body,
            color = if (valueColor == Color.Unspecified) colors.fg else valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val LanProxyPhase.labelRes: Int
    get() =
        when (this) {
            LanProxyPhase.OFF -> R.string.cli_lan_proxy_state_off
            LanProxyPhase.ARMING -> R.string.cli_lan_proxy_state_arming
            LanProxyPhase.READY -> R.string.cli_lan_proxy_state_ready
            LanProxyPhase.DEGRADED -> R.string.cli_lan_proxy_state_degraded
            LanProxyPhase.NETWORK_LOST -> R.string.cli_lan_proxy_state_network_lost
            LanProxyPhase.FAILED -> R.string.cli_lan_proxy_state_failed
            LanProxyPhase.UNAVAILABLE -> R.string.cli_lan_proxy_state_unavailable
        }

private val LanProxyUpstream.labelRes: Int
    get() =
        when (this) {
            LanProxyUpstream.VPN -> R.string.cli_lan_proxy_upstream_vpn
            LanProxyUpstream.TOR -> R.string.cli_lan_proxy_upstream_tor
            LanProxyUpstream.MIXED -> R.string.cli_lan_proxy_upstream_mixed
        }

private val LanProxyUnavailableReason.labelRes: Int
    get() =
        when (this) {
            LanProxyUnavailableReason.NO_SESSION -> R.string.cli_lan_proxy_reason_no_session
            LanProxyUnavailableReason.NO_WIFI -> R.string.cli_lan_proxy_reason_no_wifi
            LanProxyUnavailableReason.NO_CREDENTIALS -> R.string.cli_lan_proxy_reason_no_credentials
            LanProxyUnavailableReason.PACKET_TUNNEL -> R.string.cli_lan_proxy_reason_packet_tunnel
            LanProxyUnavailableReason.NETWORK_REFUSED -> R.string.cli_lan_proxy_reason_network_refused
            LanProxyUnavailableReason.BIND_FAILED -> R.string.cli_lan_proxy_reason_bind_failed
            LanProxyUnavailableReason.CORE_UNSUPPORTED -> R.string.cli_lan_proxy_reason_core_unsupported
            LanProxyUnavailableReason.UNKNOWN -> R.string.cli_lan_proxy_reason_unknown
        }

@Composable
private fun CliLanPortRow(
    viewModel: HomeViewModel,
    lan: LocalSurfaceSettings,
) {
    if (lan.lanProxyMode != ProxySurfaceMode.HTTP) {
        CliProxyPortRow(
            key = "socks",
            label = stringResource(R.string.cli_lan_proxy_socks_address),
            port = lan.socks.port,
            onPort = { port -> viewModel.onSocksSurfaceChanged(lan.socks.copy(port = port)) },
        )
    }
    if (lan.lanProxyMode != ProxySurfaceMode.SOCKS5) {
        CliProxyPortRow(
            key = "http",
            label = stringResource(R.string.cli_lan_proxy_http_address),
            port = lan.http.port,
            onPort = { port -> viewModel.onHttpSurfaceChanged(lan.http.copy(port = port)) },
        )
    }
}

@Composable
internal fun CliProxyPortRow(
    key: String,
    label: String,
    port: Int,
    onPort: (Int) -> Unit,
) {
    var customOpen by rememberSaveable(key) { mutableStateOf(false) }
    var portText by rememberSaveable(key) { mutableStateOf("") }
    CliDropdownRow(
        label = "${stringResource(R.string.cli_route_lan_port)} · $label",
        icon = R.drawable.pix_link,
        value = port.toString(),
        options = listOf(
            CliDropdownOption(id = CLI_OPT_CUSTOM, label = stringResource(R.string.cli_common_custom)),
        ),
        selectedId = null,
        onSelect = { customOpen = true },
    )
    if (customOpen) {
        CliInputModal(
            title = stringResource(R.string.cli_input_value_title),
            icon = R.drawable.pix_link,
            prompt = "port",
            value = portText,
            onValueChange = { raw -> portText = raw.filter(Char::isDigit).take(5) },
            onSubmit = {
                portText.toIntOrNull()?.coerceIn(LAN_MIN_PORT, LAN_MAX_PORT)?.let { value ->
                    onPort(value)
                    customOpen = false
                }
            },
            onDismiss = { customOpen = false },
            numeric = true,
        )
    }
}

internal const val LAN_MIN_PORT = 1024
internal const val LAN_MAX_PORT = 65535
