package com.foxhole.beta.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AnomalyEvent
import com.foxhole.beta.core.model.StatisticsUiState
import com.foxhole.beta.ui.statistics.StatisticsCardTone
import com.foxhole.beta.ui.statistics.StatisticsDashboardCard
import com.foxhole.beta.ui.statistics.StatisticsMetricTileGrid
import com.foxhole.beta.ui.statistics.StatisticsMetricTileModel
import com.foxhole.beta.ui.statistics.statisticsVisualTokens

@Composable
internal fun StatisticsOverviewHeroCard(
    statistics: StatisticsUiState,
    appRows: List<AppTrafficRow>,
    dnsSummary: DnsProtectionSummary,
    countryRows: List<CountryTrafficUiRow>,
    anomalyEvents: List<AnomalyEvent>,
) {
    val context = LocalContext.current
    val tokens = statisticsVisualTokens()
    val totalProfileTraffic = statistics.profileTraffic.sumOf { item -> item.totalBytes.coerceAtLeast(0L) }
    val topApp = appRows.maxByOrNull { row -> row.totalBytes }
    val countryValue =
        if (anomalyEvents.isNotEmpty()) {
            anomalyEvents.size.toString()
        } else {
            countryRows.size.toString()
        }
    StatisticsDashboardCard(
        icon = Icons.Outlined.QueryStats,
        title = stringResource(R.string.statistics_title),
        subtitle = stringResource(R.string.statistics_chart_axes_app_timeline),
        tone = StatisticsCardTone.Elevated,
    ) {
        StatisticsMetricTileGrid(
            metrics =
            listOf(
                StatisticsMetricTileModel(
                    label = stringResource(R.string.statistics_total_traffic),
                    value = formatBytes(context, totalProfileTraffic),
                    accent = MaterialTheme.colorScheme.primary,
                ),
                StatisticsMetricTileModel(
                    label = stringResource(R.string.statistics_metric_apps),
                    value = topApp?.let { app -> formatBytes(context, app.totalBytes) } ?: appRows.size.toString(),
                    caption = topApp?.label,
                    accent = tokens.colors.tx,
                ),
                StatisticsMetricTileModel(
                    label = stringResource(R.string.statistics_dns_blocked_queries),
                    value = dnsSummary.blockedQueries.toString(),
                    caption = stringResource(R.string.statistics_dns_block_ratio) + " " + formatPercent(dnsSummary.blockRatio),
                    accent = tokens.colors.danger,
                ),
                StatisticsMetricTileModel(
                    label =
                    if (anomalyEvents.isNotEmpty()) {
                        stringResource(R.string.statistics_metric_anomalies)
                    } else {
                        stringResource(R.string.statistics_metric_countries)
                    },
                    value = countryValue,
                    accent = if (anomalyEvents.isNotEmpty()) tokens.colors.warning else tokens.colors.rx,
                ),
            ),
        )
    }
}
