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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.Settings
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.R
import com.foxhole.guard.runtime.AppUpdateBuildSignals
import com.foxhole.guard.runtime.AppUpdateCheck
import com.foxhole.guard.runtime.AppUpdateSeverity
import com.foxhole.guard.runtime.AppUpdateState
import com.foxhole.guard.runtime.RemoteUpdatePhase
import com.foxhole.guard.runtime.appUpdateSeverity
import com.foxhole.guard.ui.FoxholeDbGroupUi
import com.foxhole.guard.ui.FoxholeUpdatePhase
import com.foxhole.guard.ui.GeoIpDatabaseUiState
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.appUpdateChannelIsGithub
import com.foxhole.guard.ui.appUpdateFailureLabelRes
import com.foxhole.guard.ui.appUpdateRequiredLabelRes
import com.foxhole.guard.ui.cli.CliColors
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliDashedInfoNote
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.isRunning
import com.foxhole.guard.ui.onAppUpdateCheckRequested
import com.foxhole.guard.ui.onAppUpdateDownloadRequested
import com.foxhole.guard.ui.onAppUpdateInstallRequested
import com.foxhole.guard.ui.onComponentAutoUpdateChanged
import com.foxhole.guard.ui.onComponentUpdateCheckChanged
import com.foxhole.guard.ui.onFoxholeDbRefreshAll
import com.foxhole.guard.ui.onUpdateSourcesChanged
import com.foxhole.guard.ui.refreshGeoIpDatabaseInfo
import com.foxhole.guard.ui.threatIntelInstalledGeneratedAt
import com.foxhole.guard.ui.threatIntelUpdatePhase
import com.foxhole.guard.ui.tlsFingerprintInstalledGeneratedAt
import com.foxhole.guard.ui.tlsFingerprintUpdatePhase
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
internal fun CliUpdatesSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val geoIp by viewModel.geoIpDatabaseUiState.collectAsStateWithLifecycle()
    val dnsPhase by viewModel.dnsFilterUpdatePhase.collectAsStateWithLifecycle()
    val bridgePhase by viewModel.torBridgeUpdatePhase.collectAsStateWithLifecycle()
    val threatIntelPhase by viewModel.threatIntelUpdatePhase.collectAsStateWithLifecycle()
    val tlsFingerprintPhase by viewModel.tlsFingerprintUpdatePhase.collectAsStateWithLifecycle()
    val dnsUpdateAvailable by viewModel.dnsFilterUpdateAvailable.collectAsStateWithLifecycle()
    val appUpdate by viewModel.container.appUpdateRepository.state.collectAsStateWithLifecycle()
    val settings = state.settings

    LaunchedEffect(Unit) { viewModel.refreshGeoIpDatabaseInfo() }
    val threatIntelGeneratedAt = remember(threatIntelPhase) {
        viewModel.threatIntelInstalledGeneratedAt()
    }
    val tlsFingerprintGeneratedAt = remember(tlsFingerprintPhase) {
        viewModel.tlsFingerprintInstalledGeneratedAt()
    }

    val groups = foxholeDbGroups(
        settings = settings,
        geoIp = geoIp,
        dnsUpdateAvailable = dnsUpdateAvailable,
        threatIntelGeneratedAt = threatIntelGeneratedAt,
        tlsFingerprintGeneratedAt = tlsFingerprintGeneratedAt,
    )
    val anyPhaseRunning =
        listOf(dnsPhase, bridgePhase, threatIntelPhase, geoIp.phase, tlsFingerprintPhase).any { it.isRunning }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(
            label = stringResource(R.string.cli_cfg_more_updates),
            icon = R.drawable.lin_settings,
            trailing = {
                CliUpdateSourcesButton(
                    sources = settings.updateSources,
                    appChannelEditable = appUpdateChannelIsGithub,
                    onApply = viewModel::onUpdateSourcesChanged,
                )
            },
        )

        Column(

            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalCliBottomChromeClearance.current),

        ) {
            if (AppUpdateBuildSignals.fdroidUpdateNoticeRequired()) {
                CliFdroidUpdateNotice()
                Spacer(modifier = Modifier.height(CliSpacing.sm))
            }
            CliFoxholeDbPanel(
                viewModel = viewModel,
                settings = settings,
                groups = groups,
                phases = FoxholeDbPhases(
                    dns = dnsPhase,
                    bridges = bridgePhase,
                    security = threatIntelPhase,
                    geo = geoIp.phase,
                    tlsFingerprints = tlsFingerprintPhase,
                ),
                refreshRunning = anyPhaseRunning,
                refreshPhase =
                foxholeAggregatePhase(dnsPhase, bridgePhase, threatIntelPhase, geoIp.phase, tlsFingerprintPhase),
            )
            if (appUpdateChannelIsGithub) {
                Spacer(modifier = Modifier.height(CliSpacing.sm))
                CliPanel(
                    title = stringResource(R.string.cli_updates_app),
                    icon = R.drawable.lin_update,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CliAppUpdatePanelBody(
                        state = appUpdate,
                        onCheck = viewModel::onAppUpdateCheckRequested,
                        onDownload = viewModel::onAppUpdateDownloadRequested,
                        onInstall = viewModel::onAppUpdateInstallRequested,
                    )
                }
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
    }
}

@Composable
private fun CliFdroidUpdateNotice() {
    val colors = LocalCliColors.current
    CliPanel(
        title = stringResource(R.string.cli_updates_fdroid_title),
        titleColor = colors.err,
        icon = R.drawable.lin_update,
        iconColor = colors.err,
        attention = true,
        attentionColor = colors.err,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliKeyValue(
            key = stringResource(R.string.cli_updates_app_version),
            value = BuildConfig.VERSION_NAME,
            valueColor = colors.err,
            icon = R.drawable.lin_status,
            iconColor = colors.err,
        )
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        CliDashedInfoNote(text = stringResource(R.string.cli_updates_fdroid_notice))
    }
}

private data class FoxholeDbPhases(
    val dns: FoxholeUpdatePhase,
    val bridges: FoxholeUpdatePhase,
    val security: FoxholeUpdatePhase,
    val geo: FoxholeUpdatePhase,
    val tlsFingerprints: FoxholeUpdatePhase,
)

private data class FoxholeDbGroups(
    val dns: FoxholeDbGroupUi,
    val bridges: FoxholeDbGroupUi,
    val security: FoxholeDbGroupUi,
    val geo: FoxholeDbGroupUi,
    val tlsFingerprints: FoxholeDbGroupUi,
) {
    private val all get() = listOf(dns, bridges, security, geo, tlsFingerprints)

    val downloadRequired: Boolean
        get() = all.any { group -> group.enabled && group.updatedAtMs == null }
    val updatePending: Boolean
        get() = all.any { group -> group.enabled && group.updatedAtMs != null && group.updateAvailable }
    val needsUpdate: Boolean
        get() = all.any(FoxholeDbGroupUi::needsData)
}

private fun foxholeDbGroups(
    settings: Settings,
    geoIp: GeoIpDatabaseUiState,
    dnsUpdateAvailable: Boolean,
    threatIntelGeneratedAt: String?,
    tlsFingerprintGeneratedAt: String?,
): FoxholeDbGroups =
    FoxholeDbGroups(
        dns = FoxholeDbGroupUi(
            enabled = settings.dns.dnsRuleSetFilteringEnabled(),
            updatedAtMs = settings.dns.filtersUpdatedAt,
            updateAvailable = dnsUpdateAvailable,
        ),
        bridges = FoxholeDbGroupUi(
            enabled = settings.privacyRoute.permitted && settings.privacyRoute.bridgesEnabled,
            updatedAtMs = settings.privacyRoute.bridgesUpdatedAt,
        ),
        security = FoxholeDbGroupUi(
            enabled = settings.anomaly.enabled,
            updatedAtMs =
            threatIntelGeneratedAt?.let { stamp ->
                runCatching { Instant.parse(stamp).toEpochMilli() }.getOrNull()
            },
        ),
        geo = FoxholeDbGroupUi(
            enabled = true,
            updatedAtMs = geoIp.info?.installed?.downloadedAtMs,
        ),
        tlsFingerprints = FoxholeDbGroupUi(
            enabled = true,
            updatedAtMs =
            tlsFingerprintGeneratedAt?.let { stamp ->
                runCatching { Instant.parse(stamp).toEpochMilli() }.getOrNull()
            },
        ),
    )

@Composable
private fun CliFoxholeDbPanel(
    viewModel: HomeViewModel,
    settings: Settings,
    groups: FoxholeDbGroups,
    phases: FoxholeDbPhases,
    refreshRunning: Boolean,
    refreshPhase: FoxholeUpdatePhase,
) {
    val colors = LocalCliColors.current
    CliPanel(
        title = stringResource(R.string.cli_foxdb_title),
        icon = R.drawable.lin_update,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val statusColor = when {
            groups.downloadRequired -> colors.err
            groups.updatePending -> colors.warn
            else -> colors.ok
        }
        CliKeyValue(
            key = stringResource(R.string.cli_foxdb_status),
            value = stringResource(
                if (groups.needsUpdate) R.string.cli_foxdb_status_required else R.string.cli_foxdb_status_ok,
            ),
            valueColor = statusColor,
            icon = R.drawable.lin_status,
            iconColor = statusColor,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_updates_check),
            icon = R.drawable.lin_update,
            checked = settings.connection.componentUpdateCheckEnabled,
            onToggle = viewModel::onComponentUpdateCheckChanged,
        )
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_updates_auto),
            icon = R.drawable.lin_restart,
            checked = settings.connection.componentUpdateCheckEnabled &&
                settings.connection.componentAutoUpdateEnabled,
            enabled = settings.connection.componentUpdateCheckEnabled,
            onToggle = viewModel::onComponentAutoUpdateChanged,
        )
        CliRowDivider()
        CliFoxholeDbGroupRow(
            label = stringResource(R.string.cli_foxdb_group_dns),
            icon = R.drawable.lin_dns,
            group = groups.dns,
            phase = phases.dns,
        )
        CliRowDivider()
        CliFoxholeDbGroupRow(
            label = stringResource(R.string.cli_foxdb_group_bridges),
            icon = R.drawable.lin_tor,
            group = groups.bridges,
            phase = phases.bridges,
        )
        CliRowDivider()
        CliFoxholeDbGroupRow(
            label = stringResource(R.string.cli_foxdb_group_security),
            icon = R.drawable.lin_shield,
            group = groups.security,
            phase = phases.security,
        )
        CliRowDivider()
        CliFoxholeDbGroupRow(
            label = stringResource(R.string.cli_foxdb_group_geo),
            icon = R.drawable.lin_map,
            group = groups.geo,
            phase = phases.geo,
        )
        CliRowDivider()
        CliFoxholeDbGroupRow(
            label = stringResource(R.string.cli_foxdb_group_tls),
            icon = R.drawable.lin_shield,
            group = groups.tlsFingerprints,
            phase = phases.tlsFingerprints,
        )
        CliRowDivider()
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        val doneHold = rememberUpdateDoneHold(refreshRunning) &&
            refreshPhase != FoxholeUpdatePhase.FAILED
        CliButton(
            label = foxholeRefreshButtonLabel(refreshRunning, doneHold, refreshPhase, groups),
            color = statusColor,
            enabled = !refreshRunning && !doneHold,
            animatedLabel = refreshRunning,
            onClick = { viewModel.onFoxholeDbRefreshAll(settings) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CliFoxholeDbGroupRow(
    label: String,
    icon: Int,
    group: FoxholeDbGroupUi,
    phase: FoxholeUpdatePhase,
) {
    val colors = LocalCliColors.current
    val presentation = foxholeDbGroupPresentation(group, phase)
    val valueColor = presentation.tone.color(colors)
    Column {
        CliKeyValue(
            key = label,
            value = stringResource(presentation.labelRes),
            valueColor = valueColor,
            icon = icon,
            iconColor = colors.dim,
        )
    }
}

private data class FoxholeDbGroupPresentation(
    val labelRes: Int,
    val tone: FoxholeDbGroupTone,
)

private enum class FoxholeDbGroupTone { DIM, ERROR, WARNING, SUCCESS }

private fun foxholeDbGroupPresentation(
    group: FoxholeDbGroupUi,
    phase: FoxholeUpdatePhase,
): FoxholeDbGroupPresentation = when {
    phase == FoxholeUpdatePhase.FAILED ->
        FoxholeDbGroupPresentation(phase.statusLabelRes(), FoxholeDbGroupTone.ERROR)
    phase == FoxholeUpdatePhase.DONE || phase == FoxholeUpdatePhase.NO_UPDATE ->
        FoxholeDbGroupPresentation(phase.statusLabelRes(), FoxholeDbGroupTone.SUCCESS)
    phase.isRunning ->
        FoxholeDbGroupPresentation(phase.statusLabelRes(), FoxholeDbGroupTone.WARNING)
    !group.enabled ->
        FoxholeDbGroupPresentation(R.string.cli_foxdb_group_off, FoxholeDbGroupTone.DIM)
    group.updatedAtMs == null ->
        FoxholeDbGroupPresentation(R.string.cli_foxdb_group_missing, FoxholeDbGroupTone.ERROR)
    group.updateAvailable ->
        FoxholeDbGroupPresentation(R.string.cli_foxdb_status_required, FoxholeDbGroupTone.WARNING)
    else ->
        FoxholeDbGroupPresentation(R.string.cli_foxdb_status_ok, FoxholeDbGroupTone.SUCCESS)
}

private fun FoxholeDbGroupTone.color(colors: CliColors): Color = when (this) {
    FoxholeDbGroupTone.DIM -> colors.faint
    FoxholeDbGroupTone.ERROR -> colors.err
    FoxholeDbGroupTone.WARNING -> colors.warn
    FoxholeDbGroupTone.SUCCESS -> colors.ok
}

private fun foxholeAggregatePhase(vararg phases: FoxholeUpdatePhase): FoxholeUpdatePhase =
    phases.filter { phase -> phase.isRunning }
        .maxByOrNull { phase -> phase.foxholeUpdateStage() ?: 0 }
        ?: phases.maxByOrNull { phase -> phase.foxholeUpdateStage() ?: 0 }
        ?: FoxholeUpdatePhase.IDLE

@Composable
private fun rememberUpdateDoneHold(running: Boolean): Boolean {
    var holding by remember { mutableStateOf(false) }
    var ranAtLeastOnce by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (running) {
            ranAtLeastOnce = true
            holding = false
        } else if (ranAtLeastOnce) {
            ranAtLeastOnce = false
            holding = true
            delay(UPDATE_DONE_HOLD_MS)
            holding = false
        }
    }
    return holding
}

@Composable
private fun foxholeRefreshButtonLabel(
    running: Boolean,
    doneHold: Boolean,
    phase: FoxholeUpdatePhase,
    groups: FoxholeDbGroups,
): String {
    if (doneHold) return stringResource(R.string.cli_updates_phase_done)
    if (!running) {
        return stringResource(
            when {
                groups.downloadRequired -> R.string.cli_foxdb_sheet_download
                groups.updatePending -> R.string.cli_foxdb_refresh
                else -> R.string.cli_updates_btn_check
            },
        )
    }
    return stringResource(phase.updateStageLabelRes())
}

private fun FoxholeUpdatePhase.updateStageLabelRes(): Int = when (this) {
    FoxholeUpdatePhase.CHECKING, FoxholeUpdatePhase.IDLE -> R.string.cli_updates_phase_checking
    FoxholeUpdatePhase.DOWNLOADING -> R.string.cli_updates_phase_downloading
    FoxholeUpdatePhase.VERIFYING -> R.string.cli_updates_phase_verifying
    FoxholeUpdatePhase.DONE, FoxholeUpdatePhase.NO_UPDATE -> R.string.cli_updates_phase_done
    FoxholeUpdatePhase.FAILED -> R.string.cli_foxdb_status_failed
}

@Composable
private fun CliAppUpdatePanelBody(
    state: AppUpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    val colors = LocalCliColors.current
    val busy = state is AppUpdateState.Busy || state is AppUpdateState.Downloading
    val statusColor = appUpdateStatusColor(state, colors)
    CliKeyValue(
        key = stringResource(R.string.cli_updates_app_version),
        value = appUpdateStatusValue(state),
        valueColor = statusColor,
        icon = R.drawable.lin_status,
        iconColor = colors.dim,
    )
    CliRowDivider()
    Spacer(modifier = Modifier.height(CliSpacing.xs))
    val doneHold = rememberUpdateDoneHold(busy) && state !is AppUpdateState.Failed
    CliButton(
        label = if (doneHold) stringResource(R.string.cli_updates_phase_done) else appUpdateButtonLabel(state),
        color = if (busy) colors.dim else statusColor,
        enabled = !busy && !doneHold,
        animatedLabel = busy,
        onClick = when (state) {
            is AppUpdateState.Available -> onDownload
            is AppUpdateState.Downloaded -> onInstall
            else -> onCheck
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun appUpdateButtonLabel(state: AppUpdateState): String = when (state) {
    is AppUpdateState.Downloading -> stringResource(
        R.string.cli_updates_progress,
        stringResource(R.string.cli_updates_phase_downloading),
        CliFormat.percent(
            if (state.totalBytes > 0L) state.downloadedBytes.toFloat() / state.totalBytes else 0f,
        ),
    )
    is AppUpdateState.Busy -> stringResource(state.phase.updateStageLabelRes())
    else -> stringResource(appUpdateButtonLabelRes(state))
}

private fun RemoteUpdatePhase.updateStageLabelRes(): Int = when (this) {
    RemoteUpdatePhase.CHECKING -> R.string.cli_updates_phase_checking
    RemoteUpdatePhase.DOWNLOADING -> R.string.cli_updates_phase_downloading
    RemoteUpdatePhase.VERIFYING -> R.string.cli_updates_phase_verifying
}

private fun FoxholeUpdatePhase.statusLabelRes(): Int = when (this) {
    FoxholeUpdatePhase.IDLE -> R.string.cli_foxdb_status_ok
    FoxholeUpdatePhase.CHECKING -> R.string.cli_wizard_phase_checking
    FoxholeUpdatePhase.DOWNLOADING -> R.string.cli_wizard_phase_downloading
    FoxholeUpdatePhase.VERIFYING -> R.string.cli_wizard_phase_verifying
    FoxholeUpdatePhase.DONE -> R.string.cli_foxdb_status_updated
    FoxholeUpdatePhase.NO_UPDATE -> R.string.cli_foxdb_status_ok
    FoxholeUpdatePhase.FAILED -> R.string.cli_foxdb_status_failed
}

private fun appUpdateStatusColor(
    state: AppUpdateState,
    colors: com.foxhole.guard.ui.cli.CliColors,
) = when (state) {
    is AppUpdateState.Failed -> colors.err
    AppUpdateState.UpToDate -> colors.ok
    AppUpdateState.Idle, is AppUpdateState.Busy -> colors.dim
    else -> when (state.appUpdateSeverity) {
        AppUpdateSeverity.BEHIND_ONE -> colors.warn
        AppUpdateSeverity.BEHIND_TWO -> colors.alert
        AppUpdateSeverity.BEHIND_MANY -> colors.err
        AppUpdateSeverity.NONE -> colors.dim
    }
}

@Composable
private fun appUpdateStatusValue(state: AppUpdateState): String = when (state) {
    AppUpdateState.Idle -> BuildConfig.VERSION_NAME
    is AppUpdateState.Busy -> stringResource(state.phase.updateStageLabelRes())
    AppUpdateState.UpToDate -> stringResource(R.string.cli_updates_app_uptodate)
    is AppUpdateState.Available -> appUpdateOfferLabel(state.update)
    is AppUpdateState.Downloading ->
        CliFormat.percent(
            if (state.totalBytes > 0L) state.downloadedBytes.toFloat() / state.totalBytes else 0f,
        )
    is AppUpdateState.Downloaded -> appUpdateOfferLabel(state.update)
    is AppUpdateState.Failed -> stringResource(state.failure.appUpdateFailureLabelRes())
}

@Composable
private fun appUpdateOfferLabel(update: AppUpdateCheck.Available): String =
    appUpdateRequiredLabelRes(update.severity)
        ?.let { res -> stringResource(res, update.versionName) }
        ?: stringResource(R.string.cli_updates_app_available, update.versionName)

private fun appUpdateButtonLabelRes(state: AppUpdateState): Int = when (state) {
    is AppUpdateState.Available -> R.string.cli_foxdb_sheet_download
    is AppUpdateState.Downloaded -> R.string.cli_updates_app_install
    is AppUpdateState.Busy -> R.string.cli_updates_phase_checking
    is AppUpdateState.Downloading -> R.string.cli_updates_phase_downloading
    else -> R.string.cli_updates_btn_check
}

private const val UPDATE_DONE_HOLD_MS = 2_000L

@Composable
internal fun rememberCliUpdatesAttention(viewModel: HomeViewModel): Boolean {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val geoIp by viewModel.geoIpDatabaseUiState.collectAsStateWithLifecycle()
    val dnsUpdateAvailable by viewModel.dnsFilterUpdateAvailable.collectAsStateWithLifecycle()
    val threatIntelPhase by viewModel.threatIntelUpdatePhase.collectAsStateWithLifecycle()
    val tlsFingerprintPhase by viewModel.tlsFingerprintUpdatePhase.collectAsStateWithLifecycle()
    val appUpdate by viewModel.container.appUpdateRepository.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshGeoIpDatabaseInfo() }
    val threatIntelGeneratedAt = remember(threatIntelPhase) {
        viewModel.threatIntelInstalledGeneratedAt()
    }
    val tlsFingerprintGeneratedAt = remember(tlsFingerprintPhase) {
        viewModel.tlsFingerprintInstalledGeneratedAt()
    }
    val groups = foxholeDbGroups(
        settings = state.settings,
        geoIp = geoIp,
        dnsUpdateAvailable = dnsUpdateAvailable,
        threatIntelGeneratedAt = threatIntelGeneratedAt,
        tlsFingerprintGeneratedAt = tlsFingerprintGeneratedAt,
    )
    val geoKnown = geoIp.info != null
    val appUpdatePending =
        appUpdateChannelIsGithub &&
            (appUpdate is AppUpdateState.Available || appUpdate is AppUpdateState.Downloaded)
    return groups.dns.needsData ||
        groups.bridges.needsData ||
        groups.security.needsData ||
        (geoKnown && groups.geo.needsData) ||
        groups.tlsFingerprints.needsData ||
        appUpdatePending
}
