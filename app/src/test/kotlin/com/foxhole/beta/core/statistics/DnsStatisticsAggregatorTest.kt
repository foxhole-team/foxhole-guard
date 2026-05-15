package com.foxhole.beta.core.statistics

import com.foxhole.beta.core.model.DnsSettings
import com.foxhole.beta.core.model.NetworkType
import com.foxhole.beta.core.model.TrafficWindow
import com.foxhole.beta.core.model.VpnMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DnsStatisticsAggregatorTest {
    @Test
    fun `dns summary marks estimated and synthetic rows`() {
        val summary =
            dnsProtectionSummary(
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
                            blockedDns = 10,
                            allowedDns = 30,
                            reconnects = 0,
                            latencyMs = null,
                            destinationCountries = emptyMap(),
                        ),
                    ),
                appRows =
                    listOf(
                        AppTrafficRow(
                            packageName = "pkg",
                            label = "Pkg",
                            txBytes = 20L,
                            rxBytes = 80L,
                            badges = setOf(AppAnomalyBadge.NORMAL),
                        ),
                    ),
                dnsSettings = DnsSettings(filteringEnabled = true),
            )

        assertEquals(ChartDataQuality.REAL, summary.quality)
        assertEquals(ChartDataQuality.SYNTHETIC, summary.categoryQuality)
        assertEquals(ChartDataQuality.ESTIMATED, summary.appQuality)
        assertEquals(ChartDataQuality.SYNTHETIC, summary.categoryRows.single { it.category == DnsProtectionCategory.ADS }.quality)
        assertEquals(ChartDataQuality.ESTIMATED, summary.appRows.single().quality)
    }
}
