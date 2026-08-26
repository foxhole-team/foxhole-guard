package com.foxhole.core.runtime

import com.foxhole.core.model.AppliedTorRoute
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.TrafficMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TunnelIpRefreshCacheKeyTest {
    @Test
    fun `hot route change cannot reuse a tunnel identity from the other egress`() {
        val plainVpn =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
                protocolOptionId = "tcp",
                lastChangeAt = 5_000L,
            )
        val vpnTor =
            plainVpn.copy(
                torActive = true,
                appliedTorRoute = AppliedTorRoute(PrivacyRouteScope.ALL_APPS, bypassVpnTunnel = false),
            )
        val selectedTor =
            vpnTor.copy(
                appliedTorRoute = AppliedTorRoute(PrivacyRouteScope.SELECTED_APPS, bypassVpnTunnel = false),
            )

        assertEquals(ActiveTunnelIpInfoEgressRole.VPN, plainVpn.activeTunnelIpInfoEgressRole())
        assertEquals(ActiveTunnelIpInfoEgressRole.TOR, vpnTor.activeTunnelIpInfoEgressRole())
        assertEquals(ActiveTunnelIpInfoEgressRole.VPN, selectedTor.activeTunnelIpInfoEgressRole())
        assertNotEquals(plainVpn.activeTunnelIpInfoCacheKey(), vpnTor.activeTunnelIpInfoCacheKey())
        assertEquals(
            plainVpn.activeTunnelIpInfoCacheKey().egressRole,
            selectedTor.activeTunnelIpInfoCacheKey().egressRole,
        )
    }
}
