package com.foxhole.beta.ui

import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteSettings
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProfileTrafficTotal
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.ProxySurfaceMode
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSettings
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.UiSettings
import com.foxhole.beta.vpn.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardPresentationTest {
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
    fun `protocol model exposes metric pages for every smart option`() {
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
                    protocolLatenciesByOptionId = mapOf("vless" to 110L, "trojan" to 210L),
                    protocolDownOptionIds = setOf("wg"),
                    protocolServerPingsByOptionId = mapOf("trojan" to 76L),
                    protocolServerPingUnavailableOptionIds = setOf("wg"),
                ),
            )

        assertEquals(listOf("vless", "trojan", "wg"), model.metricPages.map { it.optionId })
        assertEquals(0, model.selectedMetricPageIndex)
        assertEquals(110L, model.metricPages[0].latencyPresentation.latencyMs)
        assertEquals(210L, model.metricPages[1].latencyPresentation.latencyMs)
        assertEquals(76L, model.metricPages[1].serverPingMs)
        assertTrue(model.metricPages[2].latencyPresentation.isDown)
        assertTrue(model.metricPages[2].serverPingUnavailable)
    }

    @Test
    fun `protocol metric page defaults to connected option while preserving other protocol metrics`() {
        val model =
            resolveHomeDashboardProtocolModel(
                HomeRouteUiState(
                    activeProfile = smartProfile(),
                    connection =
                        ConnectionSnapshot(
                            state = ConnectionState.CONNECTED,
                            profileId = 1L,
                            protocolHint = ProtocolHint.TROJAN,
                            protocolOptionId = "trojan",
                        ),
                    protocolLatenciesByOptionId = mapOf("vless" to 110L, "trojan" to 210L),
                ),
            )

        assertEquals("trojan", model.presentation.selectedProtocolOptionId)
        assertEquals(1, model.selectedMetricPageIndex)
        assertEquals(listOf(110L, 210L, null), model.metricPages.map { it.latencyPresentation.latencyMs })
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
                state = HomeRouteUiState(connection = ConnectionSnapshot(state = ConnectionState.CONNECTED)),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertEquals(R.string.home_network_connection_info_title, model.titleRes)
        assertTrue(model.showConnectionStatus)
        assertFalse(model.showLoading)
    }

    @Test
    fun `network model shows loading while dashboard connection metrics refresh is active`() {
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
                        connection = ConnectionSnapshot(state = ConnectionState.CONNECTED),
                        dashboardConnectionMetricsLoading = true,
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertEquals(R.string.home_network_connection_info_title, model.titleRes)
        assertTrue(model.showConnectionStatus)
        assertTrue(model.showLoading)
    }

    @Test
    fun `network model shows loading during reconnect even with previous ip info`() {
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
                        connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                        reconnectInProgress = true,
                    ),
                visibleIpInfo = ipInfo,
                deviceInternetAvailable = true,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertEquals(R.string.home_network_connection_info_title, model.titleRes)
        assertTrue(model.showConnectionStatus)
        assertTrue(model.showLoading)
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
    }

    @Test
    fun `network model hides stale tunnel ip while local guard is idle`() {
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

        assertEquals(null, model.visibleIpInfo)
        assertEquals(R.string.home_network_current_ip_title, model.titleRes)
        assertFalse(model.showConnectionStatus)
        assertTrue(model.showLoading)
    }

    @Test
    fun `network model hides stale tunnel ip after ordinary vpn disconnect`() {
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

        assertEquals(null, model.visibleIpInfo)
        assertEquals(R.string.home_network_current_ip_title, model.titleRes)
        assertFalse(model.showConnectionStatus)
        assertTrue(model.showLoading)
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
                privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN),
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
                HomeConnectionFeature.KILL_SWITCH,
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
                HomeConnectionFeatureStatus.ON,
            ),
            indicators.map { it.status },
        )
        assertEquals(
            listOf(
                HomeConnectionFeature.KILL_SWITCH,
                HomeConnectionFeature.FIREWALL,
            ),
            homeConnectionFeatureIndicators(HomeRouteUiState(settings = Settings())).map { it.feature },
        )
        assertEquals(
            listOf(
                HomeConnectionFeatureStatus.OFF,
                HomeConnectionFeatureStatus.OFF,
            ),
            homeConnectionFeatureIndicators(HomeRouteUiState(settings = Settings())).map { it.status },
        )
        assertEquals(
            listOf(
                HomeConnectionFeature.KILL_SWITCH,
                HomeConnectionFeature.FIREWALL,
                HomeConnectionFeature.TOR,
            ),
            homeConnectionFeatureIndicators(
                HomeRouteUiState(settings = Settings(ui = UiSettings(showTorQuickLaunch = true))),
            ).map { it.feature },
        )
        assertEquals(
            HomeConnectionFeatureStatus.PENDING,
            homeConnectionFeatureIndicators(
                HomeRouteUiState(settings = Settings(expert = ExpertSettings(firewallEnabled = true))),
            ).single { it.feature == HomeConnectionFeature.FIREWALL }.status,
        )
    }

    @Test
    fun `tor indicator is pending until a compatible tunnel is connected`() {
        val settings = Settings(privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN))

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
