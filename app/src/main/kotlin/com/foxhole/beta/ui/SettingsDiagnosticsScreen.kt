package com.foxhole.beta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.core.content.FileProvider
import com.foxhole.beta.R
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.DiagnosticsRetention
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
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var networkLogVisible by rememberSaveable { mutableStateOf(false) }
    var foxholeLogVisible by rememberSaveable { mutableStateOf(false) }
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
        onOpenNetworkLog = { networkLogVisible = true },
        onOpenFoxholeLog = { foxholeLogVisible = true },
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
                        text = formatPlainLog(title, networkEntries),
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
    onOpenNetworkLog: () -> Unit,
    onOpenFoxholeLog: () -> Unit,
) {
    var persistentLoggingWarningVisible by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(
        title = stringResource(R.string.diagnostics_and_usage),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
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
): File {
    val targetDir = File(context.cacheDir, "diagnostics-export").apply { mkdirs() }
    val file = File(targetDir, "$filenamePrefix-${UUID.randomUUID()}.log")
    file.writeText(formatPlainLog(title, entries), Charsets.UTF_8)
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
                appendLine("${formatter.format(Date(entry.timestamp))} [${entry.tag}] ${entry.message}")
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
