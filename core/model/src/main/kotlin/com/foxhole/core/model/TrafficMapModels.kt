package com.foxhole.core.model

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
    val ipAddress: String? = null,
    val providerName: String? = null,
    val protocolBadge: String? = null,
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
    DNS_SERVER,
}

enum class TrafficMapEdgeRole {
    DIRECT,
    VPN_ROUTE,
    VPN_DESTINATION,
    TOR_ROUTE,
    TOR_DESTINATION,
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
    DAYS_7,
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
    val sampleWindowLabel: String = "Waiting",
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
    val days7: TrafficMapPeriodSnapshot = TrafficMapPeriodSnapshot(TrafficMapPeriod.DAYS_7),
) {
    fun snapshot(period: TrafficMapPeriod): TrafficMapPeriodSnapshot =
        when (period) {
            TrafficMapPeriod.FIVE_MINUTES -> fiveMinutes
            TrafficMapPeriod.SESSION -> session
            TrafficMapPeriod.DAY_24 -> day24
            TrafficMapPeriod.DAYS_7 -> days7
        }
}

@Immutable
data class TrafficMapCountryDetail(
    val countryCode: String,
    val appRows: List<TrafficMapCountryAppRow> = emptyList(),
    val hostRows: List<TrafficMapCountryHostRow> = emptyList(),
    val firstSeenAtMs: Long? = null,
    val lastSeenAtMs: Long? = null,
) {
    val hasRows: Boolean
        get() = appRows.isNotEmpty() || hostRows.isNotEmpty()
}

@Immutable
data class TrafficMapCountryAppRow(
    val packageName: String,
    val bytes: Long,
    val connections: Int,
    val firstSeenAtMs: Long,
    val lastSeenAtMs: Long,
)

@Immutable
data class TrafficMapCountryHostRow(
    val remoteHost: String,
    val remotePort: Int?,
    val protocol: String,
    val bytes: Long,
    val connections: Int,
    val appCount: Int,
    val firstSeenAtMs: Long,
    val lastSeenAtMs: Long,
)

/** Live Android carrier that currently gives the I2P router its device routing surface. */
enum class TrafficMapI2pCarrier {
    DEVICE,

    VPN,

    /** Standalone Tor-only runtime owns the Android carrier itself. */
    TOR,
}

@Immutable
data class TrafficMapUiState(
    val originLat: Double = 48.8566,
    val originLon: Double = 2.3522,
    val originCountryCode: String? = null,
    val originCountryName: String? = null,
    val originCity: String? = null,
    val originIpAddress: String? = null,
    val vpnRoute: TrafficMapPoint? = null,
    val torExit: TrafficMapPoint? = null,
    val dnsServer: TrafficMapPoint? = null,

    val exitLatencyMs: Long? = null,

    val torExitLatencyMs: Long? = null,

    val torBypassesVpn: Boolean = false,

    val torRouteActive: Boolean = false,

    val firewallActive: Boolean = false,

    val firewallTransparent: Boolean = false,
    // True only while the live I2P phase and its Android carrier are both confirmed.
    val i2pActive: Boolean = false,

    val i2pCarrier: TrafficMapI2pCarrier? = null,

    val torAppPackages: List<String> = emptyList(),

    val proxyModeActive: Boolean = false,

    val splitTunnelActive: Boolean = false,

    val splitAppPackages: List<String> = emptyList(),

    val directAppPackages: List<String> = emptyList(),

    val lanProxyEnabled: Boolean = false,

    val networkJournalEnabled: Boolean = false,
    val isAvailable: Boolean = false,
    val destinations: List<TrafficMapPoint> = emptyList(),
    val edges: List<TrafficMapEdge> = emptyList(),
    val highlightedCountries: Set<String> = emptySet(),
    val countryVisuals: List<TrafficMapCountryVisual> = emptyList(),
    val sampleWindowLabel: String = "Waiting",
    val periodSnapshots: TrafficMapPeriodSnapshots = TrafficMapPeriodSnapshots(),
    val countryDetailsByCode: Map<String, TrafficMapCountryDetail> = emptyMap(),
    val lastSampleAtMs: Long? = null,
    val unknownCountryBytes: Long = 0L,
    val unknownCountryConnections: Int = 0,
    val hiddenCountryCount: Int = 0,
    val totalBytes: Long = 0L,
    val totalConnections: Int = 0,
    val countryCount: Int = 0,
)
