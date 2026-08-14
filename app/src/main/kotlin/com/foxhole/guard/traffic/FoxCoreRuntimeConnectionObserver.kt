package com.foxhole.guard.traffic

import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.core.runtime.FoxholeRuntime
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Polls FoxCore's bounded traffic-map snapshot.
 *
 * Native owns the sampling data and returns one internally consistent document containing flows,
 * routes, per-app totals, lane totals and DNS counters. The UI never opens a second command socket
 * and never observes a mixture of generations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FoxCoreRuntimeConnectionObserver(
    private val runtimeProvider: () -> FoxholeRuntime,
    private val diagnosticsLogger: DiagnosticsLogger? = null,
) {
    fun connectionSnapshots(
        runtimeAvailable: Flow<Boolean>,
        includeProcessInfo: () -> Boolean = { false },
    ): Flow<RuntimeConnectionSnapshot> =
        runtimeAvailable
            .distinctUntilChanged()
            .flatMapLatest { available ->
                if (available) {
                    pollingSnapshots(includeProcessInfo)
                } else {
                    flowOf(RuntimeConnectionSnapshot())
                }
            }.distinctUntilChanged()

    private fun pollingSnapshots(includeProcessInfo: () -> Boolean): Flow<RuntimeConnectionSnapshot> =
        flow {
            while (currentCoroutineContext().isActive) {
                val runtime = runtimeProvider()
                val auditDrain =
                    runtime
                        .drainRuntimeAuditEventsJson(MAX_AUDIT_EVENT_BATCH)
                        ?.let(::parseRuntimeAuditDrain)
                        ?: RuntimeAuditDrain()
                val trafficDrain =
                    runtime
                        .drainRuntimeTrafficEventsJson(MAX_TRAFFIC_EVENT_BATCH)
                        ?.let(::parseRuntimeTrafficDrain)
                        ?: RuntimeTrafficDrain()
                val liveSnapshot =
                    runtime
                        .runtimeTrafficMapJson()
                        ?.let { document -> parseTrafficMap(document, includeProcessInfo()) }
                        ?: RuntimeConnectionSnapshot()
                val snapshot =
                    liveSnapshot.copy(
                        connections = mergeLiveAndClosedConnections(
                            live = liveSnapshot.connections,
                            closed = trafficDrain.closedConnections,
                        ),
                        truncated = liveSnapshot.truncated || trafficDrain.dropped > 0L,
                        auditEvents = auditDrain.events,
                        droppedAuditEvents = auditDrain.dropped,
                        droppedTrafficEvents = trafficDrain.dropped,
                    )
                emit(snapshot)
                delay(SAMPLE_INTERVAL_MS)
            }
        }.catch {
            diagnosticsLogger?.recordFailure("connection", "FoxCore traffic-map polling failed")
            emit(RuntimeConnectionSnapshot())
        }.flowOn(Dispatchers.IO)

    private companion object {
        const val SAMPLE_INTERVAL_MS = 3_000L
        const val MAX_AUDIT_EVENT_BATCH = 512
        const val MAX_TRAFFIC_EVENT_BATCH = 4_096
    }
}

internal data class RuntimeConnectionSnapshot(
    val generation: Long = 0L,
    val connections: List<RuntimeConnectionRecord> = emptyList(),
    val lanes: List<RuntimeLaneTraffic> = emptyList(),
    val truncated: Boolean = false,
    val dnsQueries: Long = 0L,
    val dnsBlocked: Long = 0L,
    val dnsAllowed: Long = 0L,
    val auditEvents: List<RuntimeAuditEvent> = emptyList(),
    val droppedAuditEvents: Long = 0L,
    val droppedTrafficEvents: Long = 0L,
)

/**
 * FoxCore's authoritative cumulative byte counters for one routed lane.
 *
 * Unlike [RuntimeConnectionRecord], these totals retain traffic after a short flow closes and are
 * not affected by the bounded list of live rows. That makes them the only lossless source for the
 * Tor/I2P statistics lanes.
 */
internal data class RuntimeLaneTraffic(
    val lane: String,
    val bytesTx: Long,
    val bytesRx: Long,
)

internal sealed interface RuntimeAuditEvent {
    data class DnsBlocked(
        val domain: String,
        val category: DnsFilterCategory?,
        val packageName: String?,
        val reason: String? = null,
    ) : RuntimeAuditEvent

    data class OutboundUnavailable(
        val id: String,
        val kind: String,
        val reason: String,
        val message: String,
        val attempts: Int,
    ) : RuntimeAuditEvent

    data class Blocked(
        val reason: String,
        val transport: String,
        val host: String?,
        val port: Int?,
        val uid: Int?,
        val packageName: String?,
    ) : RuntimeAuditEvent

    data class ConfigApplied(
        val revision: Long,
        val previousRevision: Long,
    ) : RuntimeAuditEvent

    data class ConfirmationRequired(
        val interruption: String,
        val token: Long,
        val expiresInMs: Long?,
    ) : RuntimeAuditEvent

    data class ConfirmationExpired(
        val interruption: String,
        val token: Long,
    ) : RuntimeAuditEvent

    data class OutboundRestored(
        val id: String,
        val kind: String,
        val attempts: Int,
    ) : RuntimeAuditEvent

    /**
     * Live flows cut on request — the enforcement half of blocking an app, which a policy reload
     * deliberately does not do.
     *
     * [target] is the revocation kind (`all`, `lane`, `uid`, `package`, `outbound`, `flow`) and
     * [scope] the identifier inside it, absent for `all`. [count] of zero is not a non-event: the
     * core publishes the request itself, because "we cut this app off and it had nothing open" and
     * "we never asked" are different facts and only the journal can tell them apart afterwards.
     */
    data class FlowsRevoked(
        val target: String,
        val scope: String?,
        val count: Int,
    ) : RuntimeAuditEvent
}

internal data class RuntimeAuditDrain(
    val events: List<RuntimeAuditEvent> = emptyList(),
    val dropped: Long = 0L,
)

internal data class RuntimeTrafficDrain(
    val closedConnections: List<RuntimeConnectionRecord> = emptyList(),
    val dropped: Long = 0L,
)

internal data class RuntimeConnectionRecord(
    val connectionId: String,
    val lane: String?,
    val outboundType: String?,
    val outboundTag: String?,
    val destination: String?,
    val domain: String?,
    val network: String?,
    val protocol: String?,
    val bytesTx: Long,
    val bytesRx: Long,
    val packageNames: List<String> = emptyList(),
    val sharedUidCandidates: List<String> = emptyList(),
    val uid: Int? = null,
)

private val trafficMapJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal fun parseTrafficMap(
    source: String,
    includeProcessInfo: Boolean,
): RuntimeConnectionSnapshot =
    runCatching {
        val root = trafficMapJson.parseToJsonElement(source).jsonObject
        val connections = root.array("connections")
        val omitted = root.long("omitted_rows")
        val dns = root.objectValue("dns")
        RuntimeConnectionSnapshot(
            generation = root.long("generation"),
            connections = connections
                .take(MAX_TRACKED_CONNECTIONS)
                .mapNotNull { element -> element as? JsonObject }
                .map { connection ->
                    connection.toRuntimeConnectionRecord(includeProcessInfo)
                },
            lanes = root.array("lanes")
                .mapNotNull { element -> element as? JsonObject }
                .mapNotNull(JsonObject::toRuntimeLaneTraffic),
            truncated = omitted > 0L || connections.size > MAX_TRACKED_CONNECTIONS,
            dnsQueries = dns?.long("queries") ?: 0L,
            dnsBlocked = dns?.long("blocked") ?: 0L,
            dnsAllowed = dns?.long("allowed") ?: 0L,
        )
    }.getOrDefault(RuntimeConnectionSnapshot())

private fun JsonObject.toRuntimeLaneTraffic(): RuntimeLaneTraffic? {
    val lane = string("lane")?.trim()?.takeIf(String::isNotBlank) ?: return null
    return RuntimeLaneTraffic(
        lane = lane,
        bytesTx = long("bytes_up").coerceAtLeast(0L),
        bytesRx = long("bytes_down").coerceAtLeast(0L),
    )
}

internal fun parseRuntimeAuditDrain(source: String): RuntimeAuditDrain =
    runCatching {
        val root = trafficMapJson.parseToJsonElement(source).jsonObject
        RuntimeAuditDrain(
            events =
            root.array("events")
                .mapNotNull { element -> element as? JsonObject }
                .mapNotNull(JsonObject::toRuntimeAuditEvent),
            dropped = root.long("dropped").coerceAtLeast(0L),
        )
    }.getOrDefault(RuntimeAuditDrain())

internal fun parseRuntimeTrafficDrain(source: String): RuntimeTrafficDrain =
    runCatching {
        val root = trafficMapJson.parseToJsonElement(source).jsonObject
        RuntimeTrafficDrain(
            closedConnections =
            root.array("events")
                .mapNotNull { element -> element as? JsonObject }
                .filter { event -> event.string("type") == "closed" }
                .take(MAX_TRACKED_CONNECTIONS)
                .map { event -> event.toRuntimeConnectionRecord(includeProcessInfo = true) },
            dropped = root.long("dropped").coerceAtLeast(0L),
        )
    }.getOrDefault(RuntimeTrafficDrain())

internal fun mergeLiveAndClosedConnections(
    live: List<RuntimeConnectionRecord>,
    closed: List<RuntimeConnectionRecord>,
): List<RuntimeConnectionRecord> =
    (live + closed)
        .associateBy(RuntimeConnectionRecord::connectionId)
        .values
        .take(MAX_TRACKED_CONNECTIONS)

private fun JsonObject.toRuntimeAuditEvent(): RuntimeAuditEvent? =
    when (string("type")) {
        "dns_blocked" -> {
            val domain = string("domain")?.trim().orEmpty()
            if (domain.isBlank()) {
                null
            } else {
                RuntimeAuditEvent.DnsBlocked(
                    domain = domain,
                    category =
                    string("category")
                        ?.let { value ->
                            DnsFilterCategory.entries.firstOrNull { category ->
                                category.name.equals(value, ignoreCase = true)
                            }
                        },
                    packageName = string("package")?.trim()?.takeIf(String::isNotBlank),
                    reason = string("reason")?.trim()?.takeIf(String::isNotBlank),
                )
            }
        }
        "outbound_unavailable" -> {
            val id = string("id")?.trim().orEmpty()
            val kind = string("kind")?.trim().orEmpty()
            val reason = string("reason")?.trim().orEmpty()
            val message = string("message")?.trim().orEmpty()
            val requiredFields = listOf(id, kind, reason, message)
            if (requiredFields.any(String::isBlank)) {
                null
            } else {
                RuntimeAuditEvent.OutboundUnavailable(
                    id = id,
                    kind = kind,
                    reason = reason,
                    message = message,
                    attempts = int("attempts")?.coerceAtLeast(1) ?: 1,
                )
            }
        }
        "blocked" -> {
            val destination = objectValue("destination")
            val reason = string("reason")?.trim().orEmpty()
            val transport = string("transport")?.trim().orEmpty()
            if (reason.isBlank() || transport.isBlank()) {
                null
            } else {
                RuntimeAuditEvent.Blocked(
                    reason = reason,
                    transport = transport,
                    host = destination?.string("host")?.trim()?.takeIf(String::isNotBlank),
                    port = destination?.int("port"),
                    uid = int("uid"),
                    packageName = string("package")?.trim()?.takeIf(String::isNotBlank),
                )
            }
        }
        "config_applied" ->
            RuntimeAuditEvent.ConfigApplied(
                revision = long("revision"),
                previousRevision = long("previous_revision"),
            )
        "confirmation_required" -> {
            val interruption = string("interruption")?.trim().orEmpty()
            if (interruption.isBlank()) {
                null
            } else {
                RuntimeAuditEvent.ConfirmationRequired(
                    interruption = interruption,
                    token = long("token"),
                    expiresInMs = this["expires_in_ms"]?.jsonPrimitive?.longOrNull,
                )
            }
        }
        "confirmation_expired" -> {
            val interruption = string("interruption")?.trim().orEmpty()
            if (interruption.isBlank()) {
                null
            } else {
                RuntimeAuditEvent.ConfirmationExpired(
                    interruption = interruption,
                    token = long("token"),
                )
            }
        }
        "outbound_restored" -> {
            val id = string("id")?.trim().orEmpty()
            val kind = string("kind")?.trim().orEmpty()
            if (id.isBlank() || kind.isBlank()) {
                null
            } else {
                RuntimeAuditEvent.OutboundRestored(
                    id = id,
                    kind = kind,
                    attempts = int("attempts")?.coerceAtLeast(1) ?: 1,
                )
            }
        }
        "flows_revoked" -> {
            val target = string("target")?.trim().orEmpty()
            if (target.isBlank()) {
                null
            } else {
                RuntimeAuditEvent.FlowsRevoked(
                    target = target,
                    // Absent for a device-wide `all`, and left null rather than defaulted so the
                    // journal cannot print a scope the core never named.
                    scope = string("scope")?.trim()?.takeIf(String::isNotBlank),
                    // A missing count reads as zero, never as "unknown, assume some": the count is
                    // evidence, and an invented one is worse than none.
                    count = int("count")?.coerceAtLeast(0) ?: 0,
                )
            }
        }
        else -> null
    }

private fun JsonObject.toRuntimeConnectionRecord(includeProcessInfo: Boolean): RuntimeConnectionRecord {
    val route = objectValue("route")
    val host = string("host")
    val port = int("port")
    val transport = string("transport")
    val destination = when {
        host == null -> null
        port == null -> host
        else -> "$host:$port"
    }
    val attribution = objectValue("attribution")
    val processInfo =
        if (includeProcessInfo) {
            runtimeProcessAttribution(attribution)
        } else {
            RuntimeProcessAttribution.Withheld
        }
    val packages = processInfo.packages
    val sharedUidCandidates = processInfo.sharedUidCandidates
    return RuntimeConnectionRecord(
        connectionId = string("id") ?: long("id").toString(),
        lane = route?.string("lane"),
        outboundType = route?.string("outbound") ?: string("outbound"),
        outboundTag = route?.string("outbound_id"),
        destination = destination,
        domain = host?.takeUnless(::isIpLiteral),
        network = transport,
        protocol = route?.string("member"),
        bytesTx = long("bytes_up").coerceAtLeast(0L),
        bytesRx = long("bytes_down").coerceAtLeast(0L),
        packageNames = packages,
        sharedUidCandidates = sharedUidCandidates,
        uid = int("uid") ?: attribution?.int("uid"),
    )
}

/**
 * Who a flow belongs to, as far as the runtime is willing to say. A shared UID never resolves to
 * one package: the candidates are reported instead and [packages] stays empty, so nothing
 * downstream can accuse an arbitrary member of the UID.
 */
private data class RuntimeProcessAttribution(
    val packages: List<String>,
    val sharedUidCandidates: List<String>,
) {
    companion object {
        /** Process info is off (or not consented to): the record carries no attribution at all. */
        val Withheld = RuntimeProcessAttribution(packages = emptyList(), sharedUidCandidates = emptyList())
    }
}

private fun JsonObject.runtimeProcessAttribution(attribution: JsonObject?): RuntimeProcessAttribution {
    val rawPackages = array("packages").stringValues()
    val attributionKind = attribution?.string("kind")
    val sharedUidCandidates =
        when {
            attributionKind == "shared_uid" -> attribution.array("candidates").stringValues()
            attribution == null && rawPackages.size > 1 -> rawPackages
            else -> emptyList()
        }
    val packages =
        when {
            attributionKind == "exact_package" ->
                listOfNotNull(attribution.string("package")?.trim()?.takeIf(String::isNotBlank))
            sharedUidCandidates.isNotEmpty() -> emptyList()
            rawPackages.size <= 1 -> rawPackages
            else -> emptyList()
        }
    return RuntimeProcessAttribution(packages = packages, sharedUidCandidates = sharedUidCandidates)
}

private fun JsonArray.stringValues(): List<String> =
    mapNotNull { element -> element.jsonPrimitive.contentOrNull }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sorted()

private fun JsonObject.array(key: String): JsonArray =
    this[key]?.let { element -> runCatching { element.jsonArray }.getOrNull() } ?: JsonArray(emptyList())

private fun JsonObject.objectValue(key: String): JsonObject? =
    this[key]?.let { element -> runCatching { element.jsonObject }.getOrNull() }

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(key: String): Long =
    this[key]?.jsonPrimitive?.longOrNull ?: 0L

private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

private fun isIpLiteral(value: String): Boolean =
    value.count { it == '.' } == 3 && value.all { it.isDigit() || it == '.' } ||
        ':' in value

private const val MAX_TRACKED_CONNECTIONS = 2_000
