package com.foxhole.guard.router

import java.util.Locale
import kotlinx.serialization.json.JsonObject

internal enum class RouterUpdateAvailability { NOT_CHECKED, CURRENT, AVAILABLE, UNSUPPORTED, CHECK_FAILED }
internal enum class RouterUpdateOperation { CHECK, APPLY }
internal enum class RouterUpdateStep {
    PREPARING, CHECKING_RELEASE, DOWNLOADING_INDEX, DOWNLOADING_PACKAGE, VERIFYING_PACKAGE, BACKING_UP,
    INSTALLING_PACKAGE, CHECKING_PACKAGE, RECONNECTING_VPN, COMPLETED, FAILED, RECOVERY_REQUIRED,
}
internal enum class RouterUpdateFailureReason {
    UNSUPPORTED_TARGET, NOT_CHECKED, RELEASE_UNAVAILABLE, AUTHENTICATION_FAILED, STALE_PLAN, STALE_SESSION,
    BUSY, NO_SPACE, DOWNLOAD_FAILED, PACKAGE_VERIFICATION_FAILED, DOWNGRADE_REJECTED, VERSION_IDENTITY_CONFLICT,
    ROLLBACK_UNAVAILABLE, RAM_STAGE_UNAVAILABLE, ROUTER_UNAVAILABLE, RECOVERY_REQUIRED, INVALID_REQUEST, LEGACY_CONNECTION_ACTIVE,
}
internal class RouterUpdateFailure(val reason: RouterUpdateFailureReason) : RouterApiFailure(RouterApiError.UNAVAILABLE)
internal fun routerUpdateFailure(code: String): RouterUpdateFailure = RouterUpdateFailure(
    RouterUpdateFailureReason.entries.singleOrNull { "update_" + it.name.lowercase(Locale.ROOT) == code } ?: invalidEngineResponse())

internal data class RouterUpdateAuto(val enabled: Boolean, val intervalSeconds: Long,
    val lastAttemptAtUnixMs: Long?, val nextCheckAtUnixMs: Long?, val suspendedReason: RouterUpdateFailureReason?)
internal data class RouterUpdateModule(val protocol: RouterInstallProtocol, val storage: RouterEngineStorage, val version: String)
internal data class RouterUpdateProgress(val operation: RouterUpdateOperation, val automatic: Boolean,
    val state: RouterEngineJobState, val step: RouterUpdateStep, val progressPercent: Int, val failureReason: RouterUpdateFailureReason?) {
    val running: Boolean get() = !state.terminal
}
internal data class RouterUpdateJobRef(val jobId: String, val planId: String?, val operation: RouterUpdateOperation,
    val state: RouterEngineJobState, val step: RouterUpdateStep, val progressPercent: Int)
internal data class RouterUpdateJob(val routerId: String, val jobId: String, val planId: String?,
    val progress: RouterUpdateProgress, val revision: Long, val previousConnectionRestored: Boolean?) {
    val running: Boolean get() = progress.running
}
internal data class RouterUpdateStatus(val supported: Boolean, val routerId: String, val revision: Long,
    val currentVersion: String?, val availableVersion: String?, val availability: RouterUpdateAvailability,
    val lastCheckedAtUnixMs: Long?, val failureReason: RouterUpdateFailureReason?, val busy: Boolean,
    val autoUpdate: RouterUpdateAuto, val installedModules: List<RouterUpdateModule>,
    val activeOwnJob: RouterUpdateJobRef?, val lastOwnJob: RouterUpdateJobRef?, val activeAutomaticJob: RouterUpdateProgress?)
internal data class RouterUpdateChange(val id: String, val fromVersion: String, val toVersion: String, val storage: RouterEngineStorage?)
internal data class RouterUpdatePlan(val routerId: String, val revision: Long, val planId: String, val planSha256: String,
    val fromVersion: String, val toVersion: String, val protocols: Set<RouterInstallProtocol>, val changes: List<RouterUpdateChange>,
    val impact: RouterEngineImpact, val expiresAtUptimeMs: Long, val bootId: String, val budget: RouterEngineBudget)

// Kept until an acknowledged or discovered job proves which write the router accepted.
internal data class RouterUpdatePending(val operation: RouterUpdateOperation, val planId: String?, val previousJobId: String?) {
    fun matches(ref: RouterUpdateJobRef): Boolean = ref.operation == operation &&
        if (operation == RouterUpdateOperation.APPLY) ref.planId == planId else ref.jobId != previousJobId
}
internal data class RouterUpdateControlState(val supported: Boolean? = null, val status: RouterUpdateStatus? = null,
    val plan: RouterUpdatePlan? = null, val job: RouterUpdateJob? = null, val failure: RouterUpdateFailureReason? = null,
    val pending: RouterUpdatePending? = null, val reconnecting: Boolean = false, val unreachable: Boolean = false,
    val pollEpoch: Long = 0) {
    val requiresVpnStop: Boolean get() = failure == RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE ||
        status?.failureReason == RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE ||
        status?.autoUpdate?.suspendedReason == RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE
    val running: Boolean get() = pending != null || reconnecting || job?.running == true || status?.busy == true ||
        status?.activeAutomaticJob?.running == true
    val canCheck: Boolean get() = supported == true && status?.supported == true && !running
    val canApply: Boolean get() {
        val current = status ?: return false
        val prepared = plan ?: return false
        return canCheck && current.availability == RouterUpdateAvailability.AVAILABLE && current.failureReason == null &&
            job?.progress?.state != RouterEngineJobState.RECOVERY_REQUIRED &&
            current.autoUpdate.suspendedReason !in setOf(RouterUpdateFailureReason.RECOVERY_REQUIRED,
                RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE) && prepared.routerId == current.routerId && prepared.revision == current.revision
    }
    val canSetAuto: Boolean get() = canCheck
}

internal fun requireUpdateId(value: String, kind: String) {
    if (!value.matches(Regex("update-$kind-[a-f0-9]{32}"))) invalidEngineResponse()
}
internal fun JsonObject.updateId(name: String, kind: String): String = engineText(name).also { requireUpdateId(it, kind) }
private fun JsonObject.updateFailure(name: String): RouterUpdateFailureReason? =
    engineNullableText(name)?.let { engineEnumValue<RouterUpdateFailureReason>(it) }
private fun JsonObject.updateModuleProtocol(): RouterInstallProtocol = engineProtocol("id").also {
    if (it !in setOf(RouterInstallProtocol.VLESS, RouterInstallProtocol.HYSTERIA2, RouterInstallProtocol.WIREGUARD)) invalidEngineResponse()
}

internal fun parseRouterUpdateStatus(value: JsonObject): RouterUpdateStatus {
    value.engineSchema()
    val supported = value.engineBoolean("supported")
    val availability = value.engineEnum<RouterUpdateAvailability>("availability")
    val current = value.engineNullableText("currentVersion", 96)
    val available = value.engineNullableText("availableVersion", 96)
    val failure = value.updateFailure("failureReason")
    if (supported && current == null || !supported && availability != RouterUpdateAvailability.UNSUPPORTED ||
        supported && availability == RouterUpdateAvailability.UNSUPPORTED ||
        availability in setOf(RouterUpdateAvailability.CURRENT, RouterUpdateAvailability.AVAILABLE) && available == null ||
        availability == RouterUpdateAvailability.CURRENT && current != available ||
        availability == RouterUpdateAvailability.CHECK_FAILED && failure == null ||
        availability == RouterUpdateAvailability.CURRENT && failure != null ||
        availability == RouterUpdateAvailability.AVAILABLE && failure != null && failure != RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE) invalidEngineResponse()
    val auto = value.engineNullableObject("autoUpdate") ?: invalidEngineResponse()
    val suspended = auto.updateFailure("suspendedReason")
    if (auto.engineNumber("intervalSeconds") != 86400L || suspended != null && suspended !in setOf(
            RouterUpdateFailureReason.RECOVERY_REQUIRED, RouterUpdateFailureReason.UNSUPPORTED_TARGET,
            RouterUpdateFailureReason.ROLLBACK_UNAVAILABLE, RouterUpdateFailureReason.RAM_STAGE_UNAVAILABLE,
            RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE)) invalidEngineResponse()
    val modules = value.engineObjects("installedModules", 3).map {
        RouterUpdateModule(it.updateModuleProtocol(), it.engineEnum("storage"), it.engineText("version", 96)) }
    if (modules.map { it.protocol }.distinct().size != modules.size) invalidEngineResponse()
    val active = value.engineNullableObject("activeOwnJob")?.let(::parseRouterUpdateJobRef)
    val last = value.engineNullableObject("lastOwnJob")?.let(::parseRouterUpdateJobRef)
    if (active?.state?.terminal == true) invalidEngineResponse()
    val automatic = value.engineNullableObject("activeAutomaticJob")?.let { parseUpdateProgress(it, true) }
    if (automatic?.running == false) invalidEngineResponse()
    return RouterUpdateStatus(supported, value.engineHash("routerId"), value.engineNumber("revision"), current, available,
        availability, value.engineNullableNumber("lastCheckedAtUnixMs"), failure, value.engineBoolean("busy"),
        RouterUpdateAuto(auto.engineBoolean("enabled"), 86400, auto.engineNullableNumber("lastAttemptAtUnixMs"),
            auto.engineNullableNumber("nextCheckAtUnixMs"), suspended), modules, active, last, automatic)
}

internal fun parseRouterUpdatePlan(value: JsonObject, status: RouterUpdateStatus): RouterUpdatePlan {
    value.engineSchema()
    val from = value.engineText("fromVersion", 96)
    val to = value.engineText("toVersion", 96)
    if (!status.supported || status.busy || status.failureReason != null ||
        status.autoUpdate.suspendedReason == RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE || status.availability != RouterUpdateAvailability.AVAILABLE ||
        value.engineHash("routerId") != status.routerId || value.engineNumber("revision") != status.revision ||
        from != status.currentVersion || to != status.availableVersion || !value.engineBoolean("confirmationRequired")) invalidEngineResponse()
    val protocols = engineStrings(value["engineIds"], 3).map(::engineProtocol)
    if (protocols.distinct().size != protocols.size || protocols.toSet() != status.installedModules.map { it.protocol }.toSet()) invalidEngineResponse()
    val changes = value.engineObjects("changes", 4).map { change ->
        val id = change.engineText("id")
        val storage = change.engineNullableText("storage")?.let { engineEnumValue<RouterEngineStorage>(it) }
        val old = change.engineText("fromVersion", 96)
        val target = change.engineText("toVersion", 96)
        if (old == target) invalidEngineResponse()
        if (id == "foxhole") {
            if (storage != null || old != from || target != to) invalidEngineResponse()
        } else {
            val module = status.installedModules.singleOrNull { it.protocol.canonical == id } ?: invalidEngineResponse()
            if (storage != module.storage || old != module.version) invalidEngineResponse()
        }
        RouterUpdateChange(id, old, target, storage)
    }
    if (changes.isEmpty() || changes.map { it.id }.distinct().size != changes.size) invalidEngineResponse()
    val baseChanged = changes.any { it.id == "foxhole" }
    val impact = value.engineEnum<RouterEngineImpact>("impact")
    if (baseChanged && impact != RouterEngineImpact.RECONNECT_REQUIRED || !baseChanged && from != to) invalidEngineResponse()
    val budget = value.engineNullableObject("budget") ?: invalidEngineResponse()
    val keys = setOf("downloadBytes", "stagingBytes", "flashRequiredBytes", "flashAvailableBytes", "ramRequiredBytes", "ramAvailableBytes", "reservedBytes")
    if (budget.keys != keys) invalidEngineResponse()
    return RouterUpdatePlan(status.routerId, status.revision, value.updateId("planId", "plan"), value.engineHash("planSha256"),
        from, to, protocols.toSet(), changes, impact, value.engineNumber("expiresAtUptimeMs"), value.engineId("bootId"),
        RouterEngineBudget(budget.engineNumber("downloadBytes"), budget.engineNumber("stagingBytes"),
            budget.engineNumber("flashRequiredBytes"), budget.engineNumber("flashAvailableBytes"),
            budget.engineNumber("ramRequiredBytes"), budget.engineNumber("ramAvailableBytes"), budget.engineNumber("reservedBytes")))
}

private fun parseUpdateProgress(value: JsonObject, automatic: Boolean): RouterUpdateProgress {
    if (value.engineBoolean("automatic") != automatic) invalidEngineResponse()
    val state = value.engineEnum<RouterEngineJobState>("state")
    val step = value.engineEnum<RouterUpdateStep>("step")
    val percent = value.engineNumber("progressPercent").also { if (it > 100) invalidEngineResponse() }.toInt()
    val failure = value.updateFailure("failureReason")
    if (state in setOf(RouterEngineJobState.FAILED, RouterEngineJobState.RECOVERY_REQUIRED) && failure == null ||
        state == RouterEngineJobState.SUCCEEDED && (failure != null || step != RouterUpdateStep.COMPLETED || percent != 100) ||
        state == RouterEngineJobState.FAILED && step != RouterUpdateStep.FAILED ||
        state == RouterEngineJobState.RECOVERY_REQUIRED && step != RouterUpdateStep.RECOVERY_REQUIRED) invalidEngineResponse()
    return RouterUpdateProgress(value.engineEnum("operation"), automatic, state, step, percent, failure)
}
internal fun parseRouterUpdateJobRef(value: JsonObject): RouterUpdateJobRef {
    if (value.engineBoolean("automatic")) invalidEngineResponse()
    val operation = value.engineEnum<RouterUpdateOperation>("operation")
    val plan = value.engineNullableText("planId")?.also { requireUpdateId(it, "plan") }
    if ((operation == RouterUpdateOperation.CHECK) != (plan == null)) invalidEngineResponse()
    return RouterUpdateJobRef(value.updateId("jobId", "job"), plan, operation, value.engineEnum("state"), value.engineEnum("step"),
        value.engineNumber("progressPercent").also { if (it > 100) invalidEngineResponse() }.toInt())
}
internal fun parseRouterUpdateJob(value: JsonObject, routerId: String, expected: RouterUpdateJobRef? = null): RouterUpdateJob {
    value.engineSchema()
    val ref = parseRouterUpdateJobRef(value)
    if (value.engineHash("routerId") != routerId || expected != null &&
        (ref.jobId != expected.jobId || ref.planId != expected.planId || ref.operation != expected.operation)) invalidEngineResponse()
    return RouterUpdateJob(routerId, ref.jobId, ref.planId, parseUpdateProgress(value, false), value.engineNumber("revision"),
        value.engineNullableBoolean("previousConnectionRestored"))
}
