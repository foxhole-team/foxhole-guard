package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.TrafficMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoxholeConnectionServiceContractTest {
    @Test
    fun `service class follows traffic mode`() {
        assertEquals(FoxholeVpnService::class.java, FoxholeConnectionServiceContract.serviceClass(TrafficMode.TUNNEL))
        assertEquals(FoxholeProxyService::class.java, FoxholeConnectionServiceContract.serviceClass(TrafficMode.PROXY))
    }

    @Test
    fun `active snapshot keeps current service mode for disconnect routing`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.PROXY,
            )

        assertEquals(
            TrafficMode.PROXY,
            FoxholeConnectionServiceContract.serviceMode(
                snapshot = snapshot,
                fallbackMode = TrafficMode.TUNNEL,
            ),
        )
    }

    @Test
    fun `idle snapshot falls back to settings mode`() {
        val snapshot = ConnectionSnapshot(state = ConnectionState.IDLE, trafficMode = TrafficMode.TUNNEL)

        assertEquals(
            TrafficMode.PROXY,
            FoxholeConnectionServiceContract.serviceMode(
                snapshot = snapshot,
                fallbackMode = TrafficMode.PROXY,
            ),
        )
    }

    @Test
    fun `disconnect dispatch only uses active runtime snapshots`() {
        assertNull(
            disconnectDispatchModeOrNull(
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.TUNNEL,
                ),
            ),
        )

        assertEquals(
            TrafficMode.PROXY,
            disconnectDispatchModeOrNull(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.PROXY,
                ),
            ),
        )
    }
}
