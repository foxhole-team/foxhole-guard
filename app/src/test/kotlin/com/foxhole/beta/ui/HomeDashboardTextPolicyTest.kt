package com.foxhole.beta.ui

import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.SecureDnsMode
import org.junit.Assert.assertEquals
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
