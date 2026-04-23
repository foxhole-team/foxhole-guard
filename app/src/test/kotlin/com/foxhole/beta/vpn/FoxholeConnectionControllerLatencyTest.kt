package com.foxhole.beta.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoxholeConnectionControllerLatencyTest {
    @Test
    fun `latency probe endpoints use live neutral public https targets`() {
        assertEquals(
            FoxholeVpnService.CONNECTIVITY_PROBE_ENDPOINTS,
            latencyProbeEndpoints(),
        )
    }

    @Test
    fun `representative latency is null when there are no successful probes`() {
        assertNull(representativeLatencyMs(emptyList()))
    }

    @Test
    fun `representative latency keeps a single successful probe`() {
        assertEquals(184L, representativeLatencyMs(listOf(184L)))
    }

    @Test
    fun `representative latency averages two successful probes`() {
        assertEquals(155L, representativeLatencyMs(listOf(130L, 180L)))
    }

    @Test
    fun `representative latency uses the median and ignores outlier spikes`() {
        assertEquals(122L, representativeLatencyMs(listOf(900L, 122L, 97L)))
    }
}
