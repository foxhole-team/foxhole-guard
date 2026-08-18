package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.TorProbeProxyFailure
import com.foxhole.core.runtime.TorProbeProxyUnavailableException
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.guard.runtime.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeIpRefreshPolicyTest {
    @Test
    fun `only hard tor probe startup failures become actionable terminal errors`() {
        assertTrue(
            shouldReportTorProbeStartupFailure(
                IpInfoRefreshReason.TOR_ROUTE,
                TorProbeProxyUnavailableException(TorProbeProxyFailure.START_REFUSED),
            ),
        )
        assertTrue(
            shouldReportTorProbeStartupFailure(
                IpInfoRefreshReason.TOR_ROUTE,
                TorProbeProxyUnavailableException(TorProbeProxyFailure.INVALID_LOOPBACK_ADDRESS),
            ),
        )
        assertFalse(
            shouldReportTorProbeStartupFailure(
                IpInfoRefreshReason.TOR_ROUTE,
                TorProbeProxyUnavailableException(TorProbeProxyFailure.NOT_READY),
            ),
        )
        assertFalse(
            shouldReportTorProbeStartupFailure(
                IpInfoRefreshReason.MANUAL,
                TorProbeProxyUnavailableException(TorProbeProxyFailure.START_REFUSED),
            ),
        )
    }

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
    fun `network handover publishes physical identity only for the same active revision`() {
        val current =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                upstreamNetworkRevision = 9L,
            )

        assertTrue(shouldPublishNetworkChangeDeviceIdentity(expectedRevision = 9L, current = current))
        assertFalse(shouldPublishNetworkChangeDeviceIdentity(expectedRevision = 8L, current = current))
        assertFalse(
            shouldPublishNetworkChangeDeviceIdentity(
                expectedRevision = 9L,
                current = current.copy(state = ConnectionState.IDLE),
            ),
        )
    }

    @Test
    fun `every connected route refreshes the physical identity used by device and direct map flags`() {
        assertTrue(
            shouldRefreshDeviceIdentityForConnectedRoute(
                reason = IpInfoRefreshReason.POST_CONNECT,
            ),
        )
        assertTrue(
            shouldRefreshDeviceIdentityForConnectedRoute(
                reason = IpInfoRefreshReason.RESTORED_VPN,
            ),
        )
        assertTrue(
            shouldRefreshDeviceIdentityForConnectedRoute(
                reason = IpInfoRefreshReason.POST_CONNECT,
            ),
        )
        assertTrue(
            shouldRefreshDeviceIdentityForConnectedRoute(
                reason = IpInfoRefreshReason.NETWORK_CHANGE,
            ),
        )
    }

    @Test
    fun `default network callback refreshes both connected and idle device identity paths`() {
        assertTrue(shouldUseConnectedIpRefreshAfterDefaultNetworkChange(ConnectionState.CONNECTED))
        assertFalse(shouldUseConnectedIpRefreshAfterDefaultNetworkChange(ConnectionState.IDLE))
        assertFalse(shouldUseConnectedIpRefreshAfterDefaultNetworkChange(ConnectionState.DISCONNECTING))
    }

    @Test
    fun `underlying handover clears upstream identity but preserves stable vpn exit and smart pin`() {
        assertTrue(
            shouldClearDashboardIdentityOnUnderlyingHandover(
                snapshot = ConnectionSnapshot(state = ConnectionState.IDLE),
                smartAnalysisRunning = false,
            ),
        )
        assertTrue(
            shouldClearDashboardIdentityOnUnderlyingHandover(
                snapshot = ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.PROXY,
                    profileId = 7L,
                ),
                smartAnalysisRunning = false,
            ),
        )
        assertFalse(
            shouldClearDashboardIdentityOnUnderlyingHandover(
                snapshot = ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                ),
                smartAnalysisRunning = false,
            ),
        )
        assertFalse(
            shouldClearDashboardIdentityOnUnderlyingHandover(
                snapshot = ConnectionSnapshot(state = ConnectionState.IDLE),
                smartAnalysisRunning = true,
            ),
        )
        assertFalse(
            shouldClearExistingIpForRefresh(
                reason = IpInfoRefreshReason.NETWORK_CHANGE,
                clearExistingIp = true,
            ),
        )
    }

    @Test
    fun `refresh keeps the shown address until a new one resolves`() {
        for (reason in listOf(
            IpInfoRefreshReason.NETWORK_CHANGE,
            IpInfoRefreshReason.POST_CONNECT,
            IpInfoRefreshReason.RESTORED_VPN,
            IpInfoRefreshReason.TOR_ROUTE,
        )) {
            assertFalse(
                "$reason must not clear the shown address",
                shouldClearExistingIpForRefresh(reason = reason, clearExistingIp = true),
            )
        }
        assertTrue(
            shouldClearExistingIpForRefresh(
                reason = IpInfoRefreshReason.MANUAL,
                clearExistingIp = true,
            ),
        )
        assertFalse(
            shouldClearExistingIpForRefresh(
                reason = IpInfoRefreshReason.MANUAL,
                clearExistingIp = false,
            ),
        )
        val source = uiSourceFile("HomeViewModelIpRefreshSupport.kt").readText()
        val manualRefresh = source
            .substringAfter("internal fun HomeViewModel.refreshIpInfo()")
            .substringBefore("internal fun HomeViewModel.refreshIpInfoSilently()")
        assertTrue(manualRefresh.contains("clearExistingIp = false"))
    }

    @Test
    fun `background geo enrichment failure is not logged as connectivity failure`() {
        assertEquals(
            "geo enrichment unavailable",
            ipInfoRefreshFailureDiagnosticLabel(
                fetchMode = IpInfoFetchMode.GEO_ENRICHMENT,
                reportFailures = false,
            ),
        )
        assertEquals(
            "geo refresh failed",
            ipInfoRefreshFailureDiagnosticLabel(
                fetchMode = IpInfoFetchMode.GEO_ENRICHMENT,
                reportFailures = true,
            ),
        )
        assertEquals(
            "geo refresh failed",
            ipInfoRefreshFailureDiagnosticLabel(
                fetchMode = IpInfoFetchMode.FULL,
                reportFailures = false,
            ),
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
    fun `failed primary refresh still schedules the dedicated Tor exit probe`() {
        assertTrue(
            shouldScheduleTorRouteAfterPrimaryRefresh(
                reason = IpInfoRefreshReason.POST_CONNECT,
                publishedInfo = false,
            ),
        )
        assertFalse(
            shouldScheduleTorRouteAfterPrimaryRefresh(
                reason = IpInfoRefreshReason.POST_CONNECT,
                publishedInfo = true,
            ),
        )
        assertFalse(
            shouldScheduleTorRouteAfterPrimaryRefresh(
                reason = IpInfoRefreshReason.TOR_ROUTE,
                publishedInfo = false,
            ),
        )
    }

    @Test
    fun `connected route owns a bounded latency fallback independent of ip publication`() {
        assertFalse(
            shouldClearExistingIpForRefresh(
                reason = IpInfoRefreshReason.POST_CONNECT,
                clearExistingIp = true,
            ),
        )
        assertTrue(shouldScheduleConnectedDashboardLatencyRefresh(IpInfoRefreshReason.POST_CONNECT))
        assertTrue(shouldScheduleConnectedDashboardLatencyRefresh(IpInfoRefreshReason.RESTORED_VPN))
        assertTrue(shouldScheduleConnectedDashboardLatencyRefresh(IpInfoRefreshReason.NETWORK_CHANGE))
        assertFalse(shouldScheduleConnectedDashboardLatencyRefresh(IpInfoRefreshReason.FOREGROUND))
        assertFalse(shouldScheduleConnectedDashboardLatencyRefresh(IpInfoRefreshReason.TOR_ROUTE))
        assertEquals(5_000L, HomeViewModel.POST_CONNECT_LATENCY_AFTER_IP_DELAY_MS)
        assertTrue(HomeViewModel.CONNECTED_LATENCY_TOTAL_TIMEOUT_MS <= 8_000L)
        val ipSource = uiSourceFile("HomeViewModelIpRefreshSupport.kt").readText()
        val connectedRefresh =
            ipSource
                .substringAfter("internal fun HomeViewModel.scheduleConnectedIpRefresh(")
                .substringBefore("private suspend fun HomeViewModel.refreshDeviceIdentityForConnectedRoute")
        assertTrue(
            connectedRefresh.indexOf("scheduleConnectedDashboardLatencyRefresh(reason)") <
                connectedRefresh.indexOf("startIpInfoRefresh("),
        )
        assertTrue(connectedRefresh.contains("accelerateConnectedDashboardLatencyRefresh(latencyRefreshGeneration)"))
    }

    @Test
    fun `real dashboard probe owns timeout and generation safe cleanup`() {
        val source = uiSourceFile("HomeViewModelProtocolMetricsSupport.kt").readText()
        val refresh =
            source
                .substringAfter("internal fun HomeViewModel.scheduleActiveProfileLatencyRefresh(")
                .substringBefore("internal fun shouldContinueDashboardLatencyRefreshAfterInitialSample")

        assertTrue(refresh.contains("acquireDashboardConnectionMetricsLoading(refreshGeneration)"))
        assertTrue(refresh.contains("isCurrentDashboardLatencyRefresh(refreshGeneration)"))
        assertTrue(refresh.contains("profileLatencyRefreshJob === coroutineContext[Job]"))
        assertTrue(source.contains("withTimeoutOrNull(HomeViewModel.CONNECTED_LATENCY_TOTAL_TIMEOUT_MS)"))
        assertTrue(source.contains("releaseDashboardConnectionMetricsLoading(refreshGeneration)"))
    }

    @Test
    fun `post disconnect physical ip survives transparent firewall handoff`() {
        assertTrue(
            shouldPublishDashboardIpRefresh(
                startedTarget = IpInfoRefreshTarget.UPSTREAM,
                currentTarget = IpInfoRefreshTarget.LOCAL_GUARD,
                reason = IpInfoRefreshReason.FOREGROUND,
            ),
        )
        assertTrue(
            shouldPublishDashboardIpRefresh(
                startedTarget = IpInfoRefreshTarget.LOCAL_GUARD,
                currentTarget = IpInfoRefreshTarget.UPSTREAM,
                reason = IpInfoRefreshReason.FOREGROUND,
            ),
        )
        assertFalse(
            shouldPublishDashboardIpRefresh(
                startedTarget = IpInfoRefreshTarget.UPSTREAM,
                currentTarget = IpInfoRefreshTarget.VPN_BOUND,
                reason = IpInfoRefreshReason.FOREGROUND,
            ),
        )
    }

    @Test
    fun `dashboard refreshes keep full fetch limited to manual refresh`() {
        assertEquals(IpInfoFetchMode.FULL, ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.MANUAL))
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
        assertTrue(shouldShowIpInfoGeoEnrichmentLoading(fullInfo.copy(countryName = null)))
        assertTrue(shouldShowIpInfoGeoEnrichmentLoading(fullInfo.copy(isp = null)))
        assertFalse(shouldShowIpInfoGeoEnrichmentLoading(fullInfo))
    }

    @Test
    fun `entry quick geo enrichment stays visually silent once ip is visible`() {
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
        val noVisibleIp =
            ipOnly.copy(
                ip = "",
                ipv4 = null,
                ipv6 = null,
            )

        assertTrue(shouldShowIpInfoGeoEnrichmentLoading(ipOnly))
        assertFalse(shouldShowIpInfoGeoEnrichmentRefreshLoading(ipOnly))
        assertTrue(shouldShowIpInfoGeoEnrichmentRefreshLoading(noVisibleIp))
    }

    @Test
    fun `post connect refresh hides a stale route ip but keeps a fresh validated identity`() {
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

        assertTrue(
            shouldShowDashboardIpRefreshLoading(
                reason = IpInfoRefreshReason.POST_CONNECT,
                snapshot = snapshot,
                currentIpInfo = previousRouteIp,
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

        assertTrue(
            shouldShowDashboardIpRefreshLoading(
                reason = IpInfoRefreshReason.POST_CONNECT,
                snapshot = snapshot,
                currentIpInfo = null,
            ),
        )
    }

    @Test
    fun `connecting tunnel profile uses vpn-bound ip target`() {
        assertEquals(
            IpInfoRefreshTarget.VPN_BOUND,
            ipInfoRefreshTargetForSnapshot(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                ),
            ),
        )
        assertEquals(
            IpInfoRefreshTarget.VPN_BOUND,
            ipInfoRefreshTargetForSnapshot(
                ConnectionSnapshot(
                    state = ConnectionState.RECONNECTING,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                ),
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
    fun `connecting an ordinary vpn with tor enabled schedules a tor exit refresh`() {
        val vpnSnapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 7L,
            )
        val torSettings = Settings(privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN))

        assertTrue(
            shouldRefreshTorExitAfterConnect(IpInfoRefreshReason.POST_CONNECT, vpnSnapshot, torSettings),
        )
        assertFalse(
            shouldRefreshTorExitAfterConnect(IpInfoRefreshReason.POST_CONNECT, vpnSnapshot, Settings()),
        )
        assertFalse(
            shouldRefreshTorExitAfterConnect(
                IpInfoRefreshReason.POST_CONNECT,
                vpnSnapshot.copy(profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID),
                torSettings,
            ),
        )
        assertFalse(
            shouldRefreshTorExitAfterConnect(IpInfoRefreshReason.TOR_ROUTE, vpnSnapshot, torSettings),
        )
    }

    @Test
    fun `tor alongside vpn via bypass still refreshes the tor exit ip`() {
        val vpnSnapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 7L,
            )
        val torBypassSettings =
            Settings(
                privacyRoute = PrivacyRouteSettings(
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    bypassVpnTunnel = true,
                ),
            )

        assertEquals(
            IpInfoRefreshReason.TOR_ROUTE,
            runtimeReloadIpRefreshReason(
                snapshot = vpnSnapshot,
                settings = torBypassSettings,
            ),
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
    fun `dashboard refresh stores device ip only from upstream or local guard targets`() {
        assertTrue(shouldPublishDeviceIpInfoFromDashboardRefresh(IpInfoRefreshTarget.UPSTREAM))
        assertTrue(shouldPublishDeviceIpInfoFromDashboardRefresh(IpInfoRefreshTarget.LOCAL_GUARD))
        assertFalse(shouldPublishDeviceIpInfoFromDashboardRefresh(IpInfoRefreshTarget.VPN_BOUND))
        assertFalse(shouldPublishDeviceIpInfoFromDashboardRefresh(IpInfoRefreshTarget.TOR))
        assertFalse(shouldPublishDeviceIpInfoFromDashboardRefresh(IpInfoRefreshTarget.PROXY))
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
    fun `tor route refresh rejects stale non tor dashboard ip`() {
        val currentVpnIp =
            IpInfo(
                ip = "198.51.100.44",
                ipv4 = "198.51.100.44",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "VPN ISP",
                fetchedAt = 1_000L,
            )
        val staleTorCandidate =
            currentVpnIp.copy(
                isp = "VPN ISP through app-owned refresh",
                fetchedAt = 2_000L,
            )
        val actualTorExit =
            IpInfo(
                ip = "203.0.113.5",
                ipv4 = "203.0.113.5",
                countryCode = "DE",
                countryName = "Germany",
                city = "Berlin",
                isp = "TOR exit",
                fetchedAt = 2_000L,
            )

        assertFalse(
            shouldAcceptTorRouteIpRefresh(
                info = staleTorCandidate,
                currentNonTorIpInfo = currentVpnIp,
                torRouteVisible = true,
                torOperation = HomeTorOperationUiState(),
            ),
        )
        assertTrue(
            shouldAcceptTorRouteIpRefresh(
                info = actualTorExit,
                currentNonTorIpInfo = currentVpnIp,
                torRouteVisible = true,
                torOperation = HomeTorOperationUiState(),
            ),
        )
    }

    @Test
    fun `tor operation start address only uses previous tor ip`() {
        val previousTorIp =
            IpInfo(
                ip = "203.0.113.5",
                ipv4 = "203.0.113.5",
                countryCode = "DE",
                countryName = "Germany",
                city = "Berlin",
                isp = "TOR exit",
                fetchedAt = 1_000L,
            )

        assertEquals("203.0.113.5", torOperationStartedIpAddress(previousTorIp))
        assertEquals(null, torOperationStartedIpAddress(previousTorIpInfo = null))
    }

    @Test
    fun `tor operation completion only uses current tor ip`() {
        val currentTorIp =
            IpInfo(
                ip = "203.0.113.5",
                ipv4 = "203.0.113.5",
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

    @Test
    fun `tor operation completion ignores dashboard vpn ip while waiting for tor ip`() {
        val currentTorIp =
            IpInfo(
                ip = "203.0.113.5",
                ipv4 = "203.0.113.5",
                countryCode = "DE",
                countryName = "Germany",
                city = "Berlin",
                isp = "TOR exit",
                fetchedAt = 1_000L,
            )
        val activeOperation =
            HomeTorOperationUiState(
                kind = HomeTorOperationKind.CONNECTING,
                startedAt = 2_000L,
            )

        assertEquals(
            currentTorIp,
            torOperationCompletionIpInfo(
                snapshot = ConnectionSnapshot(state = ConnectionState.CONNECTED),
                torOperation = activeOperation,
                currentTorIpInfo = currentTorIp,
            ),
        )
        assertEquals(
            null,
            torOperationCompletionIpInfo(
                snapshot = ConnectionSnapshot(state = ConnectionState.CONNECTING),
                torOperation = activeOperation,
                currentTorIpInfo = currentTorIp,
            ),
        )
        assertEquals(
            null,
            torOperationCompletionIpInfo(
                snapshot = ConnectionSnapshot(state = ConnectionState.CONNECTED),
                torOperation = HomeTorOperationUiState(),
                currentTorIpInfo = currentTorIp,
            ),
        )
        assertEquals(
            null,
            torOperationCompletionIpInfo(
                snapshot = ConnectionSnapshot(state = ConnectionState.CONNECTED),
                torOperation = activeOperation,
                currentTorIpInfo = null,
            ),
        )
    }

    @Test
    fun `validated tor connection clears bootstrap operation even when tor ip is unavailable`() {
        val activeOperation =
            HomeTorOperationUiState(
                kind = HomeTorOperationKind.BOOTSTRAPPING,
                startedAt = 2_000L,
            )

        assertTrue(
            shouldClearTorOperationAfterValidatedConnect(
                snapshot = ConnectionSnapshot(state = ConnectionState.CONNECTED),
                torOperation = activeOperation,
                nowMs = 2_000L + HomeViewModel.TOR_OPERATION_MIN_VISIBLE_MS,
            ),
        )
        assertFalse(
            shouldClearTorOperationAfterValidatedConnect(
                snapshot = ConnectionSnapshot(state = ConnectionState.CONNECTING),
                torOperation = activeOperation,
                nowMs = 2_000L + HomeViewModel.TOR_OPERATION_MIN_VISIBLE_MS,
            ),
        )
        assertFalse(
            shouldClearTorOperationAfterValidatedConnect(
                snapshot = ConnectionSnapshot(state = ConnectionState.CONNECTED),
                torOperation = HomeTorOperationUiState(
                    kind = HomeTorOperationKind.CHANGING_LOCATION,
                    startedAt = 2_000L,
                ),
                nowMs = 2_000L + HomeViewModel.TOR_OPERATION_MIN_VISIBLE_MS,
            ),
        )
    }

    @Test
    fun `tor route dashboard refresh uses dedicated tor route ip path`() {
        val source = uiSourceFile("HomeViewModelIpRefreshSupport.kt").readText()
        val refreshBlock =
            source.substringAfter("private suspend fun HomeViewModel.refreshIpInfoForReason")
                .substringBefore("internal fun ipInfoRefreshAttemptsForReason")

        assertTrue(refreshBlock.contains("reason == IpInfoRefreshReason.TOR_ROUTE"))
        assertTrue(refreshBlock.contains("refreshTorRouteIpInfo(fetchMode = fetchMode)"))
        assertTrue(refreshBlock.contains("refreshIpInfo(fetchMode = fetchMode)"))
    }

    @Test
    fun `tor exit is published through a single guarded owner`() {
        val offendingWrites =
            uiSourceFiles()
                .flatMap { file -> file.readLines().map { line -> file.name to line.trim() } }
                .filter { (_, line) -> line.startsWith("torIpInfoMutable.value =") }
                .filterNot { (_, line) -> line == "torIpInfoMutable.value = null" }
                .filterNot { (file, _) -> file == "HomeViewModelIpRefreshGuards.kt" }

        assertEquals(emptyList<Pair<String, String>>(), offendingWrites)

        assertTrue(
            "publishTorRouteExit must retain prior geo across same-ip updates",
            uiSourceFile("HomeViewModelIpRefreshGuards.kt").readText().contains(
                "torIpInfoMutable.value = candidate.retainKnownDetailsFrom(torIpInfoMutable.value)",
            ),
        )
    }

    @Test
    fun `every tor exit writer routes through publishTorRouteExit`() {
        assertTrue(
            uiSourceFile("HomeViewModelSupervisorsSupport.kt")
                .readText()
                .contains("publishTorRouteExit(runtimeTorExit)"),
        )
        assertTrue(
            uiSourceFile("HomeViewModelDashboardRefreshSupport.kt").readText()
                .contains("val published = publishTorRouteExit(info)"),
        )
        assertTrue(
            uiSourceFile("HomeViewModelTorOperationSupport.kt").readText()
                .contains("publishTorRouteExit(publishableIpInfo)"),
        )
    }

    private fun uiSourceFile(name: String): File =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/$name"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/$name"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/$name"),
        ).first { file -> file.isFile }

    private fun uiSourceFiles(): List<File> {
        val dir =
            listOf(
                File("src/main/kotlin/com/foxhole/guard/ui"),
                File("app/src/main/kotlin/com/foxhole/guard/ui"),
                File("../app/src/main/kotlin/com/foxhole/guard/ui"),
            ).first { file -> file.isDirectory }
        return dir.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList()
    }
}
