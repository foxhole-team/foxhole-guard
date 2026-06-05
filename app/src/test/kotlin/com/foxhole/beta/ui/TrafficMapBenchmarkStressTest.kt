package com.foxhole.beta.ui

import com.foxhole.beta.core.model.TrafficMapPointRole
import com.foxhole.beta.core.traffic.TrafficMapRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMapBenchmarkStressTest {
    @Test
    fun `benchmark stress state exercises max destination and retained connection caps`() {
        val state =
            TrafficMapBenchmarkStress.buildState(
                repository = TrafficMapRepository(),
                nowMs = 1_706_000_000_000L,
            )

        assertTrue(state.isAvailable)
        assertEquals(TrafficMapRepository.MaxTrafficMapDestinations, state.destinations.size)
        assertEquals(TrafficMapRepository.MaxRetainedConnectionSamples, state.totalConnections)
        assertEquals(TrafficMapRepository.MaxTrafficMapDestinations, state.countryCount)
        assertEquals("Live", state.sampleWindowLabel)
        assertNotNull(state.vpnRoute)
        assertNotNull(state.torExit)
        assertEquals(TrafficMapPointRole.VPN_ROUTE, state.vpnRoute?.role)
        assertEquals(TrafficMapPointRole.TOR_EXIT, state.torExit?.role)
        assertEquals(TrafficMapRepository.MaxRetainedConnectionSamples, state.vpnRoute?.connections)
        assertEquals(TrafficMapRepository.MaxRetainedConnectionSamples, state.torExit?.connections)
        assertTrue(state.edges.size >= TrafficMapRepository.MaxTrafficMapDestinations)
        assertEquals(8, state.countryVisuals.count { visual -> visual.isNewCountry })
    }
}
