package com.foxhole.beta.ui

import com.foxhole.beta.core.model.TrafficMapPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMapDetailControlsTest {
    @Test
    fun `detail destinations sort by sessions country and total`() {
        val points =
            listOf(
                trafficMapPoint("DE", "Germany", bytes = 1_000L, connections = 8),
                trafficMapPoint("US", "United States", bytes = 8_000L, connections = 2),
                trafficMapPoint("BR", "Brazil", bytes = 4_000L, connections = 4),
            )

        assertEquals(
            listOf("US", "BR", "DE"),
            trafficMapDetailDestinations(
                destinations = points,
                sort = TrafficMapDetailSort.TOTAL,
                filter = TrafficMapDetailFilter.ALL,
                range = TrafficMapDetailRange.ALL,
            ).map(TrafficMapPoint::countryCode),
        )
        assertEquals(
            listOf("DE", "BR", "US"),
            trafficMapDetailDestinations(
                destinations = points,
                sort = TrafficMapDetailSort.SESSIONS,
                filter = TrafficMapDetailFilter.ALL,
                range = TrafficMapDetailRange.ALL,
            ).map(TrafficMapPoint::countryCode),
        )
        assertEquals(
            listOf("BR", "DE", "US"),
            trafficMapDetailDestinations(
                destinations = points,
                sort = TrafficMapDetailSort.COUNTRY,
                filter = TrafficMapDetailFilter.ALL,
                range = TrafficMapDetailRange.ALL,
            ).map(TrafficMapPoint::countryCode),
        )
    }

    @Test
    fun `detail destinations apply heavy filter and range after sorting`() {
        val points =
            (1..12).map { index ->
                trafficMapPoint(
                    countryCode = "C$index",
                    label = "Country $index",
                    bytes = index * 512_000L,
                    connections = index,
                )
            }

        val heavyTopTen =
            trafficMapDetailDestinations(
                destinations = points,
                sort = TrafficMapDetailSort.TOTAL,
                filter = TrafficMapDetailFilter.HEAVY,
                range = TrafficMapDetailRange.TOP_10,
            )

        assertEquals(10, heavyTopTen.size)
        assertEquals("C12", heavyTopTen.first().countryCode)
        assertTrue(heavyTopTen.all { point -> point.bytes >= 1_048_576L })
    }

    @Test
    fun `unknown country row follows active and heavy filters`() {
        assertTrue(
            trafficMapDetailShowUnknownCountry(
                unknownCountryBytes = 256L,
                unknownCountryConnections = 0,
                filter = TrafficMapDetailFilter.ALL,
            ),
        )
        assertFalse(
            trafficMapDetailShowUnknownCountry(
                unknownCountryBytes = 256L,
                unknownCountryConnections = 0,
                filter = TrafficMapDetailFilter.ACTIVE,
            ),
        )
        assertTrue(
            trafficMapDetailShowUnknownCountry(
                unknownCountryBytes = 2_048_000L,
                unknownCountryConnections = 0,
                filter = TrafficMapDetailFilter.HEAVY,
            ),
        )
    }
}

private fun trafficMapPoint(
    countryCode: String,
    label: String,
    bytes: Long,
    connections: Int,
): TrafficMapPoint =
    TrafficMapPoint(
        countryCode = countryCode,
        label = label,
        lat = 0.0,
        lon = 0.0,
        bytes = bytes,
        connections = connections,
    )
