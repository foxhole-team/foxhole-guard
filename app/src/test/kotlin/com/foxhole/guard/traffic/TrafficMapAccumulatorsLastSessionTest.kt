package com.foxhole.guard.traffic

import com.foxhole.core.model.TrafficMapPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// After the runtime disconnects the map must keep showing the LAST session's aggregates;
// only the next unavailable -> available transition starts the accumulator over.
class TrafficMapAccumulatorsLastSessionTest {
    @Test
    fun `unavailable batch keeps last session aggregates on the map`() {
        val active =
            TrafficMapConnectionAccumulator()
                .updatedForBatch(
                    TrafficMapSampleBatch(
                        samples = listOf(
                            TrafficMapConnectionSample(connectionId = "us", countryCode = "US", bytes = 100L),
                            TrafficMapConnectionSample(connectionId = "de", countryCode = "DE", bytes = 40L),
                        ),
                        runtimeAvailable = true,
                    ),
                    nowMs = 1_000L,
                )

        val retained =
            active.updatedForBatch(
                TrafficMapSampleBatch(samples = emptyList(), runtimeAvailable = false),
                nowMs = 2_000L,
            )

        assertEquals(100L, retained.countryAggregates()["US"]?.bytes)
        assertEquals(40L, retained.countryAggregates()["DE"]?.bytes)
        assertEquals(100L, retained.periodAggregates(TrafficMapPeriod.SESSION, nowMs = 2_000L)["US"]?.bytes)
        assertEquals(40L, retained.periodAggregates(TrafficMapPeriod.FIVE_MINUTES, nowMs = 2_000L)["DE"]?.bytes)
    }

    @Test
    fun `repeated unavailable batches keep the retained session aggregates`() {
        val retained =
            TrafficMapConnectionAccumulator()
                .updatedForBatch(
                    TrafficMapSampleBatch(
                        samples = listOf(
                            TrafficMapConnectionSample(connectionId = "us", countryCode = "US", bytes = 100L),
                        ),
                        runtimeAvailable = true,
                    ),
                    nowMs = 1_000L,
                )
                .updatedForBatch(
                    TrafficMapSampleBatch(samples = emptyList(), runtimeAvailable = false),
                    nowMs = 2_000L,
                )
                .updatedForBatch(
                    TrafficMapSampleBatch(samples = emptyList(), runtimeAvailable = false),
                    nowMs = 3_000L,
                )

        assertEquals(100L, retained.countryAggregates()["US"]?.bytes)
        assertEquals(100L, retained.periodAggregates(TrafficMapPeriod.SESSION, nowMs = 3_000L)["US"]?.bytes)
    }

    @Test
    fun `session country bytes remain cumulative after a short flow leaves the live batch`() {
        val first =
            TrafficMapConnectionAccumulator()
                .updatedForBatch(
                    TrafficMapSampleBatch(
                        samples = listOf(
                            TrafficMapConnectionSample(connectionId = "short", countryCode = "DE", bytes = 90L),
                        ),
                        runtimeAvailable = true,
                    ),
                    nowMs = 1_000L,
                )
        val afterClose =
            first.updatedForBatch(
                TrafficMapSampleBatch(samples = emptyList(), runtimeAvailable = true),
                nowMs = 2_000L,
            )

        assertNull(afterClose.countryAggregates()["DE"])
        assertEquals(90L, afterClose.sessionCountryBytes()["DE"])
    }

    @Test
    fun `new session after unavailable starts from a clean accumulator`() {
        val retained =
            TrafficMapConnectionAccumulator()
                .updatedForBatch(
                    TrafficMapSampleBatch(
                        samples = listOf(
                            TrafficMapConnectionSample(connectionId = "us", countryCode = "US", bytes = 100L),
                        ),
                        runtimeAvailable = true,
                    ),
                    nowMs = 1_000L,
                )
                .updatedForBatch(
                    TrafficMapSampleBatch(samples = emptyList(), runtimeAvailable = false),
                    nowMs = 2_000L,
                )

        val nextSession =
            retained.updatedForBatch(
                TrafficMapSampleBatch(
                    samples = listOf(
                        TrafficMapConnectionSample(connectionId = "fr", countryCode = "FR", bytes = 5L),
                    ),
                    runtimeAvailable = true,
                ),
                nowMs = 3_000L,
            )

        assertNull(nextSession.countryAggregates()["US"])
        assertEquals(5L, nextSession.countryAggregates()["FR"]?.bytes)
        val session = nextSession.periodAggregates(TrafficMapPeriod.SESSION, nowMs = 3_000L)
        assertNull(session["US"])
        assertEquals(5L, session["FR"]?.bytes)
    }

    @Test
    fun `zero session tunnel floor outside a session keeps the retained snapshot`() {
        // Out of session the tunnel counter provider reads 0; the floor must stay a floor
        // (only ever raising totals) so the retained last-session snapshot is never zeroed.
        val snapshot =
            trafficMapDestinationSnapshotFromAggregates(
                aggregates = mapOf(
                    "US" to TrafficMapAggregate(countryCode = "US", bytes = 100L, connections = 1),
                ),
                limit = 10,
            )

        val floored = snapshot.withSessionTunnelFloor(0L)

        assertEquals(snapshot, floored)
        assertEquals(100L, floored.totalBytes)
        assertEquals(100L, floored.routeAggregate.bytes)
    }
}
