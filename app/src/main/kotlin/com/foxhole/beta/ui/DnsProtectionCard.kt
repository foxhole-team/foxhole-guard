package com.foxhole.beta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
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
                pluralStringResource(
                    R.plurals.statistics_dns_blocked_app_value,
                    row.estimatedBlockedQueries,
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
