package com.foxhole.guard.traffic

import com.foxhole.core.model.TrafficMapCountryAppRow
import com.foxhole.core.model.TrafficMapCountryDetail
import com.foxhole.core.model.TrafficMapCountryHostRow
import com.foxhole.core.model.TrafficMapPeriod
import kotlinx.coroutines.flow.map
import java.util.Locale

data class TrafficMapCountryCoordinate(
    val countryCode: String,
    val label: String,
    val lat: Double,
    val lon: Double,
)

internal class MutableTrafficMapCountryDetail(
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

internal class MutableTrafficMapCountryAppAccumulator(
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

internal class MutableTrafficMapCountryHostAccumulator(
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

internal data class TrafficMapCountryHostKey(
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
    val newCountryCodes: Set<String> = emptySet(),
)

internal data class TrafficMapConnectionAccumulator(
    val samplesById: LinkedHashMap<String, TrafficMapConnectionSample> = linkedMapOf(),
    val lastSampleAtMs: Long? = null,
    val newCountryCodes: Set<String> = emptySet(),
    val sessionBytesByCountry: Map<String, Long> = emptyMap(),
    val sessionConnectionIdsByCountry: Map<String, Set<String>> = emptyMap(),
    val periodBuckets: List<TrafficMapPeriodBucket> = emptyList(),
    val sessionActive: Boolean = false,
) {
    @Volatile
    private var cachedCountryAggregates: Map<String, TrafficMapAggregate>? = null

    fun updatedForBatch(
        batch: TrafficMapSampleBatch,
        nowMs: Long = System.currentTimeMillis(),
    ): TrafficMapConnectionAccumulator {
        if (!batch.runtimeAvailable) {
            return copy(newCountryCodes = emptySet(), sessionActive = false)
        }
        val base = if (sessionActive) this else TrafficMapConnectionAccumulator(sessionActive = true)
        return base.updatedWith(
            samples = batch.samples,
            previousCountryCodes = base.countryAggregates().keys,
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
            val nextSamples = if (replaceLiveSamples) linkedMapOf() else samplesById
            return copy(
                samplesById = nextSamples,
                newCountryCodes = emptySet(),
                periodBuckets = periodBuckets.prunedTrafficMapPeriodBuckets(nowMs),
            ).also { pruned ->
                pruned.cachedCountryAggregates = if (replaceLiveSamples) emptyMap() else cachedCountryAggregates
            }
        }
        val folded =
            samples.foldIntoTrafficMapSamples(
                previousSamples = samplesById,
                replaceLiveSamples = replaceLiveSamples,
            )
        val next = folded.samplesById
        val connectionIdsByCountry = folded.connectionIdsByCountry
        val nextAggregates = aggregateTrafficMapSamples(next.values.toList())
        val newCountryCodes = nextAggregates.keys - previousCountryCodes
        val deltaBytesByCountry =
            folded.sampleDeltas
                .groupBy(TrafficMapConnectionSample::countryCode)
                .mapValues { (_, values) -> values.sumOf { sample -> sample.bytes.coerceAtLeast(0L) } }
        val nextSessionBytes =
            sessionBytesByCountry.addTrafficMapCountryBytes(deltaBytesByCountry)
        val nextSessionConnections =
            sessionConnectionIdsByCountry.addTrafficMapCountryConnectionIds(connectionIdsByCountry)
        val nextBuckets =
            periodBuckets.withTrafficMapPeriodBucket(
                nowMs = nowMs,
                deltaBytesByCountry = deltaBytesByCountry,
                connectionIdsByCountry = connectionIdsByCountry,
                newCountryCodes = newCountryCodes,
            )
        return copy(
            samplesById = next,
            lastSampleAtMs = nowMs,
            newCountryCodes = newCountryCodes,
            sessionBytesByCountry = nextSessionBytes,
            sessionConnectionIdsByCountry = nextSessionConnections,
            periodBuckets = nextBuckets,
        ).also { updated -> updated.cachedCountryAggregates = nextAggregates }
    }

    fun countryAggregates(): Map<String, TrafficMapAggregate> =
        cachedCountryAggregates
            ?: aggregateTrafficMapSamples(samplesById.values.toList())
                .also { aggregates -> cachedCountryAggregates = aggregates }

    fun sessionCountryBytes(): Map<String, Long> = sessionBytesByCountry

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
            TrafficMapPeriod.DAY_24,
            TrafficMapPeriod.DAYS_7,
            -> emptyMap()
        }

    fun recentNewCountryCodes(nowMs: Long = System.currentTimeMillis()): Set<String> =
        periodBuckets
            .prunedTrafficMapPeriodBuckets(nowMs)
            .flatMap(TrafficMapPeriodBucket::newCountryCodes)
            .toSet()
}

private class FoldedTrafficMapSamples(
    val samplesById: LinkedHashMap<String, TrafficMapConnectionSample>,
    val sampleDeltas: List<TrafficMapConnectionSample>,
    val connectionIdsByCountry: Map<String, Set<String>>,
)

private fun List<TrafficMapConnectionSample>.foldIntoTrafficMapSamples(
    previousSamples: LinkedHashMap<String, TrafficMapConnectionSample>,
    replaceLiveSamples: Boolean,
): FoldedTrafficMapSamples {
    val next: LinkedHashMap<String, TrafficMapConnectionSample> =
        if (replaceLiveSamples) LinkedHashMap() else LinkedHashMap(previousSamples)
    val sampleDeltas = mutableListOf<TrafficMapConnectionSample>()
    val connectionIdsByCountry = linkedMapOf<String, MutableSet<String>>()
    forEach { sample ->
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
    return FoldedTrafficMapSamples(
        samplesById = next,
        sampleDeltas = sampleDeltas,
        connectionIdsByCountry = connectionIdsByCountry,
    )
}

private fun List<TrafficMapPeriodBucket>.withTrafficMapPeriodBucket(
    nowMs: Long,
    deltaBytesByCountry: Map<String, Long>,
    connectionIdsByCountry: Map<String, Set<String>>,
    newCountryCodes: Set<String>,
): List<TrafficMapPeriodBucket> =
    if (deltaBytesByCountry.isEmpty()) {
        prunedTrafficMapPeriodBuckets(nowMs)
    } else {
        (
            this +
                TrafficMapPeriodBucket(
                    timestampMs = nowMs,
                    bytesByCountry = deltaBytesByCountry,
                    connectionIdsByCountry = connectionIdsByCountry,
                    newCountryCodes = newCountryCodes,
                )
            )
            .prunedTrafficMapPeriodBuckets(nowMs)
            .takeLast(TrafficMapRepository.MaxTrafficMapPeriodBuckets)
    }
