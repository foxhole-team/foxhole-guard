package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safe mode (the "factory expert defaults" flag, on for every fresh install) must end the moment
 * the user pins an app to any lane: while it is on, [Settings.normalized] strips every non-BLOCK
 * lane, so a wrong safe-mode clause makes Apps-screen additions silently vanish on write.
 */
internal class AppLaneSafeModeTest {
    private val pristine = Settings()

    @Test
    fun `fresh install starts in safe mode`() {
        assertTrue(pristine.connection.safeModeEnabled)
    }

    @Test
    fun `adding an app to the vpn lane from pristine defaults survives normalization`() {
        val result = updateAppLaneIn(pristine, "com.app.one", AppTunnelLane.VPN).normalized()

        assertFalse(result.connection.safeModeEnabled)
        assertEquals(AppTunnelLane.VPN, result.expert.appAssignments["com.app.one"])
        assertEquals(PerAppRoutingMode.INCLUDE_SELECTED_APPS, result.expert.perAppRoutingMode)
        assertEquals(TrafficMode.TUNNEL, result.traffic.mode)
    }

    @Test
    fun `adding an app to the tor lane from pristine defaults survives normalization`() {
        val result = updateAppLaneIn(pristine, "com.app.one", AppTunnelLane.TOR).normalized()

        assertFalse(result.connection.safeModeEnabled)
        assertEquals(AppTunnelLane.TOR, result.expert.appAssignments["com.app.one"])
    }

    @Test
    fun `adding an app to the exclude lane from pristine defaults survives normalization`() {
        val result = updateAppLaneIn(pristine, "com.app.one", AppTunnelLane.EXCLUDE).normalized()

        assertFalse(result.connection.safeModeEnabled)
        assertEquals(AppTunnelLane.EXCLUDE, result.expert.appAssignments["com.app.one"])
    }

    @Test
    fun `blocking an app from pristine defaults keeps arming the firewall`() {
        val result = updateAppLaneIn(pristine, "com.app.one", AppTunnelLane.BLOCK).normalized()

        assertFalse(result.connection.safeModeEnabled)
        assertEquals(AppTunnelLane.BLOCK, result.expert.appAssignments["com.app.one"])
        assertTrue(result.expert.firewallEnabled)
        assertTrue(result.expert.blockedPackagesEnabled)
    }

    @Test
    fun `removing the last assignment keeps safe mode where it was`() {
        val added = updateAppLaneIn(pristine, "com.app.one", AppTunnelLane.VPN).normalized()
        val removed = updateAppLaneIn(added, "com.app.one", null).normalized()

        assertFalse(removed.connection.safeModeEnabled)
        assertTrue(removed.expert.appAssignments.isEmpty())
        assertEquals(PerAppRoutingMode.FULL_TUNNEL, removed.expert.perAppRoutingMode)
    }

    @Test
    fun `batch assignment write from pristine defaults survives normalization`() {
        val result =
            updateUnifiedAppAssignmentsIn(
                pristine,
                selectedPackages = listOf("com.app.one", "com.app.two"),
                blockedPackages = listOf("com.app.three"),
            ).normalized()

        assertFalse(result.connection.safeModeEnabled)
        assertEquals(AppTunnelLane.VPN, result.expert.appAssignments["com.app.one"])
        assertEquals(AppTunnelLane.VPN, result.expert.appAssignments["com.app.two"])
        assertEquals(AppTunnelLane.BLOCK, result.expert.appAssignments["com.app.three"])
    }

    @Test
    fun `batch selection without blocks also leaves safe mode`() {
        val result =
            updateUnifiedAppAssignmentsIn(
                pristine,
                selectedPackages = listOf("com.app.one"),
                blockedPackages = emptyList(),
            ).normalized()

        assertFalse(result.connection.safeModeEnabled)
        assertEquals(AppTunnelLane.VPN, result.expert.appAssignments["com.app.one"])
    }
}
