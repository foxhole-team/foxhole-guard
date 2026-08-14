package com.foxhole.core.runtime

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.runtime.ForegroundServiceStartBlockReason
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.runtime.disconnectDispatchModeOrNull
import com.foxhole.guard.runtime.disconnectDispatchModes
import com.foxhole.guard.runtime.foregroundServiceStartBlockReason
import com.foxhole.guard.runtime.foregroundStartBlockedDiagnosticMessage
import com.foxhole.guard.runtime.shouldClearDetachedTunnelReconnect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxholeConnectionServiceContractTest {
    @Test
    fun `the runtime contract exposes only the single vpn service`() {
        assertEquals(FoxholeVpnService::class.java, FoxholeConnectionServiceContract.serviceClass())
    }

    @Test
    fun `active snapshot keeps current service mode for disconnect routing`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.PROXY,
            )

        assertEquals(
            TrafficMode.TUNNEL,
            FoxholeConnectionServiceContract.serviceMode(
                snapshot = snapshot,
                fallbackMode = TrafficMode.TUNNEL,
            ),
        )
    }

    @Test
    fun `idle snapshot falls back to settings mode`() {
        val snapshot = ConnectionSnapshot(state = ConnectionState.IDLE, trafficMode = TrafficMode.TUNNEL)

        assertEquals(
            TrafficMode.TUNNEL,
            FoxholeConnectionServiceContract.serviceMode(
                snapshot = snapshot,
                fallbackMode = TrafficMode.PROXY,
            ),
        )
    }

    @Test
    fun `disconnect dispatch only uses active runtime snapshots`() {
        assertNull(
            disconnectDispatchModeOrNull(
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.TUNNEL,
                ),
            ),
        )

        assertEquals(
            TrafficMode.TUNNEL,
            disconnectDispatchModeOrNull(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.PROXY,
                ),
            ),
        )
    }

    @Test
    fun `stale idle snapshot still dispatches tunnel disconnect when system vpn is active`() {
        assertEquals(
            listOf(TrafficMode.TUNNEL),
            disconnectDispatchModes(
                snapshot = ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.PROXY,
                ),
                activeVpnNetworkAvailable = true,
            ),
        )

        assertEquals(
            emptyList<TrafficMode>(),
            disconnectDispatchModes(
                snapshot = ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    trafficMode = TrafficMode.TUNNEL,
                ),
                activeVpnNetworkAvailable = false,
            ),
        )
    }

    @Test
    fun `detached tunnel reconnect clears locally instead of dispatching to dead service`() {
        val reconnectingTunnel =
            ConnectionSnapshot(
                state = ConnectionState.RECONNECTING,
                trafficMode = TrafficMode.TUNNEL,
            )
        val reconnectingProxy =
            ConnectionSnapshot(
                state = ConnectionState.RECONNECTING,
                trafficMode = TrafficMode.PROXY,
            )

        assertTrue(
            shouldClearDetachedTunnelReconnect(reconnectingTunnel, activeVpnNetworkAvailable = false),
        )

        assertFalse(
            shouldClearDetachedTunnelReconnect(reconnectingTunnel, activeVpnNetworkAvailable = true),
        )

        assertTrue(
            shouldClearDetachedTunnelReconnect(reconnectingProxy, activeVpnNetworkAvailable = false),
        )
    }

    @Test
    fun `foreground service start failures are classified for safe restore`() {
        assertEquals(
            ForegroundServiceStartBlockReason.SECURITY,
            foregroundServiceStartBlockReason(SecurityException("contains raw endpoint")),
        )
        assertEquals(
            ForegroundServiceStartBlockReason.ILLEGAL_STATE,
            foregroundServiceStartBlockReason(IllegalStateException("background launch blocked")),
        )
        assertNull(foregroundServiceStartBlockReason(RuntimeException("ordinary runtime failure")))
    }

    @Test
    fun `foreground service start diagnostics do not include raw exception message`() {
        val message =
            foregroundStartBlockedDiagnosticMessage(
                action = FoxholeConnectionServiceContract.ACTION_RESTORE,
                mode = TrafficMode.TUNNEL,
                reason = ForegroundServiceStartBlockReason.SECURITY,
                error = SecurityException("https://secret.example/subscription"),
            )

        assertTrue(message.contains("reason=security"))
        assertTrue(message.contains("action=${FoxholeConnectionServiceContract.ACTION_RESTORE}"))
        assertTrue(message.contains("mode=tunnel"))
        assertTrue(message.contains("error=SecurityException"))
        assertFalse(message.contains("secret.example"))
        assertFalse(message.contains("subscription"))
    }
}
