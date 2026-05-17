@file:Suppress("ImportOrdering")

package com.foxhole.beta.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.beta.R
import com.foxhole.beta.core.anomaly.UsageStatsAccess
import com.foxhole.beta.core.model.AnomalyEvent
import com.foxhole.beta.core.model.AnomalySeverity
import com.foxhole.beta.core.model.AnomalyType
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.DnsSettings
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.InstalledAppInventoryChange
import com.foxhole.beta.core.model.NetworkActivityEvent
import com.foxhole.beta.core.model.OverallStatisticsUiItem
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileComparisonSideUiItem
import com.foxhole.beta.core.model.ProfileComparisonUiItem
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileTrafficUiItem
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProtocolStatisticsUiItem
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.model.StatisticsRange
import com.foxhole.beta.core.model.StatisticsMetric
import com.foxhole.beta.core.model.StatisticsRefreshInterval
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.StatisticsUiState
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapUiState
import com.foxhole.beta.core.model.TransportProtocol
import com.foxhole.beta.core.model.TransportStatisticsUiItem
import com.foxhole.beta.core.model.TrafficWindow
import com.foxhole.beta.core.statistics.ChartColorToken
import com.foxhole.beta.core.traffic.TorGeoIpCountryResolver
import com.foxhole.beta.ui.statistics.charts.chartColor
import com.foxhole.beta.ui.statistics.charts.chartCountryColors
import com.foxhole.beta.ui.statistics.charts.chartVisualTokens
import com.foxhole.beta.ui.theme.LocalFoxholeSemanticColors
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "UnusedParameter")
fun StatisticsScreen(
    state: SettingsRouteUiState,
    trafficMapState: TrafficMapUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onStatisticsEnabledChanged: (Boolean) -> Unit,
    onStatisticsRetentionSelected: (StatisticsRetention) -> Unit,
    onStatisticsRefreshIntervalSelected: (StatisticsRefreshInterval) -> Unit,
    onStatisticsMetricEnabledChanged: (StatisticsMetric, Boolean) -> Unit,
    onAppTrafficStatsEnabledChanged: (Boolean) -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onClearUsage: () -> Unit,
) {
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var retentionMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var refreshIntervalMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var allAppsVisible by rememberSaveable { mutableStateOf(false) }
    var allCountriesVisible by rememberSaveable { mutableStateOf(false) }
    var allAnomaliesVisible by rememberSaveable { mutableStateOf(false) }
    var selectedApp by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var clearConfirmVisible by rememberSaveable { mutableStateOf(false) }
    var appStatsEnablePendingUsageAccess by rememberSaveable { mutableStateOf(false) }
    var appTrafficRange by rememberSaveable { mutableStateOf(StatisticsDisplayRange.HOURS_24) }
    var dnsRange by rememberSaveable { mutableStateOf(StatisticsDisplayRange.HOURS_24) }
    var anomalyRange by rememberSaveable { mutableStateOf(StatisticsDisplayRange.HOURS_24) }
    val statisticsSettings = state.settings.statistics
    val context = LocalContext.current
    val retention = state.settings.statistics.retention
    val statistics = state.statisticsDashboard.statistics
    val appRows = state.statisticsDashboard.appRows
    val topApps = appRows.take(APP_TRAFFIC_CHART_LIMIT)
    val countryRows = state.statisticsDashboard.countryRows
    val topCountryLimit =
        if (countryRows.size > STATISTICS_TOP_PREVIEW_LIMIT) {
            STATISTICS_TOP_PREVIEW_LIMIT + 1
        } else {
            STATISTICS_TOP_PREVIEW_LIMIT
        }
    val topCountryRows = countryRows.take(topCountryLimit)
    val dnsSummary =
        dnsProtectionSummary(
            trafficWindows = state.trafficWindows,
            appRows = appRows,
            retention = dnsRange.toStatisticsRetention(),
            displayRange = dnsRange,
            dnsSettings = state.settings.dns,
        )
    val anomalyEventsForRange = remember(state.anomalyEvents, anomalyRange) {
        state.anomalyEvents.filterForDisplayRange(anomalyRange, AnomalyEvent::createdAtMs)
    }
    val appSamplesForRange = remember(state.appTrafficWindows, appTrafficRange) {
        state.appTrafficWindows.filterForDisplayRange(appTrafficRange, AppTrafficWindow::startedAtMs)
    }
    val appStatsSwitchChecked = state.settings.appTrafficStatsEnabled
    val usageAccessGranted = rememberUsageAccessGranted()
    LaunchedEffect(usageAccessGranted, appStatsEnablePendingUsageAccess) {
        if (usageAccessGranted && appStatsEnablePendingUsageAccess) {
            appStatsEnablePendingUsageAccess = false
            onAppTrafficStatsEnabledChanged(true)
        }
    }
    val firewallEnabled = state.settings.expert.firewallEnabled
    val dnsFilteringAvailable = state.settings.dns.adGuardFilteringEnabled()
    val appStatsEnabled =
        statisticsSettings.enabled &&
            statisticsSettings.appTrafficEnabled &&
            appStatsSwitchChecked &&
            usageAccessGranted
    val selectedAppRow = selectedApp?.let { packageName -> appRows.firstOrNull { row -> row.packageName == packageName } }
    val selectedProfile =
        selectedProfileId?.let { profileId ->
            statistics.profileTraffic.firstOrNull { item -> item.profileId == profileId }
        }

    SettingsScaffold(
        title = stringResource(R.string.statistics_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        actions = {
            IconButton(onClick = { settingsVisible = true }) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.statistics_settings_content_description),
                )
            }
        },
    ) {
        if (!statisticsSettings.enabled) {
            item(key = "statistics-disabled") {
                StatisticsDisabledState(onEnable = { onStatisticsEnabledChanged(true) })
            }
        } else {
            if (statisticsSettings.profileTrafficEnabled) {
                item(key = "profile-traffic") {
                    ProfileTrafficOverviewCard(
                        statistics = statistics,
                        state = state,
                        onClear = { clearConfirmVisible = true },
                        onProfileClick = { profileId -> selectedProfileId = profileId },
                    )
                }
            }
            if (statisticsSettings.vpnProtocolsEnabled && statistics.vpnProtocols.isNotEmpty()) {
                item(key = "protocols") {
                    ProtocolStatisticsSection(items = statistics.vpnProtocols)
                }
            }
            if (statisticsSettings.profileComparisonsEnabled && statistics.profileComparisons.isNotEmpty()) {
                item(key = "profile-comparisons") {
                    ProfileComparisonsSection(items = statistics.profileComparisons)
                }
            }
            if (statisticsSettings.transportsEnabled && statistics.transports.isNotEmpty()) {
                item(key = "transports") {
                    TransportStatisticsSection(items = statistics.transports)
                }
            }
            if (statisticsSettings.dnsFilteringEnabled && dnsFilteringAvailable) {
                item(key = "dns-protection") {
                    DnsProtectionCard(
                        summary = dnsSummary,
                        range = dnsRange,
                        onRangeSelected = { dnsRange = it },
                    )
                }
            }
            if (statisticsSettings.anomalyMetricsEnabled && anomalyEventsForRange.isNotEmpty()) {
                item(key = "anomalies") {
                    AnomalyStatisticsCard(
                        events = anomalyEventsForRange,
                        totalEventsCount = state.anomalyEvents.size,
                        installedApps = state.installedApps,
                        range = anomalyRange,
                        onRangeSelected = { anomalyRange = it },
                        onShowAllEvents = { allAnomaliesVisible = true },
                    )
                }
            }
            if (statisticsSettings.appTrafficEnabled && appStatsSwitchChecked) {
                item(key = "app-statistics") {
                    AppTrafficStatisticsCard(
                        rows = topApps,
                        samples = appSamplesForRange,
                        allRowsCount = appRows.size,
                        enabled = appStatsEnabled,
                        usageAccessGranted = usageAccessGranted,
                        range = appTrafficRange,
                        onRangeSelected = { appTrafficRange = it },
                        onOpenUsageAccess = { openUsageAccessSettings(context) },
                        onShowAll = { allAppsVisible = true },
                        onRowClick = { row -> selectedApp = row.packageName },
                    )
                }
            }
            if (statisticsSettings.countryTrafficEnabled && firewallEnabled && topCountryRows.isNotEmpty()) {
                item(key = "country-traffic") {
                    CountryTrafficCard(
                        state = trafficMapState,
                        rows = topCountryRows,
                        totalRowsCount = countryRows.size,
                        enabled = state.settings.statistics.countryTrafficEnabled && firewallEnabled,
                        onShowAll = { allCountriesVisible = true },
                    )
                }
            }
        }
    }

    if (settingsVisible) {
        AlertDialog(
            onDismissRequest = { settingsVisible = false },
            title = { Text(stringResource(R.string.statistics_settings_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SettingsControlGroup {
                        SettingSwitchRow(
                            title = stringResource(R.string.statistics_enabled_title),
                            checked = statisticsSettings.enabled,
                            summary = stringResource(R.string.statistics_enabled_summary),
                            leadingIcon = Icons.Outlined.BarChart,
                            onCheckedChange = onStatisticsEnabledChanged,
                            grouped = true,
                        )
                        SettingsControlGroupDivider()
                        DropdownSettingRow(
                            title = stringResource(R.string.statistics_retention_title),
                            value = statisticsRetentionLabel(state.settings.statistics.retention),
                            expanded = retentionMenuExpanded,
                            onExpandedChange = { retentionMenuExpanded = it },
                            values = StatisticsRetention.entries,
                            selected = state.settings.statistics.retention,
                            label = { statisticsRetentionLabel(it) },
                            onSelect = onStatisticsRetentionSelected,
                            leadingIcon = Icons.Outlined.Storage,
                            grouped = true,
                        )
                        SettingsControlGroupDivider()
                        DropdownSettingRow(
                            title = stringResource(R.string.statistics_refresh_interval_title),
                            value = statisticsRefreshIntervalLabel(state.settings.statistics.refreshInterval),
                            expanded = refreshIntervalMenuExpanded,
                            onExpandedChange = { refreshIntervalMenuExpanded = it },
                            values = StatisticsRefreshInterval.entries,
                            selected = state.settings.statistics.refreshInterval,
                            label = { statisticsRefreshIntervalLabel(it) },
                            onSelect = onStatisticsRefreshIntervalSelected,
                            summary = stringResource(R.string.statistics_refresh_interval_summary),
                            leadingIcon = Icons.Outlined.BarChart,
                            grouped = true,
                        )
                    }
                    SettingsControlGroup {
                        SettingSwitchRow(
                            title = stringResource(R.string.app_statistics_enabled_title),
                            checked = appStatsSwitchChecked,
                            summary = stringResource(R.string.app_statistics_enabled_summary),
                            leadingIcon = Icons.Outlined.Apps,
                            onCheckedChange = { enabled ->
                                if (enabled && !usageAccessGranted) {
                                    appStatsEnablePendingUsageAccess = true
                                    openUsageAccessSettings(context)
                                } else {
                                    appStatsEnablePendingUsageAccess = false
                                    onAppTrafficStatsEnabledChanged(enabled)
                                }
                            },
                            enabled = statisticsSettings.enabled,
                            grouped = true,
                        )
                    }
                    SettingsControlGroup {
                        StatisticsMetricSwitch(
                            metric = StatisticsMetric.PROFILE_TRAFFIC,
                            checked = statisticsSettings.profileTrafficEnabled,
                            title = stringResource(R.string.statistics_metric_profiles),
                            enabled = statisticsSettings.enabled,
                            onMetricChanged = onStatisticsMetricEnabledChanged,
                        )
                        SettingsControlGroupDivider()
                        StatisticsMetricSwitch(
                            metric = StatisticsMetric.VPN_PROTOCOLS,
                            checked = statisticsSettings.vpnProtocolsEnabled,
                            title = stringResource(R.string.statistics_metric_protocols),
                            enabled = statisticsSettings.enabled,
                            onMetricChanged = onStatisticsMetricEnabledChanged,
                        )
                        SettingsControlGroupDivider()
                        StatisticsMetricSwitch(
                            metric = StatisticsMetric.PROFILE_COMPARISONS,
                            checked = statisticsSettings.profileComparisonsEnabled,
                            title = stringResource(R.string.statistics_metric_comparisons),
                            enabled = statisticsSettings.enabled,
                            onMetricChanged = onStatisticsMetricEnabledChanged,
                        )
                        SettingsControlGroupDivider()
                        StatisticsMetricSwitch(
                            metric = StatisticsMetric.TRANSPORTS,
                            checked = statisticsSettings.transportsEnabled,
                            title = stringResource(R.string.statistics_metric_transports),
                            enabled = statisticsSettings.enabled,
                            onMetricChanged = onStatisticsMetricEnabledChanged,
                        )
                        SettingsControlGroupDivider()
                        StatisticsMetricSwitch(
                            metric = StatisticsMetric.APP_TRAFFIC,
                            checked = statisticsSettings.appTrafficEnabled,
                            title = stringResource(R.string.statistics_metric_apps),
                            enabled = statisticsSettings.enabled,
                            onMetricChanged = { metric, enabled ->
                                if (enabled && appStatsSwitchChecked && !usageAccessGranted) {
                                    appStatsEnablePendingUsageAccess = true
                                    openUsageAccessSettings(context)
                                }
                                onStatisticsMetricEnabledChanged(metric, enabled)
                            },
                        )
                        SettingsControlGroupDivider()
                        StatisticsMetricSwitch(
                            metric = StatisticsMetric.DNS_FILTERING,
                            checked = statisticsSettings.dnsFilteringEnabled,
                            title = stringResource(R.string.statistics_metric_dns_filtering),
                            summary =
                            if (dnsFilteringAvailable) {
                                null
                            } else {
                                stringResource(R.string.statistics_metric_dns_filtering_summary)
                            },
                            enabled = statisticsSettings.enabled && dnsFilteringAvailable,
                            onMetricChanged = onStatisticsMetricEnabledChanged,
                        )
                        SettingsControlGroupDivider()
                        StatisticsMetricSwitch(
                            metric = StatisticsMetric.COUNTRY_TRAFFIC,
                            checked = statisticsSettings.countryTrafficEnabled,
                            title = stringResource(R.string.statistics_metric_countries),
                            summary =
                            if (state.settings.expert.firewallEnabled) {
                                null
                            } else {
                                stringResource(R.string.statistics_metric_countries_firewall_summary)
                            },
                            enabled = statisticsSettings.enabled && state.settings.expert.firewallEnabled,
                            onMetricChanged = onStatisticsMetricEnabledChanged,
                        )
                        SettingsControlGroupDivider()
                        StatisticsMetricSwitch(
                            metric = StatisticsMetric.ANOMALIES,
                            checked = statisticsSettings.anomalyMetricsEnabled,
                            title = stringResource(R.string.statistics_metric_anomalies),
                            enabled = statisticsSettings.enabled,
                            onMetricChanged = onStatisticsMetricEnabledChanged,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                FoxholeDialogDismissButton(onClick = { settingsVisible = false })
            },
        )
    }

    if (allCountriesVisible) {
        AlertDialog(
            onDismissRequest = { allCountriesVisible = false },
            title = { Text(stringResource(R.string.statistics_country_all_title)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    items(countryRows, key = CountryTrafficUiRow::countryCode) { row ->
                        CountryTrafficRow(row = row)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                FoxholeDialogDismissButton(onClick = { allCountriesVisible = false })
            },
        )
    }

    if (selectedProfile != null) {
        AlertDialog(
            onDismissRequest = { selectedProfileId = null },
            title = { Text(selectedProfile.profileName) },
            text = {
                ProfileStatisticsDetail(
                    state = state,
                    statistics = statistics,
                    item = selectedProfile,
                )
            },
            confirmButton = {},
            dismissButton = {
                FoxholeDialogDismissButton(onClick = { selectedProfileId = null })
            },
        )
    }

    if (selectedAppRow != null) {
        AlertDialog(
            onDismissRequest = { selectedApp = null },
            title = { Text(selectedAppRow.label) },
            text = {
                AppTrafficDetail(
                    row = selectedAppRow,
                    samples = state.appTrafficWindows,
                    networkActivityEvents = state.networkActivityEvents,
                    ipInfo = state.ipInfo,
                )
            },
            confirmButton = {},
            dismissButton = {
                FoxholeDialogDismissButton(onClick = { selectedApp = null })
            },
        )
    }

    if (allAppsVisible) {
        AlertDialog(
            onDismissRequest = { allAppsVisible = false },
            title = { Text(stringResource(R.string.app_statistics_all_title)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    items(appRows, key = AppTrafficRow::packageName) { row ->
                        AppTrafficRowView(
                            row = row,
                            onClick = {
                                allAppsVisible = false
                                selectedApp = row.packageName
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                FoxholeDialogDismissButton(onClick = { allAppsVisible = false })
            },
        )
    }

    if (allAnomaliesVisible) {
        AlertDialog(
            onDismissRequest = { allAnomaliesVisible = false },
            title = { Text(stringResource(R.string.statistics_anomaly_all_events_title)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    items(
                        anomalyEventsForRange,
                        key = { event -> "${event.createdAtMs}:${event.type}:${event.packageName}" },
                    ) { event ->
                        AnomalyEventRow(event = event, installedApps = state.installedApps)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                FoxholeDialogDismissButton(onClick = { allAnomaliesVisible = false })
            },
        )
    }

    if (clearConfirmVisible) {
        ConfirmDialog(
            title = stringResource(R.string.clear_usage_confirm_title),
            body = stringResource(R.string.clear_usage_confirm_body),
            confirmLabel = stringResource(R.string.clear_usage_title),
            icon = Icons.Outlined.DeleteSweep,
            onDismiss = { clearConfirmVisible = false },
            onConfirm = {
                clearConfirmVisible = false
                onClearUsage()
            },
        )
    }
}

@Composable
private fun ProfileTrafficOverviewCard(
    statistics: StatisticsUiState,
    state: SettingsRouteUiState,
    onClear: () -> Unit,
    onProfileClick: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.statistics_profile_traffic_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = stringResource(R.string.clear_statistics_history_content_description),
                    )
                }
            }
            ProfileTrafficList(
                items = statistics.profileTraffic,
                state = state,
                onProfileClick = onProfileClick,
            )
        }
    }
}

@Composable
private fun ProfileTrafficList(
    items: List<ProfileTrafficUiItem>,
    state: SettingsRouteUiState,
    onProfileClick: (Long) -> Unit,
) {
    if (items.isEmpty()) {
        Text(
            text = stringResource(R.string.diagnostics_usage_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items
            .sortedByDescending(ProfileTrafficUiItem::totalBytes)
            .take(PROFILE_TRAFFIC_PREVIEW_LIMIT)
            .forEachIndexed { index, item ->
                val detail = profileStatisticsDetail(state, item)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onProfileClick(item.profileId) }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.profileName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.statistics_profile_subtitle,
                                protocolDisplayName(detail.lastProtocolHint),
                                detail.avgLatencyMs.formatLatency(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(
                                R.string.statistics_profile_rx_tx,
                                formatBytes(context, item.rxBytes),
                                formatBytes(context, item.txBytes),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                        Text(
                            text = formatBytes(context, item.totalBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End,
                        )
                    }
                }
                if (index != minOf(items.size, PROFILE_TRAFFIC_PREVIEW_LIMIT) - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
                }
            }
    }
}

@Composable
@Suppress("UnusedParameter")
private fun CountryTrafficCard(
    state: TrafficMapUiState,
    rows: List<CountryTrafficUiRow>,
    totalRowsCount: Int,
    enabled: Boolean,
    onShowAll: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(
                icon = Icons.Outlined.Public,
                title = stringResource(R.string.statistics_country_traffic_top_title),
            )
            if (!enabled) {
                Text(
                    text = stringResource(R.string.traffic_map_live_requires_firewall),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (rows.isEmpty()) {
                EmptySectionText(text = stringResource(R.string.traffic_map_waiting_connections))
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    CountryVerticalBarChart(
                        points = rows,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    CountryTrafficList(
                        points = rows,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                if (totalRowsCount > rows.size) {
                    TextButton(onClick = onShowAll) {
                        Text(stringResource(R.string.show_all_label))
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryVerticalBarChart(
    points: List<CountryTrafficUiRow>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = statisticsCountryChartColors()
    val tokens = chartVisualTokens()
    val maxBytes = points.maxOfOrNull(CountryTrafficUiRow::bytes)?.coerceAtLeast(1L) ?: 1L
    val chartDescription =
        listOf(
            stringResource(R.string.statistics_country_traffic_title),
            points
                .map { point -> "${point.label} ${formatBytes(context, point.bytes)}" }
                .joinToString(),
        ).filter { value -> value.isNotBlank() }.joinToString(". ")
    val visible = rememberOneShotVisible("countries")
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = DONUT_ANIMATION_DURATION_MS, easing = FastOutSlowInEasing),
        label = "country-bars-progress",
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.statistics_country_traffic_legend, formatBytes(context, maxBytes)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics { contentDescription = chartDescription },
        ) {
            val chartTop = 8.dp.toPx()
            val chartBottom = size.height - 4.dp.toPx()
            val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)
            drawLine(
                color = tokens.gridColor,
                start = Offset(0f, chartTop),
                end = Offset(size.width, chartTop),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            val slotWidth = size.width / points.size.coerceAtLeast(1).toFloat()
            val barWidth = (slotWidth * 0.48f).coerceAtMost(18.dp.toPx())
            val radius = CornerRadius(tokens.barCornerRadius.toPx(), tokens.barCornerRadius.toPx())
            points.forEachIndexed { index, point ->
                val normalized = point.bytes.toFloat() / maxBytes.toFloat()
                val barHeight = (chartHeight * normalized * progress).coerceAtLeast(if (point.bytes > 0L) 2f else 0f)
                val center = slotWidth * index + slotWidth / 2f
                drawRoundRect(
                    color = tokens.trackColor,
                    topLeft = Offset(center - barWidth / 2f, chartTop),
                    size = Size(barWidth, chartHeight),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    color = colors[index % colors.size],
                    topLeft = Offset(center - barWidth / 2f, chartBottom - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = radius,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            points.forEach { point ->
                Text(
                    text = countryEmoji(point.countryCode),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CountryTrafficRow(row: CountryTrafficUiRow) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = countryEmoji(row.countryCode),
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.statistics_country_connections, row.sessions),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatBytes(context, row.bytes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun StatisticsDisabledState(onEnable: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 420.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BarChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
            Text(
                text = stringResource(R.string.statistics_disabled_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onEnable) {
                Text(stringResource(R.string.statistics_enable_action))
            }
        }
    }
}

@Composable
private fun CountryTrafficList(
    points: List<CountryTrafficUiRow>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = statisticsCountryChartColors()
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        points.forEachIndexed { index, point ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(modifier = Modifier.size(9.dp).clearAndSetSemantics {}) {
                    drawCircle(color = colors[index % colors.size])
                }
                Text(
                    text = "${countryEmoji(point.countryCode)} ${point.label}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatBytes(context, point.bytes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun ProtocolStatisticsSection(items: List<ProtocolStatisticsUiItem>) {
    StatisticsSectionCard(
        icon = Icons.Outlined.Route,
        title = stringResource(R.string.statistics_protocols_title),
    ) {
        if (items.isEmpty()) {
            EmptySectionText(text = stringResource(R.string.statistics_protocols_empty))
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= COMPACT_PROTOCOL_GRID_WIDTH) 4 else 2
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.chunked(columns).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowItems.forEach { item ->
                                ProtocolStatCard(item = item, modifier = Modifier.weight(1f))
                            }
                            repeat(columns - rowItems.size) {
                                Box(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtocolStatCard(
    item: ProtocolStatisticsUiItem,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val visible = rememberOneShotVisible("protocol:${item.protocol.name}")
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = protocolDisplayName(item.protocol),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 9.sp),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier.padding(top = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedDonutChart(
                    successRate = item.successRate,
                    errorRate = item.errorRate,
                    visible = visible,
                    contentDescription =
                    stringResource(
                        R.string.statistics_profile_protocol_metrics,
                        formatPercent(item.successRate),
                        formatPercent(item.errorRate),
                        formatBytes(context, item.totalBytes),
                    ),
                    modifier = Modifier.size(58.dp),
                )
                Text(
                    text = formatPercent(item.successRate),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.statistics_errors_percent, formatPercent(item.errorRate)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text =
                stringResource(
                    R.string.statistics_protocol_footer,
                    formatBytes(context, item.totalBytes),
                    item.totalAttempts,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AnimatedDonutChart(
    successRate: Float,
    errorRate: Float,
    visible: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val tokens = chartVisualTokens()
    val successColor = chartColor(ChartColorToken.SUCCESS)
    val errorColor = chartColor(ChartColorToken.ERROR)
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = DONUT_ANIMATION_DURATION_MS, easing = FastOutSlowInEasing),
        label = "donut-progress",
    )
    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        val stroke = Stroke(width = tokens.ringStrokeWidth.toPx(), cap = StrokeCap.Round)
        val successSweep = 360f * successRate.coerceIn(0f, 1f) * progress
        val errorSweep = 360f * errorRate.coerceIn(0f, 1f) * progress
        drawArc(
            color = tokens.ringTrackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
        )
        drawArc(
            color = successColor,
            startAngle = -90f,
            sweepAngle = successSweep,
            useCenter = false,
            style = stroke,
        )
        drawArc(
            color = errorColor,
            startAngle = -90f + successSweep,
            sweepAngle = errorSweep,
            useCenter = false,
            style = stroke,
        )
    }
}

@Composable
private fun ProfileComparisonsSection(items: List<ProfileComparisonUiItem>) {
    StatisticsSectionCard(
        icon = Icons.Outlined.SettingsEthernet,
        title = stringResource(R.string.statistics_profile_comparison_title),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                ProfileComparisonCard(item = item)
            }
        }
    }
}

@Composable
private fun ProfileComparisonCard(item: ProfileComparisonUiItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = protocolDisplayName(item.protocol),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComparisonSide(
                    side = item.left,
                    modifier = Modifier.weight(1f),
                    stable = item.left.stability >= item.right.stability,
                    problematic = item.left.errorRate > item.right.errorRate,
                )
                HorizontalDivider(modifier = Modifier.width(30.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ComparisonSide(
                    side = item.right,
                    modifier = Modifier.weight(1f),
                    stable = item.right.stability > item.left.stability,
                    problematic = item.right.errorRate >= item.left.errorRate,
                )
            }
        }
    }
}

@Composable
private fun ComparisonSide(
    side: ProfileComparisonSideUiItem,
    stable: Boolean,
    problematic: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = side.profileName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                if (stable) {
                    stringResource(R.string.statistics_more_stable)
                } else if (problematic) {
                    stringResource(R.string.statistics_more_errors)
                } else {
                    stringResource(R.string.statistics_even)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text =
                stringResource(
                    R.string.statistics_comparison_metrics,
                    formatPercent(side.stability),
                    formatPercent(side.errorRate),
                    side.totalAttempts,
                    side.avgLatencyMs.formatLatency(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransportStatisticsSection(items: List<TransportStatisticsUiItem>) {
    StatisticsSectionCard(
        icon = Icons.Outlined.SettingsEthernet,
        title = stringResource(R.string.statistics_transports_title),
    ) {
        if (items.isEmpty()) {
            EmptySectionText(text = stringResource(R.string.statistics_transports_empty))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    TransportRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun TransportRow(item: TransportStatisticsUiItem) {
    val context = LocalContext.current
    val tokens = chartVisualTokens()
    val successColor = chartColor(ChartColorToken.SUCCESS)
    val errorColor = chartColor(ChartColorToken.ERROR)
    val barDescription =
        "${transportLabel(item.transport)} ${
            stringResource(
                R.string.statistics_profile_protocol_metrics,
                formatPercent(item.successRate),
                formatPercent(item.errorRate),
                formatBytes(context, item.totalBytes),
            )
        }"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = transportLabel(item.transport),
            modifier = Modifier.widthIn(min = 74.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .semantics { contentDescription = barDescription },
        ) {
            val radius = CornerRadius(tokens.barCornerRadius.toPx(), tokens.barCornerRadius.toPx())
            drawRoundRect(
                color = tokens.trackColor,
                cornerRadius = radius,
            )
            drawRoundRect(
                color = successColor,
                size = Size(size.width * item.successRate.coerceIn(0f, 1f), size.height),
                cornerRadius = radius,
            )
            if (item.errorRate > 0f) {
                val errorWidth = size.width * item.errorRate.coerceIn(0f, 1f)
                drawRoundRect(
                    color = errorColor,
                    topLeft = Offset(size.width - errorWidth, 0f),
                    size = Size(errorWidth, size.height),
                    cornerRadius = radius,
                )
            }
        }
        Text(
            text = formatBytes(context, item.totalBytes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun DnsProtectionCard(
    summary: DnsProtectionSummary,
    range: StatisticsDisplayRange,
    onRangeSelected: (StatisticsDisplayRange) -> Unit,
) {
    val context = LocalContext.current
    var rangeExpanded by rememberSaveable { mutableStateOf(false) }
    StatisticsSectionCard(
        icon = Icons.Outlined.Public,
        title = stringResource(R.string.statistics_dns_filtering_title),
        trailing = {
            StatisticsRangePillDropdown(
                value = range,
                expanded = rangeExpanded,
                onExpandedChange = { rangeExpanded = it },
                onSelect = onRangeSelected,
            )
        },
    ) {
        Text(
            text = stringResource(R.string.statistics_dns_real_summary),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DetailMetricGrid(
            metrics =
            listOfNotNull(
                metricIfPositive(
                    stringResource(R.string.statistics_dns_blocked_queries),
                    summary.blockedQueries,
                ),
                metricIfPositive(
                    stringResource(R.string.statistics_dns_allowed_queries),
                    summary.allowedQueries,
                ),
                stringResource(R.string.statistics_dns_block_ratio) to formatPercent(summary.blockRatio),
            ),
        )
        if (summary.categoryRows.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_dns_categories_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.statistics_dns_categories_estimated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DnsCategoryDonutChart(summary = summary)
            DnsCategoryTable(rows = summary.categoryRows)
        }
        if (summary.appRows.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_dns_apps_estimated),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            DnsProtectionChart(rows = summary.appRows.take(STATISTICS_TOP_PREVIEW_LIMIT))
            Text(
                text = stringResource(R.string.statistics_dns_app_drop_legend),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                summary.appRows.take(STATISTICS_TOP_PREVIEW_LIMIT).forEach { row ->
                    DnsProtectionAppRowView(row = row, totalBytesText = formatBytes(context, row.totalBytes))
                }
            }
        }
    }
}

@Composable
private fun DnsProtectionChart(rows: List<DnsProtectionAppRow>) {
    val maxBlocked = rows.maxOfOrNull(DnsProtectionAppRow::estimatedBlockedQueries)?.coerceAtLeast(1) ?: 1
    val categoryColors = dnsCategoryColorMap()
    val tokens = chartVisualTokens()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { index, row ->
            val barDescription =
                "${row.label}: ${row.estimatedBlockedQueries} ${
                    stringResource(R.string.statistics_dns_blocked_queries)
                }, ${formatPercent(row.blockRatio)}"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (index + 1).toString(),
                    modifier = Modifier.width(20.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
                AppIcon(packageName = row.packageName, modifier = Modifier.size(26.dp))
                Text(
                    text = row.label,
                    modifier = Modifier.widthIn(min = 70.dp, max = 112.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Canvas(
                    modifier =
                    Modifier
                        .weight(1f)
                        .height(14.dp)
                        .semantics { contentDescription = barDescription },
                ) {
                    val radius = CornerRadius(tokens.barCornerRadius.toPx(), tokens.barCornerRadius.toPx())
                    drawRoundRect(color = tokens.trackColor, size = size, cornerRadius = radius)
                    val scaledWidth =
                        size.width *
                            (row.estimatedBlockedQueries.toFloat() / maxBlocked.toFloat()).coerceIn(0f, 1f)
                    var left = 0f
                    row.categoryRatios.entries.forEach { (category, ratio) ->
                        val categoryShareOfBlocked =
                            if (row.blockRatio > 0f) {
                                (ratio / row.blockRatio).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        val width =
                            (scaledWidth * categoryShareOfBlocked)
                                .coerceAtLeast(if (row.estimatedBlockedQueries > 0) 1.5f else 0f)
                                .coerceAtMost((scaledWidth - left).coerceAtLeast(0f))
                        if (width > 0f) {
                            drawRoundRect(
                                color = categoryColors.getValue(category),
                                topLeft = Offset(left, 0f),
                                size = Size(width, size.height),
                                cornerRadius = radius,
                            )
                            left += width
                        }
                    }
                }
                Text(
                    text = row.estimatedBlockedQueries.toString(),
                    modifier = Modifier.widthIn(min = 34.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun DnsCategoryDonutChart(summary: DnsProtectionSummary) {
    val visible = rememberOneShotVisible("dns-categories")
    val categoryColors = dnsCategoryColorMap()
    val tokens = chartVisualTokens()
    val categoryDescriptions =
        summary.categoryRows.map { row ->
            "${stringResource(dnsCategoryLabel(row.category))} ${row.blockedQueries}"
        }
    val donutDescription =
        listOf(
            stringResource(R.string.statistics_dns_categories_title),
            categoryDescriptions.joinToString(),
        ).filter { value -> value.isNotBlank() }.joinToString(". ")
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = DONUT_ANIMATION_DURATION_MS, easing = FastOutSlowInEasing),
        label = "dns-category-donut",
    )
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(
                    modifier = Modifier
                        .size(118.dp)
                        .semantics { contentDescription = donutDescription },
                ) {
                    val total = summary.categoryRows.sumOf(DnsProtectionCategoryRow::blockedQueries).coerceAtLeast(1)
                    val stroke = Stroke(width = tokens.ringStrokeWidth.toPx(), cap = StrokeCap.Round)
                    var start = -90f
                    drawArc(
                        color = tokens.ringTrackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = stroke,
                    )
                    summary.categoryRows.forEach { row ->
                        val sweep = 360f * (row.blockedQueries.toFloat() / total.toFloat()) * progress
                        drawArc(
                            color = categoryColors.getValue(row.category),
                            startAngle = start,
                            sweepAngle = sweep,
                            useCenter = false,
                            style = stroke,
                        )
                        start += sweep
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatPercent(summary.blockRatio),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = summary.blockedQueries.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                summary.categoryRows.forEach { row ->
                    LegendItem(
                        color = dnsCategoryColor(row.category),
                        text = "${stringResource(dnsCategoryLabel(row.category))}: ${row.blockedQueries}",
                    )
                }
            }
        }
    }
}

@Composable
private fun DnsCategoryTable(rows: List<DnsProtectionCategoryRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendItem(
                    color = dnsCategoryColor(row.category),
                    text = stringResource(dnsCategoryLabel(row.category)),
                )
                Text(
                    text = row.blockedQueries.toString(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }
            if (index != rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
            }
        }
    }
}

@Composable
private fun DnsProtectionAppRowView(
    row: DnsProtectionAppRow,
    totalBytesText: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = row.packageName, modifier = Modifier.size(34.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DnsAppDropStack(row)
            Text(
                text = row.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatPercent(row.blockRatio),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                stringResource(
                    R.string.statistics_dns_blocked_app_value,
                    row.estimatedBlockedQueries,
                    totalBytesText,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun DnsAppDropStack(row: DnsProtectionAppRow) {
    val tokens = chartVisualTokens()
    val categoryColors = dnsCategoryColorMap()
    val description =
        "${row.label}: ${formatPercent(row.blockRatio)} ${
            stringResource(R.string.statistics_dns_block_ratio)
        }"
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.dp)
            .semantics { contentDescription = description },
    ) {
        val radius = CornerRadius(tokens.barCornerRadius.toPx(), tokens.barCornerRadius.toPx())
        drawRoundRect(color = tokens.trackColor, cornerRadius = radius)
        var left = 0f
        row.categoryRatios.forEach { (category, ratio) ->
            val width = size.width * ratio.coerceIn(0f, 1f)
            if (width > 0f) {
                drawRoundRect(
                    color = categoryColors.getValue(category),
                    topLeft = Offset(left, 0f),
                    size = Size(width, size.height),
                    cornerRadius = radius,
                )
                left += width
            }
        }
    }
}

@Composable
private fun StatisticsRangePillDropdown(
    value: StatisticsDisplayRange,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (StatisticsDisplayRange) -> Unit,
) {
    val triggerWidth = 112.dp
    val menuWidth = 132.dp
    Box(
        modifier = Modifier.width(triggerWidth),
        contentAlignment = Alignment.TopEnd,
    ) {
        FoxholeValuePill(
            value = statisticsDisplayRangeLabel(value),
            modifier = Modifier.fillMaxWidth(),
            expanded = expanded,
            onClick = { onExpandedChange(!expanded) },
            fillContent = true,
        )
        FoxholeDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.width(menuWidth),
            popupGap = 0.dp,
            horizontalAlignment = FoxholeDropdownHorizontalAlignment.AnchorEnd,
        ) {
            DASHBOARD_DISPLAY_RANGES.forEach { range ->
                val selected = range == value
                FoxholeDropdownItem(
                    onClick = {
                        onSelect(range)
                        onExpandedChange(false)
                    },
                    selected = selected,
                    highlightSelected = false,
                ) {
                    Text(
                        text = statisticsDisplayRangeLabel(range),
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

@Composable
private fun statisticsDisplayRangeLabel(value: StatisticsDisplayRange): String =
    stringResource(
        when (value) {
            StatisticsDisplayRange.HOURS_24 -> R.string.statistics_range_24h
            StatisticsDisplayRange.WEEK -> R.string.statistics_range_week
            StatisticsDisplayRange.MONTH -> R.string.statistics_range_month
            StatisticsDisplayRange.ALL -> R.string.statistics_range_all
        },
    )

private fun dnsCategoryLabel(category: DnsProtectionCategory): Int =
    when (category) {
        DnsProtectionCategory.ADS -> R.string.statistics_dns_category_ads
        DnsProtectionCategory.TRACKERS -> R.string.statistics_dns_category_trackers
        DnsProtectionCategory.TELEMETRY -> R.string.statistics_dns_category_telemetry
        DnsProtectionCategory.MALICIOUS -> R.string.statistics_dns_category_malicious
    }

@Composable
private fun dnsCategoryColor(category: DnsProtectionCategory): Color =
    chartColor(dnsCategoryColorToken(category))

private fun dnsCategoryColorToken(category: DnsProtectionCategory): ChartColorToken =
    when (category) {
        DnsProtectionCategory.ADS -> ChartColorToken.ADS
        DnsProtectionCategory.TRACKERS -> ChartColorToken.TRACKERS
        DnsProtectionCategory.TELEMETRY -> ChartColorToken.TELEMETRY
        DnsProtectionCategory.MALICIOUS -> ChartColorToken.MALICIOUS
    }

@Composable
private fun dnsCategoryColorMap(): Map<DnsProtectionCategory, Color> =
    DnsProtectionCategory.entries.associateWith { category -> dnsCategoryColor(category) }

@Composable
private fun AnomalyStatisticsCard(
    events: List<AnomalyEvent>,
    totalEventsCount: Int,
    installedApps: List<InstalledAppOption>,
    range: StatisticsDisplayRange,
    onRangeSelected: (StatisticsDisplayRange) -> Unit,
    onShowAllEvents: () -> Unit,
) {
    var rangeExpanded by rememberSaveable { mutableStateOf(false) }
    val recentEvents = remember(events) { events.sortedByDescending(AnomalyEvent::createdAtMs) }
    val appsCount =
        remember(recentEvents) {
            recentEvents.mapNotNull(AnomalyEvent::packageName).distinct().size
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(
                icon = Icons.Outlined.WarningAmber,
                title = stringResource(R.string.statistics_anomaly_summary_title),
                trailing = {
                    StatisticsRangePillDropdown(
                        value = range,
                        expanded = rangeExpanded,
                        onExpandedChange = { rangeExpanded = it },
                        onSelect = onRangeSelected,
                    )
                },
            )
            if (recentEvents.isEmpty()) {
                EmptySectionText(text = stringResource(R.string.statistics_anomaly_empty))
            } else {
                DetailMetricGrid(
                    metrics =
                    listOfNotNull(
                        metricIfPositive(
                            stringResource(R.string.statistics_anomaly_events),
                            recentEvents.size,
                        ),
                        metricIfPositive(
                            stringResource(R.string.statistics_anomaly_high_events),
                            recentEvents.count { event -> event.severity == AnomalySeverity.HIGH },
                        ),
                        metricIfPositive(
                            stringResource(R.string.statistics_anomaly_notified_events),
                            recentEvents.count(AnomalyEvent::notificationShown),
                        ),
                        metricIfPositive(
                            stringResource(R.string.statistics_anomaly_apps),
                            appsCount,
                        ),
                    ),
                )
                if (recentEvents.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.statistics_anomaly_recent_events),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        recentEvents.take(STATISTICS_TOP_PREVIEW_LIMIT).forEach { event ->
                            AnomalyEventRow(event = event, installedApps = installedApps)
                        }
                    }
                    if (recentEvents.size > STATISTICS_TOP_PREVIEW_LIMIT || totalEventsCount > recentEvents.size) {
                        TextButton(onClick = onShowAllEvents) {
                            Text(stringResource(R.string.statistics_anomaly_show_all_events))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnomalyEventRow(
    event: AnomalyEvent,
    installedApps: List<InstalledAppOption>,
) {
    val context = LocalContext.current
    val appLabel =
        event.packageName
            ?.let { packageName -> installedApps.firstOrNull { app -> app.packageName == packageName }?.label ?: packageName }
            ?: stringResource(R.string.statistics_anomaly_whole_tunnel)
    val eventColor =
        when (event.severity) {
            AnomalySeverity.HIGH -> MaterialTheme.colorScheme.error
            AnomalySeverity.NOTIFICATION -> MaterialTheme.colorScheme.primary
            AnomalySeverity.ACTIVITY_LOG -> MaterialTheme.colorScheme.tertiary
            AnomalySeverity.SILENT -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(eventColor)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = anomalyReasonText(
                        event = event,
                        appLabel = appLabel,
                        formatBytes = { bytes -> formatBytes(context, bytes) },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        event.packageName,
                        event.protocol,
                        event.createdAtMs.formatLastActivity(),
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = event.score.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = eventColor,
            )
        }
    }
}

@Composable
private fun rememberUsageAccessGranted(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember(context) { mutableStateOf(UsageStatsAccess.isGranted(context)) }
    DisposableEffect(context, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = UsageStatsAccess.isGranted(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        granted = UsageStatsAccess.isGranted(context)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return granted
}

private fun openUsageAccessSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
private fun AppTrafficStatisticsCard(
    rows: List<AppTrafficRow>,
    samples: List<AppTrafficWindow>,
    allRowsCount: Int,
    enabled: Boolean,
    usageAccessGranted: Boolean,
    range: StatisticsDisplayRange,
    onRangeSelected: (StatisticsDisplayRange) -> Unit,
    onOpenUsageAccess: () -> Unit,
    onShowAll: () -> Unit,
    onRowClick: (AppTrafficRow) -> Unit,
) {
    var rangeExpanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.statistics_apps_top_title),
                trailing = {
                    StatisticsRangePillDropdown(
                        value = range,
                        expanded = rangeExpanded,
                        onExpandedChange = { rangeExpanded = it },
                        onSelect = onRangeSelected,
                    )
                },
            )
            if (!usageAccessGranted) {
                Text(
                    text = stringResource(R.string.statistics_usage_access_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenUsageAccess) {
                    Text(stringResource(R.string.statistics_usage_access_action))
                }
            } else if (!enabled) {
                Text(
                    text = stringResource(R.string.app_statistics_disabled_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (rows.isEmpty()) {
                EmptySectionText(text = stringResource(R.string.app_statistics_empty))
            } else {
                AppTrafficTopStackedChart(
                    rows = rows,
                    onRowClick = onRowClick,
                )
                if (allRowsCount > rows.size) {
                    TextButton(onClick = onShowAll) {
                        Text(stringResource(R.string.show_all_label))
                    }
                }
                AppTrafficTimelineChart(samples = samples, range = range)
            }
        }
    }
}

@Composable
private fun AppTrafficTimelineChart(
    samples: List<AppTrafficWindow>,
    range: StatisticsDisplayRange,
) {
    val context = LocalContext.current
    val buckets = remember(samples, range) { trafficTimelineBuckets(samples, range) }
    val maxBytes =
        buckets
            .maxOfOrNull { bucket -> max(bucket.txBytes, bucket.rxBytes) }
            ?.coerceAtLeast(1L)
            ?: 1L
    val yMax = niceTimelineTrafficScale(maxBytes)
    val rangeStart = buckets.firstOrNull()?.startedAtMs ?: System.currentTimeMillis()
    val rangeEnd = (buckets.lastOrNull()?.startedAtMs ?: rangeStart) + range.policy(rangeStart).bucketSizeMs
    val updatedAtMs = samples.maxOfOrNull(AppTrafficWindow::startedAtMs) ?: rangeEnd
    val model =
        com.foxhole.beta.core.statistics.ChartModel(
            id = "app-traffic-timeline",
            title = stringResource(R.string.statistics_apps_title),
            subtitle = stringResource(R.string.statistics_axis_y_bytes, formatBytes(context, yMax)),
            range = range.toStatsRange(),
            xAxis =
            com.foxhole.beta.core.statistics.ChartAxis(
                label = stringResource(R.string.statistics_axis_x_time),
                min = rangeStart.toDouble(),
                max = rangeEnd.toDouble(),
                ticks =
                timelineTicks(
                    range = range,
                    rangeStart = rangeStart,
                    rangeEnd = rangeEnd,
                    nowLabel = stringResource(R.string.statistics_range_now),
                ),
                formatter = com.foxhole.beta.core.statistics.ChartValueFormatter.TIME,
            ),
            yAxis =
            com.foxhole.beta.core.statistics.ChartAxis(
                label = stringResource(R.string.statistics_axis_y_bytes, formatBytes(context, yMax)),
                min = 0.0,
                max = yMax.toDouble(),
                ticks =
                listOf(
                    com.foxhole.beta.core.statistics.ChartTick(0.0, "0"),
                    com.foxhole.beta.core.statistics.ChartTick(yMax.toDouble(), formatBytes(context, yMax)),
                ),
                formatter = com.foxhole.beta.core.statistics.ChartValueFormatter.BYTES,
            ),
            series =
            listOf(
                com.foxhole.beta.core.statistics.ChartSeries(
                    id = "tx",
                    label = stringResource(R.string.traffic_sent),
                    kind = com.foxhole.beta.core.statistics.ChartSeriesKind.LINE,
                    colorToken = com.foxhole.beta.core.statistics.ChartColorToken.TX,
                    points =
                    buckets.map { bucket ->
                        com.foxhole.beta.core.statistics.ChartPoint(
                            bucket.startedAtMs,
                            bucket.txBytes.toDouble(),
                        )
                    },
                ),
                com.foxhole.beta.core.statistics.ChartSeries(
                    id = "rx",
                    label = stringResource(R.string.traffic_received),
                    kind = com.foxhole.beta.core.statistics.ChartSeriesKind.LINE,
                    colorToken = com.foxhole.beta.core.statistics.ChartColorToken.RX,
                    points =
                    buckets.map { bucket ->
                        com.foxhole.beta.core.statistics.ChartPoint(
                            bucket.startedAtMs,
                            bucket.rxBytes.toDouble(),
                        )
                    },
                ),
            ),
            legend =
            com.foxhole.beta.core.statistics.ChartLegendModel(
                items =
                listOf(
                    com.foxhole.beta.core.statistics.ChartLegendItem(
                        "tx",
                        stringResource(R.string.traffic_sent),
                        com.foxhole.beta.core.statistics.ChartColorToken.TX,
                    ),
                    com.foxhole.beta.core.statistics.ChartLegendItem(
                        "rx",
                        stringResource(R.string.traffic_received),
                        com.foxhole.beta.core.statistics.ChartColorToken.RX,
                    ),
                ),
            ),
            emptyState =
            com.foxhole.beta.core.statistics.ChartEmptyState(
                stringResource(R.string.app_statistics_empty),
            ),
            updatedAtMs = updatedAtMs,
        )
    com.foxhole.beta.ui.statistics.charts.TimelineChart(model = model)
}

@Composable
private fun AppTrafficTopStackedChart(
    rows: List<AppTrafficRow>,
    onRowClick: (AppTrafficRow) -> Unit,
) {
    val maxTotal = rows.maxOfOrNull(AppTrafficRow::totalBytes)?.coerceAtLeast(1L) ?: 1L
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChartLegend()
        rows.forEachIndexed { index, row ->
            AppTrafficStackedBarRow(
                index = index,
                row = row,
                maxTotal = maxTotal,
                onClick = { onRowClick(row) },
            )
        }
    }
}

@Composable
private fun AppTrafficStackedBarRow(
    index: Int,
    row: AppTrafficRow,
    maxTotal: Long,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val tokens = chartVisualTokens()
    val sentColor = chartColor(ChartColorToken.TX)
    val receivedColor = chartColor(ChartColorToken.RX)
    val barDescription =
        "${row.label}: ${formatBytes(context, row.totalBytes)}, ${
            stringResource(R.string.traffic_sent)
        } ${formatBytes(context, row.txBytes)}, ${
            stringResource(R.string.traffic_received)
        } ${formatBytes(context, row.rxBytes)}"
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = (index + 1).toString(),
            modifier = Modifier.width(22.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
        AppIcon(packageName = row.packageName, modifier = Modifier.size(32.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatBytes(context, row.totalBytes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Canvas(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .semantics { contentDescription = barDescription },
            ) {
                val radius = CornerRadius(tokens.barCornerRadius.toPx(), tokens.barCornerRadius.toPx())
                drawRoundRect(color = tokens.trackColor, size = size, cornerRadius = radius)
                val scaledWidth = size.width * (row.totalBytes.toFloat() / maxTotal.toFloat()).coerceIn(0f, 1f)
                if (scaledWidth <= 0f) {
                    return@Canvas
                }
                val total = row.totalBytes.coerceAtLeast(1L).toFloat()
                val sentWidth = (scaledWidth * (row.txBytes.coerceAtLeast(0L).toFloat() / total)).coerceAtLeast(
                    if (row.txBytes > 0L) 2.dp.toPx() else 0f,
                )
                val receivedWidth = (scaledWidth - sentWidth).coerceAtLeast(
                    if (row.rxBytes > 0L) 2.dp.toPx() else 0f,
                ).coerceAtMost((scaledWidth - sentWidth).coerceAtLeast(0f))
                if (sentWidth > 0f) {
                    drawRoundRect(
                        color = sentColor,
                        size = Size(sentWidth.coerceAtMost(scaledWidth), size.height),
                        cornerRadius = radius,
                    )
                }
                if (receivedWidth > 0f) {
                    drawRoundRect(
                        color = receivedColor,
                        topLeft = Offset(sentWidth.coerceAtMost(scaledWidth), 0f),
                        size = Size(receivedWidth, size.height),
                        cornerRadius = radius,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${stringResource(R.string.traffic_sent)} ${formatBytes(context, row.txBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = sentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${stringResource(R.string.traffic_received)} ${formatBytes(context, row.rxBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = receivedColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ChartLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendItem(color = chartColor(ChartColorToken.TX), text = stringResource(R.string.traffic_sent))
        LegendItem(color = chartColor(ChartColorToken.RX), text = stringResource(R.string.traffic_received))
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = text },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).padding(1.dp)) {
            Canvas(modifier = Modifier.size(8.dp).clearAndSetSemantics {}) { drawCircle(color) }
        }
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AppTrafficRowView(
    row: AppTrafficRow,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = row.packageName, modifier = Modifier.size(42.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                row.packageName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnomalyBadges(row.badges)
        }
        TrafficCells(tx = row.txBytes, rx = row.rxBytes)
    }
}

@Composable
private fun AnomalyBadges(badges: Set<AppAnomalyBadge>) {
    val visibleBadges = badges.take(2)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        visibleBadges.forEach { badge ->
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = anomalyBadgeColor(badge),
            ) {
                Text(
                    text = anomalyBadgeLabel(badge),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = anomalyBadgeContentColor(badge),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun anomalyBadgeLabel(badge: AppAnomalyBadge): String =
    stringResource(
        when (badge) {
            AppAnomalyBadge.NORMAL -> R.string.anomaly_badge_normal
            AppAnomalyBadge.UNUSUAL -> R.string.anomaly_badge_unusual
            AppAnomalyBadge.HIGH_UPLOAD -> R.string.anomaly_badge_high_upload
            AppAnomalyBadge.NEW_ROUTE -> R.string.anomaly_badge_new_route
            AppAnomalyBadge.BACKGROUND -> R.string.anomaly_badge_background
        },
    )

@Composable
private fun anomalyBadgeColor(badge: AppAnomalyBadge): Color {
    val semanticColors = LocalFoxholeSemanticColors.current
    return when (badge) {
        AppAnomalyBadge.NORMAL -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
        AppAnomalyBadge.UNUSUAL -> MaterialTheme.colorScheme.secondaryContainer
        AppAnomalyBadge.HIGH_UPLOAD -> MaterialTheme.colorScheme.errorContainer
        AppAnomalyBadge.NEW_ROUTE -> MaterialTheme.colorScheme.tertiaryContainer
        AppAnomalyBadge.BACKGROUND -> semanticColors.warning.copy(alpha = 0.18f)
    }
}

@Composable
private fun anomalyBadgeContentColor(badge: AppAnomalyBadge): Color =
    when (badge) {
        AppAnomalyBadge.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
        AppAnomalyBadge.UNUSUAL -> MaterialTheme.colorScheme.onSecondaryContainer
        AppAnomalyBadge.HIGH_UPLOAD -> MaterialTheme.colorScheme.onErrorContainer
        AppAnomalyBadge.NEW_ROUTE -> MaterialTheme.colorScheme.onTertiaryContainer
        AppAnomalyBadge.BACKGROUND -> MaterialTheme.colorScheme.onSurface
    }

@Composable
private fun TrafficCells(tx: Long, rx: Long) {
    val context = LocalContext.current
    val semanticColors = LocalFoxholeSemanticColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatBytes(context, tx),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(formatBytes(context, rx), style = MaterialTheme.typography.labelMedium, color = semanticColors.success)
        }
        Text(
            formatBytes(context, tx + rx),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun StatisticsSectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(icon = icon, title = title, trailing = trailing)
            content()
        }
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        trailing()
    }
}

@Composable
private fun EmptySectionText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatisticsMetricSwitch(
    metric: StatisticsMetric,
    checked: Boolean,
    title: String,
    enabled: Boolean,
    onMetricChanged: (StatisticsMetric, Boolean) -> Unit,
    summary: String? = null,
) {
    SettingSwitchRow(
        title = title,
        checked = checked,
        summary = summary,
        leadingIcon = Icons.Outlined.Tune,
        enabled = enabled,
        onCheckedChange = { value -> onMetricChanged(metric, value) },
        grouped = true,
        summaryMaxLines = Int.MAX_VALUE,
    )
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun ProfileStatisticsDetail(
    state: SettingsRouteUiState,
    statistics: StatisticsUiState,
    item: ProfileTrafficUiItem,
) {
    val context = LocalContext.current
    val detail = profileStatisticsDetail(state, item)
    Column(
        modifier = Modifier
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DetailMetricGrid(
            metrics =
            listOfNotNull(
                metricIfPositive(
                    stringResource(R.string.statistics_total_traffic),
                    detail.totalBytes,
                    formatBytes(context, detail.totalBytes),
                ),
                metricIfPositive(stringResource(R.string.statistics_vpn_sessions), detail.totalAttempts),
                metricIfPositive(stringResource(R.string.statistics_successful_connections), detail.successCount),
                metricIfPositive(stringResource(R.string.statistics_errors), detail.failureCount),
                detail.avgLatencyMs?.let { stringResource(R.string.statistics_average_latency) to it.formatLatency() },
                detail.minLatencyMs?.let { stringResource(R.string.statistics_min_latency) to it.formatLatency() },
                detail.maxLatencyMs?.let { stringResource(R.string.statistics_max_latency) to it.formatLatency() },
                detail.lastActivityAt?.let { stringResource(R.string.statistics_last_activity) to it.formatLastActivity() },
            ),
        )
        if (detail.protocols.isNotEmpty()) {
            val connectedProtocols =
                remember(detail.protocols) {
                    detail.protocols
                        .filter { protocol -> protocol.totalAttempts > 0 || protocol.totalBytes > 0L || protocol.lastUsedAt != null }
                        .sortedWith(
                            compareByDescending<ProfileProtocolDetail> { protocol -> protocol.totalBytes }
                                .thenByDescending { protocol -> protocol.totalAttempts }
                                .thenBy { protocol -> protocol.label.lowercase(Locale.getDefault()) },
                        )
                }
            var selectedProtocolLabel by rememberSaveable(item.profileId, connectedProtocols.size) {
                mutableStateOf(connectedProtocols.firstOrNull()?.label.orEmpty())
            }
            val selectedProtocol =
                connectedProtocols.firstOrNull { protocol -> protocol.label == selectedProtocolLabel }
                    ?: connectedProtocols.firstOrNull()
            if (connectedProtocols.isEmpty()) {
                EmptySectionText(text = stringResource(R.string.statistics_protocols_empty))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedProtocol != null) {
                        ProfileProtocolDetailPanel(protocol = selectedProtocol)
                    }
                    Text(
                        text = stringResource(R.string.statistics_profile_protocols_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        connectedProtocols.forEach { protocol ->
                            ProfileProtocolUsageRow(
                                protocol = protocol,
                                selected = protocol.label == selectedProtocol?.label,
                                onClick = { selectedProtocolLabel = protocol.label },
                            )
                        }
                    }
                }
            }
        }
        val comparisons =
            statistics.profileComparisons.filter { comparison ->
                comparison.left.profileId == item.profileId || comparison.right.profileId == item.profileId
            }
        if (comparisons.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_profile_comparison_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            comparisons.forEach { comparison -> ProfileComparisonCard(item = comparison) }
        }
    }
}

@Composable
private fun ProfileProtocolUsageRow(
    protocol: ProfileProtocolDetail,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
        },
        border =
        BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            },
        ),
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = protocol.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                    listOfNotNull(
                        protocol.lastUsedAt?.let { stringResource(R.string.statistics_last_activity) to it.formatLastActivity() },
                        stringResource(R.string.statistics_success_rate) to formatPercent(protocol.successRate),
                    ).joinToString(" • ") { (label, value) -> "$label: $value" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatBytes(context, protocol.totalBytes),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun ProfileProtocolDetailPanel(protocol: ProfileProtocolDetail) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = protocol.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            DetailMetricGrid(
                metrics =
                listOfNotNull(
                    stringResource(R.string.statistics_total_traffic) to formatBytes(context, protocol.totalBytes),
                    stringResource(R.string.statistics_success_rate) to formatPercent(protocol.successRate),
                    stringResource(R.string.statistics_error_rate) to formatPercent(protocol.errorRate),
                    protocol.avgLatencyMs?.let { stringResource(R.string.statistics_average_latency) to it.formatLatency() },
                    protocol.lastUsedAt?.let { stringResource(R.string.statistics_last_activity) to it.formatLastActivity() },
                ),
            )
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun AppTrafficDetail(
    row: AppTrafficRow,
    samples: List<AppTrafficWindow>,
    networkActivityEvents: List<NetworkActivityEvent>,
    ipInfo: com.foxhole.beta.core.model.IpInfo?,
) {
    val context = LocalContext.current
    val resolver = remember(context) { TorGeoIpCountryResolver(context) }
    var allConnectionsVisible by rememberSaveable { mutableStateOf(false) }
    val appSamples = samples.filter { sample -> sample.packageName == row.packageName }
    val connectionRows = remember(row.packageName, networkActivityEvents, ipInfo) {
        appConnectionRows(
            packageName = row.packageName,
            events = networkActivityEvents,
            resolver = resolver,
            ipInfo = ipInfo,
        )
    }
    if (allConnectionsVisible) {
        AlertDialog(
            onDismissRequest = { allConnectionsVisible = false },
            title = { Text(stringResource(R.string.statistics_app_detail_all_destinations)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    items(
                        connectionRows,
                        key = { connection -> "${connection.remote}:${connection.protocol}" },
                    ) { connection ->
                        AppConnectionRowView(connection = connection)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                FoxholeDialogDismissButton(onClick = { allConnectionsVisible = false })
            },
        )
    }
    Column(
        modifier = Modifier
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DetailMetricGrid(
            metrics =
            listOfNotNull(
                metricIfPositive(
                    stringResource(R.string.home_total_label),
                    row.totalBytes,
                    formatBytes(context, row.totalBytes),
                ),
                metricIfPositive(
                    stringResource(R.string.traffic_received),
                    row.rxBytes,
                    formatBytes(context, row.rxBytes),
                ),
                metricIfPositive(
                    stringResource(R.string.traffic_sent),
                    row.txBytes,
                    formatBytes(context, row.txBytes),
                ),
                metricIfPositive(stringResource(R.string.statistics_app_samples), appSamples.size),
                appSamples.maxOfOrNull(AppTrafficWindow::startedAtMs)
                    ?.let { stringResource(R.string.statistics_last_activity) to it.formatLastActivity() },
            ),
        )
        if (appSamples.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_chart_axes_app_timeline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppTrafficMiniChart(samples = appSamples)
            ChartLegend()
        }
        if (connectionRows.isEmpty()) {
            Text(
                text = stringResource(R.string.statistics_app_detail_connections_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.statistics_app_detail_top_destinations),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            connectionRows.take(STATISTICS_TOP_PREVIEW_LIMIT).forEach { connection ->
                AppConnectionRowView(connection = connection)
            }
            if (connectionRows.size > STATISTICS_TOP_PREVIEW_LIMIT) {
                TextButton(onClick = { allConnectionsVisible = true }) {
                    Text(stringResource(R.string.show_all_label))
                }
            }
        }
    }
}

@Composable
private fun AppTrafficMiniChart(samples: List<AppTrafficWindow>) {
    AppTrafficTimelineChart(samples = samples, range = StatisticsDisplayRange.HOURS_24)
}

@Composable
private fun AppConnectionRowView(connection: AppConnectionRow) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = connection.countryCode?.countryFlagEmoji().orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.Center,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = connection.ipAddress.ifBlank { connection.remote },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                listOfNotNull(
                    connection.countryName,
                    connection.city ?: stringResource(R.string.statistics_app_detail_city_unknown),
                    connection.protocol,
                    stringResource(R.string.statistics_app_detail_connection_count, connection.count),
                    connection.lastSeenAt.formatLastActivity(),
                ).joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatBytes(context, connection.bytes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun DetailMetricGrid(metrics: List<Pair<String, String>>) {
    if (metrics.isEmpty()) {
        EmptySectionText(text = stringResource(R.string.statistics_no_data))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        metrics.forEachIndexed { index, (label, value) ->
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (index != metrics.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f))
            }
        }
    }
}

private data class AppConnectionRow(
    val remote: String,
    val ipAddress: String,
    val countryCode: String?,
    val countryName: String?,
    val city: String?,
    val protocol: String,
    val count: Int,
    val bytes: Long,
    val lastSeenAt: Long,
)

private data class TrafficTimelineBucket(
    val startedAtMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
)

private data class ProfileStatisticsDetailModel(
    val totalBytes: Long,
    val successCount: Int,
    val failureCount: Int,
    val avgLatencyMs: Long?,
    val minLatencyMs: Long?,
    val maxLatencyMs: Long?,
    val lastActivityAt: Long?,
    val lastProtocolHint: ProtocolHint,
    val protocols: List<ProfileProtocolDetail>,
) {
    val totalAttempts: Int get() = successCount + failureCount
}

private data class ProfileProtocolDetail(
    val label: String,
    val protocolHint: ProtocolHint,
    val successCount: Int,
    val failureCount: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val avgLatencyMs: Long?,
    val minLatencyMs: Long?,
    val maxLatencyMs: Long?,
    val lastUsedAt: Long?,
) {
    val totalAttempts: Int get() = successCount + failureCount
    val successRate: Float get() = if (totalAttempts == 0) 0f else successCount.toFloat() / totalAttempts
    val errorRate: Float get() = if (totalAttempts == 0) 0f else failureCount.toFloat() / totalAttempts
    val totalBytes: Long get() = rxBytes + txBytes
}

private data class ProtocolAccumulator(
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var rxBytes: Long = 0L,
    var txBytes: Long = 0L,
    val latencies: MutableList<Long> = mutableListOf(),
    var lastUsedAt: Long? = null,
) {
    fun addMemory(memory: SmartProfileProtocolMemory) {
        successCount += memory.successCount.coerceAtLeast(0)
        failureCount += memory.failureCount.coerceAtLeast(0)
        memory.lastLatencyMs?.takeIf { latency -> latency > 0L }?.let(latencies::add)
        lastUsedAt = maxOfNotNull(lastUsedAt, memory.lastSuccessAt, memory.lastFailureAt, memory.lastValidatedAt, memory.lastTrafficAt)
    }

    fun addTraffic(item: ProfileTrafficUiItem) {
        rxBytes += item.rxBytes.coerceAtLeast(0L)
        txBytes += item.txBytes.coerceAtLeast(0L)
        lastUsedAt = maxOfNotNull(lastUsedAt, item.updatedAt.takeIf { it > 0L })
    }

    fun toProtocolItem(protocol: ProtocolHint): ProtocolStatisticsUiItem {
        if (successCount + failureCount == 0 && rxBytes + txBytes > 0L) {
            successCount = 1
        }
        return ProtocolStatisticsUiItem(
            protocol = protocol,
            successCount = successCount,
            failureCount = failureCount,
            rxBytes = rxBytes,
            txBytes = txBytes,
            avgLatencyMs = latencies.averageOrNull(),
            lastUsedAt = lastUsedAt,
        )
    }
}

private data class ComparisonAccumulator(
    val profileId: Long,
    val profileName: String,
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var rxBytes: Long = 0L,
    var txBytes: Long = 0L,
    val latencies: MutableList<Long> = mutableListOf(),
) {
    fun addMemory(memory: SmartProfileProtocolMemory) {
        successCount += memory.successCount.coerceAtLeast(0)
        failureCount += memory.failureCount.coerceAtLeast(0)
        memory.lastLatencyMs?.takeIf { latency -> latency > 0L }?.let(latencies::add)
    }

    fun addTraffic(item: ProfileTrafficUiItem) {
        rxBytes += item.rxBytes.coerceAtLeast(0L)
        txBytes += item.txBytes.coerceAtLeast(0L)
    }

    fun toSide(): ProfileComparisonSideUiItem {
        if (successCount + failureCount == 0 && rxBytes + txBytes > 0L) {
            successCount = 1
        }
        return ProfileComparisonSideUiItem(
            profileId = profileId,
            profileName = profileName,
            successCount = successCount,
            failureCount = failureCount,
            rxBytes = rxBytes,
            txBytes = txBytes,
            avgLatencyMs = latencies.averageOrNull(),
        )
    }
}

@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")
private fun profileStatisticsDetail(
    state: SettingsRouteUiState,
    item: ProfileTrafficUiItem,
): ProfileStatisticsDetailModel {
    val profile = state.profiles.firstOrNull { profile -> profile.id == item.profileId }
    val optionList = profile?.protocolOptions.orEmpty()
    val options = optionList.associateBy(ProfileProtocolOption::id)
    val preference = state.settings.smartProfilePreferences.firstOrNull { preference -> preference.profileId == item.profileId }
    val memoriesByOptionId = preference?.protocolMemories.orEmpty().associateBy(SmartProfileProtocolMemory::optionId)
    val selectedOptionId = profile?.selectedProtocolOptionId ?: optionList.firstOrNull(ProfileProtocolOption::isSelected)?.id
    val configuredProtocolDetails =
        optionList
            .filter { option -> option.protocolHint != ProtocolHint.UNKNOWN }
            .map { option ->
                val memory = memoriesByOptionId[option.id]
                val latency = memory?.lastLatencyMs?.takeIf { value -> value > 0L }
                val lastUsed =
                    memory?.let {
                        maxOfNotNull(
                            it.lastSuccessAt,
                            it.lastFailureAt,
                            it.lastValidatedAt,
                            it.lastTrafficAt,
                        )
                    }
                val trafficForProtocol =
                    if (
                        option.id == selectedOptionId ||
                        (selectedOptionId == null && option.protocolHint == item.protocolHint) ||
                        (selectedOptionId == null && item.protocolHint == ProtocolHint.UNKNOWN && optionList.size == 1)
                    ) {
                        item
                    } else {
                        null
                    }
                ProfileProtocolDetail(
                    label = option.displayName.ifBlank { protocolDisplayName(option.protocolHint) },
                    protocolHint = option.protocolHint,
                    successCount = memory?.successCount?.coerceAtLeast(0) ?: 0,
                    failureCount = memory?.failureCount?.coerceAtLeast(0) ?: 0,
                    rxBytes = trafficForProtocol?.rxBytes ?: 0L,
                    txBytes = trafficForProtocol?.txBytes ?: 0L,
                    avgLatencyMs = latency,
                    minLatencyMs = latency,
                    maxLatencyMs = latency,
                    lastUsedAt = lastUsed ?: trafficForProtocol?.updatedAt?.takeIf { updatedAt -> updatedAt > 0L },
                )
            }
    val memoryOnlyDetails =
        preference
            ?.protocolMemories
            .orEmpty()
            .filterNot { memory -> memory.optionId in options }
            .mapNotNull { memory ->
                val protocol = profile?.protocolHint ?: item.protocolHint
                if (protocol == ProtocolHint.UNKNOWN) return@mapNotNull null
                val latency = memory.lastLatencyMs?.takeIf { value -> value > 0L }
                val lastUsed =
                    maxOfNotNull(
                        memory.lastSuccessAt,
                        memory.lastFailureAt,
                        memory.lastValidatedAt,
                        memory.lastTrafficAt,
                    )
                ProfileProtocolDetail(
                    label = protocolDisplayName(protocol),
                    protocolHint = protocol,
                    successCount = memory.successCount.coerceAtLeast(0),
                    failureCount = memory.failureCount.coerceAtLeast(0),
                    rxBytes = 0L,
                    txBytes = 0L,
                    avgLatencyMs = latency,
                    minLatencyMs = latency,
                    maxLatencyMs = latency,
                    lastUsedAt = lastUsed,
                )
            }
            .filter { detail -> detail.totalAttempts > 0 || detail.totalBytes > 0L || detail.lastUsedAt != null }
    val fallbackProtocol =
        item.protocolHint
            .takeIf { hint -> hint != ProtocolHint.UNKNOWN }
            ?: profile?.runtimeProtocolHint()
            ?: ProtocolHint.UNKNOWN
    val protocolDetails =
        (configuredProtocolDetails + memoryOnlyDetails)
            .takeIf(List<ProfileProtocolDetail>::isNotEmpty)
            ?: emptyList()
    val visibleProtocolDetails =
        protocolDetails.takeIf(List<ProfileProtocolDetail>::isNotEmpty)
            ?: listOf(
                ProfileProtocolDetail(
                    label = protocolDisplayName(fallbackProtocol),
                    protocolHint = fallbackProtocol,
                    successCount = if (item.totalBytes > 0L) 1 else 0,
                    failureCount = 0,
                    rxBytes = item.rxBytes,
                    txBytes = item.txBytes,
                    avgLatencyMs = null,
                    minLatencyMs = null,
                    maxLatencyMs = null,
                    lastUsedAt = item.updatedAt.takeIf { updatedAt -> updatedAt > 0L },
                ),
            )
    val latencies = visibleProtocolDetails.mapNotNull(ProfileProtocolDetail::avgLatencyMs)
    val successCount = visibleProtocolDetails.sumOf(ProfileProtocolDetail::successCount).let { count ->
        if (count == 0 && item.totalBytes > 0L) 1 else count
    }
    val failureCount = visibleProtocolDetails.sumOf(ProfileProtocolDetail::failureCount)
    val lastProtocol =
        visibleProtocolDetails
            .maxByOrNull { detail -> detail.lastUsedAt ?: 0L }
            ?.protocolHint
            ?: fallbackProtocol
    return ProfileStatisticsDetailModel(
        totalBytes = item.totalBytes,
        successCount = successCount,
        failureCount = failureCount,
        avgLatencyMs = latencies.averageOrNull(),
        minLatencyMs = latencies.minOrNull(),
        maxLatencyMs = latencies.maxOrNull(),
        lastActivityAt =
        maxOfNotNull(
            item.updatedAt.takeIf { updatedAt -> updatedAt > 0L },
            visibleProtocolDetails.mapNotNull(ProfileProtocolDetail::lastUsedAt).maxOrNull(),
        ),
        lastProtocolHint = lastProtocol,
        protocols = visibleProtocolDetails,
    )
}

internal fun statisticsUiState(
    state: SettingsRouteUiState,
    retention: StatisticsRetention,
): StatisticsUiState {
    val profileTraffic = profileTrafficItems(state)
    val protocolTraffic = protocolTrafficItems(state)
    val protocolStats = protocolStatistics(state, protocolTraffic)
    val total = overallStatistics(profileTraffic, protocolStats, state)
    return StatisticsUiState(
        range = retention.toStatisticsRange(),
        extendedMode =
        state.settings.statistics.enabled &&
            state.settings.statistics.appTrafficEnabled &&
            state.settings.appTrafficStatsEnabled,
        profileTraffic = profileTraffic,
        total = total,
        vpnProtocols = protocolStats,
        profileComparisons = profileComparisons(state, protocolTraffic),
        transports = transportStatistics(protocolTraffic),
    )
}

private fun protocolTrafficItems(state: SettingsRouteUiState): List<ProfileTrafficUiItem> {
    val items =
        state.settings.profileTrafficTotals.map { total ->
            ProfileTrafficUiItem(
                profileId = total.profileId,
                profileName = total.profileName,
                protocolHint = total.protocolHint,
                transport = total.transport,
                rxBytes = total.rxTotalBytes,
                txBytes = total.txTotalBytes,
                updatedAt = total.updatedAt,
            )
        }
            .toMutableList()
    val activeProfile = state.activeProfile
    val liveTraffic = state.traffic
    if (activeProfile != null && (liveTraffic.rxTotalBytes > 0L || liveTraffic.txTotalBytes > 0L)) {
        items +=
            ProfileTrafficUiItem(
                profileId = activeProfile.id,
                profileName = activeProfile.name,
                protocolHint = activeProfile.runtimeProtocolHint(),
                transport = TransportProtocol.UNKNOWN,
                rxBytes = liveTraffic.rxTotalBytes,
                txBytes = liveTraffic.txTotalBytes,
                updatedAt = liveTraffic.sampledAt,
            )
    }
    return items.sortedByDescending(ProfileTrafficUiItem::updatedAt)
}

private fun profileTrafficItems(state: SettingsRouteUiState): List<ProfileTrafficUiItem> =
    protocolTrafficItems(state)
        .groupBy(ProfileTrafficUiItem::profileId)
        .values
        .map { items ->
            val latest = items.maxBy(ProfileTrafficUiItem::updatedAt)
            ProfileTrafficUiItem(
                profileId = latest.profileId,
                profileName = latest.profileName,
                protocolHint = latest.protocolHint,
                transport = items.singleKnownTransportOrUnknown(),
                rxBytes = items.sumOf(ProfileTrafficUiItem::rxBytes),
                txBytes = items.sumOf(ProfileTrafficUiItem::txBytes),
                updatedAt = latest.updatedAt,
            )
        }.sortedByDescending(ProfileTrafficUiItem::updatedAt)

private fun protocolStatistics(
    state: SettingsRouteUiState,
    profileTraffic: List<ProfileTrafficUiItem>,
): List<ProtocolStatisticsUiItem> {
    val profileById = state.profiles.associateBy(Profile::id)
    val accumulators = linkedMapOf<ProtocolHint, ProtocolAccumulator>()
    state.profiles.flatMap(Profile::statisticsProtocolHints).forEach { protocol ->
        if (protocol != ProtocolHint.UNKNOWN) {
            accumulators.getOrPut(protocol) { ProtocolAccumulator() }
        }
    }
    state.settings.smartProfilePreferences.forEach { preference ->
        val profile = profileById[preference.profileId] ?: return@forEach
        val options = profile.protocolOptions.associateBy(ProfileProtocolOption::id)
        preference.protocolMemories.forEach { memory ->
            val protocol = options[memory.optionId]?.protocolHint ?: return@forEach
            if (protocol != ProtocolHint.UNKNOWN) {
                accumulators.getOrPut(protocol) { ProtocolAccumulator() }.addMemory(memory)
            }
        }
    }
    profileTraffic.forEach { traffic ->
        if (traffic.protocolHint != ProtocolHint.UNKNOWN) {
            accumulators.getOrPut(traffic.protocolHint) { ProtocolAccumulator() }.addTraffic(traffic)
        }
    }
    return accumulators
        .map { (protocol, accumulator) -> accumulator.toProtocolItem(protocol) }
        .filter { item -> item.protocol != ProtocolHint.UNKNOWN && (item.successCount > 0 || item.totalBytes > 0L) }
        .sortedWith(
            compareByDescending<ProtocolStatisticsUiItem> { item -> item.totalBytes }
                .thenByDescending { item -> item.successCount }
                .thenBy { item -> item.protocol.name },
        )
}

private fun overallStatistics(
    profileTraffic: List<ProfileTrafficUiItem>,
    protocolStats: List<ProtocolStatisticsUiItem>,
    state: SettingsRouteUiState,
): OverallStatisticsUiItem {
    val memoryLatencies =
        state.settings.smartProfilePreferences.flatMap { preference ->
            preference.protocolMemories.mapNotNull { memory -> memory.lastLatencyMs?.takeIf { latency -> latency > 0L } }
        }
    val successCount = protocolStats.sumOf(ProtocolStatisticsUiItem::successCount)
    val failureCount = protocolStats.sumOf(ProtocolStatisticsUiItem::failureCount)
    val trafficBackedSessions =
        profileTraffic.count { item -> item.totalBytes > 0L && successCount == 0 }
    val lastActivity =
        maxOfNotNull(
            profileTraffic.maxOfOrNull(ProfileTrafficUiItem::updatedAt),
            protocolStats.mapNotNull(ProtocolStatisticsUiItem::lastUsedAt).maxOrNull(),
            state.traffic.sampledAt.takeIf { sampledAt -> sampledAt > 0L && state.traffic.rxTotalBytes + state.traffic.txTotalBytes > 0L },
        )
    return OverallStatisticsUiItem(
        totalBytes = profileTraffic.sumOf(ProfileTrafficUiItem::totalBytes),
        vpnSessions = successCount + failureCount + trafficBackedSessions,
        successCount = successCount + trafficBackedSessions,
        failureCount = failureCount,
        avgLatencyMs = memoryLatencies.averageOrNull(),
        lastActivityAt = lastActivity,
    )
}

private fun profileComparisons(
    state: SettingsRouteUiState,
    profileTraffic: List<ProfileTrafficUiItem>,
): List<ProfileComparisonUiItem> {
    val comparisonByProtocol = linkedMapOf<ProtocolHint, MutableMap<Long, ComparisonAccumulator>>()
    val profileById = state.profiles.associateBy(Profile::id)
    state.settings.smartProfilePreferences.forEach { preference ->
        val profile = profileById[preference.profileId] ?: return@forEach
        val options = profile.protocolOptions.associateBy(ProfileProtocolOption::id)
        preference.protocolMemories.forEach { memory ->
            val protocol = options[memory.optionId]?.protocolHint ?: return@forEach
            if (protocol == ProtocolHint.UNKNOWN) {
                return@forEach
            }
            val profileMap = comparisonByProtocol.getOrPut(protocol) { linkedMapOf() }
            profileMap
                .getOrPut(profile.id) { ComparisonAccumulator(profile.id, profile.name) }
                .addMemory(memory)
        }
    }
    profileTraffic.forEach { traffic ->
        val protocol = traffic.protocolHint.takeIf { hint -> hint != ProtocolHint.UNKNOWN } ?: return@forEach
        comparisonByProtocol
            .getOrPut(protocol) { linkedMapOf() }
            .getOrPut(traffic.profileId) { ComparisonAccumulator(traffic.profileId, traffic.profileName) }
            .addTraffic(traffic)
    }
    return comparisonByProtocol.mapNotNull { (protocol, profileMap) ->
        val candidates =
            profileMap.values
                .map(ComparisonAccumulator::toSide)
                .filter { side -> side.totalAttempts >= PROFILE_COMPARISON_MIN_ATTEMPTS }
                .sortedWith(
                    compareByDescending<ProfileComparisonSideUiItem> { side -> side.totalAttempts }
                        .thenByDescending { side -> side.totalBytes },
                )
        if (candidates.size < 2) {
            null
        } else {
            ProfileComparisonUiItem(
                protocol = protocol,
                left = candidates[0],
                right = candidates[1],
            )
        }
    }
}

private fun transportStatistics(profileTraffic: List<ProfileTrafficUiItem>): List<TransportStatisticsUiItem> {
    val accumulators = linkedMapOf<TransportProtocol, ProtocolAccumulator>()
    profileTraffic.forEach { traffic ->
        val transport = traffic.transport
        val accumulator = accumulators.getOrPut(transport) { ProtocolAccumulator() }
        if (traffic.totalBytes > 0L) {
            accumulator.successCount += 1
        }
        accumulator.rxBytes += traffic.rxBytes
        accumulator.txBytes += traffic.txBytes
        accumulator.lastUsedAt = maxOfNotNull(accumulator.lastUsedAt, traffic.updatedAt.takeIf { it > 0L })
    }
    return accumulators
        .map { (transport, accumulator) ->
            TransportStatisticsUiItem(
                transport = transport,
                successCount = accumulator.successCount,
                failureCount = accumulator.failureCount,
                rxBytes = accumulator.rxBytes,
                txBytes = accumulator.txBytes,
                avgLatencyMs = accumulator.latencies.averageOrNull(),
            )
        }
        .filter { item -> item.transport != TransportProtocol.UNKNOWN && (item.totalAttempts > 0 || item.totalBytes > 0L) }
        .sortedWith(
            compareByDescending<TransportStatisticsUiItem> { item -> item.totalBytes }
                .thenBy { item -> item.transport.name },
        )
}

private fun List<ProfileTrafficUiItem>.singleKnownTransportOrUnknown(): TransportProtocol =
    map(ProfileTrafficUiItem::transport)
        .filterNot { transport -> transport == TransportProtocol.UNKNOWN }
        .distinct()
        .singleOrNull()
        ?: TransportProtocol.UNKNOWN

internal fun appTrafficRows(
    samples: List<AppTrafficWindow>,
    installedApps: List<InstalledAppOption>,
    anomalyEvents: List<AnomalyEvent>,
    retention: StatisticsRetention,
): List<AppTrafficRow> {
    val labels = installedApps.associate { it.packageName to it.label }
    val cutoff = retention.durationMs?.let { System.currentTimeMillis() - it }
    return com.foxhole.beta.core.statistics.appTrafficRows(
        windows = samples.filter { sample -> cutoff == null || sample.startedAtMs >= cutoff },
        labelsByPackage = labels,
        anomalyEvents = anomalyEvents,
        includeOther = false,
    )
}

internal fun dnsProtectionSummary(
    trafficWindows: List<TrafficWindow>,
    appRows: List<AppTrafficRow>,
    retention: StatisticsRetention,
    displayRange: StatisticsDisplayRange? = null,
    dnsSettings: DnsSettings,
): DnsProtectionSummary {
    val cutoff = (displayRange?.durationMs ?: retention.durationMs)?.let { System.currentTimeMillis() - it }
    val windows =
        trafficWindows.filter { window -> cutoff == null || window.startedAtMs >= cutoff }
    return com.foxhole.beta.core.statistics.dnsProtectionSummary(
        trafficWindows = windows,
        appRows = appRows,
        dnsSettings = dnsSettings,
    )
}

private fun enabledDnsProtectionCategories(settings: DnsSettings): List<DnsProtectionCategory> =
    com.foxhole.beta.core.statistics.enabledDnsProtectionCategories(settings)

private fun splitDnsBlockedByCategory(
    blocked: Int,
    categories: List<DnsProtectionCategory>,
): List<DnsProtectionCategoryRow> {
    return com.foxhole.beta.core.statistics.splitDnsBlockedByCategory(blocked, categories)
}

internal fun installedAppChangesForRetention(
    changes: List<InstalledAppInventoryChange>,
    retention: StatisticsRetention,
): List<InstalledAppInventoryChange> {
    val cutoff = retention.durationMs?.let { System.currentTimeMillis() - it }
    return changes
        .asSequence()
        .filter { change -> cutoff == null || change.detectedAt >= cutoff }
        .sortedByDescending(InstalledAppInventoryChange::detectedAt)
        .toList()
}

internal fun countryTrafficRows(
    trafficWindows: List<TrafficWindow>,
    liveDestinations: List<TrafficMapPoint>,
): List<CountryTrafficUiRow> =
    com.foxhole.beta.core.statistics.countryTrafficRows(
        trafficWindows = trafficWindows,
        liveDestinations = liveDestinations,
    )

private fun normalizedCountryCode(countryCode: String?): String? =
    com.foxhole.beta.core.statistics.normalizedCountryCode(countryCode)

private fun countryDisplayName(countryCode: String): String =
    com.foxhole.beta.core.statistics.countryDisplayName(countryCode)

@Composable
private fun anomalyReasonText(
    event: AnomalyEvent,
    appLabel: String,
    formatBytes: (Long) -> String,
): String =
    when (event.type) {
        AnomalyType.APP_UPLOAD_SPIKE ->
            stringResource(
                R.string.anomaly_reason_app_upload,
                appLabel,
                event.evidence["tx_per_min"]?.toLongOrNull()?.let(formatBytes) ?: stringResource(R.string.statistics_no_data),
                event.evidence["upload_ratio"].asPercentText(),
            )
        AnomalyType.APP_BACKGROUND_TRAFFIC ->
            stringResource(
                R.string.anomaly_reason_background,
                appLabel,
                event.evidence["app_share"].asPercentText(),
                event.evidence["upload_ratio"].asPercentText(),
            )
        AnomalyType.TOTAL_TRAFFIC_SPIKE ->
            stringResource(
                R.string.anomaly_reason_total_spike,
                event.evidence["bytes_per_min"]?.toLongOrNull()?.let(formatBytes) ?: stringResource(R.string.statistics_no_data),
                event.evidence["robust_z"].orEmpty().ifBlank { stringResource(R.string.statistics_no_data) },
            )
        AnomalyType.NEW_DESTINATION_COUNTRY ->
            stringResource(
                R.string.anomaly_reason_new_country,
                event.evidence["country"].orEmpty().ifBlank { stringResource(R.string.statistics_no_data) },
                event.evidence["traffic_share"].asPercentText(),
            )
        AnomalyType.DNS_BLOCK_RATIO_SPIKE ->
            stringResource(
                R.string.anomaly_reason_dns_blocks,
                event.evidence["blocked_dns"].orEmpty().ifBlank { "0" },
                event.evidence["allowed_dns"].orEmpty().ifBlank { "0" },
                event.evidence["blocked_ratio"].asPercentText(),
            )
        AnomalyType.RECONNECT_STORM ->
            stringResource(
                R.string.anomaly_reason_reconnects,
                event.evidence["reconnects"].orEmpty().ifBlank { "0" },
            )
        AnomalyType.LATENCY_SHIFT ->
            stringResource(
                R.string.anomaly_reason_latency,
                event.evidence["latency_ms"].orEmpty().ifBlank { "0" },
                event.evidence["robust_z"].orEmpty().ifBlank { stringResource(R.string.statistics_no_data) },
            )
        AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH ->
            stringResource(
                R.string.anomaly_reason_privacy_route,
                event.evidence["route_share"].asPercentText(),
            )
    }

@Composable
private fun String?.asPercentText(): String =
    this
        ?.toFloatOrNull()
        ?.let(::formatPercent)
        ?: stringResource(R.string.statistics_no_data)

private fun metricIfPositive(
    label: String,
    value: Int,
    displayValue: String = value.toString(),
): Pair<String, String>? = value.takeIf { it > 0 }?.let { label to displayValue }

private fun metricIfPositive(
    label: String,
    value: Long,
    displayValue: String = value.toString(),
): Pair<String, String>? = value.takeIf { it > 0L }?.let { label to displayValue }

private fun appConnectionRows(
    packageName: String,
    events: List<NetworkActivityEvent>,
    resolver: TorGeoIpCountryResolver,
    ipInfo: com.foxhole.beta.core.model.IpInfo?,
): List<AppConnectionRow> =
    appConnectionEventRows(packageName, events, resolver, ipInfo)
        .groupBy { row -> row.remote to row.protocol }
        .map { (key, rows) ->
            val latest = rows.maxBy(AppConnectionRow::lastSeenAt)
            AppConnectionRow(
                remote = key.first,
                ipAddress = latest.ipAddress,
                countryCode = latest.countryCode,
                countryName = latest.countryName,
                city = latest.city,
                protocol = key.second,
                count = rows.size,
                bytes = rows.sumOf(AppConnectionRow::bytes),
                lastSeenAt = rows.maxOf(AppConnectionRow::lastSeenAt),
            )
        }
        .sortedWith(
            compareByDescending<AppConnectionRow> { row -> row.bytes }
                .thenByDescending { row -> row.count }
                .thenByDescending { row -> row.lastSeenAt },
        )

private fun appConnectionEventRows(
    packageName: String,
    events: List<NetworkActivityEvent>,
    resolver: TorGeoIpCountryResolver,
    ipInfo: com.foxhole.beta.core.model.IpInfo?,
): List<AppConnectionRow> =
    events
        .asSequence()
        .filter { event -> packageName in event.packageNames }
        .mapNotNull { event ->
            val remoteHost = event.remoteHost.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val remote =
                event.remotePort
                    ?.takeIf { port -> port in 1..65535 }
                    ?.let { port -> "$remoteHost:$port" }
                    ?: remoteHost
            val ipAddress = remoteHost.connectionHost()
            val countryCode =
                event.countryCode
                    ?: resolver.countryCodeForDestination(remoteHost)
                    ?: ipInfo?.takeIf { info ->
                        ipAddress == info.ip || ipAddress == info.ipv4 || ipAddress == info.ipv6
                    }?.countryCode
            val countryName = countryCode?.let(::countryDisplayName)
            val city =
                ipInfo
                    ?.takeIf { info -> ipAddress == info.ip || ipAddress == info.ipv4 || ipAddress == info.ipv6 }
                    ?.city
            AppConnectionRow(
                remote = remote,
                ipAddress = ipAddress,
                countryCode = countryCode,
                countryName = countryName,
                city = city,
                protocol = event.protocol.ifBlank { "?" },
                count = 1,
                bytes = event.totalBytes.coerceAtLeast(0L),
                lastSeenAt = event.timestampMs,
            )
        }
        .sortedByDescending(AppConnectionRow::lastSeenAt)
        .toList()

private fun trafficTimelineBuckets(
    samples: List<AppTrafficWindow>,
    range: StatisticsDisplayRange,
): List<TrafficTimelineBucket> {
    val now = System.currentTimeMillis()
    val policy =
        range.policy(
            firstAtMs = samples.minOfOrNull(AppTrafficWindow::startedAtMs) ?: now,
            nowMs = now,
        )
    val bucketMs = policy.bucketSizeMs
    val durationMs = range.durationMs ?: samples.durationForAllRange(now, bucketMs, policy.maxBuckets)
    val startAt = now - durationMs
    val bucketCount = (durationMs / bucketMs).toInt().coerceIn(1, policy.maxBuckets)
    val buckets =
        (0 until bucketCount).associate { index ->
            val startedAt = startAt + index * bucketMs
            startedAt to TrafficTimelineBucket(startedAtMs = startedAt, rxBytes = 0L, txBytes = 0L)
        }.toMutableMap()
    samples
        .asSequence()
        .filter { sample -> sample.startedAtMs >= startAt }
        .forEach { sample ->
            val bucketStart = startAt + ((sample.startedAtMs - startAt) / bucketMs) * bucketMs
            val current = buckets[bucketStart] ?: return@forEach
            buckets[bucketStart] =
                current.copy(
                    rxBytes = current.rxBytes + sample.rxBytes.coerceAtLeast(0L),
                    txBytes = current.txBytes + sample.txBytes.coerceAtLeast(0L),
                )
        }
    return buckets.values.sortedBy(TrafficTimelineBucket::startedAtMs)
}

private val StatisticsRetention.durationMs: Long?
    get() =
        when (this) {
            StatisticsRetention.WEEK -> 7L * 24L * 60L * 60L * 1000L
            StatisticsRetention.MONTH -> 31L * 24L * 60L * 60L * 1000L
            StatisticsRetention.MONTHS_3 -> 93L * 24L * 60L * 60L * 1000L
            StatisticsRetention.FOREVER -> null
        }

private val StatisticsDisplayRange.durationMs: Long?
    get() =
        when (this) {
            StatisticsDisplayRange.HOURS_24 -> 24L * 60L * 60L * 1000L
            StatisticsDisplayRange.WEEK -> 7L * 24L * 60L * 60L * 1000L
            StatisticsDisplayRange.MONTH -> 31L * 24L * 60L * 60L * 1000L
            StatisticsDisplayRange.ALL -> null
        }

private fun StatisticsDisplayRange.toStatisticsRetention(): StatisticsRetention =
    when (this) {
        StatisticsDisplayRange.HOURS_24 -> StatisticsRetention.WEEK
        StatisticsDisplayRange.WEEK -> StatisticsRetention.WEEK
        StatisticsDisplayRange.MONTH -> StatisticsRetention.MONTH
        StatisticsDisplayRange.ALL -> StatisticsRetention.FOREVER
    }

private fun StatisticsDisplayRange.toStatsRange(): com.foxhole.beta.core.statistics.StatsRange =
    when (this) {
        StatisticsDisplayRange.HOURS_24 -> com.foxhole.beta.core.statistics.StatsRange.HOURS_24
        StatisticsDisplayRange.WEEK -> com.foxhole.beta.core.statistics.StatsRange.DAYS_7
        StatisticsDisplayRange.MONTH -> com.foxhole.beta.core.statistics.StatsRange.DAYS_31
        StatisticsDisplayRange.ALL -> com.foxhole.beta.core.statistics.StatsRange.ALL
    }

private fun StatisticsDisplayRange.policy(
    firstAtMs: Long? = null,
    nowMs: Long = System.currentTimeMillis(),
): com.foxhole.beta.core.statistics.StatsRangePolicy =
    com.foxhole.beta.core.statistics.statsRangePolicy(
        range = toStatsRange(),
        firstAtMs = firstAtMs,
        nowMs = nowMs,
    )

private fun <T> List<T>.filterForDisplayRange(
    range: StatisticsDisplayRange,
    timestamp: (T) -> Long,
): List<T> {
    val cutoff = range.durationMs?.let { duration -> System.currentTimeMillis() - duration } ?: return this
    return filter { item -> timestamp(item) >= cutoff }
}

private fun timelineTicks(
    range: StatisticsDisplayRange,
    rangeStart: Long,
    rangeEnd: Long,
    nowLabel: String,
): List<com.foxhole.beta.core.statistics.ChartTick> {
    val tickCount = range.policy(rangeStart, rangeEnd).majorTickCount.coerceAtLeast(2)
    val duration = (rangeEnd - rangeStart).coerceAtLeast(1L)
    val formatter =
        when (range) {
            StatisticsDisplayRange.HOURS_24 -> null
            StatisticsDisplayRange.WEEK -> SimpleDateFormat("EEE", Locale.getDefault())
            StatisticsDisplayRange.MONTH,
            StatisticsDisplayRange.ALL,
            -> SimpleDateFormat("MMM d", Locale.getDefault())
        }
    return (0 until tickCount).map { index ->
        val timestamp =
            if (index == tickCount - 1) {
                rangeEnd
            } else {
                rangeStart + duration * index / (tickCount - 1)
            }
        val label =
            when {
                index == tickCount - 1 -> nowLabel
                range == StatisticsDisplayRange.HOURS_24 -> "-${24 - (24 * index / (tickCount - 1))}h"
                else -> formatter?.format(Date(timestamp)).orEmpty()
            }
        com.foxhole.beta.core.statistics.ChartTick(timestamp.toDouble(), label)
    }
}

private fun List<AppTrafficWindow>.durationForAllRange(
    now: Long,
    bucketMs: Long,
    maxBuckets: Int,
): Long {
    val first = minOfOrNull(AppTrafficWindow::startedAtMs) ?: return 24L * 60L * 60L * 1000L
    return (now - first)
        .coerceAtLeast(bucketMs)
        .coerceAtMost(bucketMs * maxBuckets)
}

private fun String.connectionHost(): String {
    val value = trim().removePrefix("/")
    return if (value.startsWith("[")) {
        value.substringAfter("[").substringBefore("]").ifBlank { value }
    } else {
        val colonCount = value.count { character -> character == ':' }
        when {
            colonCount == 1 -> value.substringBefore(":")
            colonCount > 1 -> value.substringBefore("%")
            else -> value
        }.ifBlank { value }
    }
}

private fun String.countryFlagEmoji(): String {
    val normalized = normalizedCountryCode(this) ?: return ""
    return normalized
        .map { character -> Character.toChars(0x1F1E6 + (character.code - 'A'.code)).concatToString() }
        .joinToString("")
}

private fun niceTimelineTrafficScale(maxBytes: Long): Long =
    niceTrafficScale(maxBytes)

private fun Profile.statisticsProtocolHints(): List<ProtocolHint> {
    val optionHints = protocolOptions.map(ProfileProtocolOption::protocolHint)
    return (optionHints + protocolHint).distinct()
}

private fun DnsSettings.adGuardFilteringEnabled(): Boolean =
    filteringEnabled && (blockAds || blockTrackers || blockAppTelemetry || blockMaliciousDomains)

private fun Profile.runtimeProtocolHint(): ProtocolHint =
    selectedProtocolOptionId
        ?.takeIf(String::isNotBlank)
        ?.let { selectedId -> protocolOptions.firstOrNull { option -> option.id == selectedId } }
        ?.protocolHint
        ?: protocolOptions.firstOrNull { option -> option.isSelected }?.protocolHint
        ?: protocolOptions.firstOrNull()?.protocolHint
        ?: protocolHint

@Composable
private fun rememberOneShotVisible(key: String): Boolean {
    var visible by rememberSaveable(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (!visible) {
            visible = true
        }
    }
    return visible
}

@Composable
private fun statisticsCountryChartColors(): List<Color> = chartCountryColors()

private fun niceTrafficScale(maxBytes: Long): Long {
    return com.foxhole.beta.core.statistics.niceBytesScale(maxBytes)
}

@Composable
private fun statisticsRetentionLabel(value: StatisticsRetention): String =
    stringResource(
        when (value) {
            StatisticsRetention.WEEK -> R.string.statistics_retention_week
            StatisticsRetention.MONTH -> R.string.statistics_retention_month
            StatisticsRetention.MONTHS_3 -> R.string.statistics_retention_months_3
            StatisticsRetention.FOREVER -> R.string.statistics_retention_forever
        },
    )

private fun StatisticsRetention.toStatisticsRange(): StatisticsRange =
    when (this) {
        StatisticsRetention.WEEK -> StatisticsRange.WEEK
        StatisticsRetention.MONTH -> StatisticsRange.MONTH
        StatisticsRetention.MONTHS_3 -> StatisticsRange.MONTHS_3
        StatisticsRetention.FOREVER -> StatisticsRange.FOREVER
    }

@Composable
private fun statisticsRefreshIntervalLabel(value: StatisticsRefreshInterval): String =
    pluralStringResource(
        R.plurals.statistics_refresh_interval_seconds,
        value.seconds,
        value.seconds,
    )

private fun protocolDisplayName(protocol: ProtocolHint): String =
    when (protocol) {
        ProtocolHint.HYSTERIA2 -> "Hysteria2"
        ProtocolHint.SING_BOX -> "Sing-box"
        ProtocolHint.UNKNOWN -> "Unknown"
        else -> protocol.name
    }

private fun transportLabel(transport: TransportProtocol): String =
    when (transport) {
        TransportProtocol.TCP -> "TCP"
        TransportProtocol.UDP -> "UDP"
        TransportProtocol.UNKNOWN -> "Unknown"
    }

private fun formatPercent(value: Float): String = "${(value.coerceIn(0f, 1f) * 100f).roundToInt()}%"

@Composable
private fun Long?.formatLatency(): String =
    this?.let { latency -> stringResource(R.string.statistics_latency_ms, latency) }
        ?: stringResource(R.string.statistics_no_data)

@Composable
private fun Long?.formatLastActivity(): String =
    this?.takeIf { timestamp -> timestamp > 0L }?.let { timestamp ->
        DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    } ?: stringResource(R.string.statistics_no_data)

private fun List<Long>.averageOrNull(): Long? =
    takeIf(List<Long>::isNotEmpty)
        ?.let { values -> values.average().roundToInt().toLong() }

private fun maxOfNotNull(vararg values: Long?): Long? =
    values.filterNotNull().maxOrNull()

private val COMPACT_PROTOCOL_GRID_WIDTH = 360.dp
private const val DONUT_ANIMATION_DURATION_MS = 700
private const val PROFILE_TRAFFIC_PREVIEW_LIMIT = 6
private const val STATISTICS_TOP_PREVIEW_LIMIT = 5
private const val APP_TRAFFIC_CHART_LIMIT = 10
private val DASHBOARD_DISPLAY_RANGES =
    listOf(
        StatisticsDisplayRange.HOURS_24,
        StatisticsDisplayRange.WEEK,
        StatisticsDisplayRange.MONTH,
    )
internal fun dashboardStatisticsDisplayRanges(): List<StatisticsDisplayRange> = DASHBOARD_DISPLAY_RANGES
private const val PROFILE_COMPARISON_MIN_ATTEMPTS = 2
