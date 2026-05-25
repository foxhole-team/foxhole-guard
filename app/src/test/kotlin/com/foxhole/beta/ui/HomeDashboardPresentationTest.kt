package com.foxhole.beta.ui

import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.PrivacyRouteSettings
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProfileTrafficTotal
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.ProxySurfaceMode
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.StatisticsSettings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSettings
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.UiSettings
import com.foxhole.beta.vpn.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("LargeClass")
class HomeDashboardPresentationTest {
    @Test
    fun `primary action stays stop while reconnecting even if reconnect is required`() {
        assertEquals(
            HomePrimaryAction.STOP,
            homePrimaryAction(ConnectionState.RECONNECTING, reconnectRequired = true),
        )
        assertEquals(
            HomePrimaryAction.STOP,
            homePrimaryAction(ConnectionState.CONNECTING, reconnectRequired = true),
        )
        assertEquals(
            HomePrimaryAction.RECONNECT,
            homePrimaryAction(ConnectionState.CONNECTED, reconnectRequired = true),
        )
    }

    @Test
    fun `top status uses skeleton while startup state is still restoring`() {
        assertTrue(
            shouldShowHomeTopStatusLoading(
                HomeRouteUiState(
                    profilesLoaded = false,
                    connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                ),
            ),
        )
        assertFalse(
            shouldShowHomeTopStatusLoading(
                HomeRouteUiState(
                    profilesLoaded = true,
                    connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                ),
            ),
        )
    }

    @Test
    fun `top status uses skeleton while local guard runtime is being restored`() {
        assertTrue(
            shouldShowHomeTopStatusLoading(
                HomeRouteUiState(
                    profilesLoaded = true,
                    settings = Settings(expert = ExpertSettings(firewallEnabled = true)),
                    connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                ),
            ),
        )
        assertFalse(
            shouldShowHomeTopStatusLoading(
                HomeRouteUiState(
                    profilesLoaded = true,
                    settings = Settings(expert = ExpertSettings(firewallEnabled = true)),
                    connection =
                        ConnectionSnapshot(
                            state = ConnectionState.CONNECTED,
                            profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                        ),
                ),
            ),
        )
    }

    @Test
    fun `connected tunnel refreshes dashboard ip when upstream network revision changes`() {
        assertTrue(
            shouldAutoRefreshIpAfterUpstreamNetworkChange(
                connectionState = ConnectionState.CONNECTED,
                previousRevision = 2L,
                currentRevision = 3L,
            ),
        )
        assertFalse(
            shouldAutoRefreshIpAfterUpstreamNetworkChange(
                connectionState = ConnectionState.CONNECTING,
                previousRevision = 2L,
                currentRevision = 3L,
            ),
        )
        assertFalse(
            shouldAutoRefreshIpAfterUpstreamNetworkChange(
                connectionState = ConnectionState.CONNECTED,
                previousRevision = null,
                currentRevision = 3L,
            ),
        )
    }

    @Test
    fun `connected tunnel consumes pending upstream network revision after reconnect`() {
        assertTrue(
            shouldAutoRefreshIpAfterPendingUpstreamNetworkChange(
                connectionState = ConnectionState.CONNECTED,
                pendingRevision = 4L,
                currentRevision = 4L,
            ),
        )
        assertFalse(
            shouldAutoRefreshIpAfterPendingUpstreamNetworkChange(
                connectionState = ConnectionState.RECONNECTING,
                pendingRevision = 4L,
                currentRevision = 4L,
            ),
        )
        assertFalse(
            shouldAutoRefreshIpAfterPendingUpstreamNetworkChange(
                connectionState = ConnectionState.CONNECTED,
                pendingRevision = null,
                currentRevision = 4L,
            ),
        )
    }

    @Test
    fun `protocol model merges remembered current and running smart start metrics`() {
        val state =
            HomeRouteUiState(
                activeProfile = smartProfile(),
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTED),
                selectedProtocolLatencyMs = 220L,
                smartStartRememberedLatenciesByOptionId = mapOf("vless" to 120L),
                protocolLatenciesByOptionId = mapOf("trojan" to 180L),
                protocolDownOptionIds = setOf("wg"),
                protocolLatencyUnavailableOptionIds = setOf("hysteria"),
                protocolServerPingsByOptionId = mapOf("vless" to 88L),
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "trojan",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "trojan",
                                    displayName = "Trojan",
                                    protocolHint = ProtocolHint.TROJAN,
                                    status = AutoConnectProbeStatus.SUCCESS,
                                    latencyMs = 144L,
                                ),
                                AutoConnectProbeOptionUiState(
                                    optionId = "wg",
                                    displayName = "WireGuard",
                                    protocolHint = ProtocolHint.WIREGUARD,
                                    status = AutoConnectProbeStatus.FAILED,
                                ),
                            ),
                    ),
            )

        val model = resolveHomeDashboardProtocolModel(state)

        assertEquals("trojan", model.presentation.selectedProtocolOptionId)
        assertEquals(144L, model.latencyPresentation.latencyMs)
        assertEquals(mapOf("vless" to 120L, "trojan" to 144L), model.latenciesByOptionId)
        assertEquals(setOf("wg"), model.downOptionIds)
        assertEquals(setOf("hysteria"), model.latencyUnavailableOptionIds)
        assertTrue(model.showSmartStartLatency)
        assertEquals(88L, model.selectedServerPingMs)
        assertTrue(model.connectionDetailsReady)
        assertFalse(model.connectionMetricsLoading)
    }

    @Test
    fun `protocol model hides profile latency while disconnected`() {
        val model =
            resolveHomeDashboardProtocolModel(
                HomeRouteUiState(
                    activeProfile = smartProfile(),
                    connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                    selectedProtocolLatencyMs = 220L,
                    smartStartRememberedLatenciesByOptionId = mapOf("vless" to 120L),
                    protocolLatenciesByOptionId = mapOf("trojan" to 180L),
                    protocolDownOptionIds = setOf("wg"),
                    protocolLatencyUnavailableOptionIds = setOf("hysteria"),
                    protocolServerPingsByOptionId = mapOf("vless" to 88L),
                    protocolMetricsRefreshing = true,
                ),
            )

        assertEquals(null, model.latencyPresentation.latencyMs)
        assertFalse(model.latencyPresentation.isDown)
        assertFalse(model.latencyPresentation.isUnavailable)
        assertEquals(emptyMap<String, Long>(), model.latenciesByOptionId)
        assertEquals(emptySet<String>(), model.downOptionIds)
        assertEquals(emptySet<String>(), model.latencyUnavailableOptionIds)
        assertFalse(model.showSmartStartLatency)
        assertFalse(model.connectionMetricsLoading)
    }

    @Test
    fun `protocol model hides profile latency while local guard firewall is connected`() {
        val model =
            resolveHomeDashboardProtocolModel(
                HomeRouteUiState(
                    activeProfile = smartProfile(),
                    connection =
                        ConnectionSnapshot(
                            state = ConnectionState.CONNECTED,
                            profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                            protocolHint = ProtocolHint.SING_BOX,
                        ),
                    selectedProtocolLatencyMs = 220L,
                    smartStartRememberedLatenciesByOptionId = mapOf("vless" to 120L),
                    protocolDownOptionIds = setOf("vless"),
                    protocolLatencyUnavailableOptionIds = setOf("trojan"),
                    protocolServerPingsByOptionId = mapOf("vless" to 88L),
                    dashboardConnectionMetricsLoading = true,
                ),
            )

        assertEquals(null, model.latencyPresentation.latencyMs)
        assertFalse(model.latencyPresentation.isDown)
        assertFalse(model.latencyPresentation.isUnavailable)
        assertEquals(emptyMap<String, Long>(), model.latenciesByOptionId)
        assertEquals(emptySet<String>(), model.downOptionIds)
        assertEquals(emptySet<String>(), model.latencyUnavailableOptionIds)
        assertFalse(model.showSmartStartLatency)
        assertFalse(model.connectionDetailsReady)
        assertFalse(model.connectionMetricsLoading)
    }

    @Test
    fun `protocol model does not mark active smart start connection down while scan is running`() {
        val state =
            HomeRouteUiState(
                activeProfile = smartProfile(),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 1L,
                        protocolHint = ProtocolHint.VLESS,
                        protocolOptionId = "vless",
                    ),
                protocolDownOptionIds = setOf("vless"),
                protocolLatencyUnavailableOptionIds = setOf("vless"),
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "vless",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "vless",
                                    displayName = "VLESS",
                                    protocolHint = ProtocolHint.VLESS,
                                    status = AutoConnectProbeStatus.FAILED,
                                ),
                            ),
                    ),
            )

        val model = resolveHomeDashboardProtocolModel(state)

        assertFalse("active smart start option should not be down", "vless" in model.downOptionIds)
        assertFalse("active smart start option should not be unavailable", "vless" in model.latencyUnavailableOptionIds)
    }

    @Test
    fun `protocol model suppresses transient down states while smart profile refresh is running`() {
        val state =
            HomeRouteUiState(
                activeProfile = smartProfile(),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 1L,
                        protocolHint = ProtocolHint.VLESS,
                        protocolOptionId = "vless",
                    ),
                protocolMetricsRefreshing = true,
                protocolMetricsRefreshingOptionId = "trojan",
                protocolDownOptionIds = setOf("vless", "trojan", "wg"),
                protocolLatencyUnavailableOptionIds = setOf("trojan"),
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "trojan",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "vless",
                                    displayName = "VLESS",
                                    protocolHint = ProtocolHint.VLESS,
                                    status = AutoConnectProbeStatus.FAILED,
                                ),
                                AutoConnectProbeOptionUiState(
                                    optionId = "trojan",
                                    displayName = "Trojan",
                                    protocolHint = ProtocolHint.TROJAN,
                                    status = AutoConnectProbeStatus.FAILED,
                                ),
                                AutoConnectProbeOptionUiState(
                                    optionId = "wg",
                                    displayName = "WireGuard",
                                    protocolHint = ProtocolHint.WIREGUARD,
                                    status = AutoConnectProbeStatus.FAILED,
                                ),
                            ),
                    ),
            )

        val model = resolveHomeDashboardProtocolModel(state)

        assertEquals(emptySet<String>(), model.downOptionIds)
        assertEquals(emptySet<String>(), model.latencyUnavailableOptionIds)
    }

    @Test
    fun `protocol model shows latency loading during reconnect before metrics refresh starts`() {
        val model =
            resolveHomeDashboardProtocolModel(
                HomeRouteUiState(
                    activeProfile = smartProfile(),
                    connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                    reconnectInProgress = true,
                ),
            )

        assertTrue(model.connectionMetricsLoading)
    }

    @Test
    fun `protocol model does not use server ping as connected latency`() {
        val model =
            resolveHomeDashboardProtocolModel(
                HomeRouteUiState(
                    activeProfile = smartProfile(),
                    connection =
                        ConnectionSnapshot(
                            state = ConnectionState.CONNECTED,
                            profileId = 1L,
                            protocolOptionId = "vless",
                        ),
                    selectedProtocolLatencyUnavailable = true,
                    protocolServerPingsByOptionId = mapOf("vless" to 379L),
                ),
            )

        assertEquals(null, model.latencyPresentation.latencyMs)
        assertTrue(model.latencyPresentation.isUnavailable)
        assertEquals(379L, model.selectedServerPingMs)
        assertTrue(model.connectionDetailsReady)
    }

    @Test
    fun `tor feature stays pending and reports udp when selected vpn protocol is udp`() {
        val state =
            HomeRouteUiState(
                activeProfile = smartProfile().copy(selectedProtocolOptionId = "wg"),
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTED),
                settings =
                    Settings(
                        traffic = TrafficSettings(mode = TrafficMode.TUNNEL),
                        privacyRoute =
                            PrivacyRouteSettings(
                                mode = PrivacyRouteMode.TOR_OVER_VPN,
                                scope = PrivacyRouteScope.ALL_APPS,
                            ),
                    ),
            )

        assertTrue(homeTorSelectedProtocolIsUdp(state))
        assertEquals(HomeConnectionFeatureStatus.PENDING, homeTorFeatureStatus(state))
    }

    @Test
    fun `tor feature is on for connected tunnel with tcp selected protocol`() {
        val state =
            HomeRouteUiState(
                activeProfile = smartProfile().copy(selectedProtocolOptionId = "vless"),
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTED),
                settings =
                    Settings(
                        traffic = TrafficSettings(mode = TrafficMode.TUNNEL),
                        privacyRoute =
                            PrivacyRouteSettings(
                                mode = PrivacyRouteMode.TOR_OVER_VPN,
                                scope = PrivacyRouteScope.ALL_APPS,
                            ),
                    ),
            )

        assertFalse(homeTorSelectedProtocolIsUdp(state))
        assertEquals(HomeConnectionFeatureStatus.ON, homeTorFeatureStatus(state))
    }

    @Test
    fun `tor route can start without vpn profile when route scope is ready`() {
        val state =
            HomeRouteUiState(
                settings =
                    Settings(
                        privacyRoute =
                            PrivacyRouteSettings(
                                mode = PrivacyRouteMode.TOR_OVER_VPN,
                                scope = PrivacyRouteScope.ALL_APPS,
                            ),
                    ),
            )

        assertTrue(homeTorOnlyStartAvailable(state))
        assertFalse(homeTorSelectedProtocolIsUdp(state))
        assertEquals(HomeConnectionFeatureStatus.PENDING, homeTorFeatureStatus(state))
    }

    @Test
    fun `protocol model shows newly selected option before reconnect is applied`() {
        val model =
            resolveHomeDashboardProtocolModel(
                HomeRouteUiState(
                    activeProfile = smartProfile().copy(selectedProtocolOptionId = "trojan"),
                    connection =
                        ConnectionSnapshot(
                            state = ConnectionState.CONNECTED,
                            profileId = 1L,
                            protocolHint = ProtocolHint.VLESS,
                            protocolOptionId = "vless",
                        ),
                    reconnectRequired = true,
                ),
            )

        assertEquals("trojan", model.presentation.selectedProtocolOptionId)
        assertEquals(ProtocolHint.TROJAN, model.presentation.protocolHint)
        assertTrue(model.presentation.protocolOptions.first { option -> option.id == "trojan" }.isSelected)
    }

    @Test
    fun `network model keeps current connected surface when metrics are missing without active refresh`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                lastChangeAt = 500L,
                            ),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertEquals(R.string.home_network_connection_info_title, model.titleRes)
        assertTrue(model.showConnectionStatus)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `network model keeps data steady while dashboard connection metrics refresh is active`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                lastChangeAt = 500L,
                            ),
                        dashboardConnectionMetricsLoading = true,
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertEquals(R.string.home_network_connection_info_title, model.titleRes)
        assertTrue(model.showConnectionStatus)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `network model shows data skeleton during manual network refresh with current ip`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = true,
                        ipInfoLoading = true,
                        connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertTrue(model.showLoading)
        assertTrue(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `connected network model shows connection skeleton during manual network refresh`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = true,
                        ipInfoLoading = true,
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                profileId = 1L,
                                lastChangeAt = 500L,
                            ),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertTrue(model.showLoading)
        assertTrue(model.showIpInfoLoading)
        assertTrue(model.showConnectionDetailsLoading)
    }

    @Test
    fun `connected smart metrics refresh keeps current vpn data without skeleton`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                lastChangeAt = 500L,
                            ),
                        protocolMetricsRefreshing = true,
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertEquals(R.string.home_network_connection_info_title, model.titleRes)
        assertTrue(model.showConnectionStatus)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
        assertTrue(model.showRefreshProgress)
    }

    @Test
    fun `network model keeps vpn ip visible while tor route starts in background`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                profileId = 1L,
                                lastChangeAt = 5_000L,
                            ),
                        settings =
                            Settings(
                                privacyRoute =
                                    PrivacyRouteSettings(
                                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                                        scope = PrivacyRouteScope.ALL_APPS,
                                    ),
                            ),
                        torOperation =
                            HomeTorOperationUiState(
                                kind = HomeTorOperationKind.CONNECTING,
                                startedAt = 5_000L,
                                startedIpAddress = "203.0.113.10",
                            ),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertFalse(model.showRefreshProgress)
    }

    @Test
    fun `connected smart metrics refresh keeps pinned network data even when older than snapshot`() {
        val deviceIpInfo =
            IpInfo(
                ip = "198.51.100.20",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Device network",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = true,
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = 42L,
                                lastChangeAt = 2_000L,
                            ),
                        protocolMetricsRefreshing = true,
                    ),
                visibleIpInfo = deviceIpInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(deviceIpInfo, model.visibleIpInfo)
        assertEquals(R.string.home_network_connection_info_title, model.titleRes)
        assertTrue(model.showConnectionStatus)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
        assertTrue(model.showRefreshProgress)
    }

    @Test
    fun `network model treats connected proxy like routed connection surface`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                trafficMode = TrafficMode.PROXY,
                                profileId = 42L,
                                lastChangeAt = 500L,
                            ),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertEquals(R.string.home_network_connection_info_title, model.titleRes)
        assertTrue(model.showConnectionStatus)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `network model keeps just loaded route ip during connected metrics refresh`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 9_900L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = 42L,
                                lastChangeAt = 10_000L,
                            ),
                        dashboardConnectionMetricsLoading = true,
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertFalse(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `network model keeps just validated route ip after connected snapshot settles`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 9_900L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = 42L,
                                lastChangeAt = 10_000L,
                            ),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `network model does not keep skeleton forever for connected tunnel without resolved ip`() {
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = true,
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = 42L,
                                lastChangeAt = 1_000L,
                            ),
                    ),
                visibleIpInfo = null,
                deviceInternetAvailable = true,
            )

        assertTrue(model.showConnectionStatus)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `network model hides pre-connect device ip after tunnel connects`() {
        val ipInfo =
            IpInfo(
                ip = "198.51.100.20",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Device network",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = true,
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = 42L,
                                lastChangeAt = 2_000L,
                            ),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(null, model.visibleIpInfo)
        assertTrue(model.showConnectionStatus)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
    }

    @Test
    fun `network model hides pre-connect device ip during connected ip refresh`() {
        val ipInfo =
            IpInfo(
                ip = "198.51.100.20",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Device network",
                fetchedAt = 1_950L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = true,
                        ipInfoLoading = true,
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = 42L,
                                lastChangeAt = 2_000L,
                            ),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(null, model.visibleIpInfo)
        assertTrue(model.showIpInfoLoading)
    }

    @Test
    fun `network model hides local device address during reconnect even when freshly fetched`() {
        val localDeviceIp =
            IpInfo(
                ip = "10.13.13.110",
                countryCode = null,
                countryName = "Local network",
                city = null,
                isp = "Wi-Fi",
                fetchedAt = 3_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = true,
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.RECONNECTING,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = 42L,
                                lastChangeAt = 2_000L,
                            ),
                    ),
                visibleIpInfo = localDeviceIp,
                deviceInternetAvailable = true,
            )

        assertEquals(null, model.visibleIpInfo)
        assertTrue(model.showConnectionStatus)
        assertFalse(model.showIpInfoLoading)
    }

    @Test
    fun `network model does not show ip skeleton during reconnect without route ip`() {
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = true,
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.RECONNECTING,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = 42L,
                                lastChangeAt = 2_000L,
                            ),
                    ),
                visibleIpInfo = null,
                deviceInternetAvailable = true,
            )

        assertTrue(model.showConnectionStatus)
        assertFalse(model.showIpInfoLoading)
    }

    @Test
    fun `network model shows connection details skeleton while smart start is preparing route`() {
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = true,
                        activeProfile = smartProfile(),
                        autoConnect =
                            AutoConnectUiState(
                                running = true,
                                currentOptionId = "vless",
                            ),
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.IDLE,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = null,
                                lastChangeAt = 2_000L,
                            ),
                    ),
                visibleIpInfo = null,
                deviceInternetAvailable = true,
            )

        assertTrue(model.showConnectionStatus)
        assertTrue(model.showConnectionDetailsLoading)
        assertTrue(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertEquals(R.string.home_network_connection_info_title, model.titleRes)
    }

    @Test
    fun `network model shows startup skeleton while disconnected profile state loads`() {
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = false,
                        connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                    ),
                visibleIpInfo = null,
                deviceInternetAvailable = true,
        )

        assertFalse(model.showConnectionStatus)
        assertTrue(model.showLoading)
        assertTrue(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `tor ip presentation keeps known ip visible while refresh is loading`() {
        val ipInfo =
            IpInfo(
                ip = "185.220.101.12",
                countryCode = "DE",
                countryName = "Germany",
                city = "Berlin",
                isp = "Tor exit",
                fetchedAt = 1_000L,
            )
        val presentation =
            resolveHomeTorIpPresentation(
                state =
                    HomeRouteUiState(
                        torIpInfo = ipInfo,
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                                lastChangeAt = 2_000L,
                            ),
                    ),
                loading = true,
            )

        assertEquals("185.220.101.12", presentation.ipText)
        assertTrue(presentation.countryText.endsWith("Germany"))
        assertEquals("Berlin", presentation.cityText)
        assertTrue(presentation.hasIp)
        assertFalse(presentation.loading)
    }

    @Test
    fun `network model hides stale ip during route transition without ip skeleton`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTING,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = 42L,
                                lastChangeAt = 2_000L,
                            ),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(null, model.visibleIpInfo)
        assertEquals(R.string.home_network_connection_info_title, model.titleRes)
        assertTrue(model.showConnectionStatus)
        assertTrue(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertTrue(model.showConnectionDetailsLoading)
    }

    @Test
    fun `network model keeps fresh route ip during route transition`() {
        val ipInfo =
            IpInfo(
                ip = "203.0.113.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 2_100L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTING,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = 42L,
                                lastChangeAt = 2_000L,
                            ),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertEquals(R.string.home_network_connection_info_title, model.titleRes)
        assertTrue(model.showConnectionStatus)
        assertTrue(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertTrue(model.showConnectionDetailsLoading)
    }

    @Test
    fun `network model hides stale upstream ip after route error`() {
        val ipInfo =
            IpInfo(
                ip = "198.51.100.20",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Device network",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.ERROR,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = 42L,
                                lastChangeAt = 2_000L,
                            ),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(null, model.visibleIpInfo)
        assertEquals(R.string.home_network_current_ip_title, model.titleRes)
        assertFalse(model.showConnectionStatus)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
    }

    @Test
    fun `network model can show fresh manual ip after route error`() {
        val ipInfo =
            IpInfo(
                ip = "198.51.100.20",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Device network",
                fetchedAt = 2_100L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.ERROR,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = 42L,
                                lastChangeAt = 2_000L,
                            ),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertEquals(R.string.home_network_current_ip_title, model.titleRes)
        assertFalse(model.showConnectionStatus)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
    }

    @Test
    fun `network model treats local guard firewall vpn as ordinary device network`() {
        val ipInfo =
            IpInfo(
                ip = "198.51.100.20",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 2_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = true,
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                                lastChangeAt = 1_000L,
                            ),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertEquals(R.string.home_network_current_ip_title, model.titleRes)
        assertFalse(model.showConnectionStatus)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `local guard firewall runtime keeps dashboard primary action on start`() {
        val state =
            HomeRouteUiState(
                settings = Settings(expert = ExpertSettings(firewallEnabled = true)),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                    ),
            )

        assertEquals(HomePrimaryAction.START, homePrimaryAction(state))
        assertFalse(state.hasPrimaryConnectionRuntime())
    }

    @Test
    fun `tor only runtime keeps network card on current ip layout with tor title`() {
        val ipInfo =
            IpInfo(
                ip = "198.51.100.20",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = true,
                        connection =
                            ConnectionSnapshot(
                                state = ConnectionState.CONNECTED,
                                trafficMode = TrafficMode.TUNNEL,
                                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                                lastChangeAt = 500L,
                            ),
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertEquals(R.string.home_network_tor_title, model.titleRes)
        assertFalse(model.showConnectionStatus)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `tor only runtime keeps dashboard primary action on stop without active profile`() {
        val state =
            HomeRouteUiState(
                activeProfile = null,
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                    ),
            )

        assertEquals(HomePrimaryAction.STOP, homePrimaryAction(state))
        assertTrue(state.hasPrimaryConnectionRuntime())
        assertTrue(state.hasTorOnlyRuntime())
    }

    @Test
    fun `ordinary vpn runtime keeps dashboard primary action on stop`() {
        val state =
            HomeRouteUiState(
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 42L,
                    ),
            )

        assertEquals(HomePrimaryAction.STOP, homePrimaryAction(state))
        assertTrue(state.hasPrimaryConnectionRuntime())
    }

    @Test
    fun `home mode treats split tunnel as configured only after apps are selected`() {
        val splitModeWithoutApps =
            Settings(
                expert =
                    ExpertSettings(
                        perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    ),
            )
        val configuredSplit =
            Settings(
                expert =
                    ExpertSettings(
                        perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                        selectedPackages = listOf("org.example.app"),
                    ),
            )

        assertFalse(splitModeWithoutApps.homeSplitTunnelConfigured())
        assertEquals(HomeModeOption.TUNNEL, currentHomeModeOption(splitModeWithoutApps))
        assertTrue(configuredSplit.homeSplitTunnelConfigured())
        assertEquals(HomeModeOption.SPLIT, currentHomeModeOption(configuredSplit))
    }

    @Test
    fun `network model keeps previous tunnel ip while local guard is idle`() {
        val staleTunnelIp =
            IpInfo(
                ip = "203.0.113.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = true,
                        settings = Settings(expert = ExpertSettings(firewallEnabled = true)),
                        connection = ConnectionSnapshot(state = ConnectionState.IDLE, lastChangeAt = 2_000L),
                    ),
                visibleIpInfo = staleTunnelIp,
                deviceInternetAvailable = true,
            )

        assertEquals(staleTunnelIp, model.visibleIpInfo)
        assertEquals(R.string.home_network_current_ip_title, model.titleRes)
        assertFalse(model.showConnectionStatus)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `network model keeps previous tunnel ip after ordinary vpn disconnect`() {
        val staleTunnelIp =
            IpInfo(
                ip = "203.0.113.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Example",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                    HomeRouteUiState(
                        profilesLoaded = true,
                        connection = ConnectionSnapshot(state = ConnectionState.IDLE, profileId = 1L, lastChangeAt = 2_000L),
                    ),
                visibleIpInfo = staleTunnelIp,
                deviceInternetAvailable = true,
            )

        assertEquals(staleTunnelIp, model.visibleIpInfo)
        assertEquals(R.string.home_network_current_ip_title, model.titleRes)
        assertFalse(model.showConnectionStatus)
        assertFalse(model.showLoading)
        assertFalse(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `traffic map runtime is available for connected tunnel and active local firewall guard only`() {
        assertFalse(
            isTrafficMapRuntimeAvailable(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTED, profileId = 42L),
                settings = Settings(),
                activeVpnNetworkAvailable = true,
            ),
        )
        assertTrue(
            isTrafficMapRuntimeAvailable(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTED, profileId = 42L),
                settings = Settings(ui = UiSettings(trafficMapEnabled = true)),
                activeVpnNetworkAvailable = true,
            ),
        )
        assertTrue(
            isTrafficMapRuntimeAvailable(
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                    ),
                settings =
                    Settings(
                        ui = UiSettings(trafficMapEnabled = true),
                        expert = ExpertSettings(firewallEnabled = true),
                        statistics = StatisticsSettings(enabled = true, countryTrafficEnabled = true),
                    ),
                activeVpnNetworkAvailable = true,
            ),
        )
        val activeLocalFirewallSettings =
            Settings(
                ui = UiSettings(trafficMapEnabled = true),
                expert =
                    ExpertSettings(
                        firewallEnabled = true,
                        blockedPackagesEnabled = true,
                        blockedPackages = listOf("org.mozilla.firefox"),
                        blockAppsAlways = true,
                    ),
                statistics = StatisticsSettings(enabled = true, countryTrafficEnabled = true),
            )
        assertTrue(
            isTrafficMapRuntimeAvailable(
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                    ),
                settings = activeLocalFirewallSettings,
                activeVpnNetworkAvailable = false,
            ),
        )
        assertTrue(
            isTrafficMapRuntimeAvailable(
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                    ),
                settings =
                    Settings(
                        ui = UiSettings(trafficMapEnabled = true),
                        expert = ExpertSettings(firewallEnabled = true),
                        statistics = StatisticsSettings(enabled = true, countryTrafficEnabled = true),
                    ),
                activeVpnNetworkAvailable = false,
            ),
        )
        assertFalse(
            isTrafficMapRuntimeAvailable(
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.IDLE,
                        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                    ),
                settings =
                    Settings(
                        ui = UiSettings(trafficMapEnabled = true),
                        expert = ExpertSettings(firewallEnabled = true),
                    ),
                activeVpnNetworkAvailable = false,
            ),
        )
        assertFalse(
            isTrafficMapRuntimeAvailable(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTED, profileId = 42L),
                settings = Settings(ui = UiSettings(trafficMapEnabled = false)),
                activeVpnNetworkAvailable = true,
            ),
        )
    }

    @Test
    fun `dashboard transport label reports udp tcp and unknown`() {
        assertEquals("UDP", dashboardTransportTypeLabel(ProtocolHint.WIREGUARD))
        assertEquals("UDP", dashboardTransportTypeLabel(ProtocolHint.HYSTERIA2))
        assertEquals("TCP", dashboardTransportTypeLabel(ProtocolHint.VLESS))
        assertEquals("-", dashboardTransportTypeLabel(ProtocolHint.UNKNOWN))
    }

    @Test
    fun `proxy model prefers active proxy surface and marks lan chip only when address exists`() {
        val settings =
            Settings(
                traffic = TrafficSettings(mode = TrafficMode.PROXY),
                expert =
                    ExpertSettings(
                        localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.HTTP,
                                lanProxyMode = ProxySurfaceMode.HTTP,
                                http = ProxyInboundSettings(enabled = true, port = 18080),
                                allowLanAccess = true,
                            ),
                    ),
            )

        val model = resolveHomeDashboardProxyModel(HomeRouteUiState(settings = settings), wifiLanAddress = "192.168.1.10")

        assertEquals(HomeModeOption.PROXY, model.modeOption)
        assertEquals("HTTP", model.proxySurface?.label)
        assertEquals(18080, model.proxySurface?.settings?.port)
        assertEquals(null, model.dashboardProxySurface)
        assertTrue(model.lanProxyActive)
    }

    @Test
    fun `connection feature indicators show enabled dashboard flags and tor status`() {
        val settings =
            Settings(
                ui =
                    UiSettings(
                        showFirewallStatus = true,
                        showTorQuickLaunch = true,
                    ),
                privacyRoute =
                    PrivacyRouteSettings(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        scope = PrivacyRouteScope.ALL_APPS,
                    ),
                expert =
                    ExpertSettings(
                        killSwitchEnabled = true,
                        firewallEnabled = true,
                        blockedPackagesEnabled = true,
                        blockedPackages = listOf("org.mozilla.firefox"),
                        localSurfaces = LocalSurfaceSettings(allowLanAccess = true),
                    ),
            )
        val state =
            HomeRouteUiState(
                activeProfile = smartProfile(),
                settings = settings,
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        protocolHint = ProtocolHint.VLESS,
                    ),
                traffic = TrafficSnapshot(available = true),
            )
        val indicators = homeConnectionFeatureIndicators(state)

        assertEquals(
            listOf(
                HomeConnectionFeature.FIREWALL,
                HomeConnectionFeature.TOR,
                HomeConnectionFeature.LAN_PROXY,
            ),
            indicators.map { it.feature },
        )
        assertEquals(
            listOf(
                HomeConnectionFeatureStatus.ON,
                HomeConnectionFeatureStatus.ON,
                HomeConnectionFeatureStatus.ON,
            ),
            indicators.map { it.status },
        )
        assertEquals(
            listOf(
                HomeConnectionFeature.TOR,
            ),
            homeConnectionFeatureIndicators(HomeRouteUiState(settings = Settings())).map { it.feature },
        )
        assertEquals(
            listOf(
                HomeConnectionFeatureStatus.OFF,
            ),
            homeConnectionFeatureIndicators(HomeRouteUiState(settings = Settings())).map { it.status },
        )
        assertEquals(
            HomeConnectionFeatureStatus.PENDING,
            homeConnectionFeatureIndicators(
                HomeRouteUiState(
                    settings =
                        Settings(
                            ui = UiSettings(showFirewallStatus = true),
                            expert = ExpertSettings(firewallEnabled = true),
                        ),
                ),
            ).single { it.feature == HomeConnectionFeature.FIREWALL }.status,
        )
    }

    @Test
    fun `tor indicator is pending until a compatible tunnel is connected`() {
        val settings =
            Settings(
                ui = UiSettings(showTorQuickLaunch = true),
                privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN),
            )

        assertEquals(
            HomeConnectionFeatureStatus.PENDING,
            homeConnectionFeatureIndicators(
                HomeRouteUiState(
                    activeProfile = smartProfile(),
                    settings = settings,
                    connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                ),
            ).single { it.feature == HomeConnectionFeature.TOR }.status,
        )
        assertEquals(
            HomeConnectionFeatureStatus.PENDING,
            homeConnectionFeatureIndicators(
                HomeRouteUiState(
                    activeProfile = smartProfile(),
                    settings = settings,
                    connection =
                        ConnectionSnapshot(
                            state = ConnectionState.CONNECTED,
                            protocolHint = ProtocolHint.WIREGUARD,
                        ),
                ),
            ).single { it.feature == HomeConnectionFeature.TOR }.status,
        )
    }

    @Test
    fun `traffic model summarizes profile totals and session activity`() {
        val model =
            resolveHomeDashboardTrafficModel(
                state =
                    HomeRouteUiState(
                        settings =
                            Settings(
                                usageTrackingStartedAt = 1_000L,
                                profileTrafficTotals =
                                    listOf(
                                        ProfileTrafficTotal(
                                            profileId = 1L,
                                            profileName = "Edge",
                                            protocolHint = ProtocolHint.VLESS,
                                            rxTotalBytes = 100L,
                                            txTotalBytes = 50L,
                                        ),
                                    ),
                            ),
                        traffic = TrafficSnapshot(rxBytesPerSec = 1L, txBytesPerSec = 0L),
                    ),
                now = 86_401_000L,
            )

        assertEquals(150L, model.totalBytes)
        assertEquals(2L, model.totalDays)
        assertTrue(model.hasIncomingTraffic)
        assertFalse(model.hasOutgoingTraffic)
    }

    @Test
    fun `traffic model exposes only selected smart protocol total`() {
        val model =
            resolveHomeDashboardTrafficModel(
                state =
                    HomeRouteUiState(
                        activeProfile = smartProfile().copy(selectedProtocolOptionId = "trojan"),
                        settings =
                            Settings(
                                profileTrafficTotals =
                                    listOf(
                                        ProfileTrafficTotal(
                                            profileId = 1L,
                                            profileName = "Smart",
                                            protocolHint = ProtocolHint.VLESS,
                                            protocolOptionId = "vless",
                                            rxTotalBytes = 100L,
                                            txTotalBytes = 50L,
                                        ),
                                        ProfileTrafficTotal(
                                            profileId = 1L,
                                            profileName = "Smart",
                                            protocolHint = ProtocolHint.TROJAN,
                                            protocolOptionId = "trojan",
                                            rxTotalBytes = 300L,
                                            txTotalBytes = 40L,
                                        ),
                                    ),
                            ),
                    ),
                now = 1_000L,
            )

        assertEquals(340L, model.selectedProtocolTotalBytes)
        assertEquals(ProtocolHint.TROJAN, model.selectedProtocolHint)
    }

    private fun smartProfile(): Profile =
        Profile(
            id = 1L,
            name = "Smart",
            sourceType = ProfileSourceType.SUBSCRIPTION_URL,
            secretRef = "secret",
            protocolHint = ProtocolHint.VLESS,
            lastUpdatedAt = null,
            lastEtag = null,
            protocolOptions =
                listOf(
                    ProfileProtocolOption(id = "vless", displayName = "VLESS", protocolHint = ProtocolHint.VLESS),
                    ProfileProtocolOption(id = "trojan", displayName = "Trojan", protocolHint = ProtocolHint.TROJAN),
                    ProfileProtocolOption(id = "wg", displayName = "WireGuard", protocolHint = ProtocolHint.WIREGUARD),
                ),
            selectedProtocolOptionId = "vless",
            isActive = true,
        )
}
