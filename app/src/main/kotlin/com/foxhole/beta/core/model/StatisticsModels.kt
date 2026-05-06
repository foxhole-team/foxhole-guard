package com.foxhole.beta.core.model

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

data class StatisticsUiState(
    val range: StatisticsRange,
    val extendedMode: Boolean,
    val profileTraffic: List<ProfileTrafficUiItem>,
    val total: OverallStatisticsUiItem,
    val vpnProtocols: List<ProtocolStatisticsUiItem>,
    val profileComparisons: List<ProfileComparisonUiItem>,
    val transports: List<TransportStatisticsUiItem>,
)

data class ProfileTrafficUiItem(
    val profileId: Long,
    val profileName: String,
    val protocolHint: ProtocolHint,
    val rxBytes: Long,
    val txBytes: Long,
    val updatedAt: Long,
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

data class OverallStatisticsUiItem(
    val totalBytes: Long,
    val vpnSessions: Int,
    val successCount: Int,
    val failureCount: Int,
    val avgLatencyMs: Long?,
    val lastActivityAt: Long?,
)

data class ProtocolStatisticsUiItem(
    val protocol: ProtocolHint,
    val successCount: Int,
    val failureCount: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val avgLatencyMs: Long?,
    val lastUsedAt: Long?,
) {
    val totalAttempts: Int get() = successCount + failureCount
    val successRate: Float get() = if (totalAttempts == 0) 0f else successCount.toFloat() / totalAttempts
    val errorRate: Float get() = if (totalAttempts == 0) 0f else failureCount.toFloat() / totalAttempts
    val totalBytes: Long get() = rxBytes + txBytes
}

data class ProfileComparisonUiItem(
    val protocol: ProtocolHint,
    val left: ProfileComparisonSideUiItem,
    val right: ProfileComparisonSideUiItem,
)

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
