package com.foxhole.guard.traffic

import com.foxhole.core.model.TrafficMapPoint
import com.foxhole.core.model.TrafficMapPointRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrafficMapSampleWindowLabelTest {
    @Test
    fun `empty map stays waiting while unavailable runtime is still surfaced`() {
        val emptyUnavailable =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo = null,
                runtimeAvailable = false,
                destinations = emptyList(),
            )
        val waiting =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo = null,
                runtimeAvailable = true,
                destinations = emptyList(),
            )
        val unavailableWithTraffic =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo = null,
                runtimeAvailable = false,
                destinations =
                listOf(
                    TrafficMapPoint(
                        countryCode = "DE",
                        label = "Germany",
                        lat = 51.16,
                        lon = 10.45,
                        bytes = 512L,
                        connections = 1,
                        role = TrafficMapPointRole.DESTINATION,
                    ),
                ),
            )

        assertEquals("Waiting", emptyUnavailable.sampleWindowLabel)
        assertFalse(emptyUnavailable.isAvailable)
        assertEquals("Waiting", waiting.sampleWindowLabel)
        assertEquals("Unavailable", unavailableWithTraffic.sampleWindowLabel)
        assertFalse(unavailableWithTraffic.isAvailable)
    }
}
