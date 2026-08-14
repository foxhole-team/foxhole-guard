package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.model.ThreatIntelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkIocMatcherTest {

    private val matcher =
        NetworkIocMatcher(
            ThreatIntelDocument(
                domains = listOf("evil.example", "c2.tracker.example"),
                ips = listOf("198.51.100.7", "2001:db8::1"),
            ),
        )

    @Test
    fun `exact domain matches`() {
        assertEquals(NetworkIocHit(NetworkIocKind.DOMAIN, "evil.example"), matcher.match("evil.example"))
    }

    @Test
    fun `subdomain matches the listed parent and reports the parent`() {
        assertEquals(
            NetworkIocHit(NetworkIocKind.DOMAIN, "evil.example"),
            matcher.match("cdn.eu.evil.example"),
        )
    }

    @Test
    fun `a domain merely ending in the same text does not match`() {
        // A substring match would accuse this host; the walk is over labels, not characters.
        assertNull(matcher.match("notevil.example"))
    }

    @Test
    fun `a parent of a listed subdomain does not match`() {
        // `c2.tracker.example` is listed, `tracker.example` is not: matching up the tree would
        // condemn every neighbour under a shared parent.
        assertNull(matcher.match("tracker.example"))
    }

    @Test
    fun `a public suffix alone never matches`() {
        assertNull(matcher.match("example"))
    }

    @Test
    fun `literal addresses match, with port and brackets stripped`() {
        assertEquals(NetworkIocHit(NetworkIocKind.IP, "198.51.100.7"), matcher.match("198.51.100.7:443"))
        assertEquals(NetworkIocHit(NetworkIocKind.IP, "2001:db8::1"), matcher.match("[2001:db8::1]:8080"))
    }

    @Test
    fun `case and a trailing dot are normalized on both sides`() {
        assertEquals(NetworkIocHit(NetworkIocKind.DOMAIN, "evil.example"), matcher.match("EVIL.Example."))
    }

    @Test
    fun `an unknown destination is not a hit`() {
        assertNull(matcher.match("cdn.trusted.example"))
    }

    @Test
    fun `an empty bundle matches nothing and says so`() {
        val empty = NetworkIocMatcher(ThreatIntelDocument())

        assertTrue(empty.isEmpty)
        assertNull(empty.match("evil.example"))
    }
}

class SentinelNetworkIndicatorsTest {

    private val sentinel = FoxholeSentinel()
    private val matcher =
        NetworkIocMatcher(ThreatIntelDocument(domains = listOf("evil.example"), ips = listOf("198.51.100.7")))

    private fun event(host: String, vararg packages: String) =
        NetworkActivityEvent(
            timestampMs = 1_000L,
            packageNames = packages.toList(),
            protocol = "tcp",
            remoteHost = host,
            remotePort = 443,
            countryCode = null,
            bytesRx = 10,
            bytesTx = 20,
            profileId = 7L,
            sessionId = null,
        )

    @Test
    fun `a hit is attributed to every package that opened the flow`() {
        val findings =
            sentinel.matchNetworkIndicators(
                listOf(event("cdn.evil.example", "com.app.one", "com.app.two")),
                matcher,
            )

        assertEquals(listOf("com.app.one", "com.app.two"), findings.map { it.packageName })
        assertEquals("evil.example", findings.first().hit.indicator)
        // The source event's context travels with the finding so the caller can attribute a
        // journal entry without re-walking the events.
        assertEquals(7L, findings.first().profileId)
        assertEquals("tcp", findings.first().protocol)
        assertEquals(1_000L, findings.first().timestampMs)
    }

    @Test
    fun `clean destinations produce nothing`() {
        assertTrue(sentinel.matchNetworkIndicators(listOf(event("trusted.example", "com.app.one")), matcher).isEmpty())
    }

    @Test
    fun `an empty bundle short-circuits`() {
        val empty = NetworkIocMatcher(ThreatIntelDocument())

        assertTrue(sentinel.matchNetworkIndicators(listOf(event("evil.example", "com.app.one")), empty).isEmpty())
    }
}
