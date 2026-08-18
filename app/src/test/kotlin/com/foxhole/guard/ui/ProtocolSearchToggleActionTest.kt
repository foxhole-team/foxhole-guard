package com.foxhole.guard.ui

import org.junit.Assert.assertEquals
import org.junit.Test

internal class ProtocolSearchToggleActionTest {
    @Test
    fun `visible auto-connect run consumes the toggle`() {
        assertEquals(
            ProtocolSearchToggleAction.CANCEL_SEARCH_AND_CONSUME,
            protocolSearchToggleActionFor(
                autoConnectRunning = true,
                metricsRefreshRunning = false,
            ),
        )
    }

    @Test
    fun `auto-connect wins over a background refresh running beside it`() {
        assertEquals(
            ProtocolSearchToggleAction.CANCEL_SEARCH_AND_CONSUME,
            protocolSearchToggleActionFor(
                autoConnectRunning = true,
                metricsRefreshRunning = true,
            ),
        )
    }

    @Test
    fun `background metrics refresh cancels but never consumes the toggle`() {
        assertEquals(
            ProtocolSearchToggleAction.CANCEL_REFRESH_AND_CONTINUE,
            protocolSearchToggleActionFor(
                autoConnectRunning = false,
                metricsRefreshRunning = true,
            ),
        )
    }

    @Test
    fun `nothing running resolves to none`() {
        assertEquals(
            ProtocolSearchToggleAction.NONE,
            protocolSearchToggleActionFor(
                autoConnectRunning = false,
                metricsRefreshRunning = false,
            ),
        )
    }
}
