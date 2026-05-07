package com.foxhole.beta.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.beta.R
import com.foxhole.beta.core.anomaly.UsageStatsAccess
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.AnomalyEvent
import com.foxhole.beta.core.model.AnomalySeverity
import com.foxhole.beta.core.model.AnomalyType
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.InstalledAppOption
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
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.StatisticsUiState
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapUiState
import com.foxhole.beta.core.model.TransportProtocol
import com.foxhole.beta.core.model.TransportStatisticsUiItem
import com.foxhole.beta.core.model.TrafficWindow
import com.foxhole.beta.ui.theme.LocalFoxholeSemanticColors
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun StatisticsScreen(
    state: SettingsRouteUiState,
    trafficMapState: TrafficMapUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onStatisticsEnabledChanged: (Boolean) -> Unit,
    onStatisticsRetentionSelected: (StatisticsRetention) -> Unit,
    onStatisticsMetricEnabledChanged: (StatisticsMetric, Boolean) -> Unit,
    onAppTrafficStatsEnabledChanged: (Boolean) -> Unit,
    onClearUsage: () -> Unit,
) {
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var retentionMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var allAppsVisible by rememberSaveable { mutableStateOf(false) }
    var allCountriesVisible by rememberSaveable { mutableStateOf(false) }
    var selectedApp by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var clearConfirmVisible by rememberSaveable { mutableStateOf(false) }
    val statisticsSettings = state.settings.statistics
    val context = LocalContext.current
    val retention = StatisticsRetention.FOREVER
    val statistics =
        remember(state.settings, state.profiles, state.activeProfile, state.traffic, retention) {
            statisticsUiState(state = state, retention = retention)
        }
    val appRows =
        remember(state.appTrafficWindows, state.installedApps, state.anomalyEvents, retention) {
            appTrafficRows(
                samples = state.appTrafficWindows,
                installedApps = state.installedApps,
                anomalyEvents = state.anomalyEvents,
                retention = retention,
            )
        }
    val topApps = appRows.take(10)
    val countryRows =
        remember(state.trafficWindows, trafficMapState.destinations) {
            countryTrafficRows(
                trafficWindows = state.trafficWindows,
                liveDestinations = trafficMapState.destinations,
            )
        }
    val topCountryRows = countryRows.take(10)
    val appStatsSwitchChecked = state.settings.appTrafficStatsEnabled
    val usageAccessGranted = UsageStatsAccess.isGranted(context)
    val appStatsEnabled = statisticsSettings.enabled && statisticsSettings.appTrafficEnabled && appStatsSwitchChecked && usageAccessGranted
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
            if (statisticsSettings.vpnProtocolsEnabled) {
                item(key = "protocols") {
                    ProtocolStatisticsSection(items = statistics.vpnProtocols)
                }
            }
            if (statisticsSettings.profileComparisonsEnabled && statistics.profileComparisons.isNotEmpty()) {
                item(key = "profile-comparisons") {
                    ProfileComparisonsSection(items = statistics.profileComparisons)
                }
            }
            if (statisticsSettings.transportsEnabled) {
                item(key = "transports") {
                    TransportStatisticsSection(items = statistics.transports)
                }
            }
            if (statisticsSettings.appTrafficEnabled && appStatsSwitchChecked) {
                item(key = "app-statistics") {
                    AppTrafficStatisticsCard(
                        rows = topApps,
                        allRowsCount = appRows.size,
                        enabled = appStatsEnabled,
                        runtimeActive = appStatsEnabled && state.traffic.available,
                        usageAccessGranted = usageAccessGranted,
                        onOpenUsageAccess = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                        onShowAll = { allAppsVisible = true },
                        onRowClick = { row -> selectedApp = row.packageName },
                    )
                }
            }
            if (statisticsSettings.countryTrafficEnabled) {
                item(key = "country-traffic") {
                    CountryTrafficCard(
                        state = trafficMapState,
                        rows = topCountryRows,
                        totalRowsCount = countryRows.size,
                        enabled = state.settings.statistics.countryTrafficEnabled,
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
                    }
                    SettingsControlGroup {
                        SettingSwitchRow(
                            title = stringResource(R.string.app_statistics_enabled_title),
                            checked = appStatsSwitchChecked,
                            summary = stringResource(R.string.app_statistics_enabled_summary),
                            leadingIcon = Icons.Outlined.Apps,
                            onCheckedChange = onAppTrafficStatsEnabledChanged,
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
                    diagnosticEntries = state.diagnosticEntries,
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
                        AppTrafficRowView(row = row, onClick = { selectedApp = row.packageName })
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
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
        items.sortedByDescending(ProfileTrafficUiItem::totalBytes).take(ProfileTrafficPreviewLimit).forEachIndexed { index, item ->
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
            if (index != minOf(items.size, ProfileTrafficPreviewLimit) - 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
            }
        }
    }
}

@Composable
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
            SectionTitle(
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
                LoadingStatisticsBlock(
                    text =
                        stringResource(
                            if (state.isAvailable) {
                                R.string.statistics_loading
                            } else {
                                R.string.traffic_map_waiting_connections
                            },
                        ),
                )
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
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val maxBytes = points.maxOfOrNull(CountryTrafficUiRow::bytes)?.coerceAtLeast(1L) ?: 1L
    val visible = rememberOneShotVisible("countries")
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = DonutAnimationDurationMs, easing = FastOutSlowInEasing),
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
                    .weight(1f),
        ) {
            val chartTop = 8.dp.toPx()
            val chartBottom = size.height - 4.dp.toPx()
            val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)
            drawLine(
                color = gridColor,
                start = Offset(0f, chartTop),
                end = Offset(size.width, chartTop),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            val slotWidth = size.width / points.size.coerceAtLeast(1).toFloat()
            val barWidth = (slotWidth * 0.48f).coerceAtMost(18.dp.toPx())
            points.forEachIndexed { index, point ->
                val normalized = point.bytes.toFloat() / maxBytes.toFloat()
                val barHeight = (chartHeight * normalized * progress).coerceAtLeast(if (point.bytes > 0L) 2f else 0f)
                val center = slotWidth * index + slotWidth / 2f
                drawRoundRect(
                    color = colors[index % colors.size],
                    topLeft = Offset(center - barWidth / 2f, chartBottom - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
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
private fun LoadingStatisticsBlock(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Canvas(modifier = Modifier.size(9.dp)) {
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
                val columns = if (maxWidth >= CompactProtocolGridWidth) 4 else 2
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
            Box(contentAlignment = Alignment.Center) {
                AnimatedDonutChart(
                    successRate = item.successRate,
                    errorRate = item.errorRate,
                    visible = visible,
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
    modifier: Modifier = Modifier,
) {
    val semanticColors = LocalFoxholeSemanticColors.current
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = DonutAnimationDurationMs, easing = FastOutSlowInEasing),
        label = "donut-progress",
    )
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
        val successSweep = 360f * successRate.coerceIn(0f, 1f) * progress
        val errorSweep = 360f * errorRate.coerceIn(0f, 1f) * progress
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
        )
        drawArc(
            color = semanticColors.success,
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
    val connectorColor = MaterialTheme.colorScheme.outline
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
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
                Canvas(
                    modifier = Modifier
                        .width(30.dp)
                        .height(1.dp),
                ) {
                    drawLine(
                        color = connectorColor,
                        start = Offset.Zero,
                        end = Offset(size.width, 0f),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 7f)),
                    )
                }
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
    val semanticColors = LocalFoxholeSemanticColors.current
    val borderColor =
        when {
            stable -> semanticColors.success.copy(alpha = 0.62f)
            problematic -> MaterialTheme.colorScheme.error.copy(alpha = 0.58f)
            else -> MaterialTheme.colorScheme.outlineVariant
        }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.44f),
        border = BorderStroke(1.dp, borderColor),
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
                color =
                    if (stable) {
                        semanticColors.success
                    } else if (problematic) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
    val semanticColors = LocalFoxholeSemanticColors.current
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
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
                .height(10.dp),
        ) {
            val radius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
            drawRoundRect(
                color = trackColor,
                cornerRadius = radius,
            )
            drawRoundRect(
                color = semanticColors.success,
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
private fun AppTrafficStatisticsCard(
    rows: List<AppTrafficRow>,
    allRowsCount: Int,
    enabled: Boolean,
    runtimeActive: Boolean,
    usageAccessGranted: Boolean,
    onOpenUsageAccess: () -> Unit,
    onShowAll: () -> Unit,
    onRowClick: (AppTrafficRow) -> Unit,
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
            SectionTitle(
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.statistics_apps_top_title),
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
                LoadingStatisticsBlock(
                    text =
                        stringResource(
                            if (runtimeActive) {
                                R.string.statistics_loading
                            } else {
                                R.string.app_statistics_empty
                            },
                        ),
                )
            } else {
                TrafficBarChart(rows = rows)
                ChartLegend()
                AppTrafficTable(
                    rows = rows,
                    emptyText = stringResource(R.string.app_statistics_empty),
                    onRowClick = onRowClick,
                )
                if (allRowsCount > rows.size) {
                    TextButton(onClick = onShowAll) {
                        Text(stringResource(R.string.show_all_label))
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficBarChart(rows: List<AppTrafficRow>) {
    val semanticColors = LocalFoxholeSemanticColors.current
    val txColor = MaterialTheme.colorScheme.primary
    val rxColor = semanticColors.success
    val context = LocalContext.current
    val maxBytes = rows.maxOfOrNull { max(it.txBytes, it.rxBytes) }?.coerceAtLeast(100L * 1024L * 1024L) ?: (100L * 1024L * 1024L)
    val yMax = niceTrafficScale(maxBytes)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = formatBytes(context, yMax),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
        )
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(180.dp),
        ) {
            val chartTop = 8.dp.toPx()
            val chartBottom = size.height - 20.dp.toPx()
            drawLine(
                color = gridColor,
                start = Offset(0f, chartTop),
                end = Offset(size.width, chartTop),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            val visibleRows = rows.take(10)
            val slotWidth = size.width / visibleRows.size.coerceAtLeast(1).toFloat()
            val barWidth = (slotWidth * 0.24f).coerceAtMost(12.dp.toPx())
            visibleRows.forEachIndexed { index, row ->
                val center = slotWidth * index + slotWidth / 2f
                fun drawBar(value: Long, color: Color, xOffset: Float) {
                    val height = ((chartBottom - chartTop) * (value.toFloat() / yMax.toFloat())).coerceAtLeast(if (value > 0) 2f else 0f)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(center + xOffset - barWidth / 2f, chartBottom - height),
                        size = Size(barWidth, height),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    )
                }
                drawBar(row.txBytes, txColor, -barWidth * 0.65f)
                drawBar(row.rxBytes, rxColor, barWidth * 0.65f)
            }
        }
    }
}

@Composable
private fun ChartLegend() {
    val semanticColors = LocalFoxholeSemanticColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendItem(color = MaterialTheme.colorScheme.primary, text = stringResource(R.string.traffic_sent))
        LegendItem(color = semanticColors.success, text = stringResource(R.string.traffic_received))
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).padding(1.dp)) {
            Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color) }
        }
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AppTrafficTable(
    rows: List<AppTrafficRow>,
    emptyText: String,
    onRowClick: (AppTrafficRow) -> Unit,
) {
    if (rows.isEmpty()) {
        Text(text = emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column {
        rows.forEachIndexed { index, row ->
            AppTrafficRowView(row = row, onClick = { onRowClick(row) })
            if (index != rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
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
            Text(formatBytes(context, tx), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(formatBytes(context, rx), style = MaterialTheme.typography.labelMedium, color = semanticColors.success)
        }
        Text(formatBytes(context, tx + rx), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatisticsSectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
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
            SectionTitle(icon = icon, title = title)
            content()
        }
    }
}

@Composable
private fun SectionTitle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
) {
    Row(
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
        summaryMaxLines = 2,
    )
}

@Composable
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
                listOf(
                    stringResource(R.string.statistics_total_traffic) to formatBytes(context, detail.totalBytes),
                    stringResource(R.string.statistics_vpn_sessions) to detail.totalAttempts.toString(),
                    stringResource(R.string.statistics_successful_connections) to detail.successCount.toString(),
                    stringResource(R.string.statistics_errors) to detail.failureCount.toString(),
                    stringResource(R.string.statistics_average_latency) to detail.avgLatencyMs.formatLatency(),
                    stringResource(R.string.statistics_min_latency) to detail.minLatencyMs.formatLatency(),
                    stringResource(R.string.statistics_max_latency) to detail.maxLatencyMs.formatLatency(),
                    stringResource(R.string.statistics_last_activity) to detail.lastActivityAt.formatLastActivity(),
                ),
        )
        if (detail.protocols.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_profile_protocols_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                detail.protocols.forEach { protocol ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = protocolDisplayName(protocol.protocolHint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text =
                                        stringResource(
                                            R.string.statistics_profile_protocol_metrics,
                                            formatPercent(protocol.successRate),
                                            formatPercent(protocol.errorRate),
                                            protocol.avgLatencyMs.formatLatency(),
                                        ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun AppTrafficDetail(
    row: AppTrafficRow,
    samples: List<AppTrafficWindow>,
    diagnosticEntries: List<DiagnosticEntry>,
) {
    val context = LocalContext.current
    val appSamples = samples.filter { sample -> sample.packageName == row.packageName }
    val connectionRows = remember(row.packageName, diagnosticEntries) {
        appConnectionRows(row.packageName, diagnosticEntries)
    }
    Column(
        modifier = Modifier
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DetailMetricGrid(
            metrics =
                listOf(
                    stringResource(R.string.home_total_label) to formatBytes(context, row.totalBytes),
                    stringResource(R.string.traffic_received) to formatBytes(context, row.rxBytes),
                    stringResource(R.string.traffic_sent) to formatBytes(context, row.txBytes),
                    stringResource(R.string.statistics_app_samples) to appSamples.size.toString(),
                    stringResource(R.string.statistics_last_activity) to appSamples.maxOfOrNull(AppTrafficWindow::startedAtMs).formatLastActivity(),
                ),
        )
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
            connectionRows.take(10).forEach { connection ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = connection.remote,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOf(connection.protocol, connection.lastSeenAt.formatLastActivity()).joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = connection.count.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        if (appSamples.isNotEmpty()) {
            AppTrafficMiniChart(samples = appSamples.takeLast(24))
            Text(
                text = stringResource(R.string.statistics_app_detail_top_samples),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            appSamples
                .sortedByDescending { sample -> sample.rxBytes + sample.txBytes }
                .take(5)
                .forEach { sample ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = sample.startedAtMs.formatLastActivity(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatBytes(context, sample.rxBytes + sample.txBytes),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
        }
    }
}

@Composable
private fun AppTrafficMiniChart(samples: List<AppTrafficWindow>) {
    val semanticColors = LocalFoxholeSemanticColors.current
    val txColor = MaterialTheme.colorScheme.primary
    val rxColor = semanticColors.success
    val maxBytes = samples.maxOfOrNull { sample -> max(sample.rxBytes, sample.txBytes) }?.coerceAtLeast(1L) ?: 1L
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val step = size.width / (samples.size - 1).coerceAtLeast(1).toFloat()
        fun point(index: Int, value: Long): Offset {
            val ratio = value.toFloat() / maxBytes.toFloat()
            return Offset(
                x = index * step,
                y = size.height - (size.height * ratio.coerceIn(0f, 1f)),
            )
        }
        samples.zipWithNext().forEachIndexed { index, pair ->
            drawLine(
                color = rxColor,
                start = point(index, pair.first.rxBytes),
                end = point(index + 1, pair.second.rxBytes),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = txColor,
                start = point(index, pair.first.txBytes),
                end = point(index + 1, pair.second.txBytes),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun DetailMetricGrid(metrics: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { (label, value) ->
                    MetricTile(label = label, value = value, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private data class AppTrafficRow(
    val packageName: String,
    val label: String,
    val txBytes: Long,
    val rxBytes: Long,
    val badges: Set<AppAnomalyBadge>,
) {
    val totalBytes: Long get() = txBytes + rxBytes
}

private data class CountryTrafficUiRow(
    val countryCode: String,
    val label: String,
    val bytes: Long,
    val sessions: Int,
)

private enum class AppAnomalyBadge {
    NORMAL,
    UNUSUAL,
    HIGH_UPLOAD,
    NEW_ROUTE,
    BACKGROUND,
}

private data class AppConnectionRow(
    val remote: String,
    val protocol: String,
    val count: Int,
    val lastSeenAt: Long,
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

private fun profileStatisticsDetail(
    state: SettingsRouteUiState,
    item: ProfileTrafficUiItem,
): ProfileStatisticsDetailModel {
    val profile = state.profiles.firstOrNull { profile -> profile.id == item.profileId }
    val options = profile?.protocolOptions.orEmpty().associateBy(ProfileProtocolOption::id)
    val preference = state.settings.smartProfilePreferences.firstOrNull { preference -> preference.profileId == item.profileId }
    val protocolDetails =
        preference
            ?.protocolMemories
            .orEmpty()
            .mapNotNull { memory ->
                val protocol = options[memory.optionId]?.protocolHint ?: profile?.protocolHint ?: item.protocolHint
                if (protocol == ProtocolHint.UNKNOWN) {
                    return@mapNotNull null
                }
                val latency = memory.lastLatencyMs?.takeIf { value -> value > 0L }
                val lastUsed =
                    maxOfNotNull(
                        memory.lastSuccessAt,
                        memory.lastFailureAt,
                        memory.lastValidatedAt,
                        memory.lastTrafficAt,
                    )
                val trafficForProtocol =
                    if (protocol == item.protocolHint || item.protocolHint == ProtocolHint.UNKNOWN) {
                        item
                    } else {
                        null
                    }
                ProfileProtocolDetail(
                    protocolHint = protocol,
                    successCount = memory.successCount.coerceAtLeast(0),
                    failureCount = memory.failureCount.coerceAtLeast(0),
                    rxBytes = trafficForProtocol?.rxBytes ?: 0L,
                    txBytes = trafficForProtocol?.txBytes ?: 0L,
                    avgLatencyMs = latency,
                    minLatencyMs = latency,
                    maxLatencyMs = latency,
                    lastUsedAt = lastUsed,
                )
            }
            .filter { detail -> detail.totalAttempts > 0 || detail.totalBytes > 0L || detail.lastUsedAt != null }
            .sortedWith(
                compareByDescending<ProfileProtocolDetail> { detail -> detail.totalBytes }
                    .thenByDescending { detail -> detail.lastUsedAt ?: 0L },
            )
    val fallbackProtocol =
        item.protocolHint
            .takeIf { hint -> hint != ProtocolHint.UNKNOWN }
            ?: profile?.runtimeProtocolHint()
            ?: ProtocolHint.UNKNOWN
    val visibleProtocolDetails =
        protocolDetails.takeIf(List<ProfileProtocolDetail>::isNotEmpty)
            ?: listOf(
                ProfileProtocolDetail(
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

private fun statisticsUiState(
    state: SettingsRouteUiState,
    retention: StatisticsRetention,
): StatisticsUiState {
    val profileTraffic = profileTrafficItems(state)
    val protocolStats = protocolStatistics(state, profileTraffic)
    val total = overallStatistics(profileTraffic, protocolStats, state)
    return StatisticsUiState(
        range = retention.toStatisticsRange(),
        extendedMode =
            state.settings.statistics.enabled &&
                state.settings.statistics.appTrafficEnabled &&
                state.settings.appTrafficStatsEnabled &&
                state.settings.expert.firewallEnabled,
        profileTraffic = profileTraffic,
        total = total,
        vpnProtocols = protocolStats,
        profileComparisons = profileComparisons(state, profileTraffic),
        transports = transportStatistics(state.profiles, protocolStats),
    )
}

private fun profileTrafficItems(state: SettingsRouteUiState): List<ProfileTrafficUiItem> {
    val totals =
        state.settings.profileTrafficTotals
            .associateBy { total -> total.profileId }
            .mapValues { (_, total) ->
                ProfileTrafficUiItem(
                    profileId = total.profileId,
                    profileName = total.profileName,
                    protocolHint = total.protocolHint,
                    rxBytes = total.rxTotalBytes,
                    txBytes = total.txTotalBytes,
                    updatedAt = total.updatedAt,
                )
            }
            .toMutableMap()
    val activeProfile = state.activeProfile
    val liveTraffic = state.traffic
    if (activeProfile != null && (liveTraffic.rxTotalBytes > 0L || liveTraffic.txTotalBytes > 0L)) {
        val stored = totals[activeProfile.id]
        totals[activeProfile.id] =
            ProfileTrafficUiItem(
                profileId = activeProfile.id,
                profileName = activeProfile.name,
                protocolHint = activeProfile.runtimeProtocolHint(),
                rxBytes = (stored?.rxBytes ?: 0L) + liveTraffic.rxTotalBytes,
                txBytes = (stored?.txBytes ?: 0L) + liveTraffic.txTotalBytes,
                updatedAt = maxOf(stored?.updatedAt ?: 0L, liveTraffic.sampledAt),
            )
    }
    return totals.values.sortedByDescending(ProfileTrafficUiItem::updatedAt)
}

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
    val trafficByProfileId = profileTraffic.associateBy(ProfileTrafficUiItem::profileId)
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
    state.profiles.forEach { profile ->
        val traffic = trafficByProfileId[profile.id] ?: return@forEach
        val protocol = traffic.protocolHint.takeIf { hint -> hint != ProtocolHint.UNKNOWN } ?: return@forEach
        comparisonByProtocol
            .getOrPut(protocol) { linkedMapOf() }
            .getOrPut(profile.id) { ComparisonAccumulator(profile.id, profile.name) }
            .addTraffic(traffic)
    }
    return comparisonByProtocol.mapNotNull { (protocol, profileMap) ->
        val candidates =
            profileMap.values
                .map(ComparisonAccumulator::toSide)
                .filter { side -> side.totalAttempts >= ProfileComparisonMinAttempts }
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

private fun transportStatistics(
    profiles: List<Profile>,
    protocolStats: List<ProtocolStatisticsUiItem>,
): List<TransportStatisticsUiItem> {
    val transportByProtocol =
        ProtocolHint.entries.associateWith { protocol ->
            profiles.transportForProtocol(protocol)
        }
    val accumulators = linkedMapOf<TransportProtocol, ProtocolAccumulator>()
    protocolStats.forEach { protocol ->
        val transport = transportByProtocol[protocol.protocol] ?: TransportProtocol.UNKNOWN
        val accumulator = accumulators.getOrPut(transport) { ProtocolAccumulator() }
        accumulator.successCount += protocol.successCount
        accumulator.failureCount += protocol.failureCount
        accumulator.rxBytes += protocol.rxBytes
        accumulator.txBytes += protocol.txBytes
        protocol.avgLatencyMs?.let(accumulator.latencies::add)
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

private fun appTrafficRows(
    samples: List<AppTrafficWindow>,
    installedApps: List<InstalledAppOption>,
    anomalyEvents: List<AnomalyEvent>,
    retention: StatisticsRetention,
): List<AppTrafficRow> {
    val labels = installedApps.associate { it.packageName to it.label }
    val cutoff = retention.durationMs?.let { System.currentTimeMillis() - it }
    val badgesByPackage =
        anomalyEvents
            .groupBy { event -> event.packageName }
            .mapNotNull { (packageName, events) -> packageName?.let { it to anomalyBadgesFor(events) } }
            .toMap()
    return samples
        .asSequence()
        .filter { sample -> cutoff == null || sample.startedAtMs >= cutoff }
        .groupBy(AppTrafficWindow::packageName)
        .map { (packageName, packageSamples) ->
            AppTrafficRow(
                packageName = packageName,
                label = labels[packageName] ?: packageName,
                txBytes = packageSamples.sumOf(AppTrafficWindow::txBytes),
                rxBytes = packageSamples.sumOf(AppTrafficWindow::rxBytes),
                badges = badgesByPackage[packageName] ?: setOf(AppAnomalyBadge.NORMAL),
            )
        }
        .filter { row -> row.totalBytes > 0L }
        .sortedWith(
            compareByDescending<AppTrafficRow> { it.totalBytes }
                .thenBy { it.label.lowercase(Locale.getDefault()) },
        )
}

private fun countryTrafficRows(
    trafficWindows: List<TrafficWindow>,
    liveDestinations: List<TrafficMapPoint>,
): List<CountryTrafficUiRow> {
    val bytesByCountry = linkedMapOf<String, Long>()
    val sessionsByCountry = linkedMapOf<String, Int>()
    trafficWindows.forEach { window ->
        window.destinationCountries.forEach { (countryCode, bytes) ->
            val normalized = normalizedCountryCode(countryCode) ?: return@forEach
            bytesByCountry[normalized] = (bytesByCountry[normalized] ?: 0L) + bytes.coerceAtLeast(0L)
            if (bytes > 0L) {
                sessionsByCountry[normalized] = (sessionsByCountry[normalized] ?: 0) + 1
            }
        }
    }
    if (bytesByCountry.isEmpty()) {
        liveDestinations.forEach { point ->
            val normalized = normalizedCountryCode(point.countryCode) ?: return@forEach
            bytesByCountry[normalized] = (bytesByCountry[normalized] ?: 0L) + point.bytes.coerceAtLeast(0L)
            sessionsByCountry[normalized] = (sessionsByCountry[normalized] ?: 0) + point.connections.coerceAtLeast(0)
        }
    }
    val labelsByCountry =
        liveDestinations.associate { point ->
            point.countryCode.uppercase(Locale.US) to point.label
        }
    return bytesByCountry
        .map { (countryCode, bytes) ->
            CountryTrafficUiRow(
                countryCode = countryCode,
                label = labelsByCountry[countryCode] ?: countryDisplayName(countryCode),
                bytes = bytes,
                sessions = sessionsByCountry[countryCode]?.coerceAtLeast(1) ?: 1,
            )
        }
        .filter { row -> row.bytes > 0L }
        .sortedWith(
            compareByDescending<CountryTrafficUiRow> { row -> row.bytes }
                .thenByDescending { row -> row.sessions }
                .thenBy { row -> row.countryCode },
        )
}

private fun normalizedCountryCode(countryCode: String?): String? =
    countryCode
        ?.trim()
        ?.uppercase(Locale.US)
        ?.takeIf { code -> code.length == 2 && code.all { character -> character in 'A'..'Z' } }

private fun countryDisplayName(countryCode: String): String =
    Locale.Builder()
        .setRegion(countryCode)
        .build()
        .displayCountry
        .takeIf(String::isNotBlank)
        ?: countryCode

private fun anomalyBadgesFor(events: List<AnomalyEvent>): Set<AppAnomalyBadge> {
    val badges =
        events
            .flatMap { event ->
                when (event.type) {
                    AnomalyType.APP_UPLOAD_SPIKE -> listOf(AppAnomalyBadge.HIGH_UPLOAD)
                    AnomalyType.APP_BACKGROUND_TRAFFIC -> listOf(AppAnomalyBadge.BACKGROUND)
                    AnomalyType.NEW_DESTINATION_COUNTRY,
                    AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH,
                    -> listOf(AppAnomalyBadge.NEW_ROUTE)
                    else -> listOf(AppAnomalyBadge.UNUSUAL)
                } +
                    if (event.severity == AnomalySeverity.HIGH) {
                        listOf(AppAnomalyBadge.UNUSUAL)
                    } else {
                        emptyList()
                    }
            }
            .toSet()
    return badges.ifEmpty { setOf(AppAnomalyBadge.NORMAL) }
}

private fun appConnectionRows(
    packageName: String,
    entries: List<DiagnosticEntry>,
): List<AppConnectionRow> =
    entries
        .asSequence()
        .filter { entry -> entry.tag == "activity" && entry.message.contains("packages=") && entry.message.contains(packageName) }
        .mapNotNull { entry ->
            val parts = entry.message.substringAfter(": ", missingDelimiterValue = entry.message)
                .split(" • ")
                .mapNotNull { part ->
                    val key = part.substringBefore("=", missingDelimiterValue = "").takeIf(String::isNotBlank)
                    val value = part.substringAfter("=", missingDelimiterValue = "").takeIf(String::isNotBlank)
                    if (key != null && value != null) key to value else null
                }
                .toMap()
            val packages = parts["packages"].orEmpty().split(",").map(String::trim)
            if (packageName !in packages) {
                null
            } else {
                val remote = parts["remote"]?.takeIf { remote -> remote != "?:0" && remote != "?" } ?: return@mapNotNull null
                val protocol = parts["protocol"]?.takeIf(String::isNotBlank) ?: "?"
                AppConnectionRow(
                    remote = remote,
                    protocol = protocol,
                    count = 1,
                    lastSeenAt = entry.timestamp,
                )
            }
        }
        .groupBy { row -> row.remote to row.protocol }
        .map { (key, rows) ->
            AppConnectionRow(
                remote = key.first,
                protocol = key.second,
                count = rows.size,
                lastSeenAt = rows.maxOf(AppConnectionRow::lastSeenAt),
            )
        }
        .sortedWith(
            compareByDescending<AppConnectionRow> { row -> row.count }
                .thenByDescending { row -> row.lastSeenAt },
        )

private val StatisticsRetention.durationMs: Long?
    get() =
        when (this) {
            StatisticsRetention.WEEK -> 7L * 24L * 60L * 60L * 1000L
            StatisticsRetention.MONTH -> 31L * 24L * 60L * 60L * 1000L
            StatisticsRetention.MONTHS_3 -> 93L * 24L * 60L * 60L * 1000L
            StatisticsRetention.FOREVER -> null
        }

private fun Profile.statisticsProtocolHints(): List<ProtocolHint> {
    val optionHints = protocolOptions.map(ProfileProtocolOption::protocolHint)
    return (optionHints + protocolHint).distinct()
}

private fun Profile.runtimeProtocolHint(): ProtocolHint =
    selectedProtocolOptionId
        ?.takeIf(String::isNotBlank)
        ?.let { selectedId -> protocolOptions.firstOrNull { option -> option.id == selectedId } }
        ?.protocolHint
        ?: protocolOptions.firstOrNull { option -> option.isSelected }?.protocolHint
        ?: protocolOptions.firstOrNull()?.protocolHint
        ?: protocolHint

private fun List<Profile>.transportForProtocol(protocol: ProtocolHint): TransportProtocol {
    if (protocol == ProtocolHint.HYSTERIA2 || protocol == ProtocolHint.WIREGUARD) {
        return TransportProtocol.UDP
    }
    val explicitTransports =
        flatMap { profile ->
            profile.protocolOptions
                .filter { option -> option.protocolHint == protocol }
                .map { option -> option.inferredTransport() }
        }
        .filterNot { transport -> transport == TransportProtocol.UNKNOWN }
        .distinct()
    return if (explicitTransports.size == 1) explicitTransports.first() else TransportProtocol.UNKNOWN
}

private fun ProfileProtocolOption.inferredTransport(): TransportProtocol {
    val raw = "$id $displayName".lowercase(Locale.US)
    return when {
        raw.contains("udp") || raw.contains("quic") -> TransportProtocol.UDP
        raw.contains("tcp") || raw.contains("grpc") || raw.contains("websocket") || raw.contains(" ws") ||
            raw.contains("-ws") || raw.contains("http") -> TransportProtocol.TCP
        else -> TransportProtocol.UNKNOWN
    }
}

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
private fun statisticsCountryChartColors(): List<Color> {
    val semanticColors = LocalFoxholeSemanticColors.current
    return listOf(
        MaterialTheme.colorScheme.primary,
        semanticColors.success,
    )
}

private fun niceTrafficScale(maxBytes: Long): Long {
    val hundredMb = 100L * 1024L * 1024L
    if (maxBytes <= hundredMb) return hundredMb
    val gb = 1024L * 1024L * 1024L
    return (ceil(maxBytes.toDouble() / gb.toDouble()).toLong().coerceAtLeast(1L)) * gb
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

private val CompactProtocolGridWidth = 360.dp
private const val DonutAnimationDurationMs = 700
private const val ProfileTrafficPreviewLimit = 6
private const val ProfileComparisonMinAttempts = 2
