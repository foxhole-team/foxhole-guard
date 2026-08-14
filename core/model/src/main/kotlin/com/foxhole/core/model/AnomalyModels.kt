package com.foxhole.core.model

import androidx.compose.runtime.Immutable
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

    // Retired detector (destination-country heuristic); the value stays so persisted events decode.
    TOR_OR_I2P_ROUTE_MISMATCH,
    DORMANT_APP_NETWORK_ACTIVITY,

    // A flow destination matched the threat-intel bundle's published indicator list (the Sentinel
    // network IOC matcher); evidence carries the destination and the listed indicator.
    KNOWN_THREAT_DESTINATION,
}

/**
 * Coarse expectation of how an app uses the network, resolved from the platform app category.
 * CONTENT_HEAVY apps (video/audio/games) legitimately move large download volumes, so
 * download-shaped volume spikes from them are dampened; upload-shaped traffic never is.
 */
@Serializable
enum class AppNetworkUsageCategory {
    CONTENT_HEAVY,
    STANDARD,
}

/**
 * Long-horizon record that a package has been seen on the network. Unlike traffic windows this is
 * a tiny aggregate that survives statistics retention, so a package silent for weeks can be
 * recognized as dormant when it suddenly produces traffic again.
 */
@Immutable
data class AppNetworkPresence(
    val packageName: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val windowCount: Long,
)

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
@Immutable
data class AnomalySettings(
    val enabled: Boolean = false,
    val notifyUnusualTraffic: Boolean = false,
    val sensitivity: AnomalySensitivity = AnomalySensitivity.NORMAL,
    val analyzeBackgroundTraffic: Boolean = false,
    val analyzeDestinationCountries: Boolean = false,
    val historyRetention: AnomalyHistoryRetention = AnomalyHistoryRetention.DAYS_7,
    // Packages fully excluded from per-app anomaly analysis; device-wide detectors still see totals.
    val excludedPackages: List<String> = emptyList(),
)

@Immutable
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
    // Real per-category block counts (per-category rule-set tags); empty on the legacy merged list.
    val blockedDnsByCategory: Map<DnsFilterCategory, Long> = emptyMap(),
    // Real per-app block counts (blocked domains joined against observed DNS queries).
    val blockedDnsApps: Map<String, Long> = emptyMap(),
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

// One measured protocol outcome (smart-start probe, reconnect probe or server ping); persisted so
// profile details can chart real latency distributions instead of a single last-value snapshot.
@Immutable
data class ProtocolMetricEvent(
    val timestampMs: Long,
    val profileId: Long,
    val optionId: String?,
    val protocol: String,
    val kind: ProtocolMetricEventKind,
    val latencyMs: Long?,
    val reasonCode: String? = null,
)

enum class ProtocolMetricEventKind {
    PROBE_SUCCESS,
    PROBE_FAILURE,
    SERVER_PING,
}

@Immutable
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

@Immutable
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

@Immutable
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

@Immutable
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

@Immutable
data class AnomalySignal(
    val type: AnomalyType,
    val severity: Double,
    val reason: String,
    val evidence: Map<String, String>,
)

@Immutable
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
