package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.SecureDnsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardTextPolicyTest {
    @Test
    fun `keeps dashboard profile title unchanged when within limit`() {
        assertEquals("Foxhole vpn direct", dashboardProfileTitle("Foxhole vpn direct"))
    }

    @Test
    fun `truncates dashboard profile title after twenty five characters with two dots`() {
        assertEquals(
            "12345678901234567890123..",
            dashboardProfileTitle("123456789012345678901234567890"),
        )
    }

    @Test
    fun `country line does not include city because city has a dedicated dashboard row`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Example ISP",
                fetchedAt = 1L,
            )

        assertEquals("🇺🇸 United States", formatCountryLine(ipInfo, unknownCountry = "Unknown"))
        assertEquals("New York", buildCityLine(ipInfo))
    }

    @Test
    fun `city line falls back to dash when ip service omits city`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = "US",
                countryName = "United States",
                city = "",
                isp = "Example ISP",
                fetchedAt = 1L,
            )

        assertEquals("-", buildCityLine(ipInfo))
    }

    @Test
    fun `network detail value skeletons missing rows only while loading`() {
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(value = null, loading = true),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkDetailValue(value = null, loading = false),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "Moscow", loading = false),
            homeNetworkDetailValue(value = "Moscow", loading = true),
        )
    }

    @Test
    fun `network geo rows skeleton missing country and city until loading settles`() {
        val partialIpInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = "NL",
                countryName = null,
                city = null,
                isp = "Example ISP",
                fetchedAt = 1L,
            )

        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkGeoRowDetailValue(
                value = buildCountryLineOrNull(partialIpInfo),
                refreshLoading = false,
                geoRowsLoading = true,
            ),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkGeoRowDetailValue(
                value = buildCityLineOrNull(partialIpInfo),
                refreshLoading = false,
                geoRowsLoading = true,
            ),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkGeoRowDetailValue(
                value = buildCountryLineOrNull(partialIpInfo),
                refreshLoading = false,
                geoRowsLoading = false,
            ),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkGeoRowDetailValue(
                value = buildCityLineOrNull(partialIpInfo),
                refreshLoading = false,
                geoRowsLoading = false,
            ),
        )
    }

    @Test
    fun `network card keeps rows visible while missing country or city is still loading`() {
        val partialIpInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = null,
                countryName = null,
                city = null,
                isp = "Example ISP",
                fetchedAt = 1L,
            )

        assertFalse(shouldShowHomeNetworkFullLoading(partialIpInfo, showIpInfoLoading = true))
        assertTrue(homeNetworkDetailValue(formatCountryLineOrNull(partialIpInfo), loading = true).loading)
        assertTrue(homeNetworkDetailValue(buildCityLineOrNull(partialIpInfo), loading = true).loading)
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkDetailValue(buildCityLineOrNull(partialIpInfo), loading = false),
        )
    }

    @Test
    fun `network card skeletons missing country and city during active refresh`() {
        val partialIpInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = null,
                countryName = null,
                city = null,
                isp = "Example ISP",
                fetchedAt = 1L,
            )
        val policy = homeNetworkDetailLoadingPolicy(refreshLoading = true, geoRowsLoading = false)

        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(buildCountryLineOrNull(partialIpInfo), loading = policy.country),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(buildCityLineOrNull(partialIpInfo), loading = policy.city),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkDetailValue(buildCountryLineOrNull(partialIpInfo), loading = false),
        )
    }

    @Test
    fun `network card keeps country and city rows skeletoned until geo lookup settles`() {
        val partialIpInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = null,
                countryName = null,
                city = null,
                isp = "Example ISP",
                fetchedAt = 1L,
            )

        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(buildCountryLineOrNull(partialIpInfo), loading = true),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(buildCityLineOrNull(partialIpInfo), loading = true),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkDetailValue(buildCountryLineOrNull(partialIpInfo), loading = false),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkDetailValue(buildCityLineOrNull(partialIpInfo), loading = false),
        )
    }

    @Test
    fun `network card skeletons fresh missing geo rows and later falls back to dash`() {
        val partialIpInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = null,
                countryName = null,
                city = null,
                isp = "Example ISP",
                fetchedAt = 10_000L,
            )

        assertTrue(
            shouldShowHomeNetworkGeoRowsLoading(
                ipInfo = partialIpInfo,
                nowMs = 10_000L + HOME_NETWORK_GEO_ROW_PENDING_LOADING_MS,
            ),
        )
        assertFalse(
            shouldShowHomeNetworkGeoRowsLoading(
                ipInfo = partialIpInfo,
                nowMs = 10_000L + HOME_NETWORK_GEO_ROW_PENDING_LOADING_MS + 1L,
            ),
        )
        assertTrue(
            shouldShowHomeNetworkGeoRowsLoading(
                ipInfo = partialIpInfo,
                nowMs = 10_000L + HOME_NETWORK_GEO_ROW_PENDING_LOADING_MS + 1L,
                refreshLoading = true,
            ),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(buildCityLineOrNull(partialIpInfo), loading = true),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkDetailValue(buildCityLineOrNull(partialIpInfo), loading = false),
        )
    }

    @Test
    fun `network card keeps missing country and city skeletoned during slow geo enrichment`() {
        val partialIpInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = null,
                countryName = null,
                city = null,
                isp = "Example ISP",
                fetchedAt = 10_000L,
            )
        val stillPendingMs = 10_000L + 4_000L
        val loading =
            shouldShowHomeNetworkGeoRowsLoading(
                ipInfo = partialIpInfo,
                nowMs = stillPendingMs,
            )

        assertTrue(loading)
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(buildCountryLineOrNull(partialIpInfo), loading = loading),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(buildCityLineOrNull(partialIpInfo), loading = loading),
        )
    }

    @Test
    fun `network detail loading skeletons missing geo rows without hiding known ip`() {
        val policy = homeNetworkDetailLoadingPolicy(refreshLoading = false, geoRowsLoading = true)

        assertTrue(policy.country)
        assertTrue(policy.city)
        assertFalse(policy.ip)
        assertTrue(policy.provider)
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(value = null, loading = policy.country),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "203.0.113.7", loading = false),
            homeNetworkDetailValue(value = "203.0.113.7", loading = policy.ip),
        )
    }

    @Test
    fun `network detail keeps known country while city still skeletons`() {
        val partialIpInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = "US",
                countryName = "United States",
                city = null,
                isp = null,
                fetchedAt = 10_000L,
            )
        val policy = homeNetworkDetailLoadingPolicy(refreshLoading = false, geoRowsLoading = true)

        assertEquals(
            HomeNetworkDetailValue(text = "🇺🇸 United States", loading = false),
            homeNetworkDetailValue(formatCountryLineOrNull(partialIpInfo), loading = policy.country),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(buildCityLineOrNull(partialIpInfo), loading = policy.city),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(providerLineOrNull(partialIpInfo), loading = policy.provider),
        )
    }

    @Test
    fun `network detail skeletons unresolved location rows independently`() {
        val partialIpInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = null,
                countryName = null,
                city = "Amsterdam",
                isp = "Example ISP",
                fetchedAt = 10_000L,
            )
        val loadingPolicy = homeNetworkDetailLoadingPolicy(refreshLoading = false, geoRowsLoading = true)
        val settledPolicy = homeNetworkDetailLoadingPolicy(refreshLoading = false, geoRowsLoading = false)

        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(formatCountryLineOrNull(partialIpInfo), loading = loadingPolicy.country),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "Amsterdam", loading = false),
            homeNetworkDetailValue(buildCityLineOrNull(partialIpInfo), loading = loadingPolicy.city),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkDetailValue(formatCountryLineOrNull(partialIpInfo), loading = settledPolicy.country),
        )
    }

    @Test
    fun `network detail skeletons country row when only flag code is available`() {
        val partialIpInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = "NL",
                countryName = null,
                city = "Amsterdam",
                isp = "Example ISP",
                fetchedAt = 10_000L,
            )
        val loadingPolicy = homeNetworkDetailLoadingPolicy(refreshLoading = false, geoRowsLoading = true)
        val settledPolicy = homeNetworkDetailLoadingPolicy(refreshLoading = false, geoRowsLoading = false)

        assertEquals("🇳🇱 NL", formatCountryLineOrNull(partialIpInfo))
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(buildCountryLineOrNull(partialIpInfo), loading = loadingPolicy.country),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "Amsterdam", loading = false),
            homeNetworkDetailValue(buildCityLineOrNull(partialIpInfo), loading = loadingPolicy.city),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkDetailValue(buildCountryLineOrNull(partialIpInfo), loading = settledPolicy.country),
        )
    }

    @Test
    fun `network country row shows dash instead of code after geo lookup settles without country name`() {
        val partialIpInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = "NL",
                countryName = null,
                city = null,
                isp = "Example ISP",
                fetchedAt = 10_000L,
            )

        assertEquals(null, dashboardCountryLineForGeoState(partialIpInfo, geoRowsLoading = true))
        assertEquals(null, dashboardCountryLineForGeoState(partialIpInfo, geoRowsLoading = false))
    }

    @Test
    fun `network geo row shows skeleton for country code without country name until settled`() {
        val partialIpInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = "NL",
                countryName = null,
                city = null,
                isp = "Example ISP",
                fetchedAt = 10_000L,
            )

        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkGeoRowDetailValue(
                value = buildCountryLineOrNull(partialIpInfo),
                refreshLoading = false,
                geoRowsLoading = true,
            ),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkGeoRowDetailValue(
                value = buildCityLineOrNull(partialIpInfo),
                refreshLoading = false,
                geoRowsLoading = true,
            ),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkGeoRowDetailValue(
                value = buildCountryLineOrNull(partialIpInfo),
                refreshLoading = false,
                geoRowsLoading = false,
            ),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkGeoRowDetailValue(
                value = buildCityLineOrNull(partialIpInfo),
                refreshLoading = false,
                geoRowsLoading = false,
            ),
        )
    }

    @Test
    fun `network detail skeletons country and city rows until missing location is settled`() {
        val partialIpInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = "NL",
                countryName = null,
                city = null,
                isp = "Example ISP",
                fetchedAt = 10_000L,
            )
        val loading =
            shouldShowHomeNetworkGeoRowsLoading(
                ipInfo = partialIpInfo,
                nowMs = 10_000L + 1_000L,
            )

        assertTrue(loading)
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(buildCountryLineOrNull(partialIpInfo), loading = loading),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "", loading = true),
            homeNetworkDetailValue(buildCityLineOrNull(partialIpInfo), loading = loading),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkDetailValue(buildCountryLineOrNull(partialIpInfo), loading = false),
        )
        assertEquals(
            HomeNetworkDetailValue(text = "-", loading = false),
            homeNetworkDetailValue(buildCityLineOrNull(partialIpInfo), loading = false),
        )
    }

    @Test
    fun `network card uses full skeleton only before any ip info is available`() {
        assertTrue(shouldShowHomeNetworkFullLoading(visibleIpInfo = null, showIpInfoLoading = true))
        assertFalse(shouldShowHomeNetworkFullLoading(visibleIpInfo = null, showIpInfoLoading = false))
    }

    @Test
    fun `network card uses bounded startup skeleton while initial ip is absent`() {
        assertTrue(
            shouldShowHomeNetworkEmptyStartupSkeleton(
                visibleIpInfo = null,
                explicitLoading = false,
                connectionState = ConnectionState.IDLE,
                elapsedMs = HOME_NETWORK_EMPTY_STARTUP_SKELETON_MS - 1,
            ),
        )
        assertFalse(
            shouldShowHomeNetworkEmptyStartupSkeleton(
                visibleIpInfo = null,
                explicitLoading = false,
                connectionState = ConnectionState.IDLE,
                elapsedMs = HOME_NETWORK_EMPTY_STARTUP_SKELETON_MS,
            ),
        )
        assertFalse(
            shouldShowHomeNetworkEmptyStartupSkeleton(
                visibleIpInfo =
                    IpInfo(
                        ip = "203.0.113.7",
                        countryCode = null,
                        countryName = null,
                        city = null,
                        isp = null,
                        fetchedAt = 1L,
                    ),
                explicitLoading = false,
                connectionState = ConnectionState.IDLE,
                elapsedMs = 1L,
            ),
        )
    }

    @Test
    fun `country line uses country code instead of unknown when country name is missing`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = "NL",
                countryName = null,
                city = "Amsterdam",
                isp = "Example ISP",
                fetchedAt = 1L,
            )

        assertEquals("🇳🇱 NL", formatCountryLine(ipInfo, unknownCountry = "Unknown"))
        assertEquals("Amsterdam", buildCityLine(ipInfo))
    }

    @Test
    fun `country line falls back to country code when country name is blank`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.7",
                countryCode = "RU",
                countryName = "",
                city = "Moscow",
                isp = "Example ISP",
                fetchedAt = 1L,
            )

        assertEquals("🇷🇺 RU", formatCountryLine(ipInfo, unknownCountry = "Unknown"))
        assertEquals("Moscow", buildCityLine(ipInfo))
    }

    @Test
    fun `visible ip text skips local addresses`() {
        val ipInfo =
            IpInfo(
                ip = "10.13.13.110",
                ipv4 = "192.168.1.20",
                ipv6 = "2001:db8::7",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example ISP",
                fetchedAt = 1L,
            )

        assertEquals("2001:db8::7", primaryVisibleIp(ipInfo))
        assertEquals(null, secondaryVisibleIp(ipInfo))
    }

    @Test
    fun `dashboard dns mode line keeps only selected mode`() {
        assertEquals("Default", dashboardDnsModeLabel(SecureDnsMode.PLAIN))
        assertEquals("DOH", dashboardDnsModeLabel(SecureDnsMode.DOH))
        assertEquals("DOT", dashboardDnsModeLabel(SecureDnsMode.DOT))
    }
}
