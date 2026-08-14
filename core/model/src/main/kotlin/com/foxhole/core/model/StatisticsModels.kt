package com.foxhole.core.model

import androidx.compose.runtime.Immutable

enum class StatisticsRange {
    HOUR,
    DAY,
    DAYS_3,
    WEEK,
    MONTH,
    MONTHS_3,
    FOREVER,
    ALL,
}

enum class TransportProtocol {
    TCP,
    UDP,
    UNKNOWN,
}

@Immutable
data class StatisticsUiState(
    val range: StatisticsRange,
    val extendedMode: Boolean,
    val profileTraffic: List<ProfileTrafficUiItem>,
    val total: OverallStatisticsUiItem,
    val vpnProtocols: List<ProtocolStatisticsUiItem>,
    val profileComparisons: List<ProfileComparisonUiItem>,
    val transports: List<TransportStatisticsUiItem>,
)

@Immutable
data class ProfileTrafficUiItem(
    val profileId: Long,
    val profileName: String,
    val protocolHint: ProtocolHint,
    val protocolOptionId: String? = null,
    val transport: TransportProtocol = TransportProtocol.UNKNOWN,
    val rxBytes: Long,
    val txBytes: Long,
    val updatedAt: Long,
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

@Immutable
data class OverallStatisticsUiItem(
    val totalBytes: Long,
    val vpnSessions: Int,
    val successCount: Int,
    val failureCount: Int,
    val avgLatencyMs: Long?,
    val lastActivityAt: Long?,
)

@Immutable
data class ProtocolStatisticsUiItem(
    val protocol: ProtocolHint,
    val successCount: Int,
    val failureCount: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val avgLatencyMs: Long?,
    val lastUsedAt: Long?,
    val quality: ProtocolQuality = ProtocolQuality.MEASURED,
) {
    val totalAttempts: Int get() = successCount + failureCount
    val hasMeasuredAttempts: Boolean get() = totalAttempts > 0
    val successRateOrNull: Float? get() = if (hasMeasuredAttempts) successCount.toFloat() / totalAttempts else null
    val errorRateOrNull: Float? get() = if (hasMeasuredAttempts) failureCount.toFloat() / totalAttempts else null
    val successRate: Float get() = if (totalAttempts == 0) 0f else successCount.toFloat() / totalAttempts
    val errorRate: Float get() = if (totalAttempts == 0) 0f else failureCount.toFloat() / totalAttempts
    val totalBytes: Long get() = rxBytes + txBytes
}

enum class ProtocolQuality {
    MEASURED,
    TRAFFIC_ONLY,
}

@Immutable
data class ProfileComparisonUiItem(
    val protocol: ProtocolHint,
    val left: ProfileComparisonSideUiItem,
    val right: ProfileComparisonSideUiItem,
)

@Immutable
data class ProfileComparisonSideUiItem(
    val profileId: Long,
    val profileName: String,
    val successCount: Int,
    val failureCount: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val avgLatencyMs: Long?,
) {
    val totalAttempts: Int get() = successCount + failureCount
    val stability: Float get() = if (totalAttempts == 0) 0f else successCount.toFloat() / totalAttempts
    val errorRate: Float get() = if (totalAttempts == 0) 0f else failureCount.toFloat() / totalAttempts
    val totalBytes: Long get() = rxBytes + txBytes
}

@Immutable
data class TransportStatisticsUiItem(
    val transport: TransportProtocol,
    val successCount: Int,
    val failureCount: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val avgLatencyMs: Long?,
) {
    val totalAttempts: Int get() = successCount + failureCount
    val successRate: Float get() = if (totalAttempts == 0) 0f else successCount.toFloat() / totalAttempts
    val errorRate: Float get() = if (totalAttempts == 0) 0f else failureCount.toFloat() / totalAttempts
    val totalBytes: Long get() = rxBytes + txBytes
}
