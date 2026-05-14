package com.foxhole.beta.core.traffic

import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.TrafficMapEdge
import com.foxhole.beta.core.model.TrafficMapPoint
import com.foxhole.beta.core.model.TrafficMapUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
    @Volatile
    private var retainedConnectionAccumulator = TrafficMapConnectionAccumulator()

    @Volatile
    private var retainedUiState: TrafficMapUiState? = null

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
            .onEach { state -> retainedUiState = state }
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
                trafficMapCountryBytesFromAggregates(
                    aggregates = accumulator.countryAggregates(),
                    limit = MaxTrafficMapDestinations,
                )
            }
            .onEach { countryBytes -> retainedDestinationCountryBytes = countryBytes }
            .launchIn(scope)

    fun clearDestinationCountryBytes() {
        retainedConnectionAccumulator = TrafficMapConnectionAccumulator()
        retainedDestinationCountryBytes = emptyMap()
        retainedUiState = null
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
            connectionAccumulatorFlow(runtimeAvailable)
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
        val visibleDestinations = destinations.take(MaxTrafficMapDestinations)
        val highlightedCountries =
            (visibleDestinations.map(TrafficMapPoint::countryCode) + listOfNotNull(originInfo?.countryCode))
                .map { countryCode -> countryCode.uppercase(Locale.US) }
                .toSet()
        return TrafficMapUiState(
            originLat = origin.lat,
            originLon = origin.lon,
            originCountryCode = originInfo?.countryCode,
            originCountryName = originInfo?.countryName,
            originCity = null,
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
                accumulator.updatedForBatch(batch).also { retainedConnectionAccumulator = it }
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
                TrafficMapCountryCoordinate("AU", "Australia", -33.8688, 151.2093),
                TrafficMapCountryCoordinate("BR", "Brazil", -23.5558, -46.6396),
                TrafficMapCountryCoordinate("CA", "Canada", 43.6532, -79.3832),
                TrafficMapCountryCoordinate("CH", "Switzerland", 47.3769, 8.5417),
                TrafficMapCountryCoordinate("CN", "China", 39.9042, 116.4074),
                TrafficMapCountryCoordinate("DE", "Germany", 50.1109, 8.6821),
                TrafficMapCountryCoordinate("ES", "Spain", 40.4168, -3.7038),
                TrafficMapCountryCoordinate("FI", "Finland", 60.1699, 24.9384),
                TrafficMapCountryCoordinate("FR", "France", 48.8566, 2.3522),
                TrafficMapCountryCoordinate("GB", "United Kingdom", 51.5072, -0.1276),
                TrafficMapCountryCoordinate("HK", "Hong Kong", 22.3193, 114.1694),
                TrafficMapCountryCoordinate("ID", "Indonesia", -6.2088, 106.8456),
                TrafficMapCountryCoordinate("IE", "Ireland", 53.3498, -6.2603),
                TrafficMapCountryCoordinate("IN", "India", 19.0760, 72.8777),
                TrafficMapCountryCoordinate("IT", "Italy", 45.4642, 9.1900),
                TrafficMapCountryCoordinate("JP", "Japan", 35.6762, 139.6503),
                TrafficMapCountryCoordinate("KR", "South Korea", 37.5665, 126.9780),
                TrafficMapCountryCoordinate("MX", "Mexico", 19.4326, -99.1332),
                TrafficMapCountryCoordinate("NL", "Netherlands", 52.3676, 4.9041),
                TrafficMapCountryCoordinate("NO", "Norway", 59.9139, 10.7522),
                TrafficMapCountryCoordinate("PL", "Poland", 52.2297, 21.0122),
                TrafficMapCountryCoordinate("RO", "Romania", 44.4268, 26.1025),
                TrafficMapCountryCoordinate("RU", "Russia", 55.7558, 37.6173),
                TrafficMapCountryCoordinate("SE", "Sweden", 59.3293, 18.0686),
                TrafficMapCountryCoordinate("SG", "Singapore", 1.3521, 103.8198),
                TrafficMapCountryCoordinate("TR", "Turkey", 41.0082, 28.9784),
                TrafficMapCountryCoordinate("TW", "Taiwan", 25.0330, 121.5654),
                TrafficMapCountryCoordinate("UA", "Ukraine", 50.4501, 30.5234),
                TrafficMapCountryCoordinate("US", "United States", 40.7128, -74.0060),
                TrafficMapCountryCoordinate("ZA", "South Africa", -26.2041, 28.0473),
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
