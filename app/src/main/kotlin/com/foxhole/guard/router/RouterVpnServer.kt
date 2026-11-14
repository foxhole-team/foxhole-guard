package com.foxhole.guard.router

import kotlinx.serialization.json.*

internal data class RouterHardwareInfo(val firmwareVersion: String?, val model: String?, val architecture: String?, val lanAddress: String? = null)
internal data class RouterVpnServerPlan(
    val action: String, val available: Boolean, val revision: Long, val planSha256: String,
    val downloadBytes: Long?, val componentVersion: String?, val ownerPairingId: String? = null,
    val removalAvailable: Boolean = false,
)
internal data class RouterVpnServerJob(val jobId: String, val state: String, val progressPercent: Int, val failureReason: String?) {
    val running: Boolean get() = state !in setOf("SUCCEEDED", "FAILED", "RECOVERY_REQUIRED")
    val succeeded: Boolean get() = state == "SUCCEEDED"
}
internal data class RouterVpnServerControlState(val plan: RouterVpnServerPlan? = null, val job: RouterVpnServerJob? = null, val ownerPairingId: String? = null, val requestedEnabled: Boolean? = null) {
    val requiresRecovery: Boolean get() = job?.state == "RECOVERY_REQUIRED"
    val running: Boolean get() = job?.running == true
}

internal fun parseRouterHardwareInfo(value: JsonElement?): RouterHardwareInfo? {
    val obj = value as? JsonObject ?: return null
    if ((obj["schema"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull != 1) return null
    fun field(name: String): String? = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 && it.none { c -> c.code < 32 || c.code == 127 } }
    return RouterHardwareInfo(field("firmwareVersion"), field("model"), field("architecture"), routerLiteralLanAddress((obj["lanAddress"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull))
}

private fun invalidServerResponse(): Nothing = throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
private fun JsonObject.serverNumber(key: String): Long = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    ?.takeIf { it in 0..(64L * 1024 * 1024 * 1024) } ?: invalidServerResponse()
private fun JsonObject.serverText(key: String, max: Int = 128): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    ?.takeIf { it.length in 1..max && it.none { c -> c.code < 32 || c.code == 127 } } ?: invalidServerResponse()
private fun JsonObject.serverBool(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: invalidServerResponse()

internal fun parseRouterVpnServerPlan(value: JsonObject): RouterVpnServerPlan {
    if (value.serverNumber("schema") != 1L) invalidServerResponse()
    val action = value.serverText("action")
    if (action !in setOf("reuse", "install", "unavailable")) invalidServerResponse()
    val available = value.serverBool("available")
    val sha = value.serverText("planSha256", 64).takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: invalidServerResponse()
    val bytes = value["downloadBytes"]?.takeIf { it != JsonNull }?.let { value.serverNumber("downloadBytes") }
    val component = value["component"]?.takeIf { it != JsonNull }?.let { it as? JsonObject ?: invalidServerResponse() }
    if (available != (action != "unavailable") || action == "reuse" && bytes != 0L ||
        action == "install" && (bytes == null || bytes <= 0L) || action == "unavailable" && bytes != null) invalidServerResponse()
    val version = component?.let {
        if (it.serverText("protocol") != "hysteria2" || it.serverText("kind") !in setOf(
                "verified-legacy-server", "verified-managed-server", "verified-server-package")) invalidServerResponse()
        if (!it.serverText("binarySha256", 64).matches(Regex("[a-f0-9]{64}")) || it.serverNumber("binaryBytes") !in 1..64L*1024*1024) invalidServerResponse()
        it.serverText("version", 64)
    }
    if (available && component == null || !available && component != null) invalidServerResponse()
    val removable = value["removalAvailable"]?.let { value.serverBool("removalAvailable") } ?: false
    if (removable && (action != "reuse" || component?.get("kind")?.let { (it as? JsonPrimitive)?.contentOrNull } != "verified-managed-server")) invalidServerResponse()
    return RouterVpnServerPlan(action, available, value.serverNumber("revision"), sha, bytes, version, removalAvailable = removable)
}

internal fun parseRouterVpnServerJob(value: JsonObject): RouterVpnServerJob {
    if (value.serverNumber("schema") != 1L) invalidServerResponse()
    val id = value.serverText("jobId", 96).takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: invalidServerResponse()
    val state = value.serverText("state", 48).takeIf { it in setOf(
        "QUEUED", "DOWNLOADING", "VERIFYING", "INSTALLING", "ACTIVATING", "SUCCEEDED", "FAILED", "RECOVERY_REQUIRED") } ?: invalidServerResponse()
    val progress = value.serverNumber("progressPercent").takeIf { it <= 100 }?.toInt() ?: invalidServerResponse()
    val reason = value["failureReason"]?.takeIf { it != JsonNull }?.let { value.serverText("failureReason") }
    return RouterVpnServerJob(id, state, progress, reason)
}

internal fun routerVpnServerEnabled(snapshot: RouterControlSnapshot?): Boolean? =
    (snapshot?.settings?.get("vpnServerEnabled") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal fun routerVpnServerCapability(value: JsonElement?): Pair<Boolean, Boolean>? {
    if (value == null || value == JsonNull) return null
    val obj = value as? JsonObject ?: invalidServerResponse()
    if (obj.serverNumber("schema") != 1L) invalidServerResponse()
    return obj.serverBool("enabled") to obj.serverBool("available")
}
