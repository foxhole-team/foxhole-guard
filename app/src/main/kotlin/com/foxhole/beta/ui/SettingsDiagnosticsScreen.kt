package com.foxhole.beta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.R
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.diagnostics.DiagnosticSanitizer
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.InstalledAppChangeType
import com.foxhole.beta.core.model.InstalledAppInventoryChange
import com.foxhole.beta.core.model.InstalledAppRiskLevel
import com.foxhole.beta.core.model.StatisticsMetric
import com.foxhole.beta.core.security.labelRes
import com.foxhole.beta.ui.theme.LocalFoxholeSemanticColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onNetworkActivityLoggingChanged: (Boolean) -> Unit,
    onNetworkActivityPersistentLoggingChanged: (Boolean) -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onDiagnosticsRetentionSelected: (DiagnosticsRetention) -> Unit,
    onRawLiveDiagnosticsChanged: (Boolean) -> Unit,
    onStatisticsMetricEnabledChanged: (StatisticsMetric, Boolean) -> Unit,
    onOpenSecurityAppMonitorSettings: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var networkLogVisible by rememberSaveable { mutableStateOf(false) }
    var foxholeLogVisible by rememberSaveable { mutableStateOf(false) }
    var appChangesLogVisible by rememberSaveable { mutableStateOf(false) }
    var appChangesEnableVisible by rememberSaveable { mutableStateOf(false) }
    var retentionMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingSavedLog by remember { mutableStateOf<SavedLogPayload?>(null) }
    val saveStrings =
        SavedLogStrings(
            saved = stringResource(R.string.log_file_saved),
            failed = stringResource(R.string.log_file_save_failed),
        )
    val textLogSaver =
        rememberTextLogSaver(
            context = context,
            coroutineScope = coroutineScope,
            snackbarHostState = snackbarHostState,
            pendingLog = { pendingSavedLog },
            clearPendingLog = { pendingSavedLog = null },
            strings = saveStrings,
        )
    val networkEntries =
        remember(state.diagnosticEntries) {
            state.diagnosticEntries.filter { it.tag == NETWORK_ACTIVITY_TAG }
        }
    val foxholeEntries =
        remember(state.diagnosticEntries) {
            state.diagnosticEntries.filterNot { it.tag == NETWORK_ACTIVITY_TAG }
        }

    DiagnosticsScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        retentionMenuExpanded = retentionMenuExpanded,
        onRetentionMenuExpandedChange = { retentionMenuExpanded = it },
        onNavigateUp = onNavigateUp,
        onNetworkActivityLoggingChanged = onNetworkActivityLoggingChanged,
        onNetworkActivityPersistentLoggingChanged = onNetworkActivityPersistentLoggingChanged,
        onFirewallEnabledChanged = onFirewallEnabledChanged,
        onDiagnosticsRetentionSelected = onDiagnosticsRetentionSelected,
        onRawLiveDiagnosticsChanged = onRawLiveDiagnosticsChanged,
        onOpenNetworkLog = { networkLogVisible = true },
        onOpenFoxholeLog = { foxholeLogVisible = true },
        onOpenAppChangesLog = {
            if (state.settings.statistics.appChangesEnabled) {
                appChangesLogVisible = true
            } else {
                appChangesEnableVisible = true
            }
        },
    )

    if (networkLogVisible) {
        val title = stringResource(R.string.network_activity_log_title)
        LiveLogsDialog(
            title = title,
            entries = networkEntries,
            notice = stringResource(R.string.logs_network_activity_notice),
            onDismiss = { networkLogVisible = false },
            confirmLabel = stringResource(R.string.save_log),
            onConfirm = {
                pendingSavedLog =
                    SavedLogPayload(
                        filename = "foxhole-network-activity-${System.currentTimeMillis()}.log",
                        text = formatPlainLog(title, networkEntries, sanitize = false),
                    )
                textLogSaver.launch(pendingSavedLog?.filename ?: "foxhole-network-activity.log")
            },
        )
    }

    if (foxholeLogVisible) {
        val title = stringResource(R.string.logs_title)
        LiveLogsDialog(
            title = title,
            entries = foxholeEntries,
            onDismiss = { foxholeLogVisible = false },
            confirmLabel = stringResource(R.string.send_log_to_bot),
            onConfirm = {
                coroutineScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            createPlainLogFile(
                                context = context,
                                title = title,
                                entries = foxholeEntries,
                                filenamePrefix = "foxhole-app-log",
                            )
                        }
                    }.onSuccess { file ->
                        context.startActivity(Intent.createChooser(sharePlainLogIntent(context, file), title))
                    }.onFailure {
                        snackbarHostState.showBanner(
                            it.message ?: saveStrings.failed,
                            FoxholeBannerTone.ERROR,
                        )
                    }
                }
            },
        )
    }

    if (appChangesLogVisible) {
        InstalledAppChangesJournalDialog(
            changes = state.settings.installedAppInventoryAudit.recentChanges,
            onDismiss = { appChangesLogVisible = false },
        )
    }

    if (appChangesEnableVisible) {
        AppChangesJournalEnableDialog(
            onDismiss = { appChangesEnableVisible = false },
            onConfirm = {
                appChangesEnableVisible = false
                onStatisticsMetricEnabledChanged(StatisticsMetric.APP_CHANGES, true)
                onOpenSecurityAppMonitorSettings()
            },
        )
    }
}

@Composable
private fun AppChangesJournalEnableDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.app_changes_journal_enable_title),
        body = stringResource(R.string.app_changes_journal_enable_body),
        confirmLabel = stringResource(R.string.app_changes_journal_go_to_security),
        dismissLabel = stringResource(R.string.cancel),
        icon = Icons.Outlined.Apps,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun DiagnosticsScreenContent(
    state: DiagnosticsRouteUiState,
    snackbarHostState: SnackbarHostState,
    retentionMenuExpanded: Boolean,
    onRetentionMenuExpandedChange: (Boolean) -> Unit,
    onNavigateUp: () -> Unit,
    onNetworkActivityLoggingChanged: (Boolean) -> Unit,
    onNetworkActivityPersistentLoggingChanged: (Boolean) -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onDiagnosticsRetentionSelected: (DiagnosticsRetention) -> Unit,
    onRawLiveDiagnosticsChanged: (Boolean) -> Unit,
    onOpenNetworkLog: () -> Unit,
    onOpenFoxholeLog: () -> Unit,
    onOpenAppChangesLog: () -> Unit,
) {
    var persistentLoggingWarningVisible by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(
        title = stringResource(R.string.diagnostics_and_usage),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        actions = {
            SettingsHelpAction(
                title = stringResource(R.string.help_diagnostics_support_title),
                body = stringResource(R.string.help_diagnostics_support_body),
                icon = Icons.Outlined.FileUpload,
            )
        },
    ) {
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.network_activity_log_title),
                    checked = state.settings.expert.networkActivityLogging,
                    summary = stringResource(R.string.logs_network_activity_notice),
                    leadingIcon = Icons.Outlined.Public,
                    onCheckedChange = onNetworkActivityLoggingChanged,
                    grouped = true,
                )
                if (state.settings.expert.networkActivityLogging) {
                    SettingsControlGroupDivider()
                    DropdownSettingRow(
                        title = stringResource(R.string.diagnostics_retention_title),
                        value = diagnosticsRetentionLabel(state.settings.expert.diagnosticsRetention),
                        expanded = retentionMenuExpanded,
                        onExpandedChange = onRetentionMenuExpandedChange,
                        values = NetworkActivityRetentionValues,
                        selected = state.settings.expert.diagnosticsRetention,
                        label = { diagnosticsRetentionLabel(it) },
                        onSelect = onDiagnosticsRetentionSelected,
                        summary = stringResource(R.string.diagnostics_retention_summary),
                        leadingIcon = Icons.Outlined.Public,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.network_activity_persistent_logging_title),
                        checked = state.settings.expert.firewallEnabled && state.settings.expert.networkActivityPersistentLogging,
                        summary = stringResource(R.string.network_activity_persistent_logging_summary),
                        leadingIcon = Icons.Outlined.Public,
                        onCheckedChange = { enabled ->
                            if (enabled && !state.settings.expert.firewallEnabled) {
                                persistentLoggingWarningVisible = true
                            } else {
                                onNetworkActivityPersistentLoggingChanged(enabled)
                            }
                        },
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    SettingsNavigationRow(
                        icon = Icons.Outlined.Public,
                        title = stringResource(R.string.open_network_activity_log),
                        grouped = true,
                        onClick = onOpenNetworkLog,
                    )
                }
                SettingsControlGroupDivider()
                SettingsNavigationRow(
                    icon = Icons.Outlined.FileUpload,
                    title = stringResource(R.string.logs_title),
                    grouped = true,
                    onClick = onOpenFoxholeLog,
                )
                SettingsControlGroupDivider()
                if (BuildConfig.DEBUG) {
                    SettingSwitchRow(
                        title = stringResource(R.string.raw_live_diagnostics_title),
                        checked = state.settings.expert.rawLiveDiagnostics,
                        summary = stringResource(R.string.raw_live_diagnostics_summary),
                        leadingIcon = Icons.Outlined.FileUpload,
                        onCheckedChange = onRawLiveDiagnosticsChanged,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                }
                SettingsNavigationRow(
                    icon = Icons.Outlined.Apps,
                    title = stringResource(R.string.app_changes_journal_title),
                    summary =
                    if (state.settings.statistics.appChangesEnabled) {
                        stringResource(R.string.app_changes_journal_summary)
                    } else {
                        stringResource(R.string.app_changes_journal_disabled_summary)
                    },
                    showAlertDot = state.settings.installedAppInventoryAudit.recentChanges.isNotEmpty(),
                    grouped = true,
                    onClick = onOpenAppChangesLog,
                )
            }
        }
    }

    if (persistentLoggingWarningVisible) {
        ConfirmDialog(
            title = stringResource(R.string.network_activity_persistent_logging_warning_title),
            body = stringResource(R.string.network_activity_persistent_logging_warning_body),
            confirmLabel = stringResource(R.string.security_firewall_enable_action),
            dismissLabel = stringResource(R.string.close),
            icon = ImageVector.vectorResource(R.drawable.ic_firewall_shield_key),
            onDismiss = { persistentLoggingWarningVisible = false },
            onConfirm = {
                persistentLoggingWarningVisible = false
                onFirewallEnabledChanged(true)
                onNetworkActivityPersistentLoggingChanged(true)
            },
        )
    }
}

@Composable
private fun InstalledAppChangesJournalDialog(
    changes: List<InstalledAppInventoryChange>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_changes_journal_title)) },
        text = {
            if (changes.isEmpty()) {
                Text(
                    text = stringResource(R.string.app_changes_journal_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    items(
                        changes,
                        key = { change -> "${change.packageName}-${change.type}-${change.detectedAt}" },
                    ) { change ->
                        InstalledAppChangeJournalRow(change = change)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun InstalledAppChangeJournalRow(change: InstalledAppInventoryChange) {
    val semanticColors = LocalFoxholeSemanticColors.current
    val changeColor =
        when (change.type) {
            InstalledAppChangeType.INSTALLED -> semanticColors.warning
            InstalledAppChangeType.REMOVED -> MaterialTheme.colorScheme.error
        }
    val riskColor =
        when (change.riskLevel) {
            InstalledAppRiskLevel.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
            InstalledAppRiskLevel.MEDIUM -> semanticColors.warning
            InstalledAppRiskLevel.HIGH -> MaterialTheme.colorScheme.error
        }
    val source = change.installerPackageName ?: stringResource(R.string.installed_app_source_unknown)
    val signalLabels = change.riskSignals.map { signal -> stringResource(signal.labelRes()) }
    val signals =
        signalLabels
            .joinToString(" • ")
            .ifBlank { stringResource(R.string.installed_app_risk_signals_none) }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Canvas(modifier = Modifier.padding(top = 5.dp).size(10.dp)) {
                drawCircle(changeColor)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = change.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                    stringResource(
                        when (change.type) {
                            InstalledAppChangeType.INSTALLED -> R.string.statistics_app_change_installed
                            InstalledAppChangeType.REMOVED -> R.string.statistics_app_change_removed
                        },
                        change.packageName,
                        formatJournalRelativeTime(change.detectedAt),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                    stringResource(
                        R.string.statistics_app_change_security_summary,
                        source,
                        stringResource(change.riskLevel.labelRes()),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = riskColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = signals,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
private fun formatJournalRelativeTime(timestampMs: Long): String =
    timestampMs
        .takeIf { timestamp -> timestamp > 0L }
        ?.let { timestamp ->
            DateUtils.getRelativeTimeSpanString(
                timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
        }
        ?: stringResource(R.string.statistics_no_data)

@Composable
private fun rememberTextLogSaver(
    context: Context,
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    pendingLog: () -> SavedLogPayload?,
    clearPendingLog: () -> Unit,
    strings: SavedLogStrings,
): ManagedActivityResultLauncher<String, Uri?> =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val payload = pendingLog()
        clearPendingLog()
        if (uri == null || payload == null) {
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                        writer.write(payload.text)
                    } ?: error("openOutputStream returned null")
                }
            }.onSuccess {
                snackbarHostState.showBanner(strings.saved, FoxholeBannerTone.SUCCESS)
            }.onFailure {
                snackbarHostState.showBanner(
                    it.message ?: strings.failed,
                    FoxholeBannerTone.ERROR,
                )
            }
        }
    }

private data class SavedLogPayload(
    val filename: String,
    val text: String,
)

private data class SavedLogStrings(
    val saved: String,
    val failed: String,
)

private fun createPlainLogFile(
    context: Context,
    title: String,
    entries: List<DiagnosticEntry>,
    filenamePrefix: String,
    sanitize: Boolean = true,
): File {
    val targetDir = File(context.cacheDir, "diagnostics-export").apply { mkdirs() }
    val file = File(targetDir, "$filenamePrefix-${UUID.randomUUID()}.log")
    file.writeText(formatPlainLog(title, entries, sanitize = sanitize), Charsets.UTF_8)
    return file
}

private fun sharePlainLogIntent(
    context: Context,
    file: File,
): Intent {
    val uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun formatPlainLog(
    title: String,
    entries: List<DiagnosticEntry>,
    sanitize: Boolean = true,
): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return buildString {
        appendLine(title)
        appendLine("Generated: ${formatter.format(Date())}")
        appendLine()
        if (entries.isEmpty()) {
            appendLine("No entries.")
        } else {
            entries.forEach { entry ->
                val message =
                    if (sanitize) {
                        DiagnosticSanitizer.sanitizeForExport(entry.message)
                    } else {
                        entry.message
                    }
                appendLine("${formatter.format(Date(entry.timestamp))} [${entry.tag}] $message")
            }
        }
    }
}

private val NetworkActivityRetentionValues =
    listOf(
        DiagnosticsRetention.HOURS_24,
        DiagnosticsRetention.DAYS_2,
        DiagnosticsRetention.DAYS_3,
        DiagnosticsRetention.DAYS_7,
        DiagnosticsRetention.DAYS_30,
    )

private const val NETWORK_ACTIVITY_TAG = "activity"
