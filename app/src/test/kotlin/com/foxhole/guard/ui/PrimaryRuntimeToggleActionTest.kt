package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stop must always tear the whole tunnel down. Regression guard for the "Stop with VPN + beside-Tor
 * killed only the VPN and kept Tor running" bug: there is no keep-Tor branch anymore, so a beside-Tor
 * runtime resolves to a plain DISCONNECT like every other active runtime.
 */
internal class PrimaryRuntimeToggleActionTest {
    @Test
    fun `beside-tor active resolves to full disconnect, never keep-tor`() {
        assertEquals(
            PrimaryRuntimeToggleAction.DISCONNECT,
            primaryRuntimeToggleActionFor(
                isPrimaryConnectionRuntime = true,
                reconnectRequired = false,
                connectionState = ConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun `reconnect required on a connected runtime resolves to reconnect`() {
        assertEquals(
            PrimaryRuntimeToggleAction.RECONNECT,
            primaryRuntimeToggleActionFor(
                isPrimaryConnectionRuntime = true,
                reconnectRequired = true,
                connectionState = ConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun `reconnect required but not yet connected still disconnects`() {
        assertEquals(
            PrimaryRuntimeToggleAction.DISCONNECT,
            primaryRuntimeToggleActionFor(
                isPrimaryConnectionRuntime = true,
                reconnectRequired = true,
                connectionState = ConnectionState.CONNECTING,
            ),
        )
    }

    @Test
    fun `non-primary runtime resolves to none`() {
        assertEquals(
            PrimaryRuntimeToggleAction.NONE,
            primaryRuntimeToggleActionFor(
                isPrimaryConnectionRuntime = false,
                reconnectRequired = false,
                connectionState = ConnectionState.CONNECTED,
            ),
        )
    }
}
