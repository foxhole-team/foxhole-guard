package com.foxhole.guard.statistics

import androidx.compose.runtime.Immutable

@Immutable
data class AppTrafficRow(
    val packageName: String,
    val label: String,
    val txBytes: Long,
    val rxBytes: Long,
    val badges: Set<AppAnomalyBadge>,
    val quality: ChartDataQuality = ChartDataQuality.REAL,
) {
    val totalBytes: Long get() = txBytes + rxBytes
}

@Immutable
data class CountryTrafficUiRow(
    val countryCode: String,
    val label: String,
    val bytes: Long,
    val sessions: Int,
    val quality: ChartDataQuality = ChartDataQuality.REAL,
)

@Immutable
data class DnsProtectionSummary(
    val blockedQueries: Int,
    val allowedQueries: Int,
    val categoryRows: List<DnsProtectionCategoryRow>,
    val appRows: List<DnsProtectionAppRow>,
    val domainRows: List<DnsProtectionDomainRow> = emptyList(),
    val quality: ChartDataQuality = ChartDataQuality.REAL,
    val categoryQuality: ChartDataQuality = ChartDataQuality.SYNTHETIC,
    val appQuality: ChartDataQuality = ChartDataQuality.ESTIMATED,
    val domainQuality: ChartDataQuality = ChartDataQuality.REAL,
) {
    val totalQueries: Int get() = blockedQueries + allowedQueries
    val blockRatio: Float get() = if (totalQueries == 0) 0f else blockedQueries.toFloat() / totalQueries.toFloat()
}

@Immutable
data class DnsProtectionAppRow(
    val packageName: String,
    val label: String,
    val totalBytes: Long,
    val estimatedBlockedQueries: Int,
    val blockRatio: Float,
    val categoryRatios: Map<DnsProtectionCategory, Float>,
    val quality: ChartDataQuality = ChartDataQuality.ESTIMATED,
)

@Immutable
data class DnsProtectionCategoryRow(
    val category: DnsProtectionCategory,
    val blockedQueries: Int,
    val quality: ChartDataQuality = ChartDataQuality.SYNTHETIC,
)

@Immutable
data class DnsProtectionDomainRow(
    val domain: String,
    val blockedQueries: Long,
    val quality: ChartDataQuality = ChartDataQuality.REAL,
)

enum class DnsProtectionCategory {
    ADS,
    TRACKERS,
    TELEMETRY,
    MALICIOUS,
}

enum class StatisticsDisplayRange {
    HOURS_24,
    WEEK,
    MONTH,
    ALL,
}

enum class AppAnomalyBadge {
    NORMAL,
    UNUSUAL,
    HIGH_UPLOAD,
    NEW_ROUTE,
    BACKGROUND,
}
