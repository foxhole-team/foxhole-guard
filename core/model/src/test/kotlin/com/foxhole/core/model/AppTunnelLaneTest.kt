package com.foxhole.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppTunnelLaneTest {
    private val expert =
        ExpertSettings(
            appAssignments =
            mapOf(
                "com.a" to AppTunnelLane.TOR,
                "com.b" to AppTunnelLane.VPN,
                "com.c" to AppTunnelLane.BLOCK,
                "com.d" to AppTunnelLane.EXCLUDE,
                "com.e" to AppTunnelLane.TOR,
            ),
        )

    @Test
    fun `packages returns the deterministically ordered members of a lane`() {
        assertEquals(listOf("com.a", "com.e"), expert.packages(AppTunnelLane.TOR))
        assertEquals(listOf("com.b"), expert.packages(AppTunnelLane.VPN))
        assertEquals(listOf("com.c"), expert.blockedLanePackages())
        assertEquals(listOf("com.d"), expert.excludedLanePackages())
    }

    @Test
    fun `tunnel selection is the union of Tor and VPN lanes`() {
        assertEquals(listOf("com.a", "com.b", "com.e"), expert.tunnelSelectedPackages())
    }

    @Test
    fun `withLane reassigns a package and drops its prior lane`() {
        val moved = expert.withLane(AppTunnelLane.BLOCK, listOf("com.a"))
        assertEquals(listOf("com.e"), moved.packages(AppTunnelLane.TOR))
        assertEquals(listOf("com.a", "com.c"), moved.blockedLanePackages())
    }

    @Test
    fun `withoutAssignments removes packages from every lane`() {
        val pruned = expert.withoutAssignments(listOf("com.c", "com.d"))
        assertEquals(emptyList<String>(), pruned.blockedLanePackages())
        assertEquals(emptyList<String>(), pruned.excludedLanePackages())
        assertEquals(listOf("com.a", "com.b", "com.e"), pruned.tunnelSelectedPackages())
    }
}
