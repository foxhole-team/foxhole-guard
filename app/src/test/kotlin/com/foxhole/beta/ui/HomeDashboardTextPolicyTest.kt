package com.foxhole.beta.ui

import com.foxhole.beta.core.model.IpInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDashboardTextPolicyTest {
    @Test
    fun `keeps dashboard profile title unchanged when within limit`() {
        assertEquals("FoxHole vpn direct", dashboardProfileTitle("FoxHole vpn direct"))
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
}
