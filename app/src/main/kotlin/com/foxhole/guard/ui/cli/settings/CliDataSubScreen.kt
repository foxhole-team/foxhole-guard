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
import com.foxhole.guard.core.backup.BackupDocument
import com.foxhole.guard.core.backup.backupFileName
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.clearProfilesAndSecretsLocalData
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliDestructiveRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.cli.components.CliYesNoRow
import com.foxhole.guard.ui.exportBackup
import com.foxhole.guard.ui.factoryResetLocalData
import com.foxhole.guard.ui.parseBackup
import com.foxhole.guard.ui.resetApplicationSettingsToDefaults
import com.foxhole.guard.ui.restoreBackup

/**
 * Backup + destructive local-data actions. Export/import ride SAF; every destructive
 * action sits behind an inline y/n row. Restore shows a short preview (what the file
 * carries) before applying — the terminal echo of the classic preview sheet.
 */
@Composable
internal fun CliDataSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    var includeProfiles by rememberSaveable { mutableStateOf(true) }
    var includeSettings by rememberSaveable { mutableStateOf(true) }
    // A parsed preview document does not fit in a Bundle, so recreation keeps the source uri and
    // re-reads the file. Without this, rotation silently lost the preview and forced a second trip
    // through SAF.
    var pendingRestoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingRestore by remember { mutableStateOf<BackupDocument?>(null) }
    var confirmAction by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingRestoreUri) {
        pendingRestore = pendingRestoreUri?.let { viewModel.parseBackup(it) }
    }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri: Uri? ->
            uri?.let { viewModel.exportBackup(it, includeProfiles, includeSettings) }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                pendingRestoreUri = uri
            }
        }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_more_data), icon = R.drawable.pix_export)
        CliPanel(
            title = stringResource(R.string.cli_data_backup_title),
            icon = R.drawable.pix_export,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CliToggleRow(
                label = stringResource(R.string.cli_data_include_profiles),
                checked = includeProfiles,
                onToggle = { includeProfiles = it },
            )
            CliToggleRow(
                label = stringResource(R.string.cli_data_include_settings),
                checked = includeSettings,
                onToggle = { includeSettings = it },
            )
            CliActionRow(
                label = stringResource(R.string.cli_data_export),
                enabled = includeProfiles || includeSettings,
                onTap = { exportLauncher.launch(backupFileName(includeProfiles, includeSettings)) },
            )
            CliActionRow(
                label = stringResource(R.string.cli_data_import),
                onTap = {
                    importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                },
            )
            pendingRestore?.let { document ->
                Text(
                    text = stringResource(
                        R.string.cli_data_restore_preview,
                        document.appVersionName,
                        document.profiles.size,
                        if (document.settings != null) "+" else "−",
                    ),
                    style = CliType.small,
                    color = colors.info,
                )
                CliYesNoRow(
                    question = stringResource(R.string.cli_data_restore_confirm),
                    onYes = {
                        viewModel.restoreBackup(document)
                        pendingRestore = null
                        pendingRestoreUri = null
                    },
                    onNo = {
                        pendingRestore = null
                        pendingRestoreUri = null
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliPanel(
            title = stringResource(R.string.cli_data_clear_title),
            icon = R.drawable.pix_trash,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CliDestructiveRow(
                key = "reset_settings",
                label = stringResource(R.string.cli_data_reset_settings),
                confirmAction = confirmAction,
                onArm = { confirmAction = it },
                onConfirm = viewModel::resetApplicationSettingsToDefaults,
            )
            CliDestructiveRow(
                key = "clear_profiles",
                label = stringResource(R.string.cli_data_clear_profiles),
                confirmAction = confirmAction,
                onArm = { confirmAction = it },
                onConfirm = viewModel::clearProfilesAndSecretsLocalData,
            )
            CliDestructiveRow(
                key = "factory_reset",
                label = stringResource(R.string.cli_data_factory_reset),
                confirmAction = confirmAction,
                onArm = { confirmAction = it },
                onConfirm = viewModel::factoryResetLocalData,
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}
