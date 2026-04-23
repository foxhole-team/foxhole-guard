package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeForegroundIpPolicyTest {
    @Test
    fun `foreground auto refresh runs when app is disconnected`() {
        assertTrue(shouldAutoRefreshIpOnForeground(ConnectionState.IDLE))
        assertTrue(shouldAutoRefreshIpOnForeground(ConnectionState.ERROR))
    }

    @Test
    fun `foreground auto refresh stays off for active connection states`() {
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.CONNECTING))
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.CONNECTED))
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.RECONNECTING))
    }
}
