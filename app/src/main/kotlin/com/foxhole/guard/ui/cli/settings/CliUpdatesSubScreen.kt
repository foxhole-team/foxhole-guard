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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.Settings
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.R
import com.foxhole.guard.runtime.AppUpdateState
import com.foxhole.guard.ui.FoxholeDbGroupUi
import com.foxhole.guard.ui.FoxholeUpdatePhase
import com.foxhole.guard.ui.GeoIpDatabaseUiState
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.appUpdateChannelIsGithub
import com.foxhole.guard.ui.cli.CliColors
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
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
import java.time.Instant

/**
 * Updates as the FoxHole DB model: one repository, four data groups, and the app downloads only
 * what is enabled. Each panel reports first — the status line (up to date, or "update required" in
 * red), the switches, the per-group states — and ends with its one action button pinned at the
 * bottom edge, full width. The app's own updater is a separate panel with the same order, so the
 * two buttons sit where the eye already expects them and neither drifts up the panel as the rows
 * above it grow.
 *
 * The header control opens [CliUpdateSourcesButton]: which repository these panels read.
 */
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
    val dnsUpdateAvailable by viewModel.dnsFilterUpdateAvailable.collectAsStateWithLifecycle()
    val appUpdate by viewModel.container.appUpdateRepository.state.collectAsStateWithLifecycle()
    val settings = state.settings

    LaunchedEffect(Unit) { viewModel.refreshGeoIpDatabaseInfo() }
    // Not reactive on its own; re-read once the security-lists refresh settles.
    val threatIntelGeneratedAt = remember(threatIntelPhase) {
        viewModel.threatIntelInstalledGeneratedAt()
    }

    val groups = foxholeDbGroups(
        settings = settings,
        geoIp = geoIp,
        dnsUpdateAvailable = dnsUpdateAvailable,
        threatIntelGeneratedAt = threatIntelGeneratedAt,
    )
    val anyPhaseRunning = listOf(dnsPhase, bridgePhase, threatIntelPhase, geoIp.phase).any { it.isRunning }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(
            label = stringResource(R.string.cli_cfg_more_updates),
            icon = R.drawable.pix_settings,
            trailing = {
                CliUpdateSourcesButton(
                    sources = settings.updateSources,
                    // F-Droid and Play builds have no self-updater, so there is no app feed for
                    // them to point anywhere: the sheet offers the data repository alone.
                    appChannelEditable = appUpdateChannelIsGithub,
                    onApply = viewModel::onUpdateSourcesChanged,
                )
            },
        )
        CliFoxholeDbPanel(
            viewModel = viewModel,
            settings = settings,
            groups = groups,
            phases = FoxholeDbPhases(
                dns = dnsPhase,
                bridges = bridgePhase,
                security = threatIntelPhase,
                geo = geoIp.phase,
            ),
            refreshRunning = anyPhaseRunning,
        )
        // Only GitHub builds self-update: F-Droid and Play ship their own updater.
        if (appUpdateChannelIsGithub) {
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliPanel(
                title = stringResource(R.string.cli_updates_app),
                icon = R.drawable.pix_update,
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

private data class FoxholeDbPhases(
    val dns: FoxholeUpdatePhase,
    val bridges: FoxholeUpdatePhase,
    val security: FoxholeUpdatePhase,
    val geo: FoxholeUpdatePhase,
)

private data class FoxholeDbGroups(
    val dns: FoxholeDbGroupUi,
    val bridges: FoxholeDbGroupUi,
    val security: FoxholeDbGroupUi,
    val geo: FoxholeDbGroupUi,
) {
    private val all get() = listOf(dns, bridges, security, geo)

    // The status split behind the screen's colour law: red = an enabled group holds NO data at
    // all (first download required), orange = data present but the feed moved on.
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
        // The geo group has no switch — the map and geo statistics need it whenever it is absent.
        geo = FoxholeDbGroupUi(
            enabled = true,
            updatedAtMs = geoIp.info?.installed?.downloadedAtMs,
        ),
    )

@Composable
private fun CliFoxholeDbPanel(
    viewModel: HomeViewModel,
    settings: Settings,
    groups: FoxholeDbGroups,
    phases: FoxholeDbPhases,
    refreshRunning: Boolean,
) {
    val colors = LocalCliColors.current
    CliPanel(
        title = stringResource(R.string.cli_foxdb_title),
        icon = R.drawable.pix_update,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Status law of this screen: green up-to-date / orange update pending / red when a
        // download is required (a group holds no data at all). No timestamps — the stamp said
        // nothing actionable and drowned the one thing that matters.
        val statusColor = when {
            groups.downloadRequired -> colors.err
            groups.updatePending -> colors.warn
            else -> colors.ok
        }
        // Report first, action last: the status line and the per-group states are what the button
        // is about, so they read as the question and the button as the answer. The button holds
        // the panel's bottom edge in every state — one action for all of them (download, update or
        // plain check), full width, outlined in the status colour so the two still read as one
        // statement.
        CliKeyValue(
            key = stringResource(R.string.cli_foxdb_status),
            value = stringResource(
                if (groups.needsUpdate) R.string.cli_foxdb_status_required else R.string.cli_foxdb_status_ok,
            ),
            valueColor = statusColor,
            icon = R.drawable.pix_status,
            iconColor = statusColor,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliDashedInfoNote(text = stringResource(R.string.cli_foxdb_note))
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_updates_check),
            icon = R.drawable.pix_update,
            checked = settings.connection.componentUpdateCheckEnabled,
            onToggle = viewModel::onComponentUpdateCheckChanged,
        )
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_updates_auto),
            icon = R.drawable.pix_restart,
            checked = settings.connection.componentUpdateCheckEnabled &&
                settings.connection.componentAutoUpdateEnabled,
            enabled = settings.connection.componentUpdateCheckEnabled,
            onToggle = viewModel::onComponentAutoUpdateChanged,
        )
        CliRowDivider()
        CliFoxholeDbGroupRow(
            label = stringResource(R.string.cli_foxdb_group_dns),
            icon = R.drawable.pix_dns,
            group = groups.dns,
            phase = phases.dns,
        )
        CliRowDivider()
        CliFoxholeDbGroupRow(
            label = stringResource(R.string.cli_foxdb_group_bridges),
            icon = R.drawable.pix_tor,
            group = groups.bridges,
            phase = phases.bridges,
        )
        CliRowDivider()
        CliFoxholeDbGroupRow(
            label = stringResource(R.string.cli_foxdb_group_security),
            icon = R.drawable.pix_shield,
            group = groups.security,
            phase = phases.security,
        )
        CliRowDivider()
        CliFoxholeDbGroupRow(
            label = stringResource(R.string.cli_foxdb_group_geo),
            icon = R.drawable.pix_map,
            group = groups.geo,
            phase = phases.geo,
        )
        CliRowDivider()
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        CliButton(
            label = stringResource(
                when {
                    refreshRunning -> R.string.cli_wizard_phase_downloading
                    groups.downloadRequired -> R.string.cli_foxdb_sheet_download
                    groups.updatePending -> R.string.cli_foxdb_refresh
                    else -> R.string.cli_updates_btn_check
                },
            ),
            color = statusColor,
            enabled = !refreshRunning,
            onClick = { viewModel.onFoxholeDbRefreshAll(settings) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * One group line, statuses only (no timestamps): dim "off", red "download required" for a group
 * without data, orange "update required" when the feed moved on, green up-to-date otherwise —
 * with the live phase overriding everything while a refresh runs.
 */
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
            iconColor = if (valueColor == colors.ok) colors.ok else colors.dim,
        )
        if (phase.foxholeUpdateStage() != null) {
            Spacer(modifier = Modifier.height(CliSpacing.xs))
            CliFoxholeUpdateProgress(phase = phase)
        }
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

/**
 * The application's own update panel body: a status row (green up to date, orange when a new
 * version is pending) over the same full-width outlined action button as the FoxHole DB panel —
 * check, download or install depending on where the three-step flow stands. Same order as the
 * panel above: the report leads, the action holds the bottom edge.
 */
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
        icon = R.drawable.pix_status,
        iconColor = if (statusColor == colors.ok) colors.ok else colors.dim,
    )
    CliRowDivider()
    Spacer(modifier = Modifier.height(CliSpacing.xs))
    CliButton(
        label = stringResource(appUpdateButtonLabelRes(state)),
        color = if (busy) colors.dim else statusColor,
        enabled = !busy,
        onClick = when (state) {
            is AppUpdateState.Available -> onDownload
            is AppUpdateState.Downloaded -> onInstall
            else -> onCheck
        },
        modifier = Modifier.fillMaxWidth(),
    )
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

// Status-colour law of the updates screen: pending work is warn, a failed check err, a clean
// state ok — Idle stays dim because "never checked" is not a measurement.
private fun appUpdateStatusColor(
    state: AppUpdateState,
    colors: com.foxhole.guard.ui.cli.CliColors,
) = when (state) {
    is AppUpdateState.Available, is AppUpdateState.Downloaded, is AppUpdateState.Downloading -> colors.warn
    is AppUpdateState.Failed -> colors.err
    AppUpdateState.UpToDate -> colors.ok
    AppUpdateState.Idle, is AppUpdateState.Busy -> colors.dim
}

@Composable
private fun appUpdateStatusValue(state: AppUpdateState): String = when (state) {
    AppUpdateState.Idle -> BuildConfig.VERSION_NAME
    is AppUpdateState.Busy -> stringResource(R.string.cli_wizard_phase_checking)
    AppUpdateState.UpToDate -> stringResource(R.string.cli_updates_app_uptodate)
    is AppUpdateState.Available ->
        stringResource(R.string.cli_updates_app_available, state.update.manifest.versionName)
    is AppUpdateState.Downloading ->
        CliFormat.percent(
            if (state.totalBytes > 0L) state.downloadedBytes.toFloat() / state.totalBytes else 0f,
        )
    is AppUpdateState.Downloaded ->
        stringResource(R.string.cli_updates_app_available, state.update.manifest.versionName)
    is AppUpdateState.Failed -> stringResource(R.string.cli_updates_app_failed)
}

private fun appUpdateButtonLabelRes(state: AppUpdateState): Int = when (state) {
    is AppUpdateState.Available -> R.string.cli_foxdb_sheet_download
    is AppUpdateState.Downloaded -> R.string.cli_updates_app_install
    is AppUpdateState.Busy -> R.string.cli_wizard_phase_checking
    is AppUpdateState.Downloading -> R.string.cli_wizard_phase_downloading
    else -> R.string.cli_updates_btn_check
}

/**
 * True when anything on the updates screen actually wants the user — a group without data, a
 * pending FoxHole DB update or an app update to download/install. Drives the blinking attention
 * pixel on the settings root's "updates" row. Geo counts only once its info has genuinely been
 * read: before the first refresh the null info would blink over nothing.
 */
@Composable
internal fun rememberCliUpdatesAttention(viewModel: HomeViewModel): Boolean {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val geoIp by viewModel.geoIpDatabaseUiState.collectAsStateWithLifecycle()
    val dnsUpdateAvailable by viewModel.dnsFilterUpdateAvailable.collectAsStateWithLifecycle()
    val threatIntelPhase by viewModel.threatIntelUpdatePhase.collectAsStateWithLifecycle()
    val appUpdate by viewModel.container.appUpdateRepository.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshGeoIpDatabaseInfo() }
    val threatIntelGeneratedAt = remember(threatIntelPhase) {
        viewModel.threatIntelInstalledGeneratedAt()
    }
    val groups = foxholeDbGroups(
        settings = state.settings,
        geoIp = geoIp,
        dnsUpdateAvailable = dnsUpdateAvailable,
        threatIntelGeneratedAt = threatIntelGeneratedAt,
    )
    val geoKnown = geoIp.info != null
    return groups.dns.needsData ||
        groups.bridges.needsData ||
        groups.security.needsData ||
        (geoKnown && groups.geo.needsData) ||
        appUpdate is AppUpdateState.Available ||
        appUpdate is AppUpdateState.Downloaded
}
