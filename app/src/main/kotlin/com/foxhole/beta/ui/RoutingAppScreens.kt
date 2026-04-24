package com.foxhole.beta.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction

@Composable
fun RoutingAppsScreen(
    state: RoutingRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onPerAppRoutingModeSelected: (PerAppRoutingMode) -> Unit,
    onOpenPicker: () -> Unit,
    onSelectedPackagesChanged: (List<String>) -> Unit,
) {
    var modeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedExpanded by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    val selectedPackages = state.settings.expert.selectedPackages.toSet()
    val selectedApps =
        remember(state.installedApps, state.settings.expert.selectedPackages) {
            resolveSelectedApps(state.installedApps, state.settings.expert.selectedPackages)
        }
    val filteredSelectedApps = remember(selectedApps, query) { filterApps(selectedApps, query) }

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
                value = perAppRoutingModeLabel(state.settings.expert.perAppRoutingMode),
                expanded = modeMenuExpanded,
                onExpandedChange = { modeMenuExpanded = it },
                values = PerAppRoutingMode.entries,
                selected = state.settings.expert.perAppRoutingMode,
                label = ::perAppRoutingModeLabel,
                onSelect = onPerAppRoutingModeSelected,
                summary = routingAppsModeGuidance(state.settings.expert.perAppRoutingMode),
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
                    onClick = onOpenPicker,
                    modifier = Modifier.fillMaxWidth().testTag("routing_apps_add_exception_action"),
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
        if (selectedApps.isEmpty()) {
            item {
                WarningBlock(
                    title = stringResource(R.string.no_app_exceptions_title),
                    body = stringResource(R.string.no_app_exceptions_summary),
                )
            }
        } else {
            item {
                FoxholeCard(modifier = Modifier.testTag("routing_apps_selected_section")) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .clickable { selectedExpanded = !selectedExpanded },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.selected_app_exceptions),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = pluralStringResource(R.plurals.selected_apps_count_summary, selectedPackages.size, selectedPackages.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = if (selectedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                        )
                    }
                    if (selectedExpanded) {
                        FoxholeSearchField(
                            value = query,
                            onValueChange = { query = it },
                            label = stringResource(R.string.search_selected_apps),
                        )
                        if (filteredSelectedApps.isEmpty()) {
                            Text(
                                text = stringResource(R.string.no_matching_apps),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            filteredSelectedApps.forEach { app ->
                                SelectedAppRow(
                                    app = app,
                                    onRemove = {
                                        onSelectedPackagesChanged(
                                            selectedPackages
                                                .filterNot { it == app.packageName },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
    onSaveSiteRule: (Long?, List<String>, RoutingRuleAction) -> Unit,
    onDeleteRule: (Long) -> Unit,
) {
    var editingRule by remember { mutableStateOf<RoutingRule?>(null) }
    var createDialogVisible by rememberSaveable { mutableStateOf(false) }
    var deleteRuleId by rememberSaveable { mutableStateOf<Long?>(null) }
    val siteRules = state.activePreset?.rules.orEmpty().filter { it.matchDomains.isNotEmpty() }

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
                Button(
                    onClick = { createDialogVisible = true },
                    modifier = Modifier.fillMaxWidth().testTag("routing_sites_add_exception_action"),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.add_site_exception),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        if (siteRules.isEmpty()) {
            item {
                WarningBlock(
                    title = stringResource(R.string.no_site_exceptions_title),
                    body = stringResource(R.string.no_site_exceptions_summary),
                )
            }
        }
        items(siteRules, key = RoutingRule::id) { rule ->
            FoxholeCard {
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
                            text = rule.matchDomains.firstOrNull() ?: rule.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = rule.matchDomains.joinToString(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = siteRuleSummary(rule),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { editingRule = rule }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.edit_label))
                        }
                        IconButton(onClick = { deleteRuleId = rule.id }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_label))
                        }
                    }
                }
            }
        }
    }

    if (createDialogVisible) {
        SiteRuleDialog(
            rule = null,
            onDismiss = { createDialogVisible = false },
            onConfirm = { domains, action ->
                onSaveSiteRule(null, domains, action)
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
}
