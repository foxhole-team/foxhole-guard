package com.foxhole.guard.statistics

import com.foxhole.core.model.NetworkType
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.model.VpnMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CountryTrafficAggregatorTest {
    @Test
    fun `country rows group overflow into other`() {
        val countries = listOf("DE", "US", "NL", "FR", "JP", "SE")
        val rows =
            countryTrafficRows(
                trafficWindows =
                listOf(
                    TrafficWindow(
                        startedAtMs = 1L,
                        durationSec = 60,
                        networkType = NetworkType.UNKNOWN,
                        vpnMode = VpnMode.NORMAL,
                        profileId = null,
                        protocol = null,
                        rxBytes = 0L,
                        txBytes = 0L,
                        blockedDns = 0,
                        allowedDns = 0,
                        reconnects = 0,
                        latencyMs = null,
                        destinationCountries =
                        countries.mapIndexed { index, code ->
                            code to (100L - index)
                        }.toMap(),
                    ),
                ),
                liveDestinations = emptyList(),
            )

        assertEquals(6, rows.size)
        assertEquals("SE", rows.last().countryCode)

        val collapsed = collapseCountryOverflow(rows, maxCountries = 5)
        assertEquals(6, collapsed.size)
        assertEquals(OTHER_COUNTRY_CODE, collapsed.last().countryCode)
        assertEquals(ChartDataQuality.PARTIAL, collapsed.last().quality)
    }
}
