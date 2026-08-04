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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.R
import com.foxhole.guard.runtime.AppUpdateState
import com.foxhole.guard.ui.FoxholeUpdatePhase
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.appUpdateChannelIsGithub
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onAppUpdateCheckRequested
import com.foxhole.guard.ui.onAppUpdateDownloadRequested
import com.foxhole.guard.ui.onAppUpdateInstallRequested
import com.foxhole.guard.ui.onComponentAutoUpdateChanged
import com.foxhole.guard.ui.onComponentUpdateCheckChanged
import com.foxhole.guard.ui.onDnsFilterManualRefresh
import com.foxhole.guard.ui.onDnsFilterManualRefreshCancel
import com.foxhole.guard.ui.onGeoIpDatabaseUpdateCancel
import com.foxhole.guard.ui.onGeoIpDatabaseUpdateRequested
import com.foxhole.guard.ui.onTorBridgeManualRefresh
import com.foxhole.guard.ui.onTorBridgeManualRefreshCancel
import com.foxhole.guard.ui.refreshGeoIpDatabaseInfo

/**
 * Component updates: the two master toggles plus one action row per asset (geoip / dns
 * filter / tor bridges) whose value slot doubles as the live phase indicator; tapping a
 * running row cancels (donor pairing). Terminal phases self-settle back to IDLE in the VM.
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
    val appUpdate by viewModel.container.appUpdateRepository.state.collectAsStateWithLifecycle()
    val settings = state.settings

    LaunchedEffect(Unit) { viewModel.refreshGeoIpDatabaseInfo() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_updates), icon = R.drawable.pix_update)
        CliPanel(
            title = stringResource(R.string.cli_updates_title),
            icon = R.drawable.pix_update,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CliToggleRow(
                label = stringResource(R.string.cli_updates_check),
                checked = settings.connection.componentUpdateCheckEnabled,
                onToggle = viewModel::onComponentUpdateCheckChanged,
            )
            CliToggleRow(
                label = stringResource(R.string.cli_updates_auto),
                checked = settings.connection.componentUpdateCheckEnabled &&
                    settings.connection.componentAutoUpdateEnabled,
                enabled = settings.connection.componentUpdateCheckEnabled,
                onToggle = viewModel::onComponentAutoUpdateChanged,
            )
            CliUpdateAssetRow(
                label = "geoip",
                phase = geoIp.phase,
                idleValue = geoIp.info?.installed?.version
                    ?: stringResource(R.string.cli_updates_bundled),
                onStart = viewModel::onGeoIpDatabaseUpdateRequested,
                onCancel = viewModel::onGeoIpDatabaseUpdateCancel,
            )
            if (settings.dns.dnsRuleSetFilteringEnabled()) {
                CliUpdateAssetRow(
                    label = "dns",
                    phase = dnsPhase,
                    idleValue = settings.dns.filtersUpdatedAt?.let { CliFormat.clock(it) }
                        ?: stringResource(R.string.cli_common_never),
                    onStart = viewModel::onDnsFilterManualRefresh,
                    onCancel = viewModel::onDnsFilterManualRefreshCancel,
                )
            }
            // Only GitHub builds self-update: F-Droid and Play ship their own updater.
            if (appUpdateChannelIsGithub) {
                CliAppUpdateRow(
                    state = appUpdate,
                    onCheck = viewModel::onAppUpdateCheckRequested,
                    onDownload = viewModel::onAppUpdateDownloadRequested,
                    onInstall = viewModel::onAppUpdateInstallRequested,
                )
            }
            if (settings.privacyRoute.permitted) {
                CliUpdateAssetRow(
                    label = "tor bridges",
                    phase = bridgePhase,
                    idleValue = settings.privacyRoute.bridgesUpdatedAt?.let { CliFormat.clock(it) }
                        ?: stringResource(R.string.cli_common_never),
                    onStart = viewModel::onTorBridgeManualRefresh,
                    onCancel = viewModel::onTorBridgeManualRefreshCancel,
                )
            }
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

@Composable
private fun CliUpdateAssetRow(
    label: String,
    phase: FoxholeUpdatePhase,
    idleValue: String,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val running = phase == FoxholeUpdatePhase.CHECKING ||
        phase == FoxholeUpdatePhase.DOWNLOADING ||
        phase == FoxholeUpdatePhase.VERIFYING
    CliActionRow(
        label = label,
        value = if (phase == FoxholeUpdatePhase.IDLE) idleValue else phase.name.lowercase(),
        onTap = { if (running) onCancel() else onStart() },
    )
}

/**
 * The application's own update row. Unlike the asset rows this one has three user steps — check,
 * download, install — because the install is a system dialog the user must confirm.
 */
@Composable
private fun CliAppUpdateRow(
    state: AppUpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    val value =
        when (state) {
            AppUpdateState.Idle -> BuildConfig.VERSION_NAME
            is AppUpdateState.Busy -> stringResource(R.string.cli_wizard_phase_checking)
            AppUpdateState.UpToDate -> stringResource(R.string.cli_updates_app_uptodate)
            is AppUpdateState.Available ->
                stringResource(R.string.cli_updates_app_available, state.update.manifest.versionName)
            is AppUpdateState.Downloading ->
                CliFormat.percent(
                    if (state.totalBytes > 0L) state.downloadedBytes.toFloat() / state.totalBytes else 0f,
                )
            is AppUpdateState.Downloaded -> stringResource(R.string.cli_updates_app_install)
            is AppUpdateState.Failed -> stringResource(R.string.cli_updates_app_failed)
        }
    CliActionRow(
        label = stringResource(R.string.cli_updates_app),
        value = value,
        enabled = state !is AppUpdateState.Busy && state !is AppUpdateState.Downloading,
        onTap =
        when (state) {
            is AppUpdateState.Available -> onDownload
            is AppUpdateState.Downloaded -> onInstall
            else -> onCheck
        },
    )
}
