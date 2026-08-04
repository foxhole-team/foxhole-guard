package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.TorBridgeTransport
import com.foxhole.guard.R
import com.foxhole.guard.ui.FoxholeUpdatePhase
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onPrivacyRouteBypassVpnTunnelConfigured
import com.foxhole.guard.ui.onTorBridgeManualRefresh
import com.foxhole.guard.ui.onTorBridgeManualRefreshCancel
import com.foxhole.guard.ui.onTorBridgeTransportSelected
import com.foxhole.guard.ui.onTorBridgesAutoUpdateChanged
import com.foxhole.guard.ui.onTorBridgesEnabledChanged
import com.foxhole.guard.ui.onTorBridgesUseFoxholeSourceChanged

private val ROTATE_INTERVAL_MINUTES = listOf(10, 15, 20, 30, 60)

/** Tor sub-screen: the route behaviors and the bridges block, everything live-wired. */
@Composable
internal fun CliTorSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val bridgePhase by viewModel.torBridgeUpdatePhase.collectAsStateWithLifecycle()
    val privacyRoute = state.settings.privacyRoute

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_tor), icon = R.drawable.pix_tor)
        CliPanel(
            title = stringResource(R.string.cli_tor_route_title),
            icon = R.drawable.pix_tor,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CliToggleRow(
                label = stringResource(R.string.cli_tor_bypass_vpn),
                checked = privacyRoute.bypassVpnTunnel,
                onToggle = viewModel::onPrivacyRouteBypassVpnTunnelConfigured,
                note = stringResource(R.string.cli_tor_bypass_vpn_note),
            )
            CliToggleRow(
                label = stringResource(R.string.cli_tor_rotate_exit),
                checked = privacyRoute.autoRotateExit,
                onToggle = viewModel::onPrivacyRouteAutoRotateExitChanged,
            )
            if (privacyRoute.autoRotateExit) {
                CliDropdownRow(
                    label = stringResource(R.string.cli_tor_rotate_interval),
                    value = "${privacyRoute.autoRotateIntervalMinutes}m",
                    options = ROTATE_INTERVAL_MINUTES.map { minutes ->
                        CliDropdownOption(id = minutes.toString(), label = "${minutes}m")
                    },
                    selectedId = privacyRoute.autoRotateIntervalMinutes.toString(),
                    onSelect = { id ->
                        id.toIntOrNull()?.let(viewModel::onPrivacyRouteAutoRotateIntervalSelected)
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliTorBridgesPanel(
            viewModel = viewModel,
            privacyRoute = privacyRoute,
            bridgePhase = bridgePhase,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

@Composable
private fun CliTorBridgesPanel(
    viewModel: HomeViewModel,
    privacyRoute: PrivacyRouteSettings,
    bridgePhase: FoxholeUpdatePhase,
) {
    val colors = LocalCliColors.current
    CliPanel(
        icon = R.drawable.pix_tor,
        title = stringResource(R.string.cli_tor_bridges_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliToggleRow(
            label = stringResource(R.string.cli_tor_bridges),
            checked = privacyRoute.bridgesEnabled,
            onToggle = viewModel::onTorBridgesEnabledChanged,
        )
        if (privacyRoute.bridgesEnabled) {
            CliDropdownRow(
                label = stringResource(R.string.cli_tor_bridge_transport),
                value = privacyRoute.bridgeTransport.name.lowercase(),
                options = TorBridgeTransport.entries.map { transport ->
                    CliDropdownOption(id = transport.name, label = transport.name.lowercase())
                },
                selectedId = privacyRoute.bridgeTransport.name,
                onSelect = { id ->
                    viewModel.onTorBridgeTransportSelected(TorBridgeTransport.valueOf(id))
                },
            )
            CliDropdownRow(
                label = stringResource(R.string.cli_tor_bridge_source),
                value = if (privacyRoute.bridgesUseFoxholeSource) "foxhole" else "torproject",
                options = listOf(
                    CliDropdownOption(id = BRIDGE_SOURCE_TORPROJECT, label = "torproject"),
                    CliDropdownOption(id = BRIDGE_SOURCE_FOXHOLE, label = "foxhole"),
                ),
                selectedId = if (privacyRoute.bridgesUseFoxholeSource) {
                    BRIDGE_SOURCE_FOXHOLE
                } else {
                    BRIDGE_SOURCE_TORPROJECT
                },
                onSelect = { id ->
                    viewModel.onTorBridgesUseFoxholeSourceChanged(id == BRIDGE_SOURCE_FOXHOLE)
                },
            )
            CliToggleRow(
                label = stringResource(R.string.cli_tor_bridges_auto_update),
                checked = privacyRoute.bridgesAutoUpdate,
                onToggle = viewModel::onTorBridgesAutoUpdateChanged,
            )
            val running = bridgePhase == FoxholeUpdatePhase.CHECKING ||
                bridgePhase == FoxholeUpdatePhase.DOWNLOADING ||
                bridgePhase == FoxholeUpdatePhase.VERIFYING
            CliActionRow(
                label = stringResource(R.string.cli_tor_bridges_list),
                value = when {
                    running -> bridgePhase.name.lowercase()
                    privacyRoute.bridgesUpdatedAt != null ->
                        CliFormat.clock(privacyRoute.bridgesUpdatedAt ?: 0L)
                    else -> stringResource(R.string.cli_common_never)
                },
                onTap = {
                    if (running) {
                        viewModel.onTorBridgeManualRefreshCancel()
                    } else {
                        viewModel.onTorBridgeManualRefresh()
                    }
                },
            )
            if (bridgePhase == FoxholeUpdatePhase.FAILED) {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.cli_common_failed),
                    style = com.foxhole.guard.ui.cli.CliType.small,
                    color = colors.err,
                )
            }
        }
    }
}

private const val BRIDGE_SOURCE_TORPROJECT = "torproject"
private const val BRIDGE_SOURCE_FOXHOLE = "foxhole"
