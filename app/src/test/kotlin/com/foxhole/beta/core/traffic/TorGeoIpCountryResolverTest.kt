package com.foxhole.beta.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TorGeoIpCountryResolverTest {
    @Test
    fun `parses tor ipv4 geoip range`() {
        val range = TorGeoIpCountryResolver.parseIpv4Range("16781312,16785407,JP")

        assertEquals(Ipv4CountryRange(16781312L, 16785407L, "JP"), range)
    }

    @Test
    fun `ignores comments and unknown country ranges`() {
        assertNull(TorGeoIpCountryResolver.parseIpv4Range("# comment"))
        assertNull(TorGeoIpCountryResolver.parseIpv6Range("2001::,2001:0:ffff:ffff:ffff:ffff:ffff:ffff,??"))
    }

    @Test
    fun `parses tor ipv6 geoip range`() {
        val range = TorGeoIpCountryResolver.parseIpv6Range("2001:200::,2001:200:134:ffff:ffff:ffff:ffff:ffff,JP")

        assertNotNull(range)
        assertEquals("JP", range?.countryCode)
    }
}
