package com.foxhole.beta.vpn

import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.PrivacyRouteSettings
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.network.DNS_INDEPENDENT_IP_INFO_ENDPOINT
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
    fun `include selected apps tunnel allows runtime proxy tunnel validation`() {
        val settings =
            Settings(
                expert =
                    ExpertSettings(
                        perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                        selectedPackages = listOf("com.example.browser"),
                    ),
            )
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
                protocolHint = ProtocolHint.VLESS,
            )

        assertTrue(settings.allowsRuntimeProxyTunnelValidation(snapshot))
    }

    @Test
    fun `include selected apps tunnel keeps runtime proxy preferred after android validation`() {
        val settings =
            Settings(
                expert =
                    ExpertSettings(
                        perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                        selectedPackages = listOf("com.example.browser"),
                    ),
            )
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
                protocolHint = ProtocolHint.VLESS,
            )

        assertTrue(settings.requiresStrictRuntimeProxyIpRefresh(snapshot))
        assertFalse(settings.canUseVpnBoundIpRefreshFallback(snapshot, androidValidatedVpnNetwork = true))
        assertFalse(settings.canRecoverCachedActiveTunnelIpInfo(snapshot, androidValidatedVpnNetwork = true))
        assertFalse(settings.shouldPreferVpnBoundIpRefresh(snapshot, androidValidatedVpnNetwork = true))
    }

    @Test
    fun `full tunnel keeps runtime proxy out of tunnel validation`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
                protocolHint = ProtocolHint.VLESS,
            )

        assertFalse(Settings().allowsRuntimeProxyTunnelValidation(snapshot))
    }

    @Test
    fun `tor only selected apps allows runtime proxy tunnel validation`() {
        val settings =
            Settings(
                privacyRoute =
                    PrivacyRouteSettings(
                        scope = PrivacyRouteScope.SELECTED_APPS,
                        selectedPackages = listOf("org.torproject.torbrowser"),
                    ),
            )
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = TrafficMode.TUNNEL,
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                protocolHint = ProtocolHint.SING_BOX,
            )

        assertTrue(settings.allowsRuntimeProxyTunnelValidation(snapshot))
    }

    @Test
    fun `tor only all apps allows runtime proxy tunnel validation`() {
        val settings =
            Settings(
                privacyRoute =
                    PrivacyRouteSettings(
                        scope = PrivacyRouteScope.ALL_APPS,
                    ),
            )
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = TrafficMode.TUNNEL,
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                protocolHint = ProtocolHint.SING_BOX,
            )

        assertTrue(settings.allowsRuntimeProxyTunnelValidation(snapshot))
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
    fun `validated ordinary vpn refresh prefers vpn-bound path before runtime proxy`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
                protocolHint = ProtocolHint.VLESS,
            )

        assertTrue(Settings().shouldPreferVpnBoundIpRefresh(snapshot, androidValidatedVpnNetwork = true))
    }

    @Test
    fun `tor only refresh keeps runtime proxy path preferred`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
            )

        assertFalse(Settings().shouldPreferVpnBoundIpRefresh(snapshot, androidValidatedVpnNetwork = true))
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
    fun `strict dashboard ip refresh does not recover cached vpn result without android validation`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
                protocolHint = ProtocolHint.VLESS,
            )

        assertFalse(Settings().canRecoverCachedActiveTunnelIpInfo(snapshot, androidValidatedVpnNetwork = false))
        assertTrue(Settings().canRecoverCachedActiveTunnelIpInfo(snapshot, androidValidatedVpnNetwork = true))
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
    fun `android validated tunnel dashboard refresh uses dns independent default endpoint`() {
        assertEquals(
            DNS_INDEPENDENT_IP_INFO_ENDPOINT,
            activeTunnelIpRefreshEndpoint(
                configuredEndpoint = "",
                androidValidatedVpnNetwork = true,
            ),
        )
        assertEquals(
            DNS_INDEPENDENT_IP_INFO_ENDPOINT,
            activeTunnelIpRefreshEndpoint(
                configuredEndpoint = BuildConfig.DEFAULT_IP_INFO_ENDPOINT,
                androidValidatedVpnNetwork = true,
            ),
        )
    }

    @Test
    fun `custom active tunnel dashboard ip endpoint stays primary`() {
        val customEndpoint = "https://example.com/ip"

        assertEquals(
            customEndpoint,
            activeTunnelIpRefreshEndpoint(
                configuredEndpoint = customEndpoint,
                androidValidatedVpnNetwork = true,
            ),
        )
        assertEquals(
            customEndpoint,
            activeTunnelIpRefreshEndpoint(
                configuredEndpoint = customEndpoint,
                androidValidatedVpnNetwork = false,
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
