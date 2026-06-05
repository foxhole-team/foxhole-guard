package com.foxhole.beta.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficMapSampleWindowLabelTest {
    @Test
    fun `sample label distinguishes unavailable runtime from waiting traffic`() {
        val unavailable =
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

        assertEquals("Unavailable", unavailable.sampleWindowLabel)
        assertEquals("Waiting", waiting.sampleWindowLabel)
    }
}
