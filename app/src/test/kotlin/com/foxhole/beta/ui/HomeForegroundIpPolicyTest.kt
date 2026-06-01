package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeForegroundIpPolicyTest {
    @Test
    fun `foreground auto refresh runs when app is idle`() {
        assertTrue(shouldAutoRefreshIpOnForeground(ConnectionState.IDLE))
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.ERROR))
    }

    @Test
    fun `foreground auto refresh skips transitional connection states`() {
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.CONNECTING))
        assertTrue(shouldAutoRefreshIpOnForeground(ConnectionState.CONNECTED))
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.RECONNECTING))
    }

    @Test
    fun `foreground idle refresh shows loading only while first ip is missing`() {
        assertTrue(
            shouldShowForegroundIpRefreshLoading(
                connectionState = ConnectionState.IDLE,
                currentIpInfo = null,
            ),
        )
        assertFalse(
            shouldShowForegroundIpRefreshLoading(
                connectionState = ConnectionState.IDLE,
                currentIpInfo =
                    IpInfo(
                        ip = "203.0.113.7",
                        ipv4 = "203.0.113.7",
                        countryCode = "US",
                        countryName = "United States",
                        city = null,
                        isp = null,
                        fetchedAt = 1L,
                    ),
            ),
        )
        assertFalse(
            shouldShowForegroundIpRefreshLoading(
                connectionState = ConnectionState.CONNECTED,
                currentIpInfo = null,
            ),
        )
    }
}
