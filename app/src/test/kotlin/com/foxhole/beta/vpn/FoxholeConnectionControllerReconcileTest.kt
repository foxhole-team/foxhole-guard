package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.TrafficMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoxholeConnectionControllerReconcileTest {
    @Test
    fun `missing active vpn network timeout publishes fail-closed error snapshot`() {
        val snapshot =
            failClosedMissingActiveVpnNetworkSnapshot(
                trafficMode = TrafficMode.TUNNEL,
                message = "VPN runtime stopped unexpectedly",
            )

        assertEquals(ConnectionState.ERROR, snapshot.state)
        assertEquals(TrafficMode.TUNNEL, snapshot.trafficMode)
        assertEquals("VPN runtime stopped unexpectedly", snapshot.message)
        assertNull(snapshot.profileId)
        assertNull(snapshot.profileName)
        assertNull(snapshot.protocolHint)
        assertNull(snapshot.protocolOptionId)
    }
}
