package com.foxhole.beta.core.traffic

import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.TrafficMapEdge
import com.foxhole.beta.core.model.TrafficMapEdgeRole
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapPointRole
import com.foxhole.beta.core.model.TrafficMapUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

class TrafficMapRepository(
    private val connectionSource: TrafficMapConnectionSource = EmptyTrafficMapConnectionSource,
) {
    private val retainedConnectionAccumulatorState = MutableStateFlow(TrafficMapConnectionAccumulator())

    @Volatile
    private var retainedDestinationCountryBytes: Map<String, Long> = emptyMap()

    fun trafficMapState(
        scope: CoroutineScope,
        originIpInfo: Flow<IpInfo?>,
        routeIpInfo: Flow<IpInfo?>,
        torIpInfo: Flow<IpInfo?>,
        runtimeAvailable: Flow<Boolean>,
    ): StateFlow<TrafficMapUiState> =
        trafficMapUiStateFlow(
            originIpInfo = originIpInfo,
            routeIpInfo = routeIpInfo,
            torIpInfo = torIpInfo,
            runtimeAvailable = runtimeAvailable,
        )
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = TrafficMapUiState(),
            )

    fun startDestinationCountryTracking(
        scope: CoroutineScope,
        runtimeAvailable: Flow<Boolean>,
    ): Job =
        connectionAccumulatorFlow(runtimeAvailable)
            .map { accumulator ->
                val aggregates = accumulator.countryAggregates()
                RetainedTrafficMapSnapshot(
                    accumulator = accumulator,
                    countryBytes =
                    trafficMapCountryBytesFromAggregates(
                        aggregates = aggregates,
                        limit = MaxTrafficMapDestinations,
                    ),
                )
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .onEach { snapshot ->
                retainedConnectionAccumulatorState.value = snapshot.accumulator
                retainedDestinationCountryBytes = snapshot.countryBytes
            }
            .launchIn(scope)

    fun clearDestinationCountryBytes() {
        retainedConnectionAccumulatorState.value = TrafficMapConnectionAccumulator()
        retainedDestinationCountryBytes = emptyMap()
    }

    fun currentDestinationCountryBytes(): Map<String, Long> = retainedDestinationCountryBytes

    private fun trafficMapUiStateFlow(
        originIpInfo: Flow<IpInfo?>,
        routeIpInfo: Flow<IpInfo?>,
        torIpInfo: Flow<IpInfo?>,
        runtimeAvailable: Flow<Boolean>,
    ): Flow<TrafficMapUiState> =
        combine(
            originIpInfo
                .map(::trafficMapOriginInfo)
                .distinctUntilChanged()
                .runningFold(null as TrafficMapOriginInfo?) { retained, next -> next ?: retained }
                .distinctUntilChanged(),
            routeIpInfo
                .map(::trafficMapOriginInfo)
                .distinctUntilChanged(),
            torIpInfo
                .map(::trafficMapOriginInfo)
                .distinctUntilChanged(),
            runtimeAvailable
                .distinctUntilChanged()
                .runningFold(null as Boolean?) { _, next -> next }
                .map { available -> available == true },
            retainedConnectionAccumulatorState
                .map { accumulator ->
                    val aggregates = accumulator.countryAggregates()
                    TrafficMapDestinationSnapshot(
                        points =
                            trafficMapPointsFromAggregates(
                                aggregates = aggregates,
                                limit = MaxTrafficMapDestinations,
                            ),
                        routeAggregate = trafficMapRouteAggregate(aggregates),
                    )
                }
                .distinctUntilChanged(),
        ) { originInfo, routeInfo, torInfo, available, destinationSnapshot ->
            buildTrafficMapUiState(
                originInfo = originInfo,
                routeInfo = routeInfo,
                torInfo = torInfo,
                runtimeAvailable = available,
                destinations = destinationSnapshot.points,
                routeAggregate = destinationSnapshot.routeAggregate,
            )
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    internal fun trafficMapStateSnapshot(
        originIpInfo: IpInfo?,
        routeIpInfo: IpInfo? = null,
        torIpInfo: IpInfo? = null,
        runtimeAvailable: Boolean,
        destinations: List<TrafficMapPoint>,
    ): TrafficMapUiState =
        buildTrafficMapUiState(
            originInfo = trafficMapOriginInfo(originIpInfo),
            routeInfo = trafficMapOriginInfo(routeIpInfo),
            torInfo = trafficMapOriginInfo(torIpInfo),
            runtimeAvailable = runtimeAvailable,
            destinations = destinations,
            routeAggregate = trafficMapRouteAggregate(destinations),
        )

    private fun buildTrafficMapUiState(
        originInfo: TrafficMapOriginInfo?,
        routeInfo: TrafficMapOriginInfo?,
        torInfo: TrafficMapOriginInfo?,
        runtimeAvailable: Boolean,
        destinations: List<TrafficMapPoint>,
        routeAggregate: TrafficMapRouteAggregate,
    ): TrafficMapUiState {
        val mapAnchorInfo = originInfo ?: routeInfo ?: torInfo
        val origin = mapAnchorInfo?.countryCode?.let(::trafficMapOrigin)
        val liveRouteSourceInfo = torInfo ?: routeInfo ?: originInfo
        val visibleDestinations =
            destinations
                .take(MaxTrafficMapDestinations)
                .map { point ->
                    offsetTrafficMapPointFromSameCountries(
                        point = point,
                        anchorCountryCodes = listOf(liveRouteSourceInfo?.countryCode),
                        latOffset = SameCountryDestinationLatOffset,
                        lonOffset = SameCountryDestinationLonOffset,
                    )
                }
        val vpnRoute =
            routeInfo
                ?.let { info -> trafficMapRoutePoint(info, routeAggregate) }
                ?.let { point ->
                    offsetTrafficMapPointFromSameCountries(
                        point = point,
                        anchorCountryCodes = listOf(originInfo?.countryCode),
                        latOffset = SameCountryVpnRouteLatOffset,
                        lonOffset = SameCountryVpnRouteLonOffset,
                    )
                }
        val torExit =
            torInfo
                ?.let { info -> trafficMapTorPoint(info, routeAggregate) }
                ?.let { point ->
                    offsetTrafficMapPointFromSameCountries(
                        point = point,
                        anchorCountryCodes = listOf(vpnRoute?.countryCode, originInfo?.countryCode),
                        latOffset = SameCountryTorExitLatOffset,
                        lonOffset = SameCountryTorExitLonOffset,
                    )
                }
        val highlightedCountries =
            (
                visibleDestinations.map(TrafficMapPoint::countryCode) +
                    listOfNotNull(originInfo?.countryCode, vpnRoute?.countryCode, torExit?.countryCode)
                )
                .map { countryCode -> countryCode.uppercase(Locale.US) }
                .toSet()
        return TrafficMapUiState(
            originLat = origin?.lat ?: FallbackTrafficMapOrigin.lat,
            originLon = origin?.lon ?: FallbackTrafficMapOrigin.lon,
            originCountryCode = originInfo?.countryCode,
            originCountryName = originInfo?.countryName,
            originCity = originInfo?.city,
            vpnRoute = vpnRoute,
            torExit = torExit,
            isAvailable = runtimeAvailable,
            destinations = visibleDestinations,
            edges =
                buildTrafficMapEdges(
                    origin = origin,
                    originInfo = originInfo,
                    vpnRoute = vpnRoute,
                    torExit = torExit,
                    destinations = visibleDestinations,
                ),
            highlightedCountries = highlightedCountries,
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun buildTrafficMapEdges(
        origin: TrafficMapCountryCoordinate?,
        originInfo: TrafficMapOriginInfo?,
        vpnRoute: TrafficMapPoint?,
        torExit: TrafficMapPoint?,
        destinations: List<TrafficMapPoint>,
    ): List<TrafficMapEdge> {
        val edges = mutableListOf<TrafficMapEdge>()
        if (origin != null && originInfo != null && vpnRoute != null) {
            edges +=
                TrafficMapEdge(
                    fromLat = origin.lat,
                    fromLon = origin.lon,
                    toLat = vpnRoute.lat,
                    toLon = vpnRoute.lon,
                    bytes = vpnRoute.bytes,
                    role = TrafficMapEdgeRole.VPN_ROUTE,
                )
        }

        if (torExit != null) {
            when (val torSource = vpnRoute ?: origin?.takeIf { originInfo != null }) {
                is TrafficMapPoint ->
                    edges +=
                        TrafficMapEdge(
                            fromLat = torSource.lat,
                            fromLon = torSource.lon,
                            toLat = torExit.lat,
                            toLon = torExit.lon,
                            bytes = torExit.bytes,
                            role = TrafficMapEdgeRole.TOR_ROUTE,
                        )
                is TrafficMapCountryCoordinate ->
                    edges +=
                        TrafficMapEdge(
                            fromLat = torSource.lat,
                            fromLon = torSource.lon,
                            toLat = torExit.lat,
                            toLon = torExit.lon,
                            bytes = torExit.bytes,
                            role = TrafficMapEdgeRole.TOR_ROUTE,
                        )
            }
        }

        when (val liveRouteSource = torExit ?: vpnRoute ?: origin?.takeIf { originInfo != null }) {
            is TrafficMapPoint -> {
                val edgeRole = if (torExit != null) TrafficMapEdgeRole.TOR_ROUTE else TrafficMapEdgeRole.VPN_ROUTE
                destinations.forEach { point ->
                    edges +=
                        TrafficMapEdge(
                            fromLat = liveRouteSource.lat,
                            fromLon = liveRouteSource.lon,
                            toLat = point.lat,
                            toLon = point.lon,
                            bytes = point.bytes,
                            role = edgeRole,
                        )
                }
            }
            is TrafficMapCountryCoordinate ->
                destinations.forEach { point ->
                    edges +=
                        TrafficMapEdge(
                            fromLat = liveRouteSource.lat,
                            fromLon = liveRouteSource.lon,
                            toLat = point.lat,
                            toLon = point.lon,
                            bytes = point.bytes,
                            role = TrafficMapEdgeRole.DIRECT,
                        )
                }
        }
        return edges
    }

    private fun connectionAccumulatorFlow(runtimeAvailable: Flow<Boolean>): Flow<TrafficMapConnectionAccumulator> {
        val runtime = runtimeAvailable.distinctUntilChanged()
        return connectionSource
            .connectionSamples(runtime)
            .combine(runtime) { samples, available ->
                TrafficMapSampleBatch(samples = samples, runtimeAvailable = available)
            }
            .runningFold(TrafficMapConnectionAccumulator()) { accumulator, batch ->
                accumulator.updatedForBatch(batch)
            }
    }

    private fun trafficMapOrigin(countryCode: String): TrafficMapCountryCoordinate? =
        TrafficMapCountryCoordinates[countryCode]

    private fun normalizeCountryCode(countryCode: String?): String? =
        countryCode
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { value -> value.length == IsoCountryCodeLength && value.all { character -> character in 'A'..'Z' } }

    private fun trafficMapOriginInfo(ipInfo: IpInfo?): TrafficMapOriginInfo? {
        val countryCode = normalizeCountryCode(ipInfo?.countryCode)
        return countryCode
            ?.takeIf(TrafficMapCountryCoordinates::containsKey)
            ?.let { code ->
                TrafficMapOriginInfo(
                    countryCode = code,
                    countryName = ipInfo?.countryName?.takeIf(String::isNotBlank),
                    city = ipInfo?.city?.takeIf(String::isNotBlank),
                )
            }
    }

    private fun trafficMapRouteAggregate(destinations: List<TrafficMapPoint>): TrafficMapRouteAggregate =
        TrafficMapRouteAggregate(
            bytes = destinations.sumOf(TrafficMapPoint::bytes),
            connections = destinations.sumOf(TrafficMapPoint::connections),
        )

    private fun trafficMapRouteAggregate(aggregates: Map<String, TrafficMapAggregate>): TrafficMapRouteAggregate =
        TrafficMapRouteAggregate(
            bytes = aggregates.values.sumOf(TrafficMapAggregate::bytes),
            connections = aggregates.values.sumOf(TrafficMapAggregate::connections),
        )

    private fun trafficMapRoutePoint(
        routeInfo: TrafficMapOriginInfo,
        aggregate: TrafficMapRouteAggregate,
    ): TrafficMapPoint? {
        val coordinate = trafficMapOrigin(routeInfo.countryCode) ?: return null
        return TrafficMapPoint(
            countryCode = coordinate.countryCode,
            label = routeInfo.countryName ?: coordinate.label,
            lat = coordinate.lat,
            lon = coordinate.lon,
            bytes = aggregate.bytes,
            connections = aggregate.connections,
            role = TrafficMapPointRole.VPN_ROUTE,
        )
    }

    private fun trafficMapTorPoint(
        torInfo: TrafficMapOriginInfo,
        aggregate: TrafficMapRouteAggregate,
    ): TrafficMapPoint? {
        val coordinate = trafficMapOrigin(torInfo.countryCode) ?: return null
        return TrafficMapPoint(
            countryCode = coordinate.countryCode,
            label = torInfo.countryName ?: coordinate.label,
            lat = coordinate.lat,
            lon = coordinate.lon,
            bytes = aggregate.bytes,
            connections = aggregate.connections,
            role = TrafficMapPointRole.TOR_EXIT,
        )
    }

    private data class TrafficMapRouteAggregate(
        val bytes: Long,
        val connections: Int,
    )

    private data class TrafficMapDestinationSnapshot(
        val points: List<TrafficMapPoint>,
        val routeAggregate: TrafficMapRouteAggregate,
    )

    internal companion object {
        const val MaxTrafficMapDestinations = 30
        const val IsoCountryCodeLength = 2
        const val MaxRetainedConnectionSamples = 512
        const val SameCountryDestinationLatOffset = 1.15
        const val SameCountryDestinationLonOffset = 1.85
        const val SameCountryVpnRouteLatOffset = 1.65
        const val SameCountryVpnRouteLonOffset = 2.55
        const val SameCountryTorExitLatOffset = -1.75
        const val SameCountryTorExitLonOffset = 2.65
        const val TrafficMapMinLat = -55.0
        const val TrafficMapMaxLat = 85.0
        const val TrafficMapMinLon = -179.0
        const val TrafficMapMaxLon = 179.0

        val FallbackTrafficMapOrigin =
            TrafficMapCountryCoordinate(
                countryCode = "EU",
                label = "Europe",
                lat = 48.8566,
                lon = 2.3522,
            )

        val TrafficMapCountryCoordinates =
            listOf(
                TrafficMapCountryCoordinate("AU", "Australia", -25.0, 133.0),
                TrafficMapCountryCoordinate("BR", "Brazil", -10.0, -55.0),
                TrafficMapCountryCoordinate("CA", "Canada", 56.0, -106.0),
                TrafficMapCountryCoordinate("CH", "Switzerland", 46.8, 8.2),
                TrafficMapCountryCoordinate("CN", "China", 35.0, 103.0),
                TrafficMapCountryCoordinate("DE", "Germany", 51.0, 10.0),
                TrafficMapCountryCoordinate("ES", "Spain", 40.0, -4.0),
                TrafficMapCountryCoordinate("FI", "Finland", 64.0, 26.0),
                TrafficMapCountryCoordinate("FR", "France", 46.0, 2.0),
                TrafficMapCountryCoordinate("GB", "United Kingdom", 54.0, -2.0),
                TrafficMapCountryCoordinate("HK", "Hong Kong", 22.3193, 114.1694),
                TrafficMapCountryCoordinate("ID", "Indonesia", -2.0, 118.0),
                TrafficMapCountryCoordinate("IE", "Ireland", 53.0, -8.0),
                TrafficMapCountryCoordinate("IN", "India", 22.0, 79.0),
                TrafficMapCountryCoordinate("IT", "Italy", 42.5, 12.5),
                TrafficMapCountryCoordinate("JP", "Japan", 37.0, 138.0),
                TrafficMapCountryCoordinate("KR", "South Korea", 36.0, 128.0),
                TrafficMapCountryCoordinate("MX", "Mexico", 23.0, -102.0),
                TrafficMapCountryCoordinate("NL", "Netherlands", 52.1, 5.3),
                TrafficMapCountryCoordinate("NO", "Norway", 61.0, 8.0),
                TrafficMapCountryCoordinate("PL", "Poland", 52.0, 19.0),
                TrafficMapCountryCoordinate("RO", "Romania", 45.8, 25.0),
                TrafficMapCountryCoordinate("RU", "Russia", 61.0, 105.0),
                TrafficMapCountryCoordinate("SE", "Sweden", 62.0, 15.0),
                TrafficMapCountryCoordinate("SG", "Singapore", 1.3521, 103.8198),
                TrafficMapCountryCoordinate("TR", "Turkey", 39.0, 35.0),
                TrafficMapCountryCoordinate("TW", "Taiwan", 23.7, 121.0),
                TrafficMapCountryCoordinate("UA", "Ukraine", 49.0, 32.0),
                TrafficMapCountryCoordinate("US", "United States", 39.8, -98.6),
                TrafficMapCountryCoordinate("ZA", "South Africa", -30.0, 24.0),
                TrafficMapCountryCoordinate("EU", "Europe", 50.0, 10.0),
            ).associateBy(TrafficMapCountryCoordinate::countryCode)
    }
}

internal data class TrafficMapAggregate(
    val countryCode: String,
    val bytes: Long,
    val connections: Int,
)

internal data class TrafficMapCountryCoordinate(
    val countryCode: String,
    val label: String,
    val lat: Double,
    val lon: Double,
)

private data class TrafficMapOriginInfo(
    val countryCode: String,
    val countryName: String?,
    val city: String?,
)

private data class RetainedTrafficMapSnapshot(
    val accumulator: TrafficMapConnectionAccumulator,
    val countryBytes: Map<String, Long>,
)

private fun offsetTrafficMapPointFromSameCountries(
    point: TrafficMapPoint,
    anchorCountryCodes: List<String?>,
    latOffset: Double,
    lonOffset: Double,
): TrafficMapPoint {
    val anchorCountries =
        anchorCountryCodes
            .mapNotNull { countryCode ->
                countryCode
                    ?.trim()
                    ?.uppercase(Locale.US)
                    ?.takeIf { value ->
                        value.length == TrafficMapRepository.IsoCountryCodeLength &&
                            value.all { character -> character in 'A'..'Z' }
                    }
            }.toSet()
    return if (point.countryCode.uppercase(Locale.US) in anchorCountries) {
        point.copy(
            lat = (point.lat + latOffset)
                .coerceIn(TrafficMapRepository.TrafficMapMinLat, TrafficMapRepository.TrafficMapMaxLat),
            lon = (point.lon + lonOffset)
                .coerceIn(TrafficMapRepository.TrafficMapMinLon, TrafficMapRepository.TrafficMapMaxLon),
        )
    } else {
        point
    }
}

internal data class TrafficMapSampleBatch(
    val samples: List<TrafficMapConnectionSample>,
    val runtimeAvailable: Boolean,
)

internal data class TrafficMapConnectionAccumulator(
    val samplesById: LinkedHashMap<String, TrafficMapConnectionSample> = linkedMapOf(),
) {
    fun updatedForBatch(batch: TrafficMapSampleBatch): TrafficMapConnectionAccumulator {
        if (!batch.runtimeAvailable) {
            return TrafficMapConnectionAccumulator()
        }
        return TrafficMapConnectionAccumulator().updatedWith(batch.samples)
    }

    fun updatedWith(samples: List<TrafficMapConnectionSample>): TrafficMapConnectionAccumulator {
        if (samples.isEmpty()) {
            return this
        }
        val next = LinkedHashMap(samplesById)
        samples.forEach { sample ->
            val existing = next[sample.connectionId]
            next[sample.connectionId] =
                if (existing == null || sample.bytes >= existing.bytes) {
                    sample
                } else {
                    existing
                }
        }
        while (next.size > TrafficMapRepository.MaxRetainedConnectionSamples) {
            val oldestKey = next.keys.firstOrNull() ?: break
            next.remove(oldestKey)
        }
        return TrafficMapConnectionAccumulator(samplesById = next)
    }

    fun countryAggregates(): Map<String, TrafficMapAggregate> =
        aggregateTrafficMapSamples(samplesById.values.toList())
}

internal fun aggregateTrafficMapSamples(
    samples: List<TrafficMapConnectionSample>,
): Map<String, TrafficMapAggregate> {
    val next = linkedMapOf<String, TrafficMapAggregate>()
    samples.forEach { sample ->
        val countryCode = sample.countryCode.uppercase(Locale.US)
        val current = next[countryCode]
        next[countryCode] =
            TrafficMapAggregate(
                countryCode = countryCode,
                bytes = (current?.bytes ?: 0L) + sample.bytes.coerceAtLeast(0L),
                connections = (current?.connections ?: 0) + sample.connections.coerceAtLeast(0),
            )
    }
    return next
}

internal fun trafficMapPointsFromAggregates(
    aggregates: Map<String, TrafficMapAggregate>,
    limit: Int,
): List<TrafficMapPoint> =
    aggregates.values
        .sortedWith(
            compareByDescending<TrafficMapAggregate> { aggregate -> aggregate.bytes }
                .thenByDescending { aggregate -> aggregate.connections }
                .thenBy { aggregate -> aggregate.countryCode },
        )
        .mapNotNull { aggregate ->
            TrafficMapRepository.TrafficMapCountryCoordinates[aggregate.countryCode]?.let { coordinate ->
                TrafficMapPoint(
                    countryCode = aggregate.countryCode,
                    label = coordinate.label,
                    lat = coordinate.lat,
                    lon = coordinate.lon,
                    bytes = aggregate.bytes,
                    connections = aggregate.connections,
                )
            }
        }
        .take(limit.coerceAtLeast(0))

internal fun trafficMapCountryBytesFromAggregates(
    aggregates: Map<String, TrafficMapAggregate>,
    limit: Int,
): Map<String, Long> =
    trafficMapPointsFromAggregates(aggregates, limit)
        .associate { point -> point.countryCode to point.bytes }
