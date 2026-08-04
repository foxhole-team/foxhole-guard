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
                val snapshot =
                    (
                        runtime
                            .runtimeTrafficMapJson()
                            ?.let { document -> parseTrafficMap(document, includeProcessInfo()) }
                            ?: RuntimeConnectionSnapshot()
                        ).copy(
                        auditEvents = auditDrain.events,
                        droppedAuditEvents = auditDrain.dropped,
                    )
                emit(snapshot)
                delay(SAMPLE_INTERVAL_MS)
            }
        }.catch {
            diagnosticsLogger?.record("connection", "FoxCore traffic-map polling failed")
            emit(RuntimeConnectionSnapshot())
        }.flowOn(Dispatchers.IO)

    private companion object {
        const val SAMPLE_INTERVAL_MS = 3_000L
        const val MAX_AUDIT_EVENT_BATCH = 512
    }
}

internal data class RuntimeConnectionSnapshot(
    val generation: Long = 0L,
    val connections: List<RuntimeConnectionRecord> = emptyList(),
    val truncated: Boolean = false,
    val dnsQueries: Long = 0L,
    val dnsBlocked: Long = 0L,
    val dnsAllowed: Long = 0L,
    val auditEvents: List<RuntimeAuditEvent> = emptyList(),
    val droppedAuditEvents: Long = 0L,
)

internal sealed interface RuntimeAuditEvent {
    data class DnsBlocked(
        val domain: String,
        val category: DnsFilterCategory?,
        val packageName: String?,
    ) : RuntimeAuditEvent

    data class OutboundUnavailable(
        val id: String,
        val kind: String,
        val reason: String,
        val message: String,
        val attempts: Int,
    ) : RuntimeAuditEvent
}

internal data class RuntimeAuditDrain(
    val events: List<RuntimeAuditEvent> = emptyList(),
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
            truncated = omitted > 0L || connections.size > MAX_TRACKED_CONNECTIONS,
            dnsQueries = dns?.long("queries") ?: 0L,
            dnsBlocked = dns?.long("blocked") ?: 0L,
            dnsAllowed = dns?.long("allowed") ?: 0L,
        )
    }.getOrDefault(RuntimeConnectionSnapshot())

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
    val packages = if (includeProcessInfo) {
        array("packages")
            .mapNotNull { element -> element.jsonPrimitive.contentOrNull }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    } else {
        emptyList()
    }
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
    )
}

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
