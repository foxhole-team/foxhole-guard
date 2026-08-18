package com.foxhole.core.runtime

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class TorGeoIpCountryResolverDeviceTest {
    @Test
    fun withoutAnInstalledDatabaseTheResolverIsEmptyRatherThanBroken() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val context = object : ContextWrapper(testContext) {
            override fun getApplicationContext(): Context = this
        }
        TorGeoIpCountryResolver.overrideDirectory(context).deleteRecursively()
        TorGeoIpCountryResolver.invalidateShared()
        val resolver = TorGeoIpCountryResolver(context)

        assertEquals(false, resolver.hasDatabase())
        assertEquals(null, resolver.countryCodeForIpAddress("8.8.8.8"))
        assertEquals(0, resolver.databaseStats().ipv4RangeCount)
    }
}
