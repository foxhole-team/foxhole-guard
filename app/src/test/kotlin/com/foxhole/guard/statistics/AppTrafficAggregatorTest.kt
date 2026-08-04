package com.foxhole.guard.statistics

import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTrafficAggregatorTest {
    @Test
    fun `app traffic top apps includes other`() {
        val windows =
            (1..12).map { index ->
                AppTrafficWindow(
                    packageName = "pkg.$index",
                    startedAtMs = index.toLong(),
                    durationSec = 60,
                    rxBytes = (100 - index).toLong(),
                    txBytes = 0L,
                    foreground = null,
                    networkType = NetworkType.UNKNOWN,
                )
            }

        val rows =
            appTrafficRows(
                windows = windows,
                labelsByPackage = emptyMap(),
                anomalyEvents = emptyList(),
                maxRows = 10,
                includeOther = true,
            )

        assertEquals(11, rows.size)
        assertEquals(OTHER_PACKAGE, rows.last().packageName)
        assertEquals(ChartDataQuality.PARTIAL, rows.last().quality)
        assertTrue(rows.last().totalBytes > 0L)
    }
}
