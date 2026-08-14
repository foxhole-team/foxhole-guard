package com.foxhole.guard.traffic

import com.foxhole.core.model.CountryTrafficRole
import com.foxhole.core.model.TrafficMapCountryAppRow
import com.foxhole.core.model.TrafficMapCountryDetail
import com.foxhole.core.model.TrafficMapCountryHostRow
import com.foxhole.core.model.TrafficMapCountryVisual
import com.foxhole.core.model.TrafficMapEdge
import com.foxhole.core.model.TrafficMapEdgeRole
import com.foxhole.core.model.TrafficMapPeriod
import com.foxhole.core.model.TrafficMapPeriodSnapshot
import com.foxhole.core.model.TrafficMapPeriodSnapshots
import com.foxhole.core.model.TrafficMapPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.Locale
import kotlin.math.sqrt

// Pure aggregation/geometry helpers for the traffic map: edge building, country visuals,
// sample batching, period buckets, and the snapshot/window aggregate math.

@Suppress("CyclomaticComplexMethod")
internal fun buildTrafficMapEdges(
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
            val edgeRole =
                if (torExit != null) {
                    TrafficMapEdgeRole.TOR_DESTINATION
                } else {
                    TrafficMapEdgeRole.VPN_DESTINATION
                }
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

internal fun buildTrafficMapCountryVisuals(
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

internal data class TrafficMapAggregate(
    val countryCode: String,
    val bytes: Long,
    val connections: Int,
)

internal data class TrafficMapRouteAggregate(
    val bytes: Long,
    val connections: Int,
)

// Session aggregate of the DNS resolver egress: latest known resolver country plus the summed
// per-connection totals, so the resolver node always shows on the map while DNS flows.
internal data class TrafficMapDnsResolverAggregate(
    val countryCode: String? = null,
    val bytesByConnectionId: LinkedHashMap<String, Long> = linkedMapOf(),
    val connections: Int = 0,
) {
    val bytes: Long get() = bytesByConnectionId.values.sumOf { value -> value.coerceAtLeast(0L) }
}

internal fun MutableStateFlow<TrafficMapDnsResolverAggregate?>.updateDnsResolverAggregate(
    samples: List<TrafficMapConnectionSample>,
) {
    if (samples.isEmpty()) {
        return
    }
    val current = value ?: TrafficMapDnsResolverAggregate()
    val totalsById = LinkedHashMap(current.bytesByConnectionId)
    var countryCode = current.countryCode
    samples.forEach { sample ->
        totalsById[sample.connectionId] = sample.bytes.coerceAtLeast(0L)
        countryCode = sample.countryCode
    }
    while (totalsById.size > TrafficMapRepository.MaxRetainedConnectionSamples) {
        totalsById.remove(totalsById.keys.first())
    }
    value =
        TrafficMapDnsResolverAggregate(
            countryCode = countryCode,
            bytesByConnectionId = totalsById,
            connections = maxOf(current.connections, totalsById.size),
        )
}

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

// Floors the snapshot totals at the session tunnel counters: per-country samples undercount the
// tunnel (DNS, unresolved destinations, transport overhead), while the widget shows the counters.
internal fun TrafficMapDestinationSnapshot.withSessionTunnelFloor(
    sessionTunnelBytes: Long,
): TrafficMapDestinationSnapshot =
    if (sessionTunnelBytes > totalBytes) {
        copy(
            totalBytes = sessionTunnelBytes,
            routeAggregate = routeAggregate.copy(bytes = sessionTunnelBytes),
        )
    } else {
        this
    }

internal data class TrafficMapOriginInfo(
    val countryCode: String,
    val countryName: String?,
    val city: String?,
    val ipAddress: String?,
    val providerName: String?,
)

internal data class RetainedTrafficMapSnapshot(
    val accumulator: TrafficMapConnectionAccumulator,
    val countryBytes: Map<String, Long>,
)

internal data class TrafficMapDestinationBundle(
    val liveSnapshot: TrafficMapDestinationSnapshot,
    val periodSnapshots: TrafficMapPeriodSnapshots,
    val countryDetailsByCode: Map<String, TrafficMapCountryDetail>,
)

internal fun Map<String, Long>.addTrafficMapCountryBytes(next: Map<String, Long>): Map<String, Long> {
    if (next.isEmpty()) {
        return this
    }
    val result = LinkedHashMap(this)
    next.forEach { (countryCode, bytes) ->
        result[countryCode] = (result[countryCode] ?: 0L) + bytes.coerceAtLeast(0L)
    }
    return result
}

internal fun Map<String, Set<String>>.addTrafficMapCountryConnectionIds(
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

internal fun List<TrafficMapPeriodBucket>.prunedTrafficMapPeriodBuckets(nowMs: Long): List<TrafficMapPeriodBucket> {
    val cutoffMs = nowMs - TRAFFIC_MAP_FIVE_MINUTES_MS
    return filter { bucket -> bucket.timestampMs >= cutoffMs }
}

internal fun List<TrafficMapPeriodBucket>.toTrafficMapAggregates(): Map<String, TrafficMapAggregate> {
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

internal fun trafficMapAggregatesFromBytesAndConnectionIds(
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

internal fun trafficMapDestinationSnapshotFromPoints(
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

internal fun trafficMapPeriodSnapshotsFromLive(
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
        days7 = TrafficMapPeriodSnapshot(
            period = TrafficMapPeriod.DAYS_7,
            sampleWindowLabel = TRAFFIC_MAP_PERIOD_DAYS_7_LABEL,
        ),
    )
}

internal fun TrafficMapDestinationSnapshot.toPeriodSnapshot(
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

internal fun normalizeTrafficMapAggregateCountryCode(countryCode: String?): String? =
    countryCode
        ?.trim()
        ?.uppercase(Locale.US)
        ?.takeIf { value ->
            value.length == TrafficMapRepository.IsoCountryCodeLength &&
                value.all { character -> character in 'A'..'Z' }
        }

internal fun List<String>.normalizedTrafficMapPackageNames(): List<String> =
    asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()
        .toList()

internal fun String.normalizedTrafficMapProtocol(): String =
    trim()
        .uppercase(Locale.US)
        .ifBlank { "UNKNOWN" }

internal fun trafficMapSplitBytes(
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

internal fun trafficMapSampleWindowLabel(
    runtimeAvailable: Boolean,
    lastSampleAtMs: Long?,
    totalConnections: Int,
): String =
    when {
        totalConnections <= 0 -> TRAFFIC_MAP_STATUS_WAITING_LABEL
        !runtimeAvailable -> TRAFFIC_MAP_STATUS_UNAVAILABLE_LABEL
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

internal val TrafficMapCountryAppRowComparator =
    compareByDescending<TrafficMapCountryAppRow> { row -> row.bytes }
        .thenByDescending { row -> row.connections }
        .thenByDescending { row -> row.lastSeenAtMs }
        .thenBy { row -> row.packageName }

internal val TrafficMapCountryHostRowComparator =
    compareByDescending<TrafficMapCountryHostRow> { row -> row.bytes }
        .thenByDescending { row -> row.connections }
        .thenByDescending { row -> row.lastSeenAtMs }
        .thenBy { row -> row.remoteHost }

private const val STALE_TRAFFIC_MAP_SAMPLE_MS = 10_000L
private const val TRAFFIC_MAP_FIVE_MINUTES_MS = 5 * 60 * 1000L
internal const val TRAFFIC_MAP_DAY_24_MS = 24 * 60 * 60 * 1000L
internal const val TRAFFIC_MAP_DAYS_7_MS = 7 * TRAFFIC_MAP_DAY_24_MS
internal const val TRAFFIC_MAP_PERIOD_FIVE_MINUTES_LABEL = "Last 5 min"
internal const val TRAFFIC_MAP_PERIOD_SESSION_LABEL = "Session"
internal const val TRAFFIC_MAP_PERIOD_DAY_24_LABEL = "Last 24h"
internal const val TRAFFIC_MAP_PERIOD_DAYS_7_LABEL = "Last 7 days"
private const val TRAFFIC_MAP_STATUS_WAITING_LABEL = "Waiting"
private const val TRAFFIC_MAP_STATUS_UNAVAILABLE_LABEL = "Unavailable"
private const val TRAFFIC_MAP_COUNTRY_MIN_INTENSITY = 0.18f
private const val TRAFFIC_MAP_ROUTE_COUNTRY_MIN_INTENSITY = 0.32f
