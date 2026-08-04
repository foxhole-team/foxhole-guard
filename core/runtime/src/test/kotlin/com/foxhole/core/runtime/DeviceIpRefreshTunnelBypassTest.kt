package com.foxhole.core.runtime

import com.foxhole.core.model.TrafficMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the traffic-map origin poisoning fix: a device (upstream-truth) IP refresh
 * must be forced onto the upstream network whenever our tun could be the process default network —
 * including the connect/stop race where the VPN network is already up while the control-plane
 * snapshot still reports IDLE. An unbound fetch in that window returns the tunnel/Tor egress and
 * permanently poisons deviceIpInfo (the traffic-map origin latch pinned the phone to the Tor exit).
 */
class DeviceIpRefreshTunnelBypassTest {

    @Test
    fun `idle without vpn network keeps the plain app-owned path`() {
        assertFalse(
            deviceIpRefreshMustBypassRuntimeTunnel(
                trafficMode = TrafficMode.TUNNEL,
                vpnNetworkPresent = false,
                sessionEngaged = false,
                localGuardRuntimeActive = false,
            ),
        )
    }

    @Test
    fun `vpn network present forces upstream binding even when snapshot still says idle`() {
        assertTrue(
            deviceIpRefreshMustBypassRuntimeTunnel(
                trafficMode = TrafficMode.TUNNEL,
                vpnNetworkPresent = true,
                sessionEngaged = false,
                localGuardRuntimeActive = false,
            ),
        )
    }

    @Test
    fun `engaged session forces upstream binding before the tun is observed`() {
        assertTrue(
            deviceIpRefreshMustBypassRuntimeTunnel(
                trafficMode = TrafficMode.TUNNEL,
                vpnNetworkPresent = false,
                sessionEngaged = true,
                localGuardRuntimeActive = false,
            ),
        )
    }

    @Test
    fun `local guard runtime forces upstream binding`() {
        assertTrue(
            deviceIpRefreshMustBypassRuntimeTunnel(
                trafficMode = TrafficMode.TUNNEL,
                vpnNetworkPresent = false,
                sessionEngaged = false,
                localGuardRuntimeActive = true,
            ),
        )
    }

    @Test
    fun `proxy traffic mode never requires tunnel bypass`() {
        // No tun exists in proxy mode; forcing upstream binding there would break the proxy-mode
        // device refresh when the upstream callback has not resolved yet.
        assertFalse(
            deviceIpRefreshMustBypassRuntimeTunnel(
                trafficMode = TrafficMode.PROXY,
                vpnNetworkPresent = false,
                sessionEngaged = true,
                localGuardRuntimeActive = false,
            ),
        )
    }
}
