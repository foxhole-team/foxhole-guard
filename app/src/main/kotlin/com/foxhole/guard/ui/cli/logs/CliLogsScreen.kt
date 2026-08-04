package com.foxhole.guard.ui.cli.logs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.InstalledAppChangeType
import com.foxhole.core.model.InstalledAppInventoryChange
import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.core.model.effectiveDiagnosticsRetention
import com.foxhole.core.runtime.TorGeoIpCountryResolver
import com.foxhole.guard.R
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardEventType
import com.foxhole.guard.guardian.GuardJournalEntry
import com.foxhole.guard.guardian.GuardJournalReport
import com.foxhole.guard.guardian.GuardJournalStatus
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.NETWORK_ACTIVITY_TAG
import com.foxhole.guard.ui.clearDiagnosticsLocalData
import com.foxhole.guard.ui.clearNetworkActivityLocalData
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRetentionRow
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.createDiagnosticsArchive
import com.foxhole.guard.ui.emitError
import com.foxhole.guard.ui.emitSuccess
import com.foxhole.guard.ui.exportDiagnostics
import com.foxhole.guard.ui.fallbackNetworkDiagnosticEntries
import com.foxhole.guard.ui.loadGuardJournalReport
import com.foxhole.guard.ui.networkActivityDiagnosticEntries
import com.foxhole.guard.ui.onDiagnosticsRetentionSelected
import com.foxhole.guard.ui.onNetworkActivityLoggingChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CliLogTab(@StringRes val labelRes: Int) {
    APP(R.string.cli_logs_tab_app),
    NET(R.string.cli_logs_tab_net),
    SECURITY(R.string.cli_logs_tab_sec),
}

internal const val CLI_LOGS_SCREEN_TAG = "cli_logs_screen"
internal const val CLI_LOGS_EMPTY_TAG = "cli_logs_empty"
internal const val CLI_LOGS_LIST_TAG = "cli_logs_list"

/**
 * Restore by enum name with an APP fallback: a session saved on a retired tab must land on
 * APP, not crash the restore.
 */
private val CliLogTabSaver = Saver<CliLogTab, String>(
    save = { it.name },
    restore = { saved -> CliLogTab.entries.firstOrNull { it.name == saved } ?: CliLogTab.APP },
)

/**
 * The three journals (hosted under cfg → application → journals): the app journal, the network
 * connections journal (with its enable toggle) and the merged security journal.
 */
@Composable
internal fun CliLogsScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val state by viewModel.diagnosticsRouteState.collectAsStateWithLifecycle()
    var tab by rememberSaveable(stateSaver = CliLogTabSaver) { mutableStateOf(CliLogTab.APP) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(CLI_LOGS_SCREEN_TAG)
            .padding(horizontal = CliSpacing.md),
    ) {
        // No cli_dock_* name of its own — the header reuses the cfg entry label the user tapped.
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_journals), icon = R.drawable.pix_journal)
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            CliLogTab.entries.forEach { candidate ->
                CliChip(
                    label = stringResource(candidate.labelRes),
                    selected = tab == candidate,
                    color = colors.accent,
                    onClick = { tab = candidate },
                )
            }
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliPanel(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                // Without the NETWORK_ACTIVITY_TAG filter the activity tag floods the app journal.
                CliLogTab.APP -> {
                    // The retention policy had no surface at all: the setter existed, nothing
                    // called it, and the journal stayed pinned to the 24 h default forever.
                    CliRetentionRow(
                        label = stringResource(R.string.cli_logs_retention),
                        policy = state.settings.expert.effectiveDiagnosticsRetention(),
                        onSelect = viewModel::onDiagnosticsRetentionSelected,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    CliJournalActionsRow(
                        viewModel = viewModel,
                        onClear = viewModel::clearDiagnosticsLocalData,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    CliGroupedDiagnosticList(
                        entries = state.diagnosticEntries.filterNot { it.tag == NETWORK_ACTIVITY_TAG },
                        emptyText = stringResource(R.string.cli_logs_empty),
                    )
                }
                CliLogTab.NET -> CliNetworkLog(viewModel = viewModel)
                CliLogTab.SECURITY -> CliSecurityLog(viewModel = viewModel)
            }
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

/**
 * Clear/save journal action pair. APP shares the sanitized diagnostics archive via the system
 * share sheet; NET/SEC pass the exact visible entries through SAF — always redacted.
 */
@Composable
private fun CliJournalActionsRow(
    viewModel: HomeViewModel,
    onClear: (() -> Unit)?,
    exportEntries: List<DiagnosticEntry>? = null,
    exportTitle: String = "journal",
    exportFileName: String = "foxhole-journal.txt",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Resolved in composition: LocalContext resources inside the launcher callback are a lint
    // error and read whatever configuration is current when the picker returns.
    val savedMessage = stringResource(R.string.cli_logs_save_ok)
    val saveFailedMessage = stringResource(R.string.cli_logs_save_failed)
    var exporting by remember { mutableStateOf(false) }
    // No pending snapshot: the launcher callback sees the CURRENT exportEntries/exportTitle
    // (rememberLauncherForActivityResult keeps the lambda fresh) and the export survives process
    // death while the system picker is open — a remember-snapshot died there.
    val documentLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri: Uri? ->
            val entries = exportEntries
            if (uri != null && entries != null) {
                scope.launch {
                    exporting = true
                    val payload = withContext(Dispatchers.Default) {
                        formatSanitizedJournalExport(exportTitle, entries)
                    }
                    // The write can fail (no space, revoked uri) — without checking, "save"
                    // stayed silent on success and failure alike.
                    val written = writeJournalExport(context.contentResolver, uri, payload)
                    exporting = false
                    if (written) {
                        viewModel.emitSuccess(savedMessage)
                    } else {
                        viewModel.emitError(saveFailedMessage)
                    }
                }
            }
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        onClear?.let { clear ->
            CliButton(
                label = stringResource(R.string.cli_logs_clear),
                onClick = clear,
                modifier = Modifier.weight(1f),
            )
        }
        CliButton(
            label = stringResource(R.string.cli_logs_save),
            enabled = !exporting && (exportEntries == null || exportEntries.isNotEmpty()),
            onClick = {
                if (exportEntries != null) {
                    documentLauncher.launch(exportFileName)
                } else {
                    exporting = true
                    scope.launch {
                        val intent = runCatching {
                            withContext(Dispatchers.IO) {
                                viewModel.exportDiagnostics(
                                    viewModel.createDiagnosticsArchive(),
                                )
                            }
                        }.getOrNull()
                        exporting = false
                        intent?.let { context.startActivity(Intent.createChooser(it, null)) }
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** Grouped-mode wrapper — the name keeps the androidTest and APP-tab contract. */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.CliGroupedDiagnosticList(
    entries: List<DiagnosticEntry>,
    emptyText: String,
) = CliDiagnosticList(entries = entries, emptyText = emptyText, grouped = true)

/**
 * One journal list for both modes: [grouped] groups by tag (`[tag · N]` header, tag leaves the
 * rows), flat mode keeps the tag inline. Newest first in both.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CliDiagnosticList(
    entries: List<DiagnosticEntry>,
    emptyText: String,
    grouped: Boolean = false,
) {
    val colors = LocalCliColors.current
    if (entries.isEmpty()) {
        Text(
            text = emptyText,
            style = CliType.body,
            color = colors.dim,
            modifier = Modifier.testTag(CLI_LOGS_EMPTY_TAG),
        )
        return
    }
    val groups = if (grouped) {
        remember(entries) {
            entries
                .groupBy(DiagnosticEntry::tag)
                .entries
                .sortedByDescending { (_, values) -> values.maxOf(DiagnosticEntry::timestamp) }
        }
    } else {
        null
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .testTag(CLI_LOGS_LIST_TAG),
    ) {
        if (groups != null) {
            groups.forEach { (tag, values) ->
                item(key = "header:$tag") {
                    Text(
                        text = "[$tag · ${values.size}]",
                        style = CliType.small,
                        color = tagColor(tag, colors),
                        modifier = Modifier.padding(top = CliSpacing.xs, bottom = 2.dp),
                    )
                }
                // The index must be part of the key: timestamp+message is NOT unique — a
                // diagnostic logged twice in one millisecond produced duplicate keys and an
                // IllegalArgumentException crash of the journals screen.
                itemsIndexed(
                    values.asReversed(),
                    key = { index, entry -> "$tag:$index:${entry.timestamp}" },
                ) { _, entry ->
                    CliDiagnosticEntryRow(entry = entry, showTag = false)
                }
            }
        } else {
            // Newest first - a phone journal is read from the top.
            itemsIndexed(
                entries.asReversed(),
                key = { index, entry -> "flat:$index:${entry.timestamp}" },
            ) { _, entry ->
                CliDiagnosticEntryRow(entry = entry, showTag = true)
            }
        }
    }
}

@Composable
private fun CliDiagnosticEntryRow(entry: DiagnosticEntry, showTag: Boolean) {
    val colors = LocalCliColors.current
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "[${CliFormat.clock(entry.timestamp)}] ",
            style = CliType.small,
            color = colors.faint,
        )
        if (showTag) {
            Text(
                text = "${entry.tag} ",
                style = CliType.small,
                color = tagColor(entry.tag, colors),
            )
        }
        Text(
            text = entry.message,
            style = CliType.small,
            color = colors.fg,
            maxLines = 4,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Network connections journal: always a tab, never gated on statistics. While logging is off
 * the tab still lists whatever the runtime already produced, under a dim hint.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CliNetworkLog(viewModel: HomeViewModel) {
    val colors = LocalCliColors.current
    val state by viewModel.diagnosticsRouteState.collectAsStateWithLifecycle()
    if (!state.settingsHydrated) {
        Text(
            text = stringResource(R.string.cli_common_loading_settings),
            style = CliType.small,
            color = colors.dim,
        )
        return
    }
    val enabled = state.settings.expert.networkActivityLogging
    CliToggleRow(
        label = stringResource(R.string.cli_logs_network_journal),
        checked = enabled,
        onToggle = viewModel::onNetworkActivityLoggingChanged,
        note = stringResource(R.string.cli_logs_network_journal_note),
    )
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val countryResolver = remember(appContext) { TorGeoIpCountryResolver(appContext) }
    // The connections journal is raw by design (exports keep their own sanitizer). The
    // diagnosticEntries key is narrowed to the empty-events fallback case — otherwise every
    // unrelated diagnostic emit re-ran the whole journal (package resolves + geoip).
    val fallbackEntries = state.diagnosticEntries.takeIf { state.networkActivityEvents.isEmpty() }.orEmpty()
    val formatted by produceState<List<DiagnosticEntry>>(
        emptyList(),
        state.networkActivityEvents,
        fallbackEntries,
        state.ipInfo,
    ) {
        value = withContext(Dispatchers.Default) {
            // The structured event table is gated on statistics settings that default OFF; the
            // runtime journals the same connections as "activity" entries — fall back to those.
            networkActivityDiagnosticEntries(
                events = state.networkActivityEvents,
                context = appContext as Context,
                countryResolver = countryResolver,
                ipInfo = state.ipInfo,
            ).ifEmpty {
                fallbackNetworkDiagnosticEntries(
                    entries = fallbackEntries,
                )
            }
        }
    }
    CliJournalActionsRow(
        viewModel = viewModel,
        onClear = viewModel::clearNetworkActivityLocalData,
        exportEntries = formatted,
        exportTitle = stringResource(R.string.cli_logs_tab_net),
        exportFileName = "foxhole-network-journal.txt",
    )
    if (!enabled) {
        Text(
            text = stringResource(R.string.cli_logs_net_enable_hint),
            style = CliType.small,
            color = colors.dim,
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
    CliDiagnosticList(
        entries = formatted,
        emptyText = stringResource(R.string.cli_common_empty_run_traffic),
    )
}

/** One security-journal line; wraps the three sources so they merge chronologically. */
private sealed interface SecJournalItem {
    val timestamp: Long

    data class Guard(val entry: GuardJournalEntry) : SecJournalItem {
        override val timestamp: Long get() = entry.record.wallClock
    }

    data class AppChange(val change: InstalledAppInventoryChange) : SecJournalItem {
        override val timestamp: Long get() = change.detectedAt
    }

    data class Anomaly(val event: AnomalyEvent) : SecJournalItem {
        override val timestamp: Long get() = event.createdAtMs
    }
}

/**
 * Merged security journal: sealed guard chain + app-install changes + anomaly history, newest
 * first. Reading the guard chain IS a verify pass (a clean run re-anchors the checkpoint): one
 * pass per tab visit plus the manual recheck chip — no auto-refresh loops. Null report = guard
 * keys unavailable (locked); the other two sources still render.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CliSecurityLog(viewModel: HomeViewModel) {
    val colors = LocalCliColors.current
    val state by viewModel.diagnosticsRouteState.collectAsStateWithLifecycle()
    // Anomaly history rides the statistics route only; the diagnostics route does not carry it.
    val statsState by viewModel.statisticsRouteState.collectAsStateWithLifecycle()
    var refreshToken by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(true) }
    var checkFailed by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<GuardJournalReport?>(null) }
    // One-shot per tab visit plus manual recheck: continuously walking the sealed chain wastes
    // idle work. runCatching is mandatory: verifyGuardJournal() reads files the sentinel rotates
    // in parallel and has try/finally with NO catch — a thrown exception killed the recomposer.
    LaunchedEffect(refreshToken) {
        checking = true
        val result = runCatching { viewModel.loadGuardJournalReport() }
        report = result.getOrNull()
        checkFailed = result.isFailure
        checking = false
    }
    CliSecurityHeader(
        report = report,
        checking = checking,
        failed = checkFailed,
        onRecheck = { refreshToken++ },
    )
    Spacer(modifier = Modifier.height(6.dp))
    val changes = state.settings.installedAppInventoryAudit.recentChanges
    val anomalies = statsState.anomalyEvents
    val current = report
    val merged = remember(current, changes, anomalies) {
        buildList {
            current?.entries?.forEach { add(SecJournalItem.Guard(it)) }
            changes.forEach { add(SecJournalItem.AppChange(it)) }
            anomalies.forEach { add(SecJournalItem.Anomaly(it)) }
        }.sortedByDescending { it.timestamp }
    }
    val exportEntries = remember(merged) { merged.map(SecJournalItem::toDiagnosticEntry) }
    CliJournalActionsRow(
        viewModel = viewModel,
        onClear = null,
        exportEntries = exportEntries,
        exportTitle = stringResource(R.string.cli_logs_tab_sec),
        exportFileName = "foxhole-security-journal.txt",
    )
    Spacer(modifier = Modifier.height(6.dp))
    if (merged.isEmpty()) {
        if (!checking) {
            Text(
                text = stringResource(R.string.cli_logs_sec_empty),
                style = CliType.body,
                color = colors.dim,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
        items(merged) { item ->
            when (item) {
                is SecJournalItem.Guard -> CliGuardEntryRow(item.entry)
                is SecJournalItem.AppChange -> CliAppChangeRow(item.change)
                is SecJournalItem.Anomaly -> CliAnomalyRow(item.event)
            }
        }
    }
}

private fun SecJournalItem.toDiagnosticEntry(): DiagnosticEntry =
    when (this) {
        is SecJournalItem.Guard ->
            DiagnosticEntry(
                timestamp = timestamp,
                tag = "guard:${guardEntryTag(entry.event)}",
                message = guardEntryMessage(entry),
            )
        is SecJournalItem.AppChange ->
            DiagnosticEntry(
                timestamp = timestamp,
                tag = "app-change:${change.type.name.lowercase()}",
                message = buildString {
                    append("package=")
                    append(change.packageName)
                    append(" · risk=")
                    append(change.riskLevel.name.lowercase())
                },
            )
        is SecJournalItem.Anomaly ->
            DiagnosticEntry(
                timestamp = timestamp,
                tag = "anomaly:${event.severity.name.lowercase()}",
                message = anomalyMessage(event),
            )
    }

/** Integrity summary of the guard chain (or its checking/locked state) + the recheck chip. */
@Composable
private fun CliSecurityHeader(
    report: GuardJournalReport?,
    checking: Boolean,
    failed: Boolean,
    onRecheck: () -> Unit,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            when {
                checking -> CliLoadingRow(
                    text = stringResource(R.string.cli_journal_checking),
                    small = true,
                )
                // A failed check is NOT "no keys": the user needs a reason to hit recheck,
                // not an eternal "verifying…".
                failed -> Text(
                    text = stringResource(R.string.cli_journal_check_failed),
                    style = CliType.small,
                    color = colors.err,
                    maxLines = 2,
                )
                report == null -> Text(
                    text = stringResource(R.string.cli_journal_locked),
                    style = CliType.small,
                    color = colors.dim,
                    maxLines = 2,
                )
                else -> {
                    val statusColor = when (report.status) {
                        GuardJournalStatus.OK -> colors.ok
                        GuardJournalStatus.GAPS -> colors.warn
                        GuardJournalStatus.TRUNCATED, GuardJournalStatus.REWRITTEN -> colors.err
                    }
                    Text(
                        text = stringResource(R.string.cli_journal_status) + " ",
                        style = CliType.small,
                        color = colors.dim,
                    )
                    Text(
                        text = report.status.name.lowercase(),
                        style = CliType.small,
                        color = statusColor,
                    )
                    Text(
                        text = " · ${report.entries.size}",
                        style = CliType.small,
                        color = colors.dim,
                    )
                    if (report.anomalies.isNotEmpty()) {
                        Text(
                            text = " · ${stringResource(R.string.cli_journal_anomalies)} ${report.anomalies.size}",
                            style = CliType.small,
                            color = colors.warn,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        CliChip(label = stringResource(R.string.cli_logs_guard_recheck), onClick = onRecheck)
    }
}

@Composable
private fun CliAppChangeRow(change: InstalledAppInventoryChange) {
    val colors = LocalCliColors.current
    Row(modifier = Modifier.padding(vertical = CliSpacing.xs)) {
        val marker = when (change.type) {
            InstalledAppChangeType.INSTALLED -> "[+]"
            InstalledAppChangeType.REMOVED -> "[-]"
        }
        val markerColor = when (change.type) {
            InstalledAppChangeType.INSTALLED -> colors.ok
            InstalledAppChangeType.REMOVED -> colors.err
        }
        Text(text = "$marker ", style = CliType.small, color = markerColor)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = change.label.ifEmpty { change.packageName },
                style = CliType.small,
                color = colors.fg,
                maxLines = 1,
            )
            Text(
                text = change.packageName +
                    " · ${CliFormat.clock(change.detectedAt)}" +
                    if (change.riskLevel != InstalledAppRiskLevel.LOW) {
                        " · risk:${change.riskLevel.name.lowercase()}"
                    } else {
                        ""
                    },
                style = CliType.small,
                color = if (change.riskLevel != InstalledAppRiskLevel.LOW) colors.warn else colors.faint,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CliAnomalyRow(event: AnomalyEvent) {
    val colors = LocalCliColors.current
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "[${CliFormat.clock(event.createdAtMs)}] ",
            style = CliType.small,
            color = colors.faint,
        )
        Text(
            text = "[!] ",
            style = CliType.small,
            color = if (event.severity == AnomalySeverity.HIGH) colors.err else colors.warn,
        )
        Text(
            text = anomalyMessage(event),
            style = CliType.small,
            color = colors.fg,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun anomalyMessage(event: AnomalyEvent): String {
    val parts = mutableListOf(event.type.name.lowercase())
    event.packageName?.let { parts += it }
    if (event.reason.isNotBlank()) parts += event.reason
    return parts.joinToString(" · ")
}

@Composable
private fun CliGuardEntryRow(entry: GuardJournalEntry) {
    val colors = LocalCliColors.current
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "[${CliFormat.clock(entry.record.wallClock)}] ",
            style = CliType.small,
            color = colors.faint,
        )
        Text(
            text = guardEntryTag(entry.event) + " ",
            style = CliType.small,
            color = guardTagColor(entry.event, colors),
        )
        Text(
            text = guardEntryMessage(entry),
            style = CliType.small,
            color = if (entry.event == null) colors.dim else colors.fg,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
    }
}

/** `[+]`/`[-]`/`[~]` for package events, the event word otherwise, `sealed` when unreadable. */
private fun guardEntryTag(event: GuardEvent?): String {
    event ?: return "sealed"
    return when (event.type) {
        GuardEventType.PACKAGE_ADDED -> "[+]"
        GuardEventType.PACKAGE_REMOVED -> "[-]"
        GuardEventType.PACKAGE_REPLACED -> "[~]"
        else -> event.type.name.lowercase()
    }
}

private fun guardTagColor(
    event: GuardEvent?,
    colors: com.foxhole.guard.ui.cli.CliColors,
): androidx.compose.ui.graphics.Color {
    event ?: return colors.faint
    return when (event.type) {
        GuardEventType.PACKAGE_ADDED -> colors.ok
        GuardEventType.PACKAGE_REMOVED,
        GuardEventType.UNLOCK_FAILED,
        GuardEventType.BLACKOUT_SUSPECTED,
        GuardEventType.CLOCK_ANOMALY -> colors.err
        GuardEventType.PACKAGE_REPLACED,
        GuardEventType.SECURITY_SETTING_CHANGED,
        GuardEventType.HISTORY_CLEARED,
        GuardEventType.MONITORING_TOGGLE_ATTEMPT,
        GuardEventType.GUARD_DISABLE_REQUESTED,
        GuardEventType.GUARD_DISABLED -> colors.warn
        else -> colors.dim
    }
}

/** Compact payload line: package/service/detail plus the notable numeric bits. */
private fun guardEntryMessage(entry: GuardJournalEntry): String {
    val event = entry.event ?: return "#${entry.record.seq}"
    val parts = mutableListOf<String>()
    event.packageName?.let { parts += it }
    event.service?.let { parts += it }
    event.detail?.let { parts += it }
    event.riskLevel?.let { risk ->
        if (!risk.equals("low", ignoreCase = true)) {
            parts += "risk:${risk.lowercase()}"
        }
    }
    event.gapMs?.let { parts += "gap=${it / 1000}s" }
    event.attempt?.let { parts += "attempt=$it" }
    return parts.joinToString(" · ").ifEmpty { "#${entry.record.seq}" }
}

private fun tagColor(
    tag: String,
    colors: com.foxhole.guard.ui.cli.CliColors,
): androidx.compose.ui.graphics.Color = when (tag) {
    "connection", "runtime", "foxcore" -> colors.info
    "security", "app-inventory" -> colors.warn
    "tor" -> colors.tor
    else -> colors.dim
}
