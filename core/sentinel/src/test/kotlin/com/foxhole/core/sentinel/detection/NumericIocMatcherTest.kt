package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.ThreatIntelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumericIocMatcherTest {
    private fun matcher(ip: String) = NetworkIocMatcher(ThreatIntelDocument(ips = listOf(ip)))

    private fun assertIpMatch(indicator: String, host: String) {
        assertEquals(NetworkIocKind.IP, matcher(indicator).match(host)?.kind)
    }

    @Test
    fun expandedSeedMatchesCompressedFlow() = assertIpMatch("2001:0db8:0:0:0:0:0:1", "2001:db8::1")

    @Test
    fun compressedSeedMatchesExpandedFlow() = assertIpMatch("2001:db8::1", "2001:0db8:0:0:0:0:0:1")

    @Test
    fun mappedHexSeedMatchesDottedFlow() = assertIpMatch("::ffff:c000:201", "::ffff:192.0.2.1")

    @Test
    fun mappedDottedSeedMatchesHexFlow() = assertIpMatch("::ffff:192.0.2.1", "::ffff:c000:201")

    @Test
    fun bracketsAndPortStillMatch() = assertIpMatch("2001:db8::1", "[2001:0db8:0:0:0:0:0:1]:443")

    @Test
    fun ipv6CaseStillMatches() = assertIpMatch("2001:DB8::ABCD", "2001:db8::abcd")

    @Test
    fun ipv4PortStillMatches() = assertIpMatch("198.51.100.7", "198.51.100.7:443")

    @Test
    fun differentIpv6NeverMatches() {
        assertNull(matcher("2001:db8::1").match("2001:db8::2"))
    }

    @Test
    fun invalidColonLiteralNeverMatches() {
        assertNull(matcher("::::").match("::::"))
    }

    @Test
    fun excessiveGroupsNeverMatch() {
        assertNull(matcher("1:2:3:4:5:6:7:8:9").match("1:2:3:4:5:6:7:8:9"))
    }

    @Test
    fun invalidIpv4NeverMatches() {
        assertNull(matcher("256.51.100.7").match("256.51.100.7"))
    }

    @Test
    fun ambiguousLeadingZeroIpv4NeverMatches() {
        assertNull(matcher("019.51.100.7").match("019.51.100.7"))
    }

    @Test
    fun hostnameInIpListNeverBecomesAnIpHit() {
        assertNull(matcher("owned.example").match("owned.example"))
    }

    @Test
    fun incompleteBracketNeverMatches() {
        assertNull(matcher("2001:db8::1").match("[2001:db8::1"))
    }

    @Test
    fun invalidPortNeverMatches() {
        assertNull(matcher("198.51.100.7").match("198.51.100.7:not-a-port"))
    }

    @Test
    fun domainAndSubdomainStillMatch() {
        val m = NetworkIocMatcher(ThreatIntelDocument(domains = listOf("owned.example")))
        assertEquals(NetworkIocKind.DOMAIN, m.match("sub.OWNED.example.")?.kind)
        assertNull(m.match("notowned.example"))
    }

    @Test
    fun domainPortStillMatches() {
        val m = NetworkIocMatcher(ThreatIntelDocument(domains = listOf("owned.example")))
        assertEquals(NetworkIocKind.DOMAIN, m.match("owned.example:443")?.kind)
    }

    @Test
    fun unknownHostnameStaysUnmatched() {
        assertNull(matcher("198.51.100.7").match("never-resolve-owned.invalid"))
    }

    @Test
    fun zeroIpv6StillMatches() = assertIpMatch("0:0:0:0:0:0:0:0", "::")

    @Test
    fun ipv4AndIpv6RemainDifferentFamilies() {
        assertNull(matcher("192.0.2.1").match("::ffff:192.0.2.1"))
    }
}
