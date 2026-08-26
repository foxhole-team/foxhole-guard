package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.ThreatIndicatorKind
import com.foxhole.core.model.ThreatIntelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkIocIndicatorKindTest {
    private val matcher =
        NetworkIocMatcher(
            ThreatIntelDocument(
                schema = ThreatIntelDocument.SCHEMA,
                domains = listOf("c2.evil.example", "downloads.evil.example", "cdn.shared.example", "unsaid.example"),
                ips = listOf("198.51.100.7", "203.0.113.9"),
                indicatorKinds =
                mapOf(
                    "c2.evil.example" to "COMMAND_AND_CONTROL",
                    "downloads.evil.example" to "MALWARE_DISTRIBUTION",
                    "cdn.shared.example" to "SHARED_INFRASTRUCTURE",
                    "198.51.100.7" to "COMMAND_AND_CONTROL",
                    "203.0.113.9" to "SHARED_INFRASTRUCTURE",
                ),
            ),
        )

    @Test
    fun `each indicator carries the kind the feed gave it`() {
        assertEquals(
            ThreatIndicatorKind.COMMAND_AND_CONTROL,
            matcher.match("c2.evil.example")?.threatKind,
        )
        assertEquals(
            ThreatIndicatorKind.MALWARE_DISTRIBUTION,
            matcher.match("downloads.evil.example")?.threatKind,
        )
        assertEquals(
            ThreatIndicatorKind.SHARED_INFRASTRUCTURE,
            matcher.match("cdn.shared.example")?.threatKind,
        )
    }

    @Test
    fun `a subdomain inherits the listed parent's kind`() {
        val hit = matcher.match("eu-west.c2.evil.example")

        assertEquals("c2.evil.example", hit?.indicator)
        assertEquals(ThreatIndicatorKind.COMMAND_AND_CONTROL, hit?.threatKind)
    }

    @Test
    fun `literal addresses carry their kind too`() {
        assertEquals(ThreatIndicatorKind.COMMAND_AND_CONTROL, matcher.match("198.51.100.7:443")?.threatKind)
        assertEquals(ThreatIndicatorKind.SHARED_INFRASTRUCTURE, matcher.match("203.0.113.9")?.threatKind)
    }

    @Test
    fun `an indicator the feed listed without a kind is unclassified, not the loudest`() {
        val hit = matcher.match("unsaid.example")

        assertEquals(ThreatIndicatorKind.UNCLASSIFIED, hit?.threatKind)
        assertNotEquals(ThreatIndicatorKind.COMMAND_AND_CONTROL, hit?.threatKind)
    }

    @Test
    fun `a kind from a future feed degrades that indicator instead of failing the document`() {
        val forwardCompatible =
            NetworkIocMatcher(
                ThreatIntelDocument(
                    domains = listOf("phish.example", "c2.example"),
                    indicatorKinds =
                    mapOf(
                        "phish.example" to "PHISHING_KIT",
                        "c2.example" to "COMMAND_AND_CONTROL",
                    ),
                ),
            )

        assertEquals(ThreatIndicatorKind.UNCLASSIFIED, forwardCompatible.match("phish.example")?.threatKind)
        // The rest of the document is untouched: one unreadable kind must not cost the feed.
        assertEquals(ThreatIndicatorKind.COMMAND_AND_CONTROL, forwardCompatible.match("c2.example")?.threatKind)
    }

    @Test
    fun `a classification survives normalization of the indicator`() {
        val messy =
            NetworkIocMatcher(
                ThreatIntelDocument(
                    domains = listOf("C2.Evil.Example."),
                    indicatorKinds = mapOf("c2.evil.example." to "COMMAND_AND_CONTROL"),
                ),
            )

        assertEquals(ThreatIndicatorKind.COMMAND_AND_CONTROL, messy.match("c2.evil.example")?.threatKind)
    }

    @Test
    fun `an indicator listed twice keeps the louder classification`() {
        val duplicated =
            NetworkIocMatcher(
                ThreatIntelDocument(
                    domains = listOf("both.example", "both.example"),
                    indicatorKinds = mapOf("both.example" to "COMMAND_AND_CONTROL"),
                ),
            )

        assertEquals(ThreatIndicatorKind.COMMAND_AND_CONTROL, duplicated.match("both.example")?.threatKind)
    }

    @Test
    fun `only a command-and-control hit reaches HIGH on its own`() {
        assertEquals(AnomalySeverity.HIGH, ThreatIndicatorKind.COMMAND_AND_CONTROL.networkIocSeverity())
        ThreatIndicatorKind.entries
            .filter { kind -> kind != ThreatIndicatorKind.COMMAND_AND_CONTROL }
            .forEach { kind ->
                assertNotEquals(
                    "$kind must not produce a HIGH finding on its own",
                    AnomalySeverity.HIGH,
                    kind.networkIocSeverity(),
                )
            }
    }

    @Test
    fun `shared infrastructure is recorded but never notified`() {
        val severity = ThreatIndicatorKind.SHARED_INFRASTRUCTURE.networkIocSeverity()

        assertEquals(AnomalySeverity.ACTIVITY_LOG, severity)
        assertTrue(severity.ordinal < AnomalySeverity.NOTIFICATION.ordinal)
    }

    @Test
    fun `the score follows the same ordering as the severity`() {
        val ordered =
            listOf(
                ThreatIndicatorKind.COMMAND_AND_CONTROL,
                ThreatIndicatorKind.MALWARE_DISTRIBUTION,
                ThreatIndicatorKind.UNCLASSIFIED,
                ThreatIndicatorKind.SHARED_INFRASTRUCTURE,
            ).map(ThreatIndicatorKind::networkIocScore)

        assertEquals(ordered.sortedDescending(), ordered)
        assertEquals(ordered.distinct(), ordered)
    }
}

class LegacyThreatIntelFeedTest {
    private val legacy =
        ThreatIntelDocument(
            schema = 3,
            packages = listOf("com.spy.app"),
            certsSha1 = listOf("a".repeat(40)),
            domains = listOf("evil.example"),
            ips = listOf("198.51.100.7"),
        )

    @Test
    fun `a schema-3 feed is still one this build reads`() {
        assertTrue(ThreatIntelDocument.supportsSchema(legacy.schema))
        assertTrue(ThreatIntelDocument.supportsSchema(ThreatIntelDocument.SCHEMA))
    }

    @Test
    fun `a feed from the future is refused rather than half-read`() {
        assertTrue(!ThreatIntelDocument.supportsSchema(ThreatIntelDocument.SCHEMA + 1))
    }

    @Test
    fun `its indicators still match and are unclassified`() {
        val matcher = NetworkIocMatcher(legacy)

        assertEquals(ThreatIndicatorKind.UNCLASSIFIED, matcher.match("cdn.evil.example")?.threatKind)
        assertEquals(ThreatIndicatorKind.UNCLASSIFIED, matcher.match("198.51.100.7")?.threatKind)
    }

    @Test
    fun `an unclassified hit never reaches HIGH`() {
        assertNotEquals(AnomalySeverity.HIGH, ThreatIndicatorKind.UNCLASSIFIED.networkIocSeverity())
        assertTrue(
            ThreatIndicatorKind.UNCLASSIFIED.networkIocScore() <
                ThreatIndicatorKind.COMMAND_AND_CONTROL.networkIocScore(),
        )
    }
}
