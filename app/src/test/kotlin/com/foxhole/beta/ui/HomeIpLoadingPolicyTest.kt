package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
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
    fun `shows pending network loading while tunnel is connecting and upstream internet is available`() {
        assertTrue(
            shouldShowPendingNetworkLoading(
                visibleIpInfo = null,
                explicitLoading = false,
                connectionState = ConnectionState.CONNECTING,
                autoConnectRunning = false,
                deviceInternetAvailable = true,
            ),
        )
    }

    @Test
    fun `shows pending network loading while smart start is running and upstream internet is available`() {
        assertTrue(
            shouldShowPendingNetworkLoading(
                visibleIpInfo = null,
                explicitLoading = false,
                connectionState = ConnectionState.IDLE,
                autoConnectRunning = true,
                deviceInternetAvailable = true,
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
            ),
        )
    }
}
