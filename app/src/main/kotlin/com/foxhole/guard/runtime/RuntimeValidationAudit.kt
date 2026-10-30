package com.foxhole.guard.runtime

import com.foxhole.core.model.RuntimeFailureCode
import com.foxhole.core.runtime.FoxholeRuntime
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardEventType
import com.foxhole.guard.runtimeFailureCodeOrNull
import com.foxhole.guard.traffic.RuntimeAuditDrain
import com.foxhole.guard.traffic.RuntimeAuditEvent
import com.foxhole.guard.traffic.parseRuntimeAuditDrain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

internal data class RuntimeValidationAuditOwner(
    val runtime: FoxholeRuntime,
    val nativeGeneration: Long,
    val validationEpoch: Long,
)

internal data class ValidationAuditObservation(
    val outcome: String,
    val summary: ValidationAuditSummary? = null,
)

internal data class ValidationAuditSummary(
    val events: Int,
    val dropped: Long,
    val journalNotRetained: Int,
    val blocked: Map<Pair<String, String>, Int>,
)

internal suspend fun <T> Result<T>.observeValidationFailure(
    observe: suspend (Throwable) -> Unit,
    onObservationFailed: () -> Unit,
): Result<T> {
    val failure = exceptionOrNull() ?: return this
    currentCoroutineContext().ensureActive()
    val observed = ordinaryValidationAuditAttempt { observe(failure) }
    currentCoroutineContext().ensureActive()
    if (observed.isFailure) ordinaryValidationAuditAttempt(onObservationFailed)
    return this
}

internal fun FoxholeVpnService.captureRuntimeValidationAuditOwner(): RuntimeValidationAuditOwner? =
    ordinaryValidationAuditAttempt {
        val runtime = runtimeInstanceStore.current() ?: return@ordinaryValidationAuditAttempt null
        val snapshot = runtime.nativeSnapshot()
        RuntimeValidationAuditOwner(runtime, snapshot.nativeGeneration, validationEpoch)
            .takeIf { snapshot.hasEngineHandle && it.nativeGeneration > 0L }
    }.getOrNull()

internal suspend fun FoxholeVpnService.recordFailedRuntimeValidationAudit(
    validationRun: RuntimeValidationRun,
    error: Throwable,
) {
    if (error.runtimeFailureCodeOrNull() != RuntimeFailureCode.DNS_FAILURE) return
    val owner = validationRun.auditOwner ?: return
    withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val currentOwner = {
            context.isActive && isCurrentValidationEpoch(owner.validationEpoch) &&
                canPublishValidationResult(validationRun.currentSession) && runtimeInstanceStore.current() === owner.runtime &&
                owner.runtime.nativeSnapshot().nativeGeneration == owner.nativeGeneration
        }
        collectValidationAudit(
            isCurrentOwner = currentOwner,
            drain = { max -> owner.runtime.drainRuntimeAuditEventsJson(max, owner.nativeGeneration) },
            retainCritical = { event ->
                context.ensureActive()
                container.securityComponents.journalEvent(event)
            },
        )?.let { observation ->
            if (currentOwner() && owner.runtime.nativeSnapshot().nativeGeneration == owner.nativeGeneration) {
                val summary = observation.summary
                container.diagnosticsLogger.recordStructured(
                    "dns",
                    "native validation audit",
                    "audit_read=${observation.outcome}",
                    summary?.let { "events=${it.events}" },
                    summary?.let { "dropped=${it.dropped}" },
                    summary?.let { "journal_not_retained=${it.journalNotRetained}" },
                    "cap=$VALIDATION_AUDIT_MAX_EVENTS",
                )
                summary?.blocked?.forEach { (key, count) ->
                    container.diagnosticsLogger.recordStructured(
                        "firewall",
                        "native validation flow blocks",
                        "reason=${key.first}",
                        "transport=${key.second}",
                        "count=$count",
                    )
                }
            }
        }
    }
}

internal fun FoxholeVpnService.recordRuntimeValidationAuditFailed(validationRun: RuntimeValidationRun) {
    val owner = validationRun.auditOwner ?: return
    if (!isCurrentValidationEpoch(owner.validationEpoch) || !canPublishValidationResult(validationRun.currentSession)) return
    if (runtimeInstanceStore.current() !== owner.runtime ||
        owner.runtime.nativeSnapshot().nativeGeneration != owner.nativeGeneration
    ) {
        return
    }
    container.diagnosticsLogger.recordStructured("dns", "native validation audit", "audit_read=failed")
}

internal fun collectValidationAudit(
    isCurrentOwner: () -> Boolean,
    drain: (Int) -> String?,
    retainCritical: (GuardEvent) -> Boolean,
): ValidationAuditObservation? {
    if (!isCurrentOwner()) return null
    val source = ordinaryValidationAuditAttempt { drain(VALIDATION_AUDIT_MAX_EVENTS) }
        .getOrElse { return ValidationAuditObservation("read_failed") }
        ?: return ValidationAuditObservation("unavailable")
    val envelope = ordinaryValidationAuditAttempt { Json.parseToJsonElement(source).jsonObject }.getOrNull()
    if (envelope?.get("events") !is JsonArray) return ValidationAuditObservation("invalid")
    val events = parseRuntimeAuditDrain(source)
    val summary = summarizeValidationAudit(events, retainCritical)
    return if (isCurrentOwner()) ValidationAuditObservation("drained", summary) else null
}

internal fun summarizeValidationAudit(
    drain: RuntimeAuditDrain,
    retainCritical: (GuardEvent) -> Boolean,
): ValidationAuditSummary {
    val blocked = linkedMapOf<Pair<String, String>, Int>()
    drain.events.forEach { event ->
        when (event) {
            is RuntimeAuditEvent.Blocked -> {
                val reason = event.reason.takeIf { it in VALIDATION_AUDIT_REASONS } ?: "unknown"
                val transport = event.transport.takeIf { it == "tcp" || it == "udp" } ?: "unknown"
                val key = reason to transport
                blocked[key] = blocked.getOrDefault(key, 0) + 1
            }
            else -> Unit
        }
    }
    val journalNotRetained = validationAuditGuardEvents(drain).count { event ->
        !ordinaryValidationAuditAttempt { retainCritical(event) }.getOrDefault(false)
    }
    return ValidationAuditSummary(drain.events.size, drain.dropped, journalNotRetained, blocked)
}

internal fun validationAuditGuardEvents(drain: RuntimeAuditDrain): List<GuardEvent> = buildList {
    drain.events.filterIsInstance<RuntimeAuditEvent.Blocked>()
        .groupBy { event -> Triple(event.reason, event.transport, event.packageName) }
        .forEach { (key, blocked) ->
            add(
                GuardEvent(
                    type = GuardEventType.CORE_FLOW_BLOCKED,
                    packageName = key.third,
                    uid = blocked.firstOrNull()?.uid,
                    detail = "reason=${key.first} transport=${key.second} count=${blocked.size}",
                )
            )
        }
    drain.events.filterIsInstance<RuntimeAuditEvent.DnsBlocked>()
        .groupBy { event -> Triple(event.reason, event.category?.name, event.packageName) }
        .forEach { (key, blocked) ->
            add(
                GuardEvent(
                    type = GuardEventType.CORE_DNS_BLOCKED,
                    packageName = key.third,
                    detail = "reason=${key.first ?: "unknown"} category=${key.second ?: "none"} count=${blocked.size}",
                )
            )
        }
    drain.events.mapNotNull(::ungroupedRuntimeAuditGuardEvent).forEach(::add)
    if (drain.dropped > 0L) {
        add(GuardEvent(GuardEventType.CORE_EVENT_GAP, detail = "audit_dropped=${drain.dropped} traffic_dropped=0"))
    }
}

internal const val VALIDATION_AUDIT_MAX_EVENTS = 128

private val VALIDATION_AUDIT_REASONS = setOf(
    "kill_switch", "policy", "unattributed", "flow_limit", "dns_blocklist", "dns_overlay_gate", "udp_unsupported",
    "continuity_held", "tunnel_address_mismatch", "tunnel_family_unmapped", "dns_encrypted_bypass",
    "tunnel_fake_ip_unroutable", "tunnel_socket_unavailable",
)

private inline fun <T> ordinaryValidationAuditAttempt(action: () -> T): Result<T> =
    runCatching(action).onFailure { error ->
        if (error is CancellationException || error !is Exception) throw error
    }
