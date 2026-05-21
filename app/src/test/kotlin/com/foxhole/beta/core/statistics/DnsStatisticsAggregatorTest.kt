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
                            blockedDnsDomains =
                                mapOf(
                                    "ads.example.test" to 2L,
                                    "tracker.example.test" to 3L,
                                ),
                        ),
                        TrafficWindow(
                            startedAtMs = 2L,
                            durationSec = 60,
                            networkType = NetworkType.UNKNOWN,
                            vpnMode = VpnMode.NORMAL,
                            profileId = null,
                            protocol = null,
                            rxBytes = 0L,
                            txBytes = 0L,
                            blockedDns = 4,
                            allowedDns = 0,
                            reconnects = 0,
                            latencyMs = null,
                            destinationCountries = emptyMap(),
                            blockedDnsDomains = mapOf("ads.example.test" to 4L),
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
        assertEquals(ChartDataQuality.REAL, summary.domainQuality)
        assertEquals(ChartDataQuality.SYNTHETIC, summary.categoryRows.single { it.category == DnsProtectionCategory.ADS }.quality)
        assertEquals(ChartDataQuality.ESTIMATED, summary.appRows.single().quality)
        assertEquals(
            listOf("ads.example.test" to 6L, "tracker.example.test" to 3L),
            summary.domainRows.map { row -> row.domain to row.blockedQueries },
        )
    }

    @Test
    fun `dns categories follow enabled DNS block toggles`() {
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
                            blockedDns = 9,
                            allowedDns = 1,
                            reconnects = 0,
                            latencyMs = null,
                            destinationCountries = emptyMap(),
                        ),
                    ),
                appRows = emptyList(),
                dnsSettings =
                    DnsSettings(
                        filteringEnabled = true,
                        blockAds = false,
                        blockTrackers = true,
                        blockAppTelemetry = false,
                        blockMaliciousDomains = true,
                    ),
            )

        assertEquals(
            listOf(DnsProtectionCategory.TRACKERS, DnsProtectionCategory.MALICIOUS),
            summary.categoryRows.map { it.category },
        )
        assertEquals(listOf(5, 4), summary.categoryRows.map { it.blockedQueries })
    }

    @Test
    fun `dns categories are empty when filtering is disabled`() {
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
                            blockedDns = 9,
                            allowedDns = 1,
                            reconnects = 0,
                            latencyMs = null,
                            destinationCountries = emptyMap(),
                        ),
                    ),
                appRows = emptyList(),
                dnsSettings = DnsSettings(filteringEnabled = false),
            )

        assertEquals(emptyList<DnsProtectionCategoryRow>(), summary.categoryRows)
    }
}
