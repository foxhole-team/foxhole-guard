package com.foxhole.beta.core.statistics

import com.foxhole.beta.core.model.NetworkType
import com.foxhole.beta.core.model.TrafficWindow
import com.foxhole.beta.core.model.VpnMode
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
        assertEquals("Other", rows.last().label)
        assertEquals(ChartDataQuality.PARTIAL, rows.last().quality)
    }
}
