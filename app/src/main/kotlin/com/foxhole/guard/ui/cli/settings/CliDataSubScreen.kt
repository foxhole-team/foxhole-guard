package com.foxhole.guard.ui.cli.settings

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.guard.R
import com.foxhole.guard.core.backup.BACKUP_PASSWORD_MIN_CHARS
import com.foxhole.guard.core.backup.BackupDocument
import com.foxhole.guard.core.backup.BackupExportSelection
import com.foxhole.guard.core.backup.PendingBackupExport
import com.foxhole.guard.core.backup.PreparedBackupImport
import com.foxhole.guard.core.backup.completeBackupExportPicker
import com.foxhole.guard.core.backup.launchBackupPicker
import com.foxhole.guard.core.backup.launchPendingBackupExportPicker
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.clearProfilesAndSecretsLocalData
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliConfirmSheet
import com.foxhole.guard.ui.cli.components.CliDestructiveRow
import com.foxhole.guard.ui.cli.components.CliInputRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliSheetAction
import com.foxhole.guard.ui.cli.components.CliSheetActionsRow
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.exportBackup
import com.foxhole.guard.ui.factoryResetLocalData
import com.foxhole.guard.ui.prepareBackupImport
import com.foxhole.guard.ui.reportBackupExportCancelled
import com.foxhole.guard.ui.reportBackupPickerFailure
import com.foxhole.guard.ui.resetApplicationSettingsToDefaults
import com.foxhole.guard.ui.restoreBackup
import com.foxhole.guard.ui.unlockPreparedBackup
import kotlinx.coroutines.Job

@Composable
internal fun CliDataSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    var includeProfiles by rememberSaveable { mutableStateOf(true) }
    var includeSettings by rememberSaveable { mutableStateOf(true) }
    var encryptBackup by rememberSaveable { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<BackupDocument?>(null) }
    var pendingEncryptedImport by remember { mutableStateOf<PreparedBackupImport.Encrypted?>(null) }
    var pendingExportSelection by remember { mutableStateOf<BackupExportSelection?>(null) }
    var pendingExport by remember { mutableStateOf<PendingBackupExport?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var confirmAction by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            importJob?.cancel()
            pendingExport?.close()
            pendingExport = null
            pendingExportSelection = null
            pendingEncryptedImport = null
            pendingRestore = null
        }
    }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri: Uri? ->
            val request = pendingExport
            pendingExport = null
            completeBackupExportPicker(
                uri,
                request,
                { picked, pending -> viewModel.exportBackup(picked, pending) },
                viewModel::reportBackupExportCancelled,
            )
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                importJob = viewModel.prepareBackupImport(uri) { prepared ->
                    importBusy = false
                    when (prepared) {
                        is PreparedBackupImport.Plain -> pendingRestore = prepared.document
                        is PreparedBackupImport.Encrypted -> pendingEncryptedImport = prepared
                        null -> Unit
                    }
                }
            } else {
                importBusy = false
            }
        }

    pendingExportSelection?.let { selection ->
        CliBackupPasswordSheet(
            exporting = true,
            onSubmit = { password ->
                pendingExportSelection = null
                val request = PendingBackupExport.create(selection, password)
                pendingExport = request
                launchBackupExport(viewModel, request, exportLauncher) { pendingExport = null }
            },
            onDismiss = { pendingExportSelection = null },
        )
    }
    pendingEncryptedImport?.let { prepared ->
        CliBackupPasswordSheet(
            exporting = false,
            onSubmit = { password ->
                pendingEncryptedImport = null
                importBusy = true
                importJob = viewModel.unlockPreparedBackup(prepared, password) { document ->
                    pendingRestore = document
                    importBusy = false
                }
            },
            onDismiss = { pendingEncryptedImport = null },
        )
    }

    CliDataScreenLayout(
        viewModel = viewModel,
        modifier = modifier,
        confirmAction = confirmAction,
        onArm = { confirmAction = it },
    ) {
        CliDataBackupPanel(
            includeProfiles = includeProfiles,
            includeSettings = includeSettings,
            encryptBackup = encryptBackup,
            exportPending = pendingExportSelection != null || pendingExport != null,
            importPending = importBusy || pendingEncryptedImport != null || pendingRestore != null,
            pendingRestore = pendingRestore,
            onIncludeProfiles = { includeProfiles = it },
            onIncludeSettings = { includeSettings = it },
            onEncryptBackup = { encryptBackup = it },
            onExport = {
                val selection = BackupExportSelection(includeProfiles, includeSettings, encryptBackup)
                if (selection.encrypted) {
                    pendingExportSelection = selection
                } else {
                    val request = PendingBackupExport.create(selection)
                    pendingExport = request
                    launchBackupExport(viewModel, request, exportLauncher) { pendingExport = null }
                }
            },
            onImport = {
                importBusy = true
                launchBackupImport(viewModel, importLauncher) { importBusy = false }
            },
            onRestore = { document ->
                viewModel.restoreBackup(document)
                pendingRestore = null
            },
            onDismissRestore = { pendingRestore = null },
        )
    }
}

private fun launchBackupExport(
    viewModel: HomeViewModel,
    request: PendingBackupExport,
    launcher: ManagedActivityResultLauncher<String, Uri?>,
    clearPending: () -> Unit,
) = launchPendingBackupExportPicker(
    request = request,
    clearPending = clearPending,
    onFailure = { viewModel.reportBackupPickerFailure(exporting = true, error = it) },
    launch = { launcher.launch(it) },
)

private fun launchBackupImport(
    viewModel: HomeViewModel,
    launcher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    clearBusy: () -> Unit,
) = launchBackupPicker(
    cleanup = clearBusy,
    onFailure = { viewModel.reportBackupPickerFailure(exporting = false, error = it) },
    launch = { launcher.launch(arrayOf("application/octet-stream", "application/json", "*/*")) },
)

@Composable
private fun CliDataScreenLayout(
    viewModel: HomeViewModel,
    modifier: Modifier,
    confirmAction: String?,
    onArm: (String?) -> Unit,
    backupPanel: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_data), icon = R.drawable.lin_export)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalCliBottomChromeClearance.current),
        ) {
            backupPanel()
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliDataClearPanel(viewModel, confirmAction, onArm)
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
    }
}

@Composable
private fun CliDataBackupPanel(
    includeProfiles: Boolean,
    includeSettings: Boolean,
    encryptBackup: Boolean,
    exportPending: Boolean,
    importPending: Boolean,
    pendingRestore: BackupDocument?,
    onIncludeProfiles: (Boolean) -> Unit,
    onIncludeSettings: (Boolean) -> Unit,
    onEncryptBackup: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onRestore: (BackupDocument) -> Unit,
    onDismissRestore: () -> Unit,
) {
    CliPanel(
        title = stringResource(R.string.cli_data_backup_title),
        icon = R.drawable.lin_export,
        modifier = Modifier.fillMaxWidth(),
        infoText = stringResource(R.string.cli_data_encrypted_backup_note),
    ) {
        CliToggleRow(
            label = stringResource(R.string.cli_data_encrypt_backup),
            icon = R.drawable.lin_lock,
            checked = encryptBackup,
            onToggle = onEncryptBackup,
        )
        CliToggleRow(
            label = stringResource(R.string.cli_data_include_profiles),
            icon = R.drawable.lin_profiles,
            checked = includeProfiles,
            onToggle = onIncludeProfiles,
        )
        CliToggleRow(
            label = stringResource(R.string.cli_data_include_settings),
            icon = R.drawable.lin_settings,
            checked = includeSettings,
            onToggle = onIncludeSettings,
        )
        CliActionRow(
            label = stringResource(R.string.cli_data_export),
            icon = R.drawable.lin_export,
            enabled = (includeProfiles || includeSettings) && !exportPending,
            onTap = onExport,
        )
        CliActionRow(
            label = stringResource(R.string.cli_data_import),
            icon = R.drawable.lin_import,
            enabled = !importPending,
            onTap = onImport,
        )
        pendingRestore?.let { document ->
            CliConfirmSheet(
                title = stringResource(R.string.cli_data_import),
                icon = R.drawable.lin_import,
                question = stringResource(R.string.cli_data_restore_confirm),
                note = stringResource(
                    R.string.cli_data_restore_preview,
                    document.appVersionName,
                    document.profiles.size,
                    if (document.settings != null) "+" else "−",
                ),
                onConfirm = { onRestore(document) },
                onDismiss = onDismissRestore,
            )
        }
    }
}

@Composable
private fun CliDataClearPanel(
    viewModel: HomeViewModel,
    confirmAction: String?,
    onArm: (String?) -> Unit,
) {
    CliPanel(
        title = stringResource(R.string.cli_data_clear_title),
        icon = R.drawable.lin_trash,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliDestructiveRow(
            key = "reset_settings",
            icon = R.drawable.lin_restart,
            label = stringResource(R.string.cli_data_reset_settings),
            confirmAction = confirmAction,
            onArm = onArm,
            onConfirm = viewModel::resetApplicationSettingsToDefaults,
        )
        CliDestructiveRow(
            key = "clear_profiles",
            icon = R.drawable.lin_trash,
            label = stringResource(R.string.cli_data_clear_profiles),
            confirmAction = confirmAction,
            onArm = onArm,
            onConfirm = viewModel::clearProfilesAndSecretsLocalData,
        )
        CliDestructiveRow(
            key = "factory_reset",
            icon = R.drawable.lin_fire,
            label = stringResource(R.string.cli_data_factory_reset),
            confirmAction = confirmAction,
            onArm = onArm,
            onConfirm = viewModel::factoryResetLocalData,
        )
    }
}

@Composable
private fun CliBackupPasswordSheet(
    exporting: Boolean,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            password = ""
            confirmation = ""
        }
    }
    val colors = LocalCliColors.current
    val valid =
        if (exporting) {
            password.length >= BACKUP_PASSWORD_MIN_CHARS && password == confirmation
        } else {
            password.isNotEmpty()
        }
    CliBottomSheet(
        onDismiss = {
            password = ""
            confirmation = ""
            onDismiss()
        },
        title = stringResource(
            if (exporting) R.string.cli_data_backup_password_title else R.string.cli_data_restore_password_title,
        ),
        icon = if (exporting) R.drawable.lin_export else R.drawable.lin_import,
    ) {
        Text(
            text = if (exporting) {
                stringResource(R.string.cli_data_backup_password_note, BACKUP_PASSWORD_MIN_CHARS)
            } else {
                stringResource(R.string.cli_data_restore_password_note)
            },
            style = CliType.small,
            color = colors.warn,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliInputRow(
            prompt = stringResource(R.string.cli_data_backup_password),
            value = password,
            onValueChange = { password = it.take(MAX_BACKUP_PASSWORD_CHARS) },
            onSubmit = { attempted = true },
            password = true,
        )
        if (exporting) {
            CliInputRow(
                prompt = stringResource(R.string.cli_data_backup_password_confirm),
                value = confirmation,
                onValueChange = { confirmation = it.take(MAX_BACKUP_PASSWORD_CHARS) },
                onSubmit = { attempted = true },
                password = true,
            )
        }
        if (attempted && !valid) {
            Text(
                text = stringResource(
                    if (exporting && password != confirmation) {
                        R.string.cli_data_backup_password_mismatch
                    } else {
                        R.string.cli_data_backup_password_invalid
                    },
                ),
                style = CliType.small,
                color = colors.err,
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliSheetActionsRow(
            actions =
            listOf(
                CliSheetAction(
                    label = stringResource(
                        if (exporting) R.string.cli_data_export else R.string.cli_data_import,
                    ),
                    enabled = valid,
                    onClick = {
                        if (valid) {
                            val owned = password.toCharArray()
                            password = ""
                            confirmation = ""
                            onSubmit(owned)
                        } else {
                            attempted = true
                        }
                    },
                    dismissAfterClick = true,
                ),
            ),
        )
    }
}

private const val MAX_BACKUP_PASSWORD_CHARS = 128
