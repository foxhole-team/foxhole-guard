package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
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
import com.foxhole.guard.ui.onMixedSurfaceChanged
import com.foxhole.guard.ui.onSocksSurfaceChanged

/**
 * The LAN proxy as its own extras screen: it shares the same TUN's socks5/http/mixed surface with
 * the current Wi-Fi network. Authentication is mandatory and has no off switch — without a password
 * the LAN inbound does not come up at all (fail-closed, see RuntimeLocalSurface). The client-to-phone
 * hop gets no extra encryption, and the screen says so plainly.
 */
@Composable
internal fun CliLanProxySubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val lan = state.settings.expert.localSurfaces
    val colors = LocalCliColors.current

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(
            label = stringResource(R.string.cli_route_lan_proxy),
            icon = R.drawable.pix_device,
            trailing = { CliContextHelpButton(bodyRes = R.string.cli_help_lan_proxy_body) },
        )
        CliPanel(
            title = stringResource(R.string.cli_route_lan_proxy),
            icon = R.drawable.pix_link,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CliToggleRow(
                label = stringResource(R.string.cli_lan_proxy_enable),
                checked = lan.allowLanAccess,
                onToggle = viewModel::onLocalProxyLanAccessChanged,
                note = stringResource(R.string.cli_lan_proxy_trusted_note),
            )
            if (lan.allowLanAccess) {
                CliDropdownRow(
                    label = stringResource(R.string.cli_route_lan_mode),
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
            CliLanProxyAuthPanel(viewModel = viewModel, lan = lan)
        }
        Text(
            text = stringResource(R.string.cli_lan_proxy_compat_note),
            style = CliType.small,
            color = colors.dim,
            modifier = Modifier.padding(top = CliSpacing.sm, bottom = CliSpacing.md),
        )
    }
}

/**
 * SOCKS5/HTTP username+password. Auth is MANDATORY — there is deliberately no off switch here: the
 * LAN leg is published on the phone's Wi-Fi address, so an anonymous surface would relay the owner's
 * VPN/Tor for anyone on that network. The login/password stay editable; if the password is left
 * empty the runtime refuses to raise the LAN inbound at all, and the panel says so instead of
 * letting the user believe the surface is up. The password row copies to the clipboard.
 */
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
    ) {
        Text(
            text = stringResource(R.string.cli_lan_proxy_auth_required_note),
            style = CliType.small,
            color = colors.dim,
            modifier = Modifier.padding(bottom = CliSpacing.xs),
        )
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
            onTap = viewModel::onCopyLanProxyPassword,
            enabled = lan.lanAuth.password.isNotBlank(),
        )
    }
}

/** The port has no meaningful presets: the row opens the value modal straight away. */
@Composable
private fun CliLanPortRow(
    viewModel: HomeViewModel,
    lan: LocalSurfaceSettings,
) {
    val surface = when (lan.lanProxyMode) {
        ProxySurfaceMode.SOCKS5 -> lan.socks
        ProxySurfaceMode.HTTP -> lan.http
        ProxySurfaceMode.ALL -> lan.mixed
    }
    var customOpen by rememberSaveable(lan.lanProxyMode) { mutableStateOf(false) }
    var portText by rememberSaveable(lan.lanProxyMode) { mutableStateOf("") }
    CliDropdownRow(
        label = stringResource(R.string.cli_route_lan_port),
        value = surface.port.toString(),
        options = listOf(
            CliDropdownOption(id = CLI_OPT_CUSTOM, label = stringResource(R.string.cli_common_custom)),
        ),
        selectedId = null,
        onSelect = { customOpen = true },
    )
    if (customOpen) {
        CliInputModal(
            title = stringResource(R.string.cli_input_value_title),
            prompt = "port",
            value = portText,
            onValueChange = { raw -> portText = raw.filter(Char::isDigit).take(5) },
            onSubmit = {
                portText.toIntOrNull()?.coerceIn(LAN_MIN_PORT, LAN_MAX_PORT)?.let { port ->
                    val next = surface.copy(port = port)
                    when (lan.lanProxyMode) {
                        ProxySurfaceMode.SOCKS5 -> viewModel.onSocksSurfaceChanged(next)
                        ProxySurfaceMode.HTTP -> viewModel.onHttpSurfaceChanged(next)
                        ProxySurfaceMode.ALL -> viewModel.onMixedSurfaceChanged(next)
                    }
                    customOpen = false
                }
            },
            onDismiss = { customOpen = false },
            numeric = true,
        )
    }
}

private const val LAN_MIN_PORT = 1024
private const val LAN_MAX_PORT = 65535
