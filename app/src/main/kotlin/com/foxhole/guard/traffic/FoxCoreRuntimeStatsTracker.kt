package com.foxhole.guard.traffic

import com.foxhole.core.model.DnsRuntimeStats
import com.foxhole.core.model.I2pTrafficStats
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.model.TorTrafficStats
import com.foxhole.core.model.TunnelAppTrafficStats
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

internal class FoxCoreRuntimeStatsTracker(
    private val diagnosticsLogger: DiagnosticsLogger,
) {
    @Suppress("LongParameterList")
    fun start(
        scope: CoroutineScope,
        runtimeConnectionSnapshots: Flow<RuntimeConnectionSnapshot>,
        enabled: () -> Boolean,
        networkActivityEnabled: () -> Boolean = { false },
        tunnelAppTrafficEnabled: () -> Boolean = { false },
        torOnlyRuntimeActive: () -> Boolean = { false },
        networkActivityContext: () -> RuntimeNetworkActivityContext = { RuntimeNetworkActivityContext() },
        countryCodeForDestination: (String) -> String? = { null },
        onNetworkActivityEvents: (List<NetworkActivityEvent>) -> Unit = {},
        onRuntimeAuditEvents: (List<RuntimeAuditEvent>, Long, Long) -> Unit = { _, _, _ -> },
    ): Job =
        scope.launch(Dispatchers.Default) {
            val trafficTotals = mutableMapOf<String, RuntimeConnectionTrafficTotals>()
            val torTrafficTotals = mutableMapOf<String, RuntimeConnectionTrafficTotals>()
            val i2pTrafficTotals = mutableMapOf<String, RuntimeConnectionTrafficTotals>()
            val laneTrafficCursor = RuntimeLaneTrafficCursor()
            val dnsVerdictCursor = RuntimeDnsVerdictCursor()
            runtimeConnectionSnapshots
                .catch {
                    diagnosticsLogger.recordFailure("dns", "runtime stats snapshot stream failed")
                }
                .collect { snapshot ->
                    if (!enabled()) {
                        trafficTotals.clear()
                        torTrafficTotals.clear()
                        i2pTrafficTotals.clear()
                        laneTrafficCursor.advanceWithoutRecording(snapshot)
                        // Still advance the cursor: the counters kept running while stats were
                        // off, so the next enabled snapshot must not report the whole gap as one
                        // delta.
                        dnsVerdictCursor.advanceWithoutRecording(snapshot)
                        return@collect
                    }
                    dnsVerdictCursor.recordVerdictDelta(snapshot)
                    recordAuditEvents(snapshot, onRuntimeAuditEvents)
                    val authoritativeLaneDeltas = laneTrafficCursor.record(snapshot)
                    if (authoritativeLaneDeltas != null) {
                        val tor = authoritativeLaneDeltas.deltaFor(TOR_LANE)
                        val i2p = authoritativeLaneDeltas.deltaFor(I2P_LANE)
                        TorTrafficStats.record(rxDelta = tor.bytesRx, txDelta = tor.bytesTx)
                        I2pTrafficStats.record(rxDelta = i2p.bytesRx, txDelta = i2p.bytesTx)
                    } else {
                        // Compatibility fallback for an older native snapshot without top-level
                        // lane totals. Current FoxCore always takes the authoritative branch.
                        recordLaneTraffic(
                            snapshot = snapshot,
                            laneTotals = torTrafficTotals,
                            belongsToLane = { connection ->
                                connection.belongsToTorLane(torOnlyRuntimeActive())
                            },
                            record = { rx, tx -> TorTrafficStats.record(rxDelta = rx, txDelta = tx) },
                        )
                        recordLaneTraffic(
                            snapshot = snapshot,
                            laneTotals = i2pTrafficTotals,
                            belongsToLane = { it.lane.equals(I2P_LANE, ignoreCase = true) },
                            record = { rx, tx -> I2pTrafficStats.record(rxDelta = rx, txDelta = tx) },
                        )
                    }
                    val networkActivityEvents =
                        recordRuntimeConnections(
                            snapshot = snapshot,
                            trafficTotals = trafficTotals,
                            networkActivityEnabled = networkActivityEnabled,
                            tunnelAppTrafficEnabled = tunnelAppTrafficEnabled,
                            networkActivityContext = networkActivityContext,
                            countryCodeForDestination = countryCodeForDestination,
                        )
                    if (networkActivityEvents.isNotEmpty()) {
                        onNetworkActivityEvents(networkActivityEvents)
                    }
                }
        }

    /**
     * The audit half of a snapshot: one journal line per event, the two queue-drop notices, and
     * the single callback that hands the batch (with both drop counters) to the caller. Kept in
     * this order — the journal is written before anything downstream can react to the batch.
     */
    private fun recordAuditEvents(
        snapshot: RuntimeConnectionSnapshot,
        onRuntimeAuditEvents: (List<RuntimeAuditEvent>, Long, Long) -> Unit,
    ) {
        snapshot.auditEvents.forEach(::recordAuditEvent)
        if (snapshot.droppedAuditEvents > 0L) {
            diagnosticsLogger.record(
                "dns",
                "FoxCore audit queue dropped ${snapshot.droppedAuditEvents} events; aggregate counters remain authoritative",
            )
        }
        if (snapshot.droppedTrafficEvents > 0L) {
            diagnosticsLogger.record(
                "connection",
                "FoxCore traffic queue dropped ${snapshot.droppedTrafficEvents} events; snapshot reconciliation remains authoritative for live flows",
            )
        }
        if (
            snapshot.auditEvents.isNotEmpty() ||
            snapshot.droppedAuditEvents > 0L ||
            snapshot.droppedTrafficEvents > 0L
        ) {
            onRuntimeAuditEvents(
                snapshot.auditEvents,
                snapshot.droppedAuditEvents,
                snapshot.droppedTrafficEvents,
            )
        }
    }

    private fun recordAuditEvent(event: RuntimeAuditEvent) {
        when (event) {
            is RuntimeAuditEvent.DnsBlocked ->
                DnsRuntimeStats.recordBlockedDnsBreakdown(
                    domain = event.domain,
                    category = event.category,
                    packageName = event.packageName,
                )
            is RuntimeAuditEvent.OutboundUnavailable ->
                diagnosticsLogger.recordStructured(
                    "foxcore",
                    "native outbound unavailable",
                    "id=${event.id}",
                    "kind=${event.kind}",
                    "reason=${event.reason}",
                    "attempts=${event.attempts}",
                    "message=${event.message}",
                )
            is RuntimeAuditEvent.Blocked ->
                diagnosticsLogger.recordStructured(
                    "firewall",
                    "native flow blocked",
                    "reason=${event.reason}",
                    "transport=${event.transport}",
                    event.packageName?.let { "package=$it" },
                )
            is RuntimeAuditEvent.ConfigApplied ->
                diagnosticsLogger.recordStructured(
                    "foxcore",
                    "native policy applied",
                    "revision=${event.revision}",
                    "previous=${event.previousRevision}",
                )
            is RuntimeAuditEvent.ConfirmationRequired ->
                diagnosticsLogger.recordStructured(
                    "continuity",
                    "native confirmation required",
                    "interruption=${event.interruption}",
                    "token=${event.token}",
                )
            is RuntimeAuditEvent.ConfirmationExpired ->
                diagnosticsLogger.recordStructured(
                    "continuity",
                    "native confirmation expired",
                    "interruption=${event.interruption}",
                    "token=${event.token}",
                )
            is RuntimeAuditEvent.OutboundRestored ->
                diagnosticsLogger.recordStructured(
                    "foxcore",
                    "native outbound restored",
                    "id=${event.id}",
                    "kind=${event.kind}",
                    "attempts=${event.attempts}",
                )
            is RuntimeAuditEvent.FlowsRevoked ->
                diagnosticsLogger.recordStructured(
                    "firewall",
                    "native live flows revoked",
                    "target=${event.target}",
                    event.scope?.let { "scope=$it" },
                    "count=${event.count}",
                )
        }
    }

    /**
     * Per-lane byte accounting: FoxCore's actual route for each flow feeds that lane's
     * counters with per-snapshot deltas, so the UI can show the Tor and I2P shares while a VPN runs
     * alongside them — the tunnel totals cannot split the lanes apart.
     *
     * Tor and I2P are matched on the typed lane recorded by the Rust data plane, not inferred from
     * a proxy type or an implementation-specific tag.
     */
    private fun recordLaneTraffic(
        snapshot: RuntimeConnectionSnapshot,
        laneTotals: MutableMap<String, RuntimeConnectionTrafficTotals>,
        belongsToLane: (RuntimeConnectionRecord) -> Boolean,
        record: (rxDelta: Long, txDelta: Long) -> Unit,
    ) {
        var rxDelta = 0L
        var txDelta = 0L
        val activeIds = mutableSetOf<String>()
        snapshot.connections.take(MAX_TRACKED_CONNECTIONS).forEach { connection ->
            if (!belongsToLane(connection)) {
                return@forEach
            }
            activeIds += connection.connectionId
            val current =
                RuntimeConnectionTrafficTotals(
                    bytesTx = connection.bytesTx,
                    bytesRx = connection.bytesRx,
                )
            val previous = laneTotals[connection.connectionId]
            laneTotals[connection.connectionId] = current
            rxDelta += current.bytesRx.deltaFrom(previous?.bytesRx)
            txDelta += current.bytesTx.deltaFrom(previous?.bytesTx)
        }
        laneTotals.keys.retainAll(activeIds)
        // Record zero deltas too: snapshots are the sampling clock. Without the zero sample a
        // lane keeps displaying its last non-zero rate forever after traffic becomes idle.
        record(rxDelta, txDelta)
    }

    @Suppress("CyclomaticComplexMethod", "LongParameterList")
    private fun recordRuntimeConnections(
        snapshot: RuntimeConnectionSnapshot,
        trafficTotals: MutableMap<String, RuntimeConnectionTrafficTotals>,
        networkActivityEnabled: () -> Boolean,
        tunnelAppTrafficEnabled: () -> Boolean,
        networkActivityContext: () -> RuntimeNetworkActivityContext,
        countryCodeForDestination: (String) -> String?,
    ): List<NetworkActivityEvent> {
        val activeActivityIds = mutableSetOf<String>()
        val shouldRecordActivity = networkActivityEnabled()
        val shouldRecordTunnelAppTraffic = tunnelAppTrafficEnabled()
        val activityContext by lazy(networkActivityContext)
        val activityEvents = mutableListOf<NetworkActivityEvent>()
        snapshot.connections.take(MAX_TRACKED_CONNECTIONS).forEach { connection ->
            val connectionId = connection.connectionId
            if (connection.outboundType.equals(DNS_OUTBOUND_TYPE, ignoreCase = true)) {
                // DNS connections carry the queried domain (sniffed host) and the requesting app
                // (process info); remembering the pair lets a later block log line for the same
                // domain be attributed to the app that asked.
                val queriedDomain = connection.domain?.takeIf(String::isNotBlank)
                if (queriedDomain != null && connection.packageNames.isNotEmpty()) {
                    DnsRuntimeStats.recordDnsQueryObservation(
                        domain = queriedDomain,
                        packageNames = connection.packageNames,
                    )
                }
            } else if (shouldRecordActivity || shouldRecordTunnelAppTraffic) {
                activeActivityIds += connectionId
                recordTrackedConnection(
                    connection = connection,
                    trafficTotals = trafficTotals,
                    shouldRecordActivity = shouldRecordActivity,
                    shouldRecordTunnelAppTraffic = shouldRecordTunnelAppTraffic,
                    activityContext = { activityContext },
                    countryCodeForDestination = countryCodeForDestination,
                    activityEvents = activityEvents,
                )
            }
        }
        if (shouldRecordActivity || shouldRecordTunnelAppTraffic) {
            trafficTotals.keys.retainAll(activeActivityIds)
        } else {
            trafficTotals.clear()
        }
        return activityEvents
    }

    @Suppress("LongParameterList")
    private fun recordTrackedConnection(
        connection: RuntimeConnectionRecord,
        trafficTotals: MutableMap<String, RuntimeConnectionTrafficTotals>,
        shouldRecordActivity: Boolean,
        shouldRecordTunnelAppTraffic: Boolean,
        activityContext: () -> RuntimeNetworkActivityContext,
        countryCodeForDestination: (String) -> String?,
        activityEvents: MutableList<NetworkActivityEvent>,
    ) {
        val currentTotals =
            RuntimeConnectionTrafficTotals(
                bytesTx = connection.bytesTx,
                bytesRx = connection.bytesRx,
            )
        val previousTotals = trafficTotals[connection.connectionId]
        trafficTotals[connection.connectionId] = currentTotals
        // Tunnel-side per-app accounting: the platform's NetworkStats never attributes tunneled
        // per-app traffic (the tun ident is the VPN network type), so these connection deltas are
        // the only real per-app numbers while the VPN is up.
        if (shouldRecordTunnelAppTraffic) {
            val rxDelta = currentTotals.bytesRx.deltaFrom(previousTotals?.bytesRx)
            val txDelta = currentTotals.bytesTx.deltaFrom(previousTotals?.bytesTx)
            // Shared-UID rows deliberately have no exact package. Guessing one would accuse an
            // arbitrary package; only exact attribution reaches this branch.
            connection.packageNames.firstOrNull()?.let { packageName ->
                TunnelAppTrafficStats.add(packageName, rxDelta = rxDelta, txDelta = txDelta)
            }
        }
        if (shouldRecordActivity) {
            connection.toNetworkActivityEvent(
                context = activityContext(),
                previousTotals = previousTotals,
                currentTotals = currentTotals,
                countryCodeForDestination = countryCodeForDestination,
            )?.takeIf { activityEvents.size < MAX_NETWORK_ACTIVITY_EVENTS_PER_SAMPLE }
                ?.let(activityEvents::add)
        }
    }

    private companion object {
        const val DNS_OUTBOUND_TYPE = "dns"
        const val TOR_OUTBOUND_TYPE = "tor"

        const val TOR_LANE = "tor"
        const val I2P_LANE = "i2p"
        const val MAX_TRACKED_CONNECTIONS = 2_000
        const val MAX_NETWORK_ACTIVITY_EVENTS_PER_SAMPLE = 256
    }
}

internal data class RuntimeLaneTrafficDelta(
    val bytesTx: Long = 0L,
    val bytesRx: Long = 0L,
)

/**
 * Converts FoxCore's cumulative per-lane counters into exact per-snapshot deltas.
 *
 * The counters survive flow closure, while [RuntimeConnectionSnapshot.generation] fences a native
 * restart. A new generation contributes its current totals once; a counter that unexpectedly went
 * backwards is treated the same way instead of producing a negative delta.
 */
internal class RuntimeLaneTrafficCursor {
    private var generation = 0L
    private var previous = emptyMap<String, RuntimeLaneTraffic>()

    fun record(snapshot: RuntimeConnectionSnapshot): Map<String, RuntimeLaneTrafficDelta>? {
        if (snapshot.lanes.isEmpty()) {
            return null
        }
        val generationChanged = snapshot.generation != generation
        val current = snapshot.lanes.associateBy { lane -> lane.lane.lowercase(Locale.ROOT) }
        val deltas =
            current.mapValues { (lane, totals) ->
                val before = previous[lane]
                RuntimeLaneTrafficDelta(
                    bytesTx =
                    cumulativeCounterDelta(
                        current = totals.bytesTx,
                        previous = before?.bytesTx ?: 0L,
                        generationChanged = generationChanged || before == null,
                    ),
                    bytesRx =
                    cumulativeCounterDelta(
                        current = totals.bytesRx,
                        previous = before?.bytesRx ?: 0L,
                        generationChanged = generationChanged || before == null,
                    ),
                )
            }
        generation = snapshot.generation
        previous = current
        return deltas
    }

    fun advanceWithoutRecording(snapshot: RuntimeConnectionSnapshot) {
        if (snapshot.lanes.isEmpty()) {
            return
        }
        generation = snapshot.generation
        previous = snapshot.lanes.associateBy { lane -> lane.lane.lowercase(Locale.ROOT) }
    }
}

private fun Map<String, RuntimeLaneTrafficDelta>.deltaFor(lane: String): RuntimeLaneTrafficDelta =
    get(lane) ?: RuntimeLaneTrafficDelta()

/**
 * Where the DNS verdict counters stood at the previous snapshot. They are cumulative within one
 * runtime generation, so a new generation (or a counter that went backwards) is reported as a
 * fresh total instead of a negative delta — see [cumulativeCounterDelta].
 *
 * A snapshot observed while statistics are off still advances the cursor without recording, so
 * re-enabling them cannot report the whole idle gap as one delta.
 */
private class RuntimeDnsVerdictCursor {
    private var generation = 0L
    private var previousBlocked = 0L
    private var previousAllowed = 0L

    fun recordVerdictDelta(snapshot: RuntimeConnectionSnapshot) {
        if (snapshot.generation <= 0L) {
            return
        }
        val generationChanged = snapshot.generation != generation
        DnsRuntimeStats.recordDnsVerdictDelta(
            blocked =
            cumulativeCounterDelta(
                current = snapshot.dnsBlocked,
                previous = previousBlocked,
                generationChanged = generationChanged,
            ),
            allowed =
            cumulativeCounterDelta(
                current = snapshot.dnsAllowed,
                previous = previousAllowed,
                generationChanged = generationChanged,
            ),
        )
        advanceWithoutRecording(snapshot)
    }

    fun advanceWithoutRecording(snapshot: RuntimeConnectionSnapshot) {
        if (snapshot.generation <= 0L) {
            return
        }
        generation = snapshot.generation
        previousBlocked = snapshot.dnsBlocked
        previousAllowed = snapshot.dnsAllowed
    }
}

internal fun cumulativeCounterDelta(
    current: Long,
    previous: Long,
    generationChanged: Boolean,
): Long =
    if (generationChanged || current < previous) {
        current.coerceAtLeast(0L)
    } else {
        (current - previous).coerceAtLeast(0L)
    }

internal fun RuntimeConnectionRecord.belongsToTorLane(torOnlyRuntimeActive: Boolean): Boolean =
    lane.equals("tor", ignoreCase = true) ||
        (
            torOnlyRuntimeActive &&
                outboundType.equals("tor", ignoreCase = true)
            )

internal data class RuntimeNetworkActivityContext(
    val profileId: Long? = null,
    val sessionId: String? = null,
)

internal data class RuntimeConnectionTrafficTotals(
    val bytesTx: Long,
    val bytesRx: Long,
)

internal data class RuntimeConnectionEndpoint(
    val host: String,
    val port: Int?,
)

private fun RuntimeConnectionRecord.toNetworkActivityEvent(
    context: RuntimeNetworkActivityContext,
    previousTotals: RuntimeConnectionTrafficTotals?,
    currentTotals: RuntimeConnectionTrafficTotals,
    countryCodeForDestination: (String) -> String?,
): NetworkActivityEvent? {
    val destinationEndpoint = destination.orEmpty().toRuntimeEndpoint()
    val remoteHost = destinationEndpoint.host.takeIf(String::isNotBlank)
    val bytesTx = currentTotals.bytesTx.deltaFrom(previousTotals?.bytesTx)
    val bytesRx = currentTotals.bytesRx.deltaFrom(previousTotals?.bytesRx)
    return if (packageNames.isNotEmpty() && remoteHost != null && bytesTx + bytesRx > 0L) {
        NetworkActivityEvent(
            timestampMs = System.currentTimeMillis(),
            packageNames = packageNames,
            protocol = network.orEmpty().ifBlank { protocol.orEmpty() }.ifBlank { "?" }.uppercase(Locale.ROOT),
            remoteHost = remoteHost,
            remotePort = destinationEndpoint.port,
            countryCode = countryCodeForDestination(remoteHost),
            bytesRx = bytesRx,
            bytesTx = bytesTx,
            profileId = context.profileId,
            sessionId = context.sessionId,
        )
    } else {
        null
    }
}

internal fun String.toRuntimeEndpoint(): RuntimeConnectionEndpoint {
    val value = trim()
    return when {
        value.isBlank() -> RuntimeConnectionEndpoint(host = "", port = null)
        value.startsWith("[") ->
            value.toBracketedRuntimeEndpoint() ?: RuntimeConnectionEndpoint(host = value, port = null)
        value.count { it == ':' } == 1 ->
            value.toHostPortRuntimeEndpoint() ?: RuntimeConnectionEndpoint(host = value, port = null)
        else -> RuntimeConnectionEndpoint(host = value, port = null)
    }
}

private fun String.toBracketedRuntimeEndpoint(): RuntimeConnectionEndpoint? {
    val bracketEnd = indexOf(']')
    val host = substring(1, bracketEnd.coerceAtLeast(1))
    val port =
        substring(bracketEnd + 1)
            .removePrefix(":")
            .toIntOrNull()
            ?.takeIf { it in 1..65535 }
    return RuntimeConnectionEndpoint(host = host, port = port).takeIf { bracketEnd > 1 }
}

private fun String.toHostPortRuntimeEndpoint(): RuntimeConnectionEndpoint? {
    val host = substringBeforeLast(':')
    val port = substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 }
    return RuntimeConnectionEndpoint(host = host, port = port).takeIf { host.isNotBlank() && port != null }
}

private fun Long.deltaFrom(previous: Long?): Long =
    if (previous == null || this < previous) {
        coerceAtLeast(0L)
    } else {
        (this - previous).coerceAtLeast(0L)
    }
