package com.foxhole.beta.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.foxhole.beta.R
import com.foxhole.beta.core.settings.effectiveSupportBotHandle
import com.foxhole.beta.core.settings.supportBotUsername
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onCreateDiagnosticsArchive: (Boolean) -> File,
    onShareDiagnosticsArchive: (File) -> Intent,
    onSupportBotHandleChanged: (String?) -> Unit,
    onClearUsage: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val supportBotHandle = effectiveSupportBotHandle(state.settings.ui.supportBotHandleOverride)
    val strings = diagnosticsActionStrings()
    var liveLogsVisible by rememberSaveable { mutableStateOf(false) }
    var sendLogToBotVisible by rememberSaveable { mutableStateOf(false) }
    var sanitizerDialogVisible by rememberSaveable { mutableStateOf(false) }
    var supportBotSettingsVisible by rememberSaveable { mutableStateOf(false) }
    var pendingArchiveFile by remember { mutableStateOf<File?>(null) }
    val archiveSaver =
        rememberDiagnosticsArchiveSaver(
            context = context,
            coroutineScope = coroutineScope,
            snackbarHostState = snackbarHostState,
            pendingArchive = { pendingArchiveFile },
            clearPendingArchive = { pendingArchiveFile = null },
            archiveSaved = strings.archiveSaved,
            archiveSaveFailed = strings.archiveSaveFailed,
        )
    val exportArchive: (Boolean, Boolean) -> Unit = { share, sanitize ->
        launchDiagnosticsArchiveExport(
            coroutineScope = coroutineScope,
            context = context,
            snackbarHostState = snackbarHostState,
            onCreateDiagnosticsArchive = onCreateDiagnosticsArchive,
            onShareDiagnosticsArchive = onShareDiagnosticsArchive,
            archiveSaver = archiveSaver,
            setPendingArchiveFile = { pendingArchiveFile = it },
            exportDiagnosticsTitle = strings.exportTitle,
            diagnosticsArchiveSaveFailed = strings.archiveSaveFailed,
            share = share,
            sanitize = sanitize,
        )
    }

    DiagnosticsScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        supportBotHandle = supportBotHandle,
        onNavigateUp = onNavigateUp,
        onExportLogs = { sanitizerDialogVisible = true },
        onOpenLogs = { liveLogsVisible = true },
        onSendLog = { sendLogToBotVisible = true },
        onOpenSupport = {
            openSupportBotWithErrorBanner(
                coroutineScope = coroutineScope,
                context = context,
                snackbarHostState = snackbarHostState,
                supportBotHandle = supportBotHandle,
                supportBotOpenFailed = strings.supportBotOpenFailed,
            )
        },
        onConfigureBot = { supportBotSettingsVisible = true },
        onClearUsage = onClearUsage,
    )
    val dialogVisibility =
        DiagnosticsDialogVisibility(
            liveLogsVisible = liveLogsVisible,
            sendLogToBotVisible = sendLogToBotVisible,
            sanitizerDialogVisible = sanitizerDialogVisible,
            supportBotSettingsVisible = supportBotSettingsVisible,
        )
    val dialogActions =
        DiagnosticsDialogActions(
            onDismissLiveLogs = { liveLogsVisible = false },
            onDismissSendLog = { sendLogToBotVisible = false },
            onDismissSanitizer = { sanitizerDialogVisible = false },
            onDismissSupportBotSettings = { supportBotSettingsVisible = false },
            onShareArchive = { exportArchive(true, true) },
            onSaveArchive = { sanitizerDialogVisible = true },
            onConfirmSaveArchive = { sanitize -> exportArchive(false, sanitize) },
            onSendArchiveToSupportBot = {
                launchDiagnosticsArchiveToSupportBot(
                    coroutineScope = coroutineScope,
                    context = context,
                    snackbarHostState = snackbarHostState,
                    supportBotHandle = supportBotHandle,
                    strings = strings,
                    onCreateDiagnosticsArchive = onCreateDiagnosticsArchive,
                    onShareDiagnosticsArchive = onShareDiagnosticsArchive,
                )
            },
            onSupportBotHandleChanged = onSupportBotHandleChanged,
            onSupportBotSaved = {
                coroutineScope.launch {
                    snackbarHostState.showBanner(strings.supportBotSaved, FoxholeBannerTone.SUCCESS)
                }
            },
        )

    DiagnosticsScreenDialogs(
        state = state,
        supportBotHandle = supportBotHandle,
        visibility = dialogVisibility,
        actions = dialogActions,
    )
}

@Composable
private fun DiagnosticsScreenContent(
    state: DiagnosticsRouteUiState,
    snackbarHostState: SnackbarHostState,
    supportBotHandle: String,
    onNavigateUp: () -> Unit,
    onExportLogs: () -> Unit,
    onOpenLogs: () -> Unit,
    onSendLog: () -> Unit,
    onOpenSupport: () -> Unit,
    onConfigureBot: () -> Unit,
    onClearUsage: () -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.diagnostics_and_usage),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
            InfoBlock(
                title = stringResource(R.string.information_title),
                body = stringResource(R.string.diagnostics_info_body),
            )
        }
        item {
            SettingsControlGroup {
                SettingsNavigationRow(
                    icon = Icons.Outlined.FileUpload,
                    title = stringResource(R.string.export_diagnostics),
                    summary = stringResource(R.string.export_diagnostics_summary),
                    grouped = true,
                    onClick = onExportLogs,
                )
                SettingsControlGroupDivider()
                SettingsNavigationRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.logs_title),
                    summary = stringResource(R.string.logs_summary),
                    summaryMaxLines = 2,
                    grouped = true,
                    onClick = onOpenLogs,
                )
            }
        }
        item {
            SupportBotDiagnosticsCard(
                handle = supportBotHandle,
                onSendLog = onSendLog,
                onOpenSupport = onOpenSupport,
                onConfigureBot = onConfigureBot,
            )
        }
        item {
            UsageTotalsCard(
                state = state,
                onClear = onClearUsage,
            )
        }
    }
}

@Composable
private fun DiagnosticsScreenDialogs(
    state: DiagnosticsRouteUiState,
    supportBotHandle: String,
    visibility: DiagnosticsDialogVisibility,
    actions: DiagnosticsDialogActions,
) {
    if (visibility.liveLogsVisible) {
        LiveLogsDialog(
            entries = state.diagnosticEntries,
            networkActivityLoggingEnabled = state.settings.expert.networkActivityLogging,
            retention = state.settings.expert.diagnosticsRetention,
            onDismiss = actions.onDismissLiveLogs,
            onShareArchive = actions.onShareArchive,
            onSaveArchive = actions.onSaveArchive,
        )
    }
    if (visibility.sendLogToBotVisible) {
        ConfirmDialog(
            title = stringResource(R.string.send_log_to_bot),
            body = stringResource(R.string.send_log_to_bot_confirm_body),
            confirmLabel = stringResource(R.string.send_log_to_bot),
            icon = Icons.Outlined.FileUpload,
            onDismiss = actions.onDismissSendLog,
            onConfirm = {
                actions.onDismissSendLog()
                actions.onSendArchiveToSupportBot()
            },
        )
    }
    if (visibility.sanitizerDialogVisible) {
        ConfirmDialog(
            title = stringResource(R.string.diagnostics_sanitizer_title),
            body = stringResource(R.string.diagnostics_sanitizer_body),
            confirmLabel = stringResource(R.string.save),
            dismissLabel = stringResource(R.string.cancel),
            secondaryLabel = stringResource(R.string.save_without_sanitizing),
            onSecondary = {
                actions.onDismissSanitizer()
                actions.onConfirmSaveArchive(false)
            },
            prominentActions = true,
            icon = Icons.Outlined.Shield,
            onDismiss = actions.onDismissSanitizer,
            onConfirm = {
                actions.onDismissSanitizer()
                actions.onConfirmSaveArchive(true)
            },
        )
    }
    if (visibility.supportBotSettingsVisible) {
        SupportBotHandleDialog(
            currentHandle = supportBotHandle,
            onDismiss = actions.onDismissSupportBotSettings,
            onConfirm = { handle ->
                actions.onDismissSupportBotSettings()
                actions.onSupportBotHandleChanged(handle)
                actions.onSupportBotSaved()
            },
        )
    }
}

private data class DiagnosticsDialogVisibility(
    val liveLogsVisible: Boolean,
    val sendLogToBotVisible: Boolean,
    val sanitizerDialogVisible: Boolean,
    val supportBotSettingsVisible: Boolean,
)

private data class DiagnosticsDialogActions(
    val onDismissLiveLogs: () -> Unit,
    val onDismissSendLog: () -> Unit,
    val onDismissSanitizer: () -> Unit,
    val onDismissSupportBotSettings: () -> Unit,
    val onShareArchive: () -> Unit,
    val onSaveArchive: () -> Unit,
    val onConfirmSaveArchive: (Boolean) -> Unit,
    val onSendArchiveToSupportBot: () -> Unit,
    val onSupportBotHandleChanged: (String?) -> Unit,
    val onSupportBotSaved: () -> Unit,
)

@Composable
private fun rememberDiagnosticsArchiveSaver(
    context: Context,
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    pendingArchive: () -> File?,
    clearPendingArchive: () -> Unit,
    archiveSaved: String,
    archiveSaveFailed: String,
): ManagedActivityResultLauncher<String, Uri?> =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        val archive = pendingArchive()
        clearPendingArchive()
        if (uri == null || archive == null) {
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        archive.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("openOutputStream returned null")
                }
            }.onSuccess {
                snackbarHostState.showBanner(archiveSaved, FoxholeBannerTone.SUCCESS)
            }.onFailure {
                snackbarHostState.showBanner(
                    it.message ?: archiveSaveFailed,
                    FoxholeBannerTone.ERROR,
                )
            }
        }
    }

@Composable
private fun diagnosticsActionStrings(): DiagnosticsActionStrings =
    DiagnosticsActionStrings(
        supportBotOpenFailed = stringResource(R.string.support_bot_open_failed),
        supportBotBrowserFallback = stringResource(R.string.support_bot_browser_fallback),
        supportBotSaved = stringResource(R.string.support_bot_saved),
        archiveSaved = stringResource(R.string.diagnostics_archive_saved),
        archiveSaveFailed = stringResource(R.string.diagnostics_archive_save_failed),
        exportTitle = stringResource(R.string.export_diagnostics),
    )

private data class DiagnosticsActionStrings(
    val supportBotOpenFailed: String,
    val supportBotBrowserFallback: String,
    val supportBotSaved: String,
    val archiveSaved: String,
    val archiveSaveFailed: String,
    val exportTitle: String,
)

private fun launchDiagnosticsArchiveExport(
    coroutineScope: CoroutineScope,
    context: Context,
    snackbarHostState: SnackbarHostState,
    onCreateDiagnosticsArchive: (Boolean) -> File,
    onShareDiagnosticsArchive: (File) -> Intent,
    archiveSaver: ManagedActivityResultLauncher<String, Uri?>,
    setPendingArchiveFile: (File?) -> Unit,
    exportDiagnosticsTitle: String,
    diagnosticsArchiveSaveFailed: String,
    share: Boolean,
    sanitize: Boolean = true,
) {
    coroutineScope.launch {
        runCatching {
            withContext(Dispatchers.IO) { onCreateDiagnosticsArchive(sanitize) }
        }.onSuccess { archive ->
            if (share) {
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            onShareDiagnosticsArchive(archive),
                            exportDiagnosticsTitle,
                        ),
                    )
                }.onFailure {
                    snackbarHostState.showBanner(
                        it.message ?: diagnosticsArchiveSaveFailed,
                        FoxholeBannerTone.ERROR,
                    )
                }
            } else {
                setPendingArchiveFile(archive)
                archiveSaver.launch(archive.name)
            }
        }.onFailure {
            snackbarHostState.showBanner(
                it.message ?: diagnosticsArchiveSaveFailed,
                FoxholeBannerTone.ERROR,
            )
        }
    }
}

private fun showSupportBotOpenResult(
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    opened: Boolean,
    strings: DiagnosticsActionStrings,
) {
    coroutineScope.launch {
        snackbarHostState.showBanner(
            if (opened) {
                strings.supportBotBrowserFallback
            } else {
                strings.supportBotOpenFailed
            },
            if (opened) FoxholeBannerTone.INFO else FoxholeBannerTone.ERROR,
        )
    }
}

private fun openSupportBotWithErrorBanner(
    coroutineScope: CoroutineScope,
    context: Context,
    snackbarHostState: SnackbarHostState,
    supportBotHandle: String,
    supportBotOpenFailed: String,
) {
    if (!openSupportBot(context, supportBotHandle)) {
        coroutineScope.launch {
            snackbarHostState.showBanner(
                supportBotOpenFailed,
                FoxholeBannerTone.ERROR,
            )
        }
    }
}

private fun launchDiagnosticsArchiveToSupportBot(
    coroutineScope: CoroutineScope,
    context: Context,
    snackbarHostState: SnackbarHostState,
    supportBotHandle: String,
    strings: DiagnosticsActionStrings,
    onCreateDiagnosticsArchive: (Boolean) -> File,
    onShareDiagnosticsArchive: (File) -> Intent,
) {
    val telegramPackage = installedTelegramPackage(context.packageManager)
    if (telegramPackage == null) {
        showSupportBotOpenResult(
            coroutineScope = coroutineScope,
            snackbarHostState = snackbarHostState,
            opened = openSupportBot(context, supportBotHandle),
            strings = strings,
        )
        return
    }
    coroutineScope.launch {
        runCatching {
            withContext(Dispatchers.IO) { onCreateDiagnosticsArchive(true) }
        }.onSuccess { archive ->
            val shareIntent =
                createTelegramDiagnosticsShareIntent(
                    packageName = telegramPackage,
                    baseIntent = onShareDiagnosticsArchive(archive),
                    context = context,
                    handle = supportBotHandle,
                )
            runCatching { context.startActivity(shareIntent) }
                .onFailure {
                    showSupportBotOpenResult(
                        coroutineScope = coroutineScope,
                        snackbarHostState = snackbarHostState,
                        opened = openSupportBot(context, supportBotHandle),
                        strings = strings,
                    )
                }
        }.onFailure {
            snackbarHostState.showBanner(
                it.message ?: strings.archiveSaveFailed,
                FoxholeBannerTone.ERROR,
            )
        }
    }
}

@Composable
private fun SupportBotDiagnosticsCard(
    handle: String,
    onSendLog: () -> Unit,
    onOpenSupport: () -> Unit,
    onConfigureBot: () -> Unit,
) {
    FoxholeCard(
        modifier = Modifier.testTag("diagnostics_support_bot_card"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SupportBotIcon()
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.support_bot_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onConfigureBot) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.support_bot_settings_button),
                )
            }
        }
        Text(
            text = stringResource(R.string.support_bot_diagnostics_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.support_bot_diagnostics_instructions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = onSendLog,
            ) {
                Text(stringResource(R.string.send_log_to_bot))
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onOpenSupport,
            ) {
                Text(stringResource(R.string.write_to_support))
            }
        }
        Text(
            modifier = Modifier.fillMaxWidth().clickable { onOpenSupport() },
            text = stringResource(R.string.support_bot_current, handle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SupportBotIcon(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(38.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_telegram_mark),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SupportBotHandleDialog(
    currentHandle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(currentHandle) { mutableStateOf(currentHandle) }
    val normalized = com.foxhole.beta.core.settings.normalizeSupportBotHandle(value)
    val showError = value.isNotBlank() && normalized == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.support_bot_settings_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.support_bot_settings_summary))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    label = { Text(stringResource(R.string.support_bot_handle_label)) },
                    singleLine = true,
                    isError = showError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError) {
                    Text(
                        text = stringResource(R.string.support_bot_invalid_handle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            FoxholeDialogConfirmButton(
                onClick = { normalized?.let(onConfirm) },
                enabled = normalized != null,
            )
        },
        dismissButton = {
            FoxholeDialogDismissButton(onClick = onDismiss)
        },
    )
}

private fun supportBotBrowserUri(handle: String): Uri = "https://t.me/${supportBotUsername(handle)}".toUri()

private fun supportBotTelegramUri(handle: String): Uri = "tg://resolve?domain=${supportBotUsername(handle)}".toUri()

private fun openSupportBot(context: Context, handle: String): Boolean {
    val browserIntent =
        Intent(Intent.ACTION_VIEW, supportBotBrowserUri(handle))
            .addCategory(Intent.CATEGORY_BROWSABLE)
    val telegramPackage = installedTelegramPackage(context.packageManager)
    if (telegramPackage != null) {
        val telegramIntent =
            Intent(Intent.ACTION_VIEW, supportBotTelegramUri(handle))
                .setPackage(telegramPackage)
                .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            context.startActivity(telegramIntent)
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return try {
        context.startActivity(browserIntent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun createTelegramDiagnosticsShareIntent(
    packageName: String,
    baseIntent: Intent,
    context: Context,
    handle: String,
): Intent =
    Intent(baseIntent).apply {
        `package` = packageName
        putExtra(
            Intent.EXTRA_TEXT,
            context.getString(
                R.string.support_bot_share_text,
                handle,
                supportBotBrowserUri(handle).toString(),
            ),
        )
    }
