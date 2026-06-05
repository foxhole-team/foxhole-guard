package com.foxhole.beta.core.traffic

import com.foxhole.beta.core.model.CountryTrafficRole
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.NetworkActivityEvent
import com.foxhole.beta.core.model.TrafficMapCountryAppRow
import com.foxhole.beta.core.model.TrafficMapCountryDetail
import com.foxhole.beta.core.model.TrafficMapCountryHostRow
import com.foxhole.beta.core.model.TrafficMapCountryVisual
import com.foxhole.beta.core.model.TrafficMapEdge
import com.foxhole.beta.core.model.TrafficMapEdgeRole
import com.foxhole.beta.core.model.TrafficMapPeriod
import com.foxhole.beta.core.model.TrafficMapPeriodSnapshot
import com.foxhole.beta.core.model.TrafficMapPeriodSnapshots
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapPointRole
import com.foxhole.beta.core.model.TrafficMapUiState
import com.foxhole.beta.core.model.TrafficWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
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
    private val nowProvider: () -> Long = System::currentTimeMillis,
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
        recentTrafficWindows: Flow<List<TrafficWindow>> = flowOf(emptyList()),
        recentNetworkActivityEvents: Flow<List<NetworkActivityEvent>> = flowOf(emptyList()),
        showPrivateNetworkDetails: Flow<Boolean> = flowOf(false),
    ): StateFlow<TrafficMapUiState> =
        trafficMapUiStateFlow(
            originIpInfo = originIpInfo,
            routeIpInfo = routeIpInfo,
            torIpInfo = torIpInfo,
            runtimeAvailable = runtimeAvailable,
            recentTrafficWindows = recentTrafficWindows,
            recentNetworkActivityEvents = recentNetworkActivityEvents,
            showPrivateNetworkDetails = showPrivateNetworkDetails,
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
        recentTrafficWindows: Flow<List<TrafficWindow>>,
        recentNetworkActivityEvents: Flow<List<NetworkActivityEvent>>,
        showPrivateNetworkDetails: Flow<Boolean>,
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
                .combine(recentTrafficWindows) { accumulator, trafficWindows ->
                    accumulator to trafficWindows
                }
                .combine(recentNetworkActivityEvents) { (accumulator, trafficWindows), networkActivityEvents ->
                    (accumulator to trafficWindows) to networkActivityEvents
                }
                .combine(showPrivateNetworkDetails.distinctUntilChanged()) { combinedTraffic, showPrivateDetails ->
                    val (accumulatorAndWindows, networkActivityEvents) = combinedTraffic
                    val (accumulator, trafficWindows) = accumulatorAndWindows
                    trafficMapDestinationBundle(
                        accumulator = accumulator,
                        recentTrafficWindows = trafficWindows,
                        networkActivityEvents = networkActivityEvents,
                        includeHostDetails = showPrivateDetails,
                        nowMs = nowProvider(),
                    )
                }
                .distinctUntilChanged(),
        ) { originInfo, routeInfo, torInfo, available, destinationBundle ->
            buildTrafficMapUiState(
                originInfo = originInfo,
                routeInfo = routeInfo,
                torInfo = torInfo,
                runtimeAvailable = available,
                destinationSnapshot = destinationBundle.liveSnapshot,
                periodSnapshots = destinationBundle.periodSnapshots,
                countryDetailsByCode = destinationBundle.countryDetailsByCode,
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
            destinationSnapshot =
                trafficMapDestinationSnapshotFromPoints(destinations)
                    .copy(newCountryCodes = newCountryCodes),
            periodSnapshots =
                trafficMapPeriodSnapshotsFromLive(
                    trafficMapDestinationSnapshotFromPoints(destinations),
                ),
            countryDetailsByCode = emptyMap(),
        )

    private fun buildTrafficMapUiState(
        originInfo: TrafficMapOriginInfo?,
        routeInfo: TrafficMapOriginInfo?,
        torInfo: TrafficMapOriginInfo?,
        runtimeAvailable: Boolean,
        destinationSnapshot: TrafficMapDestinationSnapshot,
        periodSnapshots: TrafficMapPeriodSnapshots,
        countryDetailsByCode: Map<String, TrafficMapCountryDetail>,
    ): TrafficMapUiState {
        val mapAnchorInfo = originInfo ?: routeInfo ?: torInfo
        val origin = mapAnchorInfo?.countryCode?.let(::trafficMapOrigin)
        val visibleDestinations =
            destinationSnapshot.points
                .take(MaxTrafficMapDestinations)
        val vpnRoute =
            routeInfo
                ?.let { info -> trafficMapRoutePoint(info, destinationSnapshot.routeAggregate) }
        val torExit =
            torInfo
                ?.let { info -> trafficMapTorPoint(info, destinationSnapshot.routeAggregate) }
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
                    newCountryCodes = destinationSnapshot.newCountryCodes,
                ),
            sampleWindowLabel =
                trafficMapSampleWindowLabel(
                    runtimeAvailable = runtimeAvailable,
                    lastSampleAtMs = destinationSnapshot.lastSampleAtMs,
                    totalConnections = destinationSnapshot.totalConnections,
                ),
            periodSnapshots = periodSnapshots,
            countryDetailsByCode = countryDetailsByCode,
            lastSampleAtMs = destinationSnapshot.lastSampleAtMs,
            unknownCountryBytes = destinationSnapshot.unknownCountryBytes,
            unknownCountryConnections = destinationSnapshot.unknownCountryConnections,
            hiddenCountryCount = destinationSnapshot.hiddenCountryCount,
            totalBytes = destinationSnapshot.totalBytes,
            totalConnections = destinationSnapshot.totalConnections,
            countryCount = destinationSnapshot.countryCount,
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
                accumulator.updatedForBatch(batch, nowMs = nowProvider())
            }
    }

    private fun trafficMapDestinationBundle(
        accumulator: TrafficMapConnectionAccumulator,
        recentTrafficWindows: List<TrafficWindow>,
        networkActivityEvents: List<NetworkActivityEvent>,
        includeHostDetails: Boolean,
        nowMs: Long,
    ): TrafficMapDestinationBundle {
        val countryRegistry = countryRegistry()
        val liveSnapshot =
            trafficMapDestinationSnapshotFromAggregates(
                aggregates = accumulator.countryAggregates(),
                limit = MaxTrafficMapDestinations,
                countryRegistry = countryRegistry,
                lastSampleAtMs = accumulator.lastSampleAtMs,
                newCountryCodes = accumulator.newCountryCodes,
            )
        val fiveMinuteSnapshot =
            trafficMapDestinationSnapshotFromAggregates(
                aggregates = accumulator.periodAggregates(TrafficMapPeriod.FIVE_MINUTES, nowMs),
                limit = MaxTrafficMapDestinations,
                countryRegistry = countryRegistry,
                lastSampleAtMs = accumulator.lastSampleAtMs,
                newCountryCodes = accumulator.newCountryCodes,
            ).toPeriodSnapshot(
                period = TrafficMapPeriod.FIVE_MINUTES,
                sampleWindowLabel = TRAFFIC_MAP_PERIOD_FIVE_MINUTES_LABEL,
            )
        val sessionSnapshot =
            trafficMapDestinationSnapshotFromAggregates(
                aggregates = accumulator.periodAggregates(TrafficMapPeriod.SESSION, nowMs),
                limit = MaxTrafficMapDestinations,
                countryRegistry = countryRegistry,
                lastSampleAtMs = accumulator.lastSampleAtMs,
                newCountryCodes = accumulator.newCountryCodes,
            ).toPeriodSnapshot(
                period = TrafficMapPeriod.SESSION,
                sampleWindowLabel = TRAFFIC_MAP_PERIOD_SESSION_LABEL,
            )
        val daySnapshot =
            trafficMapDestinationSnapshotFromAggregates(
                aggregates = trafficMapDayAggregates(recentTrafficWindows, nowMs),
                limit = MaxTrafficMapDestinations,
                countryRegistry = countryRegistry,
                lastSampleAtMs = recentTrafficWindows.maxOfOrNull(TrafficWindow::startedAtMs),
            ).toPeriodSnapshot(
                period = TrafficMapPeriod.DAY_24,
                sampleWindowLabel = TRAFFIC_MAP_PERIOD_DAY_24_LABEL,
            )
        return TrafficMapDestinationBundle(
            liveSnapshot = liveSnapshot,
            periodSnapshots =
                TrafficMapPeriodSnapshots(
                    fiveMinutes = fiveMinuteSnapshot,
                    session = sessionSnapshot,
                    day24 = daySnapshot,
                ),
            countryDetailsByCode =
                trafficMapCountryDetailsFromNetworkActivity(
                    events = networkActivityEvents,
                    includeHostDetails = includeHostDetails,
                ),
        )
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
        const val MaxTrafficMapCountryDetailRows = 5
        const val IsoCountryCodeLength = 2
        const val MaxRetainedConnectionSamples = 512
        const val MaxTrafficMapPeriodBuckets = 120
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

private data class TrafficMapDestinationBundle(
    val liveSnapshot: TrafficMapDestinationSnapshot,
    val periodSnapshots: TrafficMapPeriodSnapshots,
    val countryDetailsByCode: Map<String, TrafficMapCountryDetail>,
)

private class MutableTrafficMapCountryDetail(
    val countryCode: String,
) {
    val appRows: LinkedHashMap<String, MutableTrafficMapCountryAppAccumulator> = linkedMapOf()
    val hostRows: LinkedHashMap<TrafficMapCountryHostKey, MutableTrafficMapCountryHostAccumulator> = linkedMapOf()
    var firstSeenAtMs: Long? = null
        private set
    var lastSeenAtMs: Long? = null
        private set

    fun observe(timestampMs: Long) {
        firstSeenAtMs = firstSeenAtMs?.let { current -> minOf(current, timestampMs) } ?: timestampMs
        lastSeenAtMs = lastSeenAtMs?.let { current -> maxOf(current, timestampMs) } ?: timestampMs
    }

    fun toDetail(
        appLimit: Int,
        hostLimit: Int,
    ): TrafficMapCountryDetail {
        val sortedAppRows =
            appRows.values
                .map(MutableTrafficMapCountryAppAccumulator::toRow)
                .sortedWith(TrafficMapCountryAppRowComparator)
                .take(appLimit.coerceAtLeast(0))
        val sortedHostRows =
            hostRows.values
                .map(MutableTrafficMapCountryHostAccumulator::toRow)
                .sortedWith(TrafficMapCountryHostRowComparator)
                .take(hostLimit.coerceAtLeast(0))
        return TrafficMapCountryDetail(
            countryCode = countryCode,
            appRows = sortedAppRows,
            hostRows = sortedHostRows,
            firstSeenAtMs = firstSeenAtMs,
            lastSeenAtMs = lastSeenAtMs,
        )
    }
}

private class MutableTrafficMapCountryAppAccumulator(
    private val packageName: String,
) {
    private var bytes: Long = 0L
    private var connections: Int = 0
    private var firstSeenAtMs: Long = Long.MAX_VALUE
    private var lastSeenAtMs: Long = Long.MIN_VALUE

    fun add(
        bytes: Long,
        timestampMs: Long,
    ) {
        this.bytes += bytes.coerceAtLeast(0L)
        connections += 1
        firstSeenAtMs = minOf(firstSeenAtMs, timestampMs)
        lastSeenAtMs = maxOf(lastSeenAtMs, timestampMs)
    }

    fun toRow(): TrafficMapCountryAppRow =
        TrafficMapCountryAppRow(
            packageName = packageName,
            bytes = bytes,
            connections = connections,
            firstSeenAtMs = firstSeenAtMs.takeUnless { value -> value == Long.MAX_VALUE } ?: 0L,
            lastSeenAtMs = lastSeenAtMs.takeUnless { value -> value == Long.MIN_VALUE } ?: 0L,
        )
}

private class MutableTrafficMapCountryHostAccumulator(
    private val key: TrafficMapCountryHostKey,
) {
    private var bytes: Long = 0L
    private var connections: Int = 0
    private val packageNames = linkedSetOf<String>()
    private var firstSeenAtMs: Long = Long.MAX_VALUE
    private var lastSeenAtMs: Long = Long.MIN_VALUE

    fun add(
        bytes: Long,
        timestampMs: Long,
        packageNames: List<String>,
    ) {
        this.bytes += bytes.coerceAtLeast(0L)
        connections += 1
        this.packageNames += packageNames
        firstSeenAtMs = minOf(firstSeenAtMs, timestampMs)
        lastSeenAtMs = maxOf(lastSeenAtMs, timestampMs)
    }

    fun toRow(): TrafficMapCountryHostRow =
        TrafficMapCountryHostRow(
            remoteHost = key.remoteHost,
            remotePort = key.remotePort,
            protocol = key.protocol,
            bytes = bytes,
            connections = connections,
            appCount = packageNames.size,
            firstSeenAtMs = firstSeenAtMs.takeUnless { value -> value == Long.MAX_VALUE } ?: 0L,
            lastSeenAtMs = lastSeenAtMs.takeUnless { value -> value == Long.MIN_VALUE } ?: 0L,
        )
}

private data class TrafficMapCountryHostKey(
    val remoteHost: String,
    val remotePort: Int?,
    val protocol: String,
)

internal data class TrafficMapSampleBatch(
    val samples: List<TrafficMapConnectionSample>,
    val runtimeAvailable: Boolean,
)

internal data class TrafficMapPeriodBucket(
    val timestampMs: Long,
    val bytesByCountry: Map<String, Long>,
    val connectionIdsByCountry: Map<String, Set<String>>,
)

internal data class TrafficMapConnectionAccumulator(
    val samplesById: LinkedHashMap<String, TrafficMapConnectionSample> = linkedMapOf(),
    val lastSampleAtMs: Long? = null,
    val newCountryCodes: Set<String> = emptySet(),
    val sessionBytesByCountry: Map<String, Long> = emptyMap(),
    val sessionConnectionIdsByCountry: Map<String, Set<String>> = emptyMap(),
    val periodBuckets: List<TrafficMapPeriodBucket> = emptyList(),
) {
    fun updatedForBatch(
        batch: TrafficMapSampleBatch,
        nowMs: Long = System.currentTimeMillis(),
    ): TrafficMapConnectionAccumulator {
        if (!batch.runtimeAvailable) {
            return TrafficMapConnectionAccumulator()
        }
        val previousCountryCodes = countryAggregates().keys
        return updatedWith(
            samples = batch.samples,
            previousCountryCodes = previousCountryCodes,
            nowMs = nowMs,
            replaceLiveSamples = true,
        )
    }

    fun updatedWith(
        samples: List<TrafficMapConnectionSample>,
        previousCountryCodes: Set<String> = countryAggregates().keys,
        nowMs: Long = System.currentTimeMillis(),
        replaceLiveSamples: Boolean = false,
    ): TrafficMapConnectionAccumulator {
        if (samples.isEmpty()) {
            return copy(periodBuckets = periodBuckets.prunedTrafficMapPeriodBuckets(nowMs))
        }
        val previousSamples = samplesById
        val next = if (replaceLiveSamples) LinkedHashMap() else LinkedHashMap(samplesById)
        val sampleDeltas = mutableListOf<TrafficMapConnectionSample>()
        val connectionIdsByCountry = linkedMapOf<String, MutableSet<String>>()
        samples.forEach { sample ->
            val existing = previousSamples[sample.connectionId]
            val liveExisting = existing.takeUnless { replaceLiveSamples }
            val deltaBytes = sample.deltaBytes(previous = existing)
            val countryCode = sample.countryCode.uppercase(Locale.US)
            if (deltaBytes > 0L) {
                sampleDeltas += sample.copy(countryCode = countryCode, bytes = deltaBytes)
                connectionIdsByCountry
                    .getOrPut(countryCode) { linkedSetOf() }
                    .add(sample.connectionId)
            }
            next[sample.connectionId] =
                if (liveExisting == null || sample.bytes >= liveExisting.bytes) {
                    sample
                } else {
                    liveExisting
                }
        }
        while (next.size > TrafficMapRepository.MaxRetainedConnectionSamples) {
            val oldestKey = next.keys.firstOrNull() ?: break
            next.remove(oldestKey)
        }
        val nextCountryCodes = aggregateTrafficMapSamples(next.values.toList()).keys
        val deltaBytesByCountry =
            sampleDeltas
                .groupBy(TrafficMapConnectionSample::countryCode)
                .mapValues { (_, values) -> values.sumOf { sample -> sample.bytes.coerceAtLeast(0L) } }
        val nextSessionBytes =
            sessionBytesByCountry.addTrafficMapCountryBytes(deltaBytesByCountry)
        val nextSessionConnections =
            sessionConnectionIdsByCountry.addTrafficMapCountryConnectionIds(connectionIdsByCountry)
        val nextBuckets =
            if (deltaBytesByCountry.isEmpty()) {
                periodBuckets.prunedTrafficMapPeriodBuckets(nowMs)
            } else {
                (
                    periodBuckets +
                        TrafficMapPeriodBucket(
                            timestampMs = nowMs,
                            bytesByCountry = deltaBytesByCountry,
                            connectionIdsByCountry = connectionIdsByCountry,
                        )
                    )
                    .prunedTrafficMapPeriodBuckets(nowMs)
                    .takeLast(TrafficMapRepository.MaxTrafficMapPeriodBuckets)
            }
        return TrafficMapConnectionAccumulator(
            samplesById = next,
            lastSampleAtMs = nowMs,
            newCountryCodes = nextCountryCodes - previousCountryCodes,
            sessionBytesByCountry = nextSessionBytes,
            sessionConnectionIdsByCountry = nextSessionConnections,
            periodBuckets = nextBuckets,
        )
    }

    fun countryAggregates(): Map<String, TrafficMapAggregate> =
        aggregateTrafficMapSamples(samplesById.values.toList())

    fun periodAggregates(
        period: TrafficMapPeriod,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<String, TrafficMapAggregate> =
        when (period) {
            TrafficMapPeriod.FIVE_MINUTES ->
                periodBuckets
                    .prunedTrafficMapPeriodBuckets(nowMs)
                    .toTrafficMapAggregates()
            TrafficMapPeriod.SESSION ->
                trafficMapAggregatesFromBytesAndConnectionIds(
                    bytesByCountry = sessionBytesByCountry,
                    connectionIdsByCountry = sessionConnectionIdsByCountry,
                )
            TrafficMapPeriod.DAY_24 -> emptyMap()
        }
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

internal fun trafficMapDayAggregates(
    trafficWindows: List<TrafficWindow>,
    nowMs: Long = System.currentTimeMillis(),
): Map<String, TrafficMapAggregate> {
    val cutoffMs = nowMs - TRAFFIC_MAP_DAY_24_MS
    val bytesByCountry = linkedMapOf<String, Long>()
    val connectionCountsByCountry = linkedMapOf<String, Int>()
    trafficWindows
        .asSequence()
        .filter { window -> window.startedAtMs >= cutoffMs }
        .forEach { window ->
            window.destinationCountries.forEach { (rawCountryCode, rawBytes) ->
                val countryCode =
                    normalizeTrafficMapAggregateCountryCode(rawCountryCode)
                        ?: rawCountryCode.trim().uppercase(Locale.US)
                val bytes = rawBytes.coerceAtLeast(0L)
                if (bytes > 0L) {
                    bytesByCountry[countryCode] = (bytesByCountry[countryCode] ?: 0L) + bytes
                    connectionCountsByCountry[countryCode] = (connectionCountsByCountry[countryCode] ?: 0) + 1
                }
            }
        }
    return bytesByCountry.mapValues { (countryCode, bytes) ->
        TrafficMapAggregate(
            countryCode = countryCode,
            bytes = bytes,
            connections = connectionCountsByCountry[countryCode] ?: 0,
        )
    }
}

internal fun trafficMapCountryDetailsFromNetworkActivity(
    events: List<NetworkActivityEvent>,
    appLimit: Int = TrafficMapRepository.MaxTrafficMapCountryDetailRows,
    hostLimit: Int = TrafficMapRepository.MaxTrafficMapCountryDetailRows,
    includeHostDetails: Boolean = true,
): Map<String, TrafficMapCountryDetail> {
    if (events.isEmpty()) {
        return emptyMap()
    }
    val countries = linkedMapOf<String, MutableTrafficMapCountryDetail>()
    events.forEach { event ->
        val countryCode = normalizeTrafficMapAggregateCountryCode(event.countryCode) ?: return@forEach
        val bytes = event.totalBytes.coerceAtLeast(0L)
        if (bytes <= 0L) {
            return@forEach
        }
        val country =
            countries.getOrPut(countryCode) {
                MutableTrafficMapCountryDetail(countryCode)
            }
        country.observe(event.timestampMs)
        val packageNames = event.packageNames.normalizedTrafficMapPackageNames()
        val appBytes = trafficMapSplitBytes(bytes, packageNames.size)
        packageNames.forEachIndexed { index, packageName ->
            val packageBytes = appBytes.getOrElse(index) { 0L }
            if (packageBytes > 0L) {
                country.appRows
                    .getOrPut(packageName) { MutableTrafficMapCountryAppAccumulator(packageName) }
                    .add(bytes = packageBytes, timestampMs = event.timestampMs)
            }
        }
        val remoteHost =
            if (includeHostDetails) {
                event.remoteHost.trim().takeIf(String::isNotEmpty)
            } else {
                null
            }
        if (remoteHost != null) {
            val key =
                TrafficMapCountryHostKey(
                    remoteHost = remoteHost,
                    remotePort = event.remotePort,
                    protocol = event.protocol.normalizedTrafficMapProtocol(),
                )
            country.hostRows
                .getOrPut(key) { MutableTrafficMapCountryHostAccumulator(key) }
                .add(bytes = bytes, timestampMs = event.timestampMs, packageNames = packageNames)
        }
    }
    return countries.mapValues { (_, country) ->
        country.toDetail(appLimit = appLimit, hostLimit = hostLimit)
    }
}

private fun TrafficMapConnectionSample.deltaBytes(previous: TrafficMapConnectionSample?): Long {
    val currentBytes = bytes.coerceAtLeast(0L)
    val previousBytes = previous?.bytes?.coerceAtLeast(0L) ?: return currentBytes
    return if (currentBytes >= previousBytes) {
        currentBytes - previousBytes
    } else {
        currentBytes
    }
}

private fun Map<String, Long>.addTrafficMapCountryBytes(next: Map<String, Long>): Map<String, Long> {
    if (next.isEmpty()) {
        return this
    }
    val result = LinkedHashMap(this)
    next.forEach { (countryCode, bytes) ->
        result[countryCode] = (result[countryCode] ?: 0L) + bytes.coerceAtLeast(0L)
    }
    return result
}

private fun Map<String, Set<String>>.addTrafficMapCountryConnectionIds(
    next: Map<String, Set<String>>,
): Map<String, Set<String>> {
    if (next.isEmpty()) {
        return this
    }
    val result = mapValuesTo(linkedMapOf()) { (_, value) -> value.toMutableSet() }
    next.forEach { (countryCode, connectionIds) ->
        result
            .getOrPut(countryCode) { linkedSetOf() }
            .addAll(connectionIds)
    }
    return result
}

private fun List<TrafficMapPeriodBucket>.prunedTrafficMapPeriodBuckets(nowMs: Long): List<TrafficMapPeriodBucket> {
    val cutoffMs = nowMs - TRAFFIC_MAP_FIVE_MINUTES_MS
    return filter { bucket -> bucket.timestampMs >= cutoffMs }
}

private fun List<TrafficMapPeriodBucket>.toTrafficMapAggregates(): Map<String, TrafficMapAggregate> {
    val bytesByCountry = linkedMapOf<String, Long>()
    val connectionIdsByCountry = linkedMapOf<String, MutableSet<String>>()
    forEach { bucket ->
        bucket.bytesByCountry.forEach { (countryCode, bytes) ->
            bytesByCountry[countryCode] = (bytesByCountry[countryCode] ?: 0L) + bytes.coerceAtLeast(0L)
        }
        bucket.connectionIdsByCountry.forEach { (countryCode, connectionIds) ->
            connectionIdsByCountry
                .getOrPut(countryCode) { linkedSetOf() }
                .addAll(connectionIds)
        }
    }
    return trafficMapAggregatesFromBytesAndConnectionIds(
        bytesByCountry = bytesByCountry,
        connectionIdsByCountry = connectionIdsByCountry,
    )
}

private fun trafficMapAggregatesFromBytesAndConnectionIds(
    bytesByCountry: Map<String, Long>,
    connectionIdsByCountry: Map<String, Set<String>>,
): Map<String, TrafficMapAggregate> =
    bytesByCountry.mapValues { (countryCode, bytes) ->
        TrafficMapAggregate(
            countryCode = countryCode,
            bytes = bytes.coerceAtLeast(0L),
            connections = connectionIdsByCountry[countryCode]?.size ?: 0,
        )
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

private fun trafficMapDestinationSnapshotFromPoints(
    points: List<TrafficMapPoint>,
): TrafficMapDestinationSnapshot =
    TrafficMapDestinationSnapshot(
        points = points,
        routeAggregate = TrafficMapRouteAggregate(
            bytes = points.sumOf(TrafficMapPoint::bytes),
            connections = points.sumOf(TrafficMapPoint::connections),
        ),
        unknownCountryBytes = 0L,
        unknownCountryConnections = 0,
        hiddenCountryCount = 0,
        totalBytes = points.sumOf(TrafficMapPoint::bytes),
        totalConnections = points.sumOf(TrafficMapPoint::connections),
        countryCount = points.size,
        lastSampleAtMs = null,
        newCountryCodes = emptySet(),
    )

private fun trafficMapPeriodSnapshotsFromLive(
    snapshot: TrafficMapDestinationSnapshot,
): TrafficMapPeriodSnapshots {
    val fiveMinutes =
        snapshot.toPeriodSnapshot(
            period = TrafficMapPeriod.FIVE_MINUTES,
            sampleWindowLabel = TRAFFIC_MAP_PERIOD_FIVE_MINUTES_LABEL,
        )
    val session =
        snapshot.toPeriodSnapshot(
            period = TrafficMapPeriod.SESSION,
            sampleWindowLabel = TRAFFIC_MAP_PERIOD_SESSION_LABEL,
        )
    return TrafficMapPeriodSnapshots(
        fiveMinutes = fiveMinutes,
        session = session,
        day24 = TrafficMapPeriodSnapshot(
            period = TrafficMapPeriod.DAY_24,
            sampleWindowLabel = TRAFFIC_MAP_PERIOD_DAY_24_LABEL,
        ),
    )
}

private fun TrafficMapDestinationSnapshot.toPeriodSnapshot(
    period: TrafficMapPeriod,
    sampleWindowLabel: String,
): TrafficMapPeriodSnapshot =
    TrafficMapPeriodSnapshot(
        period = period,
        destinations = points,
        unknownCountryBytes = unknownCountryBytes,
        unknownCountryConnections = unknownCountryConnections,
        hiddenCountryCount = hiddenCountryCount,
        totalBytes = totalBytes,
        totalConnections = totalConnections,
        countryCount = countryCount,
        sampleWindowLabel = sampleWindowLabel,
        lastSampleAtMs = lastSampleAtMs,
        newCountryCodes = newCountryCodes,
    )

private fun normalizeTrafficMapAggregateCountryCode(countryCode: String?): String? =
    countryCode
        ?.trim()
        ?.uppercase(Locale.US)
        ?.takeIf { value ->
            value.length == TrafficMapRepository.IsoCountryCodeLength &&
                value.all { character -> character in 'A'..'Z' }
        }

private fun List<String>.normalizedTrafficMapPackageNames(): List<String> =
    asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()
        .toList()

private fun String.normalizedTrafficMapProtocol(): String =
    trim()
        .uppercase(Locale.US)
        .ifBlank { "UNKNOWN" }

private fun trafficMapSplitBytes(
    bytes: Long,
    parts: Int,
): List<Long> {
    if (bytes <= 0L || parts <= 0) {
        return emptyList()
    }
    val base = bytes / parts
    val remainder = (bytes % parts).toInt()
    return List(parts) { index ->
        base + if (index < remainder) 1L else 0L
    }
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

private val TrafficMapCountryAppRowComparator =
    compareByDescending<TrafficMapCountryAppRow> { row -> row.bytes }
        .thenByDescending { row -> row.connections }
        .thenByDescending { row -> row.lastSeenAtMs }
        .thenBy { row -> row.packageName }

private val TrafficMapCountryHostRowComparator =
    compareByDescending<TrafficMapCountryHostRow> { row -> row.bytes }
        .thenByDescending { row -> row.connections }
        .thenByDescending { row -> row.lastSeenAtMs }
        .thenBy { row -> row.remoteHost }

private const val STALE_TRAFFIC_MAP_SAMPLE_MS = 10_000L
private const val TRAFFIC_MAP_FIVE_MINUTES_MS = 5 * 60 * 1000L
private const val TRAFFIC_MAP_DAY_24_MS = 24 * 60 * 60 * 1000L
private const val TRAFFIC_MAP_PERIOD_FIVE_MINUTES_LABEL = "Last 5 min"
private const val TRAFFIC_MAP_PERIOD_SESSION_LABEL = "Session"
private const val TRAFFIC_MAP_PERIOD_DAY_24_LABEL = "Last 24h"
private const val TRAFFIC_MAP_COUNTRY_MIN_INTENSITY = 0.18f
private const val TRAFFIC_MAP_ROUTE_COUNTRY_MIN_INTENSITY = 0.32f
