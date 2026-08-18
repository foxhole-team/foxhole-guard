package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProtocolTestRoutingModeTest {
    @Test
    fun `smart profile test enters vpn mode and restores the exact routing fields`() {
        val initial = Settings(
            connection = ConnectionSettings(safeModeEnabled = false),
            traffic = TrafficSettings(mode = TrafficMode.PROXY, mtu = 1_420),
            expert = ExpertSettings(perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS),
            privacyRoute = PrivacyRouteSettings(
                mode = PrivacyRouteMode.TOR_OVER_VPN,
                bypassVpnTunnel = true,
                permitted = true,
            ),
        )
        val checkpoint = initial.protocolTestRoutingCheckpoint()

        val testing = initial.forVpnOnlyProtocolTest()

        assertEquals(TrafficMode.TUNNEL, testing.traffic.mode)
        assertEquals(PerAppRoutingMode.FULL_TUNNEL, testing.expert.perAppRoutingMode)
        assertEquals(PrivacyRouteMode.OFF, testing.privacyRoute.mode)
        assertFalse(testing.connection.safeModeEnabled)

        val restored = testing
            .copy(traffic = testing.traffic.copy(mtu = 1_380))
            .restoreAfterVpnOnlyProtocolTest(checkpoint)

        assertEquals(TrafficMode.PROXY, restored.traffic.mode)
        assertEquals(PerAppRoutingMode.INCLUDE_SELECTED_APPS, restored.expert.perAppRoutingMode)
        assertEquals(1_380, restored.traffic.mtu)
        assertEquals(initial.privacyRoute, restored.privacyRoute)
        assertEquals(initial.connection.safeModeEnabled, restored.connection.safeModeEnabled)
    }
}
