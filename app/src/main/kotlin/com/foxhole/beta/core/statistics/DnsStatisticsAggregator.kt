package com.foxhole.beta.core.statistics

import com.foxhole.beta.core.model.DnsSettings
import com.foxhole.beta.core.model.TrafficWindow
import kotlin.math.roundToInt

fun dnsProtectionSummary(
    trafficWindows: List<TrafficWindow>,
    appRows: List<AppTrafficRow>,
    dnsSettings: DnsSettings,
): DnsProtectionSummary {
    val blocked = trafficWindows.sumOf(TrafficWindow::blockedDns).coerceAtLeast(0)
    val allowed = trafficWindows.sumOf(TrafficWindow::allowedDns).coerceAtLeast(0)
    val categories = enabledDnsProtectionCategories(dnsSettings)
    val categoryRows = splitDnsBlockedByCategory(blocked, categories)
    val totalAppBytes = appRows.sumOf(AppTrafficRow::totalBytes).coerceAtLeast(1L)
    val appDnsRows =
        if (blocked <= 0 || appRows.isEmpty()) {
            emptyList()
        } else {
            appRows
                .filter { row -> row.packageName != OTHER_PACKAGE }
                .mapNotNull { row ->
                    val estimatedBlocked = ((blocked.toDouble() * row.totalBytes.toDouble()) / totalAppBytes.toDouble()).roundToInt()
                    val estimatedAllowed = ((allowed.toDouble() * row.totalBytes.toDouble()) / totalAppBytes.toDouble()).roundToInt()
                    val rowQueries = (estimatedBlocked + estimatedAllowed).coerceAtLeast(1)
                    val blockRatio = estimatedBlocked.toFloat() / rowQueries.toFloat()
                    estimatedBlocked
                        .takeIf { it > 0 }
                        ?.let {
                            DnsProtectionAppRow(
                                packageName = row.packageName,
                                label = row.label,
                                totalBytes = row.totalBytes,
                                estimatedBlockedQueries = it,
                                blockRatio = blockRatio,
                                categoryRatios =
                                    categoryRows.associate { category ->
                                        val blockedShare =
                                            if (blocked <= 0) {
                                                0f
                                            } else {
                                                category.blockedQueries.toFloat() / blocked.toFloat()
                                            }
                                        category.category to (blockedShare * blockRatio).coerceIn(0f, 1f)
                                    },
                                quality = ChartDataQuality.ESTIMATED,
                            )
                        }
                }
                .sortedByDescending(DnsProtectionAppRow::estimatedBlockedQueries)
        }
    return DnsProtectionSummary(
        blockedQueries = blocked,
        allowedQueries = allowed,
        categoryRows = categoryRows,
        appRows = appDnsRows,
        quality = ChartDataQuality.REAL,
        categoryQuality = ChartDataQuality.SYNTHETIC,
        appQuality = ChartDataQuality.ESTIMATED,
    )
}

fun dnsTimelineChartModel(
    buckets: List<TrafficBucket>,
    range: StatsRange,
    startMs: Long,
    endMs: Long,
    updatedAtMs: Long,
): ChartModel {
    val yMax = buckets.maxOfOrNull { bucket -> bucket.blockedDns + bucket.allowedDns }?.coerceAtLeast(1) ?: 1
    return ChartModel(
        id = "dns-timeline",
        title = "DNS protection",
        subtitle = "Allowed and blocked queries per bucket",
        range = range,
        xAxis =
            ChartAxis(
                label = "Time",
                min = startMs.toDouble(),
                max = endMs.toDouble(),
                ticks = emptyList(),
                formatter = ChartValueFormatter.TIME,
            ),
        yAxis =
            ChartAxis(
                label = "Queries",
                min = 0.0,
                max = yMax.toDouble(),
                ticks = listOf(ChartTick(0.0, "0"), ChartTick(yMax.toDouble(), yMax.toString())),
                formatter = ChartValueFormatter.COUNT,
            ),
        series =
            listOf(
                ChartSeries(
                    id = "dns-allowed",
                    label = "Allowed",
                    kind = ChartSeriesKind.STACKED_BAR,
                    colorToken = ChartColorToken.DNS_ALLOWED,
                    points = buckets.map { bucket -> ChartPoint(bucket.bucketStartMs, bucket.allowedDns.toDouble()) },
                ),
                ChartSeries(
                    id = "dns-blocked",
                    label = "Blocked",
                    kind = ChartSeriesKind.STACKED_BAR,
                    colorToken = ChartColorToken.DNS_BLOCKED,
                    points = buckets.map { bucket -> ChartPoint(bucket.bucketStartMs, bucket.blockedDns.toDouble()) },
                ),
            ),
        legend =
            ChartLegendModel(
                items =
                    listOf(
                        ChartLegendItem("dns-allowed", "Allowed", ChartColorToken.DNS_ALLOWED, description = "real"),
                        ChartLegendItem("dns-blocked", "Blocked", ChartColorToken.DNS_BLOCKED, description = "real"),
                    ),
            ),
        emptyState = ChartEmptyState("No DNS data yet"),
        updatedAtMs = updatedAtMs,
        quality = ChartDataQuality.REAL,
    )
}

fun enabledDnsProtectionCategories(settings: DnsSettings): List<DnsProtectionCategory> =
    buildList {
        if (settings.blockAds) add(DnsProtectionCategory.ADS)
        if (settings.blockTrackers) add(DnsProtectionCategory.TRACKERS)
        if (settings.blockAppTelemetry) add(DnsProtectionCategory.TELEMETRY)
        if (settings.blockMaliciousDomains) add(DnsProtectionCategory.MALICIOUS)
    }.ifEmpty {
        DnsProtectionCategory.entries
    }

fun splitDnsBlockedByCategory(
    blocked: Int,
    categories: List<DnsProtectionCategory>,
): List<DnsProtectionCategoryRow> {
    if (blocked <= 0 || categories.isEmpty()) {
        return categories.map { category ->
            DnsProtectionCategoryRow(
                category = category,
                blockedQueries = 0,
                quality = ChartDataQuality.SYNTHETIC,
            )
        }
    }
    val base = blocked / categories.size
    val remainder = blocked % categories.size
    return categories.mapIndexed { index, category ->
        DnsProtectionCategoryRow(
            category = category,
            blockedQueries = base + if (index < remainder) 1 else 0,
            quality = ChartDataQuality.SYNTHETIC,
        )
    }
}
