package com.foxhole.beta.core.traffic

import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.TrafficMapEdge
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

class TrafficMapRepository(
    private val connectionSource: TrafficMapConnectionSource = EmptyTrafficMapConnectionSource,
) {
    @Volatile
    private var retainedOriginInfo: TrafficMapOriginInfo? = null

    @Volatile
    private var retainedConnectionAccumulator = TrafficMapConnectionAccumulator()

    @Volatile
    private var retainedUiState: TrafficMapUiState? = null

    fun trafficMapState(
        scope: CoroutineScope,
        originIpInfo: Flow<IpInfo?>,
        runtimeAvailable: Flow<Boolean>,
    ): StateFlow<TrafficMapUiState> =
        combine(
            originIpInfo
                .map(::trafficMapOriginInfo)
                .runningFold(retainedOriginInfo) { previous, next ->
                    (next ?: previous).also { retainedOriginInfo = it }
                }
                .distinctUntilChanged(),
            runtimeAvailable
                .distinctUntilChanged()
                .onStart { emit(false) },
            connectionSource
                .connectionSamples(runtimeAvailable.distinctUntilChanged())
                .combine(runtimeAvailable.distinctUntilChanged()) { samples, available ->
                    TrafficMapSampleBatch(samples = samples, runtimeAvailable = available)
                }
                .runningFold(retainedConnectionAccumulator) { accumulator, batch ->
                    accumulator.updatedForBatch(batch).also { retainedConnectionAccumulator = it }
                }
                .map { accumulator ->
                    trafficMapPointsFromAggregates(
                        aggregates = accumulator.countryAggregates(),
                        limit = MaxTrafficMapDestinations,
                    )
                },
            ::buildTrafficMapUiState,
        )
            .onEach { state -> retainedUiState = state }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
                initialValue = retainedUiState ?: TrafficMapUiState(),
            )

    fun currentDestinationCountryBytes(): Map<String, Long> =
        retainedUiState
            ?.destinations
            .orEmpty()
            .associate { point -> point.countryCode to point.bytes }

    private fun buildTrafficMapUiState(
        originInfo: TrafficMapOriginInfo?,
        runtimeAvailable: Boolean,
        destinations: List<TrafficMapPoint>,
    ): TrafficMapUiState {
        val origin = trafficMapOrigin(originInfo?.countryCode)
        val visibleDestinations = destinations.take(MaxTrafficMapDestinations)
        return TrafficMapUiState(
            originLat = origin.lat,
            originLon = origin.lon,
            originCountryCode = originInfo?.countryCode,
            originCountryName = originInfo?.countryName,
            originCity = originInfo?.city,
            isAvailable = runtimeAvailable,
            destinations = visibleDestinations,
            edges = visibleDestinations.map { point ->
                TrafficMapEdge(
                    fromLat = origin.lat,
                    fromLon = origin.lon,
                    toLat = point.lat,
                    toLon = point.lon,
                    bytes = point.bytes,
                )
            },
            highlightedCountries = visibleDestinations.mapTo(linkedSetOf(), TrafficMapPoint::countryCode),
        )
    }

    private fun trafficMapOrigin(countryCode: String?): TrafficMapCountryCoordinate =
        countryCode?.let(TrafficMapCountryCoordinates::get) ?: FallbackTrafficMapOrigin

    private fun normalizeCountryCode(countryCode: String?): String? =
        countryCode
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { value -> value.length == IsoCountryCodeLength && value.all { character -> character in 'A'..'Z' } }

    private fun trafficMapOriginInfo(ipInfo: IpInfo?): TrafficMapOriginInfo? {
        val countryCode = normalizeCountryCode(ipInfo?.countryCode)
        return countryCode?.let { code ->
            TrafficMapOriginInfo(
                countryCode = code,
                countryName = ipInfo?.countryName?.takeIf(String::isNotBlank),
                city = ipInfo?.city?.takeIf(String::isNotBlank),
            )
        }
    }

    internal companion object {
        const val MaxTrafficMapDestinations = 60
        const val IsoCountryCodeLength = 2
        const val MaxRetainedConnectionSamples = 2_000

        val FallbackTrafficMapOrigin =
            TrafficMapCountryCoordinate(
                countryCode = "EU",
                label = "Europe",
                lat = 48.8566,
                lon = 2.3522,
            )

        val TrafficMapCountryCoordinates =
            listOf(
                TrafficMapCountryCoordinate("AU", "Australia", -25.2744, 133.7751),
                TrafficMapCountryCoordinate("BR", "Brazil", -14.2350, -51.9253),
                TrafficMapCountryCoordinate("CA", "Canada", 56.1304, -106.3468),
                TrafficMapCountryCoordinate("CH", "Switzerland", 46.8182, 8.2275),
                TrafficMapCountryCoordinate("CN", "China", 35.8617, 104.1954),
                TrafficMapCountryCoordinate("DE", "Germany", 51.1657, 10.4515),
                TrafficMapCountryCoordinate("ES", "Spain", 40.4637, -3.7492),
                TrafficMapCountryCoordinate("FI", "Finland", 61.9241, 25.7482),
                TrafficMapCountryCoordinate("FR", "France", 46.2276, 2.2137),
                TrafficMapCountryCoordinate("GB", "United Kingdom", 55.3781, -3.4360),
                TrafficMapCountryCoordinate("HK", "Hong Kong", 22.3193, 114.1694),
                TrafficMapCountryCoordinate("ID", "Indonesia", -0.7893, 113.9213),
                TrafficMapCountryCoordinate("IE", "Ireland", 53.4129, -8.2439),
                TrafficMapCountryCoordinate("IN", "India", 20.5937, 78.9629),
                TrafficMapCountryCoordinate("IT", "Italy", 41.8719, 12.5674),
                TrafficMapCountryCoordinate("JP", "Japan", 36.2048, 138.2529),
                TrafficMapCountryCoordinate("KR", "South Korea", 35.9078, 127.7669),
                TrafficMapCountryCoordinate("MX", "Mexico", 23.6345, -102.5528),
                TrafficMapCountryCoordinate("NL", "Netherlands", 52.1326, 5.2913),
                TrafficMapCountryCoordinate("NO", "Norway", 60.4720, 8.4689),
                TrafficMapCountryCoordinate("PL", "Poland", 51.9194, 19.1451),
                TrafficMapCountryCoordinate("RO", "Romania", 45.9432, 24.9668),
                TrafficMapCountryCoordinate("RU", "Russia", 61.5240, 105.3188),
                TrafficMapCountryCoordinate("SE", "Sweden", 60.1282, 18.6435),
                TrafficMapCountryCoordinate("SG", "Singapore", 1.3521, 103.8198),
                TrafficMapCountryCoordinate("TR", "Turkey", 38.9637, 35.2433),
                TrafficMapCountryCoordinate("TW", "Taiwan", 23.6978, 120.9605),
                TrafficMapCountryCoordinate("UA", "Ukraine", 48.3794, 31.1656),
                TrafficMapCountryCoordinate("US", "United States", 39.8283, -98.5795),
                TrafficMapCountryCoordinate("ZA", "South Africa", -30.5595, 22.9375),
                TrafficMapCountryCoordinate("EU", "Europe", 48.8566, 2.3522),
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

internal data class TrafficMapSampleBatch(
    val samples: List<TrafficMapConnectionSample>,
    val runtimeAvailable: Boolean,
)

internal data class TrafficMapConnectionAccumulator(
    val samplesById: LinkedHashMap<String, TrafficMapConnectionSample> = linkedMapOf(),
    val awaitingFreshRuntimeSample: Boolean = false,
) {
    fun updatedForBatch(batch: TrafficMapSampleBatch): TrafficMapConnectionAccumulator {
        if (!batch.runtimeAvailable) {
            return copy(awaitingFreshRuntimeSample = true)
        }
        if (batch.samples.isEmpty()) {
            return this
        }
        val base =
            if (awaitingFreshRuntimeSample) {
                TrafficMapConnectionAccumulator()
            } else {
                this
            }
        return base.updatedWith(batch.samples)
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
