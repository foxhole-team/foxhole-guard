package com.foxhole.beta.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsDomainRuleValidatorTest {
    @Test
    fun `normalizes wildcard leading dot and case`() {
        assertEquals(
            listOf("example.com", "tracker.test"),
            normalizeDnsDomainRules("*.Example.COM\n.tracker.test."),
        )
    }

    @Test
    fun `deduplicates normalized rules`() {
        assertEquals(
            listOf("example.com"),
            normalizeDnsDomainRules("example.com, *.example.com; EXAMPLE.COM."),
        )
    }

    @Test
    fun `rejects invalid labels`() {
        assertNull(normalizeDnsDomainRule("-bad.example"))
        assertNull(normalizeDnsDomainRule("bad-.example"))
        assertNull(normalizeDnsDomainRule("bad label.example"))
        assertNull(normalizeDnsDomainRule(""))
    }
}
