package com.foxhole.guard.ui

import com.foxhole.guard.R
import org.junit.Assert.*
import org.junit.Test

class SiteMaskValidationTest {
    @Test fun websiteHelpFormatsUseTheSharedCanonicalParserFixture() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/website-mask-parity.tsv"))
        stream.bufferedReader().useLines { lines -> lines.forEach { line ->
            val (input, encoded) = line.split('\t', limit = 2)
            assertEquals(input, encoded.takeUnless { it == "<invalid>" }, normalizedSiteMaskToken(input))
        } }
    }
    @Test fun rawInputLimitAppliesBeforeTrimmingOrParsing() {
        assertNull(normalizedSiteMaskToken(" ".repeat(2048) + "example.com"))
        assertNull(normalizedSiteMaskToken("a".repeat(2049)))
    }

    @Test fun unsupportedLegacyMatchersAreExplicitAndRemainUnmodified() {
        listOf("kw:ads", "re:^ads\\..+", "KW:ads").forEach { value ->
            assertEquals(R.string.site_mask_unsupported, siteMaskValidationErrorRes(value))
            assertNull(normalizedSiteMaskToken(value))
        }
    }
    @Test fun ipRangesBecomeOnlyExactCoveringCidrs() {
        val match = siteRuleMaskParts(listOf("192.0.2.3-192.0.2.9"))
        assertTrue(match.domains.isEmpty())
        assertEquals(listOf("192.0.2.3/32", "192.0.2.4/30", "192.0.2.8/31"), match.cidrs)
    }
    @Test fun maximumIpv6RangeHasBoundedExactCoverWithoutAddressEnumeration() {
        val match = siteRuleMaskParts(listOf("::1-ffff:ffff:ffff:ffff:ffff:ffff:ffff:fffe"))
        assertEquals(254, match.cidrs.size)
        val expected = requireNotNull(javaClass.getResourceAsStream("/ipv6-full-range-cidrs.txt"))
            .bufferedReader().use { it.readLines() }
        assertEquals(expected.map { java.net.InetAddress.getByName(it.substringBefore('/')).hostAddress + "/" + it.substringAfter('/') }, match.cidrs)
        assertEquals("0:0:0:0:0:0:0:1/128", match.cidrs.first())
        assertEquals("ffff:ffff:ffff:ffff:ffff:ffff:ffff:fffe/128", match.cidrs.last())
    }
    @Test fun invalidMemberOrOversizeInputCannotBeSilentlyDroppedOrExpanded() {
        for (values in listOf(listOf("example.com", "bad..example.com"), listOf("a".repeat(2049)), List(4097) { "example.com" })) {
            assertTrue(runCatching { siteRuleMaskParts(values) }.isFailure)
        }
        assertTrue(runCatching { siteRuleMaskParts(List(17) { "range:::1-ffff:ffff:ffff:ffff:ffff:ffff:ffff:${(65534-it).toString(16)}" }) }.isFailure)
    }
    @Test fun urlAndIdnNormalizeBeforePersistenceAndNumericRulesStayNumeric() {
        val match = siteRuleMaskParts(listOf("https://faß.de/path?x=1", "zone:ru", "192.0.2.99/24"))
        assertEquals(listOf("xn--fa-hia.de", "*.ru"), match.domains)
        assertEquals(listOf("192.0.2.0/24"), match.cidrs)
    }
}
