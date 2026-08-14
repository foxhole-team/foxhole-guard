package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.ui.cli.home.activeRuntimes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRouteClassificationTest {
    @Test
    fun `only a positive live profile id is a primary vpn runtime`() {
        fun snapshot(profileId: Long?): ConnectionSnapshot =
            ConnectionSnapshot(state = ConnectionState.CONNECTED, profileId = profileId)

        assertFalse(snapshot(null).isPrimaryConnectionRuntime())
        assertFalse(snapshot(FoxholeVpnService.LOCAL_GUARD_PROFILE_ID).isPrimaryConnectionRuntime())
        assertFalse(snapshot(FoxholeVpnService.TOR_ONLY_PROFILE_ID).isPrimaryConnectionRuntime())
        assertTrue(snapshot(42L).isPrimaryConnectionRuntime())
        assertTrue(snapshot(42L).copy(torActive = true).isPrimaryConnectionRuntime())
    }

    @Test
    fun `connected tor only sentinel is tor and never vpn even before torActive catches up`() {
        val home =
            HomeRouteUiState(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                    torActive = false,
                ),
            )

        val runtimes = activeRuntimes(home, torOnlyLive = false)

        assertFalse(runtimes.vpn)
        assertTrue(runtimes.tor)
    }
}
