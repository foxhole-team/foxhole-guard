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
    private val retainedConnectionAccumulatorState = MutableStateFlow(TrafficMapConnectionAccumulator())
    private val dnsResolverState = MutableStateFlow<TrafficMapDnsResolverAggregate?>(null)

    // "Clear map history" watermark: aggregates ignore statistics windows and network-activity
    // events older than this. Live for the current process; the persisted twin arrives through
    // trafficMapState's historyCutoffMs flow so the cut survives restarts.
    private val historyCutoffMsState = MutableStateFlow(0L)

    // Session tunnel totals (what the dashboard traffic widget shows); the VPN server node rides
    // the same source so the two never disagree. Injected by the VPN service when tracking starts
    // (runtime state is fenced behind the service layer); without a session it reads zero and the
    // node honestly falls back to the connection accumulator.
    @Volatile
    private var sessionTrafficBytesProvider: () -> Long = { 0L }

    // Country of the configured DNS resolver: keeps the DNS node on the map from the moment the
    // tunnel is up, before (or without) any observed DNS egress samples.
    @Volatile
    private var dnsServerCountryProvider: () -> String? = { null }

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
        connectionSamples: Flow<List<TrafficMapConnectionSample>>,
        sessionTrafficBytesProvider: () -> Long = { 0L },
        dnsServerCountryProvider: () -> String? = { null },
    ): Job {
        this.sessionTrafficBytesProvider = sessionTrafficBytesProvider
        this.dnsServerCountryProvider = dnsServerCountryProvider
        // DNS resolver samples never enter the destination accumulator (they are not app
        // destinations); they feed their own aggregate so the resolver always shows on the map.
        val destinationSamples =
            connectionSamples.map { samples ->
                val (dnsSamples, destinations) =
                    samples.partition { sample -> sample.kind == TrafficMapConnectionSampleKind.DNS_SERVER }
                dnsResolverState.updateDnsResolverAggregate(dnsSamples)
                destinations
            }
        return connectionAccumulatorFlowFromSamples(destinationSamples)
            .collectDestinationCountryTracking(scope)
    }

    private fun Flow<TrafficMapConnectionAccumulator>.collectDestinationCountryTracking(
        scope: CoroutineScope,
    ): Job =
        this
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
        dnsResolverState.value = null
    }

    /**
     * "Clear map history": drops the live session aggregates and raises the watermark so the
     * 24h/7d aggregates and the per-country details stop reading anything recorded before now.
     * The underlying statistics windows and journals are untouched — only the map forgets.
     */
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
                dnsResolver = dnsResolverState.value,
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
        val mapAnchorInfo = originInfo ?: routeInfo ?: torInfo
        // City-refined where the city is known and the country is large enough to matter; the
        // plain country point otherwise (see TrafficMapCityAnchors).
        val origin =
            mapAnchorInfo?.countryCode
                ?.let(::trafficMapOrigin)
                ?.withCityAnchor(mapAnchorInfo.city)
        val visibleDestinations =
            destinationSnapshot.points
                .take(MaxTrafficMapDestinations)
        // The VPN server node carries the SESSION tunnel totals (the same source the dashboard
        // traffic widget shows) — the destination-sample sum starts at observation time and made
        // the node disagree with the widget.
        val sessionBytes = sessionTrafficBytesProvider().coerceAtLeast(0L)
        val routeAggregate =
            destinationSnapshot.routeAggregate.let { aggregate ->
                if (sessionBytes > aggregate.bytes) aggregate.copy(bytes = sessionBytes) else aggregate
            }
        val vpnRoute =
            routeInfo
                ?.let { info -> trafficMapRoutePoint(info, routeAggregate) }
        val torExit =
            torInfo
                ?.let { info -> trafficMapTorPoint(info, routeAggregate) }
        // The resolver node never disappears while the tunnel is up: without observed DNS egress
        // samples it falls back to the configured server's country with zeroed counters.
        val dnsServer =
            dnsResolver?.let(::trafficMapDnsServerPoint)
                ?: trafficMapDnsServerFallbackPoint()
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
            // The widget legend's "Total" reads the same session tunnel counters as the traffic
            // widget whenever they exceed the per-country sample sum.
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

    // The destination bundle used to be one combine that rebuilt live/5min/session/24h/7d AND the
    // country details on every ~3s accumulator tick. Split by real change cadence: the accumulator
    // drives only its own snapshots; 24h/7d re-aggregate only when the persisted statistics windows
    // (or the watermark) change (~1/min); details only when the activity journal or the privacy
    // toggle change. The assembled bundle type and its contents stay identical.
    private fun trafficMapDestinationBundleFlow(
        recentTrafficWindows: Flow<List<TrafficWindow>>,
        recentNetworkActivityEvents: Flow<List<NetworkActivityEvent>>,
        showPrivateNetworkDetails: Flow<Boolean>,
        historyCutoffMs: Flow<Long>,
    ): Flow<TrafficMapDestinationBundle> {
        // Effective clear-history watermark: the persisted cutoff from settings and the
        // in-process one raised by clearTrafficMapHistory, whichever is later.
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
                    ownPackageNames = setOfNotNull(ownPackageName),
                )
            }.distinctUntilChanged()
        return combine(accumulatorSnapshots, windowSnapshots, countryDetails) { accumulator, windows, details ->
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
                // The session totals (and with them the VPN-server row and the "Total" row of the
                // detail table) carry the tunnel counters — the same source the traffic widget
                // shows. The per-country sample sum alone starts at observation time and misses
                // DNS/unresolved/overhead bytes, which made the table disagree with the widget.
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

    private fun trafficMapOrigin(countryCode: String): TrafficMapCountryCoordinate? =
        countryRegistry().coordinate(countryCode)

    // Anchors the dot to the resolved city on large countries (US/CA/RU/…); everywhere else — and
    // for unknown cities — the shared country point stays, so dots without a city all coincide.
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
    ): TrafficMapPoint? {
        val coordinate = trafficMapOrigin(routeInfo.countryCode)?.withCityAnchor(routeInfo.city) ?: return null
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
    ): TrafficMapPoint? {
        val coordinate = trafficMapOrigin(torInfo.countryCode) ?: return null
        // Tor exits are country-only: no city is ever resolved for a Tor circuit, so the label
        // must not fall back to a stale city value.
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

    private fun trafficMapDnsServerPoint(aggregate: TrafficMapDnsResolverAggregate): TrafficMapPoint? {
        val countryCode = aggregate.countryCode ?: return null
        val coordinate = trafficMapOrigin(countryCode) ?: return null
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

    private fun trafficMapDnsServerFallbackPoint(): TrafficMapPoint? {
        // The resolver dot stays on the map even with the runtime down: the configured DNS
        // server's country is a stable fact of the setup, and hiding the blue dot between
        // sessions read as the resolver "disappearing".
        val countryCode = normalizeCountryCode(dnsServerCountryProvider()) ?: return null
        val coordinate = trafficMapOrigin(countryCode) ?: return null
        return TrafficMapPoint(
            countryCode = coordinate.countryCode,
            label = coordinate.label,
            lat = coordinate.lat,
            lon = coordinate.lon,
            bytes = 0L,
            connections = 0,
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

private fun IpInfo.trafficMapPrimaryIpAddress(): String? =
    listOfNotNull(ipv4, ip, ipv6)
        .map(String::trim)
        .firstOrNull(String::isNotBlank)

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
    // Clear-history watermark: the statistics windows persist (the map must not touch them),
    // so the map simply stops reading anything older.
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
    // Same watermark rule as the traffic windows: the journal persists, the map stops reading.
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
