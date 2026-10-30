package com.foxhole.guard.core.diagnostics

import com.foxhole.core.runtime.FoxholeRuntime
import com.foxhole.core.runtime.RuntimeNativeStatsSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale

internal const val MAX_NATIVE_AGGREGATE_CHARS = 64 * 1024

internal enum class NativeAggregateCounterKey(val jsonKey: String) {
    DNS_QUERIES("dns_queries"),
    DNS_BLOCKED("dns_blocked"),
    DNS_ALLOWED("dns_allowed"),
    DNS_ENCRYPTED_BYPASS("dns_encrypted_bypass"),
    DNS_TRUNCATED("dns_truncated"),
    BLOCKED_FLOWS("blocked_flows"),
    ATTRIBUTION_ERRORS("attribution_errors"),
    DIAL_ERRORS("dial_errors"),
    FLOW_ERRORS("flow_errors"),
    UDP_FLOWS_OPENED("udp_flows_opened"),
    UDP_UNSUPPORTED("udp_unsupported"),
    UDP_QUEUE_DROPPED("udp_queue_dropped"),
}

internal enum class NativeAggregateReadStatus { AVAILABLE, PARTIAL, UNAVAILABLE }

internal enum class NativeAggregateUnavailableReason {
    NO_RUNTIME,
    UNSUPPORTED,
    OWNER_CHANGED,
    READ_UNAVAILABLE,
    INVALID_PAYLOAD,
    PAYLOAD_TOO_LARGE,
    MISSING_COUNTERS,
}

internal data class NativeAggregateCounterSnapshot(
    val status: NativeAggregateReadStatus,
    val values: Map<NativeAggregateCounterKey, ULong> = emptyMap(),
    val reason: NativeAggregateUnavailableReason? = null,
)

private val unsignedCounterToken = Regex("0|[1-9][0-9]{0,19}")

private fun unavailable(reason: NativeAggregateUnavailableReason) =
    NativeAggregateCounterSnapshot(NativeAggregateReadStatus.UNAVAILABLE, reason = reason)

internal fun parseNativeAggregateCounters(source: String?): NativeAggregateCounterSnapshot {
    if (source == null) return unavailable(NativeAggregateUnavailableReason.READ_UNAVAILABLE)
    if (source.length > MAX_NATIVE_AGGREGATE_CHARS) {
        return unavailable(NativeAggregateUnavailableReason.PAYLOAD_TOO_LARGE)
    }
    val root = try {
        Json.parseToJsonElement(source) as? JsonObject
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } ?: return unavailable(NativeAggregateUnavailableReason.INVALID_PAYLOAD)
    val values = NativeAggregateCounterKey.entries.mapNotNull { key ->
        val primitive = root[key.jsonKey] as? JsonPrimitive
        val value = primitive
            ?.takeUnless(JsonPrimitive::isString)
            ?.content
            ?.takeIf(unsignedCounterToken::matches)
            ?.toULongOrNull()
        value?.let { key to it }
    }.toMap()
    if (values.isEmpty()) return unavailable(NativeAggregateUnavailableReason.MISSING_COUNTERS)
    return NativeAggregateCounterSnapshot(
        status = if (values.size == NativeAggregateCounterKey.entries.size) {
            NativeAggregateReadStatus.AVAILABLE
        } else {
            NativeAggregateReadStatus.PARTIAL
        },
        values = values,
    )
}

internal suspend fun collectNativeAggregateCounters(
    runtimeProvider: () -> FoxholeRuntime?,
): NativeAggregateCounterSnapshot =
    try {
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val runtime = runtimeProvider() ?: return@withContext unavailable(NativeAggregateUnavailableReason.NO_RUNTIME)
            val source = runtime as? RuntimeNativeStatsSource
                ?: return@withContext unavailable(NativeAggregateUnavailableReason.UNSUPPORTED)
            val lease = source.captureStatsReadLease()
                ?: return@withContext unavailable(NativeAggregateUnavailableReason.READ_UNAVAILABLE)
            if (runtimeProvider() !== runtime) {
                return@withContext unavailable(NativeAggregateUnavailableReason.OWNER_CHANGED)
            }
            currentCoroutineContext().ensureActive()
            val raw = lease.readStatsJson()
            currentCoroutineContext().ensureActive()
            if (runtimeProvider() !== runtime) {
                return@withContext unavailable(NativeAggregateUnavailableReason.OWNER_CHANGED)
            }
            parseNativeAggregateCounters(raw).also { currentCoroutineContext().ensureActive() }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        unavailable(NativeAggregateUnavailableReason.READ_UNAVAILABLE)
    }

internal fun nativeAggregateCounterLines(snapshot: NativeAggregateCounterSnapshot): List<String> =
    buildList {
        add("Native Aggregate Read: ${snapshot.status.name.lowercase(Locale.ROOT)}")
        snapshot.reason?.let { add("Native Aggregate Unavailable: ${it.name.lowercase(Locale.ROOT)}") }
        if (snapshot.status != NativeAggregateReadStatus.UNAVAILABLE) {
            NativeAggregateCounterKey.entries.forEach { key ->
                add("Native Aggregate ${key.jsonKey}: ${snapshot.values[key]?.toString() ?: "unavailable"}")
            }
        }
    }

internal suspend fun <T> ordinaryDiagnosticsExportAttempt(block: suspend () -> T): Result<T> =
    try {
        currentCoroutineContext().ensureActive()
        val value = block()
        currentCoroutineContext().ensureActive()
        Result.success(value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

internal fun <T> ordinaryDiagnosticsSnapshotAttempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

internal suspend fun <T> prepareNativeDiagnosticsArchive(
    capture: suspend () -> NativeAggregateCounterSnapshot,
    write: suspend (NativeAggregateCounterSnapshot) -> T,
): T {
    currentCoroutineContext().ensureActive()
    val snapshot = capture()
    currentCoroutineContext().ensureActive()
    return write(snapshot).also { currentCoroutineContext().ensureActive() }
}

internal suspend fun writePreparedDiagnosticsArchive(
    prepare: () -> String,
    target: () -> File,
    write: (File, String) -> Unit,
): File {
    currentCoroutineContext().ensureActive()
    val document = prepare()
    currentCoroutineContext().ensureActive()
    val file = target()
    try {
        currentCoroutineContext().ensureActive()
        write(file, document)
        currentCoroutineContext().ensureActive()
        return file
    } catch (failure: Throwable) {
        // A cleanup failure must not replace the original cancellation or fatal error.
        try {
            file.delete()
        } catch (_: Throwable) {
            Unit
        }
        throw failure
    }
}

internal suspend fun <T> prepareDiagnosticsAboutExport(
    onSettled: () -> Unit,
    block: suspend () -> T,
): Result<T> =
    try {
        ordinaryDiagnosticsExportAttempt(block)
    } finally {
        onSettled()
    }
