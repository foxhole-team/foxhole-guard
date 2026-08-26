package com.foxhole.guard.runtime

import com.foxhole.core.model.AppliedTorRoute
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PrivacyRouteScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeReconnectTorStateTest {
    @Test
    fun `detached reconnect snapshot cannot advertise a stopped TOR runtime`() {
        val reconnecting =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                profileId = 42L,
                torActive = true,
                appliedTorRoute =
                AppliedTorRoute(
                    scope = PrivacyRouteScope.SELECTED_APPS,
                    bypassVpnTunnel = false,
                    selectedPackages = listOf("com.example.app"),
                ),
            ).detachedRuntimeReconnectSnapshot("reconnecting")

        assertEquals(ConnectionState.RECONNECTING, reconnecting.state)
        assertFalse(reconnecting.torActive)
        assertNull(reconnecting.appliedTorRoute)
    }
}
