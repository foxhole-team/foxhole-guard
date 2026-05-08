package com.foxhole.beta.ui

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.ui.theme.LocalFoxholeUiPalette
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable
fun RoutingAppsScreen(
    state: RoutingRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onPerAppRoutingModeSelected: (PerAppRoutingMode) -> Unit,
    onOpenPicker: () -> Unit,
    onOpenBlockedPicker: () -> Unit,
    onSelectedPackagesChanged: (List<String>) -> Unit,
    onBlockedPackagesChanged: (List<String>) -> Unit,
    onBlockAppsAlwaysChanged: (Boolean) -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
) {
    var modeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var blockAlwaysWarningVisible by rememberSaveable { mutableStateOf(false) }
    val selectedPackages = state.settings.expert.selectedPackages
    val blockedPackages = state.settings.expert.blockedPackages
    val selectedPackageSet = selectedPackages.toSet()
    val blockedPackageSet = blockedPackages.toSet()
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
    val storedAppMode =
        state.settings.expert.perAppRoutingMode.takeIf { it != PerAppRoutingMode.FULL_TUNNEL }
    var draftAppMode by rememberSaveable {
        mutableStateOf(storedAppMode ?: PerAppRoutingMode.INCLUDE_SELECTED_APPS)
    }
    LaunchedEffect(storedAppMode) {
        if (storedAppMode != null) {
            draftAppMode = storedAppMode
        }
    }
    val effectiveAppMode =
        storedAppMode ?: draftAppMode
    val splitTunnelEnabled = storedAppMode != null
    val hasTunnelApps = selectedPackages.isNotEmpty()
    val splitTunnelToggleEnabled = splitTunnelEnabled || hasTunnelApps

    SettingsScaffold(
        title = stringResource(R.string.routing_apps_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        bannerPlacement = FoxholeBannerPlacement.BOTTOM,
    ) {
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.enable_split_tunnel),
                    checked = splitTunnelEnabled,
                    enabled = splitTunnelToggleEnabled,
                    leadingIcon = Icons.Outlined.AccountTree,
                    summary =
                        if (!splitTunnelToggleEnabled) {
                            stringResource(R.string.split_tunnel_requires_apps)
                        } else {
                            null
                        },
                    onCheckedChange = { enabled ->
                        if (enabled && !hasTunnelApps) {
                            return@SettingSwitchRow
                        }
                        if (!enabled) {
                            draftAppMode = effectiveAppMode
                        }
                        onPerAppRoutingModeSelected(
                            if (enabled) {
                                effectiveAppMode
                            } else {
                                PerAppRoutingMode.FULL_TUNNEL
                            },
                        )
                    },
                    grouped = true,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.operating_mode),
                    value = simpleAppRoutingModeLabel(effectiveAppMode),
                    expanded = modeMenuExpanded,
                    onExpandedChange = { modeMenuExpanded = it },
                    values = appModeValues,
                    selected = effectiveAppMode,
                    label = ::simpleAppRoutingModeLabel,
                    onSelect = { mode ->
                        draftAppMode = mode
                        if (splitTunnelEnabled) {
                            onPerAppRoutingModeSelected(mode)
                        }
                    },
                    leadingIcon = perAppRoutingModeIcon(effectiveAppMode),
                    optionIcon = ::perAppRoutingModeIcon,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                AppGridSectionContent(
                    title = stringResource(R.string.routing_apps_title),
                    subtitle = simpleAppRoutingModeGuidance(effectiveAppMode),
                    leadingIcon = Icons.Outlined.Apps,
                    apps = selectedApps,
                    emptyText = stringResource(R.string.split_tunnel_requires_apps),
                    headerActionLabel = stringResource(R.string.choose_label),
                    headerActionTag = "routing_apps_add_exception_action",
                    onHeaderAction = onOpenPicker,
                    onRemove = { app ->
                        onSelectedPackagesChanged(selectedPackages.filterNot { it == app.packageName })
                    },
                    onDropPackage = { packageName, beforePackageName ->
                        if (packageName in selectedPackageSet || packageName in blockedPackageSet) {
                            onBlockedPackagesChanged(blockedPackages.filterNot { it == packageName })
                            onSelectedPackagesChanged(insertPackageBefore(selectedPackages, packageName, beforePackageName))
                        }
                    },
                    modifier = Modifier.testTag("routing_apps_selected_section"),
                )
            }
        }
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.block_apps_always_title),
                    checked =
                        state.settings.expert.firewallEnabled &&
                            state.settings.expert.blockAppsAlways &&
                            state.settings.expert.blockedPackagesEnabled &&
                            blockedPackages.isNotEmpty(),
                    enabled = blockedPackages.isNotEmpty(),
                    leadingIcon = Icons.Outlined.Block,
                    summary = stringResource(R.string.block_apps_always_summary),
                    infoBody = stringResource(R.string.block_apps_always_warning_body),
                    summaryMaxLines = 2,
                    onCheckedChange = { enabled ->
                        if (enabled && !state.settings.expert.firewallEnabled) {
                            blockAlwaysWarningVisible = true
                        } else {
                            onBlockAppsAlwaysChanged(enabled)
                        }
                    },
                    grouped = true,
                )
                SettingsControlGroupDivider()
                AppGridSectionContent(
                    title = stringResource(R.string.blocked_app_exceptions),
                    subtitle = stringResource(R.string.blocked_apps_info_body),
                    leadingIcon = Icons.Outlined.Block,
                    apps = blockedApps,
                    emptyText = "",
                    headerActionLabel = stringResource(R.string.choose_label),
                    headerActionTag = "routing_apps_blocked_add_exception_action",
                    onHeaderAction = onOpenBlockedPicker,
                    onRemove = { app ->
                        onBlockedPackagesChanged(blockedPackages.filterNot { it == app.packageName })
                    },
                    onDropPackage = { packageName, beforePackageName ->
                        if (packageName in selectedPackageSet || packageName in blockedPackageSet) {
                            onSelectedPackagesChanged(selectedPackages.filterNot { it == packageName })
                            onBlockedPackagesChanged(insertPackageBefore(blockedPackages, packageName, beforePackageName))
                        }
                    },
                    modifier = Modifier.testTag("routing_apps_blocked_section"),
                )
            }
        }
    }

    if (blockAlwaysWarningVisible) {
        ConfirmDialog(
            title = stringResource(R.string.block_apps_always_warning_title),
            body = stringResource(R.string.block_apps_always_warning_body),
            confirmLabel = stringResource(R.string.security_firewall_enable_action),
            dismissLabel = stringResource(R.string.close),
            icon = ImageVector.vectorResource(R.drawable.ic_firewall_shield_key),
            onDismiss = { blockAlwaysWarningVisible = false },
            onConfirm = {
                blockAlwaysWarningVisible = false
                onFirewallEnabledChanged(true)
                onBlockAppsAlwaysChanged(true)
            },
        )
    }
}

@Composable
internal fun AppGridSectionContent(
    title: String,
    subtitle: String? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    apps: List<InstalledAppOption>,
    emptyText: String,
    onRemove: (InstalledAppOption) -> Unit,
    onDropPackage: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    headerActionLabel: String? = null,
    headerActionTag: String? = null,
    onHeaderAction: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .dragAndDropTarget(
                    shouldStartDragAndDrop = ::isFoxholeTextDragEvent,
                    target =
                        remember(onDropPackage, haptic) {
                            foxholeDropTarget(FOXHOLE_APP_DRAG_PREFIX) { packageName ->
                                onDropPackage(packageName, null)
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            }
                        },
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.padding(4.dp).size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf(String::isNotBlank)?.let { text ->
                    SettingsInfoAnchor(
                        title = title,
                        body = text,
                    )
                }
            }
            if (headerActionLabel != null && onHeaderAction != null) {
                TextButton(
                    onClick = onHeaderAction,
                    modifier = headerActionTag?.let { Modifier.testTag(it) } ?: Modifier,
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = headerActionLabel,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        if (apps.isEmpty()) {
            if (emptyText.isNotBlank()) {
                Text(
                    text = emptyText,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            AppIconGrid(
                apps = apps,
                onDropPackage = onDropPackage,
                onRemove = onRemove,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppIconGrid(
    apps: List<InstalledAppOption>,
    onDropPackage: (String, String?) -> Unit,
    onRemove: (InstalledAppOption) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns =
            when {
                maxWidth < 420.dp -> 3
                maxWidth < 520.dp -> 4
                maxWidth < 640.dp -> 5
                else -> 6
            }
        val rows = ((apps.size + columns - 1) / columns).coerceAtLeast(1)
        val gridHeight = AppGridTileHeight * rows + AppGridGap * (rows - 1)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(gridHeight)
                    .foxholeAnimateContentSize(),
            horizontalArrangement = Arrangement.spacedBy(AppGridGap),
            verticalArrangement = Arrangement.spacedBy(AppGridGap),
            userScrollEnabled = false,
        ) {
            gridItems(apps, key = InstalledAppOption::packageName) { app ->
                AppGridTile(
                    app = app,
                    onDropPackage = { packageName -> onDropPackage(packageName, app.packageName) },
                    onRemove = { onRemove(app) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGridTile(
    app: InstalledAppOption,
    onDropPackage: (String) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dragBitmap = rememberAppIconBitmap(packageName = app.packageName, bitmapSize = 72.dp)
    val dragContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
    val dragFallbackColor = MaterialTheme.colorScheme.primary
    val haptic = LocalHapticFeedback.current
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(AppGridTileHeight)
                .dragAndDropSource(
                    drawDragDecoration = {
                        drawAppDragDecoration(
                            bitmap = dragBitmap,
                            containerColor = dragContainerColor,
                            fallbackColor = dragFallbackColor,
                        )
                    },
                    transferData = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        DragAndDropTransferData(
                            clipData = ClipData.newPlainText(FOXHOLE_APP_DRAG_LABEL, "$FOXHOLE_APP_DRAG_PREFIX${app.packageName}"),
                            localState = app.packageName,
                        )
                    },
                )
                .dragAndDropTarget(
                    shouldStartDragAndDrop = ::isFoxholeTextDragEvent,
                    target =
                        remember(onDropPackage, haptic) {
                            foxholeDropTarget(FOXHOLE_APP_DRAG_PREFIX) { packageName ->
                                onDropPackage(packageName)
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            }
                        },
                )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(modifier = Modifier.size(76.dp)) {
                AppIcon(
                    packageName = app.packageName,
                    modifier = Modifier.align(Alignment.Center).size(68.dp),
                )
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
            Text(
                text = app.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun AppPickerScreen(
    title: String,
    selectionTitle: String,
    selectedPackages: List<String>,
    lockedPackages: Set<String>,
    state: RoutingRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onSelectionChanged: (List<String>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filterMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var appFilter by rememberSaveable { mutableStateOf(InstalledAppFilter.ALL) }
    var draftSelection by rememberSaveable(selectedPackages) {
        mutableStateOf(selectedPackages)
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
        title = title,
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        tag = "routing_apps_picker_screen",
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
                optionIcon = ::installedAppFilterIcon,
            )
        }
        item {
            SettingValueRow(
                title = selectionTitle,
                value = draftSelection.size.toString(),
                onClick = null,
                trailingContent = {
                    SelectionCountBadge(count = draftSelection.size)
                },
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
            val locked = app.packageName in lockedPackages
            val checked = draftSelection.contains(app.packageName) || locked
            SelectableInstalledAppRow(
                app = app,
                checked = checked,
                enabled = !locked,
                onToggle = { value ->
                    if (!locked) {
                        val nextSelection =
                            draftSelection
                                .toMutableSet()
                                .apply {
                                    if (value) {
                                        add(app.packageName)
                                    } else {
                                        remove(app.packageName)
                                    }
                                }.toList()
                        draftSelection = nextSelection
                        onSelectionChanged(nextSelection)
                    }
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
    onSniffChanged: (Boolean) -> Unit,
) {
    var editingRule by remember { mutableStateOf<RoutingRule?>(null) }
    var createDialogVisible by rememberSaveable { mutableStateOf(false) }
    var deleteRuleId by rememberSaveable { mutableStateOf<Long?>(null) }
    var revealedSiteSwipeKey by rememberSaveable { mutableStateOf<String?>(null) }
    val siteRules =
        state.activePreset
            ?.rules
            .orEmpty()
            .filter { rule -> rule.isManagedSimpleSiteRule() && (rule.matchDomains.isNotEmpty() || rule.matchIpCidrs.isNotEmpty()) }

    SettingsScaffold(
        title = stringResource(R.string.routing_sites_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        bannerPlacement = FoxholeBannerPlacement.BOTTOM,
    ) {
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.sniff_traffic),
                    checked = state.settings.expert.sniff,
                    summary = stringResource(R.string.sniff_traffic_summary),
                    leadingIcon = Icons.Outlined.Language,
                    onCheckedChange = onSniffChanged,
                    grouped = true,
                )
            }
        }
        item {
            SiteHeaderCard(
                title = stringResource(R.string.routing_sites_title),
                subtitle = stringResource(R.string.routing_sites_summary),
                actionLabel = stringResource(R.string.add_label),
                actionTag = "routing_sites_add_exception_action",
                onAction = { createDialogVisible = true },
            )
        }
        items(siteRules, key = RoutingRule::id) { rule ->
            val siteSwipeKey = "site-${rule.id}"
            SiteRuleCard(
                rule = rule,
                swipeKey = siteSwipeKey,
                revealed = revealedSiteSwipeKey == siteSwipeKey,
                anyRevealed = revealedSiteSwipeKey != null,
                onRevealChange = { revealed -> revealedSiteSwipeKey = siteSwipeKey.takeIf { revealed } },
                onDismissRevealedSwipe = { revealedSiteSwipeKey = null },
                onEdit = { editingRule = rule },
                onDelete = { deleteRuleId = rule.id },
            )
        }
    }

    if (createDialogVisible) {
        SiteRuleDialog(
            rule = null,
            defaultAction = RoutingRuleAction.PROXY,
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

@Composable
private fun SiteHeaderCard(
    title: String,
    subtitle: String,
    actionLabel: String,
    actionTag: String,
    onAction: () -> Unit,
) {
    FoxholeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onAction,
                modifier = Modifier.testTag(actionTag),
                colors = foxholeDropdownColoredButtonColors(),
                border = foxholeDropdownColoredButtonBorder(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(
                    text = actionLabel,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SiteRuleCard(
    rule: RoutingRule,
    swipeKey: String,
    revealed: Boolean,
    anyRevealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    onDismissRevealedSwipe: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    FoxholeSwipeActions(
        key = swipeKey,
        actions =
            listOf(
                FoxholeSwipeAction(
                    icon = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.edit_label),
                    onClick = onEdit,
                ),
                FoxholeSwipeAction(
                    icon = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.delete_label),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                ),
            ),
        revealed = revealed,
        onRevealChange = onRevealChange,
    ) {
        FoxholeCard(
            onClick = onDismissRevealedSwipe.takeIf { anyRevealed },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = siteActionIcon(rule.action),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = rule.siteRuleTokens().firstOrNull() ?: rule.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = rule.siteRuleTokens().drop(1).joinToString().ifBlank { siteActionLabel(rule.action) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SiteActionBadge(action = rule.action)
            }
        }
    }
}

@Composable
private fun SiteActionBadge(action: RoutingRuleAction) {
    val color =
        when (action) {
            RoutingRuleAction.BLOCK -> MaterialTheme.colorScheme.error
            RoutingRuleAction.PROXY -> FoxholePositiveAccent
            RoutingRuleAction.DIRECT -> MaterialTheme.colorScheme.primary
        }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.34f)),
    ) {
        Text(
            text = action.name,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = color,
            maxLines = 1,
        )
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

internal fun insertPackageBefore(
    packages: List<String>,
    packageName: String,
    beforePackageName: String?,
): List<String> {
    if (packageName == beforePackageName) {
        return packages
    }
    val reordered = packages.filterNot { it == packageName }.toMutableList()
    val insertIndex = beforePackageName?.let { reordered.indexOf(it).takeIf { index -> index >= 0 } } ?: reordered.size
    reordered.add(insertIndex, packageName)
    return reordered
}

private fun DrawScope.drawAppDragDecoration(
    bitmap: ImageBitmap?,
    containerColor: Color,
    fallbackColor: Color,
) {
    val iconSize = 70.dp.toPx()
    val top = 4.dp.toPx()
    if (bitmap == null) {
        drawCircle(
            color = fallbackColor,
            radius = iconSize / 3f,
            center = Offset(size.width / 2f, top + iconSize / 2f),
        )
        return
    }
    val iconLeft = ((size.width - iconSize) / 2f).toInt()
    val iconTop = top.toInt()
    drawImage(
        image = bitmap,
        dstOffset = IntOffset(iconLeft, iconTop),
        dstSize = IntSize(iconSize.toInt(), iconSize.toInt()),
    )
}

private fun isFoxholeTextDragEvent(event: DragAndDropEvent): Boolean =
    event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN)

private fun foxholeDropTarget(
    prefix: String,
    onDropPayload: (String) -> Unit,
): DragAndDropTarget =
    object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val rawPayload = event.dragPayload()
            if (!rawPayload.startsWith(prefix)) {
                return false
            }
            val payload = rawPayload.removePrefix(prefix).takeIf(String::isNotBlank) ?: return false
            onDropPayload(payload)
            return true
        }
    }

private fun DragAndDropEvent.dragPayload(): String =
    toAndroidDragEvent().clipData?.getItemAt(0)?.text?.toString().orEmpty()

private const val FOXHOLE_APP_DRAG_PREFIX = "foxhole-app:"
private const val FOXHOLE_APP_DRAG_LABEL = "FoxHole app"
private val AppGridTileHeight = 120.dp
private val AppGridGap = 8.dp
private const val MANAGED_SELECTED_SITE_RULE_PREFIX = "FoxHole selected site:"
private const val MANAGED_BLOCKED_SITE_RULE_PREFIX = "FoxHole blocked site:"

@Composable
private fun SelectionCountBadge(count: Int) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = LocalFoxholeUiPalette.current.valuePillContainerColor,
        contentColor = LocalFoxholeUiPalette.current.valuePillContentColor,
        border = foxholeDropdownColoredButtonBorder(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun foxholeDropdownColoredButtonColors() =
    ButtonDefaults.buttonColors(
        containerColor = LocalFoxholeUiPalette.current.valuePillContainerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )

@Composable
private fun foxholeDropdownColoredButtonBorder(): BorderStroke? {
    val borderColor = LocalFoxholeUiPalette.current.valuePillBorderColor
    return borderColor.takeUnless { it == Color.Transparent }?.let { BorderStroke(1.dp, it) }
}
