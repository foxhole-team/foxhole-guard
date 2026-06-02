package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteSettings
import com.foxhole.beta.core.model.Settings
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
    fun `network change refresh starts immediately to avoid skeleton blink`() {
        assertEquals(0L, connectedIpRefreshStartDelayMs(IpInfoRefreshReason.NETWORK_CHANGE))
        assertEquals(
            HomeViewModel.CONNECTED_IP_REFRESH_DELAY_MS,
            connectedIpRefreshStartDelayMs(IpInfoRefreshReason.POST_CONNECT),
        )
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
    fun `dashboard refreshes use quick fetch mode`() {
        assertEquals(IpInfoFetchMode.ENTRY_QUICK, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.MANUAL))
        assertEquals(IpInfoFetchMode.ENTRY_QUICK, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.POST_CONNECT))
        assertEquals(IpInfoFetchMode.ENTRY_QUICK, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.POST_UPDATE))
        assertEquals(IpInfoFetchMode.ENTRY_QUICK, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.FOREGROUND))
        assertEquals(IpInfoFetchMode.ENTRY_QUICK, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.RESTORED_VPN))
        assertEquals(IpInfoFetchMode.ENTRY_QUICK, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.NETWORK_CHANGE))
        assertEquals(IpInfoFetchMode.ENTRY_QUICK, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.TOR_ROUTE))
    }

    @Test
    fun `foreground dashboard refresh upgrades incomplete geo data silently`() {
        val snapshot = ConnectionSnapshot(state = ConnectionState.IDLE)
        val ipOnly =
            IpInfo(
                ip = "203.0.113.7",
                ipv4 = "203.0.113.7",
                countryCode = null,
                countryName = null,
                city = null,
                isp = null,
                fetchedAt = 1_000L,
            )

        assertFalse(
            shouldUseFullDashboardIpRefresh(
                reason = IpInfoRefreshReason.FOREGROUND,
                snapshot = snapshot,
                currentIpInfo = ipOnly,
            ),
        )
        assertFalse(
            shouldShowDashboardIpRefreshLoading(
                reason = IpInfoRefreshReason.FOREGROUND,
                snapshot = snapshot,
                currentIpInfo = ipOnly,
            ),
        )
    }

    @Test
    fun `entry quick geo enrichment shows loading until city and provider are complete`() {
        val countryOnly =
            IpInfo(
                ip = "203.0.113.7",
                ipv4 = "203.0.113.7",
                countryCode = "NL",
                countryName = "Netherlands",
                city = null,
                isp = null,
                fetchedAt = 1_000L,
            )
        val fullInfo =
            countryOnly.copy(
                city = "Amsterdam",
                isp = "Example ISP",
            )

        assertTrue(shouldShowIpInfoGeoEnrichmentLoading(countryOnly))
        assertTrue(shouldShowIpInfoGeoEnrichmentLoading(fullInfo.copy(city = null)))
        assertTrue(shouldShowIpInfoGeoEnrichmentLoading(fullInfo.copy(isp = null)))
        assertFalse(shouldShowIpInfoGeoEnrichmentLoading(fullInfo))
    }

    @Test
    fun `post connect refresh stays quick even with stale previous route ip`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 7L,
                lastChangeAt = 2_000L,
            )
        val previousRouteIp =
            IpInfo(
                ip = "203.0.113.7",
                ipv4 = "203.0.113.7",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Example ISP",
                fetchedAt = 1_000L,
            )
        val freshRouteIp = previousRouteIp.copy(fetchedAt = 2_000L)

        assertFalse(
            shouldUseFullDashboardIpRefresh(
                reason = IpInfoRefreshReason.POST_CONNECT,
                snapshot = snapshot,
                currentIpInfo = previousRouteIp,
            ),
        )
        assertFalse(
            shouldShowDashboardIpRefreshLoading(
                reason = IpInfoRefreshReason.POST_CONNECT,
                snapshot = snapshot,
                currentIpInfo = previousRouteIp,
            ),
        )
        assertFalse(
            shouldUseFullDashboardIpRefresh(
                reason = IpInfoRefreshReason.POST_CONNECT,
                snapshot = snapshot,
                currentIpInfo = freshRouteIp,
            ),
        )
        assertFalse(
            shouldShowDashboardIpRefreshLoading(
                reason = IpInfoRefreshReason.POST_CONNECT,
                snapshot = snapshot,
                currentIpInfo = freshRouteIp,
            ),
        )
    }

    @Test
    fun `post connect refresh shows loading for missing ip without full scan`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 7L,
            )

        assertFalse(
            shouldUseFullDashboardIpRefresh(
                reason = IpInfoRefreshReason.POST_CONNECT,
                snapshot = snapshot,
                currentIpInfo = null,
            ),
        )
        assertTrue(
            shouldShowDashboardIpRefreshLoading(
                reason = IpInfoRefreshReason.POST_CONNECT,
                snapshot = snapshot,
                currentIpInfo = null,
            ),
        )
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

    @Test
    fun `standalone tor reload uses tor route refresh policy`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
            )
        val reason =
            runtimeReloadIpRefreshReason(
                snapshot = snapshot,
                settings = Settings(),
            )

        assertEquals(IpInfoRefreshReason.TOR_ROUTE, reason)
        assertEquals(HomeViewModel.TOR_IP_REFRESH_ATTEMPTS, ipInfoRefreshAttemptsForReason(reason))
        assertEquals(HomeViewModel.TOR_IP_REFRESH_RETRY_DELAY_MS, ipInfoRefreshRetryDelayMsForReason(reason))
    }

    @Test
    fun `runtime reload refresh policy keeps tor over vpn strict and ordinary vpn soft`() {
        val vpnSnapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 7L,
            )
        val torOverVpnSettings =
            Settings(
                privacyRoute = PrivacyRouteSettings(
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                ),
            )

        assertEquals(
            IpInfoRefreshReason.TOR_ROUTE,
            runtimeReloadIpRefreshReason(
                snapshot = vpnSnapshot,
                settings = torOverVpnSettings,
            ),
        )
        assertEquals(
            IpInfoRefreshReason.POST_UPDATE,
            runtimeReloadIpRefreshReason(vpnSnapshot, Settings()),
        )
    }

    @Test
    fun `tor target and tor route refresh publish tor ip info`() {
        assertTrue(
            shouldPublishTorIpInfoForDashboardRefresh(
                target = IpInfoRefreshTarget.TOR,
                reason = IpInfoRefreshReason.POST_CONNECT,
            ),
        )
        assertTrue(
            shouldPublishTorIpInfoForDashboardRefresh(
                target = IpInfoRefreshTarget.VPN_BOUND,
                reason = IpInfoRefreshReason.TOR_ROUTE,
            ),
        )
        assertFalse(
            shouldPublishTorIpInfoForDashboardRefresh(
                target = IpInfoRefreshTarget.VPN_BOUND,
                reason = IpInfoRefreshReason.POST_CONNECT,
            ),
        )
        assertFalse(
            shouldPublishTorIpInfoForDashboardRefresh(
                target = IpInfoRefreshTarget.UPSTREAM,
                reason = IpInfoRefreshReason.MANUAL,
            ),
        )
    }

    @Test
    fun `tor route refresh rejects same ip during identity change`() {
        val operation =
            HomeTorOperationUiState(
                kind = HomeTorOperationKind.CHANGING_LOCATION,
                startedAt = 2_000L,
                startedIpAddress = "1.1.1.1",
            )
        val sameIpAfterReload =
            IpInfo(
                ip = "1.1.1.1",
                ipv4 = "1.1.1.1",
                countryCode = "US",
                countryName = "United States",
                city = "Los Angeles",
                isp = "Example TOR exit",
                fetchedAt = 2_500L,
            )

        assertFalse(operation.canAcceptTorIp(sameIpAfterReload))
        assertTrue(
            operation.canAcceptTorIp(
                sameIpAfterReload.copy(ip = "9.9.9.9", ipv4 = "9.9.9.9"),
            ),
        )
    }

    @Test
    fun `tor operation start address only uses previous tor ip`() {
        val previousTorIp =
            IpInfo(
                ip = "185.220.101.12",
                ipv4 = "185.220.101.12",
                countryCode = "DE",
                countryName = "Germany",
                city = "Berlin",
                isp = "TOR exit",
                fetchedAt = 1_000L,
            )

        assertEquals("185.220.101.12", torOperationStartedIpAddress(previousTorIp))
        assertEquals(null, torOperationStartedIpAddress(previousTorIpInfo = null))
    }

    @Test
    fun `tor operation completion only uses current tor ip`() {
        val currentTorIp =
            IpInfo(
                ip = "185.220.101.12",
                ipv4 = "185.220.101.12",
                countryCode = "DE",
                countryName = "Germany",
                city = "Berlin",
                isp = "TOR exit",
                fetchedAt = 1_000L,
            )

        assertEquals(currentTorIp, torOperationCompletionIpInfo(currentTorIp))
        assertEquals(
            null,
            torOperationCompletionIpInfo(currentTorIpInfo = null),
        )
    }
}
