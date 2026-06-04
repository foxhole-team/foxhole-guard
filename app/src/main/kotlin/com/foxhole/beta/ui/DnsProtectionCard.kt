package com.foxhole.beta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.ui.statistics.StatisticsMetricTileGrid
import com.foxhole.beta.ui.statistics.StatisticsMetricTileModel
import com.foxhole.beta.ui.statistics.StatisticsRowSurface
import com.foxhole.beta.ui.statistics.statisticsVisualTokens
import kotlin.math.roundToLong

@Composable
@Suppress("LongMethod")
internal fun DnsProtectionCard(
    summary: DnsProtectionSummary,
    range: StatisticsDisplayRange,
    onRangeSelected: (StatisticsDisplayRange) -> Unit,
) {
    val context = LocalContext.current
    var rangeExpanded by rememberSaveable { mutableStateOf(false) }
    var allBlockedAppsVisible by rememberSaveable { mutableStateOf(false) }
    var allBlockedDomainsVisible by rememberSaveable { mutableStateOf(false) }
    val blockedAppRows = summary.appRows
    val topBlockedAppRows = remember(blockedAppRows) { blockedAppRows.take(STATISTICS_TOP_PREVIEW_LIMIT) }
    val blockedDomainRows = summary.domainRows
    val topBlockedDomainRows = remember(blockedDomainRows) { blockedDomainRows.take(STATISTICS_TOP_PREVIEW_LIMIT) }
    StatisticsSectionCard(
        icon = Icons.Outlined.Dns,
        title = stringResource(R.string.statistics_dns_filtering_title),
        subtitle = stringResource(R.string.statistics_dns_real_summary),
        trailing = {
            StatisticsRangePillDropdown(
                value = range,
                expanded = rangeExpanded,
                onExpandedChange = { rangeExpanded = it },
                onSelect = onRangeSelected,
            )
        },
    ) {
        val tokens = statisticsVisualTokens()
        Text(
            text = stringResource(R.string.statistics_dns_active_list),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        StatisticsMetricTileGrid(
            metrics =
            listOf(
                StatisticsMetricTileModel(
                    label = stringResource(R.string.statistics_dns_blocked_queries),
                    value = summary.blockedQueries.toString(),
                    accent = tokens.colors.danger,
                ),
                StatisticsMetricTileModel(
                    label = stringResource(R.string.statistics_dns_allowed_queries),
                    value = summary.allowedQueries.toString(),
                    accent = tokens.colors.positive,
                ),
                StatisticsMetricTileModel(
                    label = stringResource(R.string.statistics_dns_block_ratio),
                    value = formatPercent(summary.blockRatio),
                    accent = if (summary.blockRatio > 0f) tokens.colors.warning else tokens.colors.mutedText,
                ),
            ),
            columns = 3,
        )
        if (summary.categoryRows.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_dns_traffic_share_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.statistics_dns_categories_estimated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DnsTrafficShareGrid(summary = summary)
        }
        if (blockedAppRows.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_dns_apps_estimated),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.statistics_dns_app_drop_legend),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                topBlockedAppRows.forEach { row ->
                    DnsProtectionAppRowView(row = row, totalBytesText = formatBytes(context, row.totalBytes))
                }
            }
            if (blockedAppRows.size > STATISTICS_TOP_PREVIEW_LIMIT) {
                TextButton(onClick = { allBlockedAppsVisible = true }) {
                    Text(stringResource(R.string.show_all_label))
                }
            }
        }
        if (blockedDomainRows.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_dns_domains_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.statistics_dns_domains_summary),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                topBlockedDomainRows.forEach { row ->
                    DnsProtectionDomainRowView(row = row)
                }
            }
            if (blockedDomainRows.size > STATISTICS_TOP_PREVIEW_LIMIT) {
                TextButton(onClick = { allBlockedDomainsVisible = true }) {
                    Text(stringResource(R.string.show_all_label))
                }
            }
        }
    }

    if (allBlockedAppsVisible) {
        AlertDialog(
            onDismissRequest = { allBlockedAppsVisible = false },
            title = { Text(stringResource(R.string.statistics_dns_all_apps_title)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    items(
                        blockedAppRows,
                        key = DnsProtectionAppRow::packageName,
                    ) { row ->
                        DnsProtectionAppRowView(row = row, totalBytesText = formatBytes(context, row.totalBytes))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                FoxholeDialogDismissButton(onClick = { allBlockedAppsVisible = false })
            },
        )
    }

    if (allBlockedDomainsVisible) {
        AlertDialog(
            onDismissRequest = { allBlockedDomainsVisible = false },
            title = { Text(stringResource(R.string.statistics_dns_all_domains_title)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    items(
                        blockedDomainRows,
                        key = DnsProtectionDomainRow::domain,
                    ) { row ->
                        DnsProtectionDomainRowView(row = row)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                FoxholeDialogDismissButton(onClick = { allBlockedDomainsVisible = false })
            },
        )
    }
}

@Composable
internal fun DnsTrafficShareGrid(summary: DnsProtectionSummary) {
    val metrics = remember(summary.appRows, summary.categoryRows) { dnsTrafficShareMetrics(summary) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowMetrics.forEach { metric ->
                    DnsTrafficShareGridCell(metric = metric, modifier = Modifier.weight(1f))
                }
                repeat(2 - rowMetrics.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DnsTrafficShareGridCell(
    metric: DnsTrafficShareMetric,
    modifier: Modifier = Modifier,
) {
    DnsTrafficShareRing(
        metric = metric,
        modifier = modifier,
    )
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
        modifier = Modifier.fillMaxWidth(),
    ) {
        StatisticsRowSurface {
            AppIcon(packageName = row.packageName, modifier = Modifier.size(36.dp))
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
                    color = statisticsVisualTokens().colors.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatPercent(row.blockRatio),
                    style = MaterialTheme.typography.titleMedium,
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
                    color = statisticsVisualTokens().colors.mutedText,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
internal fun DnsProtectionDomainRowView(row: DnsProtectionDomainRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        StatisticsRowSurface {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = RoundedCornerShape(12.dp),
                color = statisticsVisualTokens().colors.headerIconContainer,
                contentColor = statisticsVisualTokens().colors.headerIconTint,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = row.domain,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.statistics_dns_domain_quality_real),
                    style = MaterialTheme.typography.labelSmall,
                    color = statisticsVisualTokens().colors.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.statistics_dns_blocked_domain_value, row.blockedQueries),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
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
