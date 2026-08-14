package com.foxhole.guard.core.sentinel.anomaly

import com.foxhole.core.model.AppTunnelLane
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnomalyLaneSuppressionTest {
    private val assignments =
        mapOf(
            "app.excluded" to AppTunnelLane.EXCLUDE,
            "app.vpn" to AppTunnelLane.VPN,
        )

    @Test
    fun `events from an excluded app never become anomalies`() {
        assertTrue(anomalySuppressedForExcludedApp(assignments, "app.excluded"))
    }

    @Test
    fun `other lanes and unattributed events keep full inspection`() {
        assertFalse(anomalySuppressedForExcludedApp(assignments, "app.vpn"))
        assertFalse(anomalySuppressedForExcludedApp(assignments, "app.unknown"))
        assertFalse(anomalySuppressedForExcludedApp(assignments, null))
    }
}
