package com.foxhole.beta.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicConnectivityFallbackTargetsTest {
    @Test
    fun `dns-independent probe targets stay stable and tcp-only`() {
        val targets = dnsIndependentConnectivityProbeTargets()

        assertEquals(
            listOf(
                "1.1.1.1",
                "1.0.0.1",
                "2606:4700:4700::1111",
                "2606:4700:4700::1001",
            ),
            targets.map(VpnHealthProbeTarget::host),
        )
        assertTrue(targets.all { it.port == 443 })
        assertTrue(targets.all { it.transport == VpnHealthProbeTransport.TCP })
        assertEquals(targets.size, targets.distinct().size)
    }
}
