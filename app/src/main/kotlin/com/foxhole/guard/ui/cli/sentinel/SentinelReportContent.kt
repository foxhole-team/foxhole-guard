package com.foxhole.guard.ui.cli.sentinel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.scanner.ScanReportHistoryState
import com.foxhole.guard.core.sentinel.scanner.ScanReportStatus
import com.foxhole.guard.core.sentinel.scanner.SentinelScanReport
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SentinelReportSummary(report: SentinelScanReport?, unsaved: Boolean, onOpen: (String) -> Unit) {
    CliPanel(title = stringResource(R.string.sentinel_report_title), modifier = Modifier.fillMaxWidth()) {
        if (report == null) {
            Text(stringResource(R.string.sentinel_report_empty), style = CliType.small)
        } else {
            Text(stringResource(report.status.statusResource()), style = CliType.body,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(report.packageName ?: stringResource(R.string.sentinel_static_baseline), style = CliType.small)
            Text(reportDate(report.startedAtMs), style = CliType.small)
            Text(stringResource(R.string.sentinel_report_coverage,
                report.coverage.openedArtifacts, report.coverage.requestedArtifacts,
                report.coverage.completedDexEntries, report.coverage.declaredDexEntries), style = CliType.small)
            report.requestedPackages?.takeIf { it > 1 }?.let { requested ->
                Text(stringResource(R.string.sentinel_report_apps, report.completedPackages, requested), style = CliType.small)
            }
            if (report.requestComplete == false) Text(stringResource(R.string.sentinel_report_request_partial), style = CliType.small, color = LocalCliColors.current.warn)
            if (report.cached) Text(stringResource(R.string.sentinel_report_cached), style = CliType.small)
            if (report.journalDeferred) Text(stringResource(R.string.sentinel_static_journal_deferred), style = CliType.small)
            if (unsaved) Text(stringResource(R.string.sentinel_report_unsaved), style = CliType.small, color = LocalCliColors.current.warn)
            CliActionRow(stringResource(R.string.sentinel_report_open), onTap = { onOpen(report.id) })
        }
        Text(stringResource(R.string.sentinel_report_scope), style = CliType.small, color = LocalCliColors.current.dim)
    }
}

@Composable
internal fun SentinelReportDetailSheet(report: SentinelScanReport, onDismiss: () -> Unit) {
    CliBottomSheet(title = stringResource(R.string.sentinel_report_title), onDismiss = onDismiss, icon = R.drawable.lin_shield) {
        Text(report.packageName ?: stringResource(R.string.sentinel_static_baseline), style = CliType.body)
        Text(stringResource(report.status.statusResource()), style = CliType.body)
        Text(reportDate(report.startedAtMs), style = CliType.small)
        if (report.cached) Text(stringResource(R.string.sentinel_report_cached), style = CliType.small)
        if (report.status == ScanReportStatus.STALE) Text(stringResource(R.string.sentinel_report_stale_note), style = CliType.small)
        Text(stringResource(R.string.sentinel_report_scope), style = CliType.small)
        Text(stringResource(R.string.sentinel_report_static_limits), style = CliType.small)
        if (report.requestComplete == false) Text(stringResource(R.string.sentinel_report_request_partial), style = CliType.small)
        Text("${stringResource(R.string.sentinel_report_app_version)}: ${report.appVersion}", style = CliType.small)
        report.packageVersion?.let { Text("APK: $it", style = CliType.small) }
        report.analyzerVersion?.let { Text(it, style = CliType.small) }
        Text(stringResource(R.string.sentinel_report_rule_epoch), style = CliType.body)
        report.ruleEpoch?.let { SentinelTechnicalValue(it) } ?: Text(stringResource(R.string.sentinel_assessment_unknown), style = CliType.small)
        Text(stringResource(R.string.sentinel_report_coverage,
            report.coverage.openedArtifacts, report.coverage.requestedArtifacts,
            report.coverage.completedDexEntries, report.coverage.declaredDexEntries), style = CliType.small)
        Text("${report.artifactByteLimit / 1048576} MiB / APK", style = CliType.small)
        for (artifact in report.artifacts) {
            CliRowDivider()
            Text("${artifact.kind} · ${artifact.sourceId}", style = CliType.body)
            Text("${artifact.inputBytes} B · ${artifact.entriesVerified}/${artifact.entriesDeclared}", style = CliType.small)
            artifact.sha256?.let { SentinelTechnicalValue(it) }
        }
        if (report.status == ScanReportStatus.COMPLETE && report.observations.isEmpty() && report.ruleIds.isEmpty()) {
            Text(stringResource(R.string.sentinel_report_no_matches), style = CliType.small)
        }
        Text(stringResource(R.string.sentinel_report_observations), style = CliType.body)
        report.observations.forEach { Text(it, style = CliType.small) }
        Text(stringResource(R.string.sentinel_report_rules), style = CliType.body)
        report.ruleIds.forEach { SentinelTechnicalValue(it) }
        Text(stringResource(R.string.sentinel_report_issues), style = CliType.body)
        report.issues.forEach { Text(it, style = CliType.small, color = LocalCliColors.current.warn) }
        Text(stringResource(R.string.sentinel_assessment_limitations), style = CliType.body)
        report.limitations.forEach { Text(it, style = CliType.small, color = LocalCliColors.current.dim) }
        if (report.journalDeferred) Text(stringResource(R.string.sentinel_static_journal_deferred), style = CliType.small)
    }
}

@Composable
internal fun SentinelReportHistory(history: ScanReportHistoryState, onOpen: (String) -> Unit, onClear: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    if (confirming) CliBottomSheet(title = stringResource(R.string.sentinel_report_clear), onDismiss = { confirming = false }) {
        Text(stringResource(R.string.sentinel_report_clear_confirm), style = CliType.small)
        CliActionRow(stringResource(R.string.sentinel_report_clear), onTap = { confirming = false; onClear() })
    }
    CliPanel(title = stringResource(R.string.sentinel_report_history), modifier = Modifier.fillMaxWidth()) {
        if (history.unavailable) Text(stringResource(R.string.sentinel_report_unavailable), style = CliType.small, color = LocalCliColors.current.warn)
        else if (history.loaded && history.reports.isEmpty()) Text(stringResource(R.string.sentinel_report_empty), style = CliType.small)
        if (history.reports.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(history.reports, key = { it.id }) { report ->
                    Text(report.packageName ?: stringResource(R.string.sentinel_static_baseline), style = CliType.body)
                    Text(reportDate(report.startedAtMs), style = CliType.small)
                    Text(stringResource(report.status.statusResource()), style = CliType.small)
                    CliActionRow(stringResource(R.string.sentinel_report_open), onTap = { onOpen(report.id) })
                    CliRowDivider()
                }
            }
        }
        if (history.reports.isNotEmpty() || history.unavailable) {
            CliActionRow(stringResource(R.string.sentinel_report_clear), onTap = { confirming = true })
        }
        Text(stringResource(R.string.sentinel_report_retention), style = CliType.small, color = LocalCliColors.current.dim)
    }
}

private fun ScanReportStatus.statusResource(): Int = when (this) {
    ScanReportStatus.RUNNING -> R.string.sentinel_static_status_running
    ScanReportStatus.COMPLETE -> R.string.sentinel_static_status_complete
    ScanReportStatus.PARTIAL -> R.string.sentinel_static_status_partial
    ScanReportStatus.UNAVAILABLE -> R.string.sentinel_static_status_unavailable
    ScanReportStatus.UNSUPPORTED -> R.string.sentinel_static_status_unsupported
    ScanReportStatus.STALE -> R.string.sentinel_static_status_stale
    ScanReportStatus.CANCELLED -> R.string.sentinel_static_status_cancelled
    ScanReportStatus.INTERRUPTED -> R.string.sentinel_report_interrupted
}

private fun reportDate(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
