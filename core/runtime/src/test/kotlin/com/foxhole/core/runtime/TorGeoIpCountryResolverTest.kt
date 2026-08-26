package com.foxhole.core.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorGeoIpCountryResolverTest {
    @After
    fun clearSharedDestinationCache() {
        TorGeoIpCountryResolver.clearDestinationCountryCache()
    }

    @Test
    fun `destination country cache stores hits and no-country sentinel separately`() {
        TorGeoIpCountryResolver.storeDestinationCountry("false|1.2.3.4:443", "JP")
        TorGeoIpCountryResolver.storeDestinationCountry(
            "false|10.0.0.1:53",
            TorGeoIpCountryResolver.NoDestinationCountry,
        )

        assertEquals("JP", TorGeoIpCountryResolver.cachedDestinationCountry("false|1.2.3.4:443"))
        assertEquals(
            TorGeoIpCountryResolver.NoDestinationCountry,
            TorGeoIpCountryResolver.cachedDestinationCountry("false|10.0.0.1:53"),
        )
        assertNull(TorGeoIpCountryResolver.cachedDestinationCountry("true|1.2.3.4:443"))
    }

    @Test
    fun `destination country cache stays bounded by lru eviction`() {
        repeat(TorGeoIpCountryResolver.DestinationCountryCacheLimit + 100) { index ->
            TorGeoIpCountryResolver.storeDestinationCountry("false|10.0.$index.1:443", "US")
        }

        assertTrue(
            TorGeoIpCountryResolver.destinationCountryCacheSize() <=
                TorGeoIpCountryResolver.DestinationCountryCacheLimit,
        )

        assertEquals(
            "US",
            TorGeoIpCountryResolver.cachedDestinationCountry(
                "false|10.0.${TorGeoIpCountryResolver.DestinationCountryCacheLimit + 99}.1:443",
            ),
        )
        assertNull(TorGeoIpCountryResolver.cachedDestinationCountry("false|10.0.0.1:443"))
    }

    @Test
    fun `invalidate shared clears the destination country cache`() {
        TorGeoIpCountryResolver.storeDestinationCountry("false|1.2.3.4:443", "JP")

        TorGeoIpCountryResolver.invalidateShared()

        assertEquals(0, TorGeoIpCountryResolver.destinationCountryCacheSize())
    }

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

    @Test
    fun `parses dotted quad ipv4 range from db-ip csv`() {
        val range = TorGeoIpCountryResolver.parseIpv4Range("1.0.0.0,1.0.0.255,AU")

        assertEquals(Ipv4CountryRange(16777216L, 16777471L, "AU"), range)
    }

    @Test
    fun `rejects malformed dotted quad ipv4 fields`() {
        assertNull(TorGeoIpCountryResolver.parseIpv4Range("1.0.0,1.0.0.255,AU"))
        assertNull(TorGeoIpCountryResolver.parseIpv4Range("1.0.0.256,1.0.1.255,AU"))
        assertNull(TorGeoIpCountryResolver.parseIpv4Range("1.0.0.0.1,1.0.1.255,AU"))
        assertNull(TorGeoIpCountryResolver.parseIpv4Range("1..0.0,1.0.1.255,AU"))
    }
}
