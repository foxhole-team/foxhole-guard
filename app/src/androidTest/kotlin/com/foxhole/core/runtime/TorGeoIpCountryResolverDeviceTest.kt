package com.foxhole.core.runtime

import androidx.test.core.app.ApplicationProvider
import com.foxhole.guard.FoxholeApplication
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The geoip database is DOWNLOADED, not packaged — and this pins what the app does before it
 * arrives.
 *
 * It used to ship inside the APK and this test asserted exactly that. The database then moved into
 * the FoxHole DB geo group to take 24 MB off the install, and the test kept asserting a packaged
 * asset — it simply never ran, because `check` did not compile androidTest. The first device run
 * after the move found two failures that described the product correctly and the test not at all.
 *
 * Only the empty state is asserted here, unconditionally. Parsing and caching are covered off-device
 * by `TorGeoIpCountryResolverTest`, so what is left for a real phone is the one thing a JVM test
 * cannot show: that a build with no database resolves to nothing instead of crashing or inventing a
 * country. Deliberately not `assumeTrue`-gated on an installed database — a required device spec
 * that skips is coverage quietly disappearing, and the runner rejects it for that reason.
 */
class TorGeoIpCountryResolverDeviceTest {
    @Test
    fun withoutAnInstalledDatabaseTheResolverIsEmptyRatherThanBroken() {
        val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
        TorGeoIpCountryResolver.invalidateShared()
        val resolver = TorGeoIpCountryResolver(context)

        // No exception and no fabricated answer: country attribution is unavailable, which is what
        // the map's "download the geo group" sheet exists to say out loud.
        assertEquals(false, resolver.hasDatabase())
        assertEquals(null, resolver.countryCodeForIpAddress("8.8.8.8"))
        assertEquals(0, resolver.databaseStats().ipv4RangeCount)
    }
}
