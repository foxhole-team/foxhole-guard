package com.foxhole.core.runtime

import android.content.Intent
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.runtime.BOOT_RECEIVER_TIMEOUT_MS
import com.foxhole.guard.runtime.BootReceiverDispatch
import com.foxhole.guard.runtime.BootRestoreAction
import com.foxhole.guard.runtime.bootReceiverDispatch
import com.foxhole.guard.runtime.bootRestorePlan
import com.foxhole.guard.runtime.isPackageReplaceRuntimeIdleAfterKill
import com.foxhole.guard.runtime.packageReplaceRecoveryPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun `boot receiver dispatches package replace to worker recovery`() {
        assertEquals(BootReceiverDispatch.BOOT_RESTORE, bootReceiverDispatch(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(
            BootReceiverDispatch.PACKAGE_REPLACE_RECOVERY,
            bootReceiverDispatch(Intent.ACTION_MY_PACKAGE_REPLACED),
        )
        assertNull(bootReceiverDispatch(Intent.ACTION_PACKAGE_REPLACED))
    }

    @Test
    fun `boot receiver broadcast timeout stays short`() {
        assertTrue(BOOT_RECEIVER_TIMEOUT_MS <= 500L)
    }

    @Test
    fun `boot restore starts profile when auto start is enabled`() {
        val plan =
            bootRestorePlan(
                autoStartOnBoot = true,
                trafficMode = TrafficMode.PROXY,
                localGuardMode = null,
            )

        assertEquals(BootRestoreAction.RESTORE_PROFILE, plan.action)
        assertEquals(TrafficMode.PROXY, plan.trafficMode)
        assertEquals(null, plan.localGuardMode)
        assertEquals("boot restore requested", plan.diagnosticMessage)
    }

    @Test
    fun `boot restore auto start takes precedence over local guard`() {
        val plan =
            bootRestorePlan(
                autoStartOnBoot = true,
                trafficMode = TrafficMode.TUNNEL,
                localGuardMode = LocalGuardMode.DNS,
            )

        assertEquals(BootRestoreAction.RESTORE_PROFILE, plan.action)
        assertEquals(TrafficMode.TUNNEL, plan.trafficMode)
        assertEquals(null, plan.localGuardMode)
    }

    @Test
    fun `boot restore starts local guard when auto start is disabled`() {
        val plan =
            bootRestorePlan(
                autoStartOnBoot = false,
                trafficMode = TrafficMode.PROXY,
                localGuardMode = LocalGuardMode.FIREWALL,
            )

        assertEquals(BootRestoreAction.START_LOCAL_GUARD, plan.action)
        assertEquals(TrafficMode.TUNNEL, plan.trafficMode)
        assertEquals(LocalGuardMode.FIREWALL, plan.localGuardMode)
        assertEquals("boot local guard restore requested mode=firewall", plan.diagnosticMessage)
    }

    @Test
    fun `boot restore skips when auto start and local guard are disabled`() {
        val plan =
            bootRestorePlan(
                autoStartOnBoot = false,
                trafficMode = TrafficMode.PROXY,
                localGuardMode = null,
            )

        assertEquals(BootRestoreAction.SKIP, plan.action)
        assertEquals(TrafficMode.PROXY, plan.trafficMode)
        assertEquals(null, plan.localGuardMode)
        assertEquals("boot restore skipped: auto start disabled", plan.diagnosticMessage)
    }

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
