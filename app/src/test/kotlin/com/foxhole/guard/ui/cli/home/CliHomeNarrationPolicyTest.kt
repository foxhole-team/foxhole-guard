package com.foxhole.guard.ui.cli.home

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.guard.ui.HomeRouteUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CliHomeNarrationPolicyTest {
    @Test
    fun `terminal accepts current validation identity published just before connected`() {
        val connectingAt = 1_000L
        val connectedAt = 2_000L
        val oldInfo = ipInfo(fetchedAt = connectingAt - 1L)
        val validationInfo = ipInfo(fetchedAt = connectedAt - 1L)
        val freshInfo = ipInfo(fetchedAt = connectedAt)
        val connected =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                profileId = 7L,
                lastChangeAt = connectedAt,
            )

        assertNull(terminalVpnIdentity(HomeRouteUiState(connection = connected, ipInfo = oldInfo), connectingAt))
        assertEquals(
            validationInfo,
            terminalVpnIdentity(
                HomeRouteUiState(connection = connected, ipInfo = validationInfo),
                connectingAt,
            ),
        )
        assertEquals(
            freshInfo,
            terminalVpnIdentity(
                HomeRouteUiState(
                    connection = connected,
                    ipInfo = freshInfo,
                    ipInfoLoading = true,
                ),
                connectingAt,
            ),
        )
        assertEquals(
            freshInfo,
            terminalVpnIdentity(HomeRouteUiState(connection = connected, ipInfo = freshInfo)),
        )
        assertNull(
            terminalVpnIdentity(
                HomeRouteUiState(
                    connection = connected.copy(state = ConnectionState.IDLE),
                    ipInfo = freshInfo,
                ),
                connectingAt,
            ),
        )
    }

    @Test
    fun `terminal may retain validation identity while connection is still connecting`() {
        val startedAt = 1_000L
        val info = ipInfo(fetchedAt = startedAt + 1L)
        val connecting = ConnectionSnapshot(
            state = ConnectionState.CONNECTING,
            profileId = 7L,
            lastChangeAt = startedAt,
        )

        assertEquals(
            info,
            terminalVpnIdentity(HomeRouteUiState(connection = connecting, ipInfo = info), startedAt),
        )
    }

    @Test
    fun `coalesced connected snapshot accepts only the bounded pre-connected publication window`() {
        val connectedAt = 20_000L
        val boundary = terminalConnectedIdentityNotBefore(connectedAt)
        val connected = ConnectionSnapshot(
            state = ConnectionState.CONNECTED,
            profileId = 7L,
            lastChangeAt = connectedAt,
        )
        val validationInfo = ipInfo(fetchedAt = connectedAt - 1L)
        val retainedInfo = ipInfo(fetchedAt = boundary - 1L)

        assertEquals(
            validationInfo,
            terminalVpnIdentity(
                HomeRouteUiState(connection = connected, ipInfo = validationInfo),
                boundary,
            ),
        )
        assertNull(
            terminalVpnIdentity(
                HomeRouteUiState(connection = connected, ipInfo = retainedInfo),
                boundary,
            ),
        )
    }

    private fun ipInfo(fetchedAt: Long): IpInfo =
        IpInfo(
            ip = "203.0.113.7",
            countryCode = "DE",
            countryName = "Germany",
            city = "Frankfurt",
            isp = "example",
            fetchedAt = fetchedAt,
        )
}
