package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeForegroundIpPolicyTest {
    @Test
    fun `foreground auto refresh does not run when app is disconnected`() {
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.IDLE))
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.ERROR))
    }

    @Test
    fun `foreground auto refresh only runs for stable connected state`() {
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.CONNECTING))
        assertTrue(shouldAutoRefreshIpOnForeground(ConnectionState.CONNECTED))
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.RECONNECTING))
    }
}
