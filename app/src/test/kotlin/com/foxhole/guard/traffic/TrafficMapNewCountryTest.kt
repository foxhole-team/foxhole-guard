package com.foxhole.guard.traffic

import com.foxhole.core.model.CountryTrafficRole
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.TrafficMapPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMapNewCountryTest {
    @Test
    fun `accumulator marks only countries that appeared in the current live window`() {
        val first =
            TrafficMapConnectionAccumulator()
                .updatedForBatch(
                    TrafficMapSampleBatch(
                        samples = listOf(
                            trafficMapSample(connectionId = "us", countryCode = "US", bytes = 10L),
                            trafficMapSample(connectionId = "de", countryCode = "DE", bytes = 20L),
                        ),
                        runtimeAvailable = true,
                    ),
                )

        val second =
            first.updatedForBatch(
                TrafficMapSampleBatch(
                    samples = listOf(
                        trafficMapSample(connectionId = "de", countryCode = "DE", bytes = 30L),
                        trafficMapSample(connectionId = "fr", countryCode = "FR", bytes = 40L),
                    ),
                    runtimeAvailable = true,
                ),
            )

        assertEquals(setOf("US", "DE"), first.newCountryCodes)
        assertEquals(setOf("FR"), second.newCountryCodes)
        assertEquals(
            emptySet<String>(),
            second
                .updatedForBatch(TrafficMapSampleBatch(samples = emptyList(), runtimeAvailable = false))
                .newCountryCodes,
        )
    }

    @Test
    fun `accumulator keeps new countries for rolling five minute filter`() {
        val startMs = 1_000_000L
        val first =
            TrafficMapConnectionAccumulator()
                .updatedWith(
                    samples = listOf(trafficMapSample(connectionId = "us", countryCode = "US", bytes = 10L)),
                    nowMs = startMs,
                )
        val second =
            first.updatedWith(
                samples = listOf(
                    trafficMapSample(connectionId = "us", countryCode = "US", bytes = 20L),
                    trafficMapSample(connectionId = "fr", countryCode = "FR", bytes = 5L),
                ),
                nowMs = startMs + 60_000L,
            )

        assertEquals(setOf("US", "FR"), second.recentNewCountryCodes(nowMs = startMs + 60_000L))
        assertEquals(setOf("FR"), second.recentNewCountryCodes(nowMs = startMs + 301_000L))
        assertEquals(emptySet<String>(), second.recentNewCountryCodes(nowMs = startMs + 361_000L))
    }

    @Test
    fun `traffic map state marks new destination country visuals without marking route nodes`() {
        val state =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo = trafficMapIpInfo(
                    countryCode = "US",
                    countryName = "United States",
                    city = "New York",
                    isp = "Device ISP",
                ),
                routeIpInfo = trafficMapIpInfo(
                    countryCode = "NL",
                    countryName = "Netherlands",
                    city = "Amsterdam",
                    isp = "Tunnel ISP",
                ),
                runtimeAvailable = true,
                destinations = listOf(
                    trafficMapPoint("FR").copy(bytes = 4_096L, connections = 2),
                    trafficMapPoint("DE").copy(bytes = 2_048L, connections = 1),
                ),
                newCountryCodes = setOf("FR", "NL"),
            )

        val destinationVisuals = state.countryVisuals.filter { visual -> visual.role == CountryTrafficRole.DESTINATION }
        val routeVisual = state.countryVisuals.single { visual -> visual.role == CountryTrafficRole.VPN_ROUTE }

        assertTrue(destinationVisuals.single { visual -> visual.countryCode == "FR" }.isNewCountry)
        assertFalse(destinationVisuals.single { visual -> visual.countryCode == "DE" }.isNewCountry)
        assertFalse(routeVisual.isNewCountry)
    }
}

private fun trafficMapSample(
    connectionId: String,
    countryCode: String,
    bytes: Long,
): TrafficMapConnectionSample =
    TrafficMapConnectionSample(
        connectionId = connectionId,
        countryCode = countryCode,
        bytes = bytes,
    )

private fun trafficMapIpInfo(
    countryCode: String,
    countryName: String,
    city: String,
    isp: String,
): IpInfo =
    IpInfo(
        ip = "198.51.100.20",
        ipv4 = "198.51.100.20",
        countryCode = countryCode,
        countryName = countryName,
        city = city,
        isp = isp,
        fetchedAt = 1_000L,
    )

private fun trafficMapPoint(countryCode: String): TrafficMapPoint {
    val coordinate = TrafficMapRepository.TrafficMapCountryCoordinates.getValue(countryCode)
    return TrafficMapPoint(
        countryCode = countryCode,
        label = coordinate.label,
        lat = coordinate.lat,
        lon = coordinate.lon,
        bytes = 1_024L,
        connections = 1,
    )
}
