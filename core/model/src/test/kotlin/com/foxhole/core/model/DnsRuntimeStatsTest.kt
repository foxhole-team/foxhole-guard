package com.foxhole.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsRuntimeStatsTest {
    @Test
    fun `records authoritative DNS verdict deltas`() {
        DnsRuntimeStats.reset()

        DnsRuntimeStats.recordDnsVerdictDelta(blocked = 2L, allowed = 7L)
        DnsRuntimeStats.recordDnsVerdictDelta(blocked = -1L, allowed = 0L)

        val delta = DnsRuntimeStats.snapshot()
        assertEquals(2, delta.blocked)
        assertEquals(7, delta.allowed)
    }

    @Test
    fun `typed block breakdown normalizes domains without double counting totals`() {
        DnsRuntimeStats.reset()
        DnsRuntimeStats.recordDnsVerdictDelta(blocked = 2L, allowed = 0L)

        DnsRuntimeStats.recordBlockedDnsBreakdown(
            domain = "Ads.Example.Test.",
            category = null,
            packageName = null,
        )
        DnsRuntimeStats.recordBlockedDnsBreakdown(
            domain = "ads.example.test",
            category = null,
            packageName = null,
        )

        val delta = DnsRuntimeStats.drain()
        assertEquals(2, delta.blocked)
        assertEquals(mapOf("ads.example.test" to 2L), delta.blockedDomains)
        assertEquals(emptyMap<String, Long>(), DnsRuntimeStats.snapshot().blockedDomains)
    }

    @Test
    fun `typed block event preserves category and package attribution`() {
        DnsRuntimeStats.reset()

        DnsRuntimeStats.recordBlockedDnsBreakdown(
            domain = "tracker.example.test",
            category = DnsFilterCategory.TRACKERS,
            packageName = "com.example.app",
        )

        val delta = DnsRuntimeStats.drain()
        assertEquals(mapOf(DnsFilterCategory.TRACKERS to 1), delta.blockedByCategory)
        assertEquals(mapOf("com.example.app" to 1), delta.blockedByApp)
        assertEquals(emptyMap<DnsFilterCategory, Int>(), DnsRuntimeStats.snapshot().blockedByCategory)
        assertEquals(emptyMap<String, Int>(), DnsRuntimeStats.snapshot().blockedByApp)
    }

    @Test
    fun `missing event package falls back to a recent query observation`() {
        DnsRuntimeStats.reset()
        DnsRuntimeStats.recordDnsQueryObservation(
            domain = "Tracker.Example.Test.",
            packageNames = listOf("com.example.app"),
        )

        DnsRuntimeStats.recordBlockedDnsBreakdown(
            domain = "tracker.example.test",
            category = null,
            packageName = null,
        )

        assertEquals(mapOf("com.example.app" to 1), DnsRuntimeStats.snapshot().blockedByApp)
    }

    @Test
    fun `expired observations no longer attribute blocks`() {
        DnsRuntimeStats.reset()
        DnsRuntimeStats.recordDnsQueryObservation(
            domain = "old.example.test",
            packageNames = listOf("com.example.app"),
            timestampMs = System.currentTimeMillis() - DNS_QUERY_OBSERVATION_TTL_MS - 1_000L,
        )

        DnsRuntimeStats.recordBlockedDnsBreakdown(
            domain = "old.example.test",
            category = null,
            packageName = null,
        )

        assertEquals(emptyMap<String, Int>(), DnsRuntimeStats.snapshot().blockedByApp)
    }

    @Test
    fun `verdict totals saturate instead of overflowing`() {
        DnsRuntimeStats.reset()

        DnsRuntimeStats.recordDnsVerdictDelta(
            blocked = Int.MAX_VALUE.toLong(),
            allowed = Int.MAX_VALUE.toLong(),
        )
        DnsRuntimeStats.recordDnsVerdictDelta(blocked = 10L, allowed = 10L)

        assertEquals(Int.MAX_VALUE, DnsRuntimeStats.snapshot().blocked)
        assertEquals(Int.MAX_VALUE, DnsRuntimeStats.snapshot().allowed)
    }
}
