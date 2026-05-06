package com.foxhole.beta.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Storage
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
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AppTrafficSample
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
import com.foxhole.beta.core.model.StatisticsUiState
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapUiState
import com.foxhole.beta.core.model.TransportProtocol
import com.foxhole.beta.core.model.TransportStatisticsUiItem
import com.foxhole.beta.ui.theme.LocalFoxholeSemanticColors
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

enum class StatisticsPeriod(val durationMs: Long?) {
    HOUR(60L * 60L * 1000L),
    DAY(24L * 60L * 60L * 1000L),
    DAYS_3(3L * 24L * 60L * 60L * 1000L),
    WEEK(7L * 24L * 60L * 1000L),
    MONTH(31L * 24L * 60L * 60L * 1000L),
    ALL(null),
}

@Composable
fun StatisticsScreen(
    state: SettingsRouteUiState,
    trafficMapState: TrafficMapUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onAppTrafficStatsEnabledChanged: (Boolean) -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onClearUsage: () -> Unit,
) {
    var periodMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var allAppsVisible by rememberSaveable { mutableStateOf(false) }
    var clearConfirmVisible by rememberSaveable { mutableStateOf(false) }
    var appStatsFirewallWarningVisible by rememberSaveable { mutableStateOf(false) }
    var period by rememberSaveable { mutableStateOf(StatisticsPeriod.DAY) }
    val statistics =
        remember(state.settings, state.profiles, state.activeProfile, state.traffic, period) {
            statisticsUiState(state = state, period = period)
        }
    val appRows =
        remember(state.settings.appTrafficSamples, state.installedApps, period) {
            appTrafficRows(
                samples = state.settings.appTrafficSamples,
                installedApps = state.installedApps,
                period = period,
            )
        }
    val topApps = appRows.take(10)
    val appStatsEnabled = state.settings.appTrafficStatsEnabled && state.settings.expert.firewallEnabled

    SettingsScaffold(
        title = stringResource(R.string.statistics_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item(key = "profile-traffic") {
            ProfileTrafficOverviewCard(
                statistics = statistics,
                onClear = { clearConfirmVisible = true },
            )
        }
        item(key = "country-traffic") {
            CountryTrafficCard(state = trafficMapState)
        }
        item(key = "protocols") {
            ProtocolStatisticsSection(items = statistics.vpnProtocols)
        }
        if (statistics.profileComparisons.isNotEmpty()) {
            item(key = "profile-comparisons") {
                ProfileComparisonsSection(items = statistics.profileComparisons)
            }
        }
        item(key = "transports") {
            TransportStatisticsSection(items = statistics.transports)
        }
        item(key = "app-statistics-toggle") {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.app_statistics_enabled_title),
                    checked = appStatsEnabled,
                    summary = stringResource(R.string.app_statistics_enabled_summary),
                    leadingIcon = Icons.Outlined.Apps,
                    onCheckedChange = { enabled ->
                        if (enabled && !state.settings.expert.firewallEnabled) {
                            appStatsFirewallWarningVisible = true
                        } else {
                            onAppTrafficStatsEnabledChanged(enabled)
                        }
                    },
                    grouped = true,
                )
            }
        }
        item(key = "app-statistics") {
            AnimatedVisibility(visible = appStatsEnabled) {
                Column(
                    modifier = Modifier.animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DropdownSettingRow(
                        title = stringResource(R.string.statistics_period_title),
                        value = statisticsPeriodLabel(period),
                        expanded = periodMenuExpanded,
                        onExpandedChange = { periodMenuExpanded = it },
                        values = StatisticsPeriod.entries,
                        selected = period,
                        label = { statisticsPeriodLabel(it) },
                        onSelect = { period = it },
                        leadingIcon = Icons.Outlined.BarChart,
                    )
                    AppTrafficStatisticsCard(
                        rows = topApps,
                        allRowsCount = appRows.size,
                        onShowAll = { allAppsVisible = true },
                    )
                }
            }
        }
    }

    if (allAppsVisible) {
        AlertDialog(
            onDismissRequest = { allAppsVisible = false },
            title = { Text(stringResource(R.string.app_statistics_all_title)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    items(appRows, key = AppTrafficRow::packageName) { row ->
                        AppTrafficRowView(row = row)
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

    if (appStatsFirewallWarningVisible) {
        ConfirmDialog(
            title = stringResource(R.string.app_statistics_firewall_warning_title),
            body = stringResource(R.string.app_statistics_firewall_warning_body),
            confirmLabel = stringResource(R.string.security_firewall_enable_action),
            dismissLabel = stringResource(R.string.close),
            icon = Icons.Outlined.Apps,
            onDismiss = { appStatsFirewallWarningVisible = false },
            onConfirm = {
                appStatsFirewallWarningVisible = false
                onFirewallEnabledChanged(true)
                onAppTrafficStatsEnabledChanged(true)
            },
        )
    }
}

@Composable
private fun ProfileTrafficOverviewCard(
    statistics: StatisticsUiState,
    onClear: () -> Unit,
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
            OverallMetricsGrid(total = statistics.total)
            ProfileTrafficList(items = statistics.profileTraffic)
        }
    }
}

@Composable
private fun OverallMetricsGrid(total: OverallStatisticsUiItem) {
    val context = LocalContext.current
    val metrics =
        listOf(
            stringResource(R.string.statistics_total_traffic) to formatBytes(context, total.totalBytes),
            stringResource(R.string.statistics_vpn_sessions) to total.vpnSessions.toString(),
            stringResource(R.string.statistics_successful_connections) to total.successCount.toString(),
            stringResource(R.string.statistics_errors) to total.failureCount.toString(),
            stringResource(R.string.statistics_average_latency) to total.avgLatencyMs.formatLatency(),
            stringResource(R.string.statistics_last_activity) to total.lastActivityAt.formatLastActivity(),
        )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { (label, value) ->
                    MetricTile(
                        label = label,
                        value = value,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
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
private fun ProfileTrafficList(items: List<ProfileTrafficUiItem>) {
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                        text = protocolDisplayName(item.protocolHint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatBytes(context, item.totalBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }
            if (index != minOf(items.size, ProfileTrafficPreviewLimit) - 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
            }
        }
    }
}

@Composable
private fun CountryTrafficCard(state: TrafficMapUiState) {
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
                title = stringResource(R.string.statistics_country_traffic_title),
            )
            if (state.destinations.isEmpty()) {
                Text(
                    text =
                        stringResource(
                            if (state.isAvailable) {
                                R.string.traffic_map_waiting_connections
                            } else {
                                R.string.traffic_map_live_requires_firewall
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CountryDonutChart(
                        points = state.destinations.take(CountryChartSegmentLimit),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    CountryTrafficList(
                        points = state.destinations,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CountryDonutChart(
    points: List<TrafficMapPoint>,
    modifier: Modifier = Modifier,
) {
    val colors = statisticsChartColors()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val totalBytes = points.sumOf(TrafficMapPoint::bytes).coerceAtLeast(1L)
    val visible = rememberOneShotVisible("countries")
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = DonutAnimationDurationMs, easing = FastOutSlowInEasing),
        label = "country-donut-progress",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(134.dp)) {
            val stroke = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round)
            var startAngle = -90f
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
            )
            points.forEachIndexed { index, point ->
                val sweep = 360f * (point.bytes.toFloat() / totalBytes.toFloat()) * progress
                drawArc(
                    color = colors[index % colors.size],
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = stroke,
                )
                startAngle += sweep
            }
        }
        Text(
            text = points.size.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CountryTrafficList(
    points: List<TrafficMapPoint>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = statisticsChartColors()
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
                val columns = if (maxWidth >= WideStatisticsGridWidth) 3 else 2
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
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = protocolDisplayName(item.protocol),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(contentAlignment = Alignment.Center) {
                AnimatedDonutChart(
                    successRate = item.successRate,
                    errorRate = item.errorRate,
                    visible = visible,
                    modifier = Modifier.size(78.dp),
                )
                Text(
                    text = formatPercent(item.successRate),
                    style = MaterialTheme.typography.titleSmall,
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
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.statistics_apps_title),
            )
            TrafficBarChart(rows = rows)
            ChartLegend()
            AppTrafficTable(rows = rows, emptyText = stringResource(R.string.app_statistics_empty))
            if (allRowsCount > rows.size) {
                FoxholeDialogConfirmButton(
                    onClick = onShowAll,
                    label = stringResource(R.string.show_all_label),
                )
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
private fun AppTrafficTable(rows: List<AppTrafficRow>, emptyText: String) {
    if (rows.isEmpty()) {
        Text(text = emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column {
        rows.forEachIndexed { index, row ->
            AppTrafficRowView(row = row)
            if (index != rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun AppTrafficRowView(row: AppTrafficRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
        }
        TrafficCells(tx = row.txBytes, rx = row.rxBytes)
    }
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

private data class AppTrafficRow(
    val packageName: String,
    val label: String,
    val txBytes: Long,
    val rxBytes: Long,
)

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

private fun statisticsUiState(
    state: SettingsRouteUiState,
    period: StatisticsPeriod,
): StatisticsUiState {
    val profileTraffic = profileTrafficItems(state)
    val protocolStats = protocolStatistics(state, profileTraffic)
    val total = overallStatistics(profileTraffic, protocolStats, state)
    return StatisticsUiState(
        range = period.toStatisticsRange(),
        extendedMode = state.settings.appTrafficStatsEnabled && state.settings.expert.firewallEnabled,
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
        .filter { item -> item.totalAttempts > 0 || item.totalBytes > 0L }
        .sortedWith(
            compareByDescending<TransportStatisticsUiItem> { item -> item.totalBytes }
                .thenBy { item -> item.transport.name },
        )
}

private fun appTrafficRows(
    samples: List<AppTrafficSample>,
    installedApps: List<InstalledAppOption>,
    period: StatisticsPeriod,
): List<AppTrafficRow> {
    val labels = installedApps.associate { it.packageName to it.label }
    val cutoff = period.durationMs?.let { System.currentTimeMillis() - it }
    val sampledRows =
        samples
            .asSequence()
            .filter { sample -> cutoff == null || sample.sampledAt >= cutoff }
            .groupBy(AppTrafficSample::packageName)
            .map { (packageName, packageSamples) ->
                AppTrafficRow(
                    packageName = packageName,
                    label = labels[packageName] ?: packageName,
                    txBytes = packageSamples.sumOf(AppTrafficSample::txBytes),
                    rxBytes = packageSamples.sumOf(AppTrafficSample::rxBytes),
                )
            }
    val sampledPackages = sampledRows.map(AppTrafficRow::packageName).toSet()
    return (sampledRows + installedApps.filterNot { it.packageName in sampledPackages }.map { app ->
        AppTrafficRow(
            packageName = app.packageName,
            label = app.label,
            txBytes = 0L,
            rxBytes = 0L,
        )
    }).sortedWith(
        compareByDescending<AppTrafficRow> { it.txBytes + it.rxBytes }
            .thenBy { it.label.lowercase(Locale.getDefault()) },
    )
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
private fun statisticsChartColors(): List<Color> {
    val semanticColors = LocalFoxholeSemanticColors.current
    return listOf(
        MaterialTheme.colorScheme.primary,
        semanticColors.success,
        MaterialTheme.colorScheme.tertiary,
        semanticColors.warning,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error,
    )
}

private fun niceTrafficScale(maxBytes: Long): Long {
    val hundredMb = 100L * 1024L * 1024L
    if (maxBytes <= hundredMb) return hundredMb
    val gb = 1024L * 1024L * 1024L
    return (ceil(maxBytes.toDouble() / gb.toDouble()).toLong().coerceAtLeast(1L)) * gb
}

@Composable
private fun statisticsPeriodLabel(value: StatisticsPeriod): String =
    stringResource(
        when (value) {
            StatisticsPeriod.HOUR -> R.string.statistics_period_hour
            StatisticsPeriod.DAY -> R.string.statistics_period_day
            StatisticsPeriod.DAYS_3 -> R.string.statistics_period_days_3
            StatisticsPeriod.WEEK -> R.string.statistics_period_week
            StatisticsPeriod.MONTH -> R.string.statistics_period_month
            StatisticsPeriod.ALL -> R.string.statistics_period_all
        },
    )

private fun StatisticsPeriod.toStatisticsRange(): StatisticsRange =
    when (this) {
        StatisticsPeriod.HOUR -> StatisticsRange.HOUR
        StatisticsPeriod.DAY -> StatisticsRange.DAY
        StatisticsPeriod.DAYS_3 -> StatisticsRange.DAYS_3
        StatisticsPeriod.WEEK -> StatisticsRange.WEEK
        StatisticsPeriod.MONTH -> StatisticsRange.MONTH
        StatisticsPeriod.ALL -> StatisticsRange.ALL
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

private val WideStatisticsGridWidth = 620.dp
private const val DonutAnimationDurationMs = 700
private const val CountryChartSegmentLimit = 8
private const val ProfileTrafficPreviewLimit = 6
private const val ProfileComparisonMinAttempts = 2
