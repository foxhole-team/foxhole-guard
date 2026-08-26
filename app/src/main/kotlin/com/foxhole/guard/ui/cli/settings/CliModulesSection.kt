package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.Settings
import com.foxhole.guard.R
import com.foxhole.guard.ui.DatasetActivationFeature
import com.foxhole.guard.ui.DatasetActivationSource
import com.foxhole.guard.ui.DatasetActivationState
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.TorBridgePersistenceMarker
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliConfirmSheet
import com.foxhole.guard.ui.cli.components.CliDivider
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.onAnomalyEnabledChanged
import com.foxhole.guard.ui.onI2pEnabledChanged
import com.foxhole.guard.ui.onThreatIntelManualRefresh
import com.foxhole.guard.ui.onThreatIntelManualRefreshCancel
import com.foxhole.guard.ui.onTorBridgeManualRefresh
import com.foxhole.guard.ui.onTorBridgeManualRefreshCancel
import com.foxhole.guard.ui.onTorPermissionWithBridgeChoice
import com.foxhole.guard.ui.onTorRoutePermittedChanged
import com.foxhole.guard.ui.threatIntelDownloadProgress
import com.foxhole.guard.ui.threatIntelInstalledGeneratedAt
import com.foxhole.guard.ui.threatIntelUpdatePhase
import com.foxhole.guard.ui.threatIntelVerifiedSuccess
import com.foxhole.guard.ui.torBridgeDownloadProgress
import com.foxhole.guard.ui.torBridgePersistenceMarker
import com.foxhole.guard.ui.torBridgeVerifiedSuccess

@Composable
internal fun CliModulesSection(
    viewModel: HomeViewModel,
    settings: Settings,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenTor: () -> Unit,
    onOpenI2p: () -> Unit,
    onOpenFirewall: () -> Unit,
    onOpenAnomaly: () -> Unit,
) {
    val colors = LocalCliColors.current
    val firewallActionRequired = settings.expert.pendingQuarantinePackages.isNotEmpty()
    var torActivation by remember { mutableStateOf<DatasetActivationState?>(null) }
    var sentinelActivation by remember { mutableStateOf<DatasetActivationState?>(null) }
    CliPanel(
        title = stringResource(R.string.cli_cfg_group_privacy),
        icon = R.drawable.lin_incognito,
        iconColor = colors.accent,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        attention = firewallActionRequired && !expanded,
        attentionColor = colors.firewall,
    ) {
        CliTorModuleBlock(
            viewModel = viewModel,
            settings = settings,
            onOpenSettings = onOpenTor,
            onActivationRequested = {
                torActivation = DatasetActivationState(DatasetActivationFeature.TOR_BRIDGES)
            },
        )
        CliDivider(color = colors.borderBright)
        CliI2pModuleBlock(viewModel = viewModel, settings = settings, onOpenSettings = onOpenI2p)
        CliDivider(color = colors.borderBright)
        CliFirewallModuleBlock(
            viewModel = viewModel,
            settings = settings,
            onOpenSettings = onOpenFirewall,
            actionRequired = firewallActionRequired && expanded,
        )
        CliDivider(color = colors.borderBright)
        CliAnomalyModuleBlock(
            viewModel = viewModel,
            settings = settings,
            onOpenSettings = onOpenAnomaly,
            onActivationRequested = {
                sentinelActivation = DatasetActivationState(DatasetActivationFeature.FOXHOLE_SENTINEL)
            },
        )
    }
    torActivation?.let { flow ->
        CliTorDatasetActivationSheet(
            viewModel = viewModel,
            settings = settings,
            flow = flow,
            onStateChange = { torActivation = it },
            onDismiss = { torActivation = null },
        )
    }
    sentinelActivation?.let { flow ->
        CliSentinelDatasetActivationSheet(
            viewModel = viewModel,
            flow = flow,
            onStateChange = { sentinelActivation = it },
            onDismiss = { sentinelActivation = null },
        )
    }
}

@Composable
private fun CliTorModuleBlock(
    viewModel: HomeViewModel,
    settings: Settings,
    onOpenSettings: () -> Unit,
    onActivationRequested: () -> Unit,
) {
    CliModuleBlock(
        label = stringResource(R.string.cli_cfg_tor_core),
        icon = R.drawable.lin_tor,
        infoText = stringResource(R.string.cli_help_tor_body),
        checked = settings.privacyRoute.permitted,
        onToggle = { enabled ->
            if (enabled) {
                onActivationRequested()
            } else {
                viewModel.onTorRoutePermittedChanged(false)
            }
        },
        onOpenSettings = onOpenSettings,
    )
}

@Composable
private fun CliTorDatasetActivationSheet(
    viewModel: HomeViewModel,
    settings: Settings,
    flow: DatasetActivationState,
    onStateChange: (DatasetActivationState) -> Unit,
    onDismiss: () -> Unit,
) {
    val phase by viewModel.torBridgeUpdatePhase.collectAsStateWithLifecycle()
    val progress by viewModel.torBridgeDownloadProgress.collectAsStateWithLifecycle()
    var baseline by remember { mutableStateOf<TorBridgePersistenceMarker?>(null) }
    val verified =
        baseline?.let { marker ->
            torBridgeVerifiedSuccess(phase, settings.privacyRoute, marker)
        } == true
    CliDatasetActivationSheet(
        state = flow,
        phase = phase,
        progress = progress,
        verifiedDownload = verified,
        onStateChange = onStateChange,
        onSkip = {
            viewModel.onTorPermissionWithBridgeChoice(
                useBridges = false,
                source = flow.source,
            )
        },
        onStartDownload = {
            baseline = settings.privacyRoute.torBridgePersistenceMarker()
            viewModel.onTorBridgeManualRefresh(useFoxholeSourceOverride = true)
        },
        onCancelDownload = viewModel::onTorBridgeManualRefreshCancel,
        onActivate = { source ->
            viewModel.onTorPermissionWithBridgeChoice(useBridges = true, source = source)
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun CliI2pModuleBlock(
    viewModel: HomeViewModel,
    settings: Settings,
    onOpenSettings: () -> Unit,
) {
    CliModuleBlock(
        label = stringResource(R.string.cli_cfg_i2p_core),
        icon = R.drawable.lin_globe,
        infoText = listOf(
            stringResource(R.string.cli_i2p_start_body),
            stringResource(R.string.cli_i2p_runtime_note),
            stringResource(R.string.cli_i2p_allow_outside_tunnel_note),
        ).joinToString("\n\n"),
        checked = settings.i2p.enabled,
        onToggle = viewModel::onI2pEnabledChanged,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
private fun CliFirewallModuleBlock(
    viewModel: HomeViewModel,
    settings: Settings,
    onOpenSettings: () -> Unit,
    actionRequired: Boolean,
) {
    var consentOpen by remember { mutableStateOf(false) }
    var disableOpen by remember { mutableStateOf(false) }
    CliModuleBlock(
        label = stringResource(R.string.cli_cfg_firewall),
        icon = R.drawable.lin_fire,
        infoText = stringResource(R.string.cli_help_firewall_body),
        checked = settings.expert.firewallEnabled,
        note = if (actionRequired) {
            stringResource(R.string.cli_firewall_pending_disable_note)
        } else {
            null
        },
        toggleEnabled = !actionRequired,
        onToggle = { enable ->
            if (enable && !settings.ui.suppressFirewallEnableWarning) {
                consentOpen = true
            } else if (enable) {
                consentOpen = false
                viewModel.onFirewallEnabledChanged(true)
            } else {
                consentOpen = false
                disableOpen = true
            }
        },
        onOpenSettings = onOpenSettings,
        settingsAttention = actionRequired,
        settingsAttentionColor = LocalCliColors.current.firewall,
    )
    if (consentOpen) {
        CliConfirmSheet(
            title = stringResource(R.string.cli_cfg_firewall),
            icon = R.drawable.lin_fire,
            question = stringResource(R.string.cli_cfg_firewall_consent_body),
            confirmLabel = stringResource(R.string.cli_cfg_firewall_yes),
            onConfirm = {
                consentOpen = false
                viewModel.onFirewallEnabledChanged(true)
            },
            onDismiss = { consentOpen = false },
        )
    }
    if (disableOpen) {
        CliConfirmSheet(
            title = stringResource(R.string.cli_cfg_firewall),
            icon = R.drawable.lin_fire,
            question = stringResource(R.string.cli_cfg_firewall_disable_body),
            confirmLabel = stringResource(R.string.cli_cfg_firewall_disable_yes),
            onConfirm = {
                disableOpen = false
                viewModel.onFirewallEnabledChanged(false)
            },
            onDismiss = { disableOpen = false },
        )
    }
}

@Composable
private fun CliAnomalyModuleBlock(
    viewModel: HomeViewModel,
    settings: Settings,
    onOpenSettings: () -> Unit,
    onActivationRequested: () -> Unit,
) {
    val installedGeneratedAt = viewModel.threatIntelInstalledGeneratedAt()
    CliModuleBlock(
        label = stringResource(R.string.cli_cfg_more_anomaly),
        icon = R.drawable.lin_shield,
        infoText = stringResource(R.string.cli_help_sentinel_body),
        checked = settings.anomaly.enabled,
        onToggle = { value ->
            when {
                !value -> viewModel.onAnomalyEnabledChanged(false)
                installedGeneratedAt != null -> viewModel.onAnomalyEnabledChanged(true)
                else -> onActivationRequested()
            }
        },
        onOpenSettings = onOpenSettings,
    )
}

@Composable
private fun CliSentinelDatasetActivationSheet(
    viewModel: HomeViewModel,
    flow: DatasetActivationState,
    onStateChange: (DatasetActivationState) -> Unit,
    onDismiss: () -> Unit,
) {
    val phase by viewModel.threatIntelUpdatePhase.collectAsStateWithLifecycle()
    val progress by viewModel.threatIntelDownloadProgress.collectAsStateWithLifecycle()
    var baselineGeneratedAt by remember { mutableStateOf<String?>(null) }
    val verified =
        threatIntelVerifiedSuccess(
            phase = phase,
            installedGeneratedAt = viewModel.threatIntelInstalledGeneratedAt(),
            baselineGeneratedAt = baselineGeneratedAt,
        )
    CliDatasetActivationSheet(
        state = flow,
        phase = phase,
        progress = progress,
        verifiedDownload = verified,
        onStateChange = onStateChange,
        onSkip = { viewModel.onAnomalyEnabledChanged(false) },
        onStartDownload = {
            baselineGeneratedAt = viewModel.threatIntelInstalledGeneratedAt()
            viewModel.onThreatIntelManualRefresh()
        },
        onCancelDownload = viewModel::onThreatIntelManualRefreshCancel,
        onActivate = { source ->
            if (source == DatasetActivationSource.FOXHOLE_DB) {
                viewModel.onAnomalyEnabledChanged(true)
            }
        },
        onDismiss = onDismiss,
    )
}
