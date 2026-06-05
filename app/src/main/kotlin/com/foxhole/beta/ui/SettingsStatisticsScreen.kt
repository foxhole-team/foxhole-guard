package com.foxhole.beta.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AnomalyEvent
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.StatisticsMetric
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.dnsRuleSetFilteringEnabled

@Composable
@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")
fun StatisticsScreen(
    state: StatisticsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onStatisticsEnabledChanged: (Boolean) -> Unit,
    onStatisticsRetentionSelected: (StatisticsRetention) -> Unit,
    onStatisticsMetricEnabledChanged: (StatisticsMetric, Boolean) -> Unit,
    onAppTrafficStatsEnabledChanged: (Boolean) -> Unit,
    onAppTrafficUsageAccessConsentChanged: (Boolean) -> Unit,
    onNetworkActivityLoggingChanged: (Boolean) -> Unit,
    onOpenNetworkActivityLogSettings: () -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onClearUsage: () -> Unit,
) {
    DebugRecompositionCounter("StatisticsScreen")
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var retentionMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var allAppsVisible by rememberSaveable { mutableStateOf(false) }
    var allCountriesVisible by rememberSaveable { mutableStateOf(false) }
    var allAnomaliesVisible by rememberSaveable { mutableStateOf(false) }
    var selectedApp by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var clearConfirmVisible by rememberSaveable { mutableStateOf(false) }
    var appStatsEnablePendingUsageAccess by rememberSaveable { mutableStateOf(false) }
    var usageAccessConsentVisible by rememberSaveable { mutableStateOf(false) }
    var allAppRowsSnapshot by remember { mutableStateOf<List<AppTrafficRow>>(emptyList()) }
    var appTrafficRange by rememberSaveable { mutableStateOf(StatisticsDisplayRange.HOURS_24) }
    var dnsRange by rememberSaveable { mutableStateOf(StatisticsDisplayRange.HOURS_24) }
    var anomalyRange by rememberSaveable { mutableStateOf(StatisticsDisplayRange.HOURS_24) }
    val statisticsSettings = state.settings.statistics
    val context = LocalContext.current
    val retention = state.settings.statistics.retention
    val dashboard = state.statisticsDashboard
    val dashboardNowMs = dashboard.nowMs
    val statisticsNowMs = dashboardNowMs.toStatisticsUiNowBucket()
    val statistics = dashboard.statistics
    val appRows = dashboard.appRows
    val topApps = remember(appRows) { appRows.take(APP_TRAFFIC_CHART_LIMIT) }
    val visibleAllAppRows = allAppRowsSnapshot.takeIf(List<AppTrafficRow>::isNotEmpty) ?: appRows
    val countryRows = dashboard.countryRows
    val topCountryLimit =
        if (countryRows.size > STATISTICS_TOP_PREVIEW_LIMIT) {
            STATISTICS_TOP_PREVIEW_LIMIT + 1
        } else {
            STATISTICS_TOP_PREVIEW_LIMIT
        }
    val topCountryRows = remember(countryRows, topCountryLimit) { countryRows.take(topCountryLimit) }
    val dnsSummary = remember(state.trafficWindows, appRows, dnsRange, state.settings.dns, statisticsNowMs) {
        dnsProtectionSummary(
            trafficWindows = state.trafficWindows,
            appRows = appRows,
            retention = dnsRange.toStatisticsRetention(),
            displayRange = dnsRange,
            dnsSettings = state.settings.dns,
            nowMs = statisticsNowMs,
        )
    }
    val anomalyEventsForRange = remember(state.anomalyEvents, anomalyRange, statisticsNowMs) {
        state.anomalyEvents.filterForDisplayRange(anomalyRange, statisticsNowMs, AnomalyEvent::createdAtMs)
    }
    val appSamplesForRange = remember(state.appTrafficWindows, appTrafficRange, statisticsNowMs) {
        state.appTrafficWindows.filterForDisplayRange(appTrafficRange, statisticsNowMs, AppTrafficWindow::startedAtMs)
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
    val dnsFilteringAvailable = state.settings.dns.dnsRuleSetFilteringEnabled()
    val appStatsEnabled =
        statisticsSettings.enabled &&
            statisticsSettings.appTrafficEnabled &&
            appStatsSwitchChecked &&
            state.settings.appTrafficUsageAccessConsent &&
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
        tag = "statistics_settings_screen",
        actions = {
            IconButton(onClick = { settingsVisible = true }) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.statistics_settings_content_description),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        if (!statisticsSettings.enabled) {
            item(key = "statistics-disabled") {
                StatisticsDisabledState(onEnable = { onStatisticsEnabledChanged(true) })
            }
        } else {
            item(key = "statistics-overview", contentType = "statistics-card") {
                StatisticsOverviewHeroCard(
                    statistics = statistics,
                    dnsSummary = dnsSummary,
                    firewallEnabled = firewallEnabled,
                )
            }
            if (statisticsSettings.appTrafficEnabled && appStatsSwitchChecked) {
                item(key = "app-statistics", contentType = "statistics-card") {
                    AppTrafficStatisticsCard(
                        rows = topApps,
                        samples = appSamplesForRange,
                        nowMs = statisticsNowMs,
                        allRowsCount = appRows.size,
                        enabled = appStatsEnabled,
                        usageAccessGranted = usageAccessGranted,
                        range = appTrafficRange,
                        onRangeSelected = { appTrafficRange = it },
                        onOpenUsageAccess = { openUsageAccessSettings(context) },
                        onShowAll = {
                            allAppRowsSnapshot = appRows
                            allAppsVisible = true
                        },
                        onRowClick = { row -> selectedApp = row.packageName },
                    )
                }
            }
            if (statisticsSettings.profileTrafficEnabled) {
                item(key = "profile-traffic", contentType = "statistics-card") {
                    ProfileTrafficOverviewCard(
                        statistics = statistics,
                        state = state,
                        onClear = { clearConfirmVisible = true },
                        onProfileClick = { profileId -> selectedProfileId = profileId },
                    )
                }
            }
            if (statisticsSettings.dnsFilteringEnabled && dnsFilteringAvailable) {
                item(key = "dns-protection", contentType = "statistics-card") {
                    DnsProtectionCard(
                        summary = dnsSummary,
                        range = dnsRange,
                        onRangeSelected = { dnsRange = it },
                    )
                }
            }
            if (shouldShowCountryTrafficCard(statisticsSettings)) {
                item(key = "country-traffic", contentType = "statistics-card") {
                    CountryTrafficCard(
                        rows = topCountryRows,
                        totalRowsCount = countryRows.size,
                        enabled = state.settings.statistics.countryTrafficEnabled && firewallEnabled,
                        networkActivityLoggingEnabled = state.settings.expert.networkActivityLogging,
                        onEnableFirewall = { onFirewallEnabledChanged(true) },
                        onEnableNetworkActivityLogging = { onNetworkActivityLoggingChanged(true) },
                        onShowAll = { allCountriesVisible = true },
                    )
                }
            }
            if (statisticsSettings.anomalyMetricsEnabled && anomalyEventsForRange.isNotEmpty()) {
                item(key = "anomalies", contentType = "statistics-card") {
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
            if (statisticsSettings.vpnProtocolsEnabled && statistics.vpnProtocols.isNotEmpty()) {
                item(key = "protocols", contentType = "statistics-card") {
                    ProtocolStatisticsSection(items = statistics.vpnProtocols)
                }
            }
            if (statisticsSettings.transportsEnabled && statistics.transports.isNotEmpty()) {
                item(key = "transports", contentType = "statistics-card") {
                    TransportStatisticsSection(items = statistics.transports)
                }
            }
            if (statisticsSettings.profileComparisonsEnabled && statistics.profileComparisons.isNotEmpty()) {
                item(key = "profile-comparisons", contentType = "statistics-card") {
                    ProfileComparisonsSection(items = statistics.profileComparisons)
                }
            }
        }
    }

    if (settingsVisible) {
        StatisticsSettingsDialog(
            state = state,
            statisticsSettings = statisticsSettings,
            appStatsSwitchChecked = appStatsSwitchChecked,
            usageAccessGranted = usageAccessGranted,
            dnsFilteringAvailable = dnsFilteringAvailable,
            retentionMenuExpanded = retentionMenuExpanded,
            actions = StatisticsSettingsDialogActions(
                onRetentionMenuExpandedChange = { retentionMenuExpanded = it },
                onStatisticsEnabledChanged = onStatisticsEnabledChanged,
                onStatisticsRetentionSelected = onStatisticsRetentionSelected,
                onStatisticsMetricEnabledChanged = onStatisticsMetricEnabledChanged,
                onAppTrafficStatsEnabledChanged = onAppTrafficStatsEnabledChanged,
                onUsageAccessRequired = {
                    appStatsEnablePendingUsageAccess = true
                    usageAccessConsentVisible = true
                },
                onUsageAccessCleared = { appStatsEnablePendingUsageAccess = false },
                onDismiss = { settingsVisible = false },
            ),
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
            title = { Text(stringResource(R.string.statistics_app_detail_title)) },
            text = {
                AppTrafficDetail(
                    row = selectedAppRow,
                    samples = state.appTrafficWindows,
                    networkActivityEvents = state.networkActivityEvents,
                    ipInfo = state.ipInfo,
                    networkActivityLoggingEnabled = state.settings.expert.networkActivityLogging,
                    showPrivateNetworkDetails = !state.settings.expert.sanitizeNetworkActivityPrivateData,
                    onOpenNetworkActivityLogSettings = {
                        selectedApp = null
                        onOpenNetworkActivityLogSettings()
                    },
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
                    items(
                        items = visibleAllAppRows,
                        key = AppTrafficRow::packageName,
                        contentType = { "app-traffic-row" },
                    ) { row ->
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

    if (usageAccessConsentVisible) {
        UsageAccessConsentDialog(
            onDismiss = {
                usageAccessConsentVisible = false
                appStatsEnablePendingUsageAccess = false
            },
            onConfirm = {
                usageAccessConsentVisible = false
                onAppTrafficUsageAccessConsentChanged(true)
                openUsageAccessSettings(context)
            },
        )
    }
}

internal fun shouldShowCountryTrafficCard(statisticsSettings: com.foxhole.beta.core.model.StatisticsSettings): Boolean =
    statisticsSettings.countryTrafficEnabled

private const val STATISTICS_UI_NOW_BUCKET_MS = 60_000L

private fun Long.toStatisticsUiNowBucket(): Long = (this / STATISTICS_UI_NOW_BUCKET_MS) * STATISTICS_UI_NOW_BUCKET_MS
