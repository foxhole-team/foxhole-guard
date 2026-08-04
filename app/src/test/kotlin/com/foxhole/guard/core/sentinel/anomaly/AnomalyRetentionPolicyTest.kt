package com.foxhole.guard.core.sentinel.anomaly

import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.RetentionPreset
import com.foxhole.core.model.StatisticsRetention
import com.foxhole.core.model.StatisticsSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class AnomalyRetentionPolicyTest {
    @Test
    fun `statistics forever retention does not expire traffic windows`() {
        assertEquals(
            0L,
            statisticsRetentionCutoff(
                nowMs = NOW_MS,
                settings = StatisticsSettings(retention = StatisticsRetention.FOREVER)
            )
        )
    }

    @Test
    fun `statistics retention cutoff follows statistics setting not anomaly history`() {
        assertEquals(
            NOW_MS - 7L * DAY_MS,
            statisticsRetentionCutoff(
                nowMs = NOW_MS,
                settings = StatisticsSettings(retention = StatisticsRetention.WEEK)
            ),
        )
        assertEquals(
            NOW_MS - 31L * DAY_MS,
            statisticsRetentionCutoff(
                nowMs = NOW_MS,
                settings = StatisticsSettings(retention = StatisticsRetention.MONTH)
            ),
        )
        // The legacy 3-month tier coarsens to the policy's Month preset.
        assertEquals(
            NOW_MS - 31L * DAY_MS,
            statisticsRetentionCutoff(
                nowMs = NOW_MS,
                settings = StatisticsSettings(retention = StatisticsRetention.MONTHS_3)
            ),
        )
    }

    @Test
    fun `explicit retention policy overrides the legacy stored enum`() {
        val settings =
            StatisticsSettings(
                retention = StatisticsRetention.WEEK,
                retentionPolicy = RetentionPolicy(RetentionPreset.CUSTOM, customDays = 3),
            )
        assertEquals(NOW_MS - 3L * DAY_MS, statisticsRetentionCutoff(nowMs = NOW_MS, settings = settings))
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val NOW_MS = 200L * DAY_MS
    }
}
