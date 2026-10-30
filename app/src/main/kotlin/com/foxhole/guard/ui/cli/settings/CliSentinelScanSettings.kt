package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.ApkScanCost
import com.foxhole.core.model.InterfaceMode
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.scanner.SentinelScanStage
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cancelSentinelScan
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.refreshSentinelScanPolicy
import com.foxhole.guard.ui.requestSentinelBaseline
import com.foxhole.guard.ui.requestSentinelPackage
import com.foxhole.guard.ui.sentinelScanAvailable
import com.foxhole.guard.ui.sentinelScanState
import com.foxhole.guard.ui.updateSentinelScanSettings

@Composable
internal fun CliSentinelScanSettings(viewModel: HomeViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val scanState by viewModel.sentinelScanState.collectAsStateWithLifecycle()
    val policy = ui.settings.anomaly.apkScan
    val available = viewModel.sentinelScanAvailable
    val running = scanState.stage == SentinelScanStage.RUNNING
    var selected by remember { mutableStateOf<String?>(null) }
    var expertExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ui.settings.anomaly.enabled, policy) { viewModel.refreshSentinelScanPolicy() }
    CliPanel(title = stringResource(R.string.sentinel_static_title), icon = R.drawable.lin_shield) {
        CliDropdownRow(
            label = stringResource(R.string.sentinel_static_select_app),
            value = ui.installedApps.firstOrNull { it.packageName == selected }?.label ?: "—",
            options = ui.installedApps.map { CliDropdownOption(it.packageName, it.label) },
            selectedId = selected.orEmpty(),
            onSelect = { selected = it },
        )
        CliActionRow(
            label = stringResource(R.string.sentinel_static_run),
            onTap = { selected?.let(viewModel::requestSentinelPackage) },
            enabled = ui.settings.anomaly.enabled && available && selected != null && !running,
        )
        CliActionRow(
            label = stringResource(R.string.sentinel_static_baseline),
            onTap = viewModel::requestSentinelBaseline,
            enabled = ui.settings.anomaly.enabled && available && !running,
        )
        if (running) {
            CliActionRow(
                label = stringResource(R.string.sentinel_static_cancel),
                onTap = viewModel::cancelSentinelScan
            )
        }
        Text(
            stringResource(scanState.stage.scanStageResource()),
            style = CliType.small,
            color = LocalCliColors.current.dim
        )
        CliSentinelScanProgress(scanState, ui.installedApps)
        if (scanState.journalDeferred) {
            Text(
                stringResource(R.string.sentinel_static_journal_deferred),
                style = CliType.small
            )
        }
        if (!available) Text(stringResource(R.string.sentinel_static_engine_unavailable), style = CliType.small)
        Text(
            stringResource(R.string.sentinel_static_coverage_note),
            style = CliType.small,
            color = LocalCliColors.current.dim
        )
    }
    if (ui.settings.ui.interfaceMode != InterfaceMode.EXPERT) return
    CliToggleRow(
        label = stringResource(R.string.sentinel_static_expert),
        checked = expertExpanded,
        onToggle = { expertExpanded = it },
    )
    if (expertExpanded) {
        Spacer(Modifier.height(CliSpacing.sm))
        CliPanel(title = stringResource(R.string.sentinel_static_expert), icon = R.drawable.lin_settings) {
            CliToggleRow(
                stringResource(R.string.sentinel_static_dex),
                checked = policy.dexEnabled,
                onToggle = { viewModel.updateSentinelScanSettings { current -> current.copy(dexEnabled = it) } }
            )
            CliToggleRow(
                stringResource(R.string.sentinel_static_yara),
                checked = policy.yaraEnabled,
                onToggle = { viewModel.updateSentinelScanSettings { current -> current.copy(yaraEnabled = it) } }
            )
            CliToggleRow(
                stringResource(R.string.sentinel_static_notify),
                checked = policy.notifyFindings,
                onToggle = { viewModel.updateSentinelScanSettings { current -> current.copy(notifyFindings = it) } }
            )
            CliToggleRow(
                stringResource(R.string.sentinel_static_automatic),
                checked = policy.automaticNewUpdatedApps,
                onToggle = {
                    viewModel.updateSentinelScanSettings { current ->
                        current.copy(
                            automaticNewUpdatedApps = it
                        )
                    }
                }
            )
            CliToggleRow(
                stringResource(R.string.sentinel_static_charging),
                checked = policy.chargingOnly,
                onToggle = { viewModel.updateSentinelScanSettings { current -> current.copy(chargingOnly = it) } }
            )
            CliToggleRow(
                stringResource(R.string.sentinel_static_battery),
                checked = policy.batteryNotLow,
                onToggle = { viewModel.updateSentinelScanSettings { current -> current.copy(batteryNotLow = it) } }
            )
            CliDropdownRow(
                label = stringResource(R.string.sentinel_static_cost),
                value = stringResource(policy.cost.costResource()),
                options = ApkScanCost.entries.map { CliDropdownOption(it.name, stringResource(it.costResource())) },
                selectedId = policy.cost.name,
                onSelect = {
                    viewModel.updateSentinelScanSettings { current ->
                        current.copy(
                            cost = ApkScanCost.valueOf(it)
                        )
                    }
                },
            )
        }
    }
}
private fun ApkScanCost.costResource(): Int = when (this) {
    ApkScanCost.ECONOMY -> R.string.sentinel_static_economy
    ApkScanCost.BALANCED -> R.string.sentinel_static_balanced
}
internal fun SentinelScanStage.scanStageResource(): Int = when (this) {
    SentinelScanStage.IDLE -> R.string.sentinel_static_status_idle
    SentinelScanStage.DISABLED -> R.string.sentinel_static_status_disabled
    SentinelScanStage.WAITING -> R.string.sentinel_static_status_waiting
    SentinelScanStage.RUNNING -> R.string.sentinel_static_status_running
    SentinelScanStage.COMPLETE -> R.string.sentinel_static_status_complete
    SentinelScanStage.PARTIAL -> R.string.sentinel_static_status_partial
    SentinelScanStage.STALE -> R.string.sentinel_static_status_stale
    SentinelScanStage.CANCELLED -> R.string.sentinel_static_status_cancelled
    SentinelScanStage.UNAVAILABLE -> R.string.sentinel_static_status_unavailable
    SentinelScanStage.UNSUPPORTED -> R.string.sentinel_static_status_unsupported
}
