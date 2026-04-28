package com.foxhole.beta.ui

import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProfileTrafficTotal
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSettings
import com.foxhole.beta.core.model.TrafficSnapshot
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
    }

    @Test
    fun `network model keeps connected title and waits for connection detail readiness`() {
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
                connectionDetailsReady = false,
            )

        assertEquals(ipInfo, model.visibleIpInfo)
        assertEquals(R.string.home_network_connection_info_title, model.titleRes)
        assertTrue(model.showConnectionStatus)
        assertTrue(model.showLoading)
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
                                http = ProxyInboundSettings(enabled = true, port = 18080),
                                allowLanAccess = true,
                            ),
                    ),
            )

        val model = resolveHomeDashboardProxyModel(HomeRouteUiState(settings = settings), wifiLanAddress = "192.168.1.10")

        assertEquals(HomeModeOption.PROXY, model.modeOption)
        assertEquals("HTTP", model.dashboardProxySurface?.label)
        assertEquals(18080, model.dashboardProxySurface?.settings?.port)
        assertTrue(model.lanProxyActive)
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
