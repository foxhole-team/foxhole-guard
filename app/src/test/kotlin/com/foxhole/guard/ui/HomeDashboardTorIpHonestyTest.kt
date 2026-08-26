package com.foxhole.guard.ui

import com.foxhole.core.model.AppliedTorRoute
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.runtime.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Test

internal class HomeDashboardTorIpHonestyTest {
    private val torExit =
        IpInfo(
            ip = "185.220.101.1",
            countryCode = "DE",
            countryName = "Germany",
            city = "Berlin",
            isp = "Tor",
            fetchedAt = 1_000L,
        )
    private val deviceIp =
        IpInfo(
            ip = "203.0.113.7",
            countryCode = "US",
            countryName = "United States",
            city = "NYC",
            isp = "Carrier",
            fetchedAt = 1_000L,
        )

    private fun firewallSnapshot() =
        ConnectionSnapshot(
            state = ConnectionState.CONNECTED,
            trafficMode = TrafficMode.TUNNEL,
            profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
            torActive = false,
        )

    @Test
    fun `firewall runtime never shows a lingering tor exit ip`() {
        val state =
            HomeRouteUiState(
                settings =
                Settings(
                    privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN),
                    expert = ExpertSettings(firewallEnabled = true),
                ),
                connection = firewallSnapshot(),
                torIpInfo = torExit,
                deviceIpInfo = deviceIp,
            )

        assertEquals(deviceIp, state.dashboardVisibleIpInfo(visibleIpInfo = deviceIp))
    }

    @Test
    fun `active tor-only runtime still shows the tor exit ip`() {
        val state =
            HomeRouteUiState(
                settings = Settings(privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN)),
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                    torActive = true,
                    appliedTorRoute =
                    AppliedTorRoute(
                        scope = PrivacyRouteScope.ALL_APPS,
                        bypassVpnTunnel = true,
                    ),
                ),
                torIpInfo = torExit,
                deviceIpInfo = deviceIp,
            )

        assertEquals(torExit, state.dashboardVisibleIpInfo(visibleIpInfo = deviceIp))
    }

    @Test
    fun `VPN-only applied snapshot drops a late TOR exit publication`() {
        val vpnTor =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                profileId = 42L,
                torActive = true,
                appliedTorRoute =
                AppliedTorRoute(
                    scope = PrivacyRouteScope.SELECTED_APPS,
                    bypassVpnTunnel = false,
                ),
            )

        assertEquals(torExit, torIpInfoForAppliedRuntime(vpnTor, torExit))
        assertEquals(
            null,
            torIpInfoForAppliedRuntime(
                vpnTor.copy(torActive = false, appliedTorRoute = null),
                torExit,
            ),
        )
    }
}
