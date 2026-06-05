package com.foxhole.beta.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class TrafficMapPoint(
    val countryCode: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val bytes: Long,
    val connections: Int,
    val role: TrafficMapPointRole = TrafficMapPointRole.DESTINATION,
)

@Immutable
data class TrafficMapEdge(
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
    val bytes: Long,
    val role: TrafficMapEdgeRole = TrafficMapEdgeRole.DIRECT,
)

enum class TrafficMapPointRole {
    DESTINATION,
    VPN_ROUTE,
    TOR_EXIT,
}

enum class TrafficMapEdgeRole {
    DIRECT,
    VPN_ROUTE,
    TOR_ROUTE,
}

enum class CountryTrafficRole {
    ORIGIN,
    DESTINATION,
    VPN_ROUTE,
    TOR_EXIT,
}

enum class TrafficMapPeriod {
    FIVE_MINUTES,
    SESSION,
    DAY_24,
}

@Immutable
data class TrafficMapCountryVisual(
    val countryCode: String,
    val bytes: Long,
    val connections: Int,
    val role: CountryTrafficRole,
    val intensity: Float,
    val isNewCountry: Boolean,
    val isRouteNode: Boolean,
)

@Immutable
data class TrafficMapPeriodSnapshot(
    val period: TrafficMapPeriod,
    val destinations: List<TrafficMapPoint> = emptyList(),
    val unknownCountryBytes: Long = 0L,
    val unknownCountryConnections: Int = 0,
    val hiddenCountryCount: Int = 0,
    val totalBytes: Long = 0L,
    val totalConnections: Int = 0,
    val countryCount: Int = 0,
    val sampleWindowLabel: String = "No active connections",
    val lastSampleAtMs: Long? = null,
    val newCountryCodes: Set<String> = emptySet(),
) {
    val hasTraffic: Boolean
        get() = totalBytes > 0L || totalConnections > 0
}

@Immutable
data class TrafficMapPeriodSnapshots(
    val fiveMinutes: TrafficMapPeriodSnapshot = TrafficMapPeriodSnapshot(TrafficMapPeriod.FIVE_MINUTES),
    val session: TrafficMapPeriodSnapshot = TrafficMapPeriodSnapshot(TrafficMapPeriod.SESSION),
    val day24: TrafficMapPeriodSnapshot = TrafficMapPeriodSnapshot(TrafficMapPeriod.DAY_24),
) {
    fun snapshot(period: TrafficMapPeriod): TrafficMapPeriodSnapshot =
        when (period) {
            TrafficMapPeriod.FIVE_MINUTES -> fiveMinutes
            TrafficMapPeriod.SESSION -> session
            TrafficMapPeriod.DAY_24 -> day24
        }
}

@Immutable
data class TrafficMapUiState(
    val originLat: Double = 48.8566,
    val originLon: Double = 2.3522,
    val originCountryCode: String? = null,
    val originCountryName: String? = null,
    val originCity: String? = null,
    val vpnRoute: TrafficMapPoint? = null,
    val torExit: TrafficMapPoint? = null,
    val isAvailable: Boolean = false,
    val destinations: List<TrafficMapPoint> = emptyList(),
    val edges: List<TrafficMapEdge> = emptyList(),
    val highlightedCountries: Set<String> = emptySet(),
    val countryVisuals: List<TrafficMapCountryVisual> = emptyList(),
    val sampleWindowLabel: String = "No active connections",
    val periodSnapshots: TrafficMapPeriodSnapshots = TrafficMapPeriodSnapshots(),
    val lastSampleAtMs: Long? = null,
    val unknownCountryBytes: Long = 0L,
    val unknownCountryConnections: Int = 0,
    val hiddenCountryCount: Int = 0,
    val totalBytes: Long = 0L,
    val totalConnections: Int = 0,
    val countryCount: Int = 0,
)
