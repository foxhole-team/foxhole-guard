package com.foxhole.core.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.network.DNS_INDEPENDENT_IP_INFO_ENDPOINT
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.guard.runtime.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
                ),
                expert =
                ExpertSettings(
                    appAssignments = mapOf("org.tor.browser" to AppTunnelLane.TOR),
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
    fun `tor over vpn all apps runtime proxy ip does not replace dashboard vpn ip`() {
        // All traffic (including the app's own probes) egresses through Tor: the observed exit is
        // the Tor exit and must never be shown as the VPN identity.
        val settings =
            Settings(
                privacyRoute = PrivacyRouteSettings(
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    scope = PrivacyRouteScope.ALL_APPS,
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
    fun `tor over vpn selected apps keeps publishing the vpn identity to the dashboard`() {
        // Only the chosen packages ride Tor; the app's own probes observe the plain VPN egress, so
        // the network card keeps showing the VPN server identity (and the probe result must never
        // be routed into the Tor exit channel).
        val settings =
            Settings(
                privacyRoute = PrivacyRouteSettings(
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    scope = PrivacyRouteScope.SELECTED_APPS,
                ),
                expert =
                ExpertSettings(
                    appAssignments = mapOf("org.tor.browser" to AppTunnelLane.TOR),
                ),
            )
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 42L,
                protocolHint = ProtocolHint.VLESS,
            )

        assertTrue(settings.shouldPublishRuntimeProxyIpInfoToDashboard(snapshot))
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
                    appAssignments = mapOf("com.example.browser" to AppTunnelLane.VPN),
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
                    appAssignments = mapOf("com.example.browser" to AppTunnelLane.VPN),
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
                ),
                expert =
                ExpertSettings(
                    appAssignments = mapOf("org.torproject.torbrowser" to AppTunnelLane.TOR),
                ),
            )
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = TrafficMode.TUNNEL,
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                protocolHint = ProtocolHint.CUSTOM_CONFIG,
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
                protocolHint = ProtocolHint.CUSTOM_CONFIG,
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

    @Test
    fun `device ip fallback retries the real explicit upstream network after default path failure`() {
        val source = vpnSourceFile("TunnelValidationGateway.kt").readText()
        val defaultNetworkBlock =
            source.substringAfter("private suspend fun fetchDeviceIpInfoFromDefaultNetwork")
                .substringBefore("private suspend fun fetchActiveTunnelIpInfo(")

        assertTrue(defaultNetworkBlock.contains("val upstreamNetwork = currentUpstreamNetwork() ?: throw error"))
        assertFalse(defaultNetworkBlock.contains("boundNetworkForAppOwnedRequest(currentUpstreamNetwork())"))
        assertTrue(defaultNetworkBlock.contains("network = upstreamNetwork"))
    }

    @Test
    fun `tor route ip refresh is runtime proxy only and cannot recover vpn bound result`() {
        val source = vpnSourceFile("TunnelValidationGateway.kt").readText()
        val torRefreshBlock =
            source.substringAfter("suspend fun refreshTorRouteIpInfo")
                .substringBefore("private suspend fun refreshIpInfo(")
        val runtimeProxyPreferenceBlock =
            source.substringAfter("private suspend fun fetchActiveTunnelIpInfoWithRuntimeProxyPreference")
                .substringBefore("private fun recoverCachedActiveTunnelIpInfo")

        assertTrue(torRefreshBlock.contains("forceRuntimeProxyOnly = true"))
        assertTrue(runtimeProxyPreferenceBlock.contains("allowVpnBoundFallback = allowVpnBoundFallback"))
        assertTrue(runtimeProxyPreferenceBlock.contains("allowVpnBoundFallback &&"))
    }

    private fun vpnSourceFile(name: String): File =
        listOf(
            File("src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("../core/runtime/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("app/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("../core/runtime/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("../app/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("../core/runtime/src/main/kotlin/com/foxhole/core/runtime/$name"),
        ).first { file -> file.isFile }
}
