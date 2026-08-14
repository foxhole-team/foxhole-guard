package com.foxhole.guard.ui.cli.profiles

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foxhole.guard.R
import com.foxhole.guard.core.data.readLocalProfileImportUtf8Capped
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.ProfilesExportSelectionState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliSpinner
import com.foxhole.guard.ui.cli.components.cliDashedBorder
import com.foxhole.guard.ui.emitInfo
import com.foxhole.guard.ui.importProfileRaw
import com.foxhole.guard.ui.isReady
import com.foxhole.guard.ui.onPasteFromClipboard
import com.foxhole.guard.ui.requests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CliQrExport(
    val name: String,
    val text: String,
)

/**
 * The canonical two-row transfer block. QR is the full-width primary action; file and clipboard
 * share the secondary row. Export keeps QR visible for multi-config payloads but disables it,
 * because one QR code deliberately carries exactly one configuration.
 */
@Composable
internal fun CliProfileTransferRow(
    viewModel: HomeViewModel,
    selection: ProfilesExportSelectionState,
    onSelectionCleared: () -> Unit,
    onInteraction: () -> Unit = {},
) {
    val colors = LocalCliColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transferState = remember { CliProfileTransferState() }
    val exportMode = selection.isReady()
    val selectedKeyCount = if (exportMode) selection.selectedKeyCount() else 0
    val importLauncher = rememberCliImportLauncher(viewModel)
    val jsonLauncher = rememberCliSingleExportLauncher("application/json", selection, viewModel, onSelectionCleared)
    val textLauncher = rememberCliSingleExportLauncher("text/plain", selection, viewModel, onSelectionCleared)
    val treeLauncher = rememberCliTreeExportLauncher(selection, viewModel, onSelectionCleared)
    val launchers = CliTransferLaunchers(
        importFile = { importLauncher.launch(PROFILE_IMPORT_MIME_TYPES) },
        exportJson = jsonLauncher::launch,
        exportText = textLauncher::launch,
        exportTree = { treeLauncher.launch(null) },
    )
    val runExport: CliRunExport = { block ->
        launchResolvedExports(viewModel, selection, transferState, scope, context, block)
    }
    val actions = CliTransferActions(
        file = {
            onInteraction()
            handleFileTransfer(exportMode, launchers, runExport)
        },
        qr = {
            onInteraction()
            handleQrTransfer(exportMode, transferState, viewModel, context, runExport)
        },
        clipboard = {
            onInteraction()
            handleClipboardTransfer(exportMode, viewModel, context, onSelectionCleared, runExport)
        },
    )
    CliProfileTransferControls(
        exportMode = exportMode,
        selectedKeyCount = selectedKeyCount,
        busy = transferState.exportBusy,
        buttonColor = if (exportMode) colors.info else colors.accent,
        actions = actions,
    )
    CliProfileTransferOverlays(viewModel, transferState)
}

private typealias CliRunExport = (suspend (List<CliProfileConfigExport>) -> Unit) -> Unit

private class CliProfileTransferState {
    var qrScannerVisible by mutableStateOf(false)
    var qrExport by mutableStateOf<CliQrExport?>(null)
    var exportBusy by mutableStateOf(false)
}

private data class CliTransferLaunchers(
    val importFile: () -> Unit,
    val exportJson: (String) -> Unit,
    val exportText: (String) -> Unit,
    val exportTree: () -> Unit,
)

private data class CliTransferActions(
    val file: () -> Unit,
    val qr: () -> Unit,
    val clipboard: () -> Unit,
)

@Composable
private fun rememberCliImportLauncher(
    viewModel: HomeViewModel,
): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.readLocalProfileImportUtf8Capped()
                        }.orEmpty()
                    }
                }.onSuccess(viewModel::importProfileRaw)
                    .onFailure { viewModel.importProfileRaw("") }
            }
        }
    }
}

// No pending snapshot: the launcher callback rebuilds the export from the *current* selection,
// which is saveable and survives process death. A snapshot in remember died with the composition,
// so rotating with the SAF picker open silently swallowed the export — no file, no message.
private suspend fun resolveExportsForSaf(
    viewModel: HomeViewModel,
    selection: ProfilesExportSelectionState,
): List<CliProfileConfigExport> =
    runCatching { viewModel.cliResolveExportConfigs(selection.requests()) }.getOrDefault(emptyList())

@Composable
private fun rememberCliSingleExportLauncher(
    mimeType: String,
    selection: ProfilesExportSelectionState,
    viewModel: HomeViewModel,
    onSelectionCleared: () -> Unit,
): ManagedActivityResultLauncher<String, Uri?> {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    return rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        if (uri != null) {
            scope.launch {
                val savedName = resolveExportsForSaf(viewModel, selection)
                    .singleOrNull()
                    ?.takeIf { cliWriteExportToUri(context.contentResolver, uri, it.joinedConfigs()) }
                    ?.fileName
                if (savedName != null) {
                    viewModel.emitInfo(resources.getString(R.string.cli_prof_exp_saved, savedName))
                    onSelectionCleared()
                } else {
                    viewModel.emitInfo(resources.getString(R.string.cli_prof_exp_failed))
                }
            }
        }
    }
}

@Composable
private fun rememberCliTreeExportLauncher(
    selection: ProfilesExportSelectionState,
    viewModel: HomeViewModel,
    onSelectionCleared: () -> Unit,
): ManagedActivityResultLauncher<Uri?, Uri?> {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                val exports = resolveExportsForSaf(viewModel, selection)
                val written =
                    if (exports.isEmpty()) 0 else cliWriteExportsToTree(context.contentResolver, uri, exports)
                if (written > 0) {
                    viewModel.emitInfo(
                        resources.getQuantityString(R.plurals.cli_prof_exp_saved_many, written, written),
                    )
                    onSelectionCleared()
                } else {
                    viewModel.emitInfo(resources.getString(R.string.cli_prof_exp_failed))
                }
            }
        }
    }
}

private fun launchResolvedExports(
    viewModel: HomeViewModel,
    selection: ProfilesExportSelectionState,
    state: CliProfileTransferState,
    scope: CoroutineScope,
    context: Context,
    block: suspend (List<CliProfileConfigExport>) -> Unit,
) {
    if (state.exportBusy) return
    state.exportBusy = true
    scope.launch {
        try {
            val exports = resolveExportsForSaf(viewModel, selection)
            if (exports.isEmpty()) {
                viewModel.emitInfo(context.getString(R.string.cli_prof_exp_failed))
            } else {
                block(exports)
            }
        } finally {
            state.exportBusy = false
        }
    }
}

private fun handleFileTransfer(
    exportMode: Boolean,
    launchers: CliTransferLaunchers,
    runExport: CliRunExport,
) {
    if (!exportMode) {
        launchers.importFile()
        return
    }
    // The resolved exports only pick the picker (single file vs directory) and its suggested name —
    // the payload itself is re-resolved in the launcher callback, so nothing is carried across it.
    runExport { exports ->
        val export = exports.singleOrNull()
        when {
            export == null -> launchers.exportTree()
            export.fileExtension == "json" -> launchers.exportJson(export.fileName)
            else -> launchers.exportText(export.fileName)
        }
    }
}

private fun handleQrTransfer(
    exportMode: Boolean,
    state: CliProfileTransferState,
    viewModel: HomeViewModel,
    context: Context,
    runExport: CliRunExport,
) {
    if (!exportMode) {
        state.qrScannerVisible = true
        return
    }
    runExport { exports ->
        val single = exports.singleOrNull()?.takeIf { it.configs.size == 1 }
        if (single == null) {
            viewModel.emitInfo(context.getString(R.string.cli_prof_exp_failed))
        } else {
            state.qrExport = CliQrExport(single.profileName, single.configs.single())
        }
    }
}

private fun handleClipboardTransfer(
    exportMode: Boolean,
    viewModel: HomeViewModel,
    context: Context,
    onSelectionCleared: () -> Unit,
    runExport: CliRunExport,
) {
    if (!exportMode) {
        viewModel.onPasteFromClipboard()
        return
    }
    runExport { exports ->
        cliCopyConfigsToClipboard(context, exports)
        viewModel.emitInfo(context.getString(R.string.cli_prof_exp_copied))
        onSelectionCleared()
    }
}

@Composable
private fun CliProfileTransferControls(
    exportMode: Boolean,
    selectedKeyCount: Int,
    busy: Boolean,
    buttonColor: Color,
    actions: CliTransferActions,
) {
    // Import and export occupy the exact same dashed geometry. Selection changes copy and tone,
    // never layout, so the VPN profile panel cannot jump while the buttons switch purpose.
    val colors = LocalCliColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cliDashedBorder(if (exportMode) colors.info else colors.border)
            .padding(CliSpacing.sm),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = CliSpacing.xs),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.pix_info),
                contentDescription = null,
                colorFilter = ColorFilter.tint(if (exportMode) colors.info else colors.dim),
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(CliSpacing.xs))
            Text(
                text = stringResource(
                    if (exportMode) R.string.cli_prof_export_hint else R.string.cli_prof_import_hint,
                ),
                style = CliType.small,
                color = if (exportMode) colors.info else colors.dim,
                textAlign = TextAlign.Center,
            )
        }
        CliProfileTransferButtons(
            exportMode = exportMode,
            selectedKeyCount = selectedKeyCount,
            busy = busy,
            buttonColor = buttonColor,
            actions = actions,
        )
    }
}

@Composable
private fun CliProfileTransferButtons(
    exportMode: Boolean,
    selectedKeyCount: Int,
    busy: Boolean,
    buttonColor: Color,
    actions: CliTransferActions,
) {
    val directionIcon = if (exportMode) R.drawable.pix_export else R.drawable.pix_import
    if (!exportMode) {
        // Camera is the primary import path. File and clipboard remain equal secondary actions;
        // this two-row shape keeps both labels readable at 320dp without shrinking the canon.
        Column(verticalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            CliButton(
                label = stringResource(R.string.cli_prof_imp_qr),
                icon = R.drawable.pix_qr,
                color = buttonColor,
                enabled = !busy,
                onClick = actions.qr,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
            ) {
                CliButton(
                    label = stringResource(R.string.cli_prof_imp_file),
                    icon = directionIcon,
                    color = buttonColor,
                    enabled = !busy,
                    onClick = actions.file,
                    modifier = Modifier.weight(1f),
                )
                CliButton(
                    label = stringResource(R.string.cli_prof_imp_clip),
                    icon = R.drawable.pix_copy,
                    color = buttonColor,
                    enabled = !busy,
                    onClick = actions.clipboard,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        return
    }

    // Share mirrors import at narrow widths: one readable primary row and two equal secondary
    // actions. Multi-profile/config payloads keep the QR affordance visible but unavailable.
    Column(verticalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
        CliButton(
            label = stringResource(R.string.cli_prof_exp_qr),
            icon = R.drawable.pix_qr,
            color = buttonColor,
            enabled = !busy && selectedKeyCount == 1,
            // Resolving the export briefly disables the action. Keep the blue frame stable while
            // it is busy (and when a multi-config selection makes QR unavailable) so the transfer
            // block does not flash as though the button disappeared.
            dimWhenDisabled = false,
            onClick = actions.qr,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
        ) {
            CliButton(
                label = stringResource(R.string.cli_prof_exp_file),
                icon = directionIcon,
                color = buttonColor,
                enabled = !busy,
                onClick = actions.file,
                modifier = Modifier.weight(1f),
            )
            CliButton(
                label = stringResource(R.string.cli_prof_exp_clip),
                icon = R.drawable.pix_copy,
                color = buttonColor,
                enabled = !busy,
                onClick = actions.clipboard,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CliProfileTransferOverlays(viewModel: HomeViewModel, state: CliProfileTransferState) {
    if (state.qrScannerVisible) {
        CliQrScannerOverlay(
            title = stringResource(R.string.scan_qr_code),
            onDismiss = { state.qrScannerVisible = false },
            onQrDetected = { value ->
                state.qrScannerVisible = false
                viewModel.importProfileRaw(value)
            },
        )
    }
    state.qrExport?.let { export ->
        CliQrExportOverlay(export = export, onDismiss = { state.qrExport = null })
    }
}

private val PROFILE_IMPORT_MIME_TYPES = arrayOf(
    "application/json",
    "text/plain",
    "text/*",
    "application/octet-stream",
)

/** Overlay panel with the QR bitmap of a single config; oversized payloads get an err line. */
@Composable
private fun CliQrExportOverlay(
    export: CliQrExport,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    var qrGenerated by remember(export.text) { mutableStateOf(false) }
    var qrBitmap by remember(export.text) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(export.text) {
        qrBitmap = withContext(Dispatchers.Default) {
            cliGenerateQrBitmap(export.text, QR_EXPORT_SIZE_PX)
        }
        qrGenerated = true
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
        DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(CliSpacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            CliPanel(
                icon = R.drawable.pix_qr,
                title = stringResource(R.string.cli_prof_exp_qr_title),
                titleColor = colors.accent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = export.name,
                    style = CliType.body,
                    color = colors.fg,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(CliSpacing.sm))
                val bitmap = qrBitmap
                when {
                    bitmap != null ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.cli_prof_exp_qr_title),
                            filterQuality = FilterQuality.None,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                // White quiet zone regardless of theme: scanners need it.
                                .background(Color.White)
                                .padding(CliSpacing.md),
                        )
                    qrGenerated ->
                        CliElbowLine(
                            text = stringResource(R.string.cli_prof_exp_qr_too_big),
                            color = colors.err,
                        )
                    else ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CliSpinner()
                            Text(text = " …", style = CliType.small, color = colors.dim)
                        }
                }
                Spacer(modifier = Modifier.height(CliSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    CliChip(
                        label = stringResource(R.string.cli_common_no_cancel),
                        color = colors.err,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

private const val QR_EXPORT_SIZE_PX = 768
