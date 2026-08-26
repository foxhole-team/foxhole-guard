package com.foxhole.core.runtime

import com.foxhole.core.model.TrafficMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIpRefreshTunnelBypassTest {
    @Test
    fun `idle without vpn network keeps the plain app-owned path`() {
        assertFalse(
            deviceIpRefreshRequiresExplicitUpstreamNetwork(
                trafficMode = TrafficMode.TUNNEL,
                vpnNetworkPresent = false,
                sessionEngaged = false,
                localGuardRuntimeActive = false,
                localGuardAppExcluded = false,
            ),
        )
    }

    @Test
    fun `vpn network present forces upstream binding even when snapshot still says idle`() {
        assertTrue(
            deviceIpRefreshRequiresExplicitUpstreamNetwork(
                trafficMode = TrafficMode.TUNNEL,
                vpnNetworkPresent = true,
                sessionEngaged = false,
                localGuardRuntimeActive = false,
                localGuardAppExcluded = false,
            ),
        )
    }

    @Test
    fun `engaged session forces upstream binding before the tun is observed`() {
        assertTrue(
            deviceIpRefreshRequiresExplicitUpstreamNetwork(
                trafficMode = TrafficMode.TUNNEL,
                vpnNetworkPresent = false,
                sessionEngaged = true,
                localGuardRuntimeActive = false,
                localGuardAppExcluded = false,
            ),
        )
    }

    @Test
    fun `local guard with captured app forces upstream binding`() {
        assertTrue(
            deviceIpRefreshRequiresExplicitUpstreamNetwork(
                trafficMode = TrafficMode.TUNNEL,
                vpnNetworkPresent = false,
                sessionEngaged = false,
                localGuardRuntimeActive = true,
                localGuardAppExcluded = false,
            ),
        )
    }

    @Test
    fun `local guard with excluded app uses unrestricted default path`() {
        assertFalse(
            deviceIpRefreshRequiresExplicitUpstreamNetwork(
                trafficMode = TrafficMode.TUNNEL,
                vpnNetworkPresent = true,
                sessionEngaged = true,
                localGuardRuntimeActive = true,
                localGuardAppExcluded = true,
            ),
        )
    }

    @Test
    fun `proxy traffic mode never requires tunnel bypass`() {
        assertFalse(
            deviceIpRefreshRequiresExplicitUpstreamNetwork(
                trafficMode = TrafficMode.PROXY,
                vpnNetworkPresent = false,
                sessionEngaged = true,
                localGuardRuntimeActive = false,
                localGuardAppExcluded = false,
            ),
        )
    }
}
