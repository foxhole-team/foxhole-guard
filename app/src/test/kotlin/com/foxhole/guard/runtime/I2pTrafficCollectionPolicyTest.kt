package com.foxhole.guard.runtime

import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class I2pTrafficCollectionPolicyTest {

    @Test
    fun `connected router traffic reaches statistics within one short UI interval`() {
        assertEquals(5_000L, FoxholeVpnService.I2P_TRAFFIC_SAMPLE_INTERVAL_MS)
    }

    @Test
    fun `statistics consent keeps the I2P cumulative cursor warm while the router is paused`() {
        val paused =
            Settings(
                statistics = StatisticsSettings(enabled = true),
                i2p = I2pSettings(enabled = true, engaged = false),
            )

        assertTrue(i2pTrafficStatsRuntimeEnabled(paused))
    }

    @Test
    fun `I2P engagement never overrides disabled statistics consent`() {
        val activeWithoutConsent =
            Settings(
                statistics = StatisticsSettings(enabled = false),
                i2p = I2pSettings(enabled = true, engaged = true),
            )

        assertFalse(i2pTrafficStatsRuntimeEnabled(activeWithoutConsent))
    }
}
