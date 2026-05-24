package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.vpn.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeIpLoadingPolicyTest {
    @Test
    fun `does not keep ip loading after idle refresh has finished without data`() {
        assertFalse(
            shouldShowIpInfoLoading(
                currentIpInfo = null,
                explicitLoading = false,
                connectionState = ConnectionState.IDLE,
            ),
        )
    }

    @Test
    fun `stops showing ip loading after connected refresh has failed`() {
        assertFalse(
            shouldShowIpInfoLoading(
                currentIpInfo = null,
                explicitLoading = false,
                connectionState = ConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun `keeps explicit ip loading for manual refreshes`() {
        assertTrue(
            shouldShowIpInfoLoading(
                currentIpInfo =
                IpInfo(
                    ip = "1.1.1.1",
                    countryCode = null,
                    countryName = null,
                    city = null,
                    isp = null,
                    fetchedAt = 1L,
                ),
                explicitLoading = true,
                connectionState = ConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun `does not show ip loading while tunnel is still connecting`() {
        assertFalse(
            shouldShowIpInfoLoading(
                currentIpInfo = null,
                explicitLoading = false,
                connectionState = ConnectionState.CONNECTING,
            ),
        )
    }

    @Test
    fun `auto refreshes ip on foreground when disconnected`() {
        assertTrue(shouldAutoRefreshIpOnForeground(ConnectionState.IDLE))
        assertTrue(shouldAutoRefreshIpOnForeground(ConnectionState.CONNECTED))
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.CONNECTING))
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.RECONNECTING))
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.ERROR))
    }

    @Test
    fun `restored vpn refresh keeps existing ip visible`() {
        assertFalse(
            shouldClearExistingIpForRefresh(
                reason = IpInfoRefreshReason.RESTORED_VPN,
                clearExistingIp = true,
            ),
        )
    }

    @Test
    fun `manual clear request can still clear existing ip`() {
        assertTrue(
            shouldClearExistingIpForRefresh(
                reason = IpInfoRefreshReason.MANUAL,
                clearExistingIp = true,
            ),
        )
    }

    @Test
    fun `auto refreshes device ip after active connection ends`() {
        assertTrue(
            shouldAutoRefreshIpAfterDisconnect(
                previousState = ConnectionState.CONNECTED,
                currentState = ConnectionState.IDLE,
            ),
        )
        assertFalse(
            shouldAutoRefreshIpAfterDisconnect(
                previousState = ConnectionState.CONNECTING,
                currentState = ConnectionState.ERROR,
            ),
        )
        assertFalse(
            shouldAutoRefreshIpAfterDisconnect(
                previousState = ConnectionState.IDLE,
                currentState = ConnectionState.IDLE,
            ),
        )
        assertFalse(
            shouldAutoRefreshIpAfterDisconnect(
                previousState = ConnectionState.CONNECTED,
                currentState = ConnectionState.RECONNECTING,
            ),
        )
    }

    @Test
    fun `manual and foreground ip refreshes are superseded by connect`() {
        assertTrue(shouldSupersedeIpRefreshForConnect(IpInfoRefreshReason.MANUAL))
        assertTrue(shouldSupersedeIpRefreshForConnect(IpInfoRefreshReason.FOREGROUND))
        assertFalse(shouldSupersedeIpRefreshForConnect(IpInfoRefreshReason.POST_CONNECT))
        assertFalse(shouldSupersedeIpRefreshForConnect(IpInfoRefreshReason.POST_UPDATE))
        assertFalse(shouldSupersedeIpRefreshForConnect(IpInfoRefreshReason.TOR_ROUTE))
        assertFalse(shouldSupersedeIpRefreshForConnect(null))
    }

    @Test
    fun `connected tunnel refresh targets vpn bound network`() {
        assertEquals(
            IpInfoRefreshTarget.VPN_BOUND,
            ipInfoRefreshTargetForSnapshot(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                ),
            ),
        )
        assertEquals(
            IpInfoRefreshTarget.LOCAL_GUARD,
            ipInfoRefreshTargetForSnapshot(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                ),
            ),
        )
    }

    @Test
    fun `manual refresh skips connection metrics for local guard firewall`() {
        assertFalse(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
            ).shouldRefreshDashboardConnectionMetrics(),
        )
        assertTrue(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 7L,
            ).shouldRefreshDashboardConnectionMetrics(),
        )
    }

    @Test
    fun `local guard firewall shows network skeleton only during active device ip refresh`() {
        val idleModel =
            resolveHomeDashboardNetworkModel(
                state = HomeRouteUiState(
                    profilesLoaded = true,
                    settings = Settings(
                        expert = ExpertSettings(firewallEnabled = true),
                    ),
                    connection = ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        trafficMode = TrafficMode.TUNNEL,
                        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                    ),
                ),
                visibleIpInfo = null,
                deviceInternetAvailable = true,
            )

        assertFalse(idleModel.showLoading)
        assertFalse(idleModel.showIpInfoLoading)
        assertFalse(idleModel.showConnectionDetailsLoading)

        val model =
            resolveHomeDashboardNetworkModel(
                state = HomeRouteUiState(
                    profilesLoaded = true,
                    ipInfoLoading = true,
                    settings = Settings(
                        expert = ExpertSettings(firewallEnabled = true),
                    ),
                    connection = ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        trafficMode = TrafficMode.TUNNEL,
                        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                    ),
                ),
                visibleIpInfo = null,
                deviceInternetAvailable = true,
            )

        assertTrue(model.showLoading)
        assertTrue(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `shows ip loading during explicit idle refresh`() {
        assertTrue(
            shouldShowIpInfoLoading(
                currentIpInfo = null,
                explicitLoading = true,
                connectionState = ConnectionState.IDLE,
            ),
        )
    }

    @Test
    fun `does not show ip skeleton just because tunnel is connecting`() {
        assertFalse(
            shouldShowPendingNetworkLoading(
                visibleIpInfo = null,
                explicitLoading = false,
                connectionState = ConnectionState.CONNECTING,
                autoConnectRunning = false,
                deviceInternetAvailable = true,
                appLoaded = true,
            ),
        )
    }

    @Test
    fun `does not show ip skeleton just because smart start is running`() {
        assertFalse(
            shouldShowPendingNetworkLoading(
                visibleIpInfo = null,
                explicitLoading = false,
                connectionState = ConnectionState.IDLE,
                autoConnectRunning = true,
                deviceInternetAvailable = true,
                appLoaded = true,
            ),
        )
    }

    @Test
    fun `does not show pending network loading when device internet is explicitly offline`() {
        assertFalse(
            shouldShowPendingNetworkLoading(
                visibleIpInfo = null,
                explicitLoading = false,
                connectionState = ConnectionState.CONNECTING,
                autoConnectRunning = true,
                deviceInternetAvailable = false,
                appLoaded = false,
            ),
        )
    }

    @Test
    fun `does not show pending network loading when visible ip is already pinned`() {
        assertFalse(
            shouldShowPendingNetworkLoading(
                visibleIpInfo =
                IpInfo(
                    ip = "203.0.113.7",
                    countryCode = null,
                    countryName = null,
                    city = null,
                    isp = null,
                    fetchedAt = 1L,
                ),
                explicitLoading = true,
                connectionState = ConnectionState.CONNECTING,
                autoConnectRunning = true,
                deviceInternetAvailable = true,
                appLoaded = false,
            ),
        )
    }

    @Test
    fun `shows startup network skeleton while disconnected profile state loads`() {
        assertTrue(
            shouldShowPendingNetworkLoading(
                visibleIpInfo = null,
                explicitLoading = false,
                connectionState = ConnectionState.IDLE,
                autoConnectRunning = false,
                deviceInternetAvailable = true,
                appLoaded = false,
            ),
        )
    }

    @Test
    fun `does not keep pending network loading after app loaded when ip is empty`() {
        assertFalse(
            shouldShowPendingNetworkLoading(
                visibleIpInfo = null,
                explicitLoading = false,
                connectionState = ConnectionState.IDLE,
                autoConnectRunning = false,
                deviceInternetAvailable = true,
                appLoaded = true,
            ),
        )
    }

    @Test
    fun `traffic map legend loading is limited to startup vpn transitions and explicit refresh`() {
        assertTrue(
            shouldShowTrafficMapLegendLoading(
                connectionState = ConnectionState.IDLE,
                appLoaded = false,
            ),
        )
        assertTrue(
            shouldShowTrafficMapLegendLoading(
                connectionState = ConnectionState.CONNECTING,
                appLoaded = true,
            ),
        )
        assertFalse(
            shouldShowTrafficMapLegendLoading(
                connectionState = ConnectionState.IDLE,
                appLoaded = true,
            ),
        )
        assertFalse(
            shouldShowTrafficMapLegendLoading(
                connectionState = ConnectionState.IDLE,
                appLoaded = true,
            ),
        )
        assertFalse(
            shouldShowTrafficMapLegendLoading(
                connectionState = ConnectionState.CONNECTED,
                appLoaded = true,
            ),
        )
        assertTrue(
            shouldShowTrafficMapLegendLoading(
                connectionState = ConnectionState.IDLE,
                appLoaded = true,
                explicitLoading = true,
            ),
        )
    }

    @Test
    fun `shows dashboard network loading during explicit manual refresh even with current ip`() {
        assertTrue(
            shouldShowDashboardNetworkLoading(
                visibleIpInfo =
                IpInfo(
                    ip = "203.0.113.7",
                    countryCode = null,
                    countryName = null,
                    city = null,
                    isp = null,
                    fetchedAt = 1L,
                ),
                explicitLoading = true,
                connectionState = ConnectionState.CONNECTED,
                autoConnectRunning = false,
                deviceInternetAvailable = true,
                appLoaded = true,
            ),
        )
    }
}
