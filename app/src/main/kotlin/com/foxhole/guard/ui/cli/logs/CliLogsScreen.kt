package com.foxhole.guard.ui.cli.logs

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.foxhole.guard.core.sentinel.anomaly.userFacingMessage
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardEventType
import com.foxhole.guard.guardian.GuardJournalEntry
import com.foxhole.guard.guardian.GuardJournalReport
import com.foxhole.guard.guardian.GuardJournalStatus
import com.foxhole.guard.guardian.userFacingMessage
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.NETWORK_ACTIVITY_TAG
import com.foxhole.guard.ui.clearDiagnosticsLocalData
import com.foxhole.guard.ui.clearNetworkActivityLocalData
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliScaledDp
import com.foxhole.guard.ui.cli.cliScaledSp
import com.foxhole.guard.ui.cli.cliSlide
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliChromeTailSpacer
import com.foxhole.guard.ui.cli.components.CliFlagIcon
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliRetentionRow
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliSectionPreloader
import com.foxhole.guard.ui.cli.components.CliSheetAction
import com.foxhole.guard.ui.cli.components.CliSheetActionTone
import com.foxhole.guard.ui.cli.components.CliSheetActionsRow
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.emitError
import com.foxhole.guard.ui.emitSuccess
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

private enum class CliJournalActionsPage { ACTIONS, CONFIRM_CLEAR }

private enum class CliJournalExportKind { SANITIZED, NETWORK_ACTIVITY }

internal const val CLI_LOGS_SCREEN_TAG = "cli_logs_screen"
internal const val CLI_LOGS_EMPTY_TAG = "cli_logs_empty"
internal const val CLI_LOGS_LIST_TAG = "cli_logs_list"
internal const val CLI_LOGS_SETTINGS_TAG = "cli_logs_settings"
internal const val CLI_LOGS_CONTENT_TAG = "cli_logs_content"
internal const val CLI_LOGS_DOCK_TAG = "cli_logs_dock"
internal const val CLI_LOGS_ACTIONS_BUTTON_TAG = "cli_logs_actions_button"
internal const val CLI_LOGS_ACTIONS_SHEET_TAG = "cli_logs_actions_sheet"

private val CliLogTabSaver = Saver<CliLogTab, String>(
    save = { it.name },
    restore = { saved -> CliLogTab.entries.firstOrNull { it.name == saved } ?: CliLogTab.APP },
)

@Composable
internal fun CliLogsScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.diagnosticsRouteState.collectAsStateWithLifecycle()
    var tab by rememberSaveable(stateSaver = CliLogTabSaver) { mutableStateOf(CliLogTab.APP) }
    var actionsOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(CLI_LOGS_SCREEN_TAG)
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(
            label = stringResource(R.string.cli_cfg_more_journals),
            icon = R.drawable.pix_journal,
            trailing = {
                CliLogsActionsButton(onClick = { actionsOpen = true })
            },
        )
        if (!state.settingsHydrated) {
            CliSectionPreloader(text = stringResource(R.string.cli_common_loading_settings))
            return
        }
        when (tab) {
            CliLogTab.APP -> {
                CliJournalLayout(
                    tab = tab,
                    onTabSelected = { tab = it },
                    content = {
                        CliGroupedDiagnosticList(
                            entries = state.diagnosticEntries.filterNot { it.tag == NETWORK_ACTIVITY_TAG },
                            emptyText = stringResource(R.string.cli_logs_empty),
                        )
                    },
                    actions = {
                        CliJournalActionsSheet(
                            open = actionsOpen,
                            onDismiss = { actionsOpen = false },
                            viewModel = viewModel,
                            onClear = viewModel::clearDiagnosticsLocalData,
                            exportEntries = state.diagnosticEntries.filterNot { entry ->
                                entry.tag == NETWORK_ACTIVITY_TAG
                            },
                            exportTitle = stringResource(R.string.cli_logs_tab_app),
                            exportFileName = "foxhole-app-journal.txt",
                            settings = {
                                CliRetentionRow(
                                    label = stringResource(R.string.cli_logs_retention),
                                    icon = R.drawable.pix_clock,
                                    policy = state.settings.expert.effectiveDiagnosticsRetention(),
                                    onSelect = viewModel::onDiagnosticsRetentionSelected,
                                )
                            },
                        )
                    },
                )
            }
            CliLogTab.NET -> CliNetworkLog(
                viewModel = viewModel,
                tab = tab,
                onTabSelected = { tab = it },
                actionsOpen = actionsOpen,
                onDismissActions = { actionsOpen = false },
            )
            CliLogTab.SECURITY -> CliSecurityLog(
                viewModel = viewModel,
                tab = tab,
                onTabSelected = { tab = it },
                actionsOpen = actionsOpen,
                onDismissActions = { actionsOpen = false },
            )
        }
        CliChromeTailSpacer()
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CliJournalLayout(
    tab: CliLogTab,
    onTabSelected: (CliLogTab) -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    actions: @Composable () -> Unit,
) {
    CliPanel(
        title = stringResource(tab.labelRes),
        icon = R.drawable.pix_journal,
        modifier = Modifier.fillMaxWidth().weight(1f).testTag(CLI_LOGS_CONTENT_TAG),
        content = content,
    )
    Spacer(modifier = Modifier.height(CliSpacing.sm))
    CliJournalDock(
        tab = tab,
        onTabSelected = onTabSelected,
    )
    actions()
}

@Composable
private fun CliJournalDock(
    tab: CliLogTab,
    onTabSelected: (CliLogTab) -> Unit,
) {
    val colors = LocalCliColors.current
    Column(modifier = Modifier.fillMaxWidth().testTag(CLI_LOGS_DOCK_TAG)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
        ) {
            CliLogTab.entries.forEach { candidate ->
                CliChip(
                    label = stringResource(candidate.labelRes),
                    selected = tab == candidate,
                    color = colors.accent,
                    onClick = { onTabSelected(candidate) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CliJournalActionsSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    viewModel: HomeViewModel,
    onClear: (() -> Unit)?,
    exportEntries: List<DiagnosticEntry>,
    exportTitle: String,
    exportFileName: String,
    exportKind: CliJournalExportKind = CliJournalExportKind.SANITIZED,
    settings: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(R.string.cli_logs_save_ok)
    val saveFailedMessage = stringResource(R.string.cli_logs_save_failed)
    var exporting by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(CliJournalActionsPage.ACTIONS) }
    LaunchedEffect(open) {
        if (!open) page = CliJournalActionsPage.ACTIONS
    }
    val documentLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri: Uri? ->
            if (uri == null) {
                exporting = false
            } else {
                scope.launch {
                    val payload = withContext(Dispatchers.Default) {
                        when (exportKind) {
                            CliJournalExportKind.SANITIZED ->
                                formatSanitizedJournalExport(exportTitle, exportEntries)
                            CliJournalExportKind.NETWORK_ACTIVITY ->
                                formatNetworkJournalExport(exportTitle, exportEntries)
                        }
                    }
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
    if (open) {
        CliBottomSheet(
            onDismiss = onDismiss,
            title = stringResource(R.string.cli_logs_settings),
            icon = R.drawable.pix_settings,
            modifier = Modifier.testTag(CLI_LOGS_ACTIONS_SHEET_TAG),
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    cliSlide(forward = targetState == CliJournalActionsPage.CONFIRM_CLEAR)
                },
                label = "journal-actions-page",
            ) { currentPage ->
                when (currentPage) {
                    CliJournalActionsPage.ACTIONS ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(CLI_LOGS_SETTINGS_TAG),
                        ) {
                            settings()
                            Spacer(modifier = Modifier.height(CliSpacing.md))
                            CliJournalPrimaryActions(
                                clearEnabled = onClear != null,
                                saveEnabled = !exporting && exportEntries.isNotEmpty(),
                                onClear = { page = CliJournalActionsPage.CONFIRM_CLEAR },
                                onSave = {
                                    exporting = true
                                    runCatching { documentLauncher.launch(exportFileName) }
                                        .onFailure {
                                            exporting = false
                                            scope.launch { viewModel.emitError(saveFailedMessage) }
                                        }
                                },
                                onDismiss = onDismiss,
                            )
                        }
                    CliJournalActionsPage.CONFIRM_CLEAR ->
                        CliJournalClearConfirmation(
                            onBack = { page = CliJournalActionsPage.ACTIONS },
                            onConfirm = {
                                onDismiss()
                                onClear?.invoke()
                            },
                        )
                }
            }
        }
    }
}

@Composable
private fun CliJournalPrimaryActions(
    clearEnabled: Boolean,
    saveEnabled: Boolean,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
        ) {
            CliButton(
                label = stringResource(R.string.cli_logs_clear),
                filled = true,
                color = colors.err,
                enabled = clearEnabled,
                onClick = onClear,
                modifier = Modifier.weight(1f),
            )
            CliButton(
                label = stringResource(R.string.cli_logs_save),
                filled = true,
                color = colors.ok,
                enabled = saveEnabled,
                onClick = onSave,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliButton(
            label = stringResource(R.string.cli_common_no_cancel),
            color = colors.err,
            dashed = true,
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CliJournalClearConfirmation(
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalCliColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.cli_logs_clear_question),
            style = CliType.body,
            color = colors.fg,
        )
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliSheetActionsRow(
            onCancel = onBack,
            actions = listOf(
                CliSheetAction(
                    label = stringResource(R.string.cli_common_yes_confirm),
                    tone = CliSheetActionTone.DESTRUCTIVE,
                    onClick = onConfirm,
                ),
            ),
        )
    }
}

@Composable
private fun CliLogsActionsButton(onClick: () -> Unit) {
    val colors = LocalCliColors.current
    Box(
        modifier = Modifier
            .requiredSize(LOG_ACTIONS_BUTTON_SIZE)
            .testTag(CLI_LOGS_ACTIONS_BUTTON_TAG)
            .cliPressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CliPixIcon(
            id = R.drawable.pix_settings,
            contentDescription = stringResource(R.string.cli_logs_settings),
            size = 16.dp,
            tint = colors.accent,
        )
    }
}

private val LOG_ACTIONS_BUTTON_SIZE = 48.dp

@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.CliGroupedDiagnosticList(
    entries: List<DiagnosticEntry>,
    emptyText: String,
) = CliDiagnosticList(entries = entries, emptyText = emptyText, grouped = true)

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CliDiagnosticList(
    entries: List<DiagnosticEntry>,
    emptyText: String,
    grouped: Boolean = false,
    networkActivity: Boolean = false,
) {
    if (entries.isEmpty()) {
        val colors = LocalCliColors.current
        Text(
            text = emptyText,
            style = CliType.body,
            color = colors.dim,
            modifier = Modifier.testTag(CLI_LOGS_EMPTY_TAG),
        )
        return
    }
    val ordered = remember(entries, grouped) {
        if (grouped) {
            entries.sortedByDescending(DiagnosticEntry::timestamp)
        } else {
            entries.asReversed()
        }
    }
    val rows = remember(ordered, networkActivity) {
        ordered.map { entry ->
            if (networkActivity) networkJournalRow(entry) else diagnosticJournalRow(entry)
        }
    }
    CliJournalTable(rows = rows)
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CliJournalTable(rows: List<CliJournalRow>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .testTag(CLI_LOGS_LIST_TAG),
    ) {
        item(key = "journal-table-header") {
            CliJournalTableHeader()
            CliRowDivider()
        }
        itemsIndexed(
            rows,
            key = { index, row -> "journal:$index:${row.timestamp}:${row.eventType}" },
        ) { index, row ->
            if (index > 0) {
                CliRowDivider()
            }
            CliJournalTableRow(row)
        }
    }
}

@Composable
private fun CliJournalTableHeader() {
    val colors = LocalCliColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = CliSpacing.xs)) {
        Text(
            text = stringResource(R.string.cli_logs_column_time),
            style = CliType.small,
            color = colors.dim,
            textAlign = TextAlign.Start,
            maxLines = 1,
            modifier = Modifier.width(JOURNAL_TIMESTAMP_WIDTH),
        )
        Spacer(modifier = Modifier.width(JOURNAL_COLUMN_GAP))
        Row(modifier = Modifier.width(JOURNAL_EVENT_TYPE_WIDTH)) {
            Spacer(modifier = Modifier.width(JOURNAL_GLYPH_SIZE + JOURNAL_GLYPH_GAP))
            Text(
                text = stringResource(R.string.cli_logs_column_event_type),
                style = CliType.small,
                color = colors.dim,
                textAlign = TextAlign.Start,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.width(JOURNAL_COLUMN_GAP))
        Text(
            text = stringResource(R.string.cli_logs_column_description),
            style = CliType.small,
            color = colors.dim,
            textAlign = TextAlign.Start,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CliJournalTableRow(row: CliJournalRow) {
    val colors = LocalCliColors.current
    val toneColor = journalToneColor(row.tone, row.eventType, colors)
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = CliSpacing.xs)) {
        CliJournalTimestamp(row.timestamp)
        Spacer(modifier = Modifier.width(JOURNAL_COLUMN_GAP))
        Row(
            modifier = Modifier.width(JOURNAL_EVENT_TYPE_WIDTH),
            verticalAlignment = Alignment.Top,
        ) {
            CliPixIcon(
                id = journalToneIcon(row.tone),
                contentDescription = null,
                size = JOURNAL_GLYPH_SIZE,
                tint = toneColor,
            )
            Spacer(modifier = Modifier.width(JOURNAL_GLYPH_GAP))
            Text(
                text = row.eventType,
                style = CliType.small,
                color = toneColor,
                textAlign = TextAlign.Start,
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.width(JOURNAL_COLUMN_GAP))
        Column(modifier = Modifier.weight(1f)) {
            row.description.forEachIndexed { index, field ->
                if (index == 0 && row.flagCountry != null) {
                    Row(verticalAlignment = Alignment.Top) {
                        CliFlagIcon(countryCode = row.flagCountry, style = CliType.small)
                        Spacer(modifier = Modifier.width(JOURNAL_GLYPH_GAP))
                        CliJournalDescriptionLine(field = field, index = index, row = row)
                    }
                } else {
                    CliJournalDescriptionLine(field = field, index = index, row = row)
                }
            }
        }
    }
}

@Composable
private fun CliJournalDescriptionLine(field: String, index: Int, row: CliJournalRow) {
    val colors = LocalCliColors.current
    Text(
        text = field,
        style = CliType.small,
        color = when {
            index == 0 && row.tone == CliJournalRowTone.ERROR -> colors.err
            index == 0 -> colors.fg
            else -> colors.dim
        },
        maxLines = if (index == 0) 3 else 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun journalToneIcon(tone: CliJournalRowTone): Int = when (tone) {
    CliJournalRowTone.ERROR -> R.drawable.pix_forbidden
    CliJournalRowTone.WARNING -> R.drawable.pix_info
    CliJournalRowTone.SUCCESS -> R.drawable.pix_check
    CliJournalRowTone.INFO -> R.drawable.pix_link
    CliJournalRowTone.NORMAL, CliJournalRowTone.DIM -> R.drawable.pix_journal
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CliNetworkLog(
    viewModel: HomeViewModel,
    tab: CliLogTab,
    onTabSelected: (CliLogTab) -> Unit,
    actionsOpen: Boolean,
    onDismissActions: () -> Unit,
) {
    val colors = LocalCliColors.current
    val state by viewModel.diagnosticsRouteState.collectAsStateWithLifecycle()
    val enabled = state.settings.expert.networkActivityLogging
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val countryResolver = remember(appContext) { TorGeoIpCountryResolver(appContext) }
    val fallbackEntries = state.diagnosticEntries.takeIf { state.networkActivityEvents.isEmpty() }.orEmpty()
    val formatted by produceState<List<DiagnosticEntry>>(
        emptyList(),
        state.networkActivityEvents,
        fallbackEntries,
        state.ipInfo,
    ) {
        value = withContext(Dispatchers.Default) {
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
    CliJournalLayout(
        tab = tab,
        onTabSelected = onTabSelected,
        content = {
            CliDiagnosticList(
                entries = formatted,
                emptyText = stringResource(R.string.cli_common_empty_run_traffic),
                networkActivity = true,
            )
        },
        actions = {
            CliJournalActionsSheet(
                open = actionsOpen,
                onDismiss = onDismissActions,
                viewModel = viewModel,
                onClear = viewModel::clearNetworkActivityLocalData,
                exportEntries = formatted,
                exportTitle = stringResource(R.string.cli_logs_tab_net),
                exportFileName = "foxhole-network-journal.txt",
                exportKind = CliJournalExportKind.NETWORK_ACTIVITY,
                settings = {
                    if (!state.settingsHydrated) {
                        CliLoadingRow(
                            text = stringResource(R.string.cli_common_loading_settings),
                            small = true,
                        )
                    } else {
                        CliToggleRow(
                            label = stringResource(R.string.cli_logs_network_journal),
                            checked = enabled,
                            onToggle = viewModel::onNetworkActivityLoggingChanged,
                            infoText = stringResource(R.string.cli_logs_network_journal_note),
                        )
                        CliRetentionRow(
                            label = stringResource(R.string.cli_logs_retention),
                            icon = R.drawable.pix_clock,
                            policy = state.settings.expert.effectiveDiagnosticsRetention(),
                            onSelect = viewModel::onDiagnosticsRetentionSelected,
                        )
                        if (!enabled) {
                            Text(
                                text = stringResource(R.string.cli_logs_net_enable_hint),
                                style = CliType.small,
                                color = colors.dim,
                            )
                        }
                    }
                },
            )
        },
    )
}

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

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CliSecurityLog(
    viewModel: HomeViewModel,
    tab: CliLogTab,
    onTabSelected: (CliLogTab) -> Unit,
    actionsOpen: Boolean,
    onDismissActions: () -> Unit,
) {
    val colors = LocalCliColors.current
    val state by viewModel.diagnosticsRouteState.collectAsStateWithLifecycle()
    val statsState by viewModel.statisticsRouteState.collectAsStateWithLifecycle()
    var refreshToken by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(true) }
    var checkFailed by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<GuardJournalReport?>(null) }
    LaunchedEffect(refreshToken) {
        checking = true
        val result = runCatching { viewModel.loadGuardJournalReport() }
        report = result.getOrNull()
        checkFailed = result.isFailure
        checking = false
    }
    val changes = state.settings.installedAppInventoryAudit.recentChanges
    val anomalies = statsState.anomalyEvents
    val current = report
    val retention = state.settings.expert.effectiveDiagnosticsRetention()
    val retentionNow = remember(current, changes, anomalies, retention) { System.currentTimeMillis() }
    val cutoff = retention.cutoffOrNull(retentionNow) ?: Long.MIN_VALUE
    val merged = remember(current, changes, anomalies, cutoff) {
        buildList {
            current?.entries
                ?.filter { entry -> entry.record.wallClock >= cutoff }
                ?.forEach { add(SecJournalItem.Guard(it)) }
            changes.filter { change -> change.detectedAt >= cutoff }
                .forEach { add(SecJournalItem.AppChange(it)) }
            anomalies.filter { event -> event.createdAtMs >= cutoff }
                .forEach { add(SecJournalItem.Anomaly(it)) }
        }.sortedByDescending { it.timestamp }
    }
    val context = LocalContext.current
    val exportEntries = remember(merged, context) { merged.map { item -> item.toDiagnosticEntry(context) } }
    val displayRows = remember(merged, context) { merged.map { item -> item.toJournalRow(context) } }
    CliJournalLayout(
        tab = tab,
        onTabSelected = onTabSelected,
        content = {
            if (merged.isEmpty()) {
                if (!checking) {
                    Text(
                        text = stringResource(R.string.cli_logs_sec_empty),
                        style = CliType.body,
                        color = colors.dim,
                    )
                }
            } else {
                CliJournalTable(rows = displayRows)
            }
        },
        actions = {
            CliJournalActionsSheet(
                open = actionsOpen,
                onDismiss = onDismissActions,
                viewModel = viewModel,
                onClear = null,
                exportEntries = exportEntries,
                exportTitle = stringResource(R.string.cli_logs_tab_sec),
                exportFileName = "foxhole-security-journal.txt",
                settings = {
                    CliSecurityHeader(
                        report = report,
                        checking = checking,
                        failed = checkFailed,
                        onRecheck = { refreshToken++ },
                    )
                    CliRetentionRow(
                        label = stringResource(R.string.cli_logs_retention),
                        icon = R.drawable.pix_clock,
                        policy = retention,
                        onSelect = viewModel::onDiagnosticsRetentionSelected,
                    )
                },
            )
        },
    )
}

private fun SecJournalItem.toDiagnosticEntry(context: Context): DiagnosticEntry =
    when (this) {
        is SecJournalItem.Guard ->
            DiagnosticEntry(
                timestamp = timestamp,
                tag = "guard:${guardEntryTag(entry.event)}",
                message = entry.userFacingMessage(context),
            )
        is SecJournalItem.AppChange ->
            DiagnosticEntry(
                timestamp = timestamp,
                tag = "app-change:${change.type.name.lowercase()}",
                message = buildString {
                    append("package=")
                    append(change.packageName)
                    append(" · ")
                    append(
                        context.getString(
                            R.string.guard_event_risk,
                            context.getString(change.riskLevel.labelRes()),
                        ),
                    )
                },
            )
        is SecJournalItem.Anomaly ->
            DiagnosticEntry(
                timestamp = timestamp,
                tag = "anomaly:${event.severity.name.lowercase()}",
                message = event.userFacingMessage(context),
            )
    }

private fun SecJournalItem.toJournalRow(context: Context): CliJournalRow =
    when (this) {
        is SecJournalItem.Guard -> {
            val formatted = formatDiagnosticMessage(entry.userFacingMessage(context))
            journalRow(
                timestamp = timestamp,
                eventType = formatted.headline,
                description = formatted.details,
                tone = guardRowTone(entry.event),
            )
        }
        is SecJournalItem.AppChange -> {
            val typeLabel = context.getString(
                when (change.type) {
                    InstalledAppChangeType.INSTALLED -> R.string.guard_event_package_added
                    InstalledAppChangeType.REMOVED -> R.string.guard_event_package_removed
                },
            )
            journalRow(
                timestamp = timestamp,
                eventType = typeLabel,
                description = buildList {
                    add(change.label.ifEmpty { change.packageName })
                    add(change.packageName)
                    if (change.riskLevel != InstalledAppRiskLevel.LOW) {
                        add(
                            context.getString(
                                R.string.guard_event_risk,
                                context.getString(change.riskLevel.labelRes()),
                            ),
                        )
                    }
                },
                tone = if (change.type == InstalledAppChangeType.INSTALLED) {
                    CliJournalRowTone.SUCCESS
                } else {
                    CliJournalRowTone.ERROR
                },
            )
        }
        is SecJournalItem.Anomaly ->
            journalRow(
                timestamp = timestamp,
                eventType = event.type.name.lowercase().replace('_', ' '),
                description = listOf(event.userFacingMessage(context)),
                tone = if (event.severity == AnomalySeverity.HIGH) {
                    CliJournalRowTone.ERROR
                } else {
                    CliJournalRowTone.WARNING
                },
            )
    }

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
                        text = guardJournalStatusLabel(report.status),
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

private fun guardEntryTag(event: GuardEvent?): String {
    event ?: return "[?]"
    return when (event.type) {
        GuardEventType.PACKAGE_ADDED -> "[+]"
        GuardEventType.PACKAGE_REMOVED -> "[-]"
        GuardEventType.PACKAGE_REPLACED -> "[~]"
        else -> "[i]"
    }
}

private fun guardRowTone(event: GuardEvent?): CliJournalRowTone {
    event ?: return CliJournalRowTone.DIM
    return when (event.type) {
        GuardEventType.PACKAGE_ADDED -> CliJournalRowTone.SUCCESS
        GuardEventType.PACKAGE_REMOVED,
        GuardEventType.UNLOCK_FAILED,
        GuardEventType.BLACKOUT_SUSPECTED,
        GuardEventType.CLOCK_ANOMALY -> CliJournalRowTone.ERROR
        GuardEventType.PACKAGE_REPLACED,
        GuardEventType.SECURITY_SETTING_CHANGED,
        GuardEventType.HISTORY_CLEARED,
        GuardEventType.MONITORING_TOGGLE_ATTEMPT,
        GuardEventType.GUARD_DISABLE_REQUESTED,
        GuardEventType.GUARD_DISABLED -> CliJournalRowTone.WARNING
        else -> CliJournalRowTone.DIM
    }
}

@StringRes
private fun InstalledAppRiskLevel.labelRes(): Int =
    when (this) {
        InstalledAppRiskLevel.LOW -> R.string.guard_risk_low
        InstalledAppRiskLevel.MEDIUM -> R.string.guard_risk_medium
        InstalledAppRiskLevel.HIGH -> R.string.guard_risk_high
    }

@Composable
private fun guardJournalStatusLabel(status: GuardJournalStatus): String =
    stringResource(
        when (status) {
            GuardJournalStatus.OK -> R.string.guard_journal_status_ok
            GuardJournalStatus.GAPS -> R.string.guard_journal_status_gaps
            GuardJournalStatus.TRUNCATED -> R.string.guard_journal_status_truncated
            GuardJournalStatus.REWRITTEN -> R.string.guard_journal_status_rewritten
        },
    )

private fun tagColor(
    tag: String,
    colors: com.foxhole.guard.ui.cli.CliColors,
): androidx.compose.ui.graphics.Color = when (tag) {
    "connection", "runtime", "foxcore" -> colors.info
    "security", "app-inventory" -> colors.warn
    "tor" -> colors.tor
    else -> colors.dim
}

private fun journalToneColor(
    tone: CliJournalRowTone,
    eventType: String,
    colors: com.foxhole.guard.ui.cli.CliColors,
): androidx.compose.ui.graphics.Color =
    when (tone) {
        CliJournalRowTone.NORMAL -> tagColor(eventType, colors)
        CliJournalRowTone.INFO -> colors.info
        CliJournalRowTone.SUCCESS -> colors.ok
        CliJournalRowTone.WARNING -> colors.warn
        CliJournalRowTone.ERROR -> colors.err
        CliJournalRowTone.DIM -> colors.dim
    }

@Composable
private fun CliJournalTimestamp(timestamp: Long) {
    val colors = LocalCliColors.current
    Text(
        text = "[${CliFormat.clock(timestamp)}]",
        style = CliType.title.copy(fontSize = cliScaledSp(7f), lineHeight = CliType.small.lineHeight),
        color = colors.faint,
        maxLines = 1,
        textAlign = TextAlign.Start,
        modifier = Modifier.width(JOURNAL_TIMESTAMP_WIDTH),
    )
}

private val JOURNAL_GLYPH_SIZE = 12.dp
private val JOURNAL_GLYPH_GAP = 4.dp
private val JOURNAL_TIMESTAMP_WIDTH = cliScaledDp(72f)
private val JOURNAL_EVENT_TYPE_WIDTH = cliScaledDp(80f)
private val JOURNAL_COLUMN_GAP = 6.dp
