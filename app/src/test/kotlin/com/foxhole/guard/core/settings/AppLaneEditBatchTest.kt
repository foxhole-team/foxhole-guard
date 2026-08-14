package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app picker now carries both halves of an edit — apps ticked to be added and apps ticked to be
 * taken out — and applies them as one transform.
 *
 * One transform is the requirement, not a convenience: every intermediate membership is published
 * to the live runtime and re-derives the block toggles from itself, so a removals-then-additions
 * pair of writes would arm and disarm the firewall on the way through, and the runtime would reload
 * a routing set the user never asked for.
 */
internal class AppLaneEditBatchTest {

    private fun apply(
        current: Settings,
        added: List<String> = emptyList(),
        removed: List<String> = emptyList(),
    ): Settings = updateAppLanesIn(updateAppLanesIn(current, removed, null), added, AppTunnelLane.VPN)

    @Test
    fun `additions and removals land together`() {
        val current = updateAppLaneIn(Settings(), "com.app.old", AppTunnelLane.VPN)

        val result = apply(current, added = listOf("com.app.new"), removed = listOf("com.app.old"))

        assertEquals(AppTunnelLane.VPN, result.expert.appAssignments["com.app.new"])
        assertNull(result.expert.appAssignments["com.app.old"])
    }

    @Test
    fun `a removal alone leaves the lane`() {
        val current = updateAppLaneIn(Settings(), "com.app.one", AppTunnelLane.TOR)

        val result = apply(current, removed = listOf("com.app.one"))

        assertTrue(result.expert.appAssignments.isEmpty())
    }

    /**
     * The block toggles follow the FINAL membership. Taking the last blocked app out has to leave
     * "block apps" off — otherwise the firewall keeps enforcing a list that is now empty.
     */
    @Test
    fun `removing the last blocked app clears the block toggles`() {
        val current = updateAppLaneIn(Settings(), "com.app.one", AppTunnelLane.BLOCK)
        assertTrue(current.expert.blockedPackagesEnabled)

        val result = apply(current, removed = listOf("com.app.one"))

        assertFalse(result.expert.blockedPackagesEnabled)
        assertFalse(result.expert.blockAppsAlways)
    }

    @Test
    fun `an empty edit changes nothing`() {
        val current = updateAppLaneIn(Settings(), "com.app.one", AppTunnelLane.VPN)

        assertEquals(current, apply(current))
    }
}
