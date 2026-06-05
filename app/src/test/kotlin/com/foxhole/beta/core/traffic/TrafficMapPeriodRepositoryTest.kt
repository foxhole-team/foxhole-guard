package com.foxhole.beta.core.traffic

import com.foxhole.beta.core.model.NetworkType
import com.foxhole.beta.core.model.TrafficMapPeriod
import com.foxhole.beta.core.model.TrafficWindow
import com.foxhole.beta.core.model.VpnMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMapPeriodRepositoryTest {
    @Test
    fun `accumulator keeps session and rolling five minute period deltas`() {
        val startMs = 1_000_000L
        val accumulator =
            TrafficMapConnectionAccumulator()
                .updatedWith(
                    samples = listOf(
                        TrafficMapConnectionSample(connectionId = "same", countryCode = "us", bytes = 100L),
                    ),
                    nowMs = startMs,
                )
                .updatedWith(
                    samples = listOf(
                        TrafficMapConnectionSample(connectionId = "same", countryCode = "US", bytes = 140L),
                        TrafficMapConnectionSample(connectionId = "next", countryCode = "DE", bytes = 50L),
                    ),
                    nowMs = startMs + 60_000L,
                )

        val session = accumulator.periodAggregates(TrafficMapPeriod.SESSION, nowMs = startMs + 60_000L)
        val fiveMinutes = accumulator.periodAggregates(TrafficMapPeriod.FIVE_MINUTES, nowMs = startMs + 60_000L)
        val expiredFiveMinutes =
            accumulator.periodAggregates(TrafficMapPeriod.FIVE_MINUTES, nowMs = startMs + 301_000L)

        assertEquals(140L, session["US"]?.bytes)
        assertEquals(1, session["US"]?.connections)
        assertEquals(50L, session["DE"]?.bytes)
        assertEquals(session, fiveMinutes)
        assertEquals(40L, expiredFiveMinutes["US"]?.bytes)
        assertEquals(50L, expiredFiveMinutes["DE"]?.bytes)
    }

    @Test
    fun `accumulator resets session periods when runtime becomes unavailable`() {
        val accumulator =
            TrafficMapConnectionAccumulator()
                .updatedForBatch(
                    TrafficMapSampleBatch(
                        samples = listOf(
                            TrafficMapConnectionSample(connectionId = "old", countryCode = "US", bytes = 10L),
                        ),
                        runtimeAvailable = true,
                    ),
                    nowMs = 1_000L,
                )
                .updatedForBatch(
                    TrafficMapSampleBatch(samples = emptyList(), runtimeAvailable = false),
                    nowMs = 2_000L,
                )

        assertTrue(accumulator.periodAggregates(TrafficMapPeriod.SESSION).isEmpty())
        assertTrue(accumulator.periodAggregates(TrafficMapPeriod.FIVE_MINUTES).isEmpty())
    }

    @Test
    fun `batch replacement computes deltas from previous live samples`() {
        val accumulator =
            TrafficMapConnectionAccumulator()
                .updatedForBatch(
                    TrafficMapSampleBatch(
                        samples = listOf(
                            TrafficMapConnectionSample(connectionId = "same", countryCode = "US", bytes = 100L),
                        ),
                        runtimeAvailable = true,
                    ),
                    nowMs = 1_000L,
                )
                .updatedForBatch(
                    TrafficMapSampleBatch(
                        samples = listOf(
                            TrafficMapConnectionSample(connectionId = "same", countryCode = "US", bytes = 140L),
                        ),
                        runtimeAvailable = true,
                    ),
                    nowMs = 2_000L,
                )

        val session = accumulator.periodAggregates(TrafficMapPeriod.SESSION, nowMs = 2_000L)

        assertEquals(140L, accumulator.countryAggregates()["US"]?.bytes)
        assertEquals(140L, session["US"]?.bytes)
        assertEquals(1, session["US"]?.connections)
    }

    @Test
    fun `traffic map day period aggregates only the last 24 hours`() {
        val nowMs = 10_000_000L
        val aggregates =
            trafficMapDayAggregates(
                trafficWindows = listOf(
                    trafficWindow(
                        startedAtMs = nowMs - 23 * 60 * 60 * 1000L,
                        destinationCountries = mapOf("DE" to 1_000L, "ZZ" to 400L),
                    ),
                    trafficWindow(
                        startedAtMs = nowMs - 25 * 60 * 60 * 1000L,
                        destinationCountries = mapOf("US" to 2_000L),
                    ),
                    trafficWindow(
                        startedAtMs = nowMs - 60_000L,
                        destinationCountries = mapOf("de" to 250L),
                    ),
                ),
                nowMs = nowMs,
            )

        assertEquals(1_250L, aggregates["DE"]?.bytes)
        assertEquals(2, aggregates["DE"]?.connections)
        assertEquals(400L, aggregates["ZZ"]?.bytes)
        assertEquals(null, aggregates["US"])
    }
}

private fun trafficWindow(
    startedAtMs: Long,
    destinationCountries: Map<String, Long>,
): TrafficWindow =
    TrafficWindow(
        startedAtMs = startedAtMs,
        durationSec = 60,
        networkType = NetworkType.WIFI,
        vpnMode = VpnMode.NORMAL,
        profileId = null,
        protocol = null,
        rxBytes = destinationCountries.values.sumOf { bytes -> bytes },
        txBytes = 0L,
        blockedDns = 0,
        allowedDns = 0,
        reconnects = 0,
        latencyMs = null,
        destinationCountries = destinationCountries,
    )
