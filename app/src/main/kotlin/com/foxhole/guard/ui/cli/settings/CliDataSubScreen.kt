package com.foxhole.guard.ui.cli.settings

import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
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
import com.foxhole.guard.core.backup.backupFileName
import com.foxhole.guard.ui.BackupFileTooLargeException
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
import com.foxhole.guard.ui.parseBackup
import com.foxhole.guard.ui.resetApplicationSettingsToDefaults
import com.foxhole.guard.ui.restoreBackup

@Composable
internal fun CliDataSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    var includeProfiles by rememberSaveable { mutableStateOf(true) }
    var includeSettings by rememberSaveable { mutableStateOf(true) }
    var pendingRestoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingRestore by remember { mutableStateOf<BackupDocument?>(null) }
    var pendingRestorePassword by remember { mutableStateOf<CharArray?>(null) }
    var pendingExportPassword by remember { mutableStateOf<CharArray?>(null) }
    var showExportPassword by remember { mutableStateOf(false) }
    var showImportPassword by remember { mutableStateOf(false) }
    var confirmAction by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingRestoreUri, pendingRestorePassword) {
        val uri = pendingRestoreUri ?: return@LaunchedEffect
        val password = pendingRestorePassword ?: return@LaunchedEffect
        try {
            pendingRestore = viewModel.parseBackup(uri, password)
            if (pendingRestore == null) {
                pendingRestoreUri = null
            }
        } catch (_: BackupFileTooLargeException) {
            pendingRestore = null
            pendingRestoreUri = null
        } finally {
            pendingRestorePassword = null
        }
    }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri: Uri? ->
            val password = pendingExportPassword
            pendingExportPassword = null
            if (uri != null && password != null) {
                viewModel.exportBackup(uri, includeProfiles, includeSettings, password)
            } else {
                password?.fill('\u0000')
            }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                pendingRestoreUri = uri
                showImportPassword = true
            }
        }

    if (showExportPassword) {
        CliBackupPasswordSheet(
            exporting = true,
            onSubmit = { password ->
                pendingExportPassword?.fill('\u0000')
                pendingExportPassword = password
                showExportPassword = false
                exportLauncher.launch(backupFileName(includeProfiles, includeSettings))
            },
            onDismiss = { showExportPassword = false },
        )
    }
    if (showImportPassword) {
        CliBackupPasswordSheet(
            exporting = false,
            onSubmit = { password ->
                pendingRestorePassword?.fill('\u0000')
                pendingRestorePassword = password
                showImportPassword = false
            },
            onDismiss = {
                showImportPassword = false
                pendingRestoreUri = null
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_data), icon = R.drawable.pix_export)

        Column(

            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalCliBottomChromeClearance.current),

        ) {
            CliDataBackupPanel(
                includeProfiles = includeProfiles,
                includeSettings = includeSettings,
                pendingRestore = pendingRestore,
                onIncludeProfiles = { includeProfiles = it },
                onIncludeSettings = { includeSettings = it },
                onExport = { showExportPassword = true },
                onImport = { importLauncher.launch(arrayOf("application/octet-stream", "application/json", "*/*")) },
                onRestore = { document ->
                    viewModel.restoreBackup(document)
                    pendingRestore = null
                    pendingRestoreUri = null
                },
                onDismissRestore = {
                    pendingRestore = null
                    pendingRestoreUri = null
                },
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliDataClearPanel(
                viewModel = viewModel,
                confirmAction = confirmAction,
                onArm = { confirmAction = it },
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
    }
}

@Composable
private fun CliDataBackupPanel(
    includeProfiles: Boolean,
    includeSettings: Boolean,
    pendingRestore: BackupDocument?,
    onIncludeProfiles: (Boolean) -> Unit,
    onIncludeSettings: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onRestore: (BackupDocument) -> Unit,
    onDismissRestore: () -> Unit,
) {
    CliPanel(
        title = stringResource(R.string.cli_data_backup_title),
        icon = R.drawable.pix_export,
        modifier = Modifier.fillMaxWidth(),
        infoText = stringResource(R.string.cli_data_encrypted_backup_note),
    ) {
        CliToggleRow(
            label = stringResource(R.string.cli_data_include_profiles),
            icon = R.drawable.pix_profiles,
            checked = includeProfiles,
            onToggle = onIncludeProfiles,
        )
        CliToggleRow(
            label = stringResource(R.string.cli_data_include_settings),
            icon = R.drawable.pix_settings,
            checked = includeSettings,
            onToggle = onIncludeSettings,
        )
        CliActionRow(
            label = stringResource(R.string.cli_data_export),
            icon = R.drawable.pix_export,
            enabled = includeProfiles || includeSettings,
            onTap = onExport,
        )
        CliActionRow(
            label = stringResource(R.string.cli_data_import),
            icon = R.drawable.pix_import,
            onTap = onImport,
        )
        pendingRestore?.let { document ->
            CliConfirmSheet(
                title = stringResource(R.string.cli_data_import),
                icon = R.drawable.pix_import,
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
        icon = R.drawable.pix_trash,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliDestructiveRow(
            key = "reset_settings",
            icon = R.drawable.pix_restart,
            label = stringResource(R.string.cli_data_reset_settings),
            confirmAction = confirmAction,
            onArm = onArm,
            onConfirm = viewModel::resetApplicationSettingsToDefaults,
        )
        CliDestructiveRow(
            key = "clear_profiles",
            icon = R.drawable.pix_trash,
            label = stringResource(R.string.cli_data_clear_profiles),
            confirmAction = confirmAction,
            onArm = onArm,
            onConfirm = viewModel::clearProfilesAndSecretsLocalData,
        )
        CliDestructiveRow(
            key = "factory_reset",
            icon = R.drawable.pix_fire,
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
    val colors = LocalCliColors.current
    val valid =
        if (exporting) {
            password.length >= BACKUP_PASSWORD_MIN_CHARS && password == confirmation
        } else {
            password.isNotEmpty()
        }
    CliBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(
            if (exporting) R.string.cli_data_backup_password_title else R.string.cli_data_restore_password_title,
        ),
        icon = if (exporting) R.drawable.pix_export else R.drawable.pix_import,
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
            onCancel = onDismiss,
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
                ),
            ),
        )
    }
}

private const val MAX_BACKUP_PASSWORD_CHARS = 128
