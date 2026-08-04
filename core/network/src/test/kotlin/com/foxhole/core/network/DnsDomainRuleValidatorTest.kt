package com.foxhole.core.network

import org.junit.Assert.assertNull
import org.junit.Test

class DnsDomainRuleValidatorTest {
    @Test
    fun `rejects invalid labels`() {
        assertNull(normalizeDnsDomainRule("-bad.example"))
        assertNull(normalizeDnsDomainRule("bad-.example"))
        assertNull(normalizeDnsDomainRule("bad label.example"))
        assertNull(normalizeDnsDomainRule(""))
    }
}
