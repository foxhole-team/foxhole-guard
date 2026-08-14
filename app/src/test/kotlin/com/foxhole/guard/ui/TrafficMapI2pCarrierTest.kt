package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficMapI2pCarrier
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.map.CliI2pRouteTone
import com.foxhole.guard.ui.cli.map.i2pRouteConnectionLabelRes
import com.foxhole.guard.ui.cli.map.i2pRouteTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class TrafficMapI2pCarrierTest {
    @Test
    fun `live local guard carrier is a device connection`() {
        val carrier = requireNotNull(
            trafficMapI2pCarrier(
                connection = connected(LOCAL_GUARD_PROFILE_ID),
                i2pPhase = I2pNetworkPhase.CONNECTED,
            ),
        )

        assertEquals(TrafficMapI2pCarrier.DEVICE, carrier)
        assertEquals(CliI2pRouteTone.I2P, carrier.i2pRouteTone())
        assertEquals(R.string.cli_rt_i2p_device_connection, i2pRouteConnectionLabelRes(carrier))
    }

    @Test
    fun `live positive profile carrier is a vpn tunnel connection`() {
        val carrier = requireNotNull(
            trafficMapI2pCarrier(
                connection = connected(42L),
                i2pPhase = I2pNetworkPhase.CONNECTED,
            ),
        )

        assertEquals(TrafficMapI2pCarrier.VPN, carrier)
        assertEquals(CliI2pRouteTone.VPN, carrier.i2pRouteTone())
        assertEquals(R.string.cli_rt_i2p_tunnel_connection, i2pRouteConnectionLabelRes(carrier))
    }

    @Test
    fun `only tor only runtime owns the tor carrier`() {
        val carrier = requireNotNull(
            trafficMapI2pCarrier(
                connection = connected(TOR_ONLY_PROFILE_ID),
                i2pPhase = I2pNetworkPhase.CONNECTED,
            ),
        )

        assertEquals(TrafficMapI2pCarrier.TOR, carrier)
        assertEquals(CliI2pRouteTone.TOR, carrier.i2pRouteTone())
        assertEquals(R.string.cli_rt_i2p_tunnel_connection, i2pRouteConnectionLabelRes(carrier))
    }

    @Test
    fun `vpn plus tor remains a vpn carrier`() {
        val carrier = requireNotNull(
            trafficMapI2pCarrier(
                connection = connected(42L).copy(torActive = true),
                i2pPhase = I2pNetworkPhase.CONNECTED,
            ),
        )

        assertEquals(TrafficMapI2pCarrier.VPN, carrier)
        assertEquals(CliI2pRouteTone.VPN, carrier.i2pRouteTone())
        assertEquals(R.string.cli_rt_i2p_tunnel_connection, i2pRouteConnectionLabelRes(carrier))
    }

    @Test
    fun `stale owner or non ready i2p phase cannot claim a carrier`() {
        assertNull(
            trafficMapI2pCarrier(
                connection = connected(LOCAL_GUARD_PROFILE_ID).copy(state = ConnectionState.IDLE),
                i2pPhase = I2pNetworkPhase.CONNECTED,
            ),
        )
        assertNull(
            trafficMapI2pCarrier(
                connection = connected(42L),
                i2pPhase = I2pNetworkPhase.BUILDING_TUNNELS,
            ),
        )
        assertNull(
            trafficMapI2pCarrier(
                connection = connected(null),
                i2pPhase = I2pNetworkPhase.CONNECTED,
            ),
        )
    }

    private fun connected(profileId: Long?): ConnectionSnapshot =
        ConnectionSnapshot(
            state = ConnectionState.CONNECTED,
            profileId = profileId,
        )
}
