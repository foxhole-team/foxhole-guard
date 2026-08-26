package com.foxhole.guard.traffic

import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.model.TrafficMapCountryDetail
import com.foxhole.core.model.TrafficMapPeriod
import com.foxhole.core.model.TrafficMapPeriodSnapshot
import com.foxhole.core.model.TrafficMapPeriodSnapshots
import com.foxhole.core.model.TrafficMapPoint
import com.foxhole.core.model.TrafficMapPointRole
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.core.model.TrafficWindow
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

class TrafficMapRepository(
    private val countryRegistryProvider: () -> TrafficMapCountryRegistry = { TrafficMapCountryRegistry.legacyFallback() },
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val ownPackageName: String? = null,
) {
    private val ownPackageNames = setOfNotNull(ownPackageName)
    private val retainedConnectionAccumulatorState = MutableStateFlow(TrafficMapConnectionAccumulator())
    private val dnsResolverState = MutableStateFlow(TrafficMapDnsResolverSnapshot())

    private val historyCutoffMsState = MutableStateFlow(0L)

    @Volatile
    private var sessionTrafficBytesProvider: () -> Long = { 0L }

    @Volatile
    private var retainedDestinationCountryBytes: Map<String, Long> = emptyMap()

    @Volatile
    private var cachedCountryRegistry: TrafficMapCountryRegistry? = null

    @Suppress("LongParameterList")
    fun trafficMapState(
        scope: CoroutineScope,
        originIpInfo: Flow<IpInfo?>,
        routeIpInfo: Flow<IpInfo?>,
        torIpInfo: Flow<IpInfo?>,
        runtimeAvailable: Flow<Boolean>,
        recentTrafficWindows: Flow<List<TrafficWindow>> = flowOf(emptyList()),
        recentNetworkActivityEvents: Flow<List<NetworkActivityEvent>> = flowOf(emptyList()),
        showPrivateNetworkDetails: Flow<Boolean> = flowOf(false),
        historyCutoffMs: Flow<Long> = flowOf(0L),
    ): StateFlow<TrafficMapUiState> =
        trafficMapUiStateFlow(
            originIpInfo = originIpInfo,
            routeIpInfo = routeIpInfo,
            torIpInfo = torIpInfo,
            runtimeAvailable = runtimeAvailable,
            recentTrafficWindows = recentTrafficWindows,
            recentNetworkActivityEvents = recentNetworkActivityEvents,
            showPrivateNetworkDetails = showPrivateNetworkDetails,
            historyCutoffMs = historyCutoffMs,
        )
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = TrafficMapUiState(),
            )

    fun startDestinationCountryTrackingFromSamples(
        scope: CoroutineScope,
        connectionSamples: Flow<TrafficMapRuntimeSnapshotSamples>,
        sessionTrafficBytesProvider: () -> Long = { 0L },
    ): Job {
        this.sessionTrafficBytesProvider = sessionTrafficBytesProvider
        val destinationSamples =
            connectionSamples.map { runtimeSnapshot ->
                val split = splitTrafficMapSamples(runtimeSnapshot.samples)
                dnsResolverState.replaceDnsResolverSnapshot(
                    generation = runtimeSnapshot.generation,
                    samples = split.dnsSamples,
                )
                split.destinations
            }
        return connectionAccumulatorFlowFromSamples(destinationSamples)
            .collectDestinationCountryTracking(scope)
    }

    private fun Flow<TrafficMapConnectionAccumulator>.collectDestinationCountryTracking(
        scope: CoroutineScope,
    ): Job =
        this
            .map { accumulator ->
                RetainedTrafficMapSnapshot(
                    accumulator = accumulator,
                    countryBytes = accumulator.sessionCountryBytes(),
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
        dnsResolverState.clearDnsResolverAggregate()
    }

    fun clearTrafficMapHistory(nowMs: Long = nowProvider()) {
        clearDestinationCountryBytes()
        historyCutoffMsState.value = maxOf(historyCutoffMsState.value, nowMs)
    }

    fun currentDestinationCountryBytes(): Map<String, Long> = retainedDestinationCountryBytes

    @Suppress("LongParameterList")
    private fun trafficMapUiStateFlow(
        originIpInfo: Flow<IpInfo?>,
        routeIpInfo: Flow<IpInfo?>,
        torIpInfo: Flow<IpInfo?>,
        runtimeAvailable: Flow<Boolean>,
        recentTrafficWindows: Flow<List<TrafficWindow>>,
        recentNetworkActivityEvents: Flow<List<NetworkActivityEvent>>,
        showPrivateNetworkDetails: Flow<Boolean>,
        historyCutoffMs: Flow<Long>,
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
            trafficMapDestinationBundleFlow(
                recentTrafficWindows = recentTrafficWindows,
                recentNetworkActivityEvents = recentNetworkActivityEvents,
                showPrivateNetworkDetails = showPrivateNetworkDetails,
                historyCutoffMs = historyCutoffMs,
            ),
        ) { originInfo, routeInfo, torInfo, available, destinationBundle ->
            buildTrafficMapUiState(
                originInfo = originInfo,
                routeInfo = routeInfo,
                torInfo = torInfo,
                runtimeAvailable = available,
                destinationSnapshot = destinationBundle.liveSnapshot,
                periodSnapshots = destinationBundle.periodSnapshots,
                countryDetailsByCode = destinationBundle.countryDetailsByCode,
                dnsResolver = destinationBundle.dnsResolver,
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
    ): TrafficMapUiState {
        val destinationSnapshot =
            trafficMapDestinationSnapshotFromPoints(destinations)
                .copy(newCountryCodes = newCountryCodes)
        return buildTrafficMapUiState(
            originInfo = trafficMapOriginInfo(originIpInfo),
            routeInfo = trafficMapOriginInfo(routeIpInfo),
            torInfo = trafficMapOriginInfo(torIpInfo),
            runtimeAvailable = runtimeAvailable,
            destinationSnapshot = destinationSnapshot,
            periodSnapshots = trafficMapPeriodSnapshotsFromLive(destinationSnapshot),
            countryDetailsByCode = emptyMap(),
        )
    }

    @Suppress("LongParameterList")
    private fun buildTrafficMapUiState(
        originInfo: TrafficMapOriginInfo?,
        routeInfo: TrafficMapOriginInfo?,
        torInfo: TrafficMapOriginInfo?,
        runtimeAvailable: Boolean,
        destinationSnapshot: TrafficMapDestinationSnapshot,
        periodSnapshots: TrafficMapPeriodSnapshots,
        countryDetailsByCode: Map<String, TrafficMapCountryDetail>,
        dnsResolver: TrafficMapDnsResolverAggregate? = null,
    ): TrafficMapUiState {
        val registry = countryRegistry()
        val mapAnchorInfo = originInfo ?: routeInfo ?: torInfo
        val origin =
            mapAnchorInfo?.countryCode
                ?.let(registry::coordinate)
                ?.withCityAnchor(mapAnchorInfo.city)
        val visibleDestinations = trafficMapVisibleDestinations(destinationSnapshot.points)
        val sessionBytes = sessionTrafficBytesProvider().coerceAtLeast(0L)
        val routeAggregate = destinationSnapshot.routeAggregate.withSessionByteFloor(sessionBytes)
        val vpnRoute =
            routeInfo
                ?.let { info -> trafficMapRoutePoint(info, routeAggregate, registry) }
        val torExit =
            torInfo
                ?.let { info -> trafficMapTorPoint(info, routeAggregate, registry) }
        val dnsServer = dnsResolver?.let { aggregate -> trafficMapDnsServerPoint(aggregate, registry) }
        val highlightedCountries =
            buildSet {
                visibleDestinations.forEach { destination -> add(destination.countryCode.uppercase(Locale.US)) }
                originInfo?.countryCode?.let { countryCode -> add(countryCode.uppercase(Locale.US)) }
                vpnRoute?.countryCode?.let { countryCode -> add(countryCode.uppercase(Locale.US)) }
                torExit?.countryCode?.let { countryCode -> add(countryCode.uppercase(Locale.US)) }
            }
        return TrafficMapUiState(
            originLat = origin?.lat ?: FallbackTrafficMapOrigin.lat,
            originLon = origin?.lon ?: FallbackTrafficMapOrigin.lon,
            originCountryCode = originInfo?.countryCode,
            originCountryName = originInfo?.countryName,
            originCity = originInfo?.city,
            originIpAddress = originInfo?.ipAddress,
            vpnRoute = vpnRoute,
            torExit = torExit,
            dnsServer = dnsServer,
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
            totalBytes = maxOf(destinationSnapshot.totalBytes, sessionBytes),
            totalConnections = destinationSnapshot.totalConnections,
            countryCount = destinationSnapshot.countryCount,
        )
    }

    private fun connectionAccumulatorFlowFromSamples(
        connectionSamples: Flow<List<TrafficMapConnectionSample>>,
    ): Flow<TrafficMapConnectionAccumulator> =
        connectionSamples
            .runningFold(TrafficMapConnectionAccumulator()) { accumulator, samples ->
                accumulator.updatedForBatch(
                    batch = TrafficMapSampleBatch(samples = samples, runtimeAvailable = true),
                    nowMs = nowProvider(),
                )
            }

    private fun trafficMapDestinationBundleFlow(
        recentTrafficWindows: Flow<List<TrafficWindow>>,
        recentNetworkActivityEvents: Flow<List<NetworkActivityEvent>>,
        showPrivateNetworkDetails: Flow<Boolean>,
        historyCutoffMs: Flow<Long>,
    ): Flow<TrafficMapDestinationBundle> {
        val effectiveHistoryCutoffMs =
            combine(historyCutoffMs.distinctUntilChanged(), historyCutoffMsState) { persisted, live ->
                maxOf(persisted, live)
            }.distinctUntilChanged()
        val accumulatorSnapshots =
            retainedConnectionAccumulatorState
                .map { accumulator -> trafficMapAccumulatorSnapshots(accumulator, nowMs = nowProvider()) }
                .distinctUntilChanged()
        val windowSnapshots =
            combine(recentTrafficWindows.distinctUntilChanged(), effectiveHistoryCutoffMs) { windows, cutoffMs ->
                trafficMapWindowSnapshots(
                    recentTrafficWindows = windows,
                    historyCutoffMs = cutoffMs,
                    nowMs = nowProvider(),
                    countryRegistry = countryRegistry(),
                )
            }.distinctUntilChanged()
        val countryDetails =
            combine(
                recentNetworkActivityEvents.distinctUntilChanged(),
                showPrivateNetworkDetails.distinctUntilChanged(),
                effectiveHistoryCutoffMs,
            ) { events, showPrivateDetails, cutoffMs ->
                trafficMapVisibleCountryDetails(
                    networkActivityEvents = events,
                    includeHostDetails = showPrivateDetails,
                    historyCutoffMs = cutoffMs,
                    ownPackageNames = ownPackageNames,
                )
            }.distinctUntilChanged()
        return combine(
            accumulatorSnapshots,
            windowSnapshots,
            countryDetails,
            dnsResolverState,
        ) { accumulator, windows, details, dnsResolver ->
            TrafficMapDestinationBundle(
                liveSnapshot = accumulator.liveSnapshot,
                periodSnapshots =
                TrafficMapPeriodSnapshots(
                    fiveMinutes = accumulator.fiveMinutes,
                    session = accumulator.session,
                    day24 = windows.day24,
                    days7 = windows.days7,
                ),
                countryDetailsByCode = details,
                dnsResolver = dnsResolver.aggregate,
            )
        }
    }

    private data class TrafficMapAccumulatorSnapshots(
        val liveSnapshot: TrafficMapDestinationSnapshot,
        val fiveMinutes: TrafficMapPeriodSnapshot,
        val session: TrafficMapPeriodSnapshot,
    )

    private fun trafficMapAccumulatorSnapshots(
        accumulator: TrafficMapConnectionAccumulator,
        nowMs: Long,
    ): TrafficMapAccumulatorSnapshots {
        val countryRegistry = countryRegistry()
        val recentNewCountryCodes = accumulator.recentNewCountryCodes(nowMs)
        val liveSnapshot =
            trafficMapDestinationSnapshotFromAggregates(
                aggregates = accumulator.countryAggregates(),
                limit = MaxTrafficMapDestinations,
                countryRegistry = countryRegistry,
                lastSampleAtMs = accumulator.lastSampleAtMs,
                newCountryCodes = recentNewCountryCodes,
            )
        val fiveMinuteSnapshot =
            trafficMapDestinationSnapshotFromAggregates(
                aggregates = accumulator.periodAggregates(TrafficMapPeriod.FIVE_MINUTES, nowMs),
                limit = MaxTrafficMapDestinations,
                countryRegistry = countryRegistry,
                lastSampleAtMs = accumulator.lastSampleAtMs,
                newCountryCodes = recentNewCountryCodes,
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
            )
                .withSessionTunnelFloor(sessionTrafficBytesProvider().coerceAtLeast(0L))
                .toPeriodSnapshot(
                    period = TrafficMapPeriod.SESSION,
                    sampleWindowLabel = TRAFFIC_MAP_PERIOD_SESSION_LABEL,
                )
        return TrafficMapAccumulatorSnapshots(
            liveSnapshot = liveSnapshot,
            fiveMinutes = fiveMinuteSnapshot,
            session = sessionSnapshot,
        )
    }

    private fun TrafficMapCountryCoordinate.withCityAnchor(city: String?): TrafficMapCountryCoordinate {
        val anchor = TrafficMapCityAnchors.resolve(countryCode = countryCode, city = city) ?: return this
        return copy(lat = anchor.lat, lon = anchor.lon)
    }

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
                    ipAddress = ipInfo?.trafficMapPrimaryIpAddress(),
                    providerName = ipInfo?.isp?.takeIf(String::isNotBlank),
                )
            }
    }

    private fun trafficMapRoutePoint(
        routeInfo: TrafficMapOriginInfo,
        aggregate: TrafficMapRouteAggregate,
        registry: TrafficMapCountryRegistry,
    ): TrafficMapPoint? {
        val coordinate = registry.coordinate(routeInfo.countryCode)?.withCityAnchor(routeInfo.city) ?: return null
        return TrafficMapPoint(
            countryCode = coordinate.countryCode,
            label = routeInfo.trafficMapPlaceLabel(fallback = coordinate.label),
            lat = coordinate.lat,
            lon = coordinate.lon,
            bytes = aggregate.bytes,
            connections = aggregate.connections,
            role = TrafficMapPointRole.VPN_ROUTE,
            ipAddress = routeInfo.ipAddress,
            providerName = routeInfo.providerName,
        )
    }

    private fun trafficMapTorPoint(
        torInfo: TrafficMapOriginInfo,
        aggregate: TrafficMapRouteAggregate,
        registry: TrafficMapCountryRegistry,
    ): TrafficMapPoint? {
        val coordinate = registry.coordinate(torInfo.countryCode) ?: return null
        return TrafficMapPoint(
            countryCode = coordinate.countryCode,
            label = torInfo.countryName?.takeIf(String::isNotBlank) ?: coordinate.label,
            lat = coordinate.lat,
            lon = coordinate.lon,
            bytes = aggregate.bytes,
            connections = aggregate.connections,
            role = TrafficMapPointRole.TOR_EXIT,
            ipAddress = torInfo.ipAddress,
            providerName = torInfo.providerName,
        )
    }

    private fun trafficMapDnsServerPoint(
        aggregate: TrafficMapDnsResolverAggregate,
        registry: TrafficMapCountryRegistry,
    ): TrafficMapPoint? {
        val countryCode = aggregate.countryCode ?: return null
        val coordinate = registry.coordinate(countryCode) ?: return null
        return TrafficMapPoint(
            countryCode = coordinate.countryCode,
            label = coordinate.label,
            lat = coordinate.lat,
            lon = coordinate.lon,
            bytes = aggregate.bytes,
            connections = aggregate.connections,
            role = TrafficMapPointRole.DNS_SERVER,
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

private data class TrafficMapWindowSnapshots(
    val day24: TrafficMapPeriodSnapshot,
    val days7: TrafficMapPeriodSnapshot,
)

private data class TrafficMapSampleSplit(
    val dnsSamples: List<TrafficMapConnectionSample>,
    val destinations: List<TrafficMapConnectionSample>,
)

private fun trafficMapVisibleDestinations(points: List<TrafficMapPoint>): List<TrafficMapPoint> =
    if (points.size <= TrafficMapRepository.MaxTrafficMapDestinations) {
        points
    } else {
        points.take(TrafficMapRepository.MaxTrafficMapDestinations)
    }

private fun TrafficMapRouteAggregate.withSessionByteFloor(sessionBytes: Long): TrafficMapRouteAggregate =
    if (sessionBytes > bytes) copy(bytes = sessionBytes) else this

private fun splitTrafficMapSamples(samples: List<TrafficMapConnectionSample>): TrafficMapSampleSplit {
    val destinations = ArrayList<TrafficMapConnectionSample>(samples.size)
    var dnsSamples: ArrayList<TrafficMapConnectionSample>? = null
    samples.forEach { sample ->
        if (sample.kind == TrafficMapConnectionSampleKind.DNS_SERVER) {
            val currentDnsSamples = dnsSamples ?: ArrayList<TrafficMapConnectionSample>().also { dnsSamples = it }
            currentDnsSamples += sample
        } else {
            destinations += sample
        }
    }
    return TrafficMapSampleSplit(
        dnsSamples = dnsSamples ?: emptyList(),
        destinations = destinations,
    )
}

private fun IpInfo.trafficMapPrimaryIpAddress(): String? {
    ipv4?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    ip.trim().takeIf(String::isNotBlank)?.let { return it }
    return ipv6?.trim()?.takeIf(String::isNotBlank)
}

private fun TrafficMapOriginInfo.trafficMapPlaceLabel(fallback: String): String =
    city?.takeIf(String::isNotBlank)
        ?: countryName?.takeIf(String::isNotBlank)
        ?: fallback

private fun trafficMapWindowSnapshots(
    recentTrafficWindows: List<TrafficWindow>,
    historyCutoffMs: Long,
    nowMs: Long,
    countryRegistry: TrafficMapCountryRegistry,
): TrafficMapWindowSnapshots {
    val visibleTrafficWindows =
        if (historyCutoffMs > 0L) {
            recentTrafficWindows.filter { window -> window.startedAtMs >= historyCutoffMs }
        } else {
            recentTrafficWindows
        }
    val daySnapshot =
        trafficMapDestinationSnapshotFromAggregates(
            aggregates = trafficMapDayAggregates(visibleTrafficWindows, nowMs),
            limit = TrafficMapRepository.MaxTrafficMapDestinations,
            countryRegistry = countryRegistry,
            lastSampleAtMs = visibleTrafficWindows.maxOfOrNull(TrafficWindow::startedAtMs),
        ).toPeriodSnapshot(
            period = TrafficMapPeriod.DAY_24,
            sampleWindowLabel = TRAFFIC_MAP_PERIOD_DAY_24_LABEL,
        )
    val days7Snapshot =
        trafficMapDestinationSnapshotFromAggregates(
            aggregates = trafficMapSevenDayAggregates(visibleTrafficWindows, nowMs),
            limit = TrafficMapRepository.MaxTrafficMapDestinations,
            countryRegistry = countryRegistry,
            lastSampleAtMs = visibleTrafficWindows.maxOfOrNull(TrafficWindow::startedAtMs),
        ).toPeriodSnapshot(
            period = TrafficMapPeriod.DAYS_7,
            sampleWindowLabel = TRAFFIC_MAP_PERIOD_DAYS_7_LABEL,
        )
    return TrafficMapWindowSnapshots(day24 = daySnapshot, days7 = days7Snapshot)
}

private fun trafficMapVisibleCountryDetails(
    networkActivityEvents: List<NetworkActivityEvent>,
    includeHostDetails: Boolean,
    historyCutoffMs: Long,
    ownPackageNames: Set<String>,
): Map<String, TrafficMapCountryDetail> {
    val visibleNetworkActivityEvents =
        if (historyCutoffMs > 0L) {
            networkActivityEvents.filter { event -> event.timestampMs >= historyCutoffMs }
        } else {
            networkActivityEvents
        }
    return trafficMapCountryDetailsFromNetworkActivity(
        events = visibleNetworkActivityEvents,
        includeHostDetails = includeHostDetails,
        ownPackageNames = ownPackageNames,
    )
}
