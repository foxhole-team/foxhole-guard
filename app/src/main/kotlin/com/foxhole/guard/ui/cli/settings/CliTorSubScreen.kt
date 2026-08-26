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
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.TorBridgeTransport
import com.foxhole.guard.R
import com.foxhole.guard.runtime.RemoteDownloadProgress
import com.foxhole.guard.ui.FoxholeUpdatePhase
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.TorBridgePersistenceMarker
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliSheetAction
import com.foxhole.guard.ui.cli.components.CliSheetActionsRow
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.isRunning
import com.foxhole.guard.ui.onAtomicConnectionChanged
import com.foxhole.guard.ui.onPrivacyRouteBypassVpnTunnelConfigured
import com.foxhole.guard.ui.onTorBridgeManualRefresh
import com.foxhole.guard.ui.onTorBridgeManualRefreshCancel
import com.foxhole.guard.ui.onTorBridgeTransportSelected
import com.foxhole.guard.ui.onTorBridgesAutoUpdateChanged
import com.foxhole.guard.ui.onTorBridgesEnabledChanged
import com.foxhole.guard.ui.onTorBridgesUseFoxholeSourceChanged
import com.foxhole.guard.ui.torBridgeDownloadProgress
import com.foxhole.guard.ui.torBridgePersistenceMarker
import com.foxhole.guard.ui.torBridgeRefreshRequired
import com.foxhole.guard.ui.torBridgeVerifiedSuccess

private val ROTATE_INTERVAL_MINUTES = listOf(10, 15, 20, 30, 60)

@Composable
internal fun CliTorSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val bridgePhase by viewModel.torBridgeUpdatePhase.collectAsStateWithLifecycle()
    val bridgeProgress by viewModel.torBridgeDownloadProgress.collectAsStateWithLifecycle()
    val privacyRoute = state.settings.privacyRoute

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_tor), icon = R.drawable.lin_tor)

        Column(

            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalCliBottomChromeClearance.current),

        ) {
            CliPanel(
                title = stringResource(R.string.cli_tor_route_title),
                icon = R.drawable.lin_tor,
                modifier = Modifier.fillMaxWidth(),
            ) {
                CliSettingsAnimatedRows(visible = !privacyRoute.permitted) {
                    CliToggleRow(
                        label = stringResource(R.string.cli_cfg_atomic_connection),
                        icon = R.drawable.lin_shield,
                        checked = state.settings.connection.atomicConnection,
                        onToggle = viewModel::onAtomicConnectionChanged,
                        infoText = stringResource(R.string.cli_cfg_atomic_connection_note),
                    )
                }
                CliToggleRow(
                    label = stringResource(R.string.cli_tor_bypass_vpn),
                    icon = R.drawable.lin_export,
                    checked = privacyRoute.bypassVpnTunnel,
                    onToggle = viewModel::onPrivacyRouteBypassVpnTunnelConfigured,
                    infoText = stringResource(R.string.cli_tor_bypass_vpn_note),
                )
                CliToggleRow(
                    label = stringResource(R.string.cli_tor_rotate_exit),
                    icon = R.drawable.lin_restart,
                    checked = privacyRoute.autoRotateExit,
                    onToggle = viewModel::onPrivacyRouteAutoRotateExitChanged,
                )
                CliSettingsAnimatedRows(visible = privacyRoute.autoRotateExit) {
                    CliDropdownRow(
                        label = stringResource(R.string.cli_tor_rotate_interval),
                        icon = R.drawable.lin_clock,
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
                bridgeProgress = bridgeProgress,
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
    }
}

@Composable
private fun CliBridgesToggleRow(
    viewModel: HomeViewModel,
    privacyRoute: PrivacyRouteSettings,
    onRefreshRequired: () -> Unit,
) {
    CliToggleRow(
        label = stringResource(R.string.cli_tor_bridges),
        icon = R.drawable.lin_link,
        checked = privacyRoute.bridgesEnabled,
        onToggle = { value ->
            viewModel.onTorBridgesEnabledChanged(value)
            if (value && torBridgeRefreshRequired(privacyRoute.copy(bridgesEnabled = true))) {
                onRefreshRequired()
            }
        },
    )
}

@Composable
private fun CliTorBridgesPanel(
    viewModel: HomeViewModel,
    privacyRoute: PrivacyRouteSettings,
    bridgePhase: FoxholeUpdatePhase,
    bridgeProgress: RemoteDownloadProgress?,
) {
    val colors = LocalCliColors.current
    var updateSheetOpen by rememberSaveable { mutableStateOf(false) }
    CliPanel(
        icon = R.drawable.lin_tor,
        title = stringResource(R.string.cli_tor_bridges_title),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliBridgesToggleRow(
            viewModel = viewModel,
            privacyRoute = privacyRoute,
            onRefreshRequired = { updateSheetOpen = true },
        )
        CliSettingsAnimatedRows(visible = privacyRoute.bridgesEnabled) {
            CliDropdownRow(
                label = stringResource(R.string.cli_tor_bridge_transport),
                icon = R.drawable.lin_shield,
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
                icon = R.drawable.lin_globe,
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
                icon = R.drawable.lin_update,
                checked = privacyRoute.bridgesAutoUpdate,
                onToggle = viewModel::onTorBridgesAutoUpdateChanged,
            )
            CliSettingsAnimatedRows(
                visible = torBridgeRefreshActionVisible(privacyRoute, bridgePhase),
            ) {
                val failed = bridgePhase == FoxholeUpdatePhase.FAILED ||
                    privacyRoute.bridgesLastUpdateSuccess == false
                CliActionRow(
                    label = stringResource(R.string.cli_tor_bridges_list),
                    icon = R.drawable.lin_update,
                    value = when {
                        bridgePhase.isRunning -> stringResource(bridgePhase.verifiedUpdateProgressLabelRes())
                        failed -> stringResource(R.string.cli_common_failed)
                        else -> stringResource(R.string.cli_foxdb_status_required)
                    },
                    actionColor = if (failed) colors.err else colors.accent,
                    onTap = { updateSheetOpen = true },
                )
            }
        }
    }
    if (updateSheetOpen) {
        CliTorBridgeUpdateSheet(
            viewModel = viewModel,
            privacyRoute = privacyRoute,
            phase = bridgePhase,
            downloadProgress = bridgeProgress,
            onDismiss = { updateSheetOpen = false },
        )
    }
}

internal fun torBridgeRefreshActionVisible(
    settings: PrivacyRouteSettings,
    phase: FoxholeUpdatePhase,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (!settings.permitted || !settings.bridgesEnabled) return false
    return phase.isRunning || phase == FoxholeUpdatePhase.FAILED ||
        torBridgeRefreshRequired(settings, nowMs)
}

@Composable
private fun CliTorBridgeUpdateSheet(
    viewModel: HomeViewModel,
    privacyRoute: PrivacyRouteSettings,
    phase: FoxholeUpdatePhase,
    downloadProgress: RemoteDownloadProgress?,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    val initialMarker = privacyRoute.torBridgePersistenceMarker()
    var baselineCheckedAt by rememberSaveable { mutableStateOf(initialMarker.checkedAt) }
    var baselineUpdatedAt by rememberSaveable { mutableStateOf(initialMarker.updatedAt) }
    var baselineSuccess by rememberSaveable { mutableStateOf(initialMarker.lastUpdateSuccess) }
    val baseline = TorBridgePersistenceMarker(
        checkedAt = baselineCheckedAt,
        updatedAt = baselineUpdatedAt,
        lastUpdateSuccess = baselineSuccess,
    )
    val verifiedSuccess = torBridgeVerifiedSuccess(phase, privacyRoute, baseline)
    val failed = phase == FoxholeUpdatePhase.FAILED ||
        (!phase.isRunning && privacyRoute.bridgesLastUpdateSuccess == false)
    val dismiss = {
        if (phase.isRunning) viewModel.onTorBridgeManualRefreshCancel()
        onDismiss()
    }
    val startRefresh = {
        val marker = privacyRoute.torBridgePersistenceMarker()
        baselineCheckedAt = marker.checkedAt
        baselineUpdatedAt = marker.updatedAt
        baselineSuccess = marker.lastUpdateSuccess
        viewModel.onTorBridgeManualRefresh()
    }
    CliBottomSheet(
        title = stringResource(R.string.cli_tor_bridges_update_title),
        icon = R.drawable.lin_update,
        onDismiss = dismiss,
        autoDismissAfterMillis = BRIDGE_SUCCESS_AUTO_DISMISS_MS.takeIf { verifiedSuccess },
    ) {
        when {
            phase.isRunning -> {
                CliVerifiedUpdateProgress(
                    phase = phase,
                    downloadProgress = downloadProgress,
                    verifiedSuccess = false,
                )
                Spacer(modifier = Modifier.height(CliSpacing.md))
                CliSheetActionsRow(actions = emptyList())
            }
            verifiedSuccess -> {
                CliVerifiedUpdateProgress(
                    phase = phase,
                    downloadProgress = downloadProgress,
                    verifiedSuccess = true,
                )
            }
            phase == FoxholeUpdatePhase.DONE || phase == FoxholeUpdatePhase.NO_UPDATE -> {
                CliVerifiedUpdateProgress(
                    phase = phase,
                    downloadProgress = downloadProgress,
                    verifiedSuccess = false,
                )
            }
            failed -> {
                Text(
                    text = stringResource(R.string.cli_tor_bridges_update_failed),
                    style = CliType.body,
                    color = colors.err,
                )
                Spacer(modifier = Modifier.height(CliSpacing.md))
                CliSheetActionsRow(
                    actions = listOf(
                        CliSheetAction(
                            label = stringResource(R.string.cli_tor_bridges_update_retry),
                            onClick = startRefresh,
                        ),
                    ),
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.cli_tor_bridges_update_body),
                    style = CliType.body,
                    color = colors.fg,
                )
                Spacer(modifier = Modifier.height(CliSpacing.md))
                CliSheetActionsRow(
                    actions = listOf(
                        CliSheetAction(
                            label = stringResource(R.string.cli_tor_bridges_update_run),
                            onClick = startRefresh,
                        ),
                    ),
                )
            }
        }
    }
}

internal fun torBridgeStageProgress(
    phase: FoxholeUpdatePhase,
    verifiedSuccess: Boolean,
): Int? = verifiedUpdateStageProgress(phase, verifiedSuccess)

private const val BRIDGE_SOURCE_TORPROJECT = "torproject"
private const val BRIDGE_SOURCE_FOXHOLE = "foxhole"
private const val BRIDGE_SUCCESS_AUTO_DISMISS_MS = 900L
