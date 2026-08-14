package com.foxhole.guard.statistics

import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.TrafficWindow
import kotlin.math.roundToInt

fun dnsProtectionSummary(
    trafficWindows: List<TrafficWindow>,
    appRows: List<AppTrafficRow>,
    dnsSettings: DnsSettings,
): DnsProtectionSummary {
    val blocked = trafficWindows.sumOf(TrafficWindow::blockedDns).coerceAtLeast(0)
    val allowed = trafficWindows.sumOf(TrafficWindow::allowedDns).coerceAtLeast(0)
    val categories = enabledDnsProtectionCategories(dnsSettings)
    // Real per-category counts exist once the per-category rule sets are installed; before that
    // (or for windows recorded on the legacy merged list) fall back to the even synthetic split.
    val realCategoryRows = realDnsBlockedByCategory(trafficWindows, categories)
    val categoryRows = realCategoryRows ?: splitDnsBlockedByCategory(blocked, categories)
    val categoryQuality =
        when {
            realCategoryRows == null -> ChartDataQuality.SYNTHETIC
            realCategoryRows.sumOf(DnsProtectionCategoryRow::blockedQueries) >= blocked -> ChartDataQuality.REAL
            else -> ChartDataQuality.PARTIAL
        }
    val domainRows = dnsProtectionDomainRows(trafficWindows)
    val totalAppBytes = appRows.sumOf(AppTrafficRow::totalBytes).coerceAtLeast(1L)
    // Real per-app counts (blocked domains joined to the requesting app's DNS queries) take
    // precedence; the byte-share estimate only remains for ranges without measured data.
    val realAppRows = realDnsBlockedByApp(trafficWindows, appRows, blocked, categoryRows)
    val appDnsRows =
        if (realAppRows != null) {
            realAppRows
        } else if (blocked <= 0 || appRows.isEmpty()) {
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
        domainRows = domainRows,
        quality = ChartDataQuality.REAL,
        categoryQuality = categoryQuality,
        appQuality = if (realAppRows != null) ChartDataQuality.REAL else ChartDataQuality.ESTIMATED,
        domainQuality = ChartDataQuality.REAL,
    )
}

/**
 * Real per-app block rows summed from the windows' measured app attribution. Returns null when no
 * window carries per-app data (process info or sniffed domains unavailable) so the caller can fall
 * back to the labeled byte-share estimate.
 */
fun realDnsBlockedByApp(
    trafficWindows: List<TrafficWindow>,
    appRows: List<AppTrafficRow>,
    blockedTotal: Int,
    categoryRows: List<DnsProtectionCategoryRow>,
): List<DnsProtectionAppRow>? {
    val totals = mutableMapOf<String, Long>()
    trafficWindows.forEach { window ->
        window.blockedDnsApps.forEach { (packageName, count) ->
            if (count > 0L && packageName.isNotBlank()) {
                totals[packageName] = (totals[packageName] ?: 0L) + count
            }
        }
    }
    if (totals.isEmpty()) {
        return null
    }
    val trafficByPackage = appRows.associateBy(AppTrafficRow::packageName)
    val categoryBlocked = categoryRows.sumOf(DnsProtectionCategoryRow::blockedQueries).coerceAtLeast(1)
    return totals.entries
        .map { (packageName, blockedCount) ->
            val traffic = trafficByPackage[packageName]
            // With measured data the ratio reads as "this app's share of all blocked queries".
            val blockShare = (blockedCount.toFloat() / blockedTotal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            DnsProtectionAppRow(
                packageName = packageName,
                label = traffic?.label ?: packageName,
                totalBytes = traffic?.totalBytes ?: 0L,
                estimatedBlockedQueries = blockedCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                blockRatio = blockShare,
                categoryRatios =
                categoryRows.associate { category ->
                    val categoryShare = category.blockedQueries.toFloat() / categoryBlocked.toFloat()
                    category.category to (categoryShare * blockShare).coerceIn(0f, 1f)
                },
                quality = ChartDataQuality.REAL,
            )
        }
        .sortedByDescending(DnsProtectionAppRow::estimatedBlockedQueries)
}

/**
 * Sums the measured per-category block counts recorded by the per-category rule-set tags. Returns
 * null when no window in the range carries category data (legacy merged rule set) — the caller
 * then falls back to the synthetic split.
 */
fun realDnsBlockedByCategory(
    trafficWindows: List<TrafficWindow>,
    categories: List<DnsProtectionCategory>,
): List<DnsProtectionCategoryRow>? {
    val totals = mutableMapOf<DnsProtectionCategory, Long>()
    trafficWindows.forEach { window ->
        window.blockedDnsByCategory.forEach { (category, count) ->
            if (count > 0L) {
                val uiCategory = category.toDnsProtectionCategory()
                totals[uiCategory] = (totals[uiCategory] ?: 0L) + count
            }
        }
    }
    if (totals.isEmpty()) {
        return null
    }
    val visibleCategories = (categories + totals.keys).distinct()
    return visibleCategories.map { category ->
        DnsProtectionCategoryRow(
            category = category,
            blockedQueries = (totals[category] ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            quality = ChartDataQuality.REAL,
        )
    }
}

fun DnsFilterCategory.toDnsProtectionCategory(): DnsProtectionCategory =
    when (this) {
        DnsFilterCategory.ADS -> DnsProtectionCategory.ADS
        DnsFilterCategory.TRACKERS -> DnsProtectionCategory.TRACKERS
        DnsFilterCategory.TELEMETRY -> DnsProtectionCategory.TELEMETRY
        DnsFilterCategory.MALICIOUS -> DnsProtectionCategory.MALICIOUS
    }

fun dnsProtectionDomainRows(trafficWindows: List<TrafficWindow>): List<DnsProtectionDomainRow> =
    trafficWindows
        .asSequence()
        .flatMap { window -> window.blockedDnsDomains.asSequence() }
        .filter { (domain, count) -> domain.isNotBlank() && count > 0L }
        .groupBy({ (domain, _) -> domain }, { (_, count) -> count })
        .map { (domain, counts) ->
            DnsProtectionDomainRow(
                domain = domain,
                blockedQueries = counts.sum(),
                quality = ChartDataQuality.REAL,
            )
        }
        .sortedWith(
            compareByDescending<DnsProtectionDomainRow> { row -> row.blockedQueries }
                .thenBy { row -> row.domain },
        )

fun enabledDnsProtectionCategories(settings: DnsSettings): List<DnsProtectionCategory> =
    if (!settings.filteringEnabled) {
        emptyList()
    } else {
        buildList {
            if (settings.blockAds) add(DnsProtectionCategory.ADS)
            if (settings.blockTrackers) add(DnsProtectionCategory.TRACKERS)
            if (settings.blockAppTelemetry) add(DnsProtectionCategory.TELEMETRY)
            if (settings.blockMaliciousDomains) add(DnsProtectionCategory.MALICIOUS)
        }
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
