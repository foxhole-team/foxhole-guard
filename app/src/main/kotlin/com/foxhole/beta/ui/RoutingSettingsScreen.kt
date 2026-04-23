package com.foxhole.beta.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.RoutingCatalog
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import kotlinx.coroutines.launch

@Composable
fun RoutingSettingsScreen(
    state: RoutingRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onCreatePreset: (String) -> Unit,
    onUpdatePreset: (Long, String, RoutingPresetOverrideMode, Boolean) -> Unit,
    onSetActivePreset: (Long?) -> Unit,
    onDeletePreset: (Long) -> Unit,
    onSaveRule: (Long, Long?, String, Boolean, Int?, RoutingRuleAction, List<String>, List<String>, List<String>, List<String>, List<String>) -> Unit,
    onDeleteRule: (Long) -> Unit,
    onImportPresetText: (String, RoutingPresetSource) -> Unit,
    onExportPresetDocument: suspend (Long) -> String,
    onAddCatalog: (String, String) -> Unit,
    onRefreshCatalog: (Long) -> Unit,
    onDeleteCatalog: (Long) -> Unit,
    onLoadCatalogPreview: (Long) -> Unit,
    onImportPresetFromCatalog: (Long, String) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    var createPresetDialog by rememberSaveable { mutableStateOf(false) }
    var editPreset by remember { mutableStateOf<RoutingPreset?>(null) }
    var ruleDialogState by remember { mutableStateOf<RuleDialogState?>(null) }
    var addCatalogDialog by rememberSaveable { mutableStateOf(false) }
    var openCatalogId by rememberSaveable { mutableStateOf<Long?>(null) }
    var exportPresetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var expandedPresetId by rememberSaveable { mutableStateOf<Long?>(null) }

    val importPresetLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@rememberLauncherForActivityResult
            }
            coroutineScope.launch {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    onImportPresetText(reader.readText(), RoutingPresetSource.FILE)
                }
            }
        }
    val exportPresetLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val presetId = exportPresetId ?: return@rememberLauncherForActivityResult
            if (uri == null) {
                exportPresetId = null
                return@rememberLauncherForActivityResult
            }
            coroutineScope.launch {
                val payload = onExportPresetDocument(presetId)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(payload)
                }
                exportPresetId = null
            }
        }

    LaunchedEffect(openCatalogId) {
        openCatalogId?.let(onLoadCatalogPreview)
    }

    SettingsScaffold(
        title = stringResource(R.string.traffic_rules),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
            InfoBlock(
                title = stringResource(R.string.information_title),
                body = stringResource(R.string.routing_info_body),
            )
        }
        item {
            SettingValueRow(
                title = stringResource(R.string.local_preset_override),
                value = state.activePreset?.name ?: stringResource(R.string.routing_preset_none),
                onClick = null,
            )
        }
        item {
            ActionRow(
                primaryLabel = stringResource(R.string.add_preset),
                onPrimary = { createPresetDialog = true },
                secondaryLabel = stringResource(R.string.import_from_clipboard),
                onSecondary = {
                    val raw = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (raw.isNotBlank()) {
                        onImportPresetText(raw, RoutingPresetSource.LOCAL)
                    }
                },
                tertiaryLabel = stringResource(R.string.import_from_file),
                onTertiary = { importPresetLauncher.launch(arrayOf("application/json", "text/plain")) },
            )
        }
        items(state.presets, key = RoutingPreset::id) { preset ->
            FoxholeCard {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .clickable {
                                expandedPresetId = if (expandedPresetId == preset.id) null else preset.id
                            },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text =
                                buildString {
                                    append(preset.source.name.lowercase())
                                    append(" • ")
                                    append(preset.overrideMode.name.lowercase())
                                    append(" • ")
                                    append(stringResource(R.string.rules_count, preset.rules.size))
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (preset.isActive) {
                            Text(
                                text = stringResource(R.string.active_label),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                if (expandedPresetId == preset.id) {
                    PresetActionRow(
                        onActivate = { onSetActivePreset(if (preset.isActive) null else preset.id) },
                        activateLabel =
                            if (preset.isActive) {
                                stringResource(R.string.disable_label)
                            } else {
                                stringResource(R.string.activate_label)
                            },
                        onEdit = { editPreset = preset },
                        onExport = {
                            exportPresetId = preset.id
                            exportPresetLauncher.launch("${preset.name}.json")
                        },
                        onDelete = { onDeletePreset(preset.id) },
                        onAddRule = { ruleDialogState = RuleDialogState(presetId = preset.id) },
                    )
                    preset.rules.forEach { rule ->
                        FoxholeCard(modifier = Modifier.padding(start = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = rule.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = ruleSummary(rule),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row {
                                    IconButton(onClick = { ruleDialogState = RuleDialogState(presetId = preset.id, rule = rule) }) {
                                        Icon(Icons.Outlined.Edit, contentDescription = null)
                                    }
                                    IconButton(onClick = { onDeleteRule(rule.id) }) {
                                        Icon(Icons.Outlined.Delete, contentDescription = null)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionTitle(text = stringResource(R.string.remote_catalogs))
        }
        item {
            ActionRow(
                primaryLabel = stringResource(R.string.add_catalog),
                onPrimary = { addCatalogDialog = true },
                secondaryLabel = stringResource(R.string.none_label),
                onSecondary = null,
                tertiaryLabel = null,
                onTertiary = null,
            )
        }
        items(state.catalogs, key = RoutingCatalog::id) { catalog ->
            FoxholeCard(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .clickable { openCatalogId = catalog.id },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = catalog.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${catalog.url} • ${stringResource(R.string.rules_count, catalog.cachedPresetCount)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        IconButton(onClick = { onRefreshCatalog(catalog.id) }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                        }
                        IconButton(onClick = { onDeleteCatalog(catalog.id) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                        }
                    }
                }
            }
        }
    }

    if (createPresetDialog) {
        TextValueDialog(
            title = stringResource(R.string.add_preset),
            initialValue = "",
            singleLine = true,
            onDismiss = { createPresetDialog = false },
            onConfirm = onCreatePreset,
        )
    }

    editPreset?.let { preset ->
        PresetDialog(
            preset = preset,
            onDismiss = { editPreset = null },
            onConfirm = { name, overrideMode, enabled ->
                onUpdatePreset(preset.id, name, overrideMode, enabled)
                editPreset = null
            },
        )
    }

    ruleDialogState?.let { dialogState ->
        RuleEditorDialog(
            presetId = dialogState.presetId,
            rule = dialogState.rule,
            onDismiss = { ruleDialogState = null },
            onConfirm = { name, enabled, order, action, domains, ipCidrs, ports, protocols, networks ->
                onSaveRule(
                    dialogState.presetId,
                    dialogState.rule?.id,
                    name,
                    enabled,
                    order,
                    action,
                    domains,
                    ipCidrs,
                    ports,
                    protocols,
                    networks,
                )
                ruleDialogState = null
            },
        )
    }

    if (addCatalogDialog) {
        CatalogDialog(
            onDismiss = { addCatalogDialog = false },
            onConfirm = { name, url ->
                onAddCatalog(name, url)
                addCatalogDialog = false
            },
        )
    }

    openCatalogId?.let { catalogId ->
        CatalogPreviewDialog(
            catalog = state.catalogs.firstOrNull { it.id == catalogId },
            presets = state.catalogPresetPreviews[catalogId].orEmpty(),
            onDismiss = { openCatalogId = null },
            onImport = { presetId -> onImportPresetFromCatalog(catalogId, presetId) },
        )
    }
}

private data class RuleDialogState(
    val presetId: Long,
    val rule: RoutingRule? = null,
)
