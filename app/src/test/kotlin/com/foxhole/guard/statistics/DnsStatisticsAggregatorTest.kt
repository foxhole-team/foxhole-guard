package com.foxhole.guard.statistics

import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.NetworkType
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.model.VpnMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DnsStatisticsAggregatorTest {
    private fun window(
        blockedDns: Int,
        allowedDns: Int = 0,
        blockedDnsByCategory: Map<DnsFilterCategory, Long> = emptyMap(),
        blockedDnsApps: Map<String, Long> = emptyMap(),
    ): TrafficWindow =
        TrafficWindow(
            startedAtMs = 1L,
            durationSec = 60,
            networkType = NetworkType.UNKNOWN,
            vpnMode = VpnMode.NORMAL,
            profileId = null,
            protocol = null,
            rxBytes = 0L,
            txBytes = 0L,
            blockedDns = blockedDns,
            allowedDns = allowedDns,
            reconnects = 0,
            latencyMs = null,
            destinationCountries = emptyMap(),
            blockedDnsByCategory = blockedDnsByCategory,
            blockedDnsApps = blockedDnsApps,
        )

    @Test
    fun `measured category counts replace the synthetic split`() {
        val summary =
            dnsProtectionSummary(
                trafficWindows =
                listOf(
                    window(
                        blockedDns = 10,
                        blockedDnsByCategory =
                        mapOf(
                            DnsFilterCategory.MALICIOUS to 2L,
                            DnsFilterCategory.ADS to 8L,
                        ),
                    ),
                ),
                appRows = emptyList(),
                dnsSettings = DnsSettings(filteringEnabled = true),
            )
        assertEquals(ChartDataQuality.REAL, summary.categoryQuality)
        val byCategory = summary.categoryRows.associate { row -> row.category to row.blockedQueries }
        assertEquals(2, byCategory[DnsProtectionCategory.MALICIOUS])
        assertEquals(8, byCategory[DnsProtectionCategory.ADS])
        assertEquals(0, byCategory[DnsProtectionCategory.TRACKERS])
    }

    @Test
    fun `partially measured ranges are marked partial`() {
        val summary =
            dnsProtectionSummary(
                trafficWindows =
                listOf(
                    window(blockedDns = 6),
                    window(
                        blockedDns = 4,
                        blockedDnsByCategory = mapOf(DnsFilterCategory.TRACKERS to 4L),
                    ),
                ),
                appRows = emptyList(),
                dnsSettings = DnsSettings(filteringEnabled = true),
            )
        assertEquals(ChartDataQuality.PARTIAL, summary.categoryQuality)
        assertEquals(
            4,
            summary.categoryRows.first { row -> row.category == DnsProtectionCategory.TRACKERS }.blockedQueries,
        )
    }

    @Test
    fun `measured per-app counts replace the byte-share estimate`() {
        val summary =
            dnsProtectionSummary(
                trafficWindows =
                listOf(
                    window(
                        blockedDns = 10,
                        blockedDnsApps = mapOf("com.example.app" to 7L, "com.other.app" to 3L),
                    ),
                ),
                appRows =
                listOf(
                    AppTrafficRow(
                        packageName = "com.example.app",
                        label = "Example",
                        txBytes = 10L,
                        rxBytes = 10L,
                        badges = emptySet(),
                    ),
                ),
                dnsSettings = DnsSettings(filteringEnabled = true),
            )
        assertEquals(ChartDataQuality.REAL, summary.appQuality)
        val top = summary.appRows.first()
        assertEquals("com.example.app", top.packageName)
        assertEquals("Example", top.label)
        assertEquals(7, top.estimatedBlockedQueries)
        assertEquals("com.other.app", summary.appRows[1].label)
    }

    @Test
    fun `without category data the split stays synthetic`() {
        val summary =
            dnsProtectionSummary(
                trafficWindows = listOf(window(blockedDns = 8)),
                appRows = emptyList(),
                dnsSettings = DnsSettings(filteringEnabled = true),
            )
        assertEquals(ChartDataQuality.SYNTHETIC, summary.categoryQuality)
    }

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
        assertEquals(
            ChartDataQuality.SYNTHETIC,
            summary.categoryRows.single { it.category == DnsProtectionCategory.ADS }.quality
        )
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
