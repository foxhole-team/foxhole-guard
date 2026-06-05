package com.foxhole.beta.core.traffic

import com.foxhole.beta.core.model.CountryTrafficRole
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapPointRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

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
    fun `traffic map uses shape asset registry for destination countries beyond legacy table`() {
        val requestedCountries = listOf("AE", "PT", "BE", "AT", "TH", "MY", "VN", "IL", "AR", "CL")
        val aggregates =
            requestedCountries
                .mapIndexed { index, countryCode ->
                    countryCode to
                        TrafficMapAggregate(
                            countryCode = countryCode,
                            bytes = (requestedCountries.size - index) * 1_024L,
                            connections = index + 1,
                        )
                }
                .toMap()

        val points =
            trafficMapPointsFromAggregates(
                aggregates = aggregates,
                limit = 20,
                countryRegistry = trafficMapAssetRegistry(),
            )

        assertEquals(requestedCountries, points.map(TrafficMapPoint::countryCode))
        assertTrue(points.all { point -> point.label != point.countryCode })
    }

    @Test
    fun `unsupported destination countries are retained in unknown bucket instead of fake map points`() {
        val snapshot =
            trafficMapDestinationSnapshotFromAggregates(
                aggregates =
                    mapOf(
                        "DE" to TrafficMapAggregate(countryCode = "DE", bytes = 2_048L, connections = 2),
                        "ZZ" to TrafficMapAggregate(countryCode = "ZZ", bytes = 4_096L, connections = 4),
                    ),
                limit = 20,
                countryRegistry = trafficMapAssetRegistry(),
            )

        assertEquals(listOf("DE"), snapshot.points.map(TrafficMapPoint::countryCode))
        assertEquals(4_096L, snapshot.unknownCountryBytes)
        assertEquals(4, snapshot.unknownCountryConnections)
        assertEquals(6_144L, snapshot.totalBytes)
        assertEquals(6, snapshot.totalConnections)
        assertEquals(2, snapshot.countryCount)
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
    fun `traffic map state does not draw fake origin routes when device origin is unknown`() {
        val state =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo = null,
                runtimeAvailable = true,
                destinations = listOf(trafficMapPoint("DE")),
            )

        assertNull(state.originCountryCode)
        assertEquals(0, state.edges.size)
        assertEquals("DE", state.destinations.single().countryCode)
    }

    @Test
    fun `traffic map state hides unsupported origin country instead of using fallback marker`() {
        val state =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo =
                    IpInfo(
                        ip = "198.51.100.20",
                        ipv4 = "198.51.100.20",
                        countryCode = "ZZ",
                        countryName = "Unsupported",
                        city = "Nowhere",
                        isp = "Device ISP",
                        fetchedAt = 1_000L,
                    ),
                runtimeAvailable = true,
                destinations = listOf(trafficMapPoint("DE")),
            )

        assertNull(state.originCountryCode)
        assertEquals(0, state.edges.size)
        assertEquals("DE", state.destinations.single().countryCode)
    }

    @Test
    fun `traffic map state keeps supported device origin and route edges`() {
        val state =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo =
                    IpInfo(
                        ip = "198.51.100.20",
                        ipv4 = "198.51.100.20",
                        countryCode = "US",
                        countryName = "United States",
                        city = "New York",
                        isp = "Device ISP",
                        fetchedAt = 1_000L,
                    ),
                runtimeAvailable = true,
                destinations = listOf(trafficMapPoint("DE")),
            )

        assertEquals("US", state.originCountryCode)
        assertEquals("DE", state.destinations.single().countryCode)
        assertEquals(1, state.edges.size)
        assertEquals(
            TrafficMapRepository.TrafficMapCountryCoordinates.getValue("US").lat,
            state.edges.single().fromLat,
            0.0,
        )
        assertEquals(
            TrafficMapRepository.TrafficMapCountryCoordinates.getValue("US").lon,
            state.edges.single().fromLon,
            0.0,
        )
    }

    @Test
    fun `traffic map flow retains device origin through transient refresh gaps`() =
        runBlocking {
            val originIpInfo =
                MutableStateFlow<IpInfo?>(
                    IpInfo(
                        ip = "198.51.100.20",
                        ipv4 = "198.51.100.20",
                        countryCode = "US",
                        countryName = "United States",
                        city = "New York",
                        isp = "Device ISP",
                        fetchedAt = 1_000L,
                    ),
                )
            val stateScope = CoroutineScope(Dispatchers.Default)
            try {
                val state =
                    TrafficMapRepository().trafficMapState(
                        scope = stateScope,
                        originIpInfo = originIpInfo,
                        routeIpInfo = MutableStateFlow<IpInfo?>(null),
                        torIpInfo = MutableStateFlow<IpInfo?>(null),
                        runtimeAvailable = MutableStateFlow(true),
                    )

                withTimeout(1_000L) {
                    state.filter { it.originCountryCode == "US" }.first()
                }
                originIpInfo.value = null
                delay(50L)

                assertEquals("US", state.value.originCountryCode)
                assertEquals("United States", state.value.originCountryName)
                assertEquals("New York", state.value.originCity)
            } finally {
                stateScope.cancel()
            }
        }

    @Test
    fun `traffic map state shows active vpn endpoint as route node when live samples are still empty`() {
        val state =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo =
                    IpInfo(
                        ip = "198.51.100.20",
                        ipv4 = "198.51.100.20",
                        countryCode = "US",
                        countryName = "United States",
                        city = "New York",
                        isp = "Device ISP",
                        fetchedAt = 1_000L,
                    ),
                routeIpInfo =
                    IpInfo(
                        ip = "203.0.113.20",
                        ipv4 = "203.0.113.20",
                        countryCode = "NL",
                        countryName = "Netherlands",
                        city = "Amsterdam",
                        isp = "Tunnel ISP",
                        fetchedAt = 2_000L,
                    ),
                runtimeAvailable = true,
                destinations = emptyList(),
            )

        assertEquals("US", state.originCountryCode)
        assertEquals("NL", state.vpnRoute?.countryCode)
        assertEquals(TrafficMapPointRole.VPN_ROUTE, state.vpnRoute?.role)
        assertEquals(0, state.destinations.size)
        assertEquals(1, state.edges.size)
        assertTrue(state.highlightedCountries.containsAll(listOf("US", "NL")))
    }

    @Test
    fun `traffic map state keeps active vpn endpoint separate when device origin is unknown`() {
        val state =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo = null,
                routeIpInfo =
                    IpInfo(
                        ip = "203.0.113.20",
                        ipv4 = "203.0.113.20",
                        countryCode = "NL",
                        countryName = "Netherlands",
                        city = "Amsterdam",
                        isp = "Tunnel ISP",
                        fetchedAt = 2_000L,
                    ),
                runtimeAvailable = true,
                destinations = emptyList(),
            )

        assertNull(state.originCountryCode)
        assertEquals("NL", state.vpnRoute?.countryCode)
        assertEquals(0, state.destinations.size)
        assertEquals(0, state.edges.size)
        assertTrue(state.highlightedCountries.contains("NL"))
    }

    @Test
    fun `traffic map state keeps active vpn endpoint above same country live traffic`() {
        val state =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo =
                    IpInfo(
                        ip = "198.51.100.20",
                        ipv4 = "198.51.100.20",
                        countryCode = "US",
                        countryName = "United States",
                        city = "New York",
                        isp = "Device ISP",
                        fetchedAt = 1_000L,
                    ),
                routeIpInfo =
                    IpInfo(
                        ip = "203.0.113.20",
                        ipv4 = "203.0.113.20",
                        countryCode = "DE",
                        countryName = "Germany",
                        city = "Frankfurt",
                        isp = "Tunnel ISP",
                        fetchedAt = 2_000L,
                    ),
                runtimeAvailable = true,
                destinations = listOf(trafficMapPoint("DE")),
            )

        assertEquals("DE", state.vpnRoute?.countryCode)
        assertEquals(1, state.destinations.size)
        assertEquals("DE", state.destinations.single().countryCode)
        assertEquals(2, state.edges.size)
    }

    @Test
    fun `traffic map state draws tor exit as route source when tor is active`() {
        val state =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo =
                    IpInfo(
                        ip = "198.51.100.20",
                        ipv4 = "198.51.100.20",
                        countryCode = "US",
                        countryName = "United States",
                        city = "New York",
                        isp = "Device ISP",
                        fetchedAt = 1_000L,
                    ),
                routeIpInfo =
                    IpInfo(
                        ip = "203.0.113.20",
                        ipv4 = "203.0.113.20",
                        countryCode = "NL",
                        countryName = "Netherlands",
                        city = "Amsterdam",
                        isp = "Tunnel ISP",
                        fetchedAt = 2_000L,
                    ),
                torIpInfo =
                    IpInfo(
                        ip = "203.0.113.44",
                        ipv4 = "203.0.113.44",
                        countryCode = "DE",
                        countryName = "Germany",
                        city = "Frankfurt",
                        isp = "Tor Exit",
                        fetchedAt = 2_500L,
                    ),
                runtimeAvailable = true,
                destinations = listOf(trafficMapPoint("FR")),
            )

        assertEquals("NL", state.vpnRoute?.countryCode)
        assertEquals("DE", state.torExit?.countryCode)
        assertEquals(TrafficMapPointRole.TOR_EXIT, state.torExit?.role)
        assertEquals("FR", state.destinations.single().countryCode)
        assertEquals(3, state.edges.size)
        assertTrue(state.highlightedCountries.containsAll(listOf("US", "NL", "DE", "FR")))
    }

    @Test
    fun `traffic map state exposes role based country visuals for map highlights`() {
        val state =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo =
                    IpInfo(
                        ip = "198.51.100.20",
                        ipv4 = "198.51.100.20",
                        countryCode = "US",
                        countryName = "United States",
                        city = "New York",
                        isp = "Device ISP",
                        fetchedAt = 1_000L,
                    ),
                routeIpInfo =
                    IpInfo(
                        ip = "203.0.113.20",
                        ipv4 = "203.0.113.20",
                        countryCode = "NL",
                        countryName = "Netherlands",
                        city = "Amsterdam",
                        isp = "Tunnel ISP",
                        fetchedAt = 2_000L,
                    ),
                torIpInfo =
                    IpInfo(
                        ip = "203.0.113.44",
                        ipv4 = "203.0.113.44",
                        countryCode = "DE",
                        countryName = "Germany",
                        city = "Frankfurt",
                        isp = "Tor Exit",
                        fetchedAt = 2_500L,
                    ),
                runtimeAvailable = true,
                destinations = listOf(trafficMapPoint("FR").copy(bytes = 4_096L, connections = 2)),
            )

        val rolesByCountryCode = state.countryVisuals.associate { visual -> visual.countryCode to visual.role }

        assertEquals(CountryTrafficRole.ORIGIN, rolesByCountryCode["US"])
        assertEquals(CountryTrafficRole.VPN_ROUTE, rolesByCountryCode["NL"])
        assertEquals(CountryTrafficRole.TOR_EXIT, rolesByCountryCode["DE"])
        assertEquals(CountryTrafficRole.DESTINATION, rolesByCountryCode["FR"])
        assertTrue(state.countryVisuals.all { visual -> visual.intensity in 0.18f..1f })
    }

    @Test
    fun `traffic map route rows summarize live destination sessions and traffic`() {
        val state =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo =
                    IpInfo(
                        ip = "198.51.100.20",
                        ipv4 = "198.51.100.20",
                        countryCode = "US",
                        countryName = "United States",
                        city = "New York",
                        isp = "Device ISP",
                        fetchedAt = 1_000L,
                    ),
                routeIpInfo =
                    IpInfo(
                        ip = "203.0.113.20",
                        ipv4 = "203.0.113.20",
                        countryCode = "NL",
                        countryName = "Netherlands",
                        city = "Amsterdam",
                        isp = "Tunnel ISP",
                        fetchedAt = 2_000L,
                    ),
                torIpInfo =
                    IpInfo(
                        ip = "203.0.113.44",
                        ipv4 = "203.0.113.44",
                        countryCode = "DE",
                        countryName = "Germany",
                        city = "Frankfurt",
                        isp = "Tor Exit",
                        fetchedAt = 2_500L,
                    ),
                runtimeAvailable = true,
                destinations =
                    listOf(
                        trafficMapPoint("FR").copy(bytes = 2_048L, connections = 2),
                        trafficMapPoint("CH").copy(bytes = 3_072L, connections = 3),
                    ),
            )

        assertEquals(5, state.vpnRoute?.connections)
        assertEquals(5_120L, state.vpnRoute?.bytes)
        assertEquals(5, state.torExit?.connections)
        assertEquals(5_120L, state.torExit?.bytes)
    }

    @Test
    fun `traffic map keeps canonical route coordinates when nodes share the device country`() {
        val state =
            TrafficMapRepository().trafficMapStateSnapshot(
                originIpInfo =
                    IpInfo(
                        ip = "198.51.100.20",
                        ipv4 = "198.51.100.20",
                        countryCode = "US",
                        countryName = "United States",
                        city = "New York",
                        isp = "Device ISP",
                        fetchedAt = 1_000L,
                    ),
                routeIpInfo =
                    IpInfo(
                        ip = "203.0.113.20",
                        ipv4 = "203.0.113.20",
                        countryCode = "US",
                        countryName = "United States",
                        city = "New York",
                        isp = "Tunnel ISP",
                        fetchedAt = 2_000L,
                    ),
                torIpInfo =
                    IpInfo(
                        ip = "203.0.113.44",
                        ipv4 = "203.0.113.44",
                        countryCode = "US",
                        countryName = "United States",
                        city = "New York",
                        isp = "Tor Exit",
                        fetchedAt = 2_500L,
                    ),
                runtimeAvailable = true,
                destinations = emptyList(),
            )
        val origin = TrafficMapRepository.TrafficMapCountryCoordinates.getValue("US")

        assertEquals("US", state.vpnRoute?.countryCode)
        assertEquals("US", state.torExit?.countryCode)
        assertEquals(origin.lat, state.vpnRoute?.lat ?: 0.0, 0.0)
        assertEquals(origin.lon, state.vpnRoute?.lon ?: 0.0, 0.0)
        assertEquals(origin.lat, state.torExit?.lat ?: 0.0, 0.0)
        assertEquals(origin.lon, state.torExit?.lon ?: 0.0, 0.0)
        assertEquals(2, state.edges.size)
        assertTrue(state.edges.all { edge -> edge.fromLat == origin.lat && edge.toLat == origin.lat })
        assertFalse(repositorySource().contains("offsetTrafficMapPointFromSameCountries"))
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

private fun trafficMapPoint(countryCode: String): TrafficMapPoint {
    val coordinate = TrafficMapRepository.TrafficMapCountryCoordinates.getValue(countryCode)
    return TrafficMapPoint(
        countryCode = countryCode,
        label = coordinate.label,
        lat = coordinate.lat,
        lon = coordinate.lon,
        bytes = 1_024L,
        connections = 1,
    )
}

private fun trafficMapAssetRegistry(): TrafficMapCountryRegistry {
    val asset =
        listOf(
            File("src/main/assets/$TRAFFIC_MAP_COUNTRY_SHAPES_ASSET"),
            File("app/src/main/assets/$TRAFFIC_MAP_COUNTRY_SHAPES_ASSET"),
        ).first(File::isFile)
    val shapes = TrafficMapCountryShapeAssetParser().parse(asset.readText())
    return TrafficMapCountryRegistry.fromShapes(shapes = shapes, locale = Locale.US)
}

private fun repositorySource(): String =
    listOf(
        File("src/main/kotlin/com/foxhole/beta/core/traffic/TrafficMapRepository.kt"),
        File("app/src/main/kotlin/com/foxhole/beta/core/traffic/TrafficMapRepository.kt"),
        File("../app/src/main/kotlin/com/foxhole/beta/core/traffic/TrafficMapRepository.kt"),
    ).first(File::isFile).readText()
