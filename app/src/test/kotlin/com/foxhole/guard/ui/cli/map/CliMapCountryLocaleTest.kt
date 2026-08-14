package com.foxhole.guard.ui.cli.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale

class CliMapCountryLocaleTest {
    @Test
    fun `country label follows the current app locale instead of the cached map label`() {
        val russian = localizedTrafficMapCountryLabel("DE", Locale.forLanguageTag("ru"), "Germany")
        val english = localizedTrafficMapCountryLabel("DE", Locale.ENGLISH, "Германия")

        val germany = Locale.Builder().setRegion("DE").build()
        assertEquals(germany.getDisplayCountry(Locale.forLanguageTag("ru")), russian)
        assertEquals(germany.getDisplayCountry(Locale.ENGLISH), english)
        assertNotEquals(russian, english)
    }

    @Test
    fun `invalid country code keeps the supplied readable fallback`() {
        assertEquals(
            "Unknown region",
            localizedTrafficMapCountryLabel("unknown", Locale.ENGLISH, "Unknown region"),
        )
    }
}
