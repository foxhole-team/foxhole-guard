package com.foxhole.guard.ui.cli.stats

import androidx.compose.runtime.Immutable
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.ProfileTrafficTotal
import com.foxhole.core.model.ProtocolMetricEvent
import com.foxhole.core.model.ProtocolMetricEventKind
import com.foxhole.core.model.StatisticsWindow
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.model.TransportProtocol
import java.util.Locale

@Immutable
internal data class CliStatsVpnProtocolRow(
    val protocol: String,
    val rxBytes: Long,
    val txBytes: Long,
    val avgLatencyMs: Long?,
)

@Immutable
internal data class CliStatsVpnTransportRow(
    val transport: TransportProtocol,
    val rxBytes: Long,
    val txBytes: Long,
    val failures: Int,
    val attempts: Int,
) {
    val errorRateOrNull: Float?
        get() = if (attempts <= 0) null else failures.toFloat() / attempts.toFloat()
}

@Immutable
internal data class CliStatsVpnTables(
    val protocols: List<CliStatsVpnProtocolRow> = emptyList(),
    val transports: List<CliStatsVpnTransportRow> = emptyList(),
)

internal fun cliStatsVpnTables(
    window: StatisticsWindow,
    deviceWindows: List<TrafficWindow>,
    metricEvents: List<ProtocolMetricEvent>,
    profileTrafficTotals: List<ProfileTrafficTotal>,
    nowMs: Long,
): CliStatsVpnTables {
    val cutoff = nowMs - window.durationMs
    val traffic = deviceWindows.filter { sample -> sample.startedAtMs >= cutoff && sample.userVpnProfileId() != null }
    val events = metricEvents.filter { event -> event.timestampMs >= cutoff && event.profileId.isUserVpnProfileId() }
    val transportIndex = CliStatsTransportIndex(profileTrafficTotals)
    val protocols = linkedMapOf<String, ProtocolTableAccumulator>()
    traffic.forEach { sample ->
        val protocol = sample.protocol.normalizedProtocolName() ?: return@forEach
        protocols.getOrPut(protocol, ::ProtocolTableAccumulator).addTraffic(sample)
    }
    events.forEach { event ->
        val protocol = event.resolvedProtocolName(profileTrafficTotals) ?: return@forEach
        protocols.getOrPut(protocol, ::ProtocolTableAccumulator).addLatency(event.latencyMs)
    }

    val transports = linkedMapOf<TransportProtocol, TransportTableAccumulator>()
    traffic.forEach { sample ->
        val transport = transportIndex.resolve(sample) ?: return@forEach
        transports.getOrPut(transport, ::TransportTableAccumulator).addTraffic(sample)
    }
    events.forEach { event ->
        if (event.kind == ProtocolMetricEventKind.SERVER_PING) return@forEach
        val transport = transportIndex.resolve(event) ?: return@forEach
        transports.getOrPut(transport, ::TransportTableAccumulator).addOutcome(event)
    }

    return CliStatsVpnTables(
        protocols =
        protocols.map { (protocol, accumulator) -> accumulator.toRow(protocol) }
            .sortedWith(compareByDescending<CliStatsVpnProtocolRow> { it.rxBytes + it.txBytes }.thenBy { it.protocol }),
        transports =
        transports.map { (transport, accumulator) -> accumulator.toRow(transport) }
            .sortedBy(CliStatsVpnTransportRow::transport),
    )
}

private class ProtocolTableAccumulator {
    private var rxBytes = 0L
    private var txBytes = 0L
    private val latencies = mutableListOf<Long>()

    fun addTraffic(sample: TrafficWindow) {
        rxBytes += sample.rxBytes.coerceAtLeast(0L)
        txBytes += sample.txBytes.coerceAtLeast(0L)
    }

    fun addLatency(value: Long?) {
        value?.takeIf { latency -> latency > 0L }?.let(latencies::add)
    }

    fun toRow(protocol: String): CliStatsVpnProtocolRow =
        CliStatsVpnProtocolRow(
            protocol = protocol,
            rxBytes = rxBytes,
            txBytes = txBytes,
            avgLatencyMs = if (latencies.isEmpty()) null else latencies.average().toLong(),
        )
}

private class TransportTableAccumulator {
    private var rxBytes = 0L
    private var txBytes = 0L
    private var failures = 0
    private var attempts = 0

    fun addTraffic(sample: TrafficWindow) {
        rxBytes += sample.rxBytes.coerceAtLeast(0L)
        txBytes += sample.txBytes.coerceAtLeast(0L)
    }

    fun addOutcome(event: ProtocolMetricEvent) {
        attempts += 1
        if (event.kind == ProtocolMetricEventKind.PROBE_FAILURE) failures += 1
    }

    fun toRow(transport: TransportProtocol): CliStatsVpnTransportRow =
        CliStatsVpnTransportRow(
            transport = transport,
            rxBytes = rxBytes,
            txBytes = txBytes,
            failures = failures,
            attempts = attempts,
        )
}

private class CliStatsTransportIndex(totals: List<ProfileTrafficTotal>) {
    private val known = totals.filter { total -> total.transport != TransportProtocol.UNKNOWN }
    private val byOption = known.uniqueTransportIndex { total ->
        total.protocolOptionId?.takeIf(String::isNotBlank)?.let { option -> "${total.profileId}:$option" }
    }
    private val byProtocol = known.uniqueTransportIndex { total ->
        "${total.profileId}:${total.protocolHint.name.lowercase(Locale.US)}"
    }

    fun resolve(sample: TrafficWindow): TransportProtocol? {
        val profileId = sample.userVpnProfileId() ?: return null
        val protocol = sample.protocol.normalizedProtocolName() ?: return null
        return byProtocol["$profileId:$protocol"]
    }

    fun resolve(event: ProtocolMetricEvent): TransportProtocol? {
        val optionMatch =
            event.optionId
                ?.takeIf(String::isNotBlank)
                ?.let { option -> byOption["${event.profileId}:$option"] }
        if (optionMatch != null) return optionMatch
        val protocol = event.resolvedProtocolName(known) ?: return null
        return byProtocol["${event.profileId}:$protocol"]
    }
}

private fun List<ProfileTrafficTotal>.uniqueTransportIndex(
    key: (ProfileTrafficTotal) -> String?,
): Map<String, TransportProtocol> =
    mapNotNull { total -> key(total)?.let { value -> value to total.transport } }
        .groupBy({ pair -> pair.first }, { pair -> pair.second })
        .mapNotNull { (value, transports) -> transports.distinct().singleOrNull()?.let { value to it } }
        .toMap()

private fun ProtocolMetricEvent.resolvedProtocolName(totals: List<ProfileTrafficTotal>): String? =
    protocol.normalizedProtocolName()
        ?: totals.firstOrNull { total ->
            total.profileId == profileId &&
                optionId?.takeIf(String::isNotBlank) == total.protocolOptionId?.takeIf(String::isNotBlank)
        }?.protocolHint?.name?.normalizedProtocolName()

private fun TrafficWindow.userVpnProfileId(): Long? =
    profileId?.toLongOrNull()?.takeIf(Long::isUserVpnProfileId)

private fun Long.isUserVpnProfileId(): Boolean =
    this > 0L && this != TOR_ONLY_PROFILE_ID && this != LOCAL_GUARD_PROFILE_ID

private fun String?.normalizedProtocolName(): String? =
    this?.trim()?.lowercase(Locale.US)?.takeIf(String::isNotBlank)
