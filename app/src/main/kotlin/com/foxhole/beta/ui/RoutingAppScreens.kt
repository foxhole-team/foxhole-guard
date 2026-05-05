package com.foxhole.beta.ui

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.ui.theme.LocalFoxholeUiPalette

@Composable
fun RoutingAppsScreen(
    state: RoutingRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onPerAppRoutingModeSelected: (PerAppRoutingMode) -> Unit,
    onOpenPicker: () -> Unit,
    onSelectedPackagesChanged: (List<String>) -> Unit,
    onBlockedPackagesChanged: (List<String>) -> Unit,
    onBlockedPackagesEnabledChanged: (Boolean) -> Unit,
) {
    var modeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedInfoVisible by rememberSaveable { mutableStateOf(false) }
    var blockedInfoVisible by rememberSaveable { mutableStateOf(false) }
    val selectedPackages = state.settings.expert.selectedPackages.toSet()
    val blockedPackages = state.settings.expert.blockedPackages.toSet()
    val selectedApps =
        remember(state.installedApps, state.settings.expert.selectedPackages) {
            resolveSelectedApps(state.installedApps, state.settings.expert.selectedPackages)
        }
    val blockedApps =
        remember(state.installedApps, state.settings.expert.blockedPackages) {
            resolveSelectedApps(state.installedApps, state.settings.expert.blockedPackages)
        }
    val appModeValues = remember {
        listOf(PerAppRoutingMode.INCLUDE_SELECTED_APPS, PerAppRoutingMode.EXCLUDE_SELECTED_APPS)
    }
    val effectiveAppMode =
        state.settings.expert.perAppRoutingMode.takeIf { it != PerAppRoutingMode.FULL_TUNNEL }
            ?: PerAppRoutingMode.INCLUDE_SELECTED_APPS

    SettingsScaffold(
        title = stringResource(R.string.routing_apps_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
            InfoBlock(
                title = stringResource(R.string.information_title),
                body = stringResource(R.string.routing_apps_info_body),
            )
        }
        item {
            DropdownSettingRow(
                title = stringResource(R.string.operating_mode),
                value = simpleAppRoutingModeLabel(effectiveAppMode),
                expanded = modeMenuExpanded,
                onExpandedChange = { modeMenuExpanded = it },
                values = appModeValues,
                selected = effectiveAppMode,
                label = ::simpleAppRoutingModeLabel,
                onSelect = onPerAppRoutingModeSelected,
                summary = simpleAppRoutingModeGuidance(effectiveAppMode),
                optionIcon = ::perAppRoutingModeIcon,
            )
        }
        item {
            FoxholeCard {
                Text(
                    text = pluralStringResource(R.plurals.selected_apps_count_summary, selectedPackages.size, selectedPackages.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.selected_apps_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        if (state.settings.expert.perAppRoutingMode == PerAppRoutingMode.FULL_TUNNEL) {
                            onPerAppRoutingModeSelected(PerAppRoutingMode.INCLUDE_SELECTED_APPS)
                        }
                        onOpenPicker()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("routing_apps_add_exception_action"),
                    colors = foxholeDropdownColoredButtonColors(),
                    border = foxholeDropdownColoredButtonBorder(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.add_app_exception),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (selectedPackages.isNotEmpty()) {
                    TextButton(
                        onClick = { onSelectedPackagesChanged(emptyList()) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.clear_selection))
                    }
                }
            }
        }
        item {
            AppGridSection(
                title = stringResource(R.string.selected_app_exceptions),
                summary = pluralStringResource(R.plurals.selected_apps_count_summary, selectedPackages.size, selectedPackages.size),
                apps = selectedApps,
                emptyText = stringResource(R.string.no_app_exceptions_summary),
                onInfoClick = { selectedInfoVisible = true },
                onRemove = { app ->
                    onSelectedPackagesChanged(selectedPackages.filterNot { it == app.packageName })
                },
                onMove = { app ->
                    onSelectedPackagesChanged(selectedPackages.filterNot { it == app.packageName })
                    onBlockedPackagesChanged((blockedPackages + app.packageName).toList())
                },
                onDropPackage = { packageName ->
                    if (packageName !in selectedPackages) {
                        onBlockedPackagesChanged(blockedPackages.filterNot { it == packageName })
                        onSelectedPackagesChanged((selectedPackages + packageName).toList())
                    }
                },
                modifier = Modifier.testTag("routing_apps_selected_section"),
            )
        }
        item {
            AppGridSection(
                title = stringResource(R.string.blocked_app_exceptions),
                summary = pluralStringResource(R.plurals.blocked_apps_count_summary, blockedPackages.size, blockedPackages.size),
                apps = blockedApps,
                emptyText = stringResource(R.string.no_blocked_apps_summary),
                toggleChecked = state.settings.expert.blockedPackagesEnabled,
                onToggleChanged = onBlockedPackagesEnabledChanged,
                onInfoClick = { blockedInfoVisible = true },
                onRemove = { app ->
                    onBlockedPackagesChanged(blockedPackages.filterNot { it == app.packageName })
                },
                onMove = { app ->
                    onBlockedPackagesChanged(blockedPackages.filterNot { it == app.packageName })
                    onSelectedPackagesChanged((selectedPackages + app.packageName).toList())
                },
                onDropPackage = { packageName ->
                    if (packageName !in blockedPackages) {
                        onSelectedPackagesChanged(selectedPackages.filterNot { it == packageName })
                        onBlockedPackagesChanged((blockedPackages + packageName).toList())
                    }
                },
                modifier = Modifier.testTag("routing_apps_blocked_section"),
            )
        }
    }

    if (selectedInfoVisible) {
        AppInfoDialog(
            title = stringResource(R.string.selected_app_exceptions),
            body = stringResource(R.string.selected_apps_info_body),
            onDismiss = { selectedInfoVisible = false },
        )
    }
    if (blockedInfoVisible) {
        AppInfoDialog(
            title = stringResource(R.string.blocked_app_exceptions),
            body = stringResource(R.string.blocked_apps_info_body),
            onDismiss = { blockedInfoVisible = false },
        )
    }
}

@Composable
private fun AppGridSection(
    title: String,
    summary: String,
    apps: List<InstalledAppOption>,
    emptyText: String,
    onInfoClick: () -> Unit,
    onRemove: (InstalledAppOption) -> Unit,
    onMove: (InstalledAppOption) -> Unit,
    onDropPackage: (String) -> Unit,
    modifier: Modifier = Modifier,
    toggleChecked: Boolean? = null,
    onToggleChanged: ((Boolean) -> Unit)? = null,
) {
    var removalPackage by rememberSaveable { mutableStateOf<String?>(null) }
    FoxholeCard(
        modifier =
            modifier.dragAndDropTarget(
                shouldStartDragAndDrop = ::isFoxholeAppDragEvent,
                target =
                    remember(onDropPackage) {
                        foxholeDropTarget(FOXHOLE_APP_DRAG_PREFIX, onDropPackage)
                    },
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = onInfoClick, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = stringResource(R.string.information_title),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (toggleChecked != null && onToggleChanged != null) {
                FoxholeSwitch(
                    checked = toggleChecked,
                    onCheckedChange = onToggleChanged,
                )
            }
        }
        if (apps.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                apps.forEach { app ->
                    AppGridTile(
                        app = app,
                        removing = removalPackage == app.packageName,
                        onToggleRemove = {
                            removalPackage =
                                if (removalPackage == app.packageName) {
                                    null
                                } else {
                                    app.packageName
                                }
                        },
                        onRemove = {
                            removalPackage = null
                            onRemove(app)
                        },
                        onMove = {
                            removalPackage = null
                            onMove(app)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGridTile(
    app: InstalledAppOption,
    removing: Boolean,
    onToggleRemove: () -> Unit,
    onRemove: () -> Unit,
    onMove: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(width = 76.dp, height = 92.dp)
                .clip(MaterialTheme.shapes.medium)
                .dragAndDropSource(transferData = {
                    DragAndDropTransferData(
                        clipData = ClipData.newPlainText(FOXHOLE_APP_DRAG_LABEL, "$FOXHOLE_APP_DRAG_PREFIX${app.packageName}"),
                        localState = app.packageName,
                    )
                })
                .combinedClickable(
                    onClick = onToggleRemove,
                    onLongClick = onMove,
                ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppIcon(
                packageName = app.packageName,
                modifier = Modifier.size(44.dp),
            )
            Text(
                text = app.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (removing) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                onClick = onRemove,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.delete_label),
                    modifier = Modifier.padding(5.dp),
                )
            }
        }
    }
}

@Composable
private fun AppInfoDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            FoxholeDialogDismissButton(onClick = onDismiss)
        },
    )
}

@Composable
fun AppPickerScreen(
    state: RoutingRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onSaveSelection: (List<String>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filterMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var appFilter by rememberSaveable { mutableStateOf(InstalledAppFilter.ALL) }
    var draftSelection by rememberSaveable(state.settings.expert.selectedPackages) {
        mutableStateOf(state.settings.expert.selectedPackages)
    }
    val filteredApps = remember(state.installedApps, query, appFilter) {
        filterApps(state.installedApps, query)
            .filter { app ->
                when (appFilter) {
                    InstalledAppFilter.ALL -> true
                    InstalledAppFilter.USER -> !app.isSystemApp
                    InstalledAppFilter.SYSTEM -> app.isSystemApp
                }
            }
    }

    SettingsScaffold(
        title = stringResource(R.string.app_picker_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        tag = "routing_apps_picker_screen",
        actions = {
            FoxholeSaveAction(
                onClick = { onSaveSelection(draftSelection) },
                modifier = Modifier.testTag("routing_apps_picker_save_action"),
            )
        },
    ) {
        item {
            WarningBlock(
                title = stringResource(R.string.all_installed_apps_title),
                body = pluralStringResource(R.plurals.all_installed_apps_summary, state.installedApps.size, state.installedApps.size),
            )
        }
        item {
            FoxholeSearchField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.search_apps),
                tag = "routing_apps_picker_search",
            )
        }
        item {
            DropdownSettingRow(
                title = stringResource(R.string.app_filter_title),
                value = appFilterLabel(appFilter),
                expanded = filterMenuExpanded,
                onExpandedChange = { filterMenuExpanded = it },
                values = InstalledAppFilter.entries,
                selected = appFilter,
                label = ::appFilterLabel,
                onSelect = { appFilter = it },
                summary = stringResource(R.string.app_filter_summary),
                optionIcon = ::installedAppFilterIcon,
            )
        }
        item {
            SettingValueRow(
                title = stringResource(R.string.selected_app_exceptions),
                value = draftSelection.size.toString(),
                summary = stringResource(R.string.selected_apps_summary),
                onClick = null,
            )
        }
        if (state.installedAppsLoading || !state.installedAppsLoaded) {
            item {
                WarningBlock(
                    title = stringResource(R.string.loading_apps_title),
                    body = stringResource(R.string.loading_apps_summary),
                )
            }
        } else if (filteredApps.isEmpty()) {
            item {
                WarningBlock(
                    title = stringResource(R.string.no_matching_apps),
                    body = stringResource(R.string.no_matching_apps_summary),
                )
            }
        }
        items(filteredApps, key = InstalledAppOption::packageName) { app ->
            val checked = draftSelection.contains(app.packageName)
            SelectableInstalledAppRow(
                app = app,
                checked = checked,
                onToggle = { value ->
                    draftSelection =
                        draftSelection
                            .toMutableSet()
                            .apply {
                                if (value) {
                                    add(app.packageName)
                                } else {
                                    remove(app.packageName)
                                }
                            }.toList()
                    Unit
                },
            )
        }
    }
}

@Composable
fun RoutingSitesScreen(
    state: RoutingRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onSiteRoutingActionSelected: (RoutingRuleAction) -> Unit,
    onSaveSiteRule: (Long?, List<String>, RoutingRuleAction) -> Unit,
    onDeleteRule: (Long) -> Unit,
) {
    var editingRule by remember { mutableStateOf<RoutingRule?>(null) }
    var createDialogVisible by rememberSaveable { mutableStateOf(false) }
    var deleteRuleId by rememberSaveable { mutableStateOf<Long?>(null) }
    var siteModeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedInfoVisible by rememberSaveable { mutableStateOf(false) }
    var blockedInfoVisible by rememberSaveable { mutableStateOf(false) }
    val siteRules =
        state.activePreset
            ?.rules
            .orEmpty()
            .filter { rule -> rule.isManagedSimpleSiteRule() && (rule.matchDomains.isNotEmpty() || rule.matchIpCidrs.isNotEmpty()) }
    val selectedSiteRules = siteRules.filter { it.isManagedSelectedSiteRule() }
    val blockedSiteRules = siteRules.filter { it.isManagedBlockedSiteRule() }
    val siteModeValues = remember { listOf(RoutingRuleAction.PROXY, RoutingRuleAction.DIRECT) }

    SettingsScaffold(
        title = stringResource(R.string.routing_sites_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
            InfoBlock(
                title = stringResource(R.string.information_title),
                body =
                    if (state.activePreset == null) {
                        stringResource(R.string.routing_sites_create_preset_summary)
                    } else {
                        stringResource(R.string.routing_sites_active_preset_summary, state.activePreset.name)
                    },
            )
        }
        item {
            FoxholeCard {
                DropdownSettingRow(
                    title = stringResource(R.string.operating_mode),
                    value = siteActionLabel(state.settings.expert.siteRoutingAction),
                    expanded = siteModeMenuExpanded,
                    onExpandedChange = { siteModeMenuExpanded = it },
                    values = siteModeValues,
                    selected = state.settings.expert.siteRoutingAction,
                    label = { siteActionLabel(it) },
                    onSelect = onSiteRoutingActionSelected,
                    optionIcon = { siteActionIcon(it) },
                )
                Button(
                    onClick = { createDialogVisible = true },
                    modifier = Modifier.fillMaxWidth().testTag("routing_sites_add_exception_action"),
                    colors = foxholeDropdownColoredButtonColors(),
                    border = foxholeDropdownColoredButtonBorder(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.add_site_exception),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        item {
            SiteChipSection(
                title = stringResource(R.string.selected_sites_title),
                rules = selectedSiteRules,
                emptyText = stringResource(R.string.no_site_exceptions_summary),
                onInfoClick = { selectedInfoVisible = true },
                onEdit = { editingRule = it },
                onDelete = { deleteRuleId = it.id },
                onMove = { rule ->
                    onSaveSiteRule(rule.id, rule.siteRuleTokens(), RoutingRuleAction.BLOCK)
                },
                onDropRuleId = { ruleId ->
                    siteRules.firstOrNull { it.id == ruleId }?.takeIf { it.isManagedBlockedSiteRule() }?.let { rule ->
                        onSaveSiteRule(rule.id, rule.siteRuleTokens(), state.settings.expert.siteRoutingAction)
                    }
                },
            )
        }
        item {
            SiteChipSection(
                title = stringResource(R.string.blocked_sites_title),
                rules = blockedSiteRules,
                emptyText = stringResource(R.string.no_blocked_sites_summary),
                onInfoClick = { blockedInfoVisible = true },
                onEdit = { editingRule = it },
                onDelete = { deleteRuleId = it.id },
                onMove = { rule ->
                    onSaveSiteRule(rule.id, rule.siteRuleTokens(), state.settings.expert.siteRoutingAction)
                },
                onDropRuleId = { ruleId ->
                    siteRules.firstOrNull { it.id == ruleId }?.takeIf { it.isManagedSelectedSiteRule() }?.let { rule ->
                        onSaveSiteRule(rule.id, rule.siteRuleTokens(), RoutingRuleAction.BLOCK)
                    }
                },
            )
        }
    }

    if (createDialogVisible) {
        SiteRuleDialog(
            rule = null,
            onDismiss = { createDialogVisible = false },
            onConfirm = { domains, action ->
                onSaveSiteRule(null, domains, state.settings.expert.siteRoutingAction)
                createDialogVisible = false
            },
        )
    }

    editingRule?.let { rule ->
        SiteRuleDialog(
            rule = rule,
            onDismiss = { editingRule = null },
            onConfirm = { domains, action ->
                onSaveSiteRule(rule.id, domains, action)
                editingRule = null
            },
        )
    }

    deleteRuleId?.let { ruleId ->
        ConfirmDialog(
            title = stringResource(R.string.delete_site_exception_title),
            body = stringResource(R.string.delete_site_exception_summary),
            confirmLabel = stringResource(R.string.delete_label),
            onDismiss = { deleteRuleId = null },
            onConfirm = {
                onDeleteRule(ruleId)
                deleteRuleId = null
            },
        )
    }

    if (selectedInfoVisible) {
        AppInfoDialog(
            title = stringResource(R.string.selected_sites_title),
            body = stringResource(R.string.selected_sites_info_body),
            onDismiss = { selectedInfoVisible = false },
        )
    }
    if (blockedInfoVisible) {
        AppInfoDialog(
            title = stringResource(R.string.blocked_sites_title),
            body = stringResource(R.string.blocked_sites_info_body),
            onDismiss = { blockedInfoVisible = false },
        )
    }
}

@Composable
private fun SiteChipSection(
    title: String,
    rules: List<RoutingRule>,
    emptyText: String,
    onInfoClick: () -> Unit,
    onEdit: (RoutingRule) -> Unit,
    onDelete: (RoutingRule) -> Unit,
    onMove: (RoutingRule) -> Unit,
    onDropRuleId: (Long) -> Unit,
) {
    FoxholeCard(
        modifier =
            Modifier.dragAndDropTarget(
                shouldStartDragAndDrop = ::isFoxholeSiteDragEvent,
                target =
                    remember(onDropRuleId) {
                        foxholeDropTarget(FOXHOLE_SITE_DRAG_PREFIX) { payload ->
                            payload.toLongOrNull()?.let(onDropRuleId)
                        }
                    },
            ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onInfoClick, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = stringResource(R.string.information_title),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        if (rules.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rules.forEach { rule ->
                    SiteRuleChip(
                        rule = rule,
                        onEdit = { onEdit(rule) },
                        onDelete = { onDelete(rule) },
                        onMove = { onMove(rule) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SiteRuleChip(
    rule: RoutingRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    var deleteVisible by rememberSaveable(rule.id) { mutableStateOf(false) }
    Box {
        Surface(
            modifier =
                Modifier
                    .clip(MaterialTheme.shapes.large)
                    .dragAndDropSource(transferData = {
                        DragAndDropTransferData(
                            clipData = ClipData.newPlainText(FOXHOLE_SITE_DRAG_LABEL, "$FOXHOLE_SITE_DRAG_PREFIX${rule.id}"),
                            localState = rule.id,
                        )
                    })
                    .combinedClickable(
                        onClick = { deleteVisible = !deleteVisible },
                        onLongClick = onMove,
                    ),
            shape = MaterialTheme.shapes.large,
            color = LocalFoxholeUiPalette.current.valuePillContainerColor,
            border = foxholeDropdownColoredButtonBorder(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = rule.siteRuleTokens().firstOrNull() ?: rule.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.edit_label), modifier = Modifier.size(16.dp))
                }
            }
        }
        if (deleteVisible) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                onClick = onDelete,
            ) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.delete_label), modifier = Modifier.padding(5.dp))
            }
        }
    }
}

private fun RoutingRule.siteRuleTokens(): List<String> =
    matchDomains + matchIpCidrs.map { "cidr:$it" }

private fun RoutingRule.isManagedSimpleSiteRule(): Boolean =
    isManagedSelectedSiteRule() || isManagedBlockedSiteRule()

private fun RoutingRule.isManagedSelectedSiteRule(): Boolean =
    name.startsWith(MANAGED_SELECTED_SITE_RULE_PREFIX)

private fun RoutingRule.isManagedBlockedSiteRule(): Boolean =
    name.startsWith(MANAGED_BLOCKED_SITE_RULE_PREFIX)

private fun isFoxholeAppDragEvent(event: DragAndDropEvent): Boolean =
    event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
        event.dragPayload().startsWith(FOXHOLE_APP_DRAG_PREFIX)

private fun isFoxholeSiteDragEvent(event: DragAndDropEvent): Boolean =
    event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
        event.dragPayload().startsWith(FOXHOLE_SITE_DRAG_PREFIX)

private fun foxholeDropTarget(
    prefix: String,
    onDropPayload: (String) -> Unit,
): DragAndDropTarget =
    object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val payload = event.dragPayload().removePrefix(prefix).takeIf(String::isNotBlank) ?: return false
            onDropPayload(payload)
            return true
        }
    }

private fun DragAndDropEvent.dragPayload(): String =
    toAndroidDragEvent().clipData?.getItemAt(0)?.text?.toString().orEmpty()

private const val FOXHOLE_APP_DRAG_PREFIX = "foxhole-app:"
private const val FOXHOLE_APP_DRAG_LABEL = "FoxHole app"
private const val FOXHOLE_SITE_DRAG_PREFIX = "foxhole-site:"
private const val FOXHOLE_SITE_DRAG_LABEL = "FoxHole site"
private const val MANAGED_SELECTED_SITE_RULE_PREFIX = "FoxHole selected site:"
private const val MANAGED_BLOCKED_SITE_RULE_PREFIX = "FoxHole blocked site:"

@Composable
private fun foxholeDropdownColoredButtonColors() =
    ButtonDefaults.buttonColors(
        containerColor = LocalFoxholeUiPalette.current.valuePillContainerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )

@Composable
private fun foxholeDropdownColoredButtonBorder(): BorderStroke? {
    val borderColor = LocalFoxholeUiPalette.current.valuePillBorderColor
    return borderColor.takeUnless { it == androidx.compose.ui.graphics.Color.Transparent }?.let { BorderStroke(1.dp, it) }
}
