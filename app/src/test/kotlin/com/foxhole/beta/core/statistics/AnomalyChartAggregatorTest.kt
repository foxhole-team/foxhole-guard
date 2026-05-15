package com.foxhole.beta.core.statistics

import com.foxhole.beta.core.model.AnomalyEvent
import com.foxhole.beta.core.model.AnomalySeverity
import com.foxhole.beta.core.model.AnomalyType
import org.junit.Assert.assertEquals
import org.junit.Test

class AnomalyChartAggregatorTest {
    @Test
    fun `anomaly timeline keeps real time spacing and fills empty buckets`() {
        val buckets =
            anomalyTimelineBuckets(
                events =
                    listOf(
                        anomalyEvent(createdAtMs = 1_000L, score = 30),
                        anomalyEvent(createdAtMs = 4_000L, score = 80, severity = AnomalySeverity.HIGH),
                    ),
                startMs = 1_000L,
                endMs = 5_000L,
                bucketSizeMs = 1_000L,
            )

        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), buckets.map(AnomalyChartPoint::bucketStartAt))
        assertEquals(listOf(30, 0, 0, 80), buckets.map(AnomalyChartPoint::score))
        assertEquals(10f, timestampToChartX(2_000L, 1_000L, 5_000L, 0f, 40f), 0.001f)
    }

    private fun anomalyEvent(
        createdAtMs: Long,
        score: Int,
        severity: AnomalySeverity = AnomalySeverity.SILENT,
    ): AnomalyEvent =
        AnomalyEvent(
            createdAtMs = createdAtMs,
            type = AnomalyType.TOTAL_TRAFFIC_SPIKE,
            severity = severity,
            score = score,
            reason = "test",
            evidence = emptyMap(),
        )
}
