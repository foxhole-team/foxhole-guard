package com.foxhole.guard.ui

import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.TrafficMapPoint
import com.foxhole.guard.traffic.TrafficMapRepository

internal object TrafficMapBenchmarkStress {
    const val IntentExtra = "com.foxhole.guard.extra.TRAFFIC_MAP_BENCHMARK_STRESS"
    const val MaxLoadMode = "max_load"

    fun buildState(
        repository: TrafficMapRepository,
        nowMs: Long = System.currentTimeMillis(),
    ) = repository.trafficMapStateSnapshot(
        originIpInfo = stressIpInfo("198.51.100.10", "US", "United States", "New York", nowMs),
        routeIpInfo = stressIpInfo("203.0.113.20", "DE", "Germany", "Frankfurt", nowMs),
        torIpInfo = stressIpInfo("192.0.2.30", "NL", "Netherlands", "Amsterdam", nowMs),
        runtimeAvailable = true,
        destinations = stressDestinations(),
        newCountryCodes = StressNewCountryCodes,
    )

    private fun stressDestinations(): List<TrafficMapPoint> {
        val baseConnections = TrafficMapRepository.MaxRetainedConnectionSamples / StressDestinationCountryCodes.size
        val extraConnections = TrafficMapRepository.MaxRetainedConnectionSamples % StressDestinationCountryCodes.size
        return StressDestinationCountryCodes.mapIndexed { index, countryCode ->
            val coordinate = TrafficMapRepository.TrafficMapCountryCoordinates.getValue(countryCode)
            val connections = baseConnections + if (index < extraConnections) 1 else 0
            TrafficMapPoint(
                countryCode = coordinate.countryCode,
                label = coordinate.label,
                lat = coordinate.lat,
                lon = coordinate.lon,
                bytes = ((StressDestinationCountryCodes.size - index) * 1_048_576L) + (connections * 4_096L),
                connections = connections,
            )
        }
    }

    private fun stressIpInfo(
        ip: String,
        countryCode: String,
        countryName: String,
        city: String,
        nowMs: Long,
    ) = IpInfo(
        ip = ip,
        countryCode = countryCode,
        countryName = countryName,
        city = city,
        isp = "FoxHole benchmark",
        fetchedAt = nowMs,
    )

    private val StressNewCountryCodes = setOf("AU", "BR", "CA", "CN", "HK", "ID", "IN", "JP")
    private val StressDestinationCountryCodes =
        listOf(
            "AU",
            "BR",
            "CA",
            "CH",
            "CN",
            "DE",
            "ES",
            "FI",
            "FR",
            "GB",
            "HK",
            "ID",
            "IE",
            "IN",
            "IT",
            "JP",
            "KR",
            "MX",
            "NL",
            "NO",
            "PL",
            "RO",
            "RU",
            "SE",
            "SG",
            "TR",
            "TW",
            "UA",
            "US",
            "ZA",
        ).take(TrafficMapRepository.MaxTrafficMapDestinations)
}

internal object FoxholeBenchmarkIntentExtras {
    const val TestTagsAsResourceId = "com.foxhole.guard.extra.TEST_TAGS_AS_RESOURCE_ID"
}
