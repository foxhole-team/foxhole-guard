package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectivityHealthState
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeUpdatePolicyTest {
    @Test
    fun `traffic sampling stays live for visible traffic UI`() {
        assertEquals(1_000L, RuntimeUpdatePolicy.trafficUpdateIntervalMs(highFrequencyUiActive = true))
    }

    @Test
    fun `traffic sampling backs off when dashboard and statistics are not visible`() {
        assertEquals(30_000L, RuntimeUpdatePolicy.trafficUpdateIntervalMs(highFrequencyUiActive = false))
    }

    @Test
    fun `health probes use stable interval only after online state`() {
        assertEquals(60_000L, RuntimeUpdatePolicy.notificationHealthProbeIntervalMs(ConnectivityHealthState.ONLINE))
        assertEquals(10_000L, RuntimeUpdatePolicy.notificationHealthProbeIntervalMs(ConnectivityHealthState.CHECKING))
    }
}
