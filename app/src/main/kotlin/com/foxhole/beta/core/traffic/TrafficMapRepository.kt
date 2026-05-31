package com.foxhole.beta.core.traffic

import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.TrafficMapEdge
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
        runtimeAvailable: Flow<Boolean>,
    ): StateFlow<TrafficMapUiState> =
        trafficMapUiStateFlow(
            originIpInfo = originIpInfo,
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
            .onEach { accumulator ->
                retainedConnectionAccumulatorState.value = accumulator
                retainedDestinationCountryBytes =
                    trafficMapCountryBytesFromAggregates(
                        aggregates = accumulator.countryAggregates(),
                        limit = MaxTrafficMapDestinations,
                    )
            }
            .launchIn(scope)

    fun clearDestinationCountryBytes() {
        retainedConnectionAccumulatorState.value = TrafficMapConnectionAccumulator()
        retainedDestinationCountryBytes = emptyMap()
    }

    fun currentDestinationCountryBytes(): Map<String, Long> = retainedDestinationCountryBytes

    private fun trafficMapUiStateFlow(
        originIpInfo: Flow<IpInfo?>,
        runtimeAvailable: Flow<Boolean>,
    ): Flow<TrafficMapUiState> =
        combine(
            originIpInfo
                .map(::trafficMapOriginInfo)
                .distinctUntilChanged(),
            runtimeAvailable
                .distinctUntilChanged()
                .runningFold(null as Boolean?) { _, next -> next }
                .map { available -> available == true },
            retainedConnectionAccumulatorState
                .map { accumulator ->
                    trafficMapPointsFromAggregates(
                        aggregates = accumulator.countryAggregates(),
                        limit = MaxTrafficMapDestinations,
                    )
                },
            ::buildTrafficMapUiState,
        )

    private fun buildTrafficMapUiState(
        originInfo: TrafficMapOriginInfo?,
        runtimeAvailable: Boolean,
        destinations: List<TrafficMapPoint>,
    ): TrafficMapUiState {
        val origin = trafficMapOrigin(originInfo?.countryCode)
        val visibleDestinations =
            destinations
                .take(MaxTrafficMapDestinations)
                .map { point -> offsetTrafficMapDestinationFromOriginCountry(point, originInfo?.countryCode) }
        val highlightedCountries =
            (visibleDestinations.map(TrafficMapPoint::countryCode) + listOfNotNull(originInfo?.countryCode))
                .map { countryCode -> countryCode.uppercase(Locale.US) }
                .toSet()
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
            highlightedCountries = highlightedCountries,
        )
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
        const val MaxTrafficMapDestinations = 30
        const val IsoCountryCodeLength = 2
        const val MaxRetainedConnectionSamples = 512
        const val SameCountryDestinationLatOffset = 1.15
        const val SameCountryDestinationLonOffset = 1.85
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

private fun offsetTrafficMapDestinationFromOriginCountry(
    point: TrafficMapPoint,
    originCountryCode: String?,
): TrafficMapPoint {
    val normalizedOrigin =
        originCountryCode
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { value ->
                value.length == TrafficMapRepository.IsoCountryCodeLength &&
                    value.all { character -> character in 'A'..'Z' }
            }
    return if (normalizedOrigin != null && point.countryCode.equals(normalizedOrigin, ignoreCase = true)) {
        point.copy(
            lat = (point.lat + TrafficMapRepository.SameCountryDestinationLatOffset)
                .coerceIn(TrafficMapRepository.TrafficMapMinLat, TrafficMapRepository.TrafficMapMaxLat),
            lon = (point.lon + TrafficMapRepository.SameCountryDestinationLonOffset)
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
