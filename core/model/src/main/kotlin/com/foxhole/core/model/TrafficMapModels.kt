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
    // Latency of the active connection (the value shown on the profile dashboard), rendered on the
    // arrow into the traffic exit. Null when no probe result is known.
    val exitLatencyMs: Long? = null,
    // Latency measured through the Tor circuit, rendered on the Tor exit lane of the split route
    // scheme. Currently only known when the latency probe itself ran through Tor (Tor-only
    // runtime); a dedicated beside/inside-VPN Tor probe is a follow-up.
    val torExitLatencyMs: Long? = null,
    // True when Tor is routed in parallel and bypasses the VPN tunnel (Tor sits before the VPN in
    // the route chain), false when Tor is tunnelled inside the VPN.
    val torBypassesVpn: Boolean = false,
    // True when the Tor route is engaged (Tor-over-VPN enabled or a Tor-only runtime is up), even
    // before the exit geo resolves. Drives the "TOR"/"VPN TOR" route label independently of torExit,
    // which needs a geo-located point to draw.
    val torRouteActive: Boolean = false,
    // True when the local firewall guard tunnel is running (no remote VPN server involved).
    val firewallActive: Boolean = false,
    // True when the running firewall guard was raised only to carry `.i2p` (I2P + allow-outside-tunnel
    // with no manual firewall): it is a transparent pass-through, so the route node names it as such.
    val firewallTransparent: Boolean = false,
    // True when the independent I2P node is engaged alongside the active route (VPN, TOR, or the
    // firewall guard with "allow connections outside the tunnel"). Adds "I2P" to the route label.
    val i2pActive: Boolean = false,
    // Packages routed through Tor when the Tor scope is "selected apps"; empty for all-apps scope.
    val torAppPackages: List<String> = emptyList(),
    // True when the active runtime serves the profile as a LOCAL PROXY (traffic mode PROXY): the
    // device's own traffic exits directly, only proxy-configured clients ride the profile.
    val proxyModeActive: Boolean = false,
    // Tunnel runs a per-app split (include/exclude): part of the device traffic stays direct.
    val splitTunnelActive: Boolean = false,
    // Packages riding the VPN lane of an active include-mode split; empty in exclude mode (there
    // the picked apps are the DIRECT side, not the tunnel side).
    val splitAppPackages: List<String> = emptyList(),
    // Packages that LEAVE the tunnel on the direct branch of an active exclude-mode split; empty
    // in include mode (there the direct side is "everything else" and cannot be enumerated).
    val directAppPackages: List<String> = emptyList(),
    // True when the local proxy surface is also exposed to the LAN.
    val lanProxyEnabled: Boolean = false,
    // True when the network activity journal is recording: gates the "enable the journal" hint in
    // the country detail popup (a country legitimately without rows must not show the hint).
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
