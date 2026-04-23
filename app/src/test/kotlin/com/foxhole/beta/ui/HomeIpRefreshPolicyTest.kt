package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeIpRefreshPolicyTest {
    @Test
    fun `refreshes after tunnel becomes connected without ip info`() {
        assertTrue(
            shouldAutoRefreshIpAfterConnect(
                previousState = ConnectionState.CONNECTING,
                currentState = ConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun `refreshes after connect even when the previous device ip is still visible`() {
        assertTrue(
            shouldAutoRefreshIpAfterConnect(
                previousState = ConnectionState.IDLE,
                currentState = ConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun `does not refresh before connected state`() {
        assertFalse(
            shouldAutoRefreshIpAfterConnect(
                previousState = ConnectionState.IDLE,
                currentState = ConnectionState.CONNECTING,
            ),
        )
    }

    @Test
    fun `does not requeue refresh while already connected`() {
        assertFalse(
            shouldAutoRefreshIpAfterConnect(
                previousState = ConnectionState.CONNECTED,
                currentState = ConnectionState.CONNECTED,
            ),
        )
    }
}
