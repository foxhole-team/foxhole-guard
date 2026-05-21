package com.foxhole.beta.core.anomaly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsRuntimeStatsTest {
    @Test
    fun `records DNS blocked and allowed log lines without matching generic rejects`() {
        DnsRuntimeStats.reset()

        DnsRuntimeStats.recordLogMessage("rejected A ads.example.test")
        DnsRuntimeStats.recordLogMessage("cached AAAA example.test NOERROR")
        DnsRuntimeStats.recordLogMessage("outbound rejected connection")

        val delta = DnsRuntimeStats.snapshot()
        assertEquals(1, delta.blocked)
        assertEquals(1, delta.allowed)
        assertEquals(mapOf("ads.example.test" to 1L), delta.blockedDomains)
        assertTrue(isDnsRuntimeLogMessage("rejected HTTPS tracker.example.test"))
        assertFalse(isDnsRuntimeLogMessage("outbound rejected connection"))
    }

    @Test
    fun `normalizes blocked DNS domains before draining`() {
        DnsRuntimeStats.reset()

        DnsRuntimeStats.recordLogMessage("rejected A Ads.Example.Test.")
        DnsRuntimeStats.recordLogMessage("rejected HTTPS ads.example.test")

        val delta = DnsRuntimeStats.drain()
        assertEquals(mapOf("ads.example.test" to 2L), delta.blockedDomains)
        assertEquals(emptyMap<String, Long>(), DnsRuntimeStats.snapshot().blockedDomains)
    }

    @Test
    fun `counts foxhole DNS rule set predefined matches as blocked queries`() {
        DnsRuntimeStats.reset()

        DnsRuntimeStats.recordLogMessage(
            "DEBUG[0001] dns: [42 20ms] match[1] rule_set=foxhole-adguard-dns-filter => predefined(NXDOMAIN)",
        )
        DnsRuntimeStats.recordLogMessage("dns: exchanged missing.example.test NXDOMAIN 60")

        val delta = DnsRuntimeStats.snapshot()
        assertEquals(1, delta.blocked)
        assertEquals(0, delta.allowed)
        assertTrue(
            isDnsRuntimeLogMessage(
                "dns: [42 20ms] match[1] rule_set=foxhole-adguard-dns-filter => predefined(NXDOMAIN)",
            ),
        )
    }
}
