package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeReloadReadinessPolicyTest {
    @Test
    fun `in place reload preserves connected state and identity while replacement validates`() {
        val connected =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                profileId = 1L,
                profileName = "VPN",
                protocolHint = ProtocolHint.VLESS,
                torActive = false,
                message = "connected",
                lastChangeAt = 123L,
            )
        val replacement =
            VpnSession(
                profileId = 1L,
                profileName = "VPN",
                protocolHint = ProtocolHint.VLESS,
                configJson = "{}",
                correlationId = "vpn-tor-reload",
                torActive = true,
            )

        val applying =
            runtimeReloadPendingSnapshot(
                snapshot = connected,
                reconnectingMessage = "checking",
                session = replacement,
                inPlaceRuntimeReload = true,
            )

        assertEquals(ConnectionState.CONNECTED, applying.state)
        assertEquals("connected", applying.message)
        assertFalse(applying.torActive)
        assertTrue(applying.inPlaceRuntimeReload)
        assertEquals(123L, applying.lastChangeAt)
    }

    @Test
    fun `cold reload pending snapshot cannot masquerade as in place validation`() {
        val applying =
            runtimeReloadPendingSnapshot(
                snapshot = ConnectionSnapshot(state = ConnectionState.CONNECTED, profileId = 1L),
                reconnectingMessage = "restarting",
                inPlaceRuntimeReload = false,
            )

        assertEquals(ConnectionState.RECONNECTING, applying.state)
        assertFalse(applying.inPlaceRuntimeReload)
    }

    @Test
    fun `health monitor cannot reconnect while runtime command or validation owns readiness`() {
        assertTrue(notificationHealthTransitionInFlight(validationActive = true, runtimeCommandRunning = false))
        assertTrue(notificationHealthTransitionInFlight(validationActive = false, runtimeCommandRunning = true))
        assertFalse(notificationHealthTransitionInFlight(validationActive = false, runtimeCommandRunning = false))
    }
}
