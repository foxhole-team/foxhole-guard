package com.foxhole.beta.ui

import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapPointRole
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
                visibleLimit = null,
            ).map(TrafficMapPoint::countryCode),
        )
        assertEquals(
            listOf("DE", "BR", "US"),
            trafficMapDetailDestinations(
                destinations = points,
                sort = TrafficMapDetailSort.SESSIONS,
                filter = TrafficMapDetailFilter.ALL,
                visibleLimit = null,
            ).map(TrafficMapPoint::countryCode),
        )
        assertEquals(
            listOf("BR", "DE", "US"),
            trafficMapDetailDestinations(
                destinations = points,
                sort = TrafficMapDetailSort.COUNTRY,
                filter = TrafficMapDetailFilter.ALL,
                visibleLimit = null,
            ).map(TrafficMapPoint::countryCode),
        )
    }

    @Test
    fun `detail destinations apply role filters and range after sorting`() {
        val points =
            (1..12).map { index ->
                trafficMapPoint(
                    countryCode = "C$index",
                    label = "Country $index",
                    bytes = index * 512_000L,
                    connections = index,
                )
            } +
                listOf(
                    trafficMapPoint(
                        "NL",
                        "Netherlands",
                        bytes = 256_000L,
                        connections = 1,
                        role = TrafficMapPointRole.VPN_ROUTE,
                    ),
                    trafficMapPoint(
                        "DE",
                        "Germany",
                        bytes = 128_000L,
                        connections = 1,
                        role = TrafficMapPointRole.TOR_EXIT,
                    ),
                )

        val directTopTen =
            trafficMapDetailDestinations(
                destinations = points,
                sort = TrafficMapDetailSort.TOTAL,
                filter = TrafficMapDetailFilter.DIRECT,
                visibleLimit = 10,
            )

        assertEquals(10, directTopTen.size)
        assertEquals("C12", directTopTen.first().countryCode)
        assertTrue(directTopTen.all { point -> point.role == TrafficMapPointRole.DESTINATION })
        assertEquals(
            listOf("NL"),
            trafficMapDetailDestinations(
                destinations = points,
                sort = TrafficMapDetailSort.TOTAL,
                filter = TrafficMapDetailFilter.VPN,
                visibleLimit = null,
            ).map(TrafficMapPoint::countryCode),
        )
        assertEquals(
            listOf("DE"),
            trafficMapDetailDestinations(
                destinations = points,
                sort = TrafficMapDetailSort.TOTAL,
                filter = TrafficMapDetailFilter.TOR,
                visibleLimit = null,
            ).map(TrafficMapPoint::countryCode),
        )
        assertEquals(
            listOf("C5"),
            trafficMapDetailDestinations(
                destinations = points,
                sort = TrafficMapDetailSort.TOTAL,
                filter = TrafficMapDetailFilter.NEW,
                visibleLimit = null,
                newCountryCodes = setOf("C5"),
            ).map(TrafficMapPoint::countryCode),
        )
    }

    @Test
    fun `detail points add vpn and tor route rows to expanded table`() {
        val destination = trafficMapPoint("FR", "France", bytes = 4_000L, connections = 4)
        val vpn =
            trafficMapPoint(
                "NL",
                "Netherlands",
                bytes = 2_000L,
                connections = 1,
                role = TrafficMapPointRole.VPN_ROUTE,
            )
        val tor =
            trafficMapPoint(
                "DE",
                "Germany",
                bytes = 1_000L,
                connections = 1,
                role = TrafficMapPointRole.TOR_EXIT,
            )

        assertEquals(
            listOf(TrafficMapPointRole.DESTINATION, TrafficMapPointRole.VPN_ROUTE, TrafficMapPointRole.TOR_EXIT),
            trafficMapDetailPoints(
                destinations = listOf(destination),
                vpnRoute = vpn,
                torExit = tor,
            ).map(TrafficMapPoint::role),
        )
    }

    @Test
    fun `unknown country row follows all and direct filters only`() {
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
                filter = TrafficMapDetailFilter.VPN,
            ),
        )
        assertTrue(
            trafficMapDetailShowUnknownCountry(
                unknownCountryBytes = 256L,
                unknownCountryConnections = 0,
                filter = TrafficMapDetailFilter.DIRECT,
            ),
        )
    }

    @Test
    fun `detail share label keeps tiny and empty traffic honest`() {
        assertEquals("0%", trafficMapDetailShareLabel(bytes = 0L, totalBytes = 0L))
        assertEquals("<1%", trafficMapDetailShareLabel(bytes = 1L, totalBytes = 200L))
        assertEquals("25%", trafficMapDetailShareLabel(bytes = 25L, totalBytes = 100L))
        assertEquals("100%", trafficMapDetailShareLabel(bytes = 125L, totalBytes = 100L))
    }
}

private fun trafficMapPoint(
    countryCode: String,
    label: String,
    bytes: Long,
    connections: Int,
    role: TrafficMapPointRole = TrafficMapPointRole.DESTINATION,
): TrafficMapPoint =
    TrafficMapPoint(
        countryCode = countryCode,
        label = label,
        lat = 0.0,
        lon = 0.0,
        bytes = bytes,
        connections = connections,
        role = role,
    )
