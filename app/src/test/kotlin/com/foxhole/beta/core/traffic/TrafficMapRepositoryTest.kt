package com.foxhole.beta.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficMapRepositoryTest {
    @Test
    fun `dashboard map caps retained visible destinations for battery budget`() {
        assertEquals(30, TrafficMapRepository.MaxTrafficMapDestinations)
    }

    @Test
    fun `aggregates live traffic by country code before UI mapping`() {
        val aggregates =
            aggregateTrafficMapSamples(
                samples =
                listOf(
                    TrafficMapConnectionSample(connectionId = "1", countryCode = "us", bytes = 10L, connections = 1),
                    TrafficMapConnectionSample(connectionId = "2", countryCode = "US", bytes = 20L, connections = 2),
                    TrafficMapConnectionSample(connectionId = "3", countryCode = "DE", bytes = 5L, connections = 1),
                ),
            )

        val points = trafficMapPointsFromAggregates(aggregates = aggregates, limit = 10)

        assertEquals(2, points.size)
        assertEquals("US", points[0].countryCode)
        assertEquals(30L, points[0].bytes)
        assertEquals(3, points[0].connections)
        assertEquals("DE", points[1].countryCode)
    }

    @Test
    fun `accumulator keeps latest bytes per live connection id`() {
        val accumulator =
            TrafficMapConnectionAccumulator()
                .updatedWith(
                    listOf(
                        TrafficMapConnectionSample(connectionId = "same", countryCode = "US", bytes = 10L),
                    ),
                )
                .updatedWith(
                    listOf(
                        TrafficMapConnectionSample(connectionId = "same", countryCode = "US", bytes = 30L),
                        TrafficMapConnectionSample(connectionId = "next", countryCode = "DE", bytes = 5L),
                    ),
                )

        val aggregates = accumulator.countryAggregates()

        assertEquals(30L, aggregates["US"]?.bytes)
        assertEquals(1, aggregates["US"]?.connections)
        assertEquals(5L, aggregates["DE"]?.bytes)
    }

    @Test
    fun `accumulator clears map when runtime is unavailable`() {
        val accumulator =
            TrafficMapConnectionAccumulator()
                .updatedForBatch(
                    TrafficMapSampleBatch(
                        samples = listOf(TrafficMapConnectionSample(connectionId = "old", countryCode = "US", bytes = 10L)),
                        runtimeAvailable = true,
                    ),
                )
                .updatedForBatch(TrafficMapSampleBatch(samples = emptyList(), runtimeAvailable = false))

        assertEquals(null, accumulator.countryAggregates()["US"])
    }

    @Test
    fun `accumulator replaces old map with fresh live runtime samples`() {
        val accumulator =
            TrafficMapConnectionAccumulator()
                .updatedForBatch(
                    TrafficMapSampleBatch(
                        samples = listOf(TrafficMapConnectionSample(connectionId = "old", countryCode = "US", bytes = 10L)),
                        runtimeAvailable = true,
                    ),
                )

        val refreshed =
            accumulator.updatedForBatch(
                TrafficMapSampleBatch(
                    samples = listOf(TrafficMapConnectionSample(connectionId = "fresh", countryCode = "DE", bytes = 5L)),
                    runtimeAvailable = true,
                ),
            )

        assertEquals(null, refreshed.countryAggregates()["US"])
        assertEquals(5L, refreshed.countryAggregates()["DE"]?.bytes)
    }
}
