package com.foxhole.guard.core.settings

import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsRefreshInterval
import com.foxhole.core.model.StatisticsSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsPersistenceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `normalization and an app-version round trip keep statistics enabled`() {
        val stored =
            Settings(
                schemaVersion = 1,
                statistics =
                StatisticsSettings(
                    enabled = true,
                    refreshInterval = StatisticsRefreshInterval.SECONDS_10,
                    profileTrafficEnabled = true,
                    countryTrafficEnabled = true,
                ),
            )

        val restored = json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), stored))
            .normalized()

        assertTrue(restored.statistics.enabled)
        assertTrue(restored.statistics.profileTrafficEnabled)
        assertTrue(restored.statistics.countryTrafficEnabled)
        assertEquals(StatisticsRefreshInterval.SECONDS_10, restored.statistics.refreshInterval)
    }
}
