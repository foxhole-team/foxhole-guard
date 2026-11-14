package com.foxhole.guard.ui.cli.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.InstalledAppChangeType
import com.foxhole.core.model.InstalledAppInventoryChange
import com.foxhole.core.model.effectiveDiagnosticsRetention
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.scanner.SentinelScanControlState
import com.foxhole.guard.core.sentinel.scanner.ScanReportHistoryState
import com.foxhole.guard.core.sentinel.scanner.ScanReportStatus
import com.foxhole.guard.ui.cli.sentinel.SentinelReportSummary
import com.foxhole.guard.ui.cli.sentinel.SentinelReportDetailSheet
import com.foxhole.guard.ui.cli.sentinel.SentinelReportHistory
import com.foxhole.guard.ui.cli.sentinel.SentinelScanActions
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.HomeUiState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.settings.CliQuarantineQueue
import com.foxhole.guard.ui.cli.settings.SentinelAppDetailSheet
import com.foxhole.guard.ui.cli.settings.SentinelDetectionsTable
import com.foxhole.guard.ui.cli.settings.SentinelObservedAppRow
import com.foxhole.guard.ui.cli.settings.SentinelObservedAppsTable
import com.foxhole.guard.ui.cli.settings.observedSentinelPackages
import java.text.DateFormat
import java.util.Date

internal const val CLI_SENTINEL_JOURNAL_TAG = "cli_sentinel_journal"

@Composable
internal fun CliSentinelJournalContent(
    state: HomeUiState,
    scan: SentinelScanControlState,
    scannerAvailable: Boolean,
    onResolveQuarantine: (String, Boolean) -> Unit,
    onCancelScan: () -> Unit,
    history: ScanReportHistoryState = ScanReportHistoryState(),
    onRequestPackage: (String) -> Unit = {},
    onRequestBaseline: () -> Unit = {},
    onClearReports: () -> Unit = {},
) {
    val apps = remember(state.installedApps) { sentinelInstallations(state.installedApps) }
    val retention = state.settings.expert.effectiveDiagnosticsRetention()
    val now = remember(state.settings.installedAppInventoryAudit.recentChanges, retention) { System.currentTimeMillis() }
    val changes = remember(state.settings.installedAppInventoryAudit.recentChanges, retention, now) {
        sentinelInstallationHistory(state.settings.installedAppInventoryAudit.recentChanges, retention.cutoffOrNull(now) ?: Long.MIN_VALUE)
    }
    val observed = remember(state.appTrafficWindows, state.networkActivityEvents, state.anomalyEvents) {
        observedSentinelPackages(state.appTrafficWindows, state.networkActivityEvents, state.anomalyEvents)
    }
    val events = remember(state.anomalyEvents) { state.anomalyEvents.sortedByDescending(AnomalyEvent::createdAtMs) }
    var detailPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedReport by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf("observations") }
    val stored = history.reports.firstOrNull { it.id == scan.lastReport?.id }
    val latest = scan.lastReport?.let { current ->
        if (current.status == ScanReportStatus.RUNNING && stored != null && stored.status != ScanReportStatus.RUNNING) stored else current
    } ?: history.reports.firstOrNull()
    val report = if (latest?.id == selectedReport) latest else history.reports.firstOrNull { it.id == selectedReport }
    report?.let { SentinelReportDetailSheet(it, onDismiss = { selectedReport = null }) }
    if (selectedReport != null && report == null) {
        com.foxhole.guard.ui.cli.components.CliBottomSheet(
            title = stringResource(R.string.sentinel_report_title), onDismiss = { selectedReport = null },
        ) { Text(stringResource(R.string.sentinel_report_unavailable), style = CliType.small) }
    }
    apps.firstOrNull { it.packageName == detailPackage }?.let { app ->
        SentinelAppDetailSheet(app, onDismiss = { detailPackage = null })
    }
    LazyColumn(Modifier.fillMaxSize().testTag(CLI_SENTINEL_JOURNAL_TAG)) {
        item(key = "scan-actions") {
            CliPanel(title = stringResource(R.string.sentinel_static_title), icon = R.drawable.lin_shield) {
                SentinelScanActions(state.installedApps, scan, state.settings.anomaly.enabled, scannerAvailable,
                    onRequestPackage, onRequestBaseline, onCancelScan)
                if (!scannerAvailable) Text(stringResource(R.string.sentinel_static_engine_unavailable), style = CliType.small)
            }
        }
        item(key = "scan-report") {
            SentinelReportSummary(latest, scan.reportDeferred, onOpen = { selectedReport = it })
            if (history.unavailable) Text(stringResource(R.string.sentinel_report_unavailable), style = CliType.small, color = LocalCliColors.current.warn)
        }
        item(key = "detections") { SentinelDetectionsTable(events, state.installedApps, onOpenReport = { selectedReport = it }) }
        item(key = "pending-decisions") {
            CliQuarantineQueue(
                pendingPackages = state.settings.expert.pendingQuarantinePackages,
                pendingDetails = state.settings.expert.pendingQuarantineAppDetails,
                installedApps = state.installedApps,
                onResolve = onResolveQuarantine,
            )
        }
        item(key = "section") {
            CliDropdownRow(stringResource(R.string.sentinel_assessment_section),
                value = stringResource(when (section) {
                    "applications" -> R.string.sentinel_assessment_applications
                    "history" -> R.string.sentinel_assessment_history
                    else -> R.string.sentinel_assessment_observations
                }),
                options = listOf(
                    CliDropdownOption("observations", stringResource(R.string.sentinel_assessment_observations)),
                    CliDropdownOption("applications", stringResource(R.string.sentinel_assessment_applications)),
                    CliDropdownOption("history", stringResource(R.string.sentinel_assessment_history)),
                ), selectedId = section, onSelect = { section = it })
        }
        when (section) {
            "applications" -> {
                item(key = "inventory-title") { SentinelJournalSectionTitle(stringResource(R.string.cli_sentinel_installations)) }
                item(key = "inventory-status") {
                    when {
                        state.installedAppsLoading -> CliLoadingRow(stringResource(R.string.cli_common_updating))
                        !state.installedAppsLoaded -> Text(stringResource(R.string.cli_sentinel_inventory_failed), style = CliType.small, color = LocalCliColors.current.warn)
                        apps.isEmpty() -> Text(stringResource(R.string.cli_sentinel_installations_empty), style = CliType.small)
                    }
                }
                items(apps, key = { "inventory:${it.packageName}" }) { app ->
                    SentinelObservedAppRow(app.packageName, app, onSelect = { detailPackage = app.packageName })
                    CliRowDivider()
                }
            }
            "history" -> {
                item(key = "report-history") { SentinelReportHistory(history, { selectedReport = it }, onClearReports) }
                item(key = "install-history-title") { SentinelJournalSectionTitle(stringResource(R.string.cli_sentinel_install_history)) }
                if (changes.isEmpty()) item(key = "install-history-empty") { Text(stringResource(R.string.cli_sentinel_history_empty), style = CliType.small) }
                itemsIndexed(changes, key = { index, event -> "install-history:$index:${event.packageName}:${event.detectedAt}" }) { _, event ->
                    SentinelInstallHistoryRow(event)
                    CliRowDivider()
                }
            }
            else -> item(key = "activity-history") {
                CliPanel(title = stringResource(R.string.cli_sentinel_activity_history), modifier = Modifier.fillMaxWidth()) {
                    SentinelObservedAppsTable(observed, state.installedApps)
                }
            }
        }
    }
}

@Composable
private fun SentinelJournalSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(vertical = CliSpacing.sm),
        style = CliType.body,
        color = LocalCliColors.current.fg,
    )
}

@Composable
private fun SentinelInstallHistoryRow(event: InstalledAppInventoryChange) {
    val eventResource = when (event.type) {
        InstalledAppChangeType.INSTALLED -> R.string.guard_event_package_added
        InstalledAppChangeType.REMOVED -> R.string.guard_event_package_removed
    }
    val detectedAt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.detectedAt))
    Column(Modifier.fillMaxWidth().padding(vertical = CliSpacing.xs)) {
        Text(event.label.ifBlank { event.packageName }, style = CliType.body, color = LocalCliColors.current.fg)
        Text(event.packageName, style = CliType.small, color = LocalCliColors.current.dim)
        Text(
            stringResource(eventResource),
            style = CliType.small,
        )
        Text(
            stringResource(R.string.cli_sentinel_history_detected, detectedAt),
            style = CliType.small,
            color = LocalCliColors.current.dim,
        )
    }
}
