package com.foxhole.beta.core.traffic

import com.foxhole.beta.core.model.CountryTrafficRole
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.TrafficMapCountryVisual
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
import kotlin.math.sqrt

class TrafficMapRepository(
    private val connectionSource: TrafficMapConnectionSource = EmptyTrafficMapConnectionSource,
    private val countryRegistryProvider: () -> TrafficMapCountryRegistry = { TrafficMapCountryRegistry.legacyFallback() },
) {
    private val retainedConnectionAccumulatorState = MutableStateFlow(TrafficMapConnectionAccumulator())

    @Volatile
    private var retainedDestinationCountryBytes: Map<String, Long> = emptyMap()

    @Volatile
    private var cachedCountryRegistry: TrafficMapCountryRegistry? = null

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
                val countryRegistry = countryRegistry()
                RetainedTrafficMapSnapshot(
                    accumulator = accumulator,
                    countryBytes =
                        trafficMapCountryBytesFromAggregates(
                            aggregates = aggregates,
                            limit = MaxTrafficMapDestinations,
                            countryRegistry = countryRegistry,
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
                    trafficMapDestinationSnapshotFromAggregates(
                        aggregates = aggregates,
                        limit = MaxTrafficMapDestinations,
                        countryRegistry = countryRegistry(),
                        lastSampleAtMs = accumulator.lastSampleAtMs,
                        newCountryCodes = accumulator.newCountryCodes,
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
                unknownCountryBytes = destinationSnapshot.unknownCountryBytes,
                unknownCountryConnections = destinationSnapshot.unknownCountryConnections,
                hiddenCountryCount = destinationSnapshot.hiddenCountryCount,
                totalBytes = destinationSnapshot.totalBytes,
                totalConnections = destinationSnapshot.totalConnections,
                countryCount = destinationSnapshot.countryCount,
                lastSampleAtMs = destinationSnapshot.lastSampleAtMs,
                newCountryCodes = destinationSnapshot.newCountryCodes,
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
        newCountryCodes: Set<String> = emptySet(),
    ): TrafficMapUiState =
        buildTrafficMapUiState(
            originInfo = trafficMapOriginInfo(originIpInfo),
            routeInfo = trafficMapOriginInfo(routeIpInfo),
            torInfo = trafficMapOriginInfo(torIpInfo),
            runtimeAvailable = runtimeAvailable,
            destinations = destinations,
            routeAggregate = trafficMapRouteAggregate(destinations),
            unknownCountryBytes = 0L,
            unknownCountryConnections = 0,
            hiddenCountryCount = 0,
            totalBytes = destinations.sumOf(TrafficMapPoint::bytes),
            totalConnections = destinations.sumOf(TrafficMapPoint::connections),
            countryCount = destinations.size,
            lastSampleAtMs = null,
            newCountryCodes = newCountryCodes,
        )

    private fun buildTrafficMapUiState(
        originInfo: TrafficMapOriginInfo?,
        routeInfo: TrafficMapOriginInfo?,
        torInfo: TrafficMapOriginInfo?,
        runtimeAvailable: Boolean,
        destinations: List<TrafficMapPoint>,
        routeAggregate: TrafficMapRouteAggregate,
        unknownCountryBytes: Long,
        unknownCountryConnections: Int,
        hiddenCountryCount: Int,
        totalBytes: Long,
        totalConnections: Int,
        countryCount: Int,
        lastSampleAtMs: Long?,
        newCountryCodes: Set<String>,
    ): TrafficMapUiState {
        val mapAnchorInfo = originInfo ?: routeInfo ?: torInfo
        val origin = mapAnchorInfo?.countryCode?.let(::trafficMapOrigin)
        val visibleDestinations =
            destinations
                .take(MaxTrafficMapDestinations)
        val vpnRoute =
            routeInfo
                ?.let { info -> trafficMapRoutePoint(info, routeAggregate) }
        val torExit =
            torInfo
                ?.let { info -> trafficMapTorPoint(info, routeAggregate) }
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
            countryVisuals =
                buildTrafficMapCountryVisuals(
                    originInfo = originInfo,
                    destinations = visibleDestinations,
                    vpnRoute = vpnRoute,
                    torExit = torExit,
                    newCountryCodes = newCountryCodes,
                ),
            sampleWindowLabel =
                trafficMapSampleWindowLabel(
                    runtimeAvailable = runtimeAvailable,
                    lastSampleAtMs = lastSampleAtMs,
                    totalConnections = totalConnections,
                ),
            lastSampleAtMs = lastSampleAtMs,
            unknownCountryBytes = unknownCountryBytes,
            unknownCountryConnections = unknownCountryConnections,
            hiddenCountryCount = hiddenCountryCount,
            totalBytes = totalBytes,
            totalConnections = totalConnections,
            countryCount = countryCount,
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

    private fun buildTrafficMapCountryVisuals(
        originInfo: TrafficMapOriginInfo?,
        destinations: List<TrafficMapPoint>,
        vpnRoute: TrafficMapPoint?,
        torExit: TrafficMapPoint?,
        newCountryCodes: Set<String>,
    ): List<TrafficMapCountryVisual> {
        val maxBytes =
            maxOf(
                destinations.maxOfOrNull(TrafficMapPoint::bytes) ?: 0L,
                vpnRoute?.bytes ?: 0L,
                torExit?.bytes ?: 0L,
                1L,
            )
        return buildList {
            originInfo?.countryCode?.let { countryCode ->
                add(
                    TrafficMapCountryVisual(
                        countryCode = countryCode,
                        bytes = 0L,
                        connections = 0,
                        role = CountryTrafficRole.ORIGIN,
                        intensity = TRAFFIC_MAP_ROUTE_COUNTRY_MIN_INTENSITY,
                        isNewCountry = false,
                        isRouteNode = true,
                    ),
                )
            }
            vpnRoute?.let { point ->
                add(point.toTrafficMapCountryVisual(CountryTrafficRole.VPN_ROUTE, maxBytes, isRouteNode = true))
            }
            torExit?.let { point ->
                add(point.toTrafficMapCountryVisual(CountryTrafficRole.TOR_EXIT, maxBytes, isRouteNode = true))
            }
            destinations.forEach { point ->
                add(
                    point.toTrafficMapCountryVisual(
                        role = CountryTrafficRole.DESTINATION,
                        maxBytes = maxBytes,
                        isRouteNode = false,
                        isNewCountry = point.countryCode in newCountryCodes,
                    ),
                )
            }
        }
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
        countryRegistry().coordinate(countryCode)

    private fun normalizeCountryCode(countryCode: String?): String? =
        countryCode
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { value -> value.length == IsoCountryCodeLength && value.all { character -> character in 'A'..'Z' } }

    private fun trafficMapOriginInfo(ipInfo: IpInfo?): TrafficMapOriginInfo? {
        val countryCode = normalizeCountryCode(ipInfo?.countryCode)
        return countryCode
            ?.takeIf { code -> countryRegistry().contains(code) }
            ?.let { code ->
                TrafficMapOriginInfo(
                    countryCode = code,
                    countryName = ipInfo?.countryName?.takeIf(String::isNotBlank) ?: countryRegistry().meta(code)?.label,
                    city = ipInfo?.city?.takeIf(String::isNotBlank),
                )
            }
    }

    private fun trafficMapRouteAggregate(destinations: List<TrafficMapPoint>): TrafficMapRouteAggregate =
        TrafficMapRouteAggregate(
            bytes = destinations.sumOf(TrafficMapPoint::bytes),
            connections = destinations.sumOf(TrafficMapPoint::connections),
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

    private fun countryRegistry(): TrafficMapCountryRegistry {
        cachedCountryRegistry?.let { registry -> return registry }
        return synchronized(this) {
            cachedCountryRegistry?.let { registry -> return@synchronized registry }
            runCatching(countryRegistryProvider)
                .getOrElse { TrafficMapCountryRegistry.legacyFallback() }
                .also { registry -> cachedCountryRegistry = registry }
        }
    }

    internal companion object {
        const val MaxTrafficMapDestinations = 30
        const val IsoCountryCodeLength = 2
        const val MaxRetainedConnectionSamples = 512
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

        val TrafficMapCountryCoordinates = TrafficMapCountryRegistry.LegacyCoordinates
    }
}

internal data class TrafficMapAggregate(
    val countryCode: String,
    val bytes: Long,
    val connections: Int,
)

internal data class TrafficMapRouteAggregate(
    val bytes: Long,
    val connections: Int,
)

internal data class TrafficMapDestinationSnapshot(
    val points: List<TrafficMapPoint>,
    val routeAggregate: TrafficMapRouteAggregate,
    val unknownCountryBytes: Long,
    val unknownCountryConnections: Int,
    val hiddenCountryCount: Int,
    val totalBytes: Long,
    val totalConnections: Int,
    val countryCount: Int,
    val lastSampleAtMs: Long?,
    val newCountryCodes: Set<String>,
)

data class TrafficMapCountryCoordinate(
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

internal data class TrafficMapSampleBatch(
    val samples: List<TrafficMapConnectionSample>,
    val runtimeAvailable: Boolean,
)

internal data class TrafficMapConnectionAccumulator(
    val samplesById: LinkedHashMap<String, TrafficMapConnectionSample> = linkedMapOf(),
    val lastSampleAtMs: Long? = null,
    val newCountryCodes: Set<String> = emptySet(),
) {
    fun updatedForBatch(batch: TrafficMapSampleBatch): TrafficMapConnectionAccumulator {
        if (!batch.runtimeAvailable) {
            return TrafficMapConnectionAccumulator()
        }
        val previousCountryCodes = countryAggregates().keys
        return TrafficMapConnectionAccumulator().updatedWith(
            samples = batch.samples,
            previousCountryCodes = previousCountryCodes,
        )
    }

    fun updatedWith(
        samples: List<TrafficMapConnectionSample>,
        previousCountryCodes: Set<String> = countryAggregates().keys,
    ): TrafficMapConnectionAccumulator {
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
        val nextCountryCodes = aggregateTrafficMapSamples(next.values.toList()).keys
        return TrafficMapConnectionAccumulator(
            samplesById = next,
            lastSampleAtMs = System.currentTimeMillis(),
            newCountryCodes = nextCountryCodes - previousCountryCodes,
        )
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
    countryRegistry: TrafficMapCountryRegistry = TrafficMapCountryRegistry.legacyFallback(),
): List<TrafficMapPoint> =
    trafficMapDestinationSnapshotFromAggregates(
        aggregates = aggregates,
        limit = limit,
        countryRegistry = countryRegistry,
        lastSampleAtMs = null,
    ).points

internal fun trafficMapCountryBytesFromAggregates(
    aggregates: Map<String, TrafficMapAggregate>,
    limit: Int,
    countryRegistry: TrafficMapCountryRegistry = TrafficMapCountryRegistry.legacyFallback(),
): Map<String, Long> =
    trafficMapPointsFromAggregates(
        aggregates = aggregates,
        limit = limit,
        countryRegistry = countryRegistry,
    )
        .associate { point -> point.countryCode to point.bytes }

internal fun trafficMapDestinationSnapshotFromAggregates(
    aggregates: Map<String, TrafficMapAggregate>,
    limit: Int,
    countryRegistry: TrafficMapCountryRegistry = TrafficMapCountryRegistry.legacyFallback(),
    lastSampleAtMs: Long? = null,
    newCountryCodes: Set<String> = emptySet(),
): TrafficMapDestinationSnapshot {
    val visibleLimit = limit.coerceAtLeast(0)
    var supportedCountryCount = 0
    var unknownCountryBytes = 0L
    var unknownCountryConnections = 0
    val points = mutableListOf<TrafficMapPoint>()
    val sortedAggregates = aggregates.values.sortedWith(TrafficMapAggregateComparator)
    sortedAggregates.forEach { aggregate ->
        val coordinate =
            normalizeTrafficMapAggregateCountryCode(aggregate.countryCode)
                ?.let(countryRegistry::coordinate)
        if (coordinate == null) {
            unknownCountryBytes += aggregate.bytes.coerceAtLeast(0L)
            unknownCountryConnections += aggregate.connections.coerceAtLeast(0)
        } else {
            supportedCountryCount += 1
            if (points.size < visibleLimit) {
                points +=
                    TrafficMapPoint(
                        countryCode = coordinate.countryCode,
                        label = coordinate.label,
                        lat = coordinate.lat,
                        lon = coordinate.lon,
                        bytes = aggregate.bytes,
                        connections = aggregate.connections,
                    )
            }
        }
    }
    val totalBytes = sortedAggregates.sumOf { aggregate -> aggregate.bytes.coerceAtLeast(0L) }
    val totalConnections = sortedAggregates.sumOf { aggregate -> aggregate.connections.coerceAtLeast(0) }
    val unknownBucketCount = if (unknownCountryBytes > 0L || unknownCountryConnections > 0) 1 else 0
    return TrafficMapDestinationSnapshot(
        points = points,
        routeAggregate = TrafficMapRouteAggregate(
            bytes = totalBytes,
            connections = totalConnections,
        ),
        unknownCountryBytes = unknownCountryBytes,
        unknownCountryConnections = unknownCountryConnections,
        hiddenCountryCount = (supportedCountryCount - points.size).coerceAtLeast(0),
        totalBytes = totalBytes,
        totalConnections = totalConnections,
        countryCount = supportedCountryCount + unknownBucketCount,
        lastSampleAtMs = lastSampleAtMs,
        newCountryCodes = newCountryCodes,
    )
}

private fun normalizeTrafficMapAggregateCountryCode(countryCode: String?): String? =
    countryCode
        ?.trim()
        ?.uppercase(Locale.US)
        ?.takeIf { value ->
            value.length == TrafficMapRepository.IsoCountryCodeLength &&
                value.all { character -> character in 'A'..'Z' }
        }

private fun trafficMapSampleWindowLabel(
    runtimeAvailable: Boolean,
    lastSampleAtMs: Long?,
    totalConnections: Int,
): String =
    when {
        !runtimeAvailable || totalConnections <= 0 -> "No active connections"
        lastSampleAtMs == null -> "Live"
        System.currentTimeMillis() - lastSampleAtMs > STALE_TRAFFIC_MAP_SAMPLE_MS -> "Stale"
        else -> "Live, last 3s"
    }

private fun TrafficMapPoint.toTrafficMapCountryVisual(
    role: CountryTrafficRole,
    maxBytes: Long,
    isRouteNode: Boolean,
    isNewCountry: Boolean = false,
): TrafficMapCountryVisual {
    val intensity =
        sqrt(bytes.coerceAtLeast(0L).toDouble() / maxBytes.coerceAtLeast(1L).toDouble())
            .toFloat()
            .coerceIn(TRAFFIC_MAP_COUNTRY_MIN_INTENSITY, 1f)
    return TrafficMapCountryVisual(
        countryCode = countryCode,
        bytes = bytes,
        connections = connections,
        role = role,
        intensity = intensity,
        isNewCountry = isNewCountry,
        isRouteNode = isRouteNode,
    )
}

private val TrafficMapAggregateComparator =
    compareByDescending<TrafficMapAggregate> { aggregate -> aggregate.bytes }
        .thenByDescending { aggregate -> aggregate.connections }
        .thenBy { aggregate -> aggregate.countryCode }

private const val STALE_TRAFFIC_MAP_SAMPLE_MS = 10_000L
private const val TRAFFIC_MAP_COUNTRY_MIN_INTENSITY = 0.18f
private const val TRAFFIC_MAP_ROUTE_COUNTRY_MIN_INTENSITY = 0.32f
