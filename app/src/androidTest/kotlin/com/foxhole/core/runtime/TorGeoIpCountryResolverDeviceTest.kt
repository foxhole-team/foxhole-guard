package com.foxhole.core.runtime

import androidx.test.core.app.ApplicationProvider
import com.foxhole.guard.FoxholeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the PACKAGED assets (the filtered tor/data/ layout), so it fails loudly if the
 * asset layout and the resolver's candidate paths ever drift apart again — the exact regression
 * that silently removed country attribution from the traffic map.
 */
class TorGeoIpCountryResolverDeviceTest {
    @Test
    fun resolvesCountryForKnownIpv4FromPackagedAssets() {
        val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
        TorGeoIpCountryResolver.invalidateShared()
        val resolver = TorGeoIpCountryResolver(context)

        val country = resolver.countryCodeForIpAddress("8.8.8.8")

        assertNotNull("packaged geoip asset must resolve 8.8.8.8", country)
        assertEquals("US", country)
    }

    @Test
    fun packagedDatabaseCarriesRealRangeCounts() {
        val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
        TorGeoIpCountryResolver.invalidateShared()
        val resolver = TorGeoIpCountryResolver(context)
        resolver.countryCodeForIpAddress("8.8.8.8")

        val stats = resolver.databaseStats()

        assertTrue(
            "ipv4 range count ${stats.ipv4RangeCount} looks like a missing asset",
            stats.ipv4RangeCount >= GeoIpDatabaseStore.MIN_RANGES_PER_FAMILY,
        )
    }
}
