package com.foxhole.beta.core.model

data class TrafficMapPoint(
    val countryCode: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val bytes: Long,
    val connections: Int,
    val role: TrafficMapPointRole = TrafficMapPointRole.DESTINATION,
)

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
)
