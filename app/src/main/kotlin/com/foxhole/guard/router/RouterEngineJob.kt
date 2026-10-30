package com.foxhole.guard.router

import kotlinx.serialization.json.JsonObject

internal enum class RouterEngineJobState {
    QUEUED, DOWNLOADING, VERIFYING, INSTALLING, CHECKING, RECONNECTING, SUCCEEDED, FAILED, RECOVERY_REQUIRED;
    val terminal: Boolean get() = this in setOf(SUCCEEDED, FAILED, RECOVERY_REQUIRED)
}
internal enum class RouterEngineStep {
    PREPARING, DOWNLOADING_INDEX, DOWNLOADING_PACKAGE, VERIFYING_PACKAGE, INSTALLING_PACKAGE,
    CHECKING_ENGINE, SWITCHING_ENGINE, RECONNECTING_VPN, COMPLETED, FAILED, RECOVERY_REQUIRED,
}
internal enum class RouterEngineFailureReason {
    UNKNOWN_PROTOCOL, NOT_RELEASED, UNSUPPORTED_TARGET, UNSUPPORTED_PROFILE_GRAPH, STALE_PLAN, STALE_SESSION,
    ROUTER_UNAVAILABLE, BUSY, NO_SPACE, AUTHENTICATION_FAILED, DOWNLOAD_FAILED, PACKAGE_VERIFICATION_FAILED,
    CANDIDATE_CHECK_FAILED, RECONNECT_FAILED, RECOVERY_REQUIRED, INVALID_REQUEST, IN_USE,
}
internal class RouterEngineFailure(val reason: RouterEngineFailureReason) : RouterApiFailure(RouterApiError.UNAVAILABLE)

internal fun routerEngineFailure(error: String): RouterEngineFailure {
    val reason = RouterEngineFailureReason.entries.singleOrNull { "engine_" + it.name.lowercase(java.util.Locale.ROOT) == error }
        ?: invalidEngineResponse()
    return RouterEngineFailure(reason)
}

internal data class RouterEngineJobRef(val jobId: String, val planId: String, val state: RouterEngineJobState,
    val progressPercent: Int, val step: RouterEngineStep)

internal data class RouterEngineJob(val routerId: String, val jobId: String, val planId: String,
    val state: RouterEngineJobState, val progressPercent: Int, val step: RouterEngineStep, val revision: Long,
    val failureReason: RouterEngineFailureReason?, val previousConnectionRestored: Boolean?) {
    val running: Boolean get() = !state.terminal
}

internal fun parseRouterEngineJobRef(value: JsonObject): RouterEngineJobRef = RouterEngineJobRef(
    value.engineId("jobId"), value.engineId("planId"), value.engineEnum("state"), engineProgress(value), value.engineEnum("step"))

internal fun parseRouterEngineJob(value: JsonObject, routerId: String, planId: String? = null,
    jobId: String? = null): RouterEngineJob {
    value.engineSchema()
    val actualRouter = value.engineId("routerId")
    val actualPlan = value.engineId("planId")
    val actualJob = value.engineId("jobId")
    if (actualRouter != routerId || planId != null && planId != actualPlan || jobId != null && jobId != actualJob) invalidEngineResponse()
    val state = value.engineEnum<RouterEngineJobState>("state")
    val failure = value.engineNullableText("failureReason")?.let { engineEnumValue<RouterEngineFailureReason>(it) }
    if (state in setOf(RouterEngineJobState.FAILED, RouterEngineJobState.RECOVERY_REQUIRED) && failure == null ||
        state == RouterEngineJobState.SUCCEEDED && failure != null) invalidEngineResponse()
    return RouterEngineJob(actualRouter, actualJob, actualPlan, state, engineProgress(value), value.engineEnum("step"),
        value.engineNumber("revision"), failure, value.engineNullableBoolean("previousConnectionRestored"))
}

private fun engineProgress(value: JsonObject): Int = value.engineNumber("progressPercent").also {
    if (it > 100) invalidEngineResponse()
}.toInt()
