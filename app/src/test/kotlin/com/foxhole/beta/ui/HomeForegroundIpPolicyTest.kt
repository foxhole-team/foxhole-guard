package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionState
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
}
