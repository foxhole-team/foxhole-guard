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
    ): Job =
        scope.launch(Dispatchers.Default) {
            val trafficTotals = mutableMapOf<String, RuntimeConnectionTrafficTotals>()
            val torTrafficTotals = mutableMapOf<String, RuntimeConnectionTrafficTotals>()
            val i2pTrafficTotals = mutableMapOf<String, RuntimeConnectionTrafficTotals>()
            var dnsGeneration = 0L
            var previousDnsBlocked = 0L
            var previousDnsAllowed = 0L
            runtimeConnectionSnapshots
                .catch {
                    diagnosticsLogger.record("dns", "runtime stats snapshot stream failed")
                }
                .collect { snapshot ->
                    if (!enabled()) {
                        trafficTotals.clear()
                        torTrafficTotals.clear()
                        i2pTrafficTotals.clear()
                        if (snapshot.generation > 0L) {
                            dnsGeneration = snapshot.generation
                            previousDnsBlocked = snapshot.dnsBlocked
                            previousDnsAllowed = snapshot.dnsAllowed
                        }
                        return@collect
                    }
                    if (snapshot.generation > 0L) {
                        val generationChanged = snapshot.generation != dnsGeneration
                        DnsRuntimeStats.recordDnsVerdictDelta(
                            blocked =
                            cumulativeCounterDelta(
                                current = snapshot.dnsBlocked,
                                previous = previousDnsBlocked,
                                generationChanged = generationChanged,
                            ),
                            allowed =
                            cumulativeCounterDelta(
                                current = snapshot.dnsAllowed,
                                previous = previousDnsAllowed,
                                generationChanged = generationChanged,
                            ),
                        )
                        dnsGeneration = snapshot.generation
                        previousDnsBlocked = snapshot.dnsBlocked
                        previousDnsAllowed = snapshot.dnsAllowed
                    }
                    snapshot.auditEvents.forEach { event ->
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
                        }
                    }
                    if (snapshot.droppedAuditEvents > 0L) {
                        diagnosticsLogger.record(
                            "dns",
                            "FoxCore audit queue dropped ${snapshot.droppedAuditEvents} events; aggregate counters remain authoritative",
                        )
                    }
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
            // Shared-uid connections list several packages; attribute to the first to avoid
            // multiplying bytes.
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

        const val I2P_LANE = "i2p"
        const val MAX_TRACKED_CONNECTIONS = 2_000
        const val MAX_NETWORK_ACTIVITY_EVENTS_PER_SAMPLE = 256
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
