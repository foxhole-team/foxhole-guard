package com.foxhole.beta.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficMapRepositoryTest {
    @Test
    fun `dashboard map caps retained visible destinations for battery budget`() {
        assertEquals(30, TrafficMapRepository.MaxTrafficMapDestinations)
    }

    @Test
    fun `traffic map keeps retained connection window bounded for release memory`() {
        assertEquals(512, TrafficMapRepository.MaxRetainedConnectionSamples)
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
    fun `country byte snapshot follows visible dashboard map budget`() {
        val aggregates =
            TrafficMapRepository.TrafficMapCountryCoordinates.keys
                .mapIndexed { index, countryCode ->
                    countryCode to TrafficMapAggregate(countryCode = countryCode, bytes = index.toLong(), connections = 1)
                }
                .toMap()

        val countryBytes =
            trafficMapCountryBytesFromAggregates(
                aggregates = aggregates,
                limit = TrafficMapRepository.MaxTrafficMapDestinations,
            )

        assertEquals(TrafficMapRepository.MaxTrafficMapDestinations, countryBytes.size)
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

    @Test
    fun `accumulator drops oldest live connections above retained window`() {
        val samples =
            (0 until TrafficMapRepository.MaxRetainedConnectionSamples + 3).map { index ->
                TrafficMapConnectionSample(
                    connectionId = "connection-$index",
                    countryCode = "US",
                    bytes = index.toLong(),
                )
            }

        val accumulator = TrafficMapConnectionAccumulator().updatedWith(samples)

        assertEquals(TrafficMapRepository.MaxRetainedConnectionSamples, accumulator.samplesById.size)
        assertEquals(false, accumulator.samplesById.containsKey("connection-0"))
        assertEquals(true, accumulator.samplesById.containsKey("connection-3"))
    }

    @Test
    fun `runtime connection sample maps destination traffic to active country`() {
        val sample =
            runtimeConnectionTrafficMapSample(
                connectionId = "tcp-1",
                outboundType = "direct",
                destination = "8.8.8.8:443",
                domain = "dns.google",
                uplink = 10L,
                downlink = 20L,
                uplinkTotal = 100L,
                downlinkTotal = 200L,
                countryCodeForDestination = { candidate ->
                    if (candidate == "8.8.8.8:443" || candidate == "8.8.8.8") {
                        "us"
                    } else {
                        null
                    }
                },
            )

        assertEquals("tcp-1", sample?.connectionId)
        assertEquals("US", sample?.countryCode)
        assertEquals(300L, sample?.bytes)
        assertEquals(1, sample?.connections)
    }

    @Test
    fun `runtime connection sample ignores dns unresolved and zero byte connections`() {
        assertEquals(
            null,
            runtimeConnectionTrafficMapSample(
                connectionId = "dns-1",
                outboundType = "dns",
                destination = "8.8.8.8:53",
                domain = null,
                uplink = 10L,
                downlink = 10L,
                uplinkTotal = 0L,
                downlinkTotal = 0L,
                countryCodeForDestination = { "US" },
            ),
        )
        assertEquals(
            null,
            runtimeConnectionTrafficMapSample(
                connectionId = "tcp-2",
                outboundType = "direct",
                destination = "example.com:443",
                domain = "example.com",
                uplink = 0L,
                downlink = 0L,
                uplinkTotal = 0L,
                downlinkTotal = 0L,
                countryCodeForDestination = { null },
            ),
        )
    }
}
