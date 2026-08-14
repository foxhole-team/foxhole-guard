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
import com.foxhole.core.runtime.i2pWouldRaiseTransparentGuard
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

/**
 * The modules of the product, as one list: TOR, I2P, the firewall and anomaly detection.
 *
 * What makes something a module rather than a setting is that it has a surface of its own and a
 * switch of its own — so all four read identically here: the switch that permits the module, then
 * the way into its own settings, which stays reachable whether or not the switch is on. The
 * firewall and anomaly detection used to be plain rows inside the security group, where a
 * device-wide packet filter read as a preference beside "block screenshots".
 *
 * One pixel rule BETWEEN modules and none inside them: the rule separates modules from each other,
 * and a line between a module's own two rows split the pair it was supposed to hold together.
 */
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
        icon = R.drawable.pix_incognito,
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

/** The first OFF -> ON edge asks for the bridge policy before TOR permission is persisted. */
@Composable
private fun CliTorModuleBlock(
    viewModel: HomeViewModel,
    settings: Settings,
    onOpenSettings: () -> Unit,
    onActivationRequested: () -> Unit,
) {
    CliModuleBlock(
        label = stringResource(R.string.cli_cfg_tor_core),
        icon = R.drawable.pix_tor,
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

/**
 * The sheet deliberately lives outside the collapsible module panel. A panel visibility change
 * must not destroy a first-enable decision before the user can choose a source and confirm it.
 */
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

/**
 * I2P. Turning it on with nothing else running raises the transparent guard, and that guard
 * deliberately applies no rules — not the block lane, not DNS filtering. It is a tunnel the user
 * did not ask for carrying traffic it does not filter, so it is asked for rather than assumed.
 * Turning I2P off needs no such warning.
 */
@Composable
private fun CliI2pModuleBlock(
    viewModel: HomeViewModel,
    settings: Settings,
    onOpenSettings: () -> Unit,
) {
    var transparentGuardConsent by remember { mutableStateOf(false) }
    // The pack has no I2P mark; the globe stands for "another network of its own" until one
    // is drawn.
    CliModuleBlock(
        label = stringResource(R.string.cli_cfg_i2p_core),
        icon = R.drawable.pix_globe,
        checked = settings.i2p.enabled,
        onToggle = { enable ->
            if (enable && settings.i2pWouldRaiseTransparentGuard()) {
                transparentGuardConsent = true
            } else {
                viewModel.onI2pEnabledChanged(enable)
            }
        },
        onOpenSettings = onOpenSettings,
    )
    if (transparentGuardConsent) {
        CliConfirmSheet(
            title = stringResource(R.string.cli_cfg_i2p_core),
            icon = R.drawable.pix_globe,
            question = stringResource(R.string.cli_i2p_transparent_guard_body),
            confirmLabel = stringResource(R.string.cli_i2p_transparent_guard_yes),
            onConfirm = {
                transparentGuardConsent = false
                viewModel.onI2pEnabledChanged(true)
            },
            onDismiss = { transparentGuardConsent = false },
        )
    }
}

/**
 * The firewall, with both of its consents. They rise as the shared bottom modal rather than
 * unfolding under the row: arming a device-wide packet filter is exactly the class of answer that
 * must not be given by a chip that appeared where the user's finger already was.
 *
 * Turning it off takes more with it than the row says: the local guard is the tunnel the app rules
 * and the new-app quarantine are applied through, and with no VPN or TOR connection up there is
 * nothing left applying them. The sheet names what stops so the switch is not the only place that
 * knows.
 */
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
        icon = R.drawable.pix_fire,
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
        // "Do not ask again" was removed by product decision: the firewall confirmation is
        // always asked and there is no silent path.
        CliConfirmSheet(
            title = stringResource(R.string.cli_cfg_firewall),
            icon = R.drawable.pix_fire,
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
            icon = R.drawable.pix_fire,
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

/**
 * Anomaly detection. The detector leans on the security lists, so switching it on with only the
 * bundled seed on the device offers the FoxHole DB download right here — the switch is where the
 * user learns the module needs data, not the screen behind it.
 */
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
        icon = R.drawable.pix_shield,
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
