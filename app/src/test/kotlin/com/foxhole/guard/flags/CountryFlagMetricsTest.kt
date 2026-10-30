package com.foxhole.guard.flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class CountryFlagMetricsTest {
    @Test fun codesAreAsciiIsoPairsRegardlessOfTheDisplayLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("in", countryFlagCode(" IN "))
            assertEquals("gb", countryFlagCode("GB"))
        } finally { Locale.setDefault(original) }
        listOf(null, "", "NLD", "1A", "../gb", "gb/", "🇬🇧").forEach { assertNull(countryFlagCode(it)) }
    }

    @Test fun imageHeightEqualsTheMeasuredCapitalAtEveryScaleAndKeepsItsAspectRatio() {
        listOf(5f, 9f, 13.5f, 28f).forEach { capHeight ->
            val size = requireNotNull(countryFlagSizePx(capHeight, 256, 192))
            assertEquals(capHeight, size.height, 0f)
            assertEquals(4f / 3f, size.width / size.height, .00001f)
        }
    }

    @Test fun missingGlyphMetricsAndInvalidImagesDoNotCreateAnOversizedFallbackBadge() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertNull(countryFlagSizePx(it, 256, 192))
        }
        assertNull(countryFlagSizePx(10f, 0, 192))
        assertNull(countryFlagSizePx(10f, 256, -1))
    }
}
