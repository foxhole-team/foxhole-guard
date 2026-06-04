package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.TrafficMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun `package replace restores local guard from resume state after stale kill`() {
        val plan =
            packageReplaceRecoveryPlan(
                resumeState =
                RuntimeResumeState(
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = null,
                    protocolOptionId = null,
                    localGuardMode = LocalGuardMode.FIREWALL,
                ),
                hasActiveVpnNetwork = true,
                activeProfileId = 7L,
                settingsTrafficMode = TrafficMode.PROXY,
                localGuardMode = null,
            )

        assertTrue(plan.killStaleRuntime)
        assertEquals(TrafficMode.TUNNEL, plan.killTrafficMode)
        assertEquals(LocalGuardMode.FIREWALL, plan.localGuardMode)
        assertEquals(null, plan.profileId)
    }

    @Test
    fun `package replace restores profile from resume state with stored mode`() {
        val plan =
            packageReplaceRecoveryPlan(
                resumeState =
                RuntimeResumeState(
                    trafficMode = TrafficMode.PROXY,
                    profileId = 42L,
                    protocolOptionId = "vless-main",
                    localGuardMode = null,
                ),
                hasActiveVpnNetwork = true,
                activeProfileId = null,
                settingsTrafficMode = TrafficMode.TUNNEL,
                localGuardMode = LocalGuardMode.DNS,
            )

        assertTrue(plan.killStaleRuntime)
        assertEquals(TrafficMode.PROXY, plan.killTrafficMode)
        assertEquals(42L, plan.profileId)
        assertEquals("vless-main", plan.protocolOptionId)
        assertEquals(TrafficMode.PROXY, plan.profileTrafficMode)
        assertEquals(null, plan.localGuardMode)
    }

    @Test
    fun `package replace kills stale runtime and marks reconnect required when owner is unknown`() {
        val plan =
            packageReplaceRecoveryPlan(
                resumeState = null,
                hasActiveVpnNetwork = true,
                activeProfileId = null,
                settingsTrafficMode = TrafficMode.TUNNEL,
                localGuardMode = null,
            )

        assertTrue(plan.killStaleRuntime)
        assertTrue(plan.reconnectRequired)
        assertEquals(TrafficMode.TUNNEL, plan.killTrafficMode)
    }

    @Test
    fun `package replace starts local guard from settings even without stale vpn`() {
        val plan =
            packageReplaceRecoveryPlan(
                resumeState = null,
                hasActiveVpnNetwork = false,
                activeProfileId = null,
                settingsTrafficMode = TrafficMode.TUNNEL,
                localGuardMode = LocalGuardMode.DNS,
            )

        assertFalse(plan.killStaleRuntime)
        assertEquals(LocalGuardMode.DNS, plan.localGuardMode)
    }

    @Test
    fun `package replace wait accepts released tunnel network even with stale idle snapshot`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.IDLE,
                trafficMode = TrafficMode.TUNNEL,
                lastChangeAt = 100L,
            )

        assertTrue(
            isPackageReplaceRuntimeIdleAfterKill(
                snapshot = snapshot,
                hasActiveVpnNetwork = false,
                killTrafficMode = TrafficMode.TUNNEL,
                killStartedAtMs = 200L,
            ),
        )
    }

    @Test
    fun `package replace wait accepts released tunnel network even with stale active snapshot`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                lastChangeAt = 100L,
            )

        assertTrue(
            isPackageReplaceRuntimeIdleAfterKill(
                snapshot = snapshot,
                hasActiveVpnNetwork = false,
                killTrafficMode = TrafficMode.TUNNEL,
                killStartedAtMs = 200L,
            ),
        )
    }

    @Test
    fun `package replace wait keeps tunnel blocked while vpn network is active`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.IDLE,
                trafficMode = TrafficMode.TUNNEL,
                lastChangeAt = 300L,
            )

        assertFalse(
            isPackageReplaceRuntimeIdleAfterKill(
                snapshot = snapshot,
                hasActiveVpnNetwork = true,
                killTrafficMode = TrafficMode.TUNNEL,
                killStartedAtMs = 200L,
            ),
        )
    }

    @Test
    fun `package replace wait still requires bridge update for proxy runtime`() {
        val snapshot =
            ConnectionSnapshot(
                state = ConnectionState.IDLE,
                trafficMode = TrafficMode.PROXY,
                lastChangeAt = 100L,
            )

        assertFalse(
            isPackageReplaceRuntimeIdleAfterKill(
                snapshot = snapshot,
                hasActiveVpnNetwork = false,
                killTrafficMode = TrafficMode.PROXY,
                killStartedAtMs = 200L,
            ),
        )
    }
}
