package com.foxhole.beta.core.traffic

import com.foxhole.beta.core.anomaly.DnsRuntimeStats
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.NetworkActivityEvent
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.Connection
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

internal class LibboxDnsRuntimeStatsTracker(
    private val diagnosticsLogger: DiagnosticsLogger,
) {
    fun start(
        scope: CoroutineScope,
        enabled: () -> Boolean,
        networkActivityEnabled: () -> Boolean = { false },
        networkActivityContext: () -> RuntimeNetworkActivityContext = { RuntimeNetworkActivityContext() },
        countryCodeForDestination: (String) -> String? = { null },
        onNetworkActivityEvents: (List<NetworkActivityEvent>) -> Unit = {},
    ): Job =
        scope.launch(Dispatchers.IO) {
            while (isActive && enabled()) {
                observeRuntime(
                    enabled = enabled,
                    networkActivityEnabled = networkActivityEnabled,
                    networkActivityContext = networkActivityContext,
                    countryCodeForDestination = countryCodeForDestination,
                    onNetworkActivityEvents = onNetworkActivityEvents,
                )
                    .catch {
                        diagnosticsLogger.record("dns", "runtime stats stream failed")
                    }.collect()
                if (isActive && enabled()) {
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }

    private fun observeRuntime(
        enabled: () -> Boolean,
        networkActivityEnabled: () -> Boolean,
        networkActivityContext: () -> RuntimeNetworkActivityContext,
        countryCodeForDestination: (String) -> String?,
        onNetworkActivityEvents: (List<NetworkActivityEvent>) -> Unit,
    ) =
        callbackFlow<Unit> {
            val lock = Any()
            var connections = Connections()
            val trafficTotals = mutableMapOf<String, RuntimeConnectionTrafficTotals>()
            val handler =
                object : CommandClientHandler {
                    override fun connected() = Unit

                    override fun disconnected(message: String?) {
                        close()
                    }

                    override fun writeLogs(messageList: LogIterator?) = Unit

                    override fun writeConnectionEvents(events: ConnectionEvents?) {
                        if (!enabled() || events == null) {
                            return
                        }
                        synchronized(lock) {
                            if (events.reset) {
                                connections = Connections()
                            }
                            connections.applyEvents(events)
                        }
                    }

                    override fun writeStatus(message: StatusMessage?) = Unit
                    override fun setDefaultLogLevel(level: Int) = Unit
                    override fun clearLogs() = Unit
                    override fun writeGroups(message: OutboundGroupIterator?) = Unit
                    override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
                    override fun updateClashMode(newMode: String?) = Unit
                }
            val options =
                CommandClientOptions().apply {
                    addCommand(Libbox.CommandConnections)
                    statusInterval = STATUS_INTERVAL_NANOS
                }
            val client = CommandClient(handler, options)
            val ticker =
                launch(Dispatchers.IO) {
                    while (isActive) {
                        if (enabled()) {
                            val networkActivityEvents =
                                synchronized(lock) {
                                    recordRuntimeConnections(
                                        connections = connections,
                                        trafficTotals = trafficTotals,
                                        networkActivityEnabled = networkActivityEnabled,
                                        networkActivityContext = networkActivityContext,
                                        countryCodeForDestination = countryCodeForDestination,
                                    )
                                }
                            if (networkActivityEvents.isNotEmpty()) {
                                onNetworkActivityEvents(networkActivityEvents)
                            }
                        }
                        delay(SAMPLE_INTERVAL_MS)
                    }
                }
            runCatching<Unit> { client.connect() }
                .onFailure { error ->
                    diagnosticsLogger.record(
                        "dns",
                        "runtime stats stream connect failed: ${error.javaClass.simpleName}",
                    )
                    close(error)
                }
            awaitClose {
                ticker.cancel()
                runCatching<Unit> { client.disconnect() }
            }
        }

    private fun recordRuntimeConnections(
        connections: Connections,
        trafficTotals: MutableMap<String, RuntimeConnectionTrafficTotals>,
        networkActivityEnabled: () -> Boolean,
        networkActivityContext: () -> RuntimeNetworkActivityContext,
        countryCodeForDestination: (String) -> String?,
    ): List<NetworkActivityEvent> {
        val iterator = connections.iterator()
        var count = 0
        val activeActivityIds = mutableSetOf<String>()
        val shouldRecordActivity = networkActivityEnabled()
        val activityContext by lazy(networkActivityContext)
        val activityEvents = mutableListOf<NetworkActivityEvent>()
        while (iterator.hasNext() && count < MAX_TRACKED_CONNECTIONS) {
            count += 1
            val connection = iterator.next()
            val connectionId = connection.stableRuntimeConnectionId()
            if (connection.outboundType.equals(DNS_OUTBOUND_TYPE, ignoreCase = true)) {
                DnsRuntimeStats.recordDnsConnection(connectionId)
            } else if (shouldRecordActivity) {
                activeActivityIds += connectionId
                val currentTotals = connection.runtimeTrafficTotals()
                val previousTotals = trafficTotals[connectionId]
                trafficTotals[connectionId] = currentTotals
                connection.toNetworkActivityEvent(
                    context = activityContext,
                    previousTotals = previousTotals,
                    currentTotals = currentTotals,
                    countryCodeForDestination = countryCodeForDestination,
                )?.takeIf { activityEvents.size < MAX_NETWORK_ACTIVITY_EVENTS_PER_SAMPLE }
                    ?.let(activityEvents::add)
            }
        }
        trafficTotals.keys.retainAll(activeActivityIds)
        return activityEvents
    }

    private companion object {
        const val DNS_OUTBOUND_TYPE = "dns"
        const val MAX_TRACKED_CONNECTIONS = 2_000
        const val MAX_NETWORK_ACTIVITY_EVENTS_PER_SAMPLE = 256
        const val RECONNECT_DELAY_MS = 1_000L
        const val SAMPLE_INTERVAL_MS = 3_000L
        const val STATUS_INTERVAL_NANOS = 1_000_000_000L
    }
}

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

private fun Connection.stableRuntimeConnectionId(): String =
    id.takeIf(String::isNotBlank) ?: "$network|$source|$destination|$createdAt"

private fun Connection.runtimeTrafficTotals(): RuntimeConnectionTrafficTotals =
    RuntimeConnectionTrafficTotals(
        bytesTx = maxOf(uplinkTotal, uplink, 0L),
        bytesRx = maxOf(downlinkTotal, downlink, 0L),
    )

private fun Connection.toNetworkActivityEvent(
    context: RuntimeNetworkActivityContext,
    previousTotals: RuntimeConnectionTrafficTotals?,
    currentTotals: RuntimeConnectionTrafficTotals,
    countryCodeForDestination: (String) -> String?,
): NetworkActivityEvent? {
    val packageNames =
        processInfo
            ?.packageNames()
            ?.toList()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.sorted()
            .orEmpty()
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

private fun StringIterator.toList(): List<String> {
    val values = mutableListOf<String>()
    while (hasNext()) {
        values += next()
    }
    return values
}
