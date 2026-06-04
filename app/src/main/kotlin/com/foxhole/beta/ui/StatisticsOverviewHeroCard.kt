package com.foxhole.beta.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.foxhole.beta.R
import com.foxhole.beta.core.model.StatisticsUiState
import com.foxhole.beta.ui.statistics.StatisticsCardTone
import com.foxhole.beta.ui.statistics.StatisticsDashboardCard
import com.foxhole.beta.ui.statistics.StatisticsMetricTileGrid
import com.foxhole.beta.ui.statistics.StatisticsMetricTileModel
import com.foxhole.beta.ui.statistics.statisticsVisualTokens

@Composable
internal fun StatisticsOverviewHeroCard(
    statistics: StatisticsUiState,
    dnsSummary: DnsProtectionSummary,
    firewallEnabled: Boolean,
) {
    val context = LocalContext.current
    val tokens = statisticsVisualTokens()
    val totalProfileTraffic = statistics.profileTraffic.sumOf { item -> item.totalBytes.coerceAtLeast(0L) }
    StatisticsDashboardCard(
        icon = Icons.Outlined.QueryStats,
        title = stringResource(R.string.statistics_title),
        tone = StatisticsCardTone.Elevated,
    ) {
        val trafficScopeText =
            stringResource(
                if (firewallEnabled) {
                    R.string.statistics_traffic_scope_all_traffic
                } else {
                    R.string.statistics_traffic_scope_vpn_only
                },
            )
        StatisticsMetricTileGrid(
            metrics =
            listOf(
                StatisticsMetricTileModel(
                    label = stringResource(R.string.statistics_total_traffic),
                    value = formatBytes(context, totalProfileTraffic),
                    caption = trafficScopeText,
                    accent = MaterialTheme.colorScheme.primary,
                ),
                StatisticsMetricTileModel(
                    label = stringResource(R.string.statistics_dns_blocked_queries),
                    value = dnsSummary.blockedQueries.toString(),
                    caption = stringResource(R.string.statistics_dns_block_ratio_percent) + " " + formatPercent(dnsSummary.blockRatio),
                    accent = tokens.colors.danger,
                ),
            ),
        )
    }
}
