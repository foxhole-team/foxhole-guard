@file:Suppress("ImportOrdering")

package com.foxhole.beta.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.content.Context
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
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
import com.foxhole.beta.core.model.ProtocolQuality
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
import com.foxhole.beta.ui.statistics.charts.AnimatedProgressRing
import com.foxhole.beta.ui.statistics.charts.AnimatedSegmentDonutChart
import com.foxhole.beta.ui.statistics.charts.AnimatedSplitDonutChart
import com.foxhole.beta.ui.statistics.charts.SegmentedBarSegment
import com.foxhole.beta.ui.statistics.charts.SegmentedLinearBar
import com.foxhole.beta.ui.statistics.charts.SplitOutcomeBar
import com.foxhole.beta.ui.statistics.charts.VerticalValueBarChart
import com.foxhole.beta.ui.statistics.charts.chartColor
import com.foxhole.beta.ui.statistics.charts.chartCountryColors
import com.foxhole.beta.ui.theme.LocalFoxholeSemanticColors
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun DnsProtectionCard(
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
            if (summary.appRows.isNotEmpty()) {
                DnsTrafficShareRings(summary = summary)
            }
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
internal fun DnsCategoryTable(rows: List<DnsProtectionCategoryRow>) {
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

internal data class DnsTrafficShareMetric(
    val category: DnsProtectionCategory,
    val estimatedBytes: Long,
    val ratio: Float,
)

internal fun dnsTrafficShareMetrics(summary: DnsProtectionSummary): List<DnsTrafficShareMetric> {
    val totalBytes = summary.appRows.sumOf(DnsProtectionAppRow::totalBytes).coerceAtLeast(1L)
    return DnsProtectionCategory.entries.map { category ->
        val estimatedBytes =
            summary.appRows
                .sumOf { row -> (row.totalBytes.toDouble() * row.categoryRatios[category].orZero()).roundToLong() }
                .coerceAtLeast(0L)
        DnsTrafficShareMetric(
            category = category,
            estimatedBytes = estimatedBytes,
            ratio = (estimatedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f),
        )
    }
}

@Composable
internal fun DnsProtectionAppRowView(
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

internal fun dnsCategoryLabel(category: DnsProtectionCategory): Int =
    when (category) {
        DnsProtectionCategory.ADS -> R.string.statistics_dns_category_ads
        DnsProtectionCategory.TRACKERS -> R.string.statistics_dns_category_trackers
        DnsProtectionCategory.TELEMETRY -> R.string.statistics_dns_category_telemetry
        DnsProtectionCategory.MALICIOUS -> R.string.statistics_dns_category_malicious
    }

