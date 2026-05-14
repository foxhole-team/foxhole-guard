package com.foxhole.beta.core.anomaly

import com.foxhole.beta.core.model.StatisticsRetention
import org.junit.Assert.assertEquals
import org.junit.Test

class AnomalyRetentionPolicyTest {
    @Test
    fun `statistics forever retention does not expire traffic windows`() {
        assertEquals(0L, statisticsRetentionCutoff(nowMs = NOW_MS, retention = StatisticsRetention.FOREVER))
    }

    @Test
    fun `statistics retention cutoff follows statistics setting not anomaly history`() {
        assertEquals(
            NOW_MS - 7L * DAY_MS,
            statisticsRetentionCutoff(nowMs = NOW_MS, retention = StatisticsRetention.WEEK),
        )
        assertEquals(
            NOW_MS - 31L * DAY_MS,
            statisticsRetentionCutoff(nowMs = NOW_MS, retention = StatisticsRetention.MONTH),
        )
        assertEquals(
            NOW_MS - 93L * DAY_MS,
            statisticsRetentionCutoff(nowMs = NOW_MS, retention = StatisticsRetention.MONTHS_3),
        )
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val NOW_MS = 200L * DAY_MS
    }
}
