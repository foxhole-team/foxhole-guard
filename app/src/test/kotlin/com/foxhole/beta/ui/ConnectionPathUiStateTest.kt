package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.PrivacyRouteSettings
import com.foxhole.beta.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPathUiStateTest {
    @Test
    fun `route without tor renders device vpn internet only`() {
        assertEquals(
            listOf(RouteNode.DEVICE, RouteNode.VPN, RouteNode.INTERNET),
            connectionRouteNodes(torEnabled = false),
        )
    }

    @Test
    fun `route with tor renders device vpn tor internet`() {
        assertEquals(
            listOf(RouteNode.DEVICE, RouteNode.VPN, RouteNode.TOR, RouteNode.INTERNET),
            connectionRouteNodes(torEnabled = true),
        )
    }

    @Test
    fun `vpn connecting activates device to vpn pulse`() {
        val state =
            connectionPathUiState(
                HomeRouteUiState(
                    connection = testConnection(ConnectionState.CONNECTING),
                ),
            )

        assertEquals(RouteNodeState.CONNECTING, state.device)
        assertEquals(RouteNodeState.CONNECTING, state.vpn)
        assertEquals(RouteSegment.DEVICE_TO_VPN, state.activeSegment)
    }

    @Test
    fun `vpn connected and internet checking activates vpn to internet pulse`() {
        val state =
            connectionPathUiState(
                HomeRouteUiState(
                    connection = testConnection(ConnectionState.CONNECTED),
                    ipInfo = null,
                ),
            )

        assertEquals(RouteNodeState.OK, state.device)
        assertEquals(RouteNodeState.OK, state.vpn)
        assertEquals(RouteNodeState.CHECKING, state.internet)
        assertEquals(RouteSegment.VPN_TO_INTERNET, state.activeSegment)
    }

    @Test
    fun `tor selected apps activates vpn to tor until selected path is verified`() {
        val state =
            connectionPathUiState(
                HomeRouteUiState(
                    settings = Settings(
                        privacyRoute = PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.SELECTED_APPS,
                            selectedPackages = listOf("org.mozilla.firefox"),
                        ),
                    ),
                    connection = testConnection(ConnectionState.CONNECTED),
                ),
            )

        assertTrue(state.torEnabled)
        assertEquals(RouteNodeState.CHECKING, state.tor)
        assertEquals(RouteSegment.VPN_TO_TOR, state.activeSegment)
    }

    @Test
    fun `tor all apps becomes ready only after validated internet evidence`() {
        val state =
            connectionPathUiState(
                HomeRouteUiState(
                    settings = Settings(
                        privacyRoute = PrivacyRouteSettings(
                            mode = PrivacyRouteMode.TOR_OVER_VPN,
                            scope = PrivacyRouteScope.ALL_APPS,
                        ),
                    ),
                    connection = testConnection(ConnectionState.CONNECTED),
                    ipInfo = validatedIpInfo(),
                ),
            )

        assertEquals(RouteNodeState.OK, state.tor)
        assertEquals(RouteNodeState.OK, state.internet)
        assertEquals(null, state.activeSegment)
    }

    @Test
    fun `tor disabled does not claim tor flag on`() {
        val state = connectionPathUiState(HomeRouteUiState())

        assertFalse(state.torEnabled)
        assertEquals(RouteNodeState.INACTIVE, state.device)
        assertEquals(RouteNodeState.INACTIVE, state.tor)
    }

    @Test
    fun `stale ip info keeps internet checking instead of checked`() {
        val state =
            connectionPathUiState(
                HomeRouteUiState(
                    connection = testConnection(ConnectionState.CONNECTED),
                    ipInfo = validatedIpInfo(fetchedAt = ConnectedAt - 1L),
                ),
            )

        assertEquals(RouteNodeState.CHECKING, state.internet)
        assertEquals(RouteSegment.VPN_TO_INTERNET, state.activeSegment)
    }

    @Test
    fun `reconnecting clears downstream checks and returns to vpn segment`() {
        val state =
            connectionPathUiState(
                HomeRouteUiState(
                    connection = testConnection(ConnectionState.RECONNECTING),
                    ipInfo = validatedIpInfo(),
                ),
            )

        assertEquals(RouteNodeState.CONNECTING, state.device)
        assertEquals(RouteNodeState.CONNECTING, state.vpn)
        assertEquals(RouteNodeState.INACTIVE, state.internet)
        assertEquals(RouteSegment.DEVICE_TO_VPN, state.activeSegment)
    }

    @Test
    fun `error does not keep any green checks`() {
        val state =
            connectionPathUiState(
                HomeRouteUiState(
                    connection = testConnection(ConnectionState.ERROR),
                    ipInfo = validatedIpInfo(),
                ),
            )

        assertEquals(RouteNodeState.INACTIVE, state.device)
        assertEquals(RouteNodeState.ERROR, state.vpn)
        assertEquals(RouteNodeState.INACTIVE, state.internet)
        assertEquals(null, state.activeSegment)
    }

    private fun testConnection(state: ConnectionState): ConnectionSnapshot =
        ConnectionSnapshot(
            state = state,
            lastChangeAt = ConnectedAt,
        )

    private fun validatedIpInfo(): IpInfo =
        validatedIpInfo(fetchedAt = ConnectedAt)

    private fun validatedIpInfo(fetchedAt: Long): IpInfo =
        IpInfo(
            ip = "203.0.113.10",
            countryCode = "US",
            countryName = "United States",
            city = "Test",
            isp = "Example",
            fetchedAt = fetchedAt,
        )

    private companion object {
        const val ConnectedAt = 1_000L
    }
}
