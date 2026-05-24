package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.PrivacyRouteSettings
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.network.IpInfoFetchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelRuntimeProxyIpRefreshPolicyTest {
    @Test
    fun `tor-only route requires runtime proxy ip refresh`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
            )

        assertTrue(Settings().requiresStrictRuntimeProxyIpRefresh(snapshot))
    }

    @Test
    fun `tor over vpn selected apps requires runtime proxy ip refresh for udp-backed vpn`() {
        val settings =
            Settings(
                privacyRoute = PrivacyRouteSettings(
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    scope = PrivacyRouteScope.SELECTED_APPS,
                    selectedPackages = listOf("org.tor.browser"),
                ),
            )
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
                protocolHint = ProtocolHint.HYSTERIA2,
            )

        assertTrue(settings.requiresStrictRuntimeProxyIpRefresh(snapshot))
    }

    @Test
    fun `tor over vpn runtime proxy ip does not replace dashboard vpn ip`() {
        val settings =
            Settings(
                privacyRoute = PrivacyRouteSettings(
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    scope = PrivacyRouteScope.SELECTED_APPS,
                    selectedPackages = listOf("org.tor.browser"),
                ),
            )
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
                protocolHint = ProtocolHint.VLESS,
            )

        assertFalse(settings.shouldPublishRuntimeProxyIpInfoToDashboard(snapshot))
    }

    @Test
    fun `tor only runtime proxy ip remains dashboard ip`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
            )

        assertTrue(Settings().shouldPublishRuntimeProxyIpInfoToDashboard(snapshot))
    }

    @Test
    fun `normal vpn requires runtime proxy ip refresh when app control plane is outside tunnel`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
                protocolHint = ProtocolHint.VLESS,
            )

        assertTrue(Settings().requiresStrictRuntimeProxyIpRefresh(snapshot))
    }

    @Test
    fun `strict dashboard ip refresh can fall back to vpn-bound path after android validation`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
                protocolHint = ProtocolHint.VLESS,
            )

        assertTrue(Settings().requiresStrictRuntimeProxyIpRefresh(snapshot))
        assertTrue(Settings().canUseVpnBoundIpRefreshFallback(snapshot, androidValidatedVpnNetwork = true))
    }

    @Test
    fun `strict dashboard ip refresh does not fall back without android validation`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
                protocolHint = ProtocolHint.VLESS,
            )

        assertTrue(Settings().requiresStrictRuntimeProxyIpRefresh(snapshot))
        assertFalse(Settings().canUseVpnBoundIpRefreshFallback(snapshot, androidValidatedVpnNetwork = false))
    }

    @Test
    fun `android validated tunnel dashboard refresh uses quick fetch mode`() {
        assertEquals(
            IpInfoFetchMode.ENTRY_QUICK,
            validatedTunnelIpRefreshFetchMode(
                requestedMode = IpInfoFetchMode.FULL,
                androidValidatedVpnNetwork = true,
            ),
        )
        assertEquals(
            IpInfoFetchMode.FULL,
            validatedTunnelIpRefreshFetchMode(
                requestedMode = IpInfoFetchMode.FULL,
                androidValidatedVpnNetwork = false,
            ),
        )
        assertEquals(
            IpInfoFetchMode.ENTRY_QUICK,
            validatedTunnelIpRefreshFetchMode(
                requestedMode = IpInfoFetchMode.ENTRY_QUICK,
                androidValidatedVpnNetwork = true,
            ),
        )
    }

    @Test
    fun `local guard does not require strict runtime proxy ip refresh`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
            )

        assertFalse(Settings().requiresStrictRuntimeProxyIpRefresh(snapshot))
    }
}
