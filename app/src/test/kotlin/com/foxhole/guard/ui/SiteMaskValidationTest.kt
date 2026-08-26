package com.foxhole.guard.ui

import com.foxhole.guard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteMaskValidationTest {

    @Test
    fun `wildcard accepts a top level domain suffix`() {
        assertNull(siteMaskValidationErrorRes("*.com"))
        assertNull(siteMaskValidationErrorRes("*.ru"))
    }

    @Test
    fun `leading dot canonicalizes to a wildcard top level suffix`() {
        assertEquals("*.com", normalizedSiteMaskToken(" .com "))
        assertNull(siteMaskValidationErrorRes(".com"))
    }

    @Test
    fun `plain exact domain still requires at least two labels`() {
        assertEquals(R.string.site_exception_invalid_error, siteMaskValidationErrorRes("com"))
        assertNull(siteMaskValidationErrorRes("example.com"))
    }
}
