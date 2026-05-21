package com.foxhole.beta.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.StatisticsRefreshInterval
import com.foxhole.beta.core.model.StatisticsRetention
import kotlin.math.roundToInt

@Composable
@Suppress("UnusedParameter")
internal fun rememberOneShotVisible(key: String): Boolean = true

internal val COMPACT_PROTOCOL_GRID_WIDTH = 360.dp
internal const val PROFILE_DETAIL_OVERALL_KEY = "__overall__"
internal const val MIN_TIMELINE_TRAFFIC_SCALE_BYTES = 10L * 1024L * 1024L
internal const val PROFILE_TRAFFIC_PREVIEW_LIMIT = 6
internal const val STATISTICS_TOP_PREVIEW_LIMIT = 5
internal const val APP_TRAFFIC_CHART_LIMIT = 10
internal val DASHBOARD_DISPLAY_RANGES =
    listOf(
        StatisticsDisplayRange.HOURS_24,
        StatisticsDisplayRange.WEEK,
        StatisticsDisplayRange.MONTH,
    )

internal fun dashboardStatisticsDisplayRanges(): List<StatisticsDisplayRange> = DASHBOARD_DISPLAY_RANGES

@Composable
internal fun statisticsRetentionLabel(value: StatisticsRetention): String =
    stringResource(
        when (value) {
            StatisticsRetention.WEEK -> R.string.statistics_retention_week
            StatisticsRetention.MONTH -> R.string.statistics_retention_month
            StatisticsRetention.MONTHS_3 -> R.string.statistics_retention_months_3
            StatisticsRetention.FOREVER -> R.string.statistics_retention_forever
        },
    )

@Composable
internal fun statisticsRefreshIntervalLabel(value: StatisticsRefreshInterval): String =
    pluralStringResource(
        R.plurals.statistics_refresh_interval_seconds,
        value.seconds,
        value.seconds,
    )

internal fun List<Long>.averageOrNull(): Long? =
    takeIf(List<Long>::isNotEmpty)
        ?.let { values -> values.average().roundToInt().toLong() }
