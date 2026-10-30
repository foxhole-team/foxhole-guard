package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.ThreatIntelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumericIpLiteralTest {

    @Test
    fun firstLongestZeroRunWinsTies() {
        assertEquals("2001::1:0:0:1:1", normalizeIpLiteral("2001:0:0:1:0:0:1:1"))
    }

    @Test
    fun singleZeroIsNeverCompressed() {
        assertEquals("2001:db8:0:1:2:3:4:5", normalizeIpLiteral("2001:db8:0:1:2:3:4:5"))
    }

    @Test
    fun doubleCompressionIsRejected() {
        assertNull(normalizeIpLiteral("2001::1::2"))
    }

    @Test
    fun loneLeadingColonIsRejected() {
        assertNull(normalizeIpLiteral(":2001:db8:0:0:0:0:0:1"))
    }

    @Test
    fun loneTrailingColonIsRejected() {
        assertNull(normalizeIpLiteral("2001:db8:0:0:0:0:0:1:"))
    }

    @Test
    fun unicodeIpv6DigitsAreRejected() {
        assertNull(normalizeIpLiteral("٢٠٠١:db8::1"))
    }

    @Test
    fun unicodeIpv4DigitsAreRejected() {
        assertNull(normalizeIpLiteral("١٩٨.51.100.7"))
    }

    @Test
    fun scopedAddressesAreRejected() {
        assertNull(normalizeIpLiteral("fe80::1%wlan0"))
    }

    @Test
    fun bracketedDomainIsRejected() {
        val matcher = NetworkIocMatcher(ThreatIntelDocument(domains = listOf("owned.example")))
        assertNull(matcher.match("[owned.example]:443"))
    }

    @Test
    fun zeroAndOversizedPortsAreRejected() {
        val matcher = NetworkIocMatcher(ThreatIntelDocument(ips = listOf("198.51.100.7")))
        assertNull(matcher.match("198.51.100.7:0"))
        assertNull(matcher.match("198.51.100.7:65536"))
    }
}
