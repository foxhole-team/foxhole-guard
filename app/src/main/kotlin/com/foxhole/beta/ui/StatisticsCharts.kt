package com.foxhole.beta.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.statistics.ChartColorToken
import com.foxhole.beta.ui.statistics.charts.AnimatedProgressRing
import com.foxhole.beta.ui.statistics.charts.AnimatedSegmentDonutChart
import com.foxhole.beta.ui.statistics.charts.SegmentedBarSegment
import com.foxhole.beta.ui.statistics.charts.SegmentedLinearBar
import com.foxhole.beta.ui.statistics.charts.VerticalValueBarChart
import com.foxhole.beta.ui.statistics.charts.chartColor
import com.foxhole.beta.ui.statistics.charts.chartCountryColors
import com.foxhole.beta.ui.statistics.statisticsVisualTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private const val DECIMAL_MEGABYTE_BYTES = 1_000_000L
private const val TIMELINE_TRAFFIC_SCALE_STEP_BYTES = 100L * DECIMAL_MEGABYTE_BYTES
private const val MAX_TIMELINE_TRAFFIC_TICKS = 8

@Composable
internal fun CountryVerticalBarChart(
    points: List<CountryTrafficUiRow>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = statisticsCountryChartColors()
    val maxBytes = points.maxOfOrNull(CountryTrafficUiRow::bytes)?.coerceAtLeast(1L) ?: 1L
    val chartDescription =
        listOf(
            stringResource(R.string.statistics_country_traffic_title),
            points
                .map { point -> "${point.label} ${formatBytes(context, point.bytes)}" }
                .joinToString(),
        ).filter { value -> value.isNotBlank() }.joinToString(". ")
    val visible = rememberOneShotVisible("countries")
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.statistics_country_traffic_legend, formatBytes(context, maxBytes)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VerticalValueBarChart(
            values = points.map(CountryTrafficUiRow::bytes),
            colors = colors,
            maxValue = maxBytes,
            visible = visible,
            contentDescription = chartDescription,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            animationLabel = "country-bars-progress",
        )
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
internal fun DnsProtectionChart(rows: List<DnsProtectionAppRow>) {
    val maxBlocked = rows.maxOfOrNull(DnsProtectionAppRow::estimatedBlockedQueries)?.coerceAtLeast(1) ?: 1
    val categoryColors = dnsCategoryColorMap()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { index, row ->
            val barDescription =
                "${row.label}: ${row.estimatedBlockedQueries} ${
                    stringResource(R.string.statistics_dns_blocked_queries)
                }, ${formatPercent(row.blockRatio)}"
            val barSegments =
                row.categoryRatios.entries.map { (category, ratio) ->
                    val categoryShareOfBlocked =
                        if (row.blockRatio > 0f) {
                            (ratio / row.blockRatio).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    SegmentedBarSegment(
                        color = categoryColors.getValue(category),
                        ratio = categoryShareOfBlocked,
                        minVisibleWidth = 1.5.dp,
                    )
                }
            val barScale =
                (row.estimatedBlockedQueries.toFloat() / maxBlocked.toFloat())
                    .coerceIn(0f, 1f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (index + 1).toString(),
                    modifier = Modifier.width(20.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
                AppIcon(packageName = row.packageName, modifier = Modifier.size(26.dp))
                Text(
                    text = row.label,
                    modifier = Modifier.widthIn(min = 70.dp, max = 112.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SegmentedLinearBar(
                    segments = barSegments,
                    scale = barScale,
                    contentDescription = barDescription,
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp),
                )
                Text(
                    text = row.estimatedBlockedQueries.toString(),
                    modifier = Modifier.widthIn(min = 34.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
internal fun DnsCategoryDonutChart(summary: DnsProtectionSummary) {
    val visible = rememberOneShotVisible("dns-categories")
    val categoryColors = dnsCategoryColorMap()
    val categoryDescriptions =
        summary.categoryRows.map { row ->
            "${stringResource(dnsCategoryLabel(row.category))} ${row.blockedQueries}"
        }
    val donutDescription =
        listOf(
            stringResource(R.string.statistics_dns_categories_title),
            categoryDescriptions.joinToString(),
        ).filter { value -> value.isNotBlank() }.joinToString(". ")
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedSegmentDonutChart(
                    values = summary.categoryRows.map { row -> row.blockedQueries.toFloat() },
                    colors = summary.categoryRows.map { row -> categoryColors.getValue(row.category) },
                    visible = visible,
                    contentDescription = donutDescription,
                    modifier = Modifier.size(118.dp),
                    animationLabel = "dns-category-donut",
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatPercent(summary.blockRatio),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = summary.blockedQueries.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                summary.categoryRows.forEach { row ->
                    LegendItem(
                        color = dnsCategoryColor(row.category),
                        text = "${stringResource(dnsCategoryLabel(row.category))}: ${row.blockedQueries}",
                    )
                }
            }
        }
    }
}

@Composable
internal fun DnsTrafficShareRings(summary: DnsProtectionSummary) {
    val metrics = remember(summary.appRows, summary.categoryRows) { dnsTrafficShareMetrics(summary) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.statistics_dns_traffic_share_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        metrics.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowMetrics.forEach { metric ->
                    DnsTrafficShareRing(metric = metric, modifier = Modifier.weight(1f))
                }
                repeat(2 - rowMetrics.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun DnsTrafficShareRing(
    metric: DnsTrafficShareMetric,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val visible = rememberOneShotVisible("dns-traffic-share:${metric.category.name}")
    val categoryLabel = stringResource(dnsCategoryLabel(metric.category))
    val ringDescription = "$categoryLabel: ${formatPercent(metric.ratio)}"
    val color = dnsCategoryColor(metric.category)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(statisticsVisualTokens().dimens.innerRadius),
        color = statisticsVisualTokens().colors.rowContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, statisticsVisualTokens().colors.metricTileBorder),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedProgressRing(
                    value = metric.ratio,
                    color = color,
                    visible = visible,
                    contentDescription = ringDescription,
                    modifier = Modifier.size(58.dp),
                    strokeWidth = com.foxhole.beta.ui.statistics.charts.chartVisualTokens().ringStrokeWidthCompact,
                    animationLabel = "dns-traffic-share-ring-${metric.category.name}",
                )
                Text(
                    text = formatPercent(metric.ratio),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = categoryLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatBytes(context, metric.estimatedBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = statisticsVisualTokens().colors.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun DnsAppDropStack(row: DnsProtectionAppRow) {
    val categoryColors = dnsCategoryColorMap()
    val description =
        "${row.label}: ${formatPercent(row.blockRatio)} ${
            stringResource(R.string.statistics_dns_block_ratio)
        }"
    val segments =
        row.categoryRatios.map { (category, ratio) ->
            SegmentedBarSegment(
                color = categoryColors.getValue(category),
                ratio = ratio,
            )
        }
    SegmentedLinearBar(
        segments = segments,
        contentDescription = description,
        modifier = Modifier
            .fillMaxWidth()
            .height(statisticsVisualTokens().dimens.smallBarHeight),
    )
}

@Composable
internal fun StatisticsRangePillDropdown(
    value: StatisticsDisplayRange,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (StatisticsDisplayRange) -> Unit,
) {
    val triggerWidth = 118.dp
    val menuWidth = 144.dp
    Box(
        modifier = Modifier.width(triggerWidth),
        contentAlignment = Alignment.TopEnd,
    ) {
        FoxholeValuePill(
            value = statisticsDisplayRangeLabel(value),
            modifier = Modifier.fillMaxWidth(),
            expanded = expanded,
            onClick = { onExpandedChange(!expanded) },
            fillContent = true,
        )
        FoxholeDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.width(menuWidth),
            popupGap = 0.dp,
            horizontalAlignment = FoxholeDropdownHorizontalAlignment.AnchorEnd,
        ) {
            DASHBOARD_DISPLAY_RANGES.forEach { range ->
                val selected = range == value
                FoxholeDropdownItem(
                    onClick = {
                        onSelect(range)
                        onExpandedChange(false)
                    },
                    selected = selected,
                    highlightSelected = true,
                ) {
                    Text(
                        text = statisticsDisplayRangeLabel(range),
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

@Composable
internal fun statisticsDisplayRangeLabel(value: StatisticsDisplayRange): String =
    stringResource(
        when (value) {
            StatisticsDisplayRange.HOURS_24 -> R.string.statistics_range_24h
            StatisticsDisplayRange.WEEK -> R.string.statistics_range_week
            StatisticsDisplayRange.MONTH -> R.string.statistics_range_month
            StatisticsDisplayRange.ALL -> R.string.statistics_range_all
        },
    )

@Composable
internal fun dnsCategoryColor(category: DnsProtectionCategory): Color =
    chartColor(dnsCategoryColorToken(category))

internal fun dnsCategoryColorToken(category: DnsProtectionCategory): ChartColorToken =
    when (category) {
        DnsProtectionCategory.ADS -> ChartColorToken.ADS
        DnsProtectionCategory.TRACKERS -> ChartColorToken.TRACKERS
        DnsProtectionCategory.TELEMETRY -> ChartColorToken.TELEMETRY
        DnsProtectionCategory.MALICIOUS -> ChartColorToken.MALICIOUS
    }

@Composable
internal fun dnsCategoryColorMap(): Map<DnsProtectionCategory, Color> =
    DnsProtectionCategory.entries.associateWith { category -> dnsCategoryColor(category) }

@Composable
internal fun AppTrafficTimelineChart(
    samples: List<AppTrafficWindow>,
    range: StatisticsDisplayRange,
    nowMs: Long,
) {
    val context = LocalContext.current
    val buckets = remember(samples, range, nowMs) { trafficTimelineBuckets(samples, range, nowMs) }
    val maxBytes =
        buckets
            .maxOfOrNull { bucket -> max(bucket.txBytes, bucket.rxBytes) }
            ?.coerceAtLeast(1L)
            ?: 1L
    val yMax = niceTimelineTrafficScale(maxBytes)
    val rangeStart = buckets.firstOrNull()?.startedAtMs ?: nowMs
    val rangeEnd = (buckets.lastOrNull()?.startedAtMs ?: rangeStart) + range.policy(rangeStart, nowMs).bucketSizeMs
    val updatedAtMs = samples.maxOfOrNull(AppTrafficWindow::startedAtMs) ?: rangeEnd
    val model =
        com.foxhole.beta.core.statistics.ChartModel(
            id = "app-traffic-timeline",
            title = stringResource(R.string.statistics_apps_title),
            subtitle = stringResource(R.string.statistics_axis_y_bytes, formatBytes(context, yMax)),
            range = range.toStatsRange(),
            xAxis =
            com.foxhole.beta.core.statistics.ChartAxis(
                label = stringResource(R.string.statistics_axis_x_time),
                min = rangeStart.toDouble(),
                max = rangeEnd.toDouble(),
                ticks =
                timelineTicks(
                    range = range,
                    rangeStart = rangeStart,
                    rangeEnd = rangeEnd,
                    nowLabel = stringResource(R.string.statistics_range_now),
                ),
                formatter = com.foxhole.beta.core.statistics.ChartValueFormatter.TIME,
            ),
            yAxis =
            com.foxhole.beta.core.statistics.ChartAxis(
                label = stringResource(R.string.statistics_axis_y_bytes, formatBytes(context, yMax)),
                min = 0.0,
                max = yMax.toDouble(),
                ticks =
                timelineTrafficTicks(context, yMax),
                formatter = com.foxhole.beta.core.statistics.ChartValueFormatter.BYTES,
            ),
            series =
            listOf(
                com.foxhole.beta.core.statistics.ChartSeries(
                    id = "tx",
                    label = stringResource(R.string.traffic_sent),
                    kind = com.foxhole.beta.core.statistics.ChartSeriesKind.LINE,
                    colorToken = com.foxhole.beta.core.statistics.ChartColorToken.TX,
                    points =
                    buckets.map { bucket ->
                        com.foxhole.beta.core.statistics.ChartPoint(
                            bucket.startedAtMs,
                            bucket.txBytes.toDouble(),
                        )
                    },
                ),
                com.foxhole.beta.core.statistics.ChartSeries(
                    id = "rx",
                    label = stringResource(R.string.traffic_received),
                    kind = com.foxhole.beta.core.statistics.ChartSeriesKind.LINE,
                    colorToken = com.foxhole.beta.core.statistics.ChartColorToken.RX,
                    points =
                    buckets.map { bucket ->
                        com.foxhole.beta.core.statistics.ChartPoint(
                            bucket.startedAtMs,
                            bucket.rxBytes.toDouble(),
                        )
                    },
                ),
            ),
            legend =
            com.foxhole.beta.core.statistics.ChartLegendModel(
                items =
                listOf(
                    com.foxhole.beta.core.statistics.ChartLegendItem(
                        "tx",
                        stringResource(R.string.traffic_sent),
                        com.foxhole.beta.core.statistics.ChartColorToken.TX,
                    ),
                    com.foxhole.beta.core.statistics.ChartLegendItem(
                        "rx",
                        stringResource(R.string.traffic_received),
                        com.foxhole.beta.core.statistics.ChartColorToken.RX,
                    ),
                ),
            ),
            emptyState =
            com.foxhole.beta.core.statistics.ChartEmptyState(
                stringResource(R.string.app_statistics_empty),
            ),
            updatedAtMs = updatedAtMs,
        )
    com.foxhole.beta.ui.statistics.charts.TimelineChart(
        model = model,
        lineStrokeWidth = com.foxhole.beta.ui.statistics.charts.chartVisualTokens().lineStrokeWidthCompact,
    )
}

@Composable
internal fun AppTrafficTopStackedChart(
    rows: List<AppTrafficRow>,
    onRowClick: (AppTrafficRow) -> Unit,
) {
    val maxTotal = rows.maxOfOrNull(AppTrafficRow::totalBytes)?.coerceAtLeast(1L) ?: 1L
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.statistics_apps_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatBytes(context, rows.sumOf(AppTrafficRow::totalBytes)),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        ChartLegend()
        rows.forEachIndexed { index, row ->
            AppTrafficStackedBarRow(
                index = index,
                row = row,
                maxTotal = maxTotal,
                onClick = { onRowClick(row) },
            )
        }
    }
}

@Composable
internal fun AppTrafficStackedBarRow(
    index: Int,
    row: AppTrafficRow,
    maxTotal: Long,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val tokens = statisticsVisualTokens()
    val sentColor = chartColor(ChartColorToken.TX)
    val receivedColor = chartColor(ChartColorToken.RX)
    val barDescription =
        "${row.label}: ${formatBytes(context, row.totalBytes)}, ${
            stringResource(R.string.traffic_sent)
        } ${formatBytes(context, row.txBytes)}, ${
            stringResource(R.string.traffic_received)
        } ${formatBytes(context, row.rxBytes)}"
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = RoundedCornerShape(12.dp),
            color = tokens.colors.rowContainer,
            contentColor = tokens.colors.mutedText,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = (index + 1).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        AppIcon(packageName = row.packageName, modifier = Modifier.size(34.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatBytes(context, row.totalBytes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            val total = row.totalBytes.coerceAtLeast(1L).toFloat()
            val barSegments =
                listOf(
                    SegmentedBarSegment(
                        color = sentColor,
                        ratio = row.txBytes.coerceAtLeast(0L).toFloat() / total,
                        minVisibleWidth = 1.dp,
                    ),
                    SegmentedBarSegment(
                        color = receivedColor,
                        ratio = row.rxBytes.coerceAtLeast(0L).toFloat() / total,
                        minVisibleWidth = 1.dp,
                    ),
                )
            SegmentedLinearBar(
                segments = barSegments,
                scale = (row.totalBytes.toFloat() / maxTotal.toFloat()).coerceIn(0f, 1f),
                contentDescription = barDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tokens.dimens.smallBarHeight),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendValueText(
                    color = sentColor,
                    text = "${stringResource(R.string.traffic_sent)} ${formatBytes(context, row.txBytes)}",
                )
                LegendValueText(
                    color = receivedColor,
                    text = "${stringResource(R.string.traffic_received)} ${formatBytes(context, row.rxBytes)}",
                )
            }
        }
    }
}

@Composable
private fun LegendValueText(
    color: Color,
    text: String,
) {
    val mutedText = statisticsVisualTokens().colors.mutedText
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp)) {
            Canvas(modifier = Modifier.size(7.dp).clearAndSetSemantics {}) {
                drawCircle(color)
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = mutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ChartLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendItem(color = chartColor(ChartColorToken.TX), text = stringResource(R.string.traffic_sent))
        LegendItem(color = chartColor(ChartColorToken.RX), text = stringResource(R.string.traffic_received))
    }
}

@Composable
internal fun LegendItem(color: Color, text: String) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = text },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(9.dp).padding(1.dp)) {
            Canvas(modifier = Modifier.size(7.dp).clearAndSetSemantics {}) { drawCircle(color) }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = statisticsVisualTokens().colors.mutedText,
        )
    }
}

@Composable
internal fun AppTrafficMiniChart(samples: List<AppTrafficWindow>) {
    AppTrafficTimelineChart(
        samples = samples,
        range = StatisticsDisplayRange.HOURS_24,
        nowMs = samples.maxOfOrNull(AppTrafficWindow::startedAtMs) ?: System.currentTimeMillis(),
    )
}

internal fun trafficTimelineBuckets(
    samples: List<AppTrafficWindow>,
    range: StatisticsDisplayRange,
    nowMs: Long,
): List<TrafficTimelineBucket> {
    val policy =
        range.policy(
            firstAtMs = samples.minOfOrNull(AppTrafficWindow::startedAtMs) ?: nowMs,
            nowMs = nowMs,
        )
    val bucketMs = policy.bucketSizeMs
    val durationMs = range.durationMs ?: samples.durationForAllRange(nowMs, bucketMs, policy.maxBuckets)
    val startAt = nowMs - durationMs
    val bucketCount = (durationMs / bucketMs).toInt().coerceIn(1, policy.maxBuckets)
    val buckets =
        (0 until bucketCount).associate { index ->
            val startedAt = startAt + index * bucketMs
            startedAt to TrafficTimelineBucket(startedAtMs = startedAt, rxBytes = 0L, txBytes = 0L)
        }.toMutableMap()
    samples
        .asSequence()
        .filter { sample -> sample.startedAtMs >= startAt }
        .forEach { sample ->
            val bucketStart = startAt + ((sample.startedAtMs - startAt) / bucketMs) * bucketMs
            val current = buckets[bucketStart] ?: return@forEach
            buckets[bucketStart] =
                current.copy(
                    rxBytes = current.rxBytes + sample.rxBytes.coerceAtLeast(0L),
                    txBytes = current.txBytes + sample.txBytes.coerceAtLeast(0L),
                )
        }
    return buckets.values.sortedBy(TrafficTimelineBucket::startedAtMs)
}

internal fun timelineTicks(
    range: StatisticsDisplayRange,
    rangeStart: Long,
    rangeEnd: Long,
    nowLabel: String,
): List<com.foxhole.beta.core.statistics.ChartTick> {
    val tickCount = range.policy(rangeStart, rangeEnd).majorTickCount.coerceAtLeast(2)
    val duration = (rangeEnd - rangeStart).coerceAtLeast(1L)
    val formatter =
        when (range) {
            StatisticsDisplayRange.HOURS_24 -> null
            StatisticsDisplayRange.WEEK -> SimpleDateFormat("EEE", Locale.getDefault())
            StatisticsDisplayRange.MONTH,
            StatisticsDisplayRange.ALL,
            -> SimpleDateFormat("MMM d", Locale.getDefault())
        }
    return (0 until tickCount).map { index ->
        val timestamp =
            if (index == tickCount - 1) {
                rangeEnd
            } else {
                rangeStart + duration * index / (tickCount - 1)
            }
        val label =
            when {
                index == tickCount - 1 -> nowLabel
                range == StatisticsDisplayRange.HOURS_24 -> "-${24 - (24 * index / (tickCount - 1))}h"
                else -> formatter?.format(Date(timestamp)).orEmpty()
            }
        com.foxhole.beta.core.statistics.ChartTick(timestamp.toDouble(), label)
    }
}

internal fun timelineTrafficTicks(
    context: Context,
    yMax: Long,
): List<com.foxhole.beta.core.statistics.ChartTick> =
    timelineTrafficTickValues(yMax).map { value ->
        com.foxhole.beta.core.statistics.ChartTick(
            value = value.toDouble(),
            label = if (value == 0L) "0" else formatBytes(context, value),
        )
    }

internal fun timelineTrafficTickValues(yMax: Long): List<Long> {
    val boundedMax = niceTrafficScale(yMax)
    val step = timelineTrafficTickStep(boundedMax)
    val ticks =
        generateSequence(0L) { previous -> previous + step }
            .takeWhile { value -> value <= boundedMax }
            .toMutableList()
    if (ticks.lastOrNull() != boundedMax) {
        ticks += boundedMax
    }
    return ticks
}

private fun timelineTrafficTickStep(yMax: Long): Long {
    val targetIntervals = (MAX_TIMELINE_TRAFFIC_TICKS - 1).coerceAtLeast(1).toLong()
    val rawStep = divideRoundUp(yMax, targetIntervals)
    return roundUpToTimelineTrafficStep(rawStep)
}

internal fun niceTimelineTrafficScale(maxBytes: Long): Long =
    niceTrafficScale(maxBytes.coerceAtLeast(MIN_TIMELINE_TRAFFIC_SCALE_BYTES))

@Composable
internal fun statisticsCountryChartColors(): List<Color> = chartCountryColors()

internal fun niceTrafficScale(maxBytes: Long): Long {
    return roundUpToTimelineTrafficStep(maxBytes.coerceAtLeast(TIMELINE_TRAFFIC_SCALE_STEP_BYTES))
}

private fun roundUpToTimelineTrafficStep(value: Long): Long =
    divideRoundUp(value, TIMELINE_TRAFFIC_SCALE_STEP_BYTES) * TIMELINE_TRAFFIC_SCALE_STEP_BYTES

private fun divideRoundUp(
    value: Long,
    divisor: Long,
): Long =
    if (value <= 0L) {
        0L
    } else {
        1L + (value - 1L) / divisor
    }

internal fun formatPercent(value: Float): String = "${(value.coerceIn(0f, 1f) * 100f).roundToInt()}%"
