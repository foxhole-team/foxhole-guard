package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.R
import com.foxhole.guard.runtime.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class HomeIpPendingLoadingPolicyTest {
    @Test
    fun `does not show pending network loading after app loaded while internet exists and ip is empty`() {
        assertFalse(
            shouldShowPendingNetworkLoading(
                visibleIpInfo = null,
                explicitLoading = false,
                autoConnectRunning = false,
                deviceInternetAvailable = true,
            ),
        )
    }

    @Test
    fun `traffic map origin keeps real device ip while tunnel is active`() {
        val deviceIp =
            IpInfo(
                ip = "198.51.100.10",
                ipv4 = "198.51.100.10",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Device ISP",
                fetchedAt = 4_000L,
            )
        val tunnelIp =
            IpInfo(
                ip = "203.0.113.20",
                ipv4 = "203.0.113.20",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Tunnel ISP",
                fetchedAt = 5_000L,
            )

        val origin =
            trafficMapOriginIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                    lastChangeAt = 5_000L,
                ),
                deviceIpInfo = deviceIp,
                ipInfo = tunnelIp,
            )

        assertEquals(deviceIp, origin)
    }

    @Test
    fun `traffic map origin hides device marker when only vpn ip is known`() {
        val tunnelIp =
            IpInfo(
                ip = "203.0.113.20",
                ipv4 = "203.0.113.20",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Tunnel ISP",
                fetchedAt = 5_000L,
            )

        val origin =
            trafficMapOriginIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                    lastChangeAt = 5_000L,
                ),
                deviceIpInfo = null,
                ipInfo = tunnelIp,
            )

        assertNull(origin)
    }

    @Test
    fun `traffic map origin keeps fresh device address before route ip is published`() {
        val deviceIp =
            IpInfo(
                ip = "198.51.100.10",
                ipv4 = "198.51.100.10",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Device ISP",
                fetchedAt = 5_500L,
            )

        val origin =
            trafficMapOriginIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                    lastChangeAt = 5_000L,
                ),
                deviceIpInfo = deviceIp,
                ipInfo = null,
            )

        assertEquals(deviceIp, origin)
    }

    @Test
    fun `traffic map origin rejects freshly saved route ip during active tunnel`() {
        val routeIp =
            IpInfo(
                ip = "203.0.113.20",
                ipv4 = "203.0.113.20",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Tunnel ISP",
                fetchedAt = 5_500L,
            )

        val origin =
            trafficMapOriginIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                    lastChangeAt = 5_000L,
                ),
                deviceIpInfo = routeIp,
                ipInfo = routeIp,
            )

        assertNull(origin)
    }

    @Test
    fun `traffic map origin keeps fresh device address when route ip is stale and different`() {
        val deviceIp =
            IpInfo(
                ip = "198.51.100.10",
                ipv4 = "198.51.100.10",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Device ISP",
                fetchedAt = 5_500L,
            )
        val staleRouteIp =
            IpInfo(
                ip = "203.0.113.20",
                ipv4 = "203.0.113.20",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Old Tunnel ISP",
                fetchedAt = 4_500L,
            )

        val origin =
            trafficMapOriginIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                    lastChangeAt = 5_000L,
                ),
                deviceIpInfo = deviceIp,
                ipInfo = staleRouteIp,
            )

        assertEquals(deviceIp, origin)
    }

    @Test
    fun `traffic map origin retains last real device ip after connected refresh is rejected`() {
        val deviceIp =
            IpInfo(
                ip = "198.51.100.10",
                ipv4 = "198.51.100.10",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Device ISP",
                fetchedAt = 4_000L,
            )
        val tunnelIp =
            IpInfo(
                ip = "203.0.113.20",
                ipv4 = "203.0.113.20",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Tunnel ISP",
                fetchedAt = 5_500L,
            )

        assertTrue(
            shouldRetainTrafficMapOriginIpInfo(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                    lastChangeAt = 5_000L,
                ),
                previousOriginIpInfo = deviceIp,
                candidateOriginIpInfo = null,
                routeIpInfo = tunnelIp,
            ),
        )
    }

    @Test
    fun `traffic map origin does not retain previous ip when it matches route ip`() {
        val routeIp =
            IpInfo(
                ip = "203.0.113.20",
                ipv4 = "203.0.113.20",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Tunnel ISP",
                fetchedAt = 4_000L,
            )

        assertFalse(
            shouldRetainTrafficMapOriginIpInfo(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                    lastChangeAt = 5_000L,
                ),
                previousOriginIpInfo = routeIp,
                candidateOriginIpInfo = null,
                routeIpInfo = routeIp,
            ),
        )
    }

    @Test
    fun `traffic map origin hides marker when device ip has no public address`() {
        val localIp =
            IpInfo(
                ip = "10.0.0.8",
                ipv4 = "10.0.0.8",
                countryCode = null,
                countryName = null,
                city = null,
                isp = null,
                fetchedAt = 4_000L,
            )

        val origin =
            trafficMapOriginIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                    lastChangeAt = 5_000L,
                ),
                deviceIpInfo = localIp,
                ipInfo = null,
            )

        assertNull(origin)
    }

    @Test
    fun `traffic map origin keeps device ip for local guard firewall`() {
        val deviceIp =
            IpInfo(
                ip = "198.51.100.10",
                ipv4 = "198.51.100.10",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Device ISP",
                fetchedAt = 4_000L,
            )

        val origin =
            trafficMapOriginIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                    lastChangeAt = 5_000L,
                ),
                deviceIpInfo = deviceIp,
                ipInfo = null,
            )

        assertEquals(deviceIp, origin)
    }

    @Test
    fun `traffic map route point uses connected tunnel ip only`() {
        val tunnelIp =
            IpInfo(
                ip = "203.0.113.20",
                ipv4 = "203.0.113.20",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Tunnel ISP",
                fetchedAt = 5_000L,
            )

        assertEquals(
            tunnelIp,
            trafficMapRouteIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                ),
                ipInfo = tunnelIp,
            ),
        )
        assertEquals(
            tunnelIp,
            trafficMapRouteIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.PROXY,
                    profileId = 7L,
                ),
                ipInfo = tunnelIp,
            ),
        )
        assertNull(
            trafficMapRouteIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                ),
                ipInfo = tunnelIp,
            ),
        )
        assertNull(
            trafficMapRouteIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                ),
                ipInfo = tunnelIp,
            ),
        )
        assertNull(
            trafficMapRouteIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = 7L,
                ),
                ipInfo = tunnelIp,
            ),
        )
    }

    @Test
    fun `traffic map tor route requires an engaged tor session`() {
        val torIp =
            IpInfo(
                ip = "203.0.113.5",
                ipv4 = "203.0.113.5",
                countryCode = "DE",
                countryName = "Germany",
                city = "Berlin",
                isp = "TOR exit",
                fetchedAt = 5_000L,
            )
        // A permitted-but-idle route (settings switch on, no runtime) must NOT resurrect a cached
        // Tor exit into a map lane — this was the stale "Route: TOR" after Stop / with Tor off.
        assertNull(
            trafficMapTorIpInfoCandidate(
                connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                torIpInfo = torIp,
            ),
        )
        // A live session actually carrying the Tor route draws the lane.
        assertEquals(
            torIp,
            trafficMapTorIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = 7L,
                    torActive = true,
                ),
                torIpInfo = torIp,
            ),
        )
        // The dedicated Tor-only runtime draws it too.
        assertEquals(
            torIp,
            trafficMapTorIpInfoCandidate(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                ),
                torIpInfo = torIp,
            ),
        )
    }

    @Test
    fun `network card skeletons tor route while direct tor is connecting without route ip`() {
        val deviceIp =
            IpInfo(
                ip = "198.51.100.20",
                ipv4 = "198.51.100.20",
                countryCode = "US",
                countryName = "United States",
                city = "New York",
                isp = "Device ISP",
                fetchedAt = 1_000L,
            )
        val model =
            resolveHomeDashboardNetworkModel(
                state =
                HomeRouteUiState(
                    profilesLoaded = true,
                    ipInfo = deviceIp,
                    settings =
                    Settings(
                        privacyRoute =
                        PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.ALL_APPS,
                            bypassVpnTunnel = true,
                        ),
                    ),
                    torOperation =
                    HomeTorOperationUiState(
                        kind = HomeTorOperationKind.CONNECTING,
                        startedAt = 2_000L,
                    ),
                    connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                ),
                visibleIpInfo = deviceIp,
                deviceInternetAvailable = true,
            )

        assertNull(model.visibleIpInfo)
        assertEquals(R.string.home_network_tor_title, model.titleRes)
        assertTrue(model.showLoading)
        assertTrue(model.showIpInfoLoading)
        assertFalse(model.showConnectionDetailsLoading)
    }

    @Test
    fun `traffic map legend loading is limited to vpn transitions and explicit refresh`() {
        assertFalse(
            shouldShowTrafficMapLegendLoading(
                connectionState = ConnectionState.IDLE,
            ),
        )
        assertTrue(
            shouldShowTrafficMapLegendLoading(
                connectionState = ConnectionState.CONNECTING,
            ),
        )
        assertFalse(
            shouldShowTrafficMapLegendLoading(
                connectionState = ConnectionState.IDLE,
            ),
        )
        assertFalse(
            shouldShowTrafficMapLegendLoading(
                connectionState = ConnectionState.IDLE,
            ),
        )
        assertFalse(
            shouldShowTrafficMapLegendLoading(
                connectionState = ConnectionState.CONNECTED,
            ),
        )
        assertTrue(
            shouldShowTrafficMapLegendLoading(
                connectionState = ConnectionState.IDLE,
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
                autoConnectRunning = false,
                deviceInternetAvailable = true,
            ),
        )
    }

    @Test
    fun `network change refresh stays in the background`() {
        assertFalse(
            shouldShowDashboardIpRefreshLoading(
                reason = IpInfoRefreshReason.NETWORK_CHANGE,
                snapshot = ConnectionSnapshot(state = ConnectionState.CONNECTED, profileId = 7L),
                currentIpInfo = null,
            ),
        )
    }
}
