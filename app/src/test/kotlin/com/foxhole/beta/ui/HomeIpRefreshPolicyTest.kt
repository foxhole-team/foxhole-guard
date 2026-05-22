package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.vpn.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeIpRefreshPolicyTest {
    @Test
    fun `refreshes after tunnel becomes connected without ip info`() {
        assertTrue(
            shouldAutoRefreshIpAfterConnect(
                previousState = ConnectionState.CONNECTING,
                currentState = ConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun `refreshes after connect even when the previous device ip is still visible`() {
        assertTrue(
            shouldAutoRefreshIpAfterConnect(
                previousState = ConnectionState.IDLE,
                currentState = ConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun `does not refresh before connected state`() {
        assertFalse(
            shouldAutoRefreshIpAfterConnect(
                previousState = ConnectionState.IDLE,
                currentState = ConnectionState.CONNECTING,
            ),
        )
    }

    @Test
    fun `does not requeue refresh while already connected`() {
        assertFalse(
            shouldAutoRefreshIpAfterConnect(
                previousState = ConnectionState.CONNECTED,
                currentState = ConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun `post connect refresh starts quickly`() {
        assertTrue(HomeViewModel.CONNECTED_IP_REFRESH_DELAY_MS <= 500L)
    }

    @Test
    fun `post connect refresh retries while runtime proxy settles`() {
        assertEquals(
            HomeViewModel.CONNECTED_IP_REFRESH_ATTEMPTS,
            ipInfoRefreshAttemptsForReason(IpInfoRefreshReason.POST_CONNECT),
        )
        assertEquals(
            HomeViewModel.CONNECTED_IP_REFRESH_RETRY_DELAY_MS,
            ipInfoRefreshRetryDelayMsForReason(IpInfoRefreshReason.POST_CONNECT),
        )
        assertEquals(
            HomeViewModel.TOR_IP_REFRESH_ATTEMPTS,
            ipInfoRefreshAttemptsForReason(IpInfoRefreshReason.TOR_ROUTE),
        )
        assertEquals(1, ipInfoRefreshAttemptsForReason(IpInfoRefreshReason.MANUAL))
    }

    @Test
    fun `post connect refresh is silent and schedules latency five seconds after ip`() {
        assertFalse(
            shouldClearExistingIpForRefresh(
                reason = IpInfoRefreshReason.POST_CONNECT,
                clearExistingIp = true,
            ),
        )
        assertEquals(5_000L, HomeViewModel.POST_CONNECT_LATENCY_AFTER_IP_DELAY_MS)
    }

    @Test
    fun `automatic dashboard refreshes use quick fetch mode`() {
        assertEquals(IpInfoFetchMode.FULL, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.MANUAL))
        assertEquals(IpInfoFetchMode.ENTRY_QUICK, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.POST_CONNECT))
        assertEquals(IpInfoFetchMode.ENTRY_QUICK, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.POST_UPDATE))
        assertEquals(IpInfoFetchMode.ENTRY_QUICK, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.FOREGROUND))
        assertEquals(IpInfoFetchMode.ENTRY_QUICK, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.RESTORED_VPN))
        assertEquals(IpInfoFetchMode.ENTRY_QUICK, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.TOR_ROUTE))
    }

    @Test
    fun `manual network refresh failure is silent for local guard firewall`() {
        assertFalse(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
            ).shouldReportManualDashboardIpRefreshFailures(),
        )
        assertTrue(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 7L,
            ).shouldReportManualDashboardIpRefreshFailures(),
        )
    }

    @Test
    fun `manual network refresh failure is silent for standalone tor runtime`() {
        assertFalse(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
            ).shouldReportManualDashboardIpRefreshFailures(),
        )
    }
}
