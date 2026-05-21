package com.foxhole.beta.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class NetworkType {
    WIFI,
    MOBILE,
    UNKNOWN,
}

@Serializable
enum class VpnMode {
    NORMAL,
    TOR,
    I2P,
    DIRECT_BYPASS,
}

@Serializable
enum class AnomalyType {
    APP_UPLOAD_SPIKE,
    APP_BACKGROUND_TRAFFIC,
    TOTAL_TRAFFIC_SPIKE,
    NEW_DESTINATION_COUNTRY,
    DNS_BLOCK_RATIO_SPIKE,
    RECONNECT_STORM,
    LATENCY_SHIFT,
    TOR_OR_I2P_ROUTE_MISMATCH,
}

@Serializable
enum class AnomalySeverity {
    SILENT,
    ACTIVITY_LOG,
    NOTIFICATION,
    HIGH,
}

@Serializable
enum class AnomalySensitivity {
    NORMAL,
    STRICT,
}

@Serializable
enum class AnomalyHistoryRetention(val retentionHours: Int) {
    HOURS_24(24),
    DAYS_7(168),
    DAYS_30(720),
}

@Serializable
data class AnomalySettings(
    val enabled: Boolean = false,
    val notifyUnusualTraffic: Boolean = true,
    val sensitivity: AnomalySensitivity = AnomalySensitivity.NORMAL,
    val analyzeBackgroundTraffic: Boolean = true,
    val analyzeDestinationCountries: Boolean = true,
    val historyRetention: AnomalyHistoryRetention = AnomalyHistoryRetention.DAYS_7,
)

data class TrafficWindow(
    val startedAtMs: Long,
    val durationSec: Int,
    val networkType: NetworkType,
    val vpnMode: VpnMode,
    val profileId: String?,
    val protocol: String?,
    val rxBytes: Long,
    val txBytes: Long,
    val blockedDns: Int,
    val allowedDns: Int,
    val reconnects: Int,
    val latencyMs: Int?,
    val destinationCountries: Map<String, Long>,
    val blockedDnsDomains: Map<String, Long> = emptyMap(),
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

data class AppTrafficWindow(
    val packageName: String,
    val startedAtMs: Long,
    val durationSec: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val foreground: Boolean?,
    val networkType: NetworkType,
    val uid: Int = 0,
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

data class NetworkActivityEvent(
    val id: Long = 0,
    val timestampMs: Long,
    val packageNames: List<String>,
    val protocol: String,
    val remoteHost: String,
    val remotePort: Int?,
    val countryCode: String?,
    val bytesRx: Long,
    val bytesTx: Long,
    val profileId: Long?,
    val sessionId: String?,
) {
    val totalBytes: Long get() = bytesRx + bytesTx
}

data class TrafficBaseline(
    val key: String,
    val profileId: String?,
    val protocol: String?,
    val networkType: NetworkType,
    val hourBucket: Int,
    val metric: String,
    val median: Double,
    val mad: Double,
    val ewma: Double,
    val ewmad: Double,
    val sampleCount: Int,
    val lastUpdatedAt: Long,
)

data class AppBaseline(
    val key: String,
    val packageName: String,
    val profileId: String?,
    val protocol: String?,
    val networkType: NetworkType,
    val hourBucket: Int,
    val metric: String,
    val median: Double,
    val mad: Double,
    val ewma: Double,
    val ewmad: Double,
    val sampleCount: Int,
    val lastUpdatedAt: Long,
)

data class AnomalySignal(
    val type: AnomalyType,
    val severity: Double,
    val reason: String,
    val evidence: Map<String, String>,
)

data class AnomalyEvent(
    val id: Long = 0,
    val createdAtMs: Long,
    val type: AnomalyType,
    val severity: AnomalySeverity,
    val score: Int,
    val reason: String,
    val evidence: Map<String, String>,
    val packageName: String? = null,
    val profileId: String? = null,
    val protocol: String? = null,
    val notificationShown: Boolean = false,
)
