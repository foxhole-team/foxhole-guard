package com.foxhole.beta.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AppTrafficSample
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.ProfileTrafficTotal
import kotlin.math.ceil
import kotlin.math.max

enum class StatisticsPeriod(val durationMs: Long?) {
    HOUR(60L * 60L * 1000L),
    DAY(24L * 60L * 60L * 1000L),
    DAYS_3(3L * 24L * 60L * 60L * 1000L),
    WEEK(7L * 24L * 60L * 60L * 1000L),
    MONTH(31L * 24L * 60L * 60L * 1000L),
    ALL(null),
}

@Composable
fun StatisticsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onAppTrafficStatsEnabledChanged: (Boolean) -> Unit,
    onClearUsage: () -> Unit,
) {
    var periodMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var allAppsVisible by rememberSaveable { mutableStateOf(false) }
    var clearConfirmVisible by rememberSaveable { mutableStateOf(false) }
    var period by rememberSaveable { mutableStateOf(StatisticsPeriod.DAY) }
    val appRows = remember(state.settings.appTrafficSamples, state.installedApps, period) {
        appTrafficRows(
            samples = state.settings.appTrafficSamples,
            installedApps = state.installedApps,
            period = period,
        )
    }
    val topApps = appRows.take(10)

    SettingsScaffold(
        title = stringResource(R.string.statistics_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.app_statistics_enabled_title),
                    checked = state.settings.appTrafficStatsEnabled,
                    summary = stringResource(R.string.app_statistics_enabled_summary),
                    leadingIcon = Icons.Outlined.BarChart,
                    onCheckedChange = onAppTrafficStatsEnabledChanged,
                    grouped = true,
                )
            }
        }
        item {
            AnimatedVisibility(visible = state.settings.appTrafficStatsEnabled) {
                Column(
                    modifier = Modifier.animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FoxholeCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TrafficBarChart(rows = topApps)
                            ChartLegend()
                        }
                    }
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
                    FoxholeCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppTrafficTable(rows = topApps, emptyText = stringResource(R.string.app_statistics_empty))
                            if (appRows.size > 10) {
                                FoxholeDialogConfirmButton(
                                    onClick = { allAppsVisible = true },
                                    label = stringResource(R.string.show_all_label),
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            FoxholeCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.profile_statistics_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ProfileTrafficTable(rows = state.settings.profileTrafficTotals)
                }
            }
        }
        item {
            FoxholePreferenceCard(
                leadingIcon = Icons.Outlined.Delete,
                title = stringResource(R.string.reset_usage_tracking),
                summary = stringResource(R.string.clear_usage_title),
                onClick = { clearConfirmVisible = true },
                leadingIconTint = MaterialTheme.colorScheme.error,
            )
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
            confirmLabel = stringResource(R.string.delete_label),
            icon = Icons.Outlined.Delete,
            onDismiss = { clearConfirmVisible = false },
            onConfirm = {
                clearConfirmVisible = false
                onClearUsage()
            },
        )
    }
}

@Composable
private fun TrafficBarChart(rows: List<AppTrafficRow>) {
    val txColor = Color(0xFF2F80ED)
    val rxColor = Color(0xFF24A148)
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
            val slotWidth = size.width / 10f
            val barWidth = (slotWidth * 0.24f).coerceAtMost(12.dp.toPx())
            rows.take(10).forEachIndexed { index, row ->
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
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendItem(color = Color(0xFF2F80ED), text = stringResource(R.string.traffic_sent))
        LegendItem(color = Color(0xFF24A148), text = stringResource(R.string.traffic_received))
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
            Text(row.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TrafficCells(tx = row.txBytes, rx = row.rxBytes)
    }
}

@Composable
private fun TrafficCells(tx: Long, rx: Long) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.End) {
            Text(formatBytes(context, tx), style = MaterialTheme.typography.labelMedium, color = Color(0xFF2F80ED))
            Text(formatBytes(context, rx), style = MaterialTheme.typography.labelMedium, color = Color(0xFF24A148))
        }
        Text(formatBytes(context, tx + rx), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProfileTrafficTable(rows: List<ProfileTrafficTotal>) {
    if (rows.isEmpty()) {
        Text(stringResource(R.string.diagnostics_usage_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column {
        rows.sortedByDescending { it.rxTotalBytes + it.txTotalBytes }.forEachIndexed { index, row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Storage, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                Text(row.profileName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TrafficCells(tx = row.txTotalBytes, rx = row.rxTotalBytes)
            }
            if (index != rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

private data class AppTrafficRow(
    val packageName: String,
    val label: String,
    val txBytes: Long,
    val rxBytes: Long,
)

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
            .thenBy { it.label.lowercase() },
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
